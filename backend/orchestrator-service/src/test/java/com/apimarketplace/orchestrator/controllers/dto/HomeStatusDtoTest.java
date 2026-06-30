package com.apimarketplace.orchestrator.controllers.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-contract tests for {@link HomeStatusDto}.
 *
 * <p>Pre-fix bug (2026-05-11): a record-level {@code @JsonInclude(NON_NULL)}
 * would strip {@code automations} or {@code items} from the JSON whenever
 * the controller passed null - leaving the frontend to crash on
 * {@code .some()} / {@code .length} / {@code .map()} on undefined.
 *
 * <p>Fix: annotation moved to the {@code lastSeenAt} field only. These tests
 * pin the resulting wire contract so a future regression that re-adds
 * {@code @JsonInclude(NON_NULL)} at the record level - or moves it back -
 * is caught immediately.
 */
@DisplayName("HomeStatusDto - wire contract (Jackson)")
class HomeStatusDtoTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        // Match Spring Boot's default ObjectMapper config: JavaTimeModule for
        // Instant + WRITE_DATES_AS_TIMESTAMPS disabled (Boot's default) so
        // Instants serialize as ISO-8601 strings, not epoch numbers.
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    @DisplayName("Empty arrays serialize as []  - never omitted from the wire")
    void emptyArraysAreNotStripped() throws Exception {
        HomeStatusDto dto = new HomeStatusDto(List.of(), List.of(), 0, Instant.now());

        JsonNode root = mapper.readTree(mapper.writeValueAsString(dto));

        assertThat(root.has("automations")).isTrue();
        assertThat(root.get("automations").isArray()).isTrue();
        assertThat(root.get("automations")).isEmpty();
        assertThat(root.has("items")).isTrue();
        assertThat(root.get("items").isArray()).isTrue();
        assertThat(root.get("items")).isEmpty();
    }

    @Test
    @DisplayName("Null lastSeenAt is OMITTED from the JSON (field-level @JsonInclude NON_NULL)")
    void nullLastSeenAtIsOmitted() throws Exception {
        HomeStatusDto dto = new HomeStatusDto(List.of(), List.of(), 0, null);

        JsonNode root = mapper.readTree(mapper.writeValueAsString(dto));

        assertThat(root.has("lastSeenAt")).isFalse();
    }

    @Test
    @DisplayName("Non-null lastSeenAt is present as an ISO-8601 string")
    void nonNullLastSeenAtIsPresent() throws Exception {
        Instant cursor = Instant.parse("2026-05-11T08:00:00Z");
        HomeStatusDto dto = new HomeStatusDto(List.of(), List.of(), 0, cursor);

        JsonNode root = mapper.readTree(mapper.writeValueAsString(dto));

        assertThat(root.has("lastSeenAt")).isTrue();
        assertThat(root.get("lastSeenAt").asText()).isEqualTo("2026-05-11T08:00:00Z");
    }

    @Test
    @DisplayName("Regression: null automations/items are NOT stripped from the wire (no record-level @JsonInclude NON_NULL)")
    void nullArraysAreSerializedAsNullNotStripped() throws Exception {
        // Even though the production controller now defends against null
        // upstream (Objects.requireNonNullElseGet → empty list), we must
        // also pin the DTO-layer contract: if a future regression somehow
        // re-introduces a null array here, the JSON should at least carry
        // `"automations":null` - NOT omit the field entirely. The frontend
        // hooks tolerate null with `?? []` but cannot recover from a missing
        // key vs an explicit null distinction in JSON-schema-style contract
        // validation.
        HomeStatusDto dto = new HomeStatusDto(null, null, 0, null);

        JsonNode root = mapper.readTree(mapper.writeValueAsString(dto));

        assertThat(root.has("automations")).isTrue();
        assertThat(root.get("automations").isNull()).isTrue();
        assertThat(root.has("items")).isTrue();
        assertThat(root.get("items").isNull()).isTrue();
        assertThat(root.has("unreadCount")).isTrue();
        assertThat(root.get("unreadCount").asInt()).isZero();
        // lastSeenAt was explicitly null AND has @JsonInclude(NON_NULL) → omitted
        assertThat(root.has("lastSeenAt")).isFalse();
    }
}
