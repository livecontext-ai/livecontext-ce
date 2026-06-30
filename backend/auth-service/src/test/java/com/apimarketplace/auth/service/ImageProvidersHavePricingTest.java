package com.apimarketplace.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift guard for the image-generation pricing catalog: the two sources of
 * truth MUST agree on the per-image cost of every (provider, model) pair.
 *
 * <ol>
 *   <li>{@code ImageProviderCatalog.OPENAI} and {@code ImageProviderCatalog.GOOGLE}
 *       in {@code shared-agent-lib/.../imagegen/ImageProviderCatalog.java}
 *       - the hand-curated source consumed by orchestrator-service at runtime.</li>
 *   <li>V141 Flyway migration
 *       ({@code migration-service/.../V141__image_generation_and_websearch_pricing_seed.sql})
 *       - seeds {@code auth.model_pricing} with the same (provider, model,
 *       per-image rate) tuples.</li>
 * </ol>
 *
 * <p>Any drift between the two (add a quality tier in one, forget the other)
 * is an incident waiting to happen: an admin enables a tier in
 * ImageProviderCatalog, OpenAIImageProvider asks ModelPricingService for the
 * pseudo-model, the row is missing, and either the call rejects with
 * QUOTA_EXCEEDED (best case) or the default-rate fallback bills the user at
 * a token-priced rate that is meaningless for image generation (worst case).
 *
 * <p>This test reads {@code ImageProviderCatalog.java} via regex (same
 * approach as {@link BridgeProvidersHavePricingTest}) to avoid pulling
 * shared-agent-lib in as a test-only dependency for one invariant check.
 *
 * <p>Web search is still present in V141 for backward compatibility, but the
 * runtime price is controlled by {@code billing.websearch.credits-per-search}.
 * Its legacy row has no Java catalog counterpart and is asserted literally.
 */
@DisplayName("ImageProviderCatalog ↔ Flyway parity (V141 + V158)")
class ImageProvidersHavePricingTest {

    private static final String CATALOG_JAVA_REL =
            "agent-common/src/main/java/com/apimarketplace/agent/imagegen/ImageProviderCatalog.java";

    private static final String V141_MIGRATION_REL =
            "migration-service/src/main/resources/db/migration/V141__image_generation_and_websearch_pricing_seed.sql";

    /**
     * V158 added gpt-image-2 (2026-04-21 launch) to the catalog. Both V141
     * and V158 contribute to {@code auth.model_pricing}; the parity check
     * accepts a row matched by EITHER migration.
     */
    private static final String V158_MIGRATION_REL =
            "migration-service/src/main/resources/db/migration/V158__image_generation_pricing_rate_limits_and_gpt_image_2.sql";

    @Test
    @DisplayName("V141 + V158 seed exactly the (provider, model, perImageRate) entries declared in ImageProviderCatalog")
    void migrationsMatchCatalog() throws IOException {
        List<CatalogEntry> catalog = readCatalogFromJava();
        String v141 = stripSqlComments(Files.readString(resolveBackendFile(V141_MIGRATION_REL)));
        String v158 = stripSqlComments(Files.readString(resolveBackendFile(V158_MIGRATION_REL)));
        String combinedSeeds = v141 + "\n" + v158;

        assertThat(catalog)
                .as("ImageProviderCatalog declares at least one entry")
                .isNotEmpty();

        for (CatalogEntry e : catalog) {
            // Match the exact INSERT row: e.g. ('openai', 'gpt-image-1-low', 10, 0, 0,
            // We accept arbitrary whitespace around comma separators and we
            // assert input_rate matches the catalog rate, output_rate=0, fixed_cost=0.
            String pattern = String.format(
                    "\\('%s'\\s*,\\s*'%s'\\s*,\\s*%s\\s*,\\s*0\\s*,\\s*0\\s*,",
                    Pattern.quote(e.provider),
                    Pattern.quote(e.model),
                    Pattern.quote(e.perImageRate.stripTrailingZeros().toPlainString()));
            assertThat(combinedSeeds)
                    .as("V141+V158 must seed (%s, %s) with input_rate=%s, output_rate=0, fixed_cost=0",
                            e.provider, e.model, e.perImageRate.toPlainString())
                    .containsPattern(pattern);
        }
    }

    @Test
    @DisplayName("V141 keeps legacy web_search row with fixed_cost=1, input_rate=0")
    void v141SeedsWebSearch() throws IOException {
        String v141 = stripSqlComments(Files.readString(resolveBackendFile(V141_MIGRATION_REL)));
        // ('websearch', 'default', 0, 0, 1, ...)
        assertThat(v141)
                .as("V141 must seed (websearch, default) with input_rate=0, output_rate=0, fixed_cost=1")
                .containsPattern("\\('websearch'\\s*,\\s*'default'\\s*,\\s*0\\s*,\\s*0\\s*,\\s*1\\s*,");
    }

    @Test
    @DisplayName("V141 uses ON CONFLICT DO UPDATE for idempotent re-application")
    void v141IsIdempotent() throws IOException {
        String v141 = Files.readString(resolveBackendFile(V141_MIGRATION_REL));
        String normalized = v141.toUpperCase().replaceAll("\\s+", " ");
        assertThat(normalized)
                .as("V141 must use ON CONFLICT to re-align rates on re-apply")
                .contains("ON CONFLICT (PROVIDER, MODEL, EFFECTIVE_FROM)")
                .contains("DO UPDATE SET");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Path resolveBackendFile(String rel) {
        Path start = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = start; p != null; p = p.getParent()) {
            Path backend = p.resolve("backend");
            if (Files.isDirectory(backend)) return backend.resolve(rel);
            if (p.getFileName() != null && "backend".equals(p.getFileName().toString())) {
                return p.resolve(rel);
            }
        }
        throw new IllegalStateException("Could not locate 'backend/' ancestor from " + start);
    }

    /** Strips SQL comment lines so prose mentions don't false-match assertions. */
    private static String stripSqlComments(String sql) {
        StringBuilder out = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (!line.stripLeading().startsWith("--")) out.append(line).append('\n');
        }
        return out.toString();
    }

    /**
     * Parses {@code ImageProviderCatalog.java} for {@code Entry} declarations
     * inside the {@code OPENAI} and {@code GOOGLE} lists. Pattern:
     * <pre>
     *     new Entry("openai", "gpt-image-1-low",    new BigDecimal("10"))
     * </pre>
     */
    private static List<CatalogEntry> readCatalogFromJava() throws IOException {
        String src = Files.readString(resolveBackendFile(CATALOG_JAVA_REL));
        Pattern entryPattern = Pattern.compile(
                "new\\s+Entry\\s*\\(\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]+)\"\\s*,\\s*new\\s+BigDecimal\\s*\\(\\s*\"([^\"]+)\"\\s*\\)\\s*\\)");
        Matcher m = entryPattern.matcher(src);
        List<CatalogEntry> entries = new ArrayList<>();
        while (m.find()) {
            entries.add(new CatalogEntry(m.group(1), m.group(2), new BigDecimal(m.group(3))));
        }
        // Sanity: catalog must declare at least one OpenAI entry and at least
        // one Google entry - guards against the regex silently matching nothing
        // after a refactor.
        Map<String, Long> byProvider = new LinkedHashMap<>();
        entries.forEach(e -> byProvider.merge(e.provider, 1L, Long::sum));
        assertThat(byProvider)
                .as("ImageProviderCatalog must declare entries for both 'openai' and 'google'")
                .containsKeys("openai", "google");
        return entries;
    }

    private record CatalogEntry(String provider, String model, BigDecimal perImageRate) {}
}
