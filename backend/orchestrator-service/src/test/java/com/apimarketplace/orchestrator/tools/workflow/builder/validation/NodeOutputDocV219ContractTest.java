package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import com.apimarketplace.orchestrator.execution.v2.nodes.CompressionNodeSpec;
import com.apimarketplace.orchestrator.execution.v2.nodes.ConvertToFileNodeSpec;
import com.apimarketplace.orchestrator.execution.v2.nodes.DownloadFileNodeSpec;
import com.apimarketplace.orchestrator.execution.v2.nodes.SftpNodeSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the file-producer node doc contract over the V219 → V221 chain. V219 added the
 * canonical {@code file} key alongside legacy back-compat flat fields; V221 (PR2,
 * 2026-05-15) DROPPED the legacy fields and keeps only {@code file} + non-file metadata.
 *
 * <p>This test pins V221's final shape: the {@code node_type_documentation.outputs} of
 * the 4 file-producer nodes (download_file, sftp, convert_to_file, compression) MUST
 * declare the canonical {@code file} key AND MUST NOT declare any of the legacy flat
 * fields ({@code file_url}, {@code file_name}, {@code file_size}, {@code content_type}).
 *
 * <p>Failing this test means the LLM-facing doc would drift from the runtime mapper -
 * the agent would invent SpEL like {@code {{node.output.file_url}}} that now resolves
 * to nothing, breaking marketplace + share preview rendering.
 *
 * <p>Migration discovery is filesystem-based; no DB is needed.
 */
@DisplayName("V221 - file-producer doc declares canonical `file` and drops legacy flat fields")
class NodeOutputDocV219ContractTest {

    private static final Path MIGRATION_DIR = Paths.get(
            "..", "migration-service", "src", "main", "resources", "db", "migration");

    private static final String V221 = "V221__file_producer_nodes_drop_legacy_flat_fields.sql";

    private static final String[] FILE_PRODUCERS = {
            "download_file", "sftp", "convert_to_file", "compression"
    };

    private static final String[] LEGACY_FLAT_FIELDS = {
            "file_url", "file_name", "file_size", "content_type"
    };

    @Test
    @DisplayName("Each file-producer's UPDATE block declares the canonical `file` key")
    void eachProducerDeclaresFileKey() throws IOException {
        String sql = readMigration(V221);
        StringBuilder failures = new StringBuilder();

        for (String type : FILE_PRODUCERS) {
            String outputsJson = extractOutputsBlock(sql, type, failures);
            if (outputsJson == null) continue;

            if (!outputsJson.contains("\"file\"")) {
                failures.append("  - node '").append(type)
                        .append("': `file` canonical key MISSING from outputs JSON. ")
                        .append("Marketplace + share preview rely on agents finding `output.file` in the doc to wire `<img src=\"{{...output.file}}\">`.\n");
            }
        }

        if (failures.length() > 0) {
            fail("V221 file-producer outputs contract violations:\n" + failures);
        }
    }

    @Test
    @DisplayName("PR2 round-trip: V221 outputs JSON key-set == NodeSpec.outputs() key-set for each producer (drift guard)")
    void v221MatchesNodeSpecOutputs() throws IOException {
        // Why this test exists: V221 documents the outputs the LLM agent sees;
        // NodeSpec.outputs() feeds the Inspector schema that the frontend renders;
        // both must agree on the exact key set or the agent invents SpEL the runtime
        // doesn't expose (or the Inspector shows phantom fields that resolve to null).
        // CLAUDE.md "3-Way Alignment" rule. Adding `duration_ms` to V221 without
        // updating the spec - or vice versa - silently breaks this contract.
        String sql = readMigration(V221);
        StringBuilder failures = new StringBuilder();

        Map<String, NodeSpec> specs = Map.of(
                "download_file",   new DownloadFileNodeSpec(),
                "sftp",            new SftpNodeSpec(),
                "convert_to_file", new ConvertToFileNodeSpec(),
                "compression",     new CompressionNodeSpec()
        );

        for (Map.Entry<String, NodeSpec> entry : specs.entrySet()) {
            String type = entry.getKey();
            String outputsJson = extractOutputsBlock(sql, type, failures);
            if (outputsJson == null) continue;

            Set<String> v221Keys = extractTopLevelJsonKeys(outputsJson);
            Set<String> specKeys = new LinkedHashSet<>();
            for (OutputFieldDef f : entry.getValue().definition().outputs()) {
                specKeys.add(f.key());
            }

            Set<String> inV221NotSpec = new LinkedHashSet<>(v221Keys);
            inV221NotSpec.removeAll(specKeys);
            Set<String> inSpecNotV221 = new LinkedHashSet<>(specKeys);
            inSpecNotV221.removeAll(v221Keys);

            if (!inV221NotSpec.isEmpty()) {
                failures.append("  - node '").append(type)
                        .append("': V221 documents keys ").append(inV221NotSpec)
                        .append(" but NodeSpec.outputs() does NOT declare them. ")
                        .append("Inspector won't show them; agent reads them in V221 and writes dead SpEL.\n");
            }
            if (!inSpecNotV221.isEmpty()) {
                failures.append("  - node '").append(type)
                        .append("': NodeSpec.outputs() declares keys ").append(inSpecNotV221)
                        .append(" but V221 does NOT document them. ")
                        .append("Inspector shows them; agent reading V221 doesn't know they exist.\n");
            }
        }

        if (failures.length() > 0) {
            fail("V221 ↔ NodeSpec.outputs() key-set drift:\n" + failures);
        }
    }

    @Test
    @DisplayName("V221 drops all legacy flat fields (PR2 clean break)")
    void legacyFlatKeysAreDropped() throws IOException {
        // PR2 (2026-05-15) clean break: the 4 producers stopped emitting the legacy flat
        // fields entirely (no dual-emit). The doc must match - otherwise the agent will
        // invent SpEL like `{{node.output.file_url}}` that resolves to nothing at runtime.
        String sql = readMigration(V221);
        StringBuilder failures = new StringBuilder();

        for (String type : FILE_PRODUCERS) {
            String outputsJson = extractOutputsBlock(sql, type, failures);
            if (outputsJson == null) continue;

            for (String legacy : LEGACY_FLAT_FIELDS) {
                if (outputsJson.contains("\"" + legacy + "\"")) {
                    failures.append("  - node '").append(type)
                            .append("': legacy `").append(legacy).append("` STILL DECLARED in outputs. ")
                            .append("PR2 clean break - the runtime no longer emits this; declaring it in the doc would mislead the agent into writing SpEL that resolves to nothing.\n");
                }
            }
        }

        if (failures.length() > 0) {
            fail("V221 legacy-drop contract violations:\n" + failures);
        }
    }

    /**
     * Extract the outputs JSON block for a given node type from the migration SQL.
     * Returns null and appends to {@code failures} if the block cannot be located.
     */
    private static String extractOutputsBlock(String sql, String type, StringBuilder failures) {
        int whereIdx = sql.indexOf("WHERE type = '" + type + "'");
        if (whereIdx < 0) {
            failures.append("  - node '").append(type)
                    .append("': no UPDATE block found in V221 (expected `WHERE type = '").append(type).append("'`)\n");
            return null;
        }
        int setIdx = sql.lastIndexOf("SET outputs = '", whereIdx);
        if (setIdx < 0) {
            failures.append("  - node '").append(type)
                    .append("': UPDATE block found but no `SET outputs = '...'` clause\n");
            return null;
        }
        int blockStart = sql.indexOf('\'', setIdx) + 1;
        int blockEnd = sql.indexOf("'::jsonb", blockStart);
        if (blockEnd < blockStart) {
            failures.append("  - node '").append(type)
                    .append("': SET outputs clause is not properly terminated with `'::jsonb`\n");
            return null;
        }
        return sql.substring(blockStart, blockEnd);
    }

    /**
     * Extract the top-level keys from a JSON object string (e.g. the value of V221's
     * `SET outputs = '{...}'::jsonb`). Lightweight regex over double-quoted keys at the
     * top brace level - sufficient for V221 since values are flat objects (no nested
     * keys would confuse the matcher). The migration shape is stable; if it grows nested
     * objects, this helper will need a real JSON parser.
     */
    private static Set<String> extractTopLevelJsonKeys(String json) {
        // Strip line comments (V221 has `-- Note: …` annotations) so they don't pollute
        // the regex with stray `"` characters.
        String stripped = json.replaceAll("(?m)--.*$", "");
        // V221 SQL escapes single quotes inside the JSON as ''; convert to single quotes
        // for parsing, then match unescaped double-quoted keys followed by a colon.
        String normalised = stripped.replace("''", "'");
        Pattern keyPattern = Pattern.compile("\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*:");
        Matcher m = keyPattern.matcher(normalised);
        Set<String> keys = new LinkedHashSet<>();
        // Track brace depth so we only pick up depth-1 keys (skip nested {"type":...}).
        int depth = 0;
        int lastEnd = 0;
        while (m.find()) {
            // Scan braces between lastEnd and m.start() to update depth.
            for (int i = lastEnd; i < m.start(); i++) {
                char c = normalised.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') depth--;
            }
            if (depth == 1) {
                keys.add(m.group(1));
            }
            lastEnd = m.end();
        }
        return keys;
    }

    private static String readMigration(String fileName) throws IOException {
        Path path = MIGRATION_DIR.resolve(fileName);
        if (!Files.exists(path)) {
            path = Paths.get("backend", "migration-service", "src", "main",
                    "resources", "db", "migration", fileName);
        }
        return Files.readString(path);
    }
}
