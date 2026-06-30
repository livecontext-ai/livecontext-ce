package com.apimarketplace.catalog.mapping.adapter;

import com.apimarketplace.catalog.mapping.dsl.SourceSpec;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Adapter for CSV format data
 */
@Component
public class CsvAdapter implements SourceAdapter {
    
    private static final Logger logger = LoggerFactory.getLogger(CsvAdapter.class);
    
    @Override
    public boolean isCollection(SourceSpec sourceSpec, byte[] input) {
        try {
            List<CSVRecord> records = parseCsv(input);
            return records.size() > 1; // CSV is always a collection if it has more than header row
        } catch (Exception e) {
            logger.debug("Error checking if CSV is collection: {}", e.getMessage());
            return false;
        }
    }
    
    @Override
    public Iterable<?> iterateItems(SourceSpec sourceSpec, byte[] input) {
        try {
            List<CSVRecord> records = parseCsv(input);
            // Skip header row if present
            if (records.size() > 1) {
                return records.subList(1, records.size());
            }
            return records;
        } catch (Exception e) {
            logger.debug("Error iterating CSV items: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    
    @Override
    public Object evalScalar(Object context, String candidate) {
        try {
            if (context instanceof final CSVRecord record) {

                // Check if candidate is a column name
                if (record.isSet(candidate)) {
                    return record.get(candidate);
                }
                
                // Check if candidate is a column index
                try {
                    int index = Integer.parseInt(candidate);
                    if (index >= 0 && index < record.size()) {
                        return record.get(index);
                    }
                } catch (NumberFormatException e) {
                    // Not a number, continue
                }
            }
            return null;
        } catch (Exception e) {
            logger.debug("Error evaluating CSV scalar: {}", e.getMessage());
            return null;
        }
    }
    
    @Override
    public Iterable<?> evalNodes(Object context, String pathAnyOf) {
        // CSV doesn't have nested nodes, return empty list
        return Collections.emptyList();
    }
    
    @Override
    public Map<String, Object> flatten(byte[] input) {
        Map<String, Object> flattened = new HashMap<>();
        try {
            List<CSVRecord> records = parseCsv(input);
            
            if (records.isEmpty()) {
                return flattened;
            }
            
            // Get headers from first record
            CSVRecord headerRecord = records.get(0);
            String[] headers = new String[headerRecord.size()];
            for (int i = 0; i < headerRecord.size(); i++) {
                headers[i] = headerRecord.get(i);
            }
            
            // Flatten each data row
            for (int rowIndex = 1; rowIndex < records.size(); rowIndex++) {
                CSVRecord record = records.get(rowIndex);
                for (int colIndex = 0; colIndex < record.size() && colIndex < headers.length; colIndex++) {
                    String header = headers[colIndex];
                    String value = record.get(colIndex);
                    String path = "row[" + (rowIndex - 1) + "]." + header;
                    flattened.put(path, value);
                }
            }
        } catch (Exception e) {
            logger.debug("Error flattening CSV: {}", e.getMessage());
        }
        return flattened;
    }
    
    @Override
    public Object getRoot(byte[] input) {
        try {
            return parseCsv(input);
        } catch (Exception e) {
            logger.debug("Error getting CSV root: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Parse CSV from input bytes
     */
    private List<CSVRecord> parseCsv(byte[] input) throws Exception {
        ByteArrayInputStream inputStream = new ByteArrayInputStream(input);
        InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
        
        // Try to detect delimiter
        char delimiter = detectDelimiter(input);
        CSVFormat format = CSVFormat.DEFAULT.withDelimiter(delimiter);
        
        try (CSVParser parser = new CSVParser(reader, format)) {
            return parser.getRecords();
        }
    }
    
    /**
     * Detect CSV delimiter
     */
    private char detectDelimiter(byte[] input) {
        String content = new String(input, StandardCharsets.UTF_8);
        String[] lines = content.split("\n");
        
        if (lines.length < 2) {
            return ',';
        }
        
        String firstLine = lines[0];
        
        // Count occurrences of common delimiters
        int commaCount = countOccurrences(firstLine, ',');
        int semicolonCount = countOccurrences(firstLine, ';');
        int tabCount = countOccurrences(firstLine, '\t');
        int pipeCount = countOccurrences(firstLine, '|');
        
        // Return the most common delimiter
        if (commaCount >= semicolonCount && commaCount >= tabCount && commaCount >= pipeCount) {
            return ',';
        } else if (semicolonCount >= tabCount && semicolonCount >= pipeCount) {
            return ';';
        } else if (tabCount >= pipeCount) {
            return '\t';
        } else {
            return '|';
        }
    }
    
    /**
     * Count occurrences of a character in a string
     */
    private int countOccurrences(String str, char ch) {
        int count = 0;
        for (char c : str.toCharArray()) {
            if (c == ch) {
                count++;
            }
        }
        return count;
    }
}
