package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.domain.execution.AgentResultMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip tests for {@link RedisInFlightStore} serialization. The store talks to Redis,
 * but the serialization layer is pure Java and can be tested in isolation - these tests
 * pin the schema so a refactor of {@code toInFlightMap} / {@code fromInFlightMap} cannot
 * silently drop a field across the JVM-crash boundary.
 */
class RedisInFlightStoreTest {

    @Test
    @DisplayName("toInFlightMapAndFromInFlightMapRoundTripPreservesAllPendingAgentFieldsPlusResultPayload: schema is stable across the crash boundary")
    void roundTripPreservesAllFields() {
        Instant start = Instant.parse("2026-05-22T21:00:48Z");
        Instant completed = Instant.parse("2026-05-22T21:00:52Z");
        PendingAgent pending = new PendingAgent(
            "cid-1", "run_test", "agent:classify", "Classify", "trigger:cron",
            46, 7, "item-7", "classify", "tenant-1",
            Map.of("subject", "Email 7"), Map.of("input", "data"),
            "conv-1", "stream-1", "exec-1", "deepseek-chat",
            "system prompt", "user prompt", start, "org-1");

        AgentResultMessage result = new AgentResultMessage(
            "cid-1", "run_test", "agent:classify",
            Map.of("selected_category", "urgent"),
            true, null, "classify", completed);

        Map<String, Object> serialized = RedisInFlightStore.toInFlightMap(pending, result);
        RedisInFlightStore.InFlightEntry round = RedisInFlightStore.fromInFlightMap(serialized);

        assertThat(round).isNotNull();
        assertThat(round.pending().correlationId()).isEqualTo("cid-1");
        assertThat(round.pending().runId()).isEqualTo("run_test");
        assertThat(round.pending().nodeId()).isEqualTo("agent:classify");
        assertThat(round.pending().dagTriggerId()).isEqualTo("trigger:cron");
        assertThat(round.pending().epoch()).isEqualTo(46);
        assertThat(round.pending().itemIndex()).isEqualTo(7);
        assertThat(round.pending().organizationId()).isEqualTo("org-1");
        assertThat(round.pending().resolvedInputData()).containsEntry("input", "data");
        assertThat(round.pending().startedAt()).isEqualTo(start);

        assertThat(round.result().correlationId()).isEqualTo("cid-1");
        assertThat(round.result().success()).isTrue();
        assertThat(round.result().result()).containsEntry("selected_category", "urgent");
        assertThat(round.result().completedAt()).isEqualTo(completed);
    }

    @Test
    @DisplayName("roundTripPreservesFailedResultWithErrorMessage: failure-path crash recovery still carries the error context")
    void failedResultRoundTrip() {
        PendingAgent pending = new PendingAgent(
            "cid-fail", "run_x", "agent:classify", "Classify", "trigger:cron",
            1, 0, null, "classify", "tenant-1",
            null, null, null, null, null, "deepseek-chat", null, null,
            Instant.now(), "org-1");

        AgentResultMessage result = new AgentResultMessage(
            "cid-fail", "run_x", "agent:classify", null,
            false, "Rate limit exceeded (429)", "classify", Instant.now());

        Map<String, Object> map = RedisInFlightStore.toInFlightMap(pending, result);
        RedisInFlightStore.InFlightEntry round = RedisInFlightStore.fromInFlightMap(map);

        assertThat(round.result().success()).isFalse();
        assertThat(round.result().errorMessage()).isEqualTo("Rate limit exceeded (429)");
    }

    @Test
    @DisplayName("toInFlightMapJsonSerializesViaJacksonWithoutLossOfTypedFields: the wire format is JSON, the codec is the same Jackson instance used by RedisPendingAgentStore")
    void jacksonRoundTrip() throws Exception {
        PendingAgent pending = new PendingAgent(
            "cid-j", "run", "agent:a", "A", "trigger:t", 0, 0, null, "agent", "t1",
            null, null, null, null, null, "m", null, null, Instant.now(), "o1");
        AgentResultMessage result = new AgentResultMessage(
            "cid-j", "run", "agent:a", Map.of("k", "v"), true, null, "agent", Instant.now());

        Map<String, Object> map = RedisInFlightStore.toInFlightMap(pending, result);
        ObjectMapper om = new ObjectMapper();
        String json = om.writeValueAsString(map);
        Map<String, Object> parsed = om.readValue(json, om.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        RedisInFlightStore.InFlightEntry round = RedisInFlightStore.fromInFlightMap(parsed);

        assertThat(round.pending().correlationId()).isEqualTo("cid-j");
        assertThat(round.result().result()).containsEntry("k", "v");
    }
}
