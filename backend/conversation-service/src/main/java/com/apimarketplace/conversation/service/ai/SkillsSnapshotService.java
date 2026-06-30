package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stage 1a.2 - per-conversation snapshot of the rendered [SKILLS] block.
 *
 * <p><b>Why</b>: {@code AgentContextBuilder} rebuilds the skills tree on every
 * turn via two HTTP calls to agent-service ({@code getAgentSkillsSummary},
 * {@code getSkillsSummaryByIds}). The result is stable within a conversation
 * for minutes at a time, so re-fetching and re-rendering on every turn both
 * (a) wastes agent-service RTTs, and (b) breaks the Anthropic prompt-cache
 * prefix when the upstream fetch returns skills in a different order across
 * pods. This service stashes the rendered bytes on the conversation row and
 * hands them back on the next turn as long as the derivation key still matches.
 *
 * <p><b>Storage shape</b>: the {@code skills_snapshot_json} column is a JSONB
 * map of {@code "<derivation_key>": {rendered_text, cached_at}} so the two
 * call sites (agent-skills path and request-skills path in
 * {@code AgentContextBuilder}) can coexist in the same conversation row
 * without overwriting each other's cached bytes. Writes use PostgreSQL's
 * native {@code ||} JSONB merge for atomicity across pods.
 *
 * <p><b>Derivation key</b>: {@code "<agentId>|<sha1(sorted skillIds)>"}.
 * Captures the two inputs that change the rendered text:
 * <ul>
 *   <li>Agent identity (different agent → different skills bound to it).</li>
 *   <li>Default skill ids from the request (user toggles skills in-UI).</li>
 * </ul>
 * {@code agentId} may be {@code null} (general chat); in that case the key
 * is {@code "|<sha1(sorted skillIds)>"}. Skill-ids list may be empty; in that
 * case the hash component is {@code sha1("")}.
 *
 * <p><b>Invalidation</b>: TTL-based at {@link #TTL}. The plan's event-driven
 * invalidation (R37 - Redis pub/sub + {@code agentSkillTreeVersion}) is
 * deferred; until then, the TTL bounds the staleness window on skill-tree
 * edits. Concurrent writers race at most once per TTL bucket - last-write-wins
 * is safe because both payloads are correct for the same key.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillsSnapshotService {

    static final String FIELD_RENDERED_TEXT = "rendered_text";
    static final String FIELD_CACHED_AT = "cached_at";

    /** Staleness bound. 5 minutes matches the Anthropic cache TTL and keeps
     *  a single skill-tree edit from taking more than 5 min to propagate. */
    static final Duration TTL = Duration.ofMinutes(5);

    private final ConversationRepository conversationRepository;
    private final ObjectMapper objectMapper;

    @Value("${conversation.skills-snapshot.enabled:true}")
    private boolean enabled;

    /**
     * Derive the cache key for a given (agentId, skillIds) pair. Both inputs
     * may be {@code null} / empty; the output is always a valid key string.
     */
    public static String deriveKey(String agentId, List<String> skillIds) {
        List<String> sorted = skillIds == null ? new ArrayList<>() : new ArrayList<>(skillIds);
        Collections.sort(sorted);
        String joined = String.join(",", sorted);
        return (agentId == null ? "" : agentId) + "|" + sha1(joined);
    }

    /**
     * Load the cached rendered block for a given key if the snapshot exists,
     * the entry is present, and it hasn't expired. Returns empty otherwise -
     * caller must re-render and call {@link #save}.
     *
     * <p>Never throws: a malformed row (type drift, clock skew, missing field)
     * is treated as a cache miss and logged at DEBUG.
     */
    @SuppressWarnings("unchecked")
    public Optional<String> loadIfFresh(String conversationId, String expectedKey) {
        if (!enabled || conversationId == null || expectedKey == null) {
            return Optional.empty();
        }
        try {
            Optional<Conversation> opt = conversationRepository.findById(conversationId);
            if (opt.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> snapshotsByKey = opt.get().getSkillsSnapshotJson();
            if (snapshotsByKey == null) {
                return Optional.empty();
            }
            Object entryObj = snapshotsByKey.get(expectedKey);
            if (!(entryObj instanceof Map<?, ?>)) {
                return Optional.empty();
            }
            Map<String, Object> entry = (Map<String, Object>) entryObj;
            Object cachedAtRaw = entry.get(FIELD_CACHED_AT);
            if (!(cachedAtRaw instanceof String cachedAtStr)) {
                return Optional.empty();
            }
            Instant cachedAt;
            try {
                cachedAt = Instant.parse(cachedAtStr);
            } catch (Exception parseErr) {
                log.debug("Snapshot cached_at unparseable for conv {}: {}", conversationId, parseErr.getMessage());
                return Optional.empty();
            }
            // isAfter is strict: an instant exactly at cachedAt+TTL is still a hit.
            if (Instant.now().isAfter(cachedAt.plus(TTL))) {
                return Optional.empty();
            }
            Object renderedRaw = entry.get(FIELD_RENDERED_TEXT);
            if (!(renderedRaw instanceof String rendered) || rendered.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(rendered);
        } catch (Exception e) {
            log.debug("Skills snapshot load failed for conv {}: {}", conversationId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Persist the rendered block as a keyed entry in the conversation's
     * snapshot map. Other keys already present in the column are preserved
     * (atomic JSONB merge at the DB layer). Never throws - a DB failure here
     * just means the next turn re-fetches.
     *
     * <p>{@link Propagation#REQUIRES_NEW} isolates the cache write from the
     * enclosing request transaction: a DB hiccup here (lock timeout, failover
     * mid-write, constraint drift) cannot mark the caller's transaction
     * rollback-only. This is a best-effort write - missing cache ≠ broken turn.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(String conversationId, String key, String renderedText) {
        if (!enabled || conversationId == null || key == null || renderedText == null || renderedText.isBlank()) {
            return;
        }
        try {
            Map<String, Object> entry = new HashMap<>();
            entry.put(FIELD_RENDERED_TEXT, renderedText);
            entry.put(FIELD_CACHED_AT, Instant.now().toString());
            Map<String, Map<String, Object>> wrapper = new HashMap<>();
            wrapper.put(key, entry);
            String entryJson;
            try {
                entryJson = objectMapper.writeValueAsString(wrapper);
            } catch (JsonProcessingException jsonErr) {
                log.debug("Snapshot save: JSON encode failed for conv {}: {}", conversationId, jsonErr.getMessage());
                return;
            }
            int updated = conversationRepository.mergeSkillsSnapshotEntry(conversationId, entryJson);
            if (updated == 0) {
                log.debug("Skills snapshot save: conversation {} not found (already deleted?)", conversationId);
            }
        } catch (Exception e) {
            log.debug("Skills snapshot save failed for conv {}: {}", conversationId, e.getMessage());
        }
    }

    private static String sha1(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is a required algorithm on every JVM; this path is unreachable.
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }
}
