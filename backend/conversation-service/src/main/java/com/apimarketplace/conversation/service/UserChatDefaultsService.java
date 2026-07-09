package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.entity.UserChatDefaults;
import com.apimarketplace.conversation.repository.UserChatDefaultsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Read/write the per-(user, workspace) default chat options (V312).
 *
 * <p>The stored JSON mirrors the frontend {@code ChatConfig}. Writes are
 * whitelisted to the known keys so the column never accumulates arbitrary
 * client-supplied JSON (size + forward-compat hygiene). Unknown keys are dropped.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserChatDefaultsService {

    /**
     * The known {@code ChatConfig} keys (mirrors the frontend hook). {@code turnLimits}
     * is the nested object used by the general-chat shape; the three flat turn-limit keys
     * are accepted too for tolerance with the agent shape.
     */
    static final Set<String> ALLOWED_KEYS = Set.of(
            "temperature",
            "systemPrompt",
            "maxTokens",
            "maxIterations",
            "executionTimeout",
            "inactivityTimeout",
            "toolsMode",
            "webSearch",
            "imageGeneration",
            "autoAuthorizeTools",
            "defaultSkillIds",
            "turnLimits",
            "maxPerResourcePerTurn",
            "loopIdenticalStop",
            "loopConsecutiveStop",
            // V350 - per-(user, workspace) default compaction enable + cadence (flat keys;
            // seeded into a new conversation's chatConfig.compaction by the frontend).
            "compactionEnabled",
            "compactionAfterTurns",
            // Per-(user, workspace) default compaction SUMMARISER model (flat keys, same
            // seeding path as enable/cadence; consumed as chatConfig.compaction.modelProvider
            // / .modelName by ChatCompactionOrchestrator).
            "compactionModelProvider",
            "compactionModelName"
    );

    private final UserChatDefaultsRepository repository;

    /**
     * @return the stored config for (user, org), or an empty map when none exists.
     */
    public Map<String, Object> get(String userId, String organizationId) {
        return repository.findByUserIdAndOrganizationId(userId, organizationId)
                .map(UserChatDefaults::getConfig)
                .orElseGet(HashMap::new);
    }

    /**
     * Upsert the config for (user, org), keeping only whitelisted keys.
     *
     * @return the persisted (sanitized) config.
     */
    @Transactional
    public Map<String, Object> save(String userId, String organizationId, Map<String, Object> incoming) {
        Map<String, Object> sanitized = sanitize(incoming);
        UserChatDefaults entity = repository.findByUserIdAndOrganizationId(userId, organizationId)
                .orElseGet(() -> new UserChatDefaults(userId, organizationId, new HashMap<>()));
        entity.setConfig(sanitized);
        return repository.save(entity).getConfig();
    }

    /**
     * Seed the {@code chat_config} for a brand-new GENERAL (non-agent) conversation created
     * outside the message composer, e.g. a workflow-assistant conversation
     * ({@code createWorkflowConversation}). Uses the caller's explicit config when it carries
     * anything, otherwise the user's persisted per-(user, workspace) defaults (V312).
     *
     * <p>The composer already seeds its own conversations client-side (it merges these same
     * defaults into the create body), so a conversation created server-side is the only place
     * that would otherwise start with {@code chat_config = null} and inherit nothing. Agent
     * conversations are intentionally excluded: their config comes from the AgentEntity, not
     * from the user's chat defaults.
     *
     * @return the config to persist, or {@code null} when neither an explicit config nor any
     *         stored default exists (so the column stays unset, unchanged behaviour).
     */
    public Map<String, Object> seedNewConversationConfig(String userId, String organizationId,
                                                         Map<String, Object> explicit) {
        if (explicit != null && !explicit.isEmpty()) {
            return explicit;
        }
        Map<String, Object> defaults = get(userId, organizationId);
        return defaults.isEmpty() ? null : new LinkedHashMap<>(defaults);
    }

    /** Keep only the known ChatConfig keys; drop everything else (and null values). */
    static Map<String, Object> sanitize(Map<String, Object> incoming) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (incoming == null) {
            return out;
        }
        for (Map.Entry<String, Object> entry : incoming.entrySet()) {
            if (entry.getValue() != null && ALLOWED_KEYS.contains(entry.getKey())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }
}
