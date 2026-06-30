package com.apimarketplace.catalog.mapping.adapter;

import com.apimarketplace.catalog.mapping.dsl.SourceSpec;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Adapter for HTML format data
 */
@Component
public class HtmlAdapter implements SourceAdapter {
    
    private static final Logger logger = LoggerFactory.getLogger(HtmlAdapter.class);
    
    @Override
    public boolean isCollection(SourceSpec sourceSpec, byte[] input) {
        try {
            Document doc = getDocument(input);
            String rootPath = sourceSpec.getRoot();
            
            if (rootPath == null || rootPath.trim().isEmpty()) {
                return doc.select("body > *").size() > 1;
            }
            
            Elements elements = doc.select(rootPath);
            return elements.size() > 1;
        } catch (Exception e) {
            logger.debug("Error checking if HTML is collection: {}", e.getMessage());
            return false;
        }
    }
    
    @Override
    public Iterable<?> iterateItems(SourceSpec sourceSpec, byte[] input) {
        try {
            Document doc = getDocument(input);
            String rootPath = sourceSpec.getRoot();
            
            if (rootPath == null || rootPath.trim().isEmpty()) {
                return doc.select("body > *");
            }
            
            Elements elements = doc.select(rootPath);
            return elements;
        } catch (Exception e) {
            logger.debug("Error iterating HTML items: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    
    @Override
    public Object evalScalar(Object context, String candidate) {
        try {
            if (context instanceof final Element element) {

                // Check if candidate is for text content
                if (candidate.equals("text()") || candidate.equals("@text")) {
                    return element.text();
                }
                
                // Check if candidate is for HTML content
                if (candidate.equals("html()") || candidate.equals("@html")) {
                    return element.html();
                }
                
                // Check if candidate is for attribute
                if (candidate.startsWith("@")) {
                    String attrName = candidate.substring(1);
                    return element.attr(attrName);
                }
                
                // Check if candidate is for CSS selector
                Elements selected = element.select(candidate);
                if (selected.size() == 1) {
                    return selected.first().text();
                } else if (selected.size() > 1) {
                    return selected.stream().map(Element::text).toList();
                }
            }
            return null;
        } catch (Exception e) {
            logger.debug("Error evaluating HTML scalar: {}", e.getMessage());
            return null;
        }
    }
    
    @Override
    public Iterable<?> evalNodes(Object context, String pathAnyOf) {
        try {
            if (context instanceof final Element element) {
                Elements elements = element.select(pathAnyOf);
                return elements;
            }
            return Collections.emptyList();
        } catch (Exception e) {
            logger.debug("Error evaluating HTML nodes: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    
    @Override
    public Map<String, Object> flatten(byte[] input) {
        Map<String, Object> flattened = new HashMap<>();
        try {
            Document doc = getDocument(input);
            flattenElement(doc.body(), "", flattened);
        } catch (Exception e) {
            logger.debug("Error flattening HTML: {}", e.getMessage());
        }
        return flattened;
    }
    
    @Override
    public Object getRoot(byte[] input) {
        try {
            return getDocument(input);
        } catch (Exception e) {
            logger.debug("Error getting HTML root: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Get Document from input bytes
     */
    private Document getDocument(byte[] input) throws Exception {
        String content = new String(input, StandardCharsets.UTF_8);
        return Jsoup.parse(content);
    }
    
    /**
     * Recursively flatten an HTML element into path-value pairs
     */
    private void flattenElement(Element element, String path, Map<String, Object> flattened) {
        if (element == null) {
            return;
        }
        
        String tagName = element.tagName();
        String newPath = path.isEmpty() ? tagName : path + "/" + tagName;
        
        // Add attributes
        for (org.jsoup.nodes.Attribute attr : element.attributes()) {
            flattened.put(newPath + "@" + attr.getKey(), attr.getValue());
        }
        
        // Check if element has only text content (no child elements)
        Elements children = element.children();
        if (children.isEmpty()) {
            String text = element.text();
            if (text != null && !text.trim().isEmpty()) {
                flattened.put(newPath, text.trim());
            }
        } else {
            // Element has child elements, recurse
            for (Element child : children) {
                flattenElement(child, newPath, flattened);
            }
        }
    }
}
