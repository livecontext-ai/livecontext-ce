package com.apimarketplace.conversation.service.ai.schema;

import com.apimarketplace.common.event.KeyValueStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Stage 4a.4 - tracks which {@code (tool, action)} pairs the LLM has seen
 * a help response for within a single conversation, so the tools-prefix
 * builder can decide whether to serve the slim (names-only) schema or
 * inject the full schema via a {@code ToolParamValidationError}.
 *
 * <p><b>Host.</b> The original plan referenced a non-existent
 * {@code ChatContext} bean (R41 in the planning audit). This implementation
 * hosts the state in the existing {@link KeyValueStore} abstraction -
 * Redis-backed in microservice mode, in-memory in CE mode. Keyed by
 * conversation id, so every pod sees the same state without the need for
 * a request-scoped bean or sticky routing.
 *
 * <p><b>Freshness is derived, not stored.</b> The persisted state carries
 * {@link HelpSeenState#lastSeenTurn()}; freshness is computed at read time
 * as {@code currentTurn - lastSeenTurn <= hotWarmTurnBudget}. This keeps
 * the gate honest across restarts and ensures aging out of HOT/WARM
 * silently invalidates the "already helped" flag.
 *
 * <p><b>Rehydration.</b> On conversation resume the registry is rebuilt
 * from summary metadata (Stage 3/5 plumbing) via
 * {@link #rehydrateFromSummary(String, Collection, int)}. Rehydrated
 * entries are pinned with a stale {@link HelpSeenState#lastSeenTurn()}
 * (current − budget − 1) so the <em>next</em> call naturally fails the
 * freshness check and forces a cheap re-help. This is deliberate: the
 * summary tells us the action was helped in the past, but the help
 * content itself has been compacted out, so the LLM needs it back.
 *
 * <p><b>Keying.</b> Tool-action keys follow the same convention as
 * {@link SchemaSlimExclusionPolicy}: lowercased {@code "tool:action"}.
 * A blank action collapses to {@code "tool:"} - legal, matches the
 * "tool has a single default action" shape. Null/blank tool or conversation
 * id short-circuit to a no-op or empty result; the registry never throws
 * on missing inputs.
 */
@Slf4j
@Service
public class HelpSeenRegistry {

    private static final String KEY_PREFIX = "conv:";
    private static final String KEY_SUFFIX = ":helpseen";

    private final KeyValueStore store;
    private final ObjectMapper objectMapper;
    private final HelpSeenProperties properties;

    public HelpSeenRegistry(KeyValueStore store,
                            ObjectMapper objectMapper,
                            HelpSeenProperties properties) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Record that a help response for {@code (toolName, action)} was
     * observed on {@code currentTurn}. Overwrites any prior entry so the
     * latest turn wins - we don't need a history, only the most recent
     * observation.
     *
     * <p>Applies the configured TTL to the whole hash on every write, so
     * active conversations keep extending their Redis retention while
     * idle ones eventually expire.
     *
     * <p>No-op if any of the required inputs is null/blank - the registry
     * is best-effort and should never reject a call that lacks context.
     */
    public void markSeen(String conversationId, String toolName, String action, int currentTurn) {
        if (isBlank(conversationId) || isBlank(toolName)) {
            return;
        }
        String field = key(toolName, action);
        HelpSeenState entry = new HelpSeenState(field, Instant.now(), currentTurn);
        String json;
        try {
            json = objectMapper.writeValueAsString(entry);
        } catch (JsonProcessingException e) {
            log.warn("HelpSeenRegistry: failed to serialise entry conv={} key={} - dropping", conversationId, field, e);
            return;
        }
        String hashKey = hashKey(conversationId);
        store.hashPut(hashKey, field, json);
        // Re-apply TTL on every write so active conversations don't expire mid-flight.
        store.expire(hashKey, properties.getTtl());
    }

    /**
     * Look up a single entry. Empty result if no entry exists or the stored
     * JSON is unreadable (parse failure is logged, not thrown - a malformed
     * entry must not block the turn).
     */
    public Optional<HelpSeenState> get(String conversationId, String toolName, String action) {
        if (isBlank(conversationId) || isBlank(toolName)) {
            return Optional.empty();
        }
        String field = key(toolName, action);
        Optional<String> raw = store.hashGet(hashKey(conversationId), field);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        return deserialize(conversationId, field, raw.get());
    }

    /**
     * Return every recorded entry for a conversation, decoded from JSON.
     * Malformed entries are skipped with a warning - they never surface to
     * the caller. Returns an empty map for unknown conversations.
     *
     * <p>Primarily used by the tools-prefix builder for bulk decisions, and
     * by tests/metrics endpoints for visibility.
     */
    public Map<String, HelpSeenState> all(String conversationId) {
        if (isBlank(conversationId)) {
            return Collections.emptyMap();
        }
        Map<String, String> rawEntries = store.hashGetAll(hashKey(conversationId));
        if (rawEntries == null || rawEntries.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, HelpSeenState> result = new LinkedHashMap<>();
        rawEntries.forEach((field, raw) -> deserialize(conversationId, field, raw)
                .ifPresent(state -> result.put(field, state)));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Freshness gate - {@code true} iff the entry exists AND
     * {@code currentTurn - lastSeenTurn <= hotWarmTurnBudget}. Inclusive at
     * the boundary: an entry exactly {@code budget} turns old is still
     * considered fresh. A missing entry is never fresh.
     */
    public boolean isFresh(String conversationId, String toolName, String action, int currentTurn) {
        return get(conversationId, toolName, action)
                .map(state -> {
                    // Guard against a lastSeenTurn ahead of currentTurn (test typo
                    // or cross-pod clock drift on turn counters): clamp the delta
                    // to 0 so a "from the future" entry is still only counted as
                    // fresh, never indefinitely-fresh.
                    int delta = Math.max(0, currentTurn - state.lastSeenTurn());
                    return delta <= properties.getHotWarmTurnBudget();
                })
                .orElse(false);
    }

    /**
     * Seed the registry from a summary-produced list of
     * {@code helpedActions} (e.g. {@code ["agent:publish", "skill:create"]})
     * on conversation resume. Each entry is pinned with a
     * {@code lastSeenTurn} of {@code currentTurn - budget - 1}, which
     * guarantees {@link #isFresh} returns {@code false} on the next call
     * and forces a re-help before the LLM attempts the action.
     *
     * <p>Existing entries are NOT overwritten - if a live turn has already
     * recorded a fresh observation, rehydration must not knock it stale.
     *
     * <p><b>Concurrency.</b> The "check then write" is not atomic across
     * pods; in the rare interleaving where two pods both rehydrate the
     * same absent entry, both write a stale value (identical outcome).
     * A subsequent live {@link #markSeen} always clobbers it cleanly -
     * last-writer-wins is the safe direction here because live
     * observations are strictly newer than any rehydrated stale turn.
     */
    public void rehydrateFromSummary(String conversationId, Collection<String> helpedActions, int currentTurn) {
        if (isBlank(conversationId) || helpedActions == null || helpedActions.isEmpty()) {
            return;
        }
        String hashKey = hashKey(conversationId);
        int staleTurn = currentTurn - properties.getHotWarmTurnBudget() - 1;
        Instant now = Instant.now();

        Map<String, String> toWrite = new HashMap<>();
        for (String rawKey : helpedActions) {
            if (rawKey == null || rawKey.isBlank()) continue;
            String field = rawKey.toLowerCase(Locale.ROOT);
            // Skip if an entry already exists - live observations win.
            if (store.hashGet(hashKey, field).isPresent()) continue;
            HelpSeenState state = new HelpSeenState(field, now, staleTurn);
            try {
                toWrite.put(field, objectMapper.writeValueAsString(state));
            } catch (JsonProcessingException e) {
                log.warn("HelpSeenRegistry: failed to serialise rehydration entry conv={} key={}", conversationId, field, e);
            }
        }
        if (!toWrite.isEmpty()) {
            store.hashPutAll(hashKey, toWrite, properties.getTtl());
        }
    }

    private String key(String toolName, String action) {
        String normalisedTool = toolName.toLowerCase(Locale.ROOT);
        String normalisedAction = action == null ? "" : action.toLowerCase(Locale.ROOT);
        return normalisedTool + ":" + normalisedAction;
    }

    private String hashKey(String conversationId) {
        return KEY_PREFIX + conversationId + KEY_SUFFIX;
    }

    private Optional<HelpSeenState> deserialize(String conversationId, String field, String raw) {
        try {
            return Optional.of(Objects.requireNonNull(objectMapper.readValue(raw, HelpSeenState.class)));
        } catch (Exception e) {
            log.warn("HelpSeenRegistry: malformed entry conv={} key={} - ignoring", conversationId, field, e);
            return Optional.empty();
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
