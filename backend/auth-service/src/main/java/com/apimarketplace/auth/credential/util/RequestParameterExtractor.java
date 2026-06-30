package com.apimarketplace.auth.credential.util;

import org.springframework.stereotype.Component;
import java.util.*;

/**
 * Utility for extracting typed values from request parameter maps.
 * Used by credential controllers to safely extract fields from JSON request bodies.
 */
@Component
public class RequestParameterExtractor {

    public String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMap(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public List<String> getList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List) {
            return (List<String>) value;
        }
        return null;
    }
}
