package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pin down canonical-serialisation invariants. These drive the signed
 * payload - if they break, deployed CE instances can no longer verify
 * bundles signed after the change.
 */
@DisplayName("CatalogBundlePayload - canonical JSON contract")
class CatalogBundlePayloadTest {

    private static ModelConfigOverrideEntity model(String provider, String modelId,
                                                   String displayName, BigDecimal priceIn) {
        ModelConfigOverrideEntity m = new ModelConfigOverrideEntity();
        m.setProvider(provider);
        m.setModelId(modelId);
        m.setDisplayName(displayName);
        m.setPriceInput(priceIn);
        return m;
    }

    @Test
    @DisplayName("Same inputs → byte-identical output (determinism)")
    void deterministicByteOutput() {
        Instant ts = Instant.parse("2026-04-21T10:00:00Z");
        List<ModelConfigOverrideEntity> models = List.of(
                model("anthropic", "claude-sonnet-4-6", "Sonnet", new BigDecimal("3.00")),
                model("openai",    "gpt-5",             "GPT-5",  new BigDecimal("1.25")));

        byte[] a = CatalogBundlePayload.canonicalBytes(1L, 1, "cloud", ts, models);
        byte[] b = CatalogBundlePayload.canonicalBytes(1L, 1, "cloud", ts, models);

        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("Model order-independence - same byte output regardless of input order")
    void sortedRegardlessOfInput() {
        Instant ts = Instant.parse("2026-04-21T10:00:00Z");
        var a = model("anthropic", "claude-sonnet-4-6", "s", new BigDecimal("3.00"));
        var b = model("openai",    "gpt-5",             "g", new BigDecimal("1.25"));

        byte[] out1 = CatalogBundlePayload.canonicalBytes(1L, 1, "cloud", ts, List.of(a, b));
        byte[] out2 = CatalogBundlePayload.canonicalBytes(1L, 1, "cloud", ts, List.of(b, a));

        assertThat(out1).isEqualTo(out2);
    }

    @Test
    @DisplayName("Keys sorted alphabetically - stable JSON field order")
    void keysAlphabetical() {
        Instant ts = Instant.parse("2026-04-21T10:00:00Z");
        byte[] bytes = CatalogBundlePayload.canonicalBytes(1L, 1, "cloud", ts,
                List.of(model("openai", "gpt-5", "GPT-5", new BigDecimal("1.25"))));
        String json = new String(bytes, StandardCharsets.UTF_8);

        // Root keys sorted: issuer, models, schemaVersion, snapshotAt, version
        assertThat(json.indexOf("\"issuer\""))
                .isLessThan(json.indexOf("\"models\""));
        assertThat(json.indexOf("\"models\""))
                .isLessThan(json.indexOf("\"schemaVersion\""));
        assertThat(json.indexOf("\"schemaVersion\""))
                .isLessThan(json.indexOf("\"snapshotAt\""));
        assertThat(json.indexOf("\"snapshotAt\""))
                .isLessThan(json.indexOf("\"version\""));
    }

    @Test
    @DisplayName("Null fields omitted entirely from output")
    void nullFieldsOmitted() {
        Instant ts = Instant.parse("2026-04-21T10:00:00Z");
        var m = new ModelConfigOverrideEntity();
        m.setProvider("openai");
        m.setModelId("gpt-5");
        m.setDisplayName("GPT-5");
        // everything else null

        String json = new String(CatalogBundlePayload.canonicalBytes(
                1L, 1, "cloud", ts, List.of(m)), StandardCharsets.UTF_8);

        assertThat(json).doesNotContain("\"description\"");
        assertThat(json).doesNotContain("\"priceInput\"");
        assertThat(json).doesNotContain("\"tier\"");
        // but always-present keys are there
        assertThat(json).contains("\"displayName\":\"GPT-5\"");
        assertThat(json).contains("\"provider\":\"openai\"");
    }

    @Test
    @DisplayName("BigDecimal serialised as string to preserve precision")
    void bigDecimalAsString() {
        Instant ts = Instant.parse("2026-04-21T10:00:00Z");
        var m = model("openai", "gpt-5", "GPT-5", new BigDecimal("1.2500"));
        String json = new String(CatalogBundlePayload.canonicalBytes(
                1L, 1, "cloud", ts, List.of(m)), StandardCharsets.UTF_8);

        // Plain text, not double-encoded, not losing trailing zero
        assertThat(json).contains("\"priceInput\":\"1.2500\"");
    }

    @Test
    @DisplayName("Derived credit fields NOT included - CE recomputes locally")
    void creditsExcluded() {
        Instant ts = Instant.parse("2026-04-21T10:00:00Z");
        var m = model("openai", "gpt-5", "GPT-5", new BigDecimal("1.25"));

        String json = new String(CatalogBundlePayload.canonicalBytes(
                1L, 1, "cloud", ts, List.of(m)), StandardCharsets.UTF_8);

        assertThat(json).doesNotContain("creditsInput");
        assertThat(json).doesNotContain("creditsOutput");
    }

    @Test
    @DisplayName("Nested modalities map serialises deterministically regardless of input ordering")
    void nestedModalitiesDeterministic() {
        Instant ts = Instant.parse("2026-04-21T10:00:00Z");

        // Build two entities with the SAME logical modalities but different
        // insertion order at both the outer and the nested level. JSONB
        // decode can produce either shape in the wild.
        Map<String, Object> nestedA = new LinkedHashMap<>();
        nestedA.put("max", 1024);
        nestedA.put("formats", List.of("png", "jpg"));

        Map<String, Object> modA = new LinkedHashMap<>();
        modA.put("input", List.of("text", "image"));
        modA.put("image", nestedA);

        Map<String, Object> nestedB = new HashMap<>();
        nestedB.put("formats", List.of("png", "jpg"));
        nestedB.put("max", 1024);

        Map<String, Object> modB = new HashMap<>();
        modB.put("image", nestedB);
        modB.put("input", List.of("text", "image"));

        var entA = model("openai", "gpt-5", "GPT-5", new BigDecimal("1.25"));
        entA.setModalities(modA);
        var entB = model("openai", "gpt-5", "GPT-5", new BigDecimal("1.25"));
        entB.setModalities(modB);

        byte[] outA = CatalogBundlePayload.canonicalBytes(1L, 1, "cloud", ts, List.of(entA));
        byte[] outB = CatalogBundlePayload.canonicalBytes(1L, 1, "cloud", ts, List.of(entB));

        assertThat(outA).isEqualTo(outB);

        // Also pin the nested ordering: "formats" < "max" must appear in that
        // alphabetical order inside the "image" sub-object.
        String json = new String(outA, StandardCharsets.UTF_8);
        assertThat(json.indexOf("\"formats\""))
                .isLessThan(json.indexOf("\"max\""));
    }

    @Test
    @DisplayName("canonicalBytes rejects null issuer/snapshotAt/models (defensive guard)")
    void canonicalBytesRejectsNulls() {
        Instant ts = Instant.parse("2026-04-21T10:00:00Z");
        List<ModelConfigOverrideEntity> models = List.of(
                model("openai", "gpt-5", "GPT-5", new BigDecimal("1.25")));

        assertThatThrownBy(() -> CatalogBundlePayload.canonicalBytes(1L, 1, null, ts, models))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CatalogBundlePayload.canonicalBytes(1L, 1, "cloud", null, models))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CatalogBundlePayload.canonicalBytes(1L, 1, "cloud", ts, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Version, schemaVersion, issuer, snapshotAt encoded at top level")
    void topLevelMetadata() {
        Instant ts = Instant.parse("2026-04-21T10:00:00Z");
        byte[] bytes = CatalogBundlePayload.canonicalBytes(42L, 3, "acme-cloud", ts,
                List.of(model("openai", "gpt-5", "GPT-5", new BigDecimal("1.25"))));
        String json = new String(bytes, StandardCharsets.UTF_8);

        assertThat(json).contains("\"version\":42");
        assertThat(json).contains("\"schemaVersion\":3");
        assertThat(json).contains("\"issuer\":\"acme-cloud\"");
        assertThat(json).contains("\"snapshotAt\":\"2026-04-21T10:00:00Z\"");
    }
}
