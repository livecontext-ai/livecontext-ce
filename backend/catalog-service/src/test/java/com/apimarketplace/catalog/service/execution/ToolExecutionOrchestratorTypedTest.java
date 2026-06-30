package com.apimarketplace.catalog.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the V52 hardening of {@link ToolExecutionOrchestrator#projectResult}.
 *
 * <p>Before the hardening pass, every unknown / streaming mode silently fell back to
 * {@code outputProjector.project()} which made misconfigurations invisible at runtime.
 * The new behavior is:
 *
 * <ul>
 *   <li>{@code sync} / {@code upload} / {@code async_poll} / {@code streaming} →
 *       project via OutputProjector (the streaming case projects the aggregated
 *       {chunks, chunk_count, …} envelope produced upstream by StreamingResponseHandler)</li>
 *   <li>any other mode (including the retired {@code webhook}) → throws
 *       {@link IllegalStateException}</li>
 * </ul>
 */
class ToolExecutionOrchestratorTypedTest {

    private ObjectMapper objectMapper;
    private OutputProjector outputProjector;
    private ToolExecutionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        outputProjector = new OutputProjector(objectMapper);
        // The other helpers are not exercised in this test class - pass nulls.
        orchestrator = new ToolExecutionOrchestrator(
                outputProjector, null, null, null, objectMapper);
    }

    @Test
    @DisplayName("sync mode → projects via OutputProjector")
    void syncProjects() {
        Map<String, Object> raw = Map.of("a", 1, "b", "drop me");
        String schema = "[{\"key\":\"a\",\"type\":\"number\"}]";

        Object out = orchestrator.projectResult(raw, schema, "sync");

        assertThat(out).isInstanceOf(Map.class);
        Map<String, Object> projected = (Map<String, Object>) out;
        assertThat(projected).containsEntry("a", 1);
        assertThat(projected).doesNotContainKey("b");
    }

    @Test
    @DisplayName("upload mode → projects via OutputProjector (response is still JSON)")
    void uploadProjects() {
        Map<String, Object> raw = Map.of("file_id", "xyz");
        String schema = "[{\"key\":\"file_id\",\"type\":\"string\"}]";

        Object out = orchestrator.projectResult(raw, schema, "upload");

        assertThat(((Map<String, Object>) out)).containsEntry("file_id", "xyz");
    }

    @Test
    @DisplayName("async_poll mode → projects via OutputProjector")
    void asyncPollProjects() {
        Map<String, Object> raw = Map.of("status", "done", "result", "ok");
        String schema = "[{\"key\":\"result\",\"type\":\"string\"}]";

        Object out = orchestrator.projectResult(raw, schema, "async_poll");

        assertThat(((Map<String, Object>) out)).containsEntry("result", "ok").doesNotContainKey("status");
    }

    @Test
    @DisplayName("streaming mode → projects the aggregated envelope")
    void streamingProjectsEnvelope() {
        // Mimics what StreamingResponseHandler produces.
        Map<String, Object> aggregated = Map.of(
                "chunks", List.of(
                        Map.of("text", "hello"),
                        Map.of("text", "world")),
                "chunk_count", 2,
                "terminated", true,
                "truncated", false);
        String schema = "["
                + "{\"key\":\"chunks\",\"type\":\"array\","
                + "  \"children\":[{\"key\":\"text\",\"type\":\"string\"}]},"
                + "{\"key\":\"chunk_count\",\"type\":\"number\"},"
                + "{\"key\":\"terminated\",\"type\":\"boolean\"}"
                + "]";

        Object out = orchestrator.projectResult(aggregated, schema, "streaming");

        Map<String, Object> projected = (Map<String, Object>) out;
        assertThat(projected).containsKey("chunks").containsEntry("chunk_count", 2);
        assertThat(projected).containsEntry("terminated", true);
        // 'truncated' was not declared in the schema, so it should be dropped.
        assertThat(projected).doesNotContainKey("truncated");
    }

    @Test
    @DisplayName("webhook mode → falls through to unknown mode (V145 retired the special case)")
    void webhookNowRejectedAsUnknownMode() {
        // Regression guard: V145 retired the dedicated `case "webhook"` arm, so webhook
        // now hits the default path and surfaces as IllegalStateException like any other
        // unknown mode. This is the expected post-V145 behaviour.
        assertThatThrownBy(() ->
                orchestrator.projectResult(Map.of(), "[]", "webhook"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("webhook");
    }

    @Test
    @DisplayName("unknown mode → throws IllegalStateException (no silent fallback)")
    void unknownModeRejected() {
        assertThatThrownBy(() ->
                orchestrator.projectResult(Map.of(), "[]", "definitely_not_a_mode"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("definitely_not_a_mode");
    }

    @Test
    @DisplayName("null/blank mode → defaults to sync (preserves legacy callers)")
    void nullModeDefaultsToSync() {
        // Legacy callers may pass null when they don't know the mode - must NOT throw.
        Object out1 = orchestrator.projectResult(Map.of("x", 1), null, null);
        Object out2 = orchestrator.projectResult(Map.of("x", 1), null, "");
        // No schema → passthrough.
        assertThat(out1).isEqualTo(Map.of("x", 1));
        assertThat(out2).isEqualTo(Map.of("x", 1));
    }
}
