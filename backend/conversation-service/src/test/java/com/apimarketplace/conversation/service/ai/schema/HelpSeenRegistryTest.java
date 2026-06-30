package com.apimarketplace.conversation.service.ai.schema;

import com.apimarketplace.common.event.KeyValueStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 4a.4 - pin the help-seen freshness gating contract.
 *
 * <p>Uses an in-memory fake {@link KeyValueStore} so the tests exercise the
 * full JSON round-trip (registry → Jackson → store → Jackson → registry)
 * without pulling in an embedded Redis. The fake supports only the subset
 * of operations the registry actually uses (hashPut, hashGet, hashGetAll,
 * hashPutAll, expire) and tracks the most-recent TTL applied per key so
 * we can assert TTL behaviour.
 */
@DisplayName("HelpSeenRegistry - per-conversation help freshness (Stage 4a.4)")
class HelpSeenRegistryTest {

    private FakeKeyValueStore store;
    private HelpSeenProperties props;
    private HelpSeenRegistry registry;

    private static final String CONV = "conv-123";

    @BeforeEach
    void setUp() {
        store = new FakeKeyValueStore();
        props = new HelpSeenProperties();
        registry = new HelpSeenRegistry(store, new ObjectMapper().findAndRegisterModules(), props);
    }

    @Nested
    @DisplayName("markSeen + get round-trip")
    class MarkSeenRoundTrip {

        @Test
        @DisplayName("writes an entry that can be read back")
        void roundTrip() {
            registry.markSeen(CONV, "agent", "publish", 5);

            Optional<HelpSeenState> entry = registry.get(CONV, "agent", "publish");
            assertThat(entry).isPresent();
            assertThat(entry.get().toolAction()).isEqualTo("agent:publish");
            assertThat(entry.get().lastSeenTurn()).isEqualTo(5);
            assertThat(entry.get().seenAt()).isNotNull();
        }

        @Test
        @DisplayName("tool+action keys are lowercased on both sides")
        void keyCaseNormalised() {
            registry.markSeen(CONV, "Agent", "Publish", 3);

            assertThat(registry.get(CONV, "AGENT", "PUBLISH")).isPresent();
            assertThat(registry.get(CONV, "agent", "publish")).isPresent();
        }

        @Test
        @DisplayName("blank action collapses to 'tool:' key")
        void blankActionCollapses() {
            registry.markSeen(CONV, "request_credential", null, 1);

            assertThat(registry.get(CONV, "request_credential", null)).isPresent();
            assertThat(registry.get(CONV, "request_credential", "")).isPresent();
        }

        @Test
        @DisplayName("second markSeen overwrites the first (latest turn wins)")
        void latestWins() {
            registry.markSeen(CONV, "agent", "publish", 2);
            registry.markSeen(CONV, "agent", "publish", 7);

            assertThat(registry.get(CONV, "agent", "publish"))
                    .map(HelpSeenState::lastSeenTurn).hasValue(7);
        }

        @Test
        @DisplayName("TTL is reapplied on every write")
        void ttlReappliedOnWrite() {
            props.setTtlHours(12);
            registry.markSeen(CONV, "agent", "publish", 1);

            assertThat(store.lastExpireFor("conv:" + CONV + ":helpseen"))
                    .isEqualTo(Duration.ofHours(12));
        }
    }

    @Nested
    @DisplayName("isFresh freshness rule")
    class FreshnessRule {

        @Test
        @DisplayName("fresh when lastSeenTurn within budget")
        void withinBudget() {
            props.setHotWarmTurnBudget(20);
            registry.markSeen(CONV, "agent", "publish", 10);

            assertThat(registry.isFresh(CONV, "agent", "publish", 15)).isTrue();
        }

        @Test
        @DisplayName("fresh at exactly budget boundary (inclusive)")
        void atBoundary() {
            props.setHotWarmTurnBudget(20);
            registry.markSeen(CONV, "agent", "publish", 0);

            assertThat(registry.isFresh(CONV, "agent", "publish", 20)).isTrue();
        }

        @Test
        @DisplayName("stale just outside budget")
        void outsideBudget() {
            props.setHotWarmTurnBudget(20);
            registry.markSeen(CONV, "agent", "publish", 0);

            assertThat(registry.isFresh(CONV, "agent", "publish", 21)).isFalse();
        }

        @Test
        @DisplayName("missing entry is never fresh")
        void missingEntryNotFresh() {
            assertThat(registry.isFresh(CONV, "agent", "publish", 5)).isFalse();
        }

        @Test
        @DisplayName("zero budget allows only same-turn freshness")
        void zeroBudget() {
            props.setHotWarmTurnBudget(0);
            registry.markSeen(CONV, "agent", "publish", 5);

            assertThat(registry.isFresh(CONV, "agent", "publish", 5)).isTrue();
            assertThat(registry.isFresh(CONV, "agent", "publish", 6)).isFalse();
        }

        @Test
        @DisplayName("negative configured budget clamps to zero (no NPE, no unintended freshness)")
        void negativeBudgetClamped() {
            props.setHotWarmTurnBudget(-5);
            registry.markSeen(CONV, "agent", "publish", 5);

            assertThat(registry.isFresh(CONV, "agent", "publish", 5)).isTrue();
            assertThat(registry.isFresh(CONV, "agent", "publish", 6)).isFalse();
        }

        @Test
        @DisplayName("entry 'from the future' (lastSeenTurn > currentTurn) is fresh only once, not indefinitely")
        void futureEntryClamped() {
            // Simulates test typo / pod clock drift: entry pinned at turn 100
            // but current turn is 5. Delta would be -95 → uncapped that's
            // always <= budget (always fresh). With clamp-to-0 the entry is
            // fresh only while currentTurn < lastSeenTurn + budget range.
            props.setHotWarmTurnBudget(20);
            registry.markSeen(CONV, "agent", "publish", 100);

            // current < lastSeen → clamp to 0 → still within budget → fresh.
            assertThat(registry.isFresh(CONV, "agent", "publish", 5)).isTrue();
            // current == lastSeen → delta 0 → fresh.
            assertThat(registry.isFresh(CONV, "agent", "publish", 100)).isTrue();
            // current at boundary (lastSeen + budget) → fresh.
            assertThat(registry.isFresh(CONV, "agent", "publish", 120)).isTrue();
            // current > lastSeen + budget → stale.
            assertThat(registry.isFresh(CONV, "agent", "publish", 121)).isFalse();
        }
    }

    @Nested
    @DisplayName("all() returns every valid entry")
    class AllEntries {

        @Test
        @DisplayName("empty map for unknown conversation")
        void unknownConversationEmpty() {
            assertThat(registry.all("unknown-conv")).isEmpty();
        }

        @Test
        @DisplayName("returns all recorded entries for a conversation")
        void returnsEntries() {
            registry.markSeen(CONV, "agent", "publish", 1);
            registry.markSeen(CONV, "skill", "create", 2);

            Map<String, HelpSeenState> all = registry.all(CONV);
            assertThat(all).hasSize(2);
            assertThat(all).containsKeys("agent:publish", "skill:create");
        }

        @Test
        @DisplayName("malformed entries are silently skipped, not thrown")
        void malformedSkipped() {
            // Seed one valid entry normally, then poison the hash with a
            // malformed value - registry must ignore it without failing.
            registry.markSeen(CONV, "agent", "publish", 1);
            store.hashPut("conv:" + CONV + ":helpseen", "bad:entry", "not-json-at-all");

            Map<String, HelpSeenState> all = registry.all(CONV);
            assertThat(all).containsOnlyKeys("agent:publish");
        }
    }

    @Nested
    @DisplayName("rehydrateFromSummary")
    class Rehydration {

        @Test
        @DisplayName("seeds entries as stale so next isFresh call returns false")
        void seededEntriesAreStale() {
            props.setHotWarmTurnBudget(20);
            registry.rehydrateFromSummary(CONV, List.of("agent:publish", "skill:create"), 100);

            assertThat(registry.isFresh(CONV, "agent", "publish", 100)).isFalse();
            assertThat(registry.isFresh(CONV, "skill", "create", 100)).isFalse();
        }

        @Test
        @DisplayName("does NOT overwrite live entries")
        void doesNotOverwriteLive() {
            registry.markSeen(CONV, "agent", "publish", 50);
            registry.rehydrateFromSummary(CONV, List.of("agent:publish"), 100);

            // Live entry from turn 50 must survive; rehydration only fills gaps.
            Optional<HelpSeenState> state = registry.get(CONV, "agent", "publish");
            assertThat(state).map(HelpSeenState::lastSeenTurn).hasValue(50);
        }

        @Test
        @DisplayName("null / empty input → no-op")
        void nullEmptyNoOp() {
            registry.rehydrateFromSummary(CONV, null, 1);
            registry.rehydrateFromSummary(CONV, List.of(), 1);

            assertThat(registry.all(CONV)).isEmpty();
        }

        @Test
        @DisplayName("entries are lowercased before storage")
        void entriesLowercased() {
            registry.rehydrateFromSummary(CONV, List.of("AGENT:PUBLISH"), 100);

            assertThat(registry.get(CONV, "agent", "publish")).isPresent();
        }

        @Test
        @DisplayName("stale-turn formula is exactly currentTurn - budget - 1")
        void staleTurnFormula() {
            props.setHotWarmTurnBudget(20);
            registry.rehydrateFromSummary(CONV, List.of("agent:publish"), 100);

            Optional<HelpSeenState> state = registry.get(CONV, "agent", "publish");
            assertThat(state).isPresent();
            assertThat(state.get().lastSeenTurn()).isEqualTo(100 - 20 - 1); // = 79
        }

        @Test
        @DisplayName("rehydrate applies TTL to the Redis hash")
        void rehydrateAppliesTtl() {
            props.setTtlHours(6);
            registry.rehydrateFromSummary(CONV, List.of("agent:publish"), 10);

            assertThat(store.lastExpireFor("conv:" + CONV + ":helpseen"))
                    .isEqualTo(Duration.ofHours(6));
        }
    }

    @Nested
    @DisplayName("null / blank safety")
    class NullBlank {

        @Test
        @DisplayName("null conversationId → markSeen is a no-op")
        void nullConvMarkSeenNoOp() {
            KeyValueStore strict = mock(KeyValueStore.class);
            HelpSeenRegistry r = new HelpSeenRegistry(strict, new ObjectMapper(), props);

            r.markSeen(null, "agent", "publish", 1);
            r.markSeen("   ", "agent", "publish", 1);

            verify(strict, never()).hashPut(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("null tool → markSeen is a no-op")
        void nullToolMarkSeenNoOp() {
            KeyValueStore strict = mock(KeyValueStore.class);
            HelpSeenRegistry r = new HelpSeenRegistry(strict, new ObjectMapper(), props);

            r.markSeen(CONV, null, "publish", 1);
            r.markSeen(CONV, "   ", "publish", 1);

            verify(strict, never()).hashPut(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("null conversationId → get returns empty")
        void nullConvGetEmpty() {
            assertThat(registry.get(null, "agent", "publish")).isEmpty();
            assertThat(registry.get("   ", "agent", "publish")).isEmpty();
        }

        @Test
        @DisplayName("null conversationId → isFresh returns false")
        void nullConvIsFreshFalse() {
            assertThat(registry.isFresh(null, "agent", "publish", 5)).isFalse();
        }

        @Test
        @DisplayName("null conversationId → all returns empty map")
        void nullConvAllEmpty() {
            assertThat(registry.all(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("hash key format")
    class HashKeyFormat {

        @Test
        @DisplayName("uses 'conv:<id>:helpseen' convention")
        void conventionHonoured() {
            registry.markSeen(CONV, "agent", "publish", 1);
            assertThat(store.hashExists("conv:conv-123:helpseen")).isTrue();
        }
    }

    /** Minimal in-memory KeyValueStore that covers the ops the registry uses. */
    private static final class FakeKeyValueStore implements KeyValueStore {

        private final Map<String, Map<String, String>> hashes = new HashMap<>();
        private final Map<String, Duration> ttls = new HashMap<>();

        boolean hashExists(String key) {
            return hashes.containsKey(key);
        }

        Duration lastExpireFor(String key) {
            return ttls.get(key);
        }

        @Override
        public void hashPut(String key, String field, String value) {
            hashes.computeIfAbsent(key, k -> new HashMap<>()).put(field, value);
        }

        @Override
        public void hashPutAll(String key, Map<String, String> fields, Duration ttl) {
            hashes.computeIfAbsent(key, k -> new HashMap<>()).putAll(fields);
            if (ttl != null) ttls.put(key, ttl);
        }

        @Override
        public Map<String, String> hashGetAll(String key) {
            return hashes.getOrDefault(key, Map.of());
        }

        @Override
        public Optional<String> hashGet(String key, String field) {
            return Optional.ofNullable(hashes.getOrDefault(key, Map.of()).get(field));
        }

        @Override
        public void expire(String key, Duration ttl) {
            ttls.put(key, ttl);
        }

        // Unused ops - throw loudly if something new starts calling them.
        @Override public void set(String k, String v, Duration t) { throw new UnsupportedOperationException(); }
        @Override public Optional<String> get(String k) { throw new UnsupportedOperationException(); }
        @Override public boolean exists(String k) { throw new UnsupportedOperationException(); }
        @Override public void delete(String k) { throw new UnsupportedOperationException(); }
        @Override public long increment(String k, Duration t) { throw new UnsupportedOperationException(); }
        @Override public void listRightPush(String k, String v) { throw new UnsupportedOperationException(); }
        @Override public List<String> listRange(String k, long s, long e) { throw new UnsupportedOperationException(); }
        @Override public long listSize(String k) { throw new UnsupportedOperationException(); }
        @Override public void listTrim(String k, long s, long e) { throw new UnsupportedOperationException(); }
        @Override public void setAdd(String k, String... v) { throw new UnsupportedOperationException(); }
        @Override public void setRemove(String k, String... v) { throw new UnsupportedOperationException(); }
        @Override public java.util.Set<String> setMembers(String k) { throw new UnsupportedOperationException(); }
        @Override public boolean setIsMember(String k, String v) { throw new UnsupportedOperationException(); }
        @Override public void deleteByPattern(String p) { throw new UnsupportedOperationException(); }
    }
}
