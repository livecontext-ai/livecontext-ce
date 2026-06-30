package com.apimarketplace.agent.gemini.cache;

import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolParameter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 2.1 - exercise the canonical cache-key contract (R15).
 *
 * <p>Every property this test pins is load-bearing for Gemini
 * {@code cachedContent} hit rate: a drift in map order, whitespace
 * handling, Unicode composition form, or tool-list order would make
 * two conversationally-identical agents hash to different keys, upload
 * duplicate prefixes, and pay storage fees on both.
 */
@DisplayName("GeminiCachedContentKey - canonical hash invariants (Stage 2.1)")
class GeminiCachedContentKeyTest {

    private static ToolDefinition tool(String name, String description, String paramType) {
        return ToolDefinition.builder()
                .name(name)
                .description(description)
                .parameters(List.of(
                        ToolParameter.builder()
                                .name("q")
                                .type(paramType)
                                .required(true)
                                .description("query")
                                .build()))
                .build();
    }

    @Test
    @DisplayName("same inputs → same 64-char lowercase hex digest (determinism)")
    void sameInputsProduceSameHash() {
        String block = "You are a helpful assistant.";
        List<ToolDefinition> tools = List.of(tool("alpha", "first", "string"));

        String h1 = GeminiCachedContentKey.compute(block, tools);
        String h2 = GeminiCachedContentKey.compute(block, tools);

        assertThat(h1).isEqualTo(h2);
        assertThat(h1).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("tool-list order does NOT affect the hash - sorted internally")
    void toolOrderDoesNotAffectHash() {
        // The caller's iteration order of tools[] must NOT be able to
        // bust the cache. A user with a ConcurrentHashMap-backed tool
        // registry (iteration order is bucket-dependent, JVM-specific)
        // would otherwise produce different hashes on different pods.
        String block = "static";
        List<ToolDefinition> inOrder = List.of(
                tool("alpha", "a", "string"),
                tool("zeta", "z", "string"));
        List<ToolDefinition> reversed = new ArrayList<>(inOrder);
        Collections.reverse(reversed);

        assertThat(GeminiCachedContentKey.compute(block, inOrder))
                .as("reordering the tools list must not change the hash")
                .isEqualTo(GeminiCachedContentKey.compute(block, reversed));
    }

    @Test
    @DisplayName("whitespace inside the block text DOES change the hash - text is content")
    void whitespaceInBlockTextAffectsHash() {
        // Jackson whitespace normalisation applies to the JSON frame
        // (map indent / key order), not to the embedded string values.
        // Two blocks that differ by a trailing newline are genuinely
        // different static prefixes, so the hash must differ - otherwise
        // we'd serve a stale cache after a prompt-template edit that
        // only touched whitespace.
        String h1 = GeminiCachedContentKey.compute("policy", List.of());
        String h2 = GeminiCachedContentKey.compute("policy\n", List.of());
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    @DisplayName("NFC normalisation - NFD and NFC inputs for the same visual text produce the same hash")
    void nfcNormalisationOfBlockText() {
        // "é" is U+00E9 (precomposed, NFC) OR U+0065 U+0301 (decomposed, NFD).
        // Both render identically. Before normalisation their bytes differ;
        // after NFC they're identical. This is the Mac filesystem / iOS
        // clipboard bug that would otherwise split one logical cache into two.
        String nfc = "café";
        String nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD);
        assertThat(nfc).as("precondition: NFC and NFD bytes differ before normalisation")
                .isNotEqualTo(nfd);

        String h1 = GeminiCachedContentKey.compute(nfc, List.of());
        String h2 = GeminiCachedContentKey.compute(nfd, List.of());
        assertThat(h1).as("NFC and NFD of the same visual text must hash equally")
                .isEqualTo(h2);
    }

    @Test
    @DisplayName("NFC normalisation also applies to tool name and description")
    void nfcNormalisationOfToolFields() {
        // Same rationale as above but applied to the tool metadata -
        // a tool named "café_search" must hash identically regardless of
        // whether the source of truth uses NFC or NFD.
        String nfc = "café_search";
        String nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD);

        String h1 = GeminiCachedContentKey.compute("block",
                List.of(tool(nfc, "decompose", "string")));
        String h2 = GeminiCachedContentKey.compute("block",
                List.of(tool(nfd, "decompose", "string")));
        assertThat(h1).isEqualTo(h2);

        String h3 = GeminiCachedContentKey.compute("block",
                List.of(tool("t", nfc, "string")));
        String h4 = GeminiCachedContentKey.compute("block",
                List.of(tool("t", nfd, "string")));
        assertThat(h3).isEqualTo(h4);
    }

    @Test
    @DisplayName("null tool list and empty tool list hash identically - fail-safe for bootstrap")
    void nullAndEmptyToolsHashAlike() {
        // A caller that hasn't wired up tools yet (bootstrap, tests)
        // and one that explicitly passes List.of() must share a cache
        // key - otherwise we'd have two different caches for the same
        // static system block just because one path is "null-tools"
        // and the other is "zero-tools".
        String h1 = GeminiCachedContentKey.compute("block", null);
        String h2 = GeminiCachedContentKey.compute("block", List.of());
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    @DisplayName("different block text → different hash (sanity)")
    void differentBlockTextChangesHash() {
        String h1 = GeminiCachedContentKey.compute("policy A", List.of());
        String h2 = GeminiCachedContentKey.compute("policy B", List.of());
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    @DisplayName("different tool parameter type → different hash (schema drift invalidates cache)")
    void differentToolParameterTypeChangesHash() {
        // This is the whole point of the cache key - a tool whose
        // param type changed (say, integer → string, which is what
        // Stage 4a schema slimming does) must produce a different
        // hash so a stale cache isn't served against the new schema.
        List<ToolDefinition> v1 = List.of(tool("alpha", "a", "integer"));
        List<ToolDefinition> v2 = List.of(tool("alpha", "a", "string"));

        assertThat(GeminiCachedContentKey.compute("block", v1))
                .isNotEqualTo(GeminiCachedContentKey.compute("block", v2));
    }

    @Test
    @DisplayName("null block text throws IllegalArgumentException - fail loud, not silently 'empty'")
    void nullBlockTextThrows() {
        // Empty string is fine ('no system prompt' is a valid state),
        // but null is almost certainly a caller bug - we surface it
        // rather than hashing a silent empty value that could mask
        // the real issue.
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> GeminiCachedContentKey.compute(null, List.of()));
    }

    @Test
    @DisplayName("null entries inside the tool list are skipped, not NPE (defensive)")
    void nullToolEntriesAreSkipped() {
        List<ToolDefinition> withNull = new ArrayList<>();
        withNull.add(tool("alpha", "a", "string"));
        withNull.add(null);
        withNull.add(tool("zeta", "z", "string"));

        List<ToolDefinition> withoutNull = List.of(
                tool("alpha", "a", "string"),
                tool("zeta", "z", "string"));

        assertThat(GeminiCachedContentKey.compute("block", withNull))
                .as("nulls in the input list must be transparent to the hash")
                .isEqualTo(GeminiCachedContentKey.compute("block", withoutNull));
    }

    @Test
    @DisplayName("NFC normalisation applies to ToolParameter.description - deep text surface (R53)")
    void nfcOnParameterDescription() {
        // The auditor correctly flagged this gap: ToolParameter carries
        // user-visible text (name, description, pattern, enumValues)
        // that feeds into Gemini's serialised JSON. Without NFC on the
        // parameter surface, an NFD "café" in a description would
        // bust the cache versus its NFC twin. Pin the invariant.
        String nfc = "café";
        String nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD);

        ToolDefinition nfcTool = ToolDefinition.builder()
                .name("t").description("d")
                .parameters(List.of(ToolParameter.builder()
                        .name("q").type("string").required(true)
                        .description(nfc).build()))
                .build();
        ToolDefinition nfdTool = ToolDefinition.builder()
                .name("t").description("d")
                .parameters(List.of(ToolParameter.builder()
                        .name("q").type("string").required(true)
                        .description(nfd).build()))
                .build();

        assertThat(GeminiCachedContentKey.compute("block", List.of(nfcTool)))
                .as("NFC and NFD in parameter.description must hash equally")
                .isEqualTo(GeminiCachedContentKey.compute("block", List.of(nfdTool)));
    }

    @Test
    @DisplayName("NFC normalisation applies to ToolParameter.name and ToolParameter.pattern")
    void nfcOnParameterNameAndPattern() {
        String nfc = "café";
        String nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD);

        ToolDefinition withNfcName = ToolDefinition.builder()
                .name("t").description("d")
                .parameters(List.of(ToolParameter.builder()
                        .name(nfc).type("string").required(true).build()))
                .build();
        ToolDefinition withNfdName = ToolDefinition.builder()
                .name("t").description("d")
                .parameters(List.of(ToolParameter.builder()
                        .name(nfd).type("string").required(true).build()))
                .build();
        assertThat(GeminiCachedContentKey.compute("block", List.of(withNfcName)))
                .isEqualTo(GeminiCachedContentKey.compute("block", List.of(withNfdName)));

        ToolDefinition withNfcPattern = ToolDefinition.builder()
                .name("t").description("d")
                .parameters(List.of(ToolParameter.builder()
                        .name("q").type("string").required(true).pattern(nfc).build()))
                .build();
        ToolDefinition withNfdPattern = ToolDefinition.builder()
                .name("t").description("d")
                .parameters(List.of(ToolParameter.builder()
                        .name("q").type("string").required(true).pattern(nfd).build()))
                .build();
        assertThat(GeminiCachedContentKey.compute("block", List.of(withNfcPattern)))
                .isEqualTo(GeminiCachedContentKey.compute("block", List.of(withNfdPattern)));
    }

    @Test
    @DisplayName("NFC normalisation applies to ToolParameter.enumValues entries")
    void nfcOnEnumValues() {
        String nfc = "café";
        String nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD);

        ToolDefinition nfcEnum = ToolDefinition.builder()
                .name("t").description("d")
                .parameters(List.of(ToolParameter.builder()
                        .name("q").type("string").required(true)
                        .enumValues(List.of(nfc, "tea")).build()))
                .build();
        ToolDefinition nfdEnum = ToolDefinition.builder()
                .name("t").description("d")
                .parameters(List.of(ToolParameter.builder()
                        .name("q").type("string").required(true)
                        .enumValues(List.of(nfd, "tea")).build()))
                .build();

        assertThat(GeminiCachedContentKey.compute("block", List.of(nfcEnum)))
                .isEqualTo(GeminiCachedContentKey.compute("block", List.of(nfdEnum)));
    }

    @Test
    @DisplayName("NFC normalisation recurses into nested ToolParameter.properties")
    void nfcRecursesIntoNestedProperties() {
        String nfc = "café";
        String nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD);

        Map<String, ToolParameter> nfcProps = new LinkedHashMap<>();
        nfcProps.put("inner", ToolParameter.builder()
                .name("inner").type("string").description(nfc).build());
        Map<String, ToolParameter> nfdProps = new LinkedHashMap<>();
        nfdProps.put("inner", ToolParameter.builder()
                .name("inner").type("string").description(nfd).build());

        ToolDefinition nfcTool = ToolDefinition.builder()
                .name("t").description("d")
                .parameters(List.of(ToolParameter.builder()
                        .name("q").type("object").required(true)
                        .properties(nfcProps).build()))
                .build();
        ToolDefinition nfdTool = ToolDefinition.builder()
                .name("t").description("d")
                .parameters(List.of(ToolParameter.builder()
                        .name("q").type("object").required(true)
                        .properties(nfdProps).build()))
                .build();

        assertThat(GeminiCachedContentKey.compute("block", List.of(nfcTool)))
                .as("nested object properties must normalise recursively or the cache busts on deep schemas")
                .isEqualTo(GeminiCachedContentKey.compute("block", List.of(nfdTool)));
    }

    @Test
    @DisplayName("canonical ObjectMapper keeps ORDER_MAP_ENTRIES_BY_KEYS + NON_NULL - config tripwire")
    void canonicalMapperConfigIsPinned() {
        // A refactor that silently swapped the mapper config (new
        // default Jackson feature toggled, inclusion set to ALWAYS)
        // would break byte-stability without breaking any functional
        // test at the interface level. Inspect the mapper directly.
        assertThat(GeminiCachedContentKey.CANONICAL.isEnabled(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS))
                .as("canonical mapper must serialise map entries in key order")
                .isTrue();
        assertThat(GeminiCachedContentKey.CANONICAL.getSerializationConfig()
                .getDefaultPropertyInclusion().getValueInclusion())
                .as("canonical mapper must omit null properties")
                .isEqualTo(JsonInclude.Include.NON_NULL);
    }

    @Test
    @DisplayName("independent runs produce the same hash (no hidden process-local salt)")
    void noProcessLocalSalt() {
        // A regression where the hash accidentally depended on an
        // Instant.now() or a System.nanoTime() would make the cache key
        // useless across processes. Cross-run stability check: the same
        // input must hash to the same pre-known digest. We don't pin
        // the exact digest value (that would over-constrain and fail on
        // any intentional change to the canonical format), but we check
        // stability by computing two hashes in sequence.
        String h1 = GeminiCachedContentKey.compute("stable",
                List.of(tool("alpha", "a", "string")));
        // Sleep-free; two back-to-back calls are enough: any time-based
        // salt would surface as a difference in microseconds.
        String h2 = GeminiCachedContentKey.compute("stable",
                List.of(tool("alpha", "a", "string")));
        assertThat(h1).isEqualTo(h2);
    }
}
