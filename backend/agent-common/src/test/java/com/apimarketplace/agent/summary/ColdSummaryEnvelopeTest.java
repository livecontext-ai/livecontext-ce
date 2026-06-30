package com.apimarketplace.agent.summary;

import com.apimarketplace.agent.summary.ColdSummaryEnvelope.Decision;
import com.apimarketplace.agent.summary.ColdSummaryEnvelope.ErrorResolution;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stage 5.2 - round-trip + null-invariant pins on the COLD summary
 * envelope. These tests fence off the shape callers persist into
 * {@code summary_cold} JSONB.
 */
@DisplayName("ColdSummaryEnvelope - shape + round-trip (Stage 5.2)")
class ColdSummaryEnvelopeTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @Test
    @DisplayName("serialises into the canonical key names (snake_case for timestamp + token + turns)")
    void canonicalSnakeCaseKeys() throws Exception {
        ColdSummaryEnvelope env = new ColdSummaryEnvelope(
                List.of(new Decision(12, "chose Fork over Split")),
                Map.of("jean_wf", "wf_abc"),
                List.of(new ErrorResolution("bad token", "rotated")),
                List.of("build CRM"),
                List.of("agent.create"),
                "2026-04-20T12:00:00Z",
                "anthropic/claude-haiku-4-5",
                4820,
                List.of(1, 2, 3)
        );
        String json = mapper.writeValueAsString(env);
        // The @JsonProperty annotations on the record components pin
        // the snake-case form - DB consumers + frontend schema rely on
        // it. A refactor that drops the annotations silently reverts
        // to camelCase and breaks the JSONB column contract.
        assertThat(json).contains("\"generated_at\"");
        assertThat(json).contains("\"cold_tokens_at_generation\":4820");
        assertThat(json).contains("\"turns_covered\":[1,2,3]");
    }

    @Test
    @DisplayName("round-trip preserves all fields (write → read)")
    void roundTrip() throws Exception {
        ColdSummaryEnvelope original = new ColdSummaryEnvelope(
                List.of(new Decision(1, "d1"), new Decision(5, "d2")),
                Map.of("name_a", "id_a", "name_b", "id_b"),
                List.of(new ErrorResolution("e1", "fix1")),
                List.of("intent a", "intent b"),
                List.of("tool.act"),
                "2026-04-20T12:34:56Z",
                "openai/gpt-4o-mini",
                1234,
                List.of(10, 11, 12)
        );
        String json = mapper.writeValueAsString(original);
        ColdSummaryEnvelope back = mapper.readValue(json, ColdSummaryEnvelope.class);
        assertThat(back).isEqualTo(original);
    }

    @Test
    @DisplayName("NON_NULL inclusion: null optional collections are omitted from JSON")
    void nullCollectionsOmitted() throws Exception {
        // Older rows might lack helped_actions or ids_resolved (the
        // summariser skipped them). Pin that the serialisation omits
        // them so the DB doesn't store `"helped_actions":null` noise.
        ColdSummaryEnvelope env = new ColdSummaryEnvelope(
                null, null, null, null, null,
                "2026-04-20T00:00:00Z",
                "m",
                0,
                null
        );
        String json = mapper.writeValueAsString(env);
        assertThat(json).doesNotContain("\"decisions\"");
        assertThat(json).doesNotContain("\"ids_resolved\"");
        assertThat(json).doesNotContain("\"errors_resolved\"");
        assertThat(json).doesNotContain("\"user_intents\"");
        assertThat(json).doesNotContain("\"helped_actions\"");
        assertThat(json).doesNotContain("\"turns_covered\"");
        // Required fields are still present.
        assertThat(json).contains("\"generated_at\"");
        assertThat(json).contains("\"model\":\"m\"");
    }

    @Test
    @DisplayName("null required field throws - envelope rejects construction without generatedAt")
    void requiredFieldsRejectNull() {
        assertThatThrownBy(() -> new ColdSummaryEnvelope(
                null, null, null, null, null,
                null, "m", 0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("generatedAt");
        assertThatThrownBy(() -> new ColdSummaryEnvelope(
                null, null, null, null, null,
                "2026-04-20T00:00:00Z", null, 0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("model");
    }

    @Test
    @DisplayName("Decision / ErrorResolution reject null text fields")
    void nestedRecordsValidate() {
        assertThatThrownBy(() -> new Decision(0, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ErrorResolution(null, "fix"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ErrorResolution("e", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("JSON read using canonical keys populates record correctly")
    void readsCanonicalJson() throws Exception {
        String json = """
                {
                  "decisions": [{"turn": 3, "decision": "went with Slack"}],
                  "ids_resolved": {"team": "tm_1"},
                  "generated_at": "2026-04-20T08:00:00Z",
                  "model": "anthropic/claude-haiku-4-5",
                  "cold_tokens_at_generation": 999,
                  "turns_covered": [3, 4]
                }
                """;
        ColdSummaryEnvelope env = mapper.readValue(json, ColdSummaryEnvelope.class);
        assertThat(env.decisions()).containsExactly(new Decision(3, "went with Slack"));
        assertThat(env.idsResolved()).containsEntry("team", "tm_1");
        assertThat(env.coldTokensAtGeneration()).isEqualTo(999);
        assertThat(env.turnsCovered()).containsExactly(3, 4);
        assertThat(env.generatedAt()).isEqualTo("2026-04-20T08:00:00Z");
    }

    @Test
    @DisplayName("status round-trips: stale serialises and isStale() reflects it")
    void statusRoundTrip() throws Exception {
        ColdSummaryEnvelope env = new ColdSummaryEnvelope(
                null, null, null, null, null,
                "2026-04-20T12:00:00Z", "m", 10, List.of(0, 1),
                ColdSummaryEnvelope.STATUS_STALE);
        String json = mapper.writeValueAsString(env);
        assertThat(json).contains("\"status\":\"stale\"");

        ColdSummaryEnvelope back = mapper.readValue(json, ColdSummaryEnvelope.class);
        assertThat(back.status()).isEqualTo(ColdSummaryEnvelope.STATUS_STALE);
        assertThat(back.isStale()).isTrue();
    }

    @Test
    @DisplayName("legacy 9-arg construction and legacy JSON (no status key) yield null status, treated as not stale")
    void legacyRowsHaveNoStatusAndAreNotStale() throws Exception {
        // Rows persisted before the status field existed must keep parsing -
        // and must read as active (isStale=false), never as stale.
        ColdSummaryEnvelope viaCtor = new ColdSummaryEnvelope(
                null, null, null, null, null,
                "2026-04-20T12:00:00Z", "m", 10, List.of(0));
        assertThat(viaCtor.status()).isNull();
        assertThat(viaCtor.isStale()).isFalse();
        // NON_NULL inclusion keeps the key out of legacy-shaped writes.
        assertThat(mapper.writeValueAsString(viaCtor)).doesNotContain("\"status\"");

        String legacyJson = """
                {"generated_at": "2026-04-20T08:00:00Z", "model": "m",
                 "cold_tokens_at_generation": 5, "turns_covered": [0]}
                """;
        ColdSummaryEnvelope viaJson = mapper.readValue(legacyJson, ColdSummaryEnvelope.class);
        assertThat(viaJson.status()).isNull();
        assertThat(viaJson.isStale()).isFalse();
    }

    @Test
    @DisplayName("isStale() is @JsonIgnore'd - no phantom 'stale' boolean key ever serialises")
    void isStaleAccessorNeverSerialises() throws Exception {
        // Records serialise accessor methods that look like getters; without
        // @JsonIgnore, every envelope would persist a derived "stale": bool
        // key next to the real "status" field - and rows written that way
        // would FAIL deserialisation (unknown property) on read-back.
        ColdSummaryEnvelope active = new ColdSummaryEnvelope(
                null, null, null, null, null,
                "2026-04-20T12:00:00Z", "m", 10, List.of(0),
                ColdSummaryEnvelope.STATUS_ACTIVE);
        ColdSummaryEnvelope stale = new ColdSummaryEnvelope(
                null, null, null, null, null,
                "2026-04-20T12:00:00Z", "m", 10, List.of(0),
                ColdSummaryEnvelope.STATUS_STALE);

        assertThat(mapper.writeValueAsString(active)).doesNotContain("\"stale\":");
        assertThat(mapper.writeValueAsString(stale))
                .contains("\"status\":\"stale\"")
                .doesNotContain("\"stale\":");
    }

    @Test
    @DisplayName("Partial.toEnvelope stamps STATUS_ACTIVE - staleness is never a generation-time state")
    void partialPromotionStampsActive() {
        ColdSummaryEnvelope env = new ColdSummaryEnvelope.Partial(
                null, null, null, List.of("intent"), null)
                .toEnvelope("2026-04-20T12:00:00Z", "m", 10, List.of(0, 1));
        assertThat(env.status()).isEqualTo(ColdSummaryEnvelope.STATUS_ACTIVE);
        assertThat(env.isStale()).isFalse();
    }
}
