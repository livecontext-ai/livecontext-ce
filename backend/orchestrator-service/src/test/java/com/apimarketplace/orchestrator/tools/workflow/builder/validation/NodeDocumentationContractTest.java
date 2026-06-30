package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Contract test for node_type_documentation.parameters JSONB.
 *
 * Background: two bugs slipped into V11__seed_node_type_documentation.sql:
 *   1. 'sort' had parameters = NULL despite SortNode.execute() requiring
 *      'input' and 'fields'.
 *   2. 'filter' omitted 'input' even though FilterNode.execute() explicitly
 *      requires it (FilterNode.java:50-53).
 *
 * Both were invisible to LLMs calling nodeLibraryService.findByType(...).
 * V50__fix_filter_sort_node_docs.sql patches both. This test guards against
 * regression by parsing migration SQL as text and asserting that required
 * fields are declared for the critical data-manipulation and table nodes.
 *
 * Approach: text parsing (no Spring context) so the test is fast and stable.
 * For each (nodeType -> requiredField) pair, we search the canonical doc row
 * across V11 and V50, picking the *latest* migration that sets parameters for
 * that type, and assert the field appears with "required": true.
 */
class NodeDocumentationContractTest {

    private static final Path MIGRATION_DIR = Paths.get(
            "..", "migration-service", "src", "main", "resources", "db", "migration");

    /** Each entry: node type -> list of field names that MUST be declared as required. */
    private static final Map<String, List<String>> REQUIRED_FIELDS = Map.of(
            "filter", List.of("input", "conditions"),
            "sort", List.of("input", "fields"),
            "insert_row", List.of("table_id", "columns"),
            "update_row", List.of("table_id"),
            "delete_row", List.of("table_id")
    );

    @Test
    void allRequiredFieldsAreDeclaredInLatestMigration() throws IOException {
        // Priority: later migrations override earlier ones. Check V50 first, then V11.
        String v50 = readMigration("V50__fix_filter_sort_node_docs.sql");
        String v11 = readMigration("V11__seed_node_type_documentation.sql");

        StringBuilder failures = new StringBuilder();

        for (Map.Entry<String, List<String>> entry : REQUIRED_FIELDS.entrySet()) {
            String type = entry.getKey();
            List<String> fields = entry.getValue();

            String parametersJson = extractParametersForType(v50, type);
            if (parametersJson == null) {
                parametersJson = extractParametersForType(v11, type);
            }

            if (parametersJson == null) {
                failures.append("  - node '").append(type)
                        .append("': no parameters found in V11 or V50\n");
                continue;
            }
            if ("NULL".equalsIgnoreCase(parametersJson.trim())) {
                failures.append("  - node '").append(type)
                        .append("': parameters is NULL - LLM sees zero declared params\n");
                continue;
            }

            for (String field : fields) {
                if (!hasRequiredField(parametersJson, field)) {
                    failures.append("  - node '").append(type)
                            .append("': field '").append(field)
                            .append("' missing or not declared required: true\n");
                }
            }
        }

        if (failures.length() > 0) {
            fail("node_type_documentation contract violations:\n" + failures);
        }
    }

    @Test
    void v50PatchesFilterAndSort() throws IOException {
        String v50 = readMigration("V50__fix_filter_sort_node_docs.sql");
        assertTrue(v50.contains("type = 'filter'"),
                "V50 must patch the 'filter' row");
        assertTrue(v50.contains("type = 'sort'"),
                "V50 must patch the 'sort' row");
        assertTrue(v50.contains("\"input\""),
                "V50 must declare the 'input' parameter");
        assertTrue(v50.contains("\"fields\""),
                "V50 must declare the 'fields' parameter (for sort)");
        assertTrue(v50.contains("\"conditions\""),
                "V50 must declare the 'conditions' parameter (for filter)");
    }

    // ----- helpers -----

    private static String readMigration(String fileName) throws IOException {
        Path path = MIGRATION_DIR.resolve(fileName);
        if (!Files.exists(path)) {
            // Fallback: resolve from repo root when tests run from repo root.
            path = Paths.get("backend", "migration-service", "src", "main",
                    "resources", "db", "migration", fileName);
        }
        return Files.readString(path);
    }

    /**
     * Extracts the parameters JSONB value for the given node type from a
     * migration SQL file. Supports both the INSERT style used in V11 and the
     * UPDATE style used in V50.
     */
    private static String extractParametersForType(String sql, String type) {
        // UPDATE style (V50): look for "SET parameters = '...'::jsonb ... WHERE type = 'xxx'"
        int whereIdx = sql.indexOf("WHERE type = '" + type + "'");
        if (whereIdx >= 0) {
            int setIdx = sql.lastIndexOf("SET parameters = ", whereIdx);
            if (setIdx >= 0) {
                int start = sql.indexOf('\'', setIdx) + 1;
                int end = sql.indexOf("'::jsonb", start);
                if (end > start) {
                    return sql.substring(start, end);
                }
            }
        }

        // INSERT style (V11): look for "VALUES ('type', ..." and extract the
        // 6th-positional column (parameters). Simpler: find the row line, then
        // grab the first "'{...}'::jsonb" or the first "NULL,"-sequence after
        // the description column. We use a robust heuristic: find the INSERT
        // line containing ('type', and walk jsonb literals.
        String marker = "VALUES ('" + type + "',";
        int rowIdx = sql.indexOf(marker);
        if (rowIdx < 0) return null;

        int lineEnd = sql.indexOf('\n', rowIdx);
        if (lineEnd < 0) lineEnd = sql.length();
        String row = sql.substring(rowIdx, lineEnd);

        // The 6 columns before parameters are: type, label, category,
        // variable_prefix, description, parameters. We look for the 5th comma
        // separating top-level values after the opening paren - but strings
        // contain commas. Easier: find the first "'{" after the description
        // (which is the 5th value and ends with "', "), OR "NULL, " at the 6th
        // position if parameters is NULL.
        //
        // Use a simpler approach: find the sequence "}'::jsonb" occurrences and
        // their preceding "'{". The first such jsonb literal corresponds to the
        // parameters column - UNLESS parameters is NULL, in which case we must
        // detect that.
        //
        // Detect NULL parameters: after the description closing "', ", the next
        // token is "NULL,". We approximate description end by finding the 5th
        // unescaped "', " pattern. That's fragile - instead parse positionally.

        return extractSixthColumn(row);
    }

    /**
     * Parses the VALUES(...) portion of an INSERT row and returns the 6th
     * column (parameters) as a string. SQL strings use '' for escaped quotes.
     */
    private static String extractSixthColumn(String row) {
        int parenStart = row.indexOf('(');
        if (parenStart < 0) return null;
        int i = parenStart + 1;
        int col = 0;
        int len = row.length();

        while (i < len && col < 5) {
            // Skip whitespace
            while (i < len && Character.isWhitespace(row.charAt(i))) i++;
            if (i >= len) return null;
            char c = row.charAt(i);
            if (c == '\'') {
                // String literal - skip until matching ' (handle '' escape)
                i++;
                while (i < len) {
                    if (row.charAt(i) == '\'') {
                        if (i + 1 < len && row.charAt(i + 1) == '\'') {
                            i += 2;
                        } else {
                            i++;
                            break;
                        }
                    } else {
                        i++;
                    }
                }
                // Optional ::type cast
                if (i + 1 < len && row.charAt(i) == ':' && row.charAt(i + 1) == ':') {
                    i += 2;
                    while (i < len && (Character.isLetterOrDigit(row.charAt(i)) || row.charAt(i) == '_')) i++;
                }
            } else {
                // Identifier / NULL / number - read until comma or close paren
                while (i < len && row.charAt(i) != ',' && row.charAt(i) != ')') i++;
            }
            // Skip trailing whitespace
            while (i < len && Character.isWhitespace(row.charAt(i))) i++;
            if (i < len && row.charAt(i) == ',') {
                i++;
                col++;
            } else {
                return null;
            }
        }

        // Now i points at the start of the 6th column (parameters)
        while (i < len && Character.isWhitespace(row.charAt(i))) i++;
        if (i >= len) return null;

        if (row.startsWith("NULL", i)) {
            return "NULL";
        }
        if (row.charAt(i) != '\'') return null;

        int start = i + 1;
        int j = start;
        StringBuilder sb = new StringBuilder();
        while (j < len) {
            if (row.charAt(j) == '\'') {
                if (j + 1 < len && row.charAt(j + 1) == '\'') {
                    sb.append('\'');
                    j += 2;
                } else {
                    break;
                }
            } else {
                sb.append(row.charAt(j));
                j++;
            }
        }
        return sb.toString();
    }

    /**
     * Checks whether the given JSONB parameters text declares a top-level key
     * {@code fieldName} with {@code "required": true}. Uses simple substring
     * matching rather than full JSON parsing - sufficient for the canonical
     * format used in V11/V50.
     */
    private static boolean hasRequiredField(String parametersJson, String fieldName) {
        String key = "\"" + fieldName + "\"";
        int keyIdx = parametersJson.indexOf(key);
        if (keyIdx < 0) return false;
        // Find the object value: "fieldName": { ... }
        int braceStart = parametersJson.indexOf('{', keyIdx);
        if (braceStart < 0) return false;
        int depth = 0;
        int end = braceStart;
        for (int i = braceStart; i < parametersJson.length(); i++) {
            char c = parametersJson.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }
        String objectBody = parametersJson.substring(braceStart, end + 1);
        return objectBody.contains("\"required\": true")
                || objectBody.contains("\"required\":true");
    }
}
