package com.structurizr.export.plantuml;

import com.structurizr.Workspace;
import com.structurizr.export.Diagram;
import com.structurizr.util.ImageUtils;
import com.structurizr.view.ThemeUtils;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PlantUMLImageTests {

    private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"120\" height=\"60\">"
            + "<rect x=\"20\" y=\"10\" width=\"80\" height=\"40\" fill=\"#ff0000\"/></svg>";

    @TempDir
    Path directory;

    static Stream<String> icons() throws Exception {
        return Stream.of(raster("png"), raster("jpeg"), ImageUtils.getSvgAsDataUri(SVG),
                "data:image/svg+xml;utf8," + SVG.replace("<", "%3C").replace(">", "%3E").replace("#", "%23"));
    }

    private static String raster(String format) throws Exception {
        BufferedImage image = new BufferedImage(120, 60, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, bytes));
        return "data:image/" + format + ";base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    @ParameterizedTest
    @MethodSource("icons")
    void embeddedIconsAreScaledAndIncludedInBothExporters(String icon) throws Exception {
        assertEquals(0.5, PlantUMLImage.scale(icon, 60));
        Workspace workspace = new Workspace("Icons", "");
        var system = workspace.getModel().addSoftwareSystem("System");
        var view = workspace.getViews().createSystemContextView(system, "icons", "");
        view.addAllElements();
        workspace.getViews().getConfiguration().getStyles().addElementStyle("Software System").icon(icon).fontSize(24);
        workspace.getViews().getConfiguration().addProperty("c4plantuml.tags", "true");

        String reference = PlantUMLImage.reference(icon);
        var structurizrExporter = new StructurizrPlantUMLExporter();
        structurizrExporter.setPreserveSvgIcons(true);
        var c4Exporter = new C4PlantUMLExporter();
        c4Exporter.setPreserveSvgIcons(true);
        Diagram diagram = structurizrExporter.export(view);
        assertTrue(diagram.getDefinition().contains("<img:" + reference + "{scale=0.6}>"));
        assertTrue(diagram.getLegend().getDefinition().contains("<img:" + reference + "{scale=0.6}>"));
        assertTrue(c4Exporter.export(view).getDefinition().contains("$sprite=\"img:" + reference + "{scale=0.25}\""));
    }

    @ParameterizedTest
    @MethodSource("icons")
    void themeFilesResolveLocalIconsBeforeExport(String icon) throws Exception {
        String reference = PlantUMLImage.reference(icon);
        String extension = reference.startsWith("data:image/svg") ? "svg" : reference.startsWith("data:image/png") ? "png" : "jpg";
        Files.write(directory.resolve("icon." + extension), Base64.getDecoder().decode(reference.split(",", 2)[1]));
        Path theme = directory.resolve("theme.json");
        Files.writeString(theme, "{\"elements\":[{\"tag\":\"Software System\",\"icon\":\"icon." + extension + "\"}]}");
        Workspace workspace = new Workspace("Icons", "");
        var system = workspace.getModel().addSoftwareSystem("System");
        var view = workspace.getViews().createSystemContextView(system, "icons", "");
        view.addAllElements();
        ThemeUtils.inlineAllStylesFromTheme(workspace, theme.toFile());
        assertTrue(new StructurizrPlantUMLExporter().export(view).getDefinition().contains("<img:data:image/"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "width='120' height='60'|0.5",
            "width='120px' height='60px'|0.5",
            "viewBox='0 0 120 60'|0.5",
            "viewBox='-10,-20,120,60'|0.5",
            "width='240' viewBox='0 0 120 60'|0.25",
            "height='120' viewBox='0 0 120 60'|0.25",
            "width='100%' height='100%' viewBox='0 0 120 60'|0.5",
            "width='1in' height='36pt'|0.625",
            "width='2.54cm' height='12.7mm'|0.625",
            "width='6pc' height='50.8Q'|0.625"
    })
    void svgDimensionsSupportUnitsAndViewBox(String attributes, double scale) throws Exception {
        assertEquals(scale, PlantUMLImage.scale(ImageUtils.getSvgAsDataUri("<svg " + attributes + "/>"), 60), 0.00001);
    }

    @Test
    void rawSvgIsEncodedWithoutChangingPlusSignsOrArtwork() {
        String svg = SVG.replace("</svg>", "<text>A+B</text></svg>");
        String reference = PlantUMLImage.reference("data:image/svg+xml;utf8," + svg);
        assertEquals(svg, new String(Base64.getDecoder().decode(reference.split(",", 2)[1]), StandardCharsets.UTF_8));
    }

    @Test
    void rasterisationPreservesAspectRatioTransparencyAndColour() throws Exception {
        String reference = PlantUMLImage.rasterise(ImageUtils.getSvgAsDataUri(SVG), 60);
        assertTrue(reference.startsWith(ImageUtils.DATA_URI_IMAGE_PNG));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(reference.split(",", 2)[1])));
        assertEquals(240, image.getWidth());
        assertEquals(120, image.getHeight());
        assertEquals(0, image.getRGB(0, 0) >>> 24);
        assertEquals(0xffff0000, image.getRGB(60, 30));
        assertEquals(0.25, PlantUMLImage.scale(reference, 60));
    }

    @Test
    void rasterisationLeavesRasterIconsUnchanged() throws Exception {
        for (String format : new String[] {"png", "jpeg"}) {
            String icon = raster(format);
            assertEquals(icon, PlantUMLImage.rasterise(icon, 60));
        }
    }

    @Test
    void bothExportersRasteriseSvgIconsByDefault() throws Exception {
        Workspace workspace = new Workspace("Icons", "");
        var system = workspace.getModel().addSoftwareSystem("System");
        var view = workspace.getViews().createSystemContextView(system, "icons", "");
        view.addAllElements();
        workspace.getViews().getConfiguration().getStyles().addElementStyle("Software System").icon(ImageUtils.getSvgAsDataUri(SVG)).fontSize(24);
        workspace.getViews().getConfiguration().addProperty("c4plantuml.tags", "true");
        for (var exporter : new AbstractPlantUMLExporter[] {new StructurizrPlantUMLExporter(), new C4PlantUMLExporter()}) {
            Diagram diagram = exporter.export(view);
            var matcher = java.util.regex.Pattern.compile("img:data:image/png;base64,([A-Za-z0-9+/=]+)\\{scale=0.25}").matcher(diagram.getDefinition());
            assertTrue(matcher.find());
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(matcher.group(1))));
            assertEquals(exporter instanceof C4PlantUMLExporter ? 120 : 288, image.getWidth());
            assertEquals(image.getWidth() / 2, image.getHeight());
            assertEquals(0, image.getRGB(0, 0) >>> 24);
            assertEquals(0xffff0000, image.getRGB(image.getWidth() / 2, image.getHeight() / 2));
            if (diagram.getLegend() != null) {
                assertTrue(diagram.getLegend().getDefinition().contains(matcher.group(1)));
            }
            exporter.setPreserveSvgIcons(true);
            assertTrue(exporter.export(view).getDefinition().contains("img:data:image/svg+xml;base64,"));
        }
    }

    @Test
    void httpIconsAreDetectedByContentRatherThanFilename() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/icon", exchange -> {
            byte[] bytes = SVG.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "image/svg+xml");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/icon?version=1";
            assertEquals(0.5, PlantUMLImage.scale(url, 60));
            assertEquals(url, PlantUMLImage.reference(url));
            assertTrue(PlantUMLImage.rasterise(url, 60).startsWith(ImageUtils.DATA_URI_IMAGE_PNG));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rasterisationDoesNotFetchExternalSvgResources() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/external", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            String doctype = "<!DOCTYPE svg SYSTEM 'http://127.0.0.1:" + server.getAddress().getPort() + "/external'>";
            assertTrue(PlantUMLImage.rasterise(ImageUtils.getSvgAsDataUri(doctype + SVG), 60).startsWith(ImageUtils.DATA_URI_IMAGE_PNG));
            assertEquals(0, requests.get());
            String svg = SVG.replace("</svg>", "<image xmlns:xlink='http://www.w3.org/1999/xlink' xlink:href='http://127.0.0.1:"
                    + server.getAddress().getPort() + "/external' width='20' height='20'/></svg>");
            assertThrows(Exception.class, () -> PlantUMLImage.rasterise(ImageUtils.getSvgAsDataUri(svg), 60));
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void invalidImagesUseFallbackScale() {
        var exporter = new StructurizrPlantUMLExporter();
        for (String icon : new String[] {"data:image/png;base64,broken", ImageUtils.getSvgAsDataUri("<svg width='0' height='0'/>"),
                ImageUtils.getSvgAsDataUri("<svg/>"), ImageUtils.getSvgAsDataUri("<html width='120' height='60'/>")}) {
            assertEquals(0.5, exporter.calculateIconScale(icon, 60));
        }
        assertFalse(exporter.isSupportedIcon("data:image/gif;base64,AAAA"));
    }

    @Test
    void xmlEntitiesAreNotExpanded() throws Exception {
        Path secret = directory.resolve("external.txt");
        Files.writeString(secret, "120");
        String svg = "<!DOCTYPE svg [<!ENTITY size SYSTEM '" + secret.toUri() + "'>]><svg width='&size;' height='60'/>";
        assertEquals(0.5, new StructurizrPlantUMLExporter().calculateIconScale(ImageUtils.getSvgAsDataUri(svg), 60));
        assertThrows(Exception.class, () -> PlantUMLImage.rasterise(ImageUtils.getSvgAsDataUri(svg), 60));
    }

    @Test
    void groupAndDeploymentIconsAreIncluded() {
        String icon = ImageUtils.getSvgAsDataUri(SVG);
        Workspace workspace = new Workspace("Icons", "");
        var system = workspace.getModel().addSoftwareSystem("System");
        system.setGroup("Group");
        workspace.getViews().getConfiguration().getStyles().addElementStyle("Group").icon(icon);
        workspace.getViews().getConfiguration().getStyles().addElementStyle("Deployment Node").icon(icon);
        var context = workspace.getViews().createSystemContextView(system, "context", "");
        context.addAllElements();
        assertTrue(new StructurizrPlantUMLExporter().export(context).getDefinition().contains("<img:" + ImageUtils.DATA_URI_IMAGE_PNG));
        workspace.getModel().addDeploymentNode("Server").add(system);
        var deployment = workspace.getViews().createDeploymentView("deployment", "");
        deployment.addDefaultElements();
        Diagram diagram = new StructurizrPlantUMLExporter().export(deployment);
        assertTrue(diagram.getDefinition().contains("<img:" + ImageUtils.DATA_URI_IMAGE_PNG));
        assertTrue(diagram.getLegend().getDefinition().contains("<img:" + ImageUtils.DATA_URI_IMAGE_PNG));
    }
}
