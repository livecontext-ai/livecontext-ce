package com.apimarketplace.catalog.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Bootstraps the catalog schema from a pre-exported SQL dump (catalog-data.sql.gz).
 * Runs BEFORE CatalogSeedService (Order 1 vs default) so YAML-based seeds can
 * still override or augment the data.
 *
 * Activation: catalog.seed.enabled=true AND catalog-data.sql.gz exists in seed path.
 * Skip condition: catalog.apis already has data (idempotent - only seeds empty DB).
 */
@Service
@ConditionalOnProperty(name = "catalog.seed.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class CatalogDataBootstrapService {

    private static final String LEGACY_CREDENTIAL_MODE_COLUMN = "credential_mode";
    private static final String REPAIR_IMPORTED_PLATFORM_CREDENTIAL_NAMES_SQL = """
            WITH per_api_credential AS (
                SELECT a.id AS api_id, MIN(tc.credential_name) AS credential_name
                FROM catalog.apis a
                JOIN catalog.api_tools at ON at.api_id = a.id
                JOIN catalog.tool_credentials tc ON tc.api_tool_id = at.id
                JOIN catalog.credentials c ON c.credential_name = tc.credential_name
                WHERE a.source = 'import'
                  AND tc.credential_name IS NOT NULL
                  AND btrim(tc.credential_name) <> ''
                GROUP BY a.id
                HAVING COUNT(DISTINCT tc.credential_name) = 1
            ),
            unique_icon_credential AS (
                SELECT c.icon_slug, MIN(c.credential_name) AS credential_name
                FROM catalog.credentials c
                WHERE c.icon_slug IS NOT NULL
                  AND btrim(c.icon_slug) <> ''
                  AND c.credential_name IS NOT NULL
                  AND btrim(c.credential_name) <> ''
                GROUP BY c.icon_slug
                HAVING COUNT(DISTINCT c.credential_name) = 1
            ),
            resolved_credentials AS (
                SELECT a.id AS api_id,
                       COALESCE(pac.credential_name, uic.credential_name) AS credential_name
                FROM catalog.apis a
                LEFT JOIN per_api_credential pac ON pac.api_id = a.id
                LEFT JOIN unique_icon_credential uic ON uic.icon_slug = a.icon_slug
                WHERE a.source = 'import'
            )
            UPDATE catalog.apis a
            SET platform_credential_name = rc.credential_name,
                updated_at = FLOOR(EXTRACT(EPOCH FROM now()) * 1000)::bigint
            FROM resolved_credentials rc
            WHERE a.id = rc.api_id
              AND rc.credential_name IS NOT NULL
              AND a.platform_credential_name IS DISTINCT FROM rc.credential_name
            """;

    private final CatalogSeedConfig config;
    private final JdbcTemplate jdbcTemplate;
    private final ResourceLoader resourceLoader;

    /**
     * Called by CatalogSeedService before YAML-based imports.
     * NOT an event listener - avoids race conditions with parallel virtual threads.
     */
    public void bootstrapIfNeeded() {
        try {
            // Check if catalog already has bulk data (Flyway seeds a few APIs, we need 500+)
            // Skip only if we've already bootstrapped (threshold: more than 10 APIs means dump was loaded)
            Integer apiCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*)::int FROM catalog.apis", Integer.class);
            if (apiCount != null && apiCount > 10) {
                log.info("[CatalogBootstrap] Catalog already has {} APIs - skipping SQL bootstrap", apiCount);
                return;
            }

            // Look for catalog-data.sql.gz in seed path
            String seedPath = config.getPath();
            File sqlGzFile = new File(seedPath, "catalog-data.sql.gz");
            if (!sqlGzFile.exists()) {
                // Try classpath
                Resource classpathResource = resourceLoader.getResource("classpath:catalog-seeds/catalog-data.sql.gz");
                if (!classpathResource.exists()) {
                    log.info("[CatalogBootstrap] No catalog-data.sql.gz found - skipping SQL bootstrap");
                    return;
                }
                executeSqlDump(classpathResource.getInputStream());
                return;
            }

            executeSqlDump(new FileInputStream(sqlGzFile));
        } catch (Exception e) {
            log.error("[CatalogBootstrap] Failed to bootstrap catalog data", e);
        }
    }

    private void executeSqlDump(InputStream compressedStream) throws IOException {
        log.info("[CatalogBootstrap] Loading catalog data from SQL dump...");
        long start = System.currentTimeMillis();
        int statementCount = 0;

        // Clear imported data while preserving custom (user-registered) APIs.
        // Custom APIs have source='custom'; imported APIs have source='import' (default).
        // Delete dependent rows first (FK cascading), then the apis themselves.
        // Shared tables (categories, tool_names, tool_categories, seed_state) are fully cleared
        // since the dump repopulates them.
        jdbcTemplate.execute(
                "DELETE FROM catalog.credentials c WHERE "
                + "  c.metadata ->> 'source' = 'api-migration'"
                + "  OR EXISTS ("
                + "    SELECT 1 FROM catalog.tool_credentials tc"
                + "    JOIN catalog.api_tools at ON at.id = tc.api_tool_id"
                + "    JOIN catalog.apis a ON a.id = at.api_id"
                + "    WHERE a.source = 'import' AND tc.credential_id = c.id"
                + "  )"
                + "  OR EXISTS ("
                + "    SELECT 1 FROM catalog.apis a"
                + "    WHERE a.source = 'import'"
                + "      AND a.platform_credential_name = c.credential_name"
                + "  )");
        jdbcTemplate.execute(
                "DELETE FROM catalog.tool_credentials WHERE api_tool_id IN ("
                + "  SELECT at.id FROM catalog.api_tools at JOIN catalog.apis a ON at.api_id = a.id WHERE a.source = 'import'"
                + ")");
        jdbcTemplate.execute(
                "DELETE FROM catalog.tool_responses WHERE tool_id IN ("
                + "  SELECT at.id FROM catalog.api_tools at JOIN catalog.apis a ON at.api_id = a.id WHERE a.source = 'import'"
                + ")");
        jdbcTemplate.execute(
                "DELETE FROM catalog.api_tool_parameters WHERE api_tool_id IN ("
                + "  SELECT at.id FROM catalog.api_tools at JOIN catalog.apis a ON at.api_id = a.id WHERE a.source = 'import'"
                + ")");
        jdbcTemplate.execute(
                "DELETE FROM catalog.lexical_search_index WHERE api_tool_id IN ("
                + "  SELECT at.id FROM catalog.api_tools at JOIN catalog.apis a ON at.api_id = a.id WHERE a.source = 'import'"
                + ")");
        jdbcTemplate.execute(
                "DELETE FROM catalog.api_tool_monetization WHERE api_tool_id IN ("
                + "  SELECT at.id FROM catalog.api_tools at JOIN catalog.apis a ON at.api_id = a.id WHERE a.source = 'import'"
                + ")");
        jdbcTemplate.execute(
                "DELETE FROM catalog.api_tools WHERE api_id IN ("
                + "  SELECT id FROM catalog.apis WHERE source = 'import'"
                + ")");
        jdbcTemplate.execute("DELETE FROM catalog.apis WHERE source = 'import'");
        // Shared reference tables - safe to truncate since the dump repopulates them
        // and custom APIs reference them by ID (which the dump restores).
        jdbcTemplate.execute("TRUNCATE catalog.api_categories, catalog.tool_names, "
                + "catalog.tool_categories, catalog.catalog_seed_state CASCADE");

        try (var gzis = new GZIPInputStream(compressedStream);
             var reader = new BufferedReader(new InputStreamReader(gzis, StandardCharsets.UTF_8))) {

            StringBuilder currentStatement = new StringBuilder();
            boolean inCopyBlock = false;
            String copyStatement = null;

            String line;
            while ((line = reader.readLine()) != null) {
                // Skip comments and empty lines
                if (line.startsWith("--") || (line.isBlank() && !inCopyBlock)) {
                    continue;
                }
                if (!inCopyBlock && line.startsWith("\\")) {
                    currentStatement.setLength(0);
                    continue;
                }

                // Handle COPY ... FROM stdin blocks
                if (line.startsWith("COPY ") && line.contains("FROM stdin")) {
                    inCopyBlock = true;
                    // The line already contains "FROM stdin;" - use it as-is for COPY API
                    // Remove trailing semicolon for PG COPY API
                    copyStatement = line.endsWith(";") ? line.substring(0, line.length() - 1) : line;
                    currentStatement.setLength(0);
                    continue;
                }

                if (inCopyBlock) {
                    if (line.equals("\\.")) {
                        // End of COPY block - execute the whole COPY
                        String copyData = currentStatement.toString();
                        executeCopyBlock(copyStatement, copyData);
                        inCopyBlock = false;
                        copyStatement = null;
                        currentStatement.setLength(0);
                        statementCount++;
                    } else {
                        if (currentStatement.length() > 0) {
                            currentStatement.append('\n');
                        }
                        currentStatement.append(line);
                    }
                    continue;
                }

                // Regular SQL statements
                currentStatement.append(line);
                if (line.endsWith(";")) {
                    String sql = currentStatement.toString().trim();
                    if (!sql.isEmpty() && !sql.startsWith("SET ") && !sql.startsWith("SELECT ")) {
                        try {
                            jdbcTemplate.execute(sql);
                            statementCount++;
                        } catch (Exception e) {
                            log.warn("[CatalogBootstrap] Statement failed (continuing): {}", e.getMessage());
                        }
                    }
                    currentStatement.setLength(0);
                }
            }
        }

        jdbcTemplate.execute(REPAIR_IMPORTED_PLATFORM_CREDENTIAL_NAMES_SQL);

        rebuildLexicalIndexForImportedTools();

        long elapsed = System.currentTimeMillis() - start;
        Integer finalCount = jdbcTemplate.queryForObject("SELECT COUNT(*)::int FROM catalog.apis", Integer.class);
        log.info("[CatalogBootstrap] Loaded {} APIs from SQL dump ({} statements, {}ms)",
                finalCount, statementCount, elapsed);
    }

    /**
     * Rebuild the lexical search index for dump-loaded ({@code source='import'}) tools.
     *
     * <p>{@code catalog-data.sql.gz} ships the catalog tables but NOT
     * {@code catalog.lexical_search_index} (the enriched index is derived data, excluded
     * from the dump). Without this, a fresh CE loads 12k+ tools that the agent's tool-search
     * - which reads {@code lexical_search_index} - cannot find (only YAML-seeded tools get
     * indexed by {@code CatalogSeedService}). This rebuilds a baseline index from the loaded
     * rows (tool name, provider, resource, action, endpoint, summary, category); the DB
     * tsvector trigger turns those into a searchable {@code tsv_combined}. AI-enriched
     * keywords/synonyms/use-cases are not reconstructed (they existed only in the excluded
     * index), so relevance is baseline rather than full - but discoverability by name,
     * provider and summary is restored. Idempotent: only inserts rows for tools that have
     * no lexical entry yet, so YAML-seeded rows are never clobbered. Non-fatal on error -
     * the catalog data itself already loaded.
     */
    private void rebuildLexicalIndexForImportedTools() {
        try {
            int rows = jdbcTemplate.update("""
                    INSERT INTO catalog.lexical_search_index
                        (api_tool_id, tool_name, provider, resource, action, endpoint, summary,
                         category, subcategory, keywords_primary)
                    SELECT at.id,
                           LEFT(COALESCE(tn.name, at.tool_slug, ''), 255),
                           LEFT(a.api_name, 100),
                           LEFT(COALESCE(sub.name, cat.name, ''), 100),
                           at.method,
                           at.endpoint,
                           at.description,
                           cat.name,
                           sub.name,
                           ARRAY[a.api_name, a.api_slug]
                    FROM catalog.api_tools at
                    JOIN catalog.apis a ON a.id = at.api_id
                    LEFT JOIN catalog.tool_names tn ON tn.id::text = at.tool_name_id
                    LEFT JOIN catalog.api_categories cat ON cat.id = a.category_id
                    LEFT JOIN catalog.api_subcategories sub ON sub.id = a.subcategory_id
                    WHERE a.source = 'import'
                      AND NOT EXISTS (
                          SELECT 1 FROM catalog.lexical_search_index lsi WHERE lsi.api_tool_id = at.id
                      )
                    """);
            log.info("[CatalogBootstrap] Rebuilt lexical search index for {} imported tools", rows);
        } catch (Exception e) {
            log.error("[CatalogBootstrap] Failed to rebuild lexical index for imported tools: {}", e.getMessage(), e);
        }
    }

    private void executeCopyBlock(String copyStatement, String data) {
        CopyBlock copyBlock = adaptLegacyCopyBlock(copyStatement, data);
        try {
            jdbcTemplate.execute((java.sql.Connection conn) -> {
                var pgConn = conn.unwrap(org.postgresql.PGConnection.class);
                var copyManager = pgConn.getCopyAPI();
                var sql = copyBlock.statement();
                byte[] bytes = (copyBlock.data() + "\n").getBytes(StandardCharsets.UTF_8);
                try {
                    copyManager.copyIn(sql, new ByteArrayInputStream(bytes));
                } catch (IOException ioe) {
                    throw new java.sql.SQLException("COPY I/O error", ioe);
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("[CatalogBootstrap] COPY failed for '{}': {}",
                    copyBlock.statement().substring(0, Math.min(80, copyBlock.statement().length())), e.getMessage());
        }
    }

    CopyBlock adaptLegacyCopyBlock(String copyStatement, String data) {
        CopyBlock copyBlock = new CopyBlock(copyStatement, data);
        if (copyStatement.startsWith("COPY catalog.apis ")) {
            if (copyStatement.contains(LEGACY_CREDENTIAL_MODE_COLUMN)) {
                copyBlock = dropLegacyApisCredentialModeColumn(copyBlock.statement(), copyBlock.data());
            }
        }

        if (copyBlock.statement().startsWith("COPY catalog.credentials ")) {
            copyBlock = filterLegacyCredentialRows(copyBlock.statement(), copyBlock.data());
        }

        if (copyBlock.statement().startsWith("COPY catalog.tool_credentials ")) {
            copyBlock = deduplicateLegacyToolCredentialRows(copyBlock.statement(), copyBlock.data());
        }

        return copyBlock;
    }

    private CopyBlock dropLegacyApisCredentialModeColumn(String copyStatement, String data) {
        int columnsStart = copyStatement.indexOf('(');
        int columnsEnd = copyStatement.indexOf(") FROM stdin");
        if (columnsStart < 0 || columnsEnd < columnsStart) {
            return new CopyBlock(copyStatement, data);
        }

        String[] columns = copyStatement.substring(columnsStart + 1, columnsEnd).split(",");
        int legacyColumnIndex = -1;
        StringBuilder normalizedColumns = new StringBuilder();
        for (int i = 0; i < columns.length; i++) {
            String column = columns[i].trim();
            if (LEGACY_CREDENTIAL_MODE_COLUMN.equals(column)) {
                legacyColumnIndex = i;
                continue;
            }
            if (normalizedColumns.length() > 0) {
                normalizedColumns.append(", ");
            }
            normalizedColumns.append(column);
        }

        if (legacyColumnIndex < 0) {
            return new CopyBlock(copyStatement, data);
        }

        String normalizedStatement = copyStatement.substring(0, columnsStart + 1)
                + normalizedColumns
                + copyStatement.substring(columnsEnd);
        String normalizedData = removeCopyColumn(data, legacyColumnIndex);
        return new CopyBlock(normalizedStatement, normalizedData);
    }

    private CopyBlock filterLegacyCredentialRows(String copyStatement, String data) {
        int metadataIndex = findCopyColumnIndex(copyStatement, "metadata");
        if (metadataIndex < 0 || data == null || data.isEmpty()) {
            return new CopyBlock(copyStatement, data);
        }

        StringBuilder filteredData = new StringBuilder(data.length());
        for (String row : data.split("\\R", -1)) {
            String[] values = row.split("\t", -1);
            if (metadataIndex >= values.length || !isImportedCredentialMetadata(values[metadataIndex])) {
                continue;
            }
            if (filteredData.length() > 0) {
                filteredData.append('\n');
            }
            filteredData.append(row);
        }
        return new CopyBlock(copyStatement, filteredData.toString());
    }

    private boolean isImportedCredentialMetadata(String metadata) {
        return metadata != null
                && (metadata.contains("\"source\": \"api-migration\"")
                || metadata.contains("\"source\":\"api-migration\""));
    }

    private CopyBlock deduplicateLegacyToolCredentialRows(String copyStatement, String data) {
        int apiToolIdIndex = findCopyColumnIndex(copyStatement, "api_tool_id");
        int credentialNameIndex = findCopyColumnIndex(copyStatement, "credential_name");
        int variantIndex = findCopyColumnIndex(copyStatement, "variant");
        if (apiToolIdIndex < 0 || credentialNameIndex < 0 || data == null || data.isEmpty()) {
            return new CopyBlock(copyStatement, data);
        }

        Set<String> seenKeys = new LinkedHashSet<>();
        StringBuilder deduplicatedData = new StringBuilder(data.length());
        for (String row : data.split("\\R", -1)) {
            String[] values = row.split("\t", -1);
            if (apiToolIdIndex >= values.length || credentialNameIndex >= values.length) {
                continue;
            }

            String variant = variantIndex >= 0 && variantIndex < values.length ? values[variantIndex] : "primary";
            String key = values[apiToolIdIndex] + '\t' + values[credentialNameIndex] + '\t' + variant;
            if (!seenKeys.add(key)) {
                continue;
            }

            if (deduplicatedData.length() > 0) {
                deduplicatedData.append('\n');
            }
            deduplicatedData.append(row);
        }
        return new CopyBlock(copyStatement, deduplicatedData.toString());
    }

    private int findCopyColumnIndex(String copyStatement, String targetColumn) {
        int columnsStart = copyStatement.indexOf('(');
        int columnsEnd = copyStatement.indexOf(") FROM stdin");
        if (columnsStart < 0 || columnsEnd < columnsStart) {
            return -1;
        }

        String[] columns = copyStatement.substring(columnsStart + 1, columnsEnd).split(",");
        for (int i = 0; i < columns.length; i++) {
            if (targetColumn.equals(columns[i].trim())) {
                return i;
            }
        }
        return -1;
    }

    private String removeCopyColumn(String data, int columnIndex) {
        if (data == null || data.isEmpty()) {
            return data;
        }

        StringBuilder normalizedData = new StringBuilder(data.length());
        String[] rows = data.split("\\R", -1);
        for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
            if (rowIndex > 0) {
                normalizedData.append('\n');
            }
            normalizedData.append(removeCopyColumnFromRow(rows[rowIndex], columnIndex));
        }
        return normalizedData.toString();
    }

    private String removeCopyColumnFromRow(String row, int columnIndex) {
        String[] values = row.split("\t", -1);
        if (columnIndex >= values.length) {
            return row;
        }

        StringBuilder normalizedRow = new StringBuilder(row.length());
        for (int i = 0; i < values.length; i++) {
            if (i == columnIndex) {
                continue;
            }
            if (normalizedRow.length() > 0) {
                normalizedRow.append('\t');
            }
            normalizedRow.append(values[i]);
        }
        return normalizedRow.toString();
    }

    record CopyBlock(String statement, String data) {
    }
}
