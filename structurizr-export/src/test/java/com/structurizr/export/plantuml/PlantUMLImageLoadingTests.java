package com.structurizr.export.plantuml;

import com.structurizr.Workspace;
import com.structurizr.util.ImageUtils;
import com.structurizr.view.SystemContextView;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import java.awt.AWTError;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PlantUMLImageLoadingTests {

    private SystemContextView view(String icon) {
        Workspace workspace = new Workspace("Icons", "");
        var system = workspace.getModel().addSoftwareSystem("System");
        var view = workspace.getViews().createSystemContextView(system, "icons", "");
        view.addAllElements();
        workspace.getViews().getConfiguration().getStyles().addElementStyle("Software System").icon(icon);
        workspace.getViews().getConfiguration().addProperty("c4plantuml.tags", "true");
        return view;
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 503})
    void unavailableIconsKeepTheirReferenceAndDoNotAbortExport(int status) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/icon", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/icon";
            var view = view(url);
            for (var exporter : new AbstractPlantUMLExporter[] {new StructurizrPlantUMLExporter(), new C4PlantUMLExporter()}) {
                for (boolean preserve : new boolean[] {false, true}) {
                    requests.set(0);
                    exporter.setPreserveSvgIcons(preserve);
                    var diagram = exporter.export(view);
                    assertTrue(diagram.getDefinition().contains("img:" + url + "{scale=0.5}"));
                    if (diagram.getLegend() != null) {
                        assertTrue(diagram.getLegend().getDefinition().contains("img:" + url + "{scale=0.5}"));
                    }
                    assertEquals(1, requests.get());
                }
            }
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpeg", "svg"})
    void iconsAreDownloadedOncePerDiagramIncludingItsLegend(String format) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (format.equals("svg")) {
            bytes.write("<svg xmlns='http://www.w3.org/2000/svg' width='120' height='60'/>".getBytes(StandardCharsets.UTF_8));
        } else {
            assertTrue(ImageIO.write(new BufferedImage(120, 60, BufferedImage.TYPE_INT_RGB), format, bytes));
        }
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/icon", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, bytes.size());
            try (var output = exchange.getResponseBody()) {
                output.write(bytes.toByteArray());
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/icon";
            var view = view(url);
            var secondSystem = view.getModel().addSoftwareSystem("Second system");
            view.add(secondSystem);
            for (var exporter : new AbstractPlantUMLExporter[] {new StructurizrPlantUMLExporter(), new C4PlantUMLExporter()}) {
                for (boolean preserve : new boolean[] {false, true}) {
                    requests.set(0);
                    exporter.setPreserveSvgIcons(preserve);
                    exporter.export(view);
                    assertEquals(1, requests.get());
                    exporter.export(view);
                    assertEquals(2, requests.get(), "A later diagram must refresh its icons");
                }
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void explicitC4SpriteDoesNotLoadTheUnusedIcon() throws Exception {
        String sprite = "mySprite";
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/icon", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            var view = view("http://127.0.0.1:" + server.getAddress().getPort() + "/icon");
            view.getViewSet().getConfiguration().getStyles().getElementStyle("Software System")
                    .addProperty(C4PlantUMLExporter.C4PLANTUML_SPRITE, sprite);
            String definition = new C4PlantUMLExporter().export(view).getDefinition();
            assertTrue(definition.contains("$sprite=\"" + sprite + "\""));
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }

    static Stream<Throwable> awtFailures() {
        return Stream.of(new UnsatisfiedLinkError("AWT native library unavailable"),
                new NoClassDefFoundError("AWT initialisation failed"), new AWTError("Display unavailable"),
                new UnsupportedOperationException("ImageIO unavailable"));
    }

    @ParameterizedTest
    @MethodSource("awtFailures")
    void missingAwtSupportDoesNotAbortExport(Throwable failure) throws Exception {
        // Simulate a native-build failure inside ImageIO, before the scale fallback runs.
        ImageReaderSpi provider = new ImageReaderSpi() {
            @Override
            public boolean canDecodeInput(Object source) {
                if (failure instanceof Error error) throw error;
                throw (RuntimeException) failure;
            }

            @Override
            public ImageReader createReaderInstance(Object extension) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String getDescription(Locale locale) {
                return "Simulated unavailable AWT";
            }
        };
        IIORegistry registry = IIORegistry.getDefaultInstance();
        registry.registerServiceProvider(provider);
        try {
            var readers = registry.getServiceProviders(ImageReaderSpi.class, true);
            readers.forEachRemaining(reader -> {
                if (reader != provider) registry.setOrdering(ImageReaderSpi.class, provider, reader);
            });
            String icon = ImageUtils.getPngAsDataUri(new byte[] {1, 2, 3});
            for (var exporter : new AbstractPlantUMLExporter[] {new StructurizrPlantUMLExporter(), new C4PlantUMLExporter()}) {
                assertTrue(exporter.export(view(icon)).getDefinition().contains("img:" + icon + "{scale=0.5}"));
            }
        } finally {
            registry.deregisterServiceProvider(provider);
        }
    }

    @Test
    void conversionErrorsIdentifyTheStyleWithoutDumpingTheImage() {
        String icon = ImageUtils.getSvgAsDataUri("<svg xmlns='http://www.w3.org/2000/svg' width='120' height='60'><");
        var error = assertThrows(IllegalArgumentException.class, () -> new StructurizrPlantUMLExporter().export(view(icon)));
        assertTrue(error.getMessage().contains("Software System"));
        assertFalse(error.getMessage().contains(icon));
    }
}
