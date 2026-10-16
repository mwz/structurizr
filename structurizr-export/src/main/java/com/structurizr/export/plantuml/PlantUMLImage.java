package com.structurizr.export.plantuml;

import com.structurizr.util.ImageUtils;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.w3c.dom.Document;

import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Prepares icons for PlantUML while retaining their aspect ratio. */
final class PlantUMLImage {

    private static final Pattern LENGTH = Pattern.compile("([+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)(px|in|cm|mm|pt|pc|Q)?");

    static String reference(String icon) {
        // Raw/percent-encoded XML contains quotes and brackets that would break PlantUML labels.
        if (icon.startsWith(ImageUtils.DATA_URI_IMAGE_SVG) && !isBase64(icon)) {
            return ImageUtils.DATA_URI_IMAGE_SVG + "base64," + Base64.getEncoder().encodeToString(decode(icon));
        }
        return icon;
    }

    static double scale(String icon, int maxSize) throws IOException, XMLStreamException {
        Dimensions dimensions = dimensions(read(icon));
        return maxSize / Math.max(dimensions.width(), dimensions.height());
    }

    static String rasterise(String icon, int maxSize) throws Exception {
        return prepare(icon, maxSize, false).reference();
    }

    static Prepared prepare(String icon, int maxSize, boolean preserveSvg) throws Exception {
        byte[] bytes = read(icon);
        Dimensions dimensions = dimensions(bytes);
        if (!dimensions.svg()) {
            return new Prepared(reference(icon), maxSize / Math.max(dimensions.width(), dimensions.height()));
        }

        Document document = svgDocument(bytes);
        var root = document.getDocumentElement();
        // Batik does not support Q, and PlantUML treats physical units as pixels.
        root.setAttribute("width", Double.toString(dimensions.width()));
        root.setAttribute("height", Double.toString(dimensions.height()));
        double scale = maxSize * 4.0 / Math.max(dimensions.width(), dimensions.height());
        if (preserveSvg) {
            // PlantUML replaces the outer SVG element and reads integer dimensions.
            // A pixel-sized wrapper keeps the original viewBox, units and root styles intact.
            var wrapper = document.createElementNS("http://www.w3.org/2000/svg", "svg");
            String width = Long.toString((long) Math.ceil(dimensions.width() * scale));
            String height = Long.toString((long) Math.ceil(dimensions.height() * scale));
            wrapper.setAttribute("width", width);
            wrapper.setAttribute("height", height);
            wrapper.setAttribute("viewBox", "0 0 " + width + " " + height);
            var group = document.createElementNS("http://www.w3.org/2000/svg", "g");
            var scaledGroup = document.createElementNS("http://www.w3.org/2000/svg", "g");
            scaledGroup.setAttribute("transform", "scale(" + scale + ")");
            document.replaceChild(wrapper, root);
            wrapper.appendChild(group);
            // PlantUML adds a transform to the first group; leave that group untransformed.
            group.appendChild(scaledGroup);
            scaledGroup.appendChild(root);
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter output = new StringWriter();
            transformer.transform(new DOMSource(wrapper), new StreamResult(output));
            return new Prepared(ImageUtils.getSvgAsDataUri(output.toString()), 0.25);
        }

        PNGTranscoder transcoder = new PNGTranscoder();
        transcoder.addTranscodingHint(PNGTranscoder.KEY_EXECUTE_ONLOAD, false);
        transcoder.addTranscodingHint(PNGTranscoder.KEY_ALLOW_EXTERNAL_RESOURCES, false);
        // Render at four times the display size for sharper icons, preserving transparency and aspect ratio.
        transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, (float) (dimensions.width() * scale));
        transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, (float) (dimensions.height() * scale));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        transcoder.transcode(new TranscoderInput(document), new TranscoderOutput(output));
        return new Prepared(ImageUtils.getPngAsDataUri(output.toByteArray()), 0.25);
    }

    private static Document svgDocument(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private static byte[] read(String icon) throws IOException {
        byte[] bytes;
        if (icon.startsWith(ImageUtils.DATA_URI_IMAGE_PREFIX)) {
            bytes = decode(icon);
        } else {
            try (InputStream stream = URI.create(icon).toURL().openStream()) {
                bytes = stream.readAllBytes();
            }
        }

        return bytes;
    }

    private static Dimensions dimensions(byte[] bytes) throws IOException, XMLStreamException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image != null) {
            return new Dimensions(image.getWidth(), image.getHeight(), false);
        }

        // ImageIO has no built-in SVG reader. Inspect the SVG root instead.
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("External XML resources are not supported");
        });
        XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
        try {
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                    if (!"svg".equals(reader.getLocalName())) {
                        throw new IOException("Expected an SVG image");
                    }
                    double width = length(reader.getAttributeValue(null, "width"));
                    double height = length(reader.getAttributeValue(null, "height"));
                    String viewBox = reader.getAttributeValue(null, "viewBox");
                    if ((!positive(width) || !positive(height)) && viewBox != null) {
                        String[] values = viewBox.trim().split("[\\s,]+");
                        if (values.length == 4) {
                            double viewBoxWidth = Double.parseDouble(values[2]);
                            double viewBoxHeight = Double.parseDouble(values[3]);
                            if (positive(viewBoxWidth) && positive(viewBoxHeight)) {
                                if (positive(width)) {
                                    height = width * viewBoxHeight / viewBoxWidth;
                                } else if (positive(height)) {
                                    width = height * viewBoxWidth / viewBoxHeight;
                                } else {
                                    width = viewBoxWidth;
                                    height = viewBoxHeight;
                                }
                            }
                        }
                    }
                    if (!positive(width) || !positive(height)) {
                        throw new IOException("SVG dimensions must be positive; specify width/height or viewBox");
                    }
                    return new Dimensions(width, height, true);
                }
            }
            throw new IOException("Expected an SVG image");
        } finally {
            reader.close();
        }
    }

    private static boolean isBase64(String icon) {
        int comma = icon.indexOf(',');
        return comma >= 0 && icon.substring(0, comma).endsWith(";base64");
    }

    private static byte[] decode(String icon) {
        int comma = icon.indexOf(',');
        if (comma < 0) {
            throw new IllegalArgumentException("Image data URI has no content separator");
        }
        String content = icon.substring(comma + 1);
        // Raw XML can contain literal '%' and '+' characters, including text such as "50%25".
        // Encoded XML starts with an encoded '<'; decode that form exactly once.
        if (!content.stripLeading().startsWith("<")) {
            content = URLDecoder.decode(content.replace("+", "%2B"), StandardCharsets.UTF_8);
        }
        return isBase64(icon) ? Base64.getDecoder().decode(content) : content.getBytes(StandardCharsets.UTF_8);
    }

    private static double length(String value) {
        if (value == null) {
            return Double.NaN;
        }
        Matcher matcher = LENGTH.matcher(value.trim());
        if (!matcher.matches()) {
            return Double.NaN;
        }
        double size = Double.parseDouble(matcher.group(1));
        String unit = matcher.group(2);
        double factor = unit == null ? 1 : switch (unit) {
            case "in" -> 96;
            case "cm" -> 96 / 2.54;
            case "mm" -> 96 / 25.4;
            case "Q" -> 96 / 101.6;
            case "pt" -> 96.0 / 72;
            case "pc" -> 16;
            default -> 1;
        };
        return size * factor;
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0;
    }

    private record Dimensions(double width, double height, boolean svg) {
    }

    record Prepared(String reference, double scale) {
    }

}
