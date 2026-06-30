package com.apimarketplace.orchestrator.execution.v2.async;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RedisPendingAgentStore} - the Redis side-store that mirrors
 * {@link PendingAgent} entries for restart recovery.
 *
 * <p>SCAN-based listAll is intentionally NOT tested here because it requires a real
 * Redis connection (the connection-factory call chain is hostile to mocking). It is
 * exercised end-to-end in the recovery integration test.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisPendingAgentStore")
class RedisPendingAgentStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private SetOperations<String, String> setOps;

    private ObjectMapper objectMapper;
    private RedisPendingAgentStore store;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        store = new RedisPendingAgentStore(redisTemplate, objectMapper, Duration.ofHours(2));
        // Default: opsForSet returns the mock so the SADD+EXPIRE in store() doesn't NPE.
        // Tests that exercise the value-write path mock opsForValue separately.
        // Lenient because not every test reaches the SET path (e.g. null-guard tests).
        org.mockito.Mockito.lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
    }

    private PendingAgent sampleAgent() {
        Map<String, Object> splitData = new HashMap<>();
        splitData.put("splitNodeId", "core:split_messages");
        splitData.put("workflowItemIndex", 0);
        splitData.put("itemIndex", 1);
        return new PendingAgent(
            "corr-1",
            "run-1",
            "agent:classifier",
            "classifier",
            "trigger:webhook_in",
            2,         // epoch
            5,         // itemIndex
            "0.1",     // itemId
            "classify",
            "tenant-1",
            splitData,
            Map.of("content", "test input", "model", "gpt-4"),
            "conv-7730cebb",
            "stream-abc",
            "exec-99",
            "gpt-4",
            "You are a helpful classifier system prompt (resolved).",
            "Classify this user input.",
            Instant.parse("2026-04-11T00:00:00Z")
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // store
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("store SETs the JSON payload with the configured TTL under the prefixed key")
    void storeSetsJsonWithTtl() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        PendingAgent agent = sampleAgent();
        store.store(agent);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
        verify(valueOps).set(keyCaptor.capture(), jsonCaptor.capture(),
            ttlCaptor.capture(), eq(TimeUnit.MILLISECONDS));

        assertThat(keyCaptor.getValue()).isEqualTo("agent:pending:corr-1");
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofHours(2).toMillis());

        // Round-trip the JSON: every PendingAgent field is preserved
        @SuppressWarnings("unchecked")
        Map<String, Object> roundTripped = objectMapper.readValue(jsonCaptor.getValue(), Map.class);
        assertThat(roundTripped)
            .containsEntry("correlationId", "corr-1")
            .containsEntry("runId", "run-1")
            .containsEntry("nodeId", "agent:classifier")
            .containsEntry("nodeLabel", "classifier")
            .containsEntry("dagTriggerId", "trigger:webhook_in")
            .containsEntry("epoch", 2)
            .containsEntry("itemIndex", 5)
            .containsEntry("itemId", "0.1")
            .containsEntry("agentType", "classify")
            .containsEntry("tenantId", "tenant-1")
            // Conversation persistence fields - survive recovery so the assistant message
            // and stream-completed event still land on the right conversation after a
            // crash-restart of the orchestrator.
            .containsEntry("conversationId", "conv-7730cebb")
            .containsEntry("streamId", "stream-abc")
            .containsEntry("executionId", "exec-99")
            .containsEntry("model", "gpt-4")
            // Resolved system + user prompts snapshotted at enqueue so the async
            // observability path can populate Agent Performance with the same
            // SYSTEM/USER messages the inline / sub-agent / chat paths persist.
            .containsEntry("resolvedSystemPrompt", "You are a helpful classifier system prompt (resolved).")
            .containsEntry("resolvedUserPrompt", "Classify this user input.");
        // startedAt encoded as long ms
        assertThat(roundTripped.get("startedAtEpochMs")).isEqualTo(
            Instant.parse("2026-04-11T00:00:00Z").toEpochMilli());
        // splitItemData round-tripped as a Map
        assertThat(roundTripped.get("splitItemData")).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("store ignores null agent / null correlationId")
    void storeIgnoresNullAgent() {
        store.store(null);
        store.store(new PendingAgent(
            null, "run-1", "agent:x", "x", "trigger:y", 0, 0, "0", "agent", "tenant-1",
            null, null, null, null, null, null, null, null, Instant.now()));
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("store swallows Redis failures so in-memory registration is never broken")
    void storeSwallowsRedisFailure() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        doThrow(new RuntimeException("redis down"))
            .when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        // Must not throw
        store.store(sampleAgent());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // claim (cross-replica atomic GETDEL barrier)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("claim returns the PendingAgent payload when GETDEL produced a value (claim won)")
    void claimReturnsAgentWhenGetdelWins() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        PendingAgent original = sampleAgent();
        // Store once so we have a real serialized payload
        store.store(original);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(anyString(), jsonCaptor.capture(), anyLong(), any(TimeUnit.class));
        String storedJson = jsonCaptor.getValue();

        when(valueOps.getAndDelete("agent:pending:corr-1")).thenReturn(storedJson);

        java.util.Optional<PendingAgent> result = store.claim("corr-1");

        assertThat(result).isPresent();
        assertThat(result.get().correlationId()).isEqualTo("corr-1");
        assertThat(result.get().runId()).isEqualTo("run-1");
        assertThat(result.get().nodeId()).isEqualTo("agent:classifier");
        assertThat(result.get().splitItemData()).isNotNull();
        // Resolved system + user prompts survive Redis round-trip - required so
        // crash-recovery rebuilds Agent Performance with the same SYSTEM/USER
        // messages a fresh enqueue would have produced.
        assertThat(result.get().resolvedSystemPrompt())
            .isEqualTo("You are a helpful classifier system prompt (resolved).");
        assertThat(result.get().resolvedUserPrompt())
            .isEqualTo("Classify this user input.");
    }

    @Test
    @DisplayName("claim returns empty when GETDEL finds no key (claim lost)")
    void claimReturnsEmptyWhenKeyAbsent() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.getAndDelete("agent:pending:corr-x")).thenReturn(null);

        assertThat(store.claim("corr-x")).isEmpty();
    }

    @Test
    @DisplayName("claim returns empty on null correlationId without touching Redis")
    void claimIgnoresNull() {
        assertThat(store.claim(null)).isEmpty();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("claim propagates Redis failure so caller can choose its fallback strategy")
    void claimPropagatesRedisFailure() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.getAndDelete(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThatThrownBy(() -> store.claim("corr-1"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("redis down");
    }

    @Test
    @DisplayName("claim returns empty when GETDEL returned garbage JSON (key already gone, payload lost)")
    void claimReturnsEmptyOnParseFailure() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.getAndDelete("agent:pending:corr-bad")).thenReturn("not-json-{");

        assertThat(store.claim("corr-bad")).isEmpty();
    }

    @Test
    @DisplayName("claim deserializes legacy Redis row missing resolvedSystemPrompt + resolvedUserPrompt (pre-fix payload survives orchestrator restart)")
    void claimDeserializesLegacyRowWithoutSnapshotFields() {
        // Pre-fix orchestrators (before the Agent Performance SYSTEM/USER fix) wrote
        // PendingAgent JSON without the resolvedSystemPrompt + resolvedUserPrompt keys.
        // After deploy, an in-flight async agent will be claimed via fromMap on the new
        // code path - that must not throw, and both new fields must arrive as null so
        // AgentAsyncCompletionService.enrichAgentShape falls back to agentConfig.systemPrompt()
        // and omits the USER message (legacy compat path covered by
        // AgentAsyncCompletionServiceObservabilityTest#agentFallsBackToPlanWhenSnapshotMissing).
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        String legacyJson = "{"
            + "\"correlationId\":\"corr-legacy\","
            + "\"runId\":\"run-legacy\","
            + "\"nodeId\":\"agent:legacy\","
            + "\"nodeLabel\":\"legacy\","
            + "\"dagTriggerId\":\"trigger:default\","
            + "\"epoch\":0,"
            + "\"itemIndex\":0,"
            + "\"itemId\":\"0\","
            + "\"agentType\":\"agent\","
            + "\"tenantId\":\"tenant-legacy\","
            + "\"splitItemData\":null,"
            + "\"resolvedInputData\":null,"
            + "\"conversationId\":null,"
            + "\"streamId\":null,"
            + "\"executionId\":null,"
            + "\"model\":\"gpt-4\","
            + "\"startedAtEpochMs\":1714435200000"
            + "}";
        when(valueOps.getAndDelete("agent:pending:corr-legacy")).thenReturn(legacyJson);

        java.util.Optional<PendingAgent> result = store.claim("corr-legacy");

        assertThat(result).isPresent();
        assertThat(result.get().correlationId()).isEqualTo("corr-legacy");
        assertThat(result.get().model()).isEqualTo("gpt-4");
        // The two new fields must be null when absent from JSON, NOT throw or default to "".
        assertThat(result.get().resolvedSystemPrompt()).isNull();
        assertThat(result.get().resolvedUserPrompt()).isNull();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // toMap / fromMap round-trip via store + listAll fixture
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("toMap round-trips into an equivalent PendingAgent via JSON")
    void mapRoundTrip() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        PendingAgent original = sampleAgent();
        store.store(original);

        // Capture the json that store wrote
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(anyString(), jsonCaptor.capture(), anyLong(), any(TimeUnit.class));

        // Use a real ObjectMapper to deserialize and feed it back through fromMap (via reflection
        // is overkill - just round-trip via the same path the store uses internally on listAll).
        @SuppressWarnings("unchecked")
        Map<String, Object> deserialized = objectMapper.readValue(jsonCaptor.getValue(), Map.class);

        // The store's fromMap is package-private static; we can't reach it without reflection,
        // so re-construct the same way and assert equivalence on each field
        assertThat(deserialized.get("correlationId")).isEqualTo(original.correlationId());
        assertThat(deserialized.get("runId")).isEqualTo(original.runId());
        assertThat(deserialized.get("nodeId")).isEqualTo(original.nodeId());
        assertThat(deserialized.get("nodeLabel")).isEqualTo(original.nodeLabel());
        assertThat(deserialized.get("dagTriggerId")).isEqualTo(original.dagTriggerId());
        assertThat(deserialized.get("epoch")).isEqualTo(original.epoch());
        assertThat(deserialized.get("itemIndex")).isEqualTo(original.itemIndex());
        assertThat(deserialized.get("itemId")).isEqualTo(original.itemId());
        assertThat(deserialized.get("agentType")).isEqualTo(original.agentType());
        assertThat(deserialized.get("tenantId")).isEqualTo(original.tenantId());
        @SuppressWarnings("unchecked")
        Map<String, Object> splitData = (Map<String, Object>) deserialized.get("splitItemData");
        assertThat(splitData)
            .containsEntry("splitNodeId", "core:split_messages")
            .containsEntry("workflowItemIndex", 0)
            .containsEntry("itemIndex", 1);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Constants
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("KEY_PREFIX must NOT collide with the worker result-key prefix")
    void keyPrefixDoesNotCollideWithResultKeys() {
        // The worker uses "agent:result:" - make sure we use a distinct namespace
        assertThat(RedisPendingAgentStore.KEY_PREFIX).isEqualTo("agent:pending:");
        assertThat(RedisPendingAgentStore.KEY_PREFIX).doesNotStartWith("agent:result:");
    }

    @Test
    @DisplayName("RUN_INDEX_PREFIX must NOT be a prefix of (or equal to) KEY_PREFIX - listAll SCAN would match it and trigger WRONGTYPE on every recovery tick")
    void runIndexPrefixDoesNotCollideWithValueKeyPrefix() {
        // listAll() does SCAN MATCH KEY_PREFIX + "*". If RUN_INDEX_PREFIX shares the
        // same root the scan would hit SET keys, then GET on them throws WRONGTYPE
        // (the index is a SET, the value namespace is STRING). This produces a WARN
        // log per index entry per tick - log churn + needless connection churn.
        // Regression: the first iteration of the cross-replica index used
        // "agent:pending:run:" which is a strict prefix of "agent:pending:".
        assertThat(RedisPendingAgentStore.RUN_INDEX_PREFIX).doesNotStartWith(
            RedisPendingAgentStore.KEY_PREFIX);
        assertThat(RedisPendingAgentStore.KEY_PREFIX).doesNotStartWith(
            RedisPendingAgentStore.RUN_INDEX_PREFIX);
        // Sanity: the patterns must not overlap with the worker result-key namespace either.
        assertThat(RedisPendingAgentStore.RUN_INDEX_PREFIX).doesNotStartWith("agent:result:");
    }

    @Test
    @DisplayName("DEFAULT_TTL must be longer than the worker result-key TTL (1h) for recovery buffer")
    void defaultTtlIsLongerThanResultKeyTtl() {
        // Worker writes results with 1h TTL; pending must outlive results so recovery
        // can find both keys at the same time.
        assertThat(RedisPendingAgentStore.DEFAULT_TTL.toHours()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("hasPendingFor returns true when an entry matches the (run, trigger, epoch) tuple in Redis (cross-replica fallback)")
    void hasPendingForMatchesByRunTriggerEpoch() throws Exception {
        PendingAgent agent = sampleAgent();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(setOps.members(RedisPendingAgentStore.RUN_INDEX_PREFIX + "run-1"))
            .thenReturn(java.util.Set.of("corr-1"));
        when(valueOps.get(RedisPendingAgentStore.KEY_PREFIX + "corr-1"))
            .thenReturn(objectMapper.writeValueAsString(toMap(agent)));

        assertThat(store.hasPendingFor("run-1", "trigger:webhook_in", 2)).isTrue();
    }

    @Test
    @DisplayName("hasPendingFor returns false when triggerId differs even though runId matches")
    void hasPendingForRejectsTriggerMismatch() throws Exception {
        PendingAgent agent = sampleAgent(); // dagTriggerId = trigger:webhook_in
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(setOps.members(RedisPendingAgentStore.RUN_INDEX_PREFIX + "run-1"))
            .thenReturn(java.util.Set.of("corr-1"));
        when(valueOps.get(RedisPendingAgentStore.KEY_PREFIX + "corr-1"))
            .thenReturn(objectMapper.writeValueAsString(toMap(agent)));

        assertThat(store.hasPendingFor("run-1", "trigger:OTHER", 2)).isFalse();
    }

    @Test
    @DisplayName("hasPendingFor returns false when epoch differs")
    void hasPendingForRejectsEpochMismatch() throws Exception {
        PendingAgent agent = sampleAgent(); // epoch = 2
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(setOps.members(RedisPendingAgentStore.RUN_INDEX_PREFIX + "run-1"))
            .thenReturn(java.util.Set.of("corr-1"));
        when(valueOps.get(RedisPendingAgentStore.KEY_PREFIX + "corr-1"))
            .thenReturn(objectMapper.writeValueAsString(toMap(agent)));

        assertThat(store.hasPendingFor("run-1", "trigger:webhook_in", 99)).isFalse();
    }

    @Test
    @DisplayName("hasPendingFor returns false when the per-run reverse index is empty")
    void hasPendingForReturnsFalseWhenIndexEmpty() {
        when(setOps.members(RedisPendingAgentStore.RUN_INDEX_PREFIX + "run-empty"))
            .thenReturn(java.util.Set.of());

        assertThat(store.hasPendingFor("run-empty", "trigger:any", 0)).isFalse();
    }

    @Test
    @DisplayName("hasPendingFor returns false on null inputs without touching Redis")
    void hasPendingForRejectsNullInputs() {
        assertThat(store.hasPendingFor(null, "trigger:x", 0)).isFalse();
        assertThat(store.hasPendingFor("run", null, 0)).isFalse();
        verifyNoInteractions(redisTemplate);
    }

    /**
     * Helper that mirrors the package-private toMap used by store() - needed so the
     * hasPendingFor tests can serialize a PendingAgent in the same shape Redis would
     * see in production. We cannot call the real toMap (package-private), so we
     * inline the field set used by fromMap().
     */
    private Map<String, Object> toMap(PendingAgent a) {
        Map<String, Object> m = new HashMap<>();
        m.put("correlationId", a.correlationId());
        m.put("runId", a.runId());
        m.put("nodeId", a.nodeId());
        m.put("nodeLabel", a.nodeLabel());
        m.put("dagTriggerId", a.dagTriggerId());
        m.put("epoch", a.epoch());
        m.put("itemIndex", a.itemIndex());
        m.put("itemId", a.itemId());
        m.put("agentType", a.agentType());
        m.put("tenantId", a.tenantId());
        m.put("splitItemData", a.splitItemData());
        m.put("resolvedInputData", a.resolvedInputData());
        m.put("conversationId", a.conversationId());
        m.put("streamId", a.streamId());
        m.put("executionId", a.executionId());
        m.put("model", a.model());
        m.put("resolvedSystemPrompt", a.resolvedSystemPrompt());
        m.put("resolvedUserPrompt", a.resolvedUserPrompt());
        m.put("startedAt", a.startedAt() == null ? null : a.startedAt().toString());
        return m;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PR20 - organizationId round-trip
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * PR20 regression - the workspace identity captured at enqueue must survive the
     * Redis round-trip so the async-delivery path persists the agent observability
     * row under the same scope the synchronous path would have. Before PR20 the
     * {@code organization_id} field did not exist on {@link PendingAgent}, so a
     * crash-recovered async agent always landed in personal scope.
     */
    @Test
    @DisplayName("PR20 - organizationId survives store → claim round-trip")
    void organizationIdRoundTrips() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        PendingAgent original = new PendingAgent(
            "corr-org-1", "run-1", "agent:classifier", "classifier",
            "trigger:webhook_in", 2, 5, "0.1", "classify", "tenant-1",
            null, Map.of("content", "x"), "conv-1", "stream-1", "exec-1",
            "gpt-4", "sys", "usr", Instant.parse("2026-04-11T00:00:00Z"),
            "org-acme");

        store.store(original);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(anyString(), jsonCaptor.capture(), anyLong(), any(TimeUnit.class));
        String storedJson = jsonCaptor.getValue();

        // The toMap shape must include organizationId on the way down.
        @SuppressWarnings("unchecked")
        Map<String, Object> roundTripped = objectMapper.readValue(storedJson, Map.class);
        assertThat(roundTripped)
            .as("Redis payload must include organizationId so fromMap can read it back.")
            .containsEntry("organizationId", "org-acme");

        // And fromMap must reconstruct the field on the way up.
        when(valueOps.getAndDelete("agent:pending:corr-org-1")).thenReturn(storedJson);
        java.util.Optional<PendingAgent> reclaimed = store.claim("corr-org-1");
        assertThat(reclaimed).isPresent();
        assertThat(reclaimed.get().organizationId())
            .as("PendingAgent.organizationId must survive the Redis round-trip - "
              + "without it the async-delivery path persists chat agent observability "
              + "in personal scope and the strict org finder cannot find the row.")
            .isEqualTo("org-acme");
    }

    @Test
    @DisplayName("PR20 - null organizationId round-trips as null (personal scope)")
    void organizationIdNullRoundTripsAsNull() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        // Sample agent has no orgId - the back-compat 19-arg constructor delegates with null.
        PendingAgent original = sampleAgent();
        assertThat(original.organizationId()).isNull();

        store.store(original);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(anyString(), jsonCaptor.capture(), anyLong(), any(TimeUnit.class));
        when(valueOps.getAndDelete("agent:pending:corr-1")).thenReturn(jsonCaptor.getValue());

        java.util.Optional<PendingAgent> reclaimed = store.claim("corr-1");
        assertThat(reclaimed).isPresent();
        assertThat(reclaimed.get().organizationId())
            .as("Null organizationId on enqueue must round-trip as null - the receiver "
              + "treats this as personal scope on the observability row.")
            .isNull();
    }

    @Test
    @DisplayName("PR20 - pre-deploy Redis blob without organizationId field deserializes safely as null")
    void preDeployRedisBlobMissingFieldStaysNull() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        // Synthesize a pre-PR20 Redis payload that does NOT have an organizationId key.
        // During a rolling deploy these are the in-flight blobs that the post-deploy code
        // will deserialize. The receiver must treat missing-key as null = personal scope,
        // not throw a NullPointerException or ClassCastException.
        Map<String, Object> preDeployMap = new HashMap<>();
        preDeployMap.put("correlationId", "corr-pre-deploy");
        preDeployMap.put("runId", "run-1");
        preDeployMap.put("nodeId", "agent:x");
        preDeployMap.put("nodeLabel", "x");
        preDeployMap.put("dagTriggerId", "trigger:y");
        preDeployMap.put("epoch", 0);
        preDeployMap.put("itemIndex", 0);
        preDeployMap.put("itemId", "0");
        preDeployMap.put("agentType", "agent");
        preDeployMap.put("tenantId", "tenant-1");
        preDeployMap.put("startedAtEpochMs", Instant.now().toEpochMilli());
        // organizationId KEY ABSENT.

        String preDeployJson = objectMapper.writeValueAsString(preDeployMap);
        when(valueOps.getAndDelete("agent:pending:corr-pre-deploy")).thenReturn(preDeployJson);

        java.util.Optional<PendingAgent> reclaimed = store.claim("corr-pre-deploy");
        assertThat(reclaimed).isPresent();
        assertThat(reclaimed.get().organizationId())
            .as("Missing organizationId key on pre-PR20 Redis blob must deserialize as null - "
              + "the rolling-deploy window must not crash on in-flight async agents.")
            .isNull();
    }
}
