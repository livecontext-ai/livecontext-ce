package com.apimarketplace.catalog.mapping.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Utility class for type coercion and conversion
 */
public class TypeCoercion {
    
    private static final Logger logger = LoggerFactory.getLogger(TypeCoercion.class);
    
    // Common date patterns
    private static final DateTimeFormatter[] DATE_FORMATTERS = {
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"),
        DateTimeFormatter.ofPattern("MM/dd/yyyy"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("dd-MM-yyyy"),
        DateTimeFormatter.ofPattern("MM-dd-yyyy")
    };
    
    // URL pattern
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[\\w\\-]+(\\.[\\w\\-]+)+([\\w\\-\\.,@?^=%&:/~\\+#]*[\\w\\-\\@?^=%&/~\\+#])?");
    
    /**
     * Coerce a value to the specified type
     */
    public static Object coerce(Object value, String targetType) {
        if (value == null) {
            return null;
        }
        
        String stringValue = value.toString().trim();
        if (stringValue.isEmpty()) {
            return null;
        }
        
        return switch (targetType.toLowerCase()) {
            case "integer", "int" -> coerceToInteger(stringValue);
            case "boolean", "bool" -> coerceToBoolean(stringValue);
            case "datetime", "date" -> coerceToDateTime(stringValue);
            case "uri", "url" -> coerceToUri(stringValue);
            case "string" -> stringValue;
            default -> stringValue;
        };
    }
    
    /**
     * Coerce to integer
     */
    private static Object coerceToInteger(String value) {
        try {
            // Remove common formatting
            String cleaned = value.replaceAll("[,\\s]", "");
            return Integer.parseInt(cleaned);
        } catch (NumberFormatException e) {
            logger.debug("Could not coerce '{}' to integer", value);
            return value; // Return original if can't convert
        }
    }
    
    /**
     * Coerce to boolean
     */
    private static Object coerceToBoolean(String value) {
        String lower = value.toLowerCase();
        if (lower.equals("true") || lower.equals("1") || lower.equals("yes") || lower.equals("on")) {
            return true;
        } else if (lower.equals("false") || lower.equals("0") || lower.equals("no") || lower.equals("off")) {
            return false;
        } else {
            logger.debug("Could not coerce '{}' to boolean", value);
            return value; // Return original if can't convert
        }
    }
    
    /**
     * Coerce to datetime
     */
    private static Object coerceToDateTime(String value) {
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                LocalDateTime dateTime = LocalDateTime.parse(value, formatter);
                return dateTime.toString();
            } catch (DateTimeParseException e) {
                // Try next formatter
            }
        }
        
        // Try parsing as timestamp
        try {
            long timestamp = Long.parseLong(value);
            LocalDateTime dateTime = LocalDateTime.ofEpochSecond(timestamp, 0, java.time.ZoneOffset.UTC);
            return dateTime.toString();
        } catch (NumberFormatException e) {
            logger.debug("Could not coerce '{}' to datetime", value);
            return value; // Return original if can't convert
        }
    }
    
    /**
     * Coerce to URI
     */
    private static Object coerceToUri(String value) {
        if (URL_PATTERN.matcher(value).matches()) {
            return value;
        } else {
            logger.debug("Could not coerce '{}' to URI", value);
            return value; // Return original if can't convert
        }
    }
    
    /**
     * Check if a value can be coerced to a specific type
     */
    public static boolean canCoerce(Object value, String targetType) {
        if (value == null) {
            return true; // null can be coerced to any type
        }
        
        String stringValue = value.toString().trim();
        if (stringValue.isEmpty()) {
            return true; // empty string can be coerced to any type
        }
        
        return switch (targetType.toLowerCase()) {
            case "integer", "int" -> canCoerceToInteger(stringValue);
            case "boolean", "bool" -> canCoerceToBoolean(stringValue);
            case "datetime", "date" -> canCoerceToDateTime(stringValue);
            case "uri", "url" -> canCoerceToUri(stringValue);
            case "string" -> true; // Any value can be coerced to string
            default -> true;
        };
    }
    
    /**
     * Check if can coerce to integer
     */
    private static boolean canCoerceToInteger(String value) {
        try {
            String cleaned = value.replaceAll("[,\\s]", "");
            Integer.parseInt(cleaned);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
    
    /**
     * Check if can coerce to boolean
     */
    private static boolean canCoerceToBoolean(String value) {
        String lower = value.toLowerCase();
        return lower.equals("true") || lower.equals("false") || 
               lower.equals("1") || lower.equals("0") ||
               lower.equals("yes") || lower.equals("no") ||
               lower.equals("on") || lower.equals("off");
    }
    
    /**
     * Check if can coerce to datetime
     */
    private static boolean canCoerceToDateTime(String value) {
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                LocalDateTime.parse(value, formatter);
                return true;
            } catch (DateTimeParseException e) {
                // Try next formatter
            }
        }
        
        // Try parsing as timestamp
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
    
    /**
     * Check if can coerce to URI
     */
    private static boolean canCoerceToUri(String value) {
        return URL_PATTERN.matcher(value).matches();
    }
}
