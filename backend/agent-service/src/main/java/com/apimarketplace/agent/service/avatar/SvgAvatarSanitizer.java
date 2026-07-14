package com.apimarketplace.agent.service.avatar;

import org.springframework.stereotype.Component;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict allowlist sanitizer for LLM-generated SVG avatars.
 *
 * <p>The generated markup is stored as a user file and later served anonymously from the
 * app origin (the public avatar endpoint), so it must be inert: no script, no event
 * handlers, no external fetches, no foreignObject/iframe smuggling. The endpoint adds a
 * no-script CSP as a second layer; this sanitizer is the first.
 *
 * <p>Approach: parse with the JDK XML parser (secure processing, no DOCTYPE, no external
 * entities), then rebuild keeping only allowlisted elements and attributes. Anything not
 * explicitly allowed is dropped. {@code url(...)} attribute values are only kept when they
 * reference a local fragment ({@code url(#id)}).
 */
@Component
public class SvgAvatarSanitizer {

    /** Vector shapes + gradient/animation plumbing - everything a flat avatar needs, nothing else. */
    private static final Set<String> ALLOWED_ELEMENTS = Set.of(
            "svg", "g", "defs", "title", "desc",
            "lineargradient", "radialgradient", "stop",
            "circle", "ellipse", "rect", "path", "polygon", "polyline", "line",
            "clippath", "mask",
            "animate", "animatetransform", "animatemotion", "mpath", "set"
    );

    private static final Set<String> ALLOWED_ATTRIBUTES = Set.of(
            // structure
            "xmlns", "viewbox", "width", "height", "id", "class",
            // geometry
            "cx", "cy", "r", "rx", "ry", "x", "y", "x1", "y1", "x2", "y2",
            "d", "points", "transform", "transform-origin",
            // paint
            "fill", "fill-opacity", "fill-rule", "stroke", "stroke-width", "stroke-linecap",
            "stroke-linejoin", "stroke-dasharray", "stroke-dashoffset", "stroke-opacity",
            "opacity", "stop-color", "stop-opacity", "offset",
            "gradientunits", "gradienttransform", "spreadmethod",
            "clip-path", "clip-rule", "mask",
            // SMIL animation ("fill" also freezes an animation; already allowed under paint)
            "attributename", "attributetype", "values", "keytimes", "keysplines", "calcmode",
            "from", "to", "by", "dur", "begin", "end", "repeatcount", "repeatdur",
            "additive", "accumulate", "type", "path", "rotate"
    );

    /** url(...) is only acceptable as a local fragment reference (gradients, clip paths). */
    private static final Pattern LOCAL_URL = Pattern.compile("url\\(\\s*['\"]?#[^)'\"]+['\"]?\\s*\\)");
    private static final Pattern ANY_URL = Pattern.compile("url\\s*\\(", Pattern.CASE_INSENSITIVE);

    private static final int MAX_INPUT_BYTES = 128 * 1024;

    /**
     * @return the sanitized, serialized SVG markup
     * @throws IllegalArgumentException when the input is not a parseable standalone SVG
     */
    public String sanitize(String rawSvg) {
        if (rawSvg == null || rawSvg.isBlank()) {
            throw new IllegalArgumentException("Empty SVG");
        }
        byte[] bytes = rawSvg.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("SVG too large");
        }

        Document document = parseSecurely(bytes);
        Element root = document.getDocumentElement();
        if (root == null || !"svg".equalsIgnoreCase(root.getLocalName() != null ? root.getLocalName() : root.getTagName())) {
            throw new IllegalArgumentException("Root element is not <svg>");
        }

        sanitizeElement(root);
        // Always pin the SVG namespace: the file is served standalone as image/svg+xml.
        // setAttribute("xmlns", ...) cannot change an element's DOM namespace (the
        // serializer would emit xmlns="") - renameNode actually moves the elements.
        forceSvgNamespace(document, root);

        return serialize(document);
    }

    private Document parseSecurely(byte[] bytes) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid SVG markup: " + e.getMessage(), e);
        }
    }

    private void sanitizeElement(Element element) {
        // Attributes: drop everything not allowlisted, every on* handler, every external ref.
        NamedNodeMap attributes = element.getAttributes();
        List<Attr> toRemove = new ArrayList<>();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attr = (Attr) attributes.item(i);
            String name = attr.getName().toLowerCase(Locale.ROOT);
            String value = attr.getValue();
            boolean allowed = ALLOWED_ATTRIBUTES.contains(stripNsPrefix(name))
                    && !name.startsWith("on")
                    && !isExternalReference(name, value);
            if (!allowed) {
                toRemove.add(attr);
            }
        }
        toRemove.forEach(a -> element.removeAttributeNode(a));

        // Children: recurse into allowlisted elements, remove the rest (with their subtree).
        NodeList children = element.getChildNodes();
        List<Node> drop = new ArrayList<>();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            switch (child.getNodeType()) {
                case Node.ELEMENT_NODE -> {
                    Element childElement = (Element) child;
                    String tag = childElement.getLocalName() != null
                            ? childElement.getLocalName() : childElement.getTagName();
                    if (ALLOWED_ELEMENTS.contains(tag.toLowerCase(Locale.ROOT))) {
                        sanitizeElement(childElement);
                    } else {
                        drop.add(child);
                    }
                }
                case Node.TEXT_NODE -> {
                    // Shapes carry no meaningful text; keep whitespace-only nodes for formatting.
                    if (child.getTextContent() != null && !child.getTextContent().isBlank()) {
                        drop.add(child);
                    }
                }
                // Comments, CDATA, processing instructions: gone.
                default -> drop.add(child);
            }
        }
        drop.forEach(element::removeChild);
    }

    private static final String SVG_NS = "http://www.w3.org/2000/svg";

    /** Move every namespace-less element into the SVG namespace (LLMs often omit xmlns). */
    private static void forceSvgNamespace(Document document, Element root) {
        java.util.Deque<Element> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Element element = stack.pop();
            NodeList children = element.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element child) {
                    stack.push(child);
                }
            }
            if (element.getNamespaceURI() == null) {
                document.renameNode(element, SVG_NS, element.getTagName());
            }
        }
    }

    private static String stripNsPrefix(String attributeName) {
        int colon = attributeName.indexOf(':');
        return colon >= 0 ? attributeName.substring(colon + 1) : attributeName;
    }

    /** href/xlink:href are banned outright; url(...) values must be local fragments. */
    private static boolean isExternalReference(String name, String value) {
        if (name.equals("href") || name.endsWith(":href")) {
            return true;
        }
        if (value == null) {
            return false;
        }
        String v = value.trim();
        if (ANY_URL.matcher(v).find()) {
            return !LOCAL_URL.matcher(v).matches();
        }
        return false;
    }

    private String serialize(Document document) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(document.getDocumentElement()), new StreamResult(writer));
            return writer.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize sanitized SVG", e);
        }
    }
}
