package com.apimarketplace.agent.config;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Invariant: every model key in {@code ai.agent.pricing} (application.yml)
 * must exist in V112 with identical input/output prices.
 *
 * <p>The YAML block is a DISPLAY-ONLY fallback consulted by the admin catalog
 * and tier labels. The billing path reads {@code auth.model_pricing}, which is
 * seeded from V112. If the two drift, the admin UI shows one price and the
 * user gets charged another.
 *
 * <p>Referenced by the comment at {@code application.yml} line ~183:
 * {@code "An audit-time regression test (ModelPricingConfigMatchesV112SeedTest)
 * verifies this invariant on every build."}
 */
class ModelPricingConfigMatchesV112SeedTest {

    private static final Path V112_PATH = Path.of(
            "../migration-service/src/main/resources/db/migration/V112__model_catalog_curated_v1_seed.sql");

    /**
     * Row pattern:
     * {@code ('provider', 'model_id', 'display_name', TRUE, 'curated', 1, input, output, NOW())}
     * Captures: provider, model_id, price_input, price_output.
     */
    private static final Pattern V112_ROW = Pattern.compile(
            "\\(\\s*'([^']+)'\\s*,\\s*'([^']+)'\\s*,\\s*'[^']*'\\s*,\\s*TRUE\\s*,\\s*'curated'\\s*,\\s*\\d+\\s*,\\s*"
                    + "([\\d.]+)\\s*,\\s*([\\d.]+)\\s*,\\s*NOW\\(\\)\\s*\\)");

    private static final double EPSILON = 1e-6;

    @Test
    @DisplayName("Every YAML ai.agent.pricing entry is present in V112 with identical input/output rates")
    void yamlPricingMatchesV112Seed() throws IOException {
        Map<String, ModelPricingConfig.PricingInfo> yamlPricing = loadYamlPricing();
        Map<String, double[]> v112Pricing = parseV112Pricing();

        assertThat(yamlPricing)
                .as("YAML ai.agent.pricing block must not be empty - binding probably failed")
                .isNotEmpty();
        assertThat(v112Pricing)
                .as("V112 seed must not be empty - regex probably failed to parse rows")
                .isNotEmpty();

        // One-way invariant: YAML is a subset of V112. V112 may carry models with
        // no YAML display entry (those only matter in the DB catalog), but every
        // YAML key MUST appear in V112 with matching prices - otherwise the
        // display/billing tuple drifts. Soft assertions so a single invocation
        // surfaces every drift, not just the first one encountered.
        SoftAssertions soft = new SoftAssertions();
        for (Map.Entry<String, ModelPricingConfig.PricingInfo> entry : yamlPricing.entrySet()) {
            String modelId = entry.getKey();
            ModelPricingConfig.PricingInfo yaml = entry.getValue();
            double[] v112 = v112Pricing.get(modelId);

            if (v112 == null) {
                soft.fail("YAML model '%s' (input=%.4f output=%.4f) is not seeded in V112 - "
                                + "either add it to V112 or remove it from application.yml",
                        modelId, yaml.getInput(), yaml.getOutput());
                continue;
            }

            double v112Input = v112[0];
            double v112Output = v112[1];

            soft.assertThat(yaml.getInput())
                    .as("Model '%s' input rate drift: YAML=%.4f vs V112=%.4f - "
                            + "admin UI and billing will disagree", modelId, yaml.getInput(), v112Input)
                    .isCloseTo(v112Input, org.assertj.core.data.Offset.offset(EPSILON));

            soft.assertThat(yaml.getOutput())
                    .as("Model '%s' output rate drift: YAML=%.4f vs V112=%.4f - "
                            + "admin UI and billing will disagree", modelId, yaml.getOutput(), v112Output)
                    .isCloseTo(v112Output, org.assertj.core.data.Offset.offset(EPSILON));
        }
        soft.assertAll();
    }

    @Test
    @DisplayName("V112 rows parse cleanly - catches regex drift if the seed file changes shape")
    void v112RegexParsesExpectedCount() throws IOException {
        Map<String, double[]> v112 = parseV112Pricing();
        // The V112 seed listed 59 pairs at commit time. Future edits to V112 MUST
        // either keep the regex intact or update this test. Floor guard - if someone
        // reformats rows and the regex silently matches zero, we want a loud failure.
        assertThat(v112).as("V112 parser matched too few rows - check the regex against the SQL file")
                .hasSizeGreaterThanOrEqualTo(40);
        // Sanity: canonical pairs that must exist
        assertThat(v112).containsKey("claude-sonnet-4-6");
        assertThat(v112).containsKey("gpt-5");
        assertThat(v112).containsKey("gemini-2.5-pro");
    }

    private Map<String, ModelPricingConfig.PricingInfo> loadYamlPricing() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application", new ClassPathResource("application.yml"));
        StandardEnvironment env = new StandardEnvironment();
        for (PropertySource<?> source : sources) {
            env.getPropertySources().addFirst(source);
        }
        ModelPricingConfig config = Binder.get(env).bind("ai.agent", ModelPricingConfig.class).get();
        return config.getPricing();
    }

    private Map<String, double[]> parseV112Pricing() throws IOException {
        assertThat(V112_PATH)
                .as("V112 migration file not found - tests must run from backend/agent-service cwd")
                .exists();
        String sql = Files.readString(V112_PATH);

        Map<String, double[]> out = new LinkedHashMap<>();
        Matcher m = V112_ROW.matcher(sql);
        while (m.find()) {
            String modelId = m.group(2);
            double input = Double.parseDouble(m.group(3));
            double output = Double.parseDouble(m.group(4));
            double[] prior = out.put(modelId, new double[]{input, output});
            if (prior != null) {
                fail("V112 has duplicate model_id '%s' - ON CONFLICT (provider, model_id) "
                        + "DO NOTHING can mask this at migration time, but the seed must be unique", modelId);
            }
        }
        return out;
    }
}
