package com.structurizr.export.plantuml;

import com.structurizr.util.ImageUtils;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Element;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class PlantUMLImageRenderingTests {

    @ParameterizedTest
    @ValueSource(strings = {
            "width='120' height='60'",
            "width='120px' height='60px'",
            "viewBox='0 0 120 60'",
            "viewBox='-10,-20,120,60'",
            "width='240' viewBox='0 0 120 60'",
            "height='120' viewBox='0 0 120 60'",
            "width='100%' height='100%' viewBox='0 0 120 60'",
            "width='1in' height='0.5in'",
            "width='1in' height='36pt' viewBox='0 0 120 60'",
            "width='2.54cm' height='12.7mm'",
            "width='6pc' height='50.8Q'",
            "width='0.5' height='0.25'"
    })
    void dimensionsAndArtworkSurvivePlantUMLRendering(String attributes) throws Exception {
        String svg = "<svg xmlns='http://www.w3.org/2000/svg' " + attributes
                + "><rect x='-1000' y='-1000' width='2000' height='2000' fill='red'/></svg>";
        for (boolean preserveSvg : new boolean[] {false, true}) {
            var pixels = render(svg, preserveSvg);
            assertEquals(0xffff0000, pixels.getRGB(pixels.getWidth() / 2, pixels.getHeight() / 2));
        }
    }

    @Test
    void preservedSvgRetainsRootStylesTransformsAndInternalReferences() throws Exception {
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"
                     width="1in" height="0.5in" viewBox="10 20 120 60" fill="red">
                    <defs><rect id="artwork" width="80" height="40"/></defs>
                    <g transform="translate(30 30)"><use xlink:href="#artwork"/></g>
                </svg>
                """;
        var pixels = render(svg, true);
        assertEquals(0xffff0000, pixels.getRGB(pixels.getWidth() / 2, pixels.getHeight() / 2));
        assertEquals(0, pixels.getRGB(0, 0) >>> 24);
    }

    private BufferedImage render(String svg, boolean preserveSvg) throws Exception {
        var exporter = new StructurizrPlantUMLExporter();
        exporter.setPreserveSvgIcons(preserveSvg);
        String image = exporter.plantUMLImage(ImageUtils.getSvgAsDataUri(svg), 60);
        // Sequence diagrams render without an external Graphviz installation.
        String source = "@startuml\nAlice -> Bob: <" + image + ">\n@enduml";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertNotNull(new SourceStringReader(source).outputImage(output, new FileFormatOption(FileFormat.SVG)));
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(output.toByteArray()));
        var images = document.getElementsByTagNameNS("http://www.w3.org/2000/svg", "image");
        assertEquals(1, images.getLength());
        Element embedded = (Element) images.item(0);
        assertEquals(60, Double.parseDouble(embedded.getAttribute("width")), 0.25);
        assertEquals(30, Double.parseDouble(embedded.getAttribute("height")), 0.25);
        String reference = embedded.getAttributeNS("http://www.w3.org/1999/xlink", "href");
        assertTrue(reference.startsWith(preserveSvg ? ImageUtils.DATA_URI_IMAGE_SVG : ImageUtils.DATA_URI_IMAGE_PNG));
        // Decode the final embedded artwork, including PlantUML's SVG transformations.
        String png = PlantUMLImage.rasterise(reference, 60);
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(png.split(",", 2)[1])));
    }
}
