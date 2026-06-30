package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;

/**
 * XML node - Parses XML to JSON and builds XML from JSON.
 *
 * Operations:
 * - xmlToJson: Parse an XML string into a JSON Map
 * - jsonToXml: Build an XML string from a JSON Map
 *
 * Usage:
 * - Convert XML API responses to JSON for downstream processing
 * - Build XML payloads from structured data for SOAP/XML APIs
 * - Transform between XML and JSON representations in workflows
 */
public class XmlNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(XmlNode.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Core.XmlConfig xmlConfig;

    public XmlNode(String nodeId, Core.XmlConfig xmlConfig) {
        super(nodeId, NodeType.XML);
        this.xmlConfig = xmlConfig;
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        String operation = xmlConfig != null ? xmlConfig.operation() : "xmlToJson";
        logger.info("XML node executing: nodeId={}, operation={}, itemId={}",
            nodeId, operation, context.itemId());

        try {
            Map<String, Object> result = new HashMap<>();

            String value = xmlConfig != null ? xmlConfig.value() : null;

            Object operationResult = switch (operation) {
                // xmlToJson needs the value as a STRING (the XML to parse).
                case "xmlToJson" -> executeXmlToJson(resolveExpression(value, context));
                // jsonToXml needs the RAW resolved value (Map/List/JSON-string), not the
                // stringified form, so a configured value is honored instead of dumping context.
                case "jsonToXml" -> executeJsonToXml(resolveExpressionRaw(value, context), context);
                default -> throw new IllegalArgumentException("Unknown XML operation: " + operation);
            };

            result.put("result", operationResult);
            result.put("operation", operation);
            result.put("success", true);

            // MANDATORY metadata
            result.put("node_type", "XML");
            result.put("item_index", context.itemIndex());
            result.put("itemIndex", context.itemIndex());
            result.put("item_id", context.itemId());
            result.put("resolved_params", buildInputDataMap(operation, context));

            logger.info("XML completed: nodeId={}, operation={}", nodeId, operation);
            return NodeExecutionResult.success(nodeId, result);

        } catch (Exception e) {
            logger.error("XML execution failed: nodeId={}, operation={}, error={}",
                nodeId, operation, e.getMessage(), e);
            Map<String, Object> failOutput = new HashMap<>();
            failOutput.put("node_type", "XML");
            failOutput.put("item_index", context.itemIndex());
            failOutput.put("itemIndex", context.itemIndex());
            failOutput.put("item_id", context.itemId());
            failOutput.put("resolved_params", buildInputDataMap(operation, context));
            failOutput.put("error", e.getMessage());
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), failOutput, 0L);
        }
    }

    /**
     * Parse XML string to a JSON-like Map structure.
     */
    private Map<String, Object> executeXmlToJson(String xmlString) throws Exception {
        if (xmlString == null || xmlString.isBlank()) {
            throw new IllegalArgumentException("XML input value is required for xmlToJson operation");
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Security: disable external entities to prevent XXE attacks
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(new InputSource(new StringReader(xmlString)));
        document.getDocumentElement().normalize();

        return elementToMap(document.getDocumentElement());
    }

    /**
     * Build an XML string from JSON data resolved from the node's {@code value} expression.
     *
     * <p>Resolution order, so a configured value is HONORED rather than silently ignored:
     * <ol>
     *   <li>an already-structured Map/List - a single {@code {{expr}}} that resolved to data;</li>
     *   <li>a JSON object/array STRING literal - parsed into a Map/List;</li>
     *   <li>a bare step-output KEY string - looked up in step outputs (back-compat);</li>
     *   <li>only when NO value is given (null/blank) - the legacy fallback of the full
     *       step-output context.</li>
     * </ol>
     * Previously the resolved value was stringified and only ever used as a step-output key,
     * so any real JSON value fell through to dumping the entire execution context.
     */
    private String executeJsonToXml(Object resolvedValue, ExecutionContext context) throws Exception {
        String rootElement = xmlConfig != null && xmlConfig.rootElement() != null
            ? xmlConfig.rootElement() : "root";

        Map<String, Object> jsonData = coerceToJsonData(resolvedValue, context);

        if (jsonData == null || jsonData.isEmpty()) {
            throw new IllegalArgumentException("JSON input data is required for jsonToXml operation");
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.newDocument();

        Element root = document.createElement(rootElement);
        document.appendChild(root);

        mapToElement(document, root, jsonData);

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));

        return writer.toString();
    }

    /**
     * Resolve the {@code value} expression to its RAW object (Map/List/scalar) rather than a
     * stringified form, so a single {@code {{...}}} expression that resolved to structured data
     * keeps its type (the template engine preserves the original type for a pure expression).
     */
    private Object resolveExpressionRaw(String expression, ExecutionContext context) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        if (templateAdapter != null) {
            try {
                Map<String, Object> toResolve = Map.of("__expr__", expression);
                Map<String, Object> resolved = templateAdapter.resolveTemplates(toResolve, context);
                return resolved.get("__expr__");
            } catch (Exception e) {
                logger.warn("Failed to resolve expression '{}': {}", expression, e.getMessage());
                return expression;
            }
        }
        return expression;
    }

    /**
     * Coerce a resolved {@code value} into the JSON map serialized by jsonToXml. See
     * {@link #executeJsonToXml(Object, ExecutionContext)} for the resolution order.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> coerceToJsonData(Object value, ExecutionContext context) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (value instanceof List<?> list) {
            return wrapList(list);
        }
        if (value instanceof String s && !s.isBlank()) {
            String trimmed = s.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try {
                    Object parsed = JSON.readValue(trimmed, Object.class);
                    if (parsed instanceof Map<?, ?> parsedMap) {
                        return (Map<String, Object>) parsedMap;
                    }
                    if (parsed instanceof List<?> parsedList) {
                        return wrapList(parsedList);
                    }
                } catch (Exception ignored) {
                    // Not valid JSON - fall through to the step-output key lookup.
                }
            }
            Object stepOutput = context.stepOutputs() != null ? context.stepOutputs().get(s) : null;
            if (stepOutput instanceof Map<?, ?> stepMap) {
                return (Map<String, Object>) stepMap;
            }
            // Unrecognized non-blank string: keep the legacy whole-context fallback below.
        }
        // No usable value provided - legacy fallback: serialize all step outputs.
        if (context.stepOutputs() != null && !context.stepOutputs().isEmpty()) {
            return new HashMap<>(context.stepOutputs());
        }
        return null;
    }

    /** Wrap a JSON array under a repeated {@code <item>} element so it can serialize to XML. */
    private Map<String, Object> wrapList(List<?> list) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("item", list);
        return wrapper;
    }

    /**
     * Recursively convert an XML Element to a Map.
     * Handles attributes (if preserveAttributes is true), text content,
     * and child elements (including repeated elements as lists).
     */
    private Map<String, Object> elementToMap(Element element) {
        Map<String, Object> map = new LinkedHashMap<>();
        boolean preserveAttrs = xmlConfig != null && xmlConfig.preserveAttributes();

        // Handle attributes
        if (preserveAttrs && element.hasAttributes()) {
            NamedNodeMap attributes = element.getAttributes();
            Map<String, String> attrMap = new LinkedHashMap<>();
            for (int i = 0; i < attributes.getLength(); i++) {
                Node attr = attributes.item(i);
                attrMap.put(attr.getNodeName(), attr.getNodeValue());
            }
            if (!attrMap.isEmpty()) {
                map.put("@attributes", attrMap);
            }
        }

        // Collect child elements
        NodeList children = element.getChildNodes();
        Map<String, List<Object>> childMap = new LinkedHashMap<>();
        StringBuilder textContent = new StringBuilder();
        boolean hasElementChildren = false;

        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);

            if (child.getNodeType() == Node.ELEMENT_NODE) {
                hasElementChildren = true;
                String childName = child.getNodeName();
                Map<String, Object> childValue = elementToMap((Element) child);

                childMap.computeIfAbsent(childName, k -> new ArrayList<>()).add(childValue);
            } else if (child.getNodeType() == Node.TEXT_NODE
                       || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                String text = child.getTextContent().trim();
                if (!text.isEmpty()) {
                    textContent.append(text);
                }
            }
        }

        if (!hasElementChildren) {
            // Leaf element: return text content directly
            String text = textContent.toString();
            if (map.isEmpty()) {
                // No attributes - for a leaf, we still return a map with element name context
                // but the caller will unwrap this
                map.put("#text", text);
                return map;
            } else {
                // Has attributes - add text alongside them
                if (!text.isEmpty()) {
                    map.put("#text", text);
                }
                return map;
            }
        }

        // Add child elements to the map
        for (Map.Entry<String, List<Object>> entry : childMap.entrySet()) {
            List<Object> values = entry.getValue();
            if (values.size() == 1) {
                Object singleValue = values.get(0);
                // Unwrap simple leaf nodes (maps with only #text)
                if (singleValue instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> singleMap = (Map<String, Object>) singleValue;
                    if (singleMap.size() == 1 && singleMap.containsKey("#text")) {
                        map.put(entry.getKey(), singleMap.get("#text"));
                        continue;
                    }
                }
                map.put(entry.getKey(), singleValue);
            } else {
                // Multiple elements with the same name - create a list
                List<Object> unwrapped = new ArrayList<>();
                for (Object v : values) {
                    if (v instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> vMap = (Map<String, Object>) v;
                        if (vMap.size() == 1 && vMap.containsKey("#text")) {
                            unwrapped.add(vMap.get("#text"));
                            continue;
                        }
                    }
                    unwrapped.add(v);
                }
                map.put(entry.getKey(), unwrapped);
            }
        }

        return map;
    }

    /**
     * Recursively convert a Map to XML elements.
     */
    @SuppressWarnings("unchecked")
    private void mapToElement(Document document, Element parent, Map<String, Object> data) {
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // Skip metadata keys
            if (key.startsWith("@") || key.startsWith("#")) {
                continue;
            }

            // Sanitize element name (XML element names cannot start with numbers or contain spaces)
            String elementName = sanitizeElementName(key);

            if (value instanceof Map) {
                Element child = document.createElement(elementName);
                parent.appendChild(child);
                mapToElement(document, child, (Map<String, Object>) value);
            } else if (value instanceof List) {
                List<?> list = (List<?>) value;
                for (Object item : list) {
                    Element child = document.createElement(elementName);
                    parent.appendChild(child);
                    if (item instanceof Map) {
                        mapToElement(document, child, (Map<String, Object>) item);
                    } else {
                        child.setTextContent(String.valueOf(item));
                    }
                }
            } else {
                Element child = document.createElement(elementName);
                child.setTextContent(value != null ? String.valueOf(value) : "");
                parent.appendChild(child);
            }
        }
    }

    /**
     * Sanitize a string to be a valid XML element name.
     * Replaces invalid characters with underscores.
     */
    private String sanitizeElementName(String name) {
        if (name == null || name.isEmpty()) {
            return "_element";
        }
        // Replace spaces and special characters
        String sanitized = name.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
        // XML names cannot start with a digit, hyphen, or period
        if (!sanitized.isEmpty() && !Character.isLetter(sanitized.charAt(0)) && sanitized.charAt(0) != '_') {
            sanitized = "_" + sanitized;
        }
        return sanitized.isEmpty() ? "_element" : sanitized;
    }

    /**
     * Resolve a SpEL expression using the template adapter.
     */
    private String resolveExpression(String expression, ExecutionContext context) {
        if (expression == null || expression.isBlank()) {
            return null;
        }

        if (templateAdapter != null) {
            try {
                Map<String, Object> toResolve = Map.of("__expr__", expression);
                Map<String, Object> resolved = templateAdapter.resolveTemplates(toResolve, context);
                Object result = resolved.get("__expr__");
                return result != null ? String.valueOf(result) : expression;
            } catch (Exception e) {
                logger.warn("Failed to resolve expression '{}': {}", expression, e.getMessage());
                return expression;
            }
        }

        return expression;
    }

    private Map<String, Object> buildInputDataMap(String operation, ExecutionContext context) {
        Map<String, Object> inputData = new LinkedHashMap<>();
        inputData.put("operation", operation);
        if (xmlConfig != null) {
            if (xmlConfig.value() != null) inputData.put("value", resolveTemplateString(xmlConfig.value(), context));
            if (xmlConfig.rootElement() != null) inputData.put("rootElement", resolveTemplateString(xmlConfig.rootElement(), context));
            inputData.put("preserveAttributes", xmlConfig.preserveAttributes());
        }
        return inputData;
    }

    // Getters
    public Core.XmlConfig getXmlConfig() { return xmlConfig; }

    // Builder
    public static class Builder {
        private String nodeId;
        private Core.XmlConfig xmlConfig;

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder xmlConfig(Core.XmlConfig xmlConfig) { this.xmlConfig = xmlConfig; return this; }
        public XmlNode build() { return new XmlNode(nodeId, xmlConfig); }
    }

    public static Builder builder() { return new Builder(); }
}
