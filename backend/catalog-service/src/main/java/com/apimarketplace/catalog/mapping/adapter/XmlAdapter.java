package com.apimarketplace.catalog.mapping.adapter;

import com.apimarketplace.catalog.mapping.dsl.SourceSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Adapter for XML format data
 */
@Component
public class XmlAdapter implements SourceAdapter {
    
    private static final Logger logger = LoggerFactory.getLogger(XmlAdapter.class);
    
    private final XPathFactory xpathFactory = XPathFactory.newInstance();
    
    @Override
    public boolean isCollection(SourceSpec sourceSpec, byte[] input) {
        try {
            Document doc = getDocument(input);
            String rootPath = sourceSpec.getRoot();
            
            if (rootPath == null || rootPath.trim().isEmpty()) {
                return doc.getDocumentElement().getChildNodes().getLength() > 1;
            }
            
            XPath xpath = xpathFactory.newXPath();
            NodeList nodes = (NodeList) xpath.evaluate(rootPath, doc, XPathConstants.NODESET);
            return nodes.getLength() > 1;
        } catch (Exception e) {
            logger.debug("Error checking if XML is collection: {}", e.getMessage());
            return false;
        }
    }
    
    @Override
    public Iterable<?> iterateItems(SourceSpec sourceSpec, byte[] input) {
        try {
            Document doc = getDocument(input);
            String rootPath = sourceSpec.getRoot();
            
            if (rootPath == null || rootPath.trim().isEmpty()) {
                return getChildElements(doc.getDocumentElement());
            }
            
            XPath xpath = xpathFactory.newXPath();
            NodeList nodes = (NodeList) xpath.evaluate(rootPath, doc, XPathConstants.NODESET);
            return nodeListToIterable(nodes);
        } catch (Exception e) {
            logger.debug("Error iterating XML items: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    
    @Override
    public Object evalScalar(Object context, String candidate) {
        try {
            if (context instanceof final Node node) {
                XPath xpath = xpathFactory.newXPath();
                return xpath.evaluate(candidate, node);
            }
            return null;
        } catch (Exception e) {
            logger.debug("Error evaluating XML scalar: {}", e.getMessage());
            return null;
        }
    }
    
    @Override
    public Iterable<?> evalNodes(Object context, String pathAnyOf) {
        try {
            if (context instanceof final Node node) {
                XPath xpath = xpathFactory.newXPath();
                NodeList nodes = (NodeList) xpath.evaluate(pathAnyOf, node, XPathConstants.NODESET);
                return nodeListToIterable(nodes);
            }
            return Collections.emptyList();
        } catch (Exception e) {
            logger.debug("Error evaluating XML nodes: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    
    @Override
    public Map<String, Object> flatten(byte[] input) {
        Map<String, Object> flattened = new HashMap<>();
        try {
            Document doc = getDocument(input);
            flattenNode(doc.getDocumentElement(), "", flattened);
        } catch (Exception e) {
            logger.debug("Error flattening XML: {}", e.getMessage());
        }
        return flattened;
    }
    
    @Override
    public Object getRoot(byte[] input) {
        try {
            return getDocument(input);
        } catch (Exception e) {
            logger.debug("Error getting XML root: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Get Document from input bytes
     */
    private Document getDocument(byte[] input) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        InputSource source = new InputSource(new ByteArrayInputStream(input));
        return builder.parse(source);
    }
    
    /**
     * Get child elements from a node
     */
    private List<Node> getChildElements(Node parent) {
        List<Node> elements = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                elements.add(child);
            }
        }
        return elements;
    }
    
    /**
     * Convert NodeList to Iterable
     */
    private List<Node> nodeListToIterable(NodeList nodeList) {
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < nodeList.getLength(); i++) {
            nodes.add(nodeList.item(i));
        }
        return nodes;
    }
    
    /**
     * Recursively flatten an XML node into path-value pairs
     */
    private void flattenNode(Node node, String path, Map<String, Object> flattened) {
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            Element element = (Element) node;
            String nodeName = element.getNodeName();
            String newPath = path.isEmpty() ? nodeName : path + "/" + nodeName;
            
            // Add attributes
            NamedNodeMap attributes = element.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++) {
                Node attr = attributes.item(i);
                flattened.put(newPath + "@" + attr.getNodeName(), attr.getNodeValue());
            }
            
            // Check if element has text content only (no child elements)
            NodeList children = element.getChildNodes();
            boolean hasElementChildren = false;
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                    hasElementChildren = true;
                    break;
                }
            }
            
            if (!hasElementChildren) {
                // Element has only text content
                String textContent = element.getTextContent();
                if (textContent != null && !textContent.trim().isEmpty()) {
                    flattened.put(newPath, textContent.trim());
                }
            } else {
                // Element has child elements, recurse
                for (int i = 0; i < children.getLength(); i++) {
                    Node child = children.item(i);
                    if (child.getNodeType() == Node.ELEMENT_NODE) {
                        flattenNode(child, newPath, flattened);
                    }
                }
            }
        }
    }
}
