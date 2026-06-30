package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 1a.2 - pin the per-conversation skills-snapshot contract. These tests
 * lock the derivation-key shape, TTL-based staleness, the map-of-keys column
 * structure (so two call sites coexist), and the graceful-degrade path so a
 * broken cache never breaks a turn - worst case the caller re-fetches from
 * agent-service.
 */
@DisplayName("SkillsSnapshotService")
@ExtendWith(MockitoExtension.class)
class SkillsSnapshotServiceTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private SkillsSnapshotService service;

    @BeforeEach
    void enableService() throws Exception {
        Field enabledField = SkillsSnapshotService.class.getDeclaredField("enabled");
        enabledField.setAccessible(true);
        enabledField.setBoolean(service, true);
    }

    /** Helper: build a conversation whose skills_snapshot_json holds a single entry for {@code key}. */
    private Conversation conversationWithEntry(String key, String renderedText, Instant cachedAt) {
        Conversation conv = new Conversation();
        Map<String, Object> entry = new HashMap<>();
        entry.put(SkillsSnapshotService.FIELD_RENDERED_TEXT, renderedText);
        entry.put(SkillsSnapshotService.FIELD_CACHED_AT, cachedAt.toString());
        Map<String, Object> byKey = new HashMap<>();
        byKey.put(key, entry);
        conv.setSkillsSnapshotJson(byKey);
        return conv;
    }

    // ==================== deriveKey ====================

    @Test
    @DisplayName("deriveKey is stable regardless of input skill-id order")
    void deriveKeyStableAcrossOrder() {
        String k1 = SkillsSnapshotService.deriveKey("agent-1", List.of("sk-b", "sk-a", "sk-c"));
        String k2 = SkillsSnapshotService.deriveKey("agent-1", List.of("sk-c", "sk-a", "sk-b"));
        String k3 = SkillsSnapshotService.deriveKey("agent-1", List.of("sk-a", "sk-b", "sk-c"));
        assertThat(k1).isEqualTo(k2).isEqualTo(k3);
    }

    @Test
    @DisplayName("deriveKey encodes agentId separately from the hashed skill ids")
    void deriveKeyShape() {
        String k = SkillsSnapshotService.deriveKey("agent-xyz", List.of("sk-1"));
        assertThat(k).startsWith("agent-xyz|").hasSizeGreaterThan("agent-xyz|".length());
    }

    @Test
    @DisplayName("deriveKey tolerates null agentId (general chat) and null/empty skill list")
    void deriveKeyNullInputs() {
        String nullAgent = SkillsSnapshotService.deriveKey(null, List.of("sk-1"));
        assertThat(nullAgent).startsWith("|");

        String emptyList = SkillsSnapshotService.deriveKey("agent-1", List.of());
        String nullList = SkillsSnapshotService.deriveKey("agent-1", null);
        assertThat(emptyList).isEqualTo(nullList);
    }

    @Test
    @DisplayName("different agent ids produce different keys for the same skill set")
    void differentAgentsDifferentKeys() {
        String k1 = SkillsSnapshotService.deriveKey("agent-A", List.of("sk-1"));
        String k2 = SkillsSnapshotService.deriveKey("agent-B", List.of("sk-1"));
        assertThat(k1).isNotEqualTo(k2);
    }

    // ==================== loadIfFresh ====================

    @Test
    @DisplayName("loadIfFresh returns the cached text when key matches and TTL not expired")
    void loadHitWhenKeyAndTtlOk() {
        when(conversationRepository.findById("conv-1"))
                .thenReturn(Optional.of(conversationWithEntry("agent-1|abc", "[SKILLS]\n- Alpha", Instant.now())));

        Optional<String> loaded = service.loadIfFresh("conv-1", "agent-1|abc");

        assertThat(loaded).hasValue("[SKILLS]\n- Alpha");
    }

    @Test
    @DisplayName("loadIfFresh is a miss when the key doesn't match any entry")
    void loadMissOnKeyDrift() {
        when(conversationRepository.findById("conv-1"))
                .thenReturn(Optional.of(conversationWithEntry("agent-1|abc", "[SKILLS]\n", Instant.now())));

        Optional<String> loaded = service.loadIfFresh("conv-1", "agent-1|different");

        assertThat(loaded).isEmpty();
    }

    @Test
    @DisplayName("loadIfFresh is a miss when the snapshot is older than TTL")
    void loadMissOnTtlExpiry() {
        Instant stale = Instant.now().minus(SkillsSnapshotService.TTL).minus(1, ChronoUnit.MINUTES);
        when(conversationRepository.findById("conv-1"))
                .thenReturn(Optional.of(conversationWithEntry("agent-1|abc", "[SKILLS]\n", stale)));

        Optional<String> loaded = service.loadIfFresh("conv-1", "agent-1|abc");

        assertThat(loaded).isEmpty();
    }

    @Test
    @DisplayName("loadIfFresh is a hit when the snapshot age is just under the TTL boundary")
    void loadHitJustUnderTtlBoundary() {
        // Pins the comparison semantics: a snapshot cached ~1s short of TTL
        // must still be fresh. This catches an accidental off-by-one that
        // changes the effective TTL (e.g. `!isBefore` swap tightening the window
        // by a full resolution unit, or an incorrect `minus` direction).
        Instant cachedAt = Instant.now().minus(SkillsSnapshotService.TTL).plus(1, ChronoUnit.SECONDS);
        when(conversationRepository.findById("conv-1"))
                .thenReturn(Optional.of(conversationWithEntry("agent-1|abc", "[SKILLS]\n", cachedAt)));

        Optional<String> loaded = service.loadIfFresh("conv-1", "agent-1|abc");

        assertThat(loaded).hasValue("[SKILLS]\n");
    }

    @Test
    @DisplayName("loadIfFresh returns empty when the conversation row has no snapshot yet")
    void loadMissOnNullSnapshot() {
        Conversation conv = new Conversation();
        conv.setSkillsSnapshotJson(null);
        when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

        assertThat(service.loadIfFresh("conv-1", "agent-1|abc")).isEmpty();
    }

    @Test
    @DisplayName("loadIfFresh returns empty when conversation does not exist")
    void loadMissOnMissingConversation() {
        when(conversationRepository.findById("conv-missing")).thenReturn(Optional.empty());
        assertThat(service.loadIfFresh("conv-missing", "agent-1|abc")).isEmpty();
    }

    @Test
    @DisplayName("loadIfFresh is a miss when cached_at is malformed (graceful degrade)")
    void loadMissOnUnparseableCachedAt() {
        Conversation conv = new Conversation();
        Map<String, Object> entry = new HashMap<>();
        entry.put(SkillsSnapshotService.FIELD_RENDERED_TEXT, "[SKILLS]\n");
        entry.put(SkillsSnapshotService.FIELD_CACHED_AT, "not-an-instant");
        Map<String, Object> byKey = new HashMap<>();
        byKey.put("agent-1|abc", entry);
        conv.setSkillsSnapshotJson(byKey);
        when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

        assertThat(service.loadIfFresh("conv-1", "agent-1|abc")).isEmpty();
    }

    @Test
    @DisplayName("loadIfFresh is a miss when the matched entry is not a map (type drift)")
    void loadMissOnMalformedEntryType() {
        Conversation conv = new Conversation();
        Map<String, Object> byKey = new HashMap<>();
        byKey.put("agent-1|abc", "not-a-map");
        conv.setSkillsSnapshotJson(byKey);
        when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

        assertThat(service.loadIfFresh("conv-1", "agent-1|abc")).isEmpty();
    }

    @Test
    @DisplayName("loadIfFresh returns the right entry when multiple keys coexist in the same row")
    void loadPicksCorrectEntryFromMulti() {
        Conversation conv = new Conversation();
        Map<String, Object> entryA = new HashMap<>();
        entryA.put(SkillsSnapshotService.FIELD_RENDERED_TEXT, "[SKILLS]\n- Agent");
        entryA.put(SkillsSnapshotService.FIELD_CACHED_AT, Instant.now().toString());
        Map<String, Object> entryB = new HashMap<>();
        entryB.put(SkillsSnapshotService.FIELD_RENDERED_TEXT, "[SKILLS]\n- User");
        entryB.put(SkillsSnapshotService.FIELD_CACHED_AT, Instant.now().toString());
        Map<String, Object> byKey = new HashMap<>();
        byKey.put("agent-1|empty", entryA);
        byKey.put("|user-ids", entryB);
        conv.setSkillsSnapshotJson(byKey);
        when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

        assertThat(service.loadIfFresh("conv-1", "agent-1|empty")).hasValue("[SKILLS]\n- Agent");
        assertThat(service.loadIfFresh("conv-1", "|user-ids")).hasValue("[SKILLS]\n- User");
    }

    @Test
    @DisplayName("loadIfFresh short-circuits on null inputs")
    void loadShortCircuitsOnNulls() {
        assertThat(service.loadIfFresh(null, "key")).isEmpty();
        assertThat(service.loadIfFresh("conv-1", null)).isEmpty();
        verify(conversationRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("loadIfFresh returns empty when the service is disabled via property")
    void loadRespectsDisableFlag() throws Exception {
        Field enabledField = SkillsSnapshotService.class.getDeclaredField("enabled");
        enabledField.setAccessible(true);
        enabledField.setBoolean(service, false);

        assertThat(service.loadIfFresh("conv-1", "agent-1|abc")).isEmpty();
        verify(conversationRepository, never()).findById(anyString());
    }

    // ==================== save ====================

    @Test
    @DisplayName("save merges a single keyed entry with rendered_text and cached_at")
    void saveMergesExpectedEntry() throws Exception {
        when(conversationRepository.mergeSkillsSnapshotEntry(eq("conv-1"), anyString())).thenReturn(1);

        service.save("conv-1", "agent-1|abc", "[SKILLS]\n- Foo");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(conversationRepository).mergeSkillsSnapshotEntry(eq("conv-1"), captor.capture());
        String json = captor.getValue();
        // Round-trip to assert shape: {"agent-1|abc": {"rendered_text": "...", "cached_at": "..."}}
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = new ObjectMapper().readValue(json, Map.class);
        assertThat(parsed).containsOnlyKeys("agent-1|abc");
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) parsed.get("agent-1|abc");
        assertThat(entry).containsEntry(SkillsSnapshotService.FIELD_RENDERED_TEXT, "[SKILLS]\n- Foo");
        assertThat(entry.get(SkillsSnapshotService.FIELD_CACHED_AT)).asString().isNotBlank();
    }

    @Test
    @DisplayName("save does nothing on null/blank rendered text")
    void saveSkipsOnBlankInput() {
        service.save("conv-1", "agent-1|abc", "");
        service.save("conv-1", "agent-1|abc", null);
        verify(conversationRepository, never()).mergeSkillsSnapshotEntry(anyString(), anyString());
    }

    @Test
    @DisplayName("save swallows repository exceptions - a cache-write failure never breaks a turn")
    void saveSwallowsExceptions() {
        when(conversationRepository.mergeSkillsSnapshotEntry(anyString(), anyString()))
                .thenThrow(new RuntimeException("DB down"));

        service.save("conv-1", "agent-1|abc", "[SKILLS]\n");
        // No throw - test passes by virtue of not raising.
    }

    @Test
    @DisplayName("save respects the disabled flag")
    void saveRespectsDisableFlag() throws Exception {
        Field enabledField = SkillsSnapshotService.class.getDeclaredField("enabled");
        enabledField.setAccessible(true);
        enabledField.setBoolean(service, false);

        service.save("conv-1", "agent-1|abc", "[SKILLS]\n");
        verify(conversationRepository, never()).mergeSkillsSnapshotEntry(anyString(), any());
    }

    @Test
    @DisplayName("save escapes rendered text containing JSON-special characters (quotes, backslashes, newlines)")
    void saveEscapesJsonPayload() throws Exception {
        when(conversationRepository.mergeSkillsSnapshotEntry(eq("conv-1"), anyString())).thenReturn(1);

        String tricky = "[SKILLS]\n- \"quoted\" skill with \\ backslash and\nnewline";
        service.save("conv-1", "agent-1|abc", tricky);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(conversationRepository).mergeSkillsSnapshotEntry(eq("conv-1"), captor.capture());
        // The produced JSON must parse cleanly and round-trip to the exact input.
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = new ObjectMapper().readValue(captor.getValue(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) parsed.get("agent-1|abc");
        assertThat(entry.get(SkillsSnapshotService.FIELD_RENDERED_TEXT)).isEqualTo(tricky);
    }
}
