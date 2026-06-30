package com.apimarketplace.catalog.mapping.adapter;

import com.apimarketplace.catalog.mapping.dsl.SourceSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapter for plain text format data
 */
@Component
public class TextAdapter implements SourceAdapter {
    
    private static final Logger logger = LoggerFactory.getLogger(TextAdapter.class);
    
    // Common regex patterns for text extraction
    private static final Pattern EMAIL_PATTERN = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[\\w\\-]+(\\.[\\w\\-]+)+([\\w\\-\\.,@?^=%&:/~\\+#]*[\\w\\-\\@?^=%&/~\\+#])?");
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\b\\d{3}[-.]?\\d{3}[-.]?\\d{4}\\b");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b|\\b\\d{2}/\\d{2}/\\d{4}\\b|\\b\\d{2}-\\d{2}-\\d{4}\\b");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+\\.?\\d*\\b");
    
    @Override
    public boolean isCollection(SourceSpec sourceSpec, byte[] input) {
        try {
            String content = new String(input, StandardCharsets.UTF_8);
            String[] lines = content.split("\n");
            
            // Consider it a collection if it has multiple lines
            return lines.length > 1;
        } catch (Exception e) {
            logger.debug("Error checking if text is collection: {}", e.getMessage());
            return false;
        }
    }
    
    @Override
    public Iterable<?> iterateItems(SourceSpec sourceSpec, byte[] input) {
        try {
            String content = new String(input, StandardCharsets.UTF_8);
            String[] lines = content.split("\n");
            
            // Return lines as items
            return Arrays.asList(lines);
        } catch (Exception e) {
            logger.debug("Error iterating text items: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    
    @Override
    public Object evalScalar(Object context, String candidate) {
        try {
            if (context instanceof final String line) {

                // Check if candidate is a regex pattern
                if (candidate.startsWith("^") && candidate.endsWith("$")) {
                    Pattern pattern = Pattern.compile(candidate);
                    Matcher matcher = pattern.matcher(line);
                    if (matcher.find()) {
                        if (matcher.groupCount() > 0) {
                            return matcher.group(1); // Return first capture group
                        } else {
                            return matcher.group(); // Return entire match
                        }
                    }
                }
                
                // Check if candidate is a simple key-value pattern
                if (candidate.contains(":")) {
                    String[] parts = line.split(":", 2);
                    if (parts.length == 2) {
                        String key = parts[0].trim();
                        String value = parts[1].trim();
                        if (candidate.equals(key)) {
                            return value;
                        }
                    }
                }
                
                // Check if candidate is a line number (0-based)
                try {
                    int lineNumber = Integer.parseInt(candidate);
                    String[] lines = line.split("\n");
                    if (lineNumber >= 0 && lineNumber < lines.length) {
                        return lines[lineNumber];
                    }
                } catch (NumberFormatException e) {
                    // Not a number, continue
                }
            }
            return null;
        } catch (Exception e) {
            logger.debug("Error evaluating text scalar: {}", e.getMessage());
            return null;
        }
    }
    
    @Override
    public Iterable<?> evalNodes(Object context, String pathAnyOf) {
        // Text doesn't have nested nodes, return empty list
        return Collections.emptyList();
    }
    
    @Override
    public Map<String, Object> flatten(byte[] input) {
        Map<String, Object> flattened = new HashMap<>();
        try {
            String content = new String(input, StandardCharsets.UTF_8);
            String[] lines = content.split("\n");
            
            // Extract common patterns
            extractPatterns(content, flattened);
            
            // Extract line-based data
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (!line.isEmpty()) {
                    flattened.put("line[" + i + "]", line);
                    
                    // Extract key-value pairs
                    if (line.contains(":")) {
                        String[] parts = line.split(":", 2);
                        if (parts.length == 2) {
                            String key = parts[0].trim();
                            String value = parts[1].trim();
                            flattened.put("kv." + key, value);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Error flattening text: {}", e.getMessage());
        }
        return flattened;
    }
    
    @Override
    public Object getRoot(byte[] input) {
        try {
            return new String(input, StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.debug("Error getting text root: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Extract common patterns from text content
     */
    private void extractPatterns(String content, Map<String, Object> flattened) {
        // Extract emails
        Matcher emailMatcher = EMAIL_PATTERN.matcher(content);
        List<String> emails = new ArrayList<>();
        while (emailMatcher.find()) {
            emails.add(emailMatcher.group());
        }
        if (!emails.isEmpty()) {
            flattened.put("emails", emails);
        }
        
        // Extract URLs
        Matcher urlMatcher = URL_PATTERN.matcher(content);
        List<String> urls = new ArrayList<>();
        while (urlMatcher.find()) {
            urls.add(urlMatcher.group());
        }
        if (!urls.isEmpty()) {
            flattened.put("urls", urls);
        }
        
        // Extract phone numbers
        Matcher phoneMatcher = PHONE_PATTERN.matcher(content);
        List<String> phones = new ArrayList<>();
        while (phoneMatcher.find()) {
            phones.add(phoneMatcher.group());
        }
        if (!phones.isEmpty()) {
            flattened.put("phones", phones);
        }
        
        // Extract dates
        Matcher dateMatcher = DATE_PATTERN.matcher(content);
        List<String> dates = new ArrayList<>();
        while (dateMatcher.find()) {
            dates.add(dateMatcher.group());
        }
        if (!dates.isEmpty()) {
            flattened.put("dates", dates);
        }
        
        // Extract numbers
        Matcher numberMatcher = NUMBER_PATTERN.matcher(content);
        List<String> numbers = new ArrayList<>();
        while (numberMatcher.find()) {
            numbers.add(numberMatcher.group());
        }
        if (!numbers.isEmpty()) {
            flattened.put("numbers", numbers);
        }
    }
}
