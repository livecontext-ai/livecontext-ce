package com.apimarketplace.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parity guard for the decision provider: the models it DECLARES and the models it is
 * PRICED for must be the same set, in both editions.
 *
 * <ol>
 *   <li>{@code ai.agent.providers.typesafe.models} in agent-service's
 *       {@code application.yml} - read at boot by {@code LLMProviderFactory}, and what
 *       {@code TypeSafeSystemOneClient.requireDeclaredModel} refuses anything outside.</li>
 *   <li>The same key in the CE monolith's {@code application-ce.yml}.</li>
 *   <li>The {@code auth.model_pricing} rows seeded by
 *       {@code V504__typesafe_decision_model_catalog.sql}.</li>
 * </ol>
 *
 * <p><b>Why this matters more here than for the bridges.</b> The bridge equivalent of this
 * test ({@code BridgeProvidersHavePricingTest}) guards rows that are zero-rated, so drift
 * there mis-reports a cost. Here a declared model with no pricing row falls through
 * {@code ModelPricingService}'s silent default of 1.0 / 4.0 USD per 1M - which is roughly
 * 24x this vendor's real input rate, on top of output tokens it gives away free - and is
 * then multiplied by this provider's own margin lever. A single {@code models: jev-2} edit,
 * with no matching migration, is all it takes; the only trace would be one WARN per call.
 *
 * <p>Flyway migrations are immutable once applied, so a new model ships as a NEW migration
 * added to {@link #PRICING_MIGRATION_RELS}, never as an edit to V504.
 */
@DisplayName("Decision provider - declared models and priced models are the same set")
class DecisionProviderHasPricingTest {

    private static final String PROVIDER = "typesafe";

    private static final String AGENT_YML_REL =
            "agent-service/src/main/resources/application.yml";

    private static final String MONOLITH_CE_YML_REL =
            "monolith-service/src/main/resources/application-ce.yml";

    private static final java.util.List<String> PRICING_MIGRATION_RELS = java.util.List.of(
            "migration-service/src/main/resources/db/migration/V504__typesafe_decision_model_catalog.sql");

    /** Matches the (provider, model) pair of an auth.model_pricing VALUES row. */
    private static final Pattern PRICED_ROW = Pattern.compile(
            "\\('" + PROVIDER + "'\\s*,\\s*'([^']+)'");

    @Test
    @DisplayName("every model the cloud declares is priced, and every priced model is declared")
    void cloudYamlAndPricingAgree() throws IOException {
        Set<String> declared = declaredModels(AGENT_YML_REL);
        Set<String> priced = pricedModels();

        org.assertj.core.api.Assertions.assertThat(declared)
                .as("a declared model with no pricing row bills at the 1.0/4.0 default rates, "
                        + "multiplied by this provider's margin lever, with only a WARN to show for it")
                .isNotEmpty()
                .isEqualTo(priced);
    }

    @Test
    @DisplayName("CE declares exactly the same models, so a self-hoster runs what is priced")
    void ceYamlMatchesCloud() throws IOException {
        org.assertj.core.api.Assertions.assertThat(declaredModels(MONOLITH_CE_YML_REL))
                .as("CE and cloud must offer the same decision models: the migration that "
                        + "prices them runs in both editions")
                .isEqualTo(declaredModels(AGENT_YML_REL));
    }

    @Test
    @DisplayName("the catalogue row and the billing row name the same models")
    void catalogueAndBillingRowsAgree() throws IOException {
        // Two INSERTs in one migration, into two schemas. A model catalogued but not priced
        // is the same failure as one declared but not priced; a model priced but not
        // catalogued is invisible and pays for nothing.
        String sql = readMigrations();
        Set<String> catalogued = matchesOf(sql,
                Pattern.compile("\\('" + PROVIDER + "'\\s*,\\s*'([^']+)'\\s*,\\s*'"));

        org.assertj.core.api.Assertions.assertThat(catalogued)
                .as("model_config_overrides and model_pricing must name the same models")
                .isEqualTo(pricedModels());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Set<String> declaredModels(String ymlRel) throws IOException {
        Map<String, Object> root = new Yaml().load(Files.readString(resolveBackendFile(ymlRel)));
        Map<String, Object> providers = (Map<String, Object>)
                ((Map<String, Object>) ((Map<String, Object>) root.get("ai")).get("agent")).get("providers");
        Map<String, Object> provider = (Map<String, Object>) providers.get(PROVIDER);
        org.assertj.core.api.Assertions.assertThat(provider)
                .as("%s must declare a provider block in %s", PROVIDER, ymlRel)
                .isNotNull();
        return Arrays.stream(String.valueOf(provider.get("models")).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * The models priced in {@code auth.model_pricing}. Read from the statement that
     * inserts into it, so a row added to the catalogue alone does not satisfy this.
     */
    private static Set<String> pricedModels() throws IOException {
        String sql = readMigrations();
        int start = sql.indexOf("auth.model_pricing");
        org.assertj.core.api.Assertions.assertThat(start)
                .as("the pricing migration must insert into auth.model_pricing")
                .isNotEqualTo(-1);
        return matchesOf(sql.substring(start), PRICED_ROW);
    }

    private static Set<String> matchesOf(String text, Pattern pattern) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    private static String readMigrations() throws IOException {
        StringBuilder all = new StringBuilder();
        for (String rel : PRICING_MIGRATION_RELS) {
            all.append(Files.readString(resolveBackendFile(rel))).append('\n');
        }
        return all.toString();
    }

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
}
