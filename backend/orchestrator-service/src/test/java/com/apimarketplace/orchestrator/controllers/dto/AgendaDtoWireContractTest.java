package com.apimarketplace.orchestrator.controllers.dto;

import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto.ResourceType;
import com.apimarketplace.orchestrator.controllers.dto.AgendaDto.LaunchSource;
import com.apimarketplace.orchestrator.controllers.dto.AgendaDto.Occurrence;
import com.apimarketplace.orchestrator.controllers.dto.AgendaDto.OccurrenceKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The names the agenda page reads, as this DTO actually writes them.
 *
 * <p>Four fields added for agent runs cross this boundary, and every one of them fails
 * SILENTLY if the two sides stop agreeing on a spelling. The frontend reads
 * `agenda.service.ts` types and the page branches on them, so a rename here does not
 * break a build anywhere: {@code launchSource} undefined makes an agent run draw the
 * generic resource icon and vanish from the launch-kind filter; {@code conversationId}
 * undefined sends a click to the agent panel instead of the conversation;
 * {@code pastCoveredFrom} undefined degrades the banner to the vaguer sentence; and
 * {@code agentHistoryUnavailable} undefined means the "could not be loaded" sentence
 * never renders at all, so an absent source is reported as "some older runs are not
 * shown" - the exact wrong instruction that field exists to prevent.
 *
 * <p>The agent-service hop got {@code AgentRunWindowRoundTripTest} for precisely this
 * reason. This is the same contract on the other hop, checked the only way it can be
 * without running a browser: serialize with the mapper Spring Boot gives a controller,
 * then assert the JSON keys against the TypeScript declaration that reads them.
 */
@DisplayName("AgendaDto - the field names the page reads")
class AgendaDtoWireContractTest {

    /** Mirrors Boot's MVC mapper; a bare builder still writes instants as timestamps. */
    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private static final Path TS_TYPES =
            Path.of("../../frontend/lib/api/orchestrator/agenda.service.ts");

    private AgendaDto agendaWithAnAgentRun() {
        Occurrence run = new Occurrence(
                "agent-run:exec-1", OccurrenceKind.PAST,
                Instant.parse("2026-09-14T09:12:00Z"), Instant.parse("2026-09-14T09:12:08Z"),
                ResourceType.AGENT, UUID.randomUUID(), "Support agent", null,
                null, null, null, null, null,
                true, false, false, false, "COMPLETED", null, false, false, null, null,
                LaunchSource.SUB_AGENT, "conv-5");
        return new AgendaDto(Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-30T00:00:00Z"), List.of(run), List.of(), List.of(),
                true, Instant.parse("2026-09-03T09:00:00Z"), false);
    }

    @Test
    @DisplayName("writes the four agent-run fields under the names the page branches on")
    void writesTheAgentFields() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(agendaWithAnAgentRun()));

        assertThat(json.has("pastCoveredFrom")).isTrue();
        assertThat(json.has("agentHistoryUnavailable")).isTrue();
        JsonNode occurrence = json.get("occurrences").get(0);
        assertThat(occurrence.get("launchSource").asText()).isEqualTo("SUB_AGENT");
        assertThat(occurrence.get("conversationId").asText()).isEqualTo("conv-5");
    }

    @Test
    @DisplayName("every one of those names is declared in the TypeScript that reads it")
    void theTypeScriptDeclaresThem() throws Exception {
        // Reading the declaration rather than duplicating it: this fails when either side
        // is renamed alone, which is the only way this contract breaks.
        String ts = Files.readString(TS_TYPES);

        assertThat(ts).contains("pastCoveredFrom?:");
        assertThat(ts).contains("agentHistoryUnavailable?:");
        assertThat(ts).contains("launchSource?:");
        assertThat(ts).contains("conversationId?:");
    }

    @Test
    @DisplayName("the launch-kind vocabulary is the same on both sides, name for name")
    void theVocabulariesMatch() throws Exception {
        String ts = Files.readString(TS_TYPES);

        // A value Java can emit and TypeScript does not list would reach the page as a
        // kind no filter chip covers and no glyph exists for.
        for (LaunchSource source : LaunchSource.values()) {
            assertThat(ts)
                    .as("AgentLaunchSource must list %s", source)
                    .contains("'" + source.name() + "'");
        }
    }

    @Test
    @DisplayName("omits the agent fields entirely on a workflow entry, rather than nulling them")
    void workflowEntriesCarryNeither() throws Exception {
        Occurrence fire = new Occurrence(
                "run_1#trigger:daily#1", OccurrenceKind.PAST,
                Instant.parse("2026-09-14T09:00:00Z"), null,
                ResourceType.WORKFLOW, UUID.randomUUID(), "Daily digest", null,
                null, "trigger:daily", null, null, null,
                true, false, false, false, "COMPLETED", "run_1", false, false, 1, null);
        String json = mapper.writeValueAsString(new AgendaDto(
                Instant.now(), Instant.now(), List.of(fire), List.of(), List.of(), false));

        // NON_NULL, so the page's `launchSource ?? triggerType` reads undefined and falls
        // through to the workflow vocabulary. A null written explicitly would behave the
        // same in JS, but the payload is one field per entry larger for no reader.
        assertThat(json).doesNotContain("launchSource");
        assertThat(json).doesNotContain("conversationId");
    }
}
