package com.apimarketplace.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tri-parity guard for the bridge catalog: the three sources of truth MUST
 * agree on which models each local-CLI bridge exposes.
 *
 * <ol>
 *   <li>{@code BridgeAllowlist.MODELS} in
 *       {@code shared-agent-lib/.../bridge/BridgeAllowlist.java} - the
 *       hand-curated source. One commit = one release.</li>
 *   <li>{@code ai.agent.providers.{claude-code,codex,gemini-cli,mistral-vibe}.models}
 *       in {@code agent-service/application.yml} - consumed at boot by
 *       {@code LLMProviderFactory}.</li>
 *   <li>V128 Flyway migration
 *       ({@code migration-service/.../V128__bridge_catalog_allowlist_v1.sql})
 *       - seeds {@code agent.model_config_overrides} + {@code auth.model_pricing}
 *       from the same list.</li>
 * </ol>
 *
 * <p>Any drift between the three (add a model in one, forget another) is an
 * incident waiting to happen: the picker shows a model that has no pricing
 * row, or pricing exists for a model the picker never surfaces. This test
 * asserts strict set equality across all three.
 *
 * <p>History: V120 previously seeded {@code claude-opus-4-6-cc}-style ids
 * with a platform-invented {@code -cc} suffix. V128 reconciles the seed
 * with upstream CLI-accepted ids (no suffix) and the paired change to
 * {@code mcp/bridge/adapters/claude-adapter.mjs} passes the model verbatim
 * so version selection is honoured. See the V128 migration docblock for
 * full context.
 */
@DisplayName("BridgeAllowlist ↔ application.yml ↔ V128 parity")
class BridgeProvidersHavePricingTest {

    private static final Set<String> BRIDGE_PROVIDERS =
            Set.of("claude-code", "codex", "gemini-cli", "mistral-vibe");

    private static final String AGENT_YML_REL =
            "agent-service/src/main/resources/application.yml";

    private static final String ALLOWLIST_JAVA_REL =
            "shared-agent-lib/src/main/java/com/apimarketplace/agent/bridge/BridgeAllowlist.java";

    private static final String V128_MIGRATION_REL =
            "migration-service/src/main/resources/db/migration/V128__bridge_catalog_allowlist_v1.sql";

    @Test
    @DisplayName("application.yml bridge models == BridgeAllowlist MODELS")
    void yamlMatchesAllowlist() throws IOException {
        Map<String, List<String>> yaml = readBridgeProvidersFromYaml();
        Map<String, Set<String>> allowlist = readBridgeAllowlistFromJava();

        assertThat(yaml.keySet())
                .as("yaml declares all 4 bridge providers")
                .containsExactlyInAnyOrderElementsOf(BRIDGE_PROVIDERS);
        assertThat(allowlist.keySet())
                .as("allowlist declares all 4 bridge providers")
                .containsExactlyInAnyOrderElementsOf(BRIDGE_PROVIDERS);

        for (String provider : BRIDGE_PROVIDERS) {
            Set<String> yamlSet = Set.copyOf(yaml.get(provider));
            Set<String> listSet = allowlist.get(provider);
            assertThat(yamlSet)
                    .as("yaml↔allowlist drift on %s", provider)
                    .isEqualTo(listSet);
        }
    }

    @Test
    @DisplayName("V128 migration seeds exactly the BridgeAllowlist pairs - no '-cc' anywhere")
    void v128SeedsAllowlistAndHasNoCcSuffix() throws IOException {
        Map<String, Set<String>> allowlist = readBridgeAllowlistFromJava();
        String v128 = Files.readString(resolveBackendFile(V128_MIGRATION_REL));

        // Strip SQL comment lines (-- …) before checking for the legacy
        // suffix: the docblock mentions "-cc" when describing V120's
        // historical state, which is intentional documentation.
        String executableSql = Arrays.stream(v128.split("\n"))
                .filter(line -> !line.stripLeading().startsWith("--"))
                .collect(Collectors.joining("\n"));

        assertThat(executableSql)
                .as("V128 executable SQL must not contain the legacy '-cc' suffix")
                .doesNotContain("-cc'")
                .doesNotContain("-cc\"");

        String normalized = normalizeWhitespace(v128);

        for (var entry : allowlist.entrySet()) {
            String provider = entry.getKey();
            for (String model : entry.getValue()) {
                String expected = "('" + provider + "', '" + model + "',";
                assertThat(normalized)
                        .as("V128 must seed (%s, %s)", provider, model)
                        .contains(expected);
            }
        }

        // Sanity on the 'bridge' literal count - use the comment-stripped SQL
        // so the docblock (which legitimately mentions 'bridge' in prose) is
        // excluded. Expected = rowCount per table + 1 ON CONFLICT update
        // clause per table = 2 * (total_rows + 1).
        String executableNormalized = normalizeWhitespace(executableSql);
        long rowCount = allowlist.values().stream().mapToLong(Set::size).sum();
        long bridgeLiterals = countOccurrences(executableNormalized, "'bridge'");
        assertThat(bridgeLiterals)
                .as("V128 executable SQL 'bridge' literal count = 2×(rowCount + 1) [2 tables, 1 update clause each]")
                .isEqualTo((rowCount + 1) * 2);
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

    private static String normalizeWhitespace(String t) { return t.replaceAll("\\s+", " "); }

    private static long countOccurrences(String hay, String needle) {
        if (needle.isEmpty()) return 0L;
        long c = 0; int i = 0;
        while ((i = hay.indexOf(needle, i)) != -1) { c++; i += needle.length(); }
        return c;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> readBridgeProvidersFromYaml() throws IOException {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(Files.readString(resolveBackendFile(AGENT_YML_REL)));
        Map<String, Object> ai = (Map<String, Object>) root.get("ai");
        Map<String, Object> agent = (Map<String, Object>) ai.get("agent");
        Map<String, Object> providers = (Map<String, Object>) agent.get("providers");

        Map<String, List<String>> bridges = new LinkedHashMap<>();
        providers.forEach((name, cfg) -> {
            if (!BRIDGE_PROVIDERS.contains(name)) return;
            Map<String, Object> pc = (Map<String, Object>) cfg;
            String csv = (String) pc.get("models");
            List<String> m = Arrays.stream(csv.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            bridges.put(name, m);
        });
        return bridges;
    }

    /**
     * Parse {@code BridgeAllowlist.java} by regex. We don't bring the
     * shared-agent-lib jar into auth-service as a test dep because it would
     * leak a runtime dependency for a single invariant check - the file is
     * plain Java constants and its grammar is narrow enough that regex is
     * robust here.
     */
    private static Map<String, Set<String>> readBridgeAllowlistFromJava() throws IOException {
        String src = Files.readString(resolveBackendFile(ALLOWLIST_JAVA_REL));
        // Isolate the MODELS = Map.of(...) block to avoid matching the
        // LITELLM_LOOKUP_ALIAS map below it.
        int mark = src.indexOf("MODELS = Map.of");
        assertThat(mark).as("MODELS block found").isGreaterThan(0);
        int close = src.indexOf(");", mark);
        String block = src.substring(mark, close);

        Map<String, Set<String>> out = new LinkedHashMap<>();
        var provPattern = java.util.regex.Pattern.compile(
                "\"(claude-code|codex|gemini-cli|mistral-vibe)\"\\s*,\\s*Set\\.of\\(([^)]*)\\)",
                java.util.regex.Pattern.DOTALL);
        var matcher = provPattern.matcher(block);
        while (matcher.find()) {
            String provider = matcher.group(1);
            String list = matcher.group(2);
            Set<String> models = java.util.Arrays.stream(list.split(","))
                    .map(s -> s.trim().replaceAll("[\"\\s]", ""))
                    .filter(s -> !s.isEmpty())
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            out.put(provider, models);
        }
        return out;
    }
}
