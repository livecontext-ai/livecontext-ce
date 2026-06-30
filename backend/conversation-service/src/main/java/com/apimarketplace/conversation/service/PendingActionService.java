package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.repository.ConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service for managing pending actions on conversations.
 *
 * Pending actions represent interrupted tool calls waiting for user intervention,
 * such as credential configuration or user confirmation.
 *
 * Lifecycle:
 * 1. setPendingAction() - When tool call fails due to missing credentials
 * 2. findConversationsWaitingFor() - When credentials are configured
 * 3. getPendingAction() - To resume the action
 * 4. clearPendingAction() - After action is completed or expired
 */
@Service
public class PendingActionService {

    private static final Logger logger = LoggerFactory.getLogger(PendingActionService.class);

    private static final int DEFAULT_EXPIRY_HOURS = 1;

    private final ConversationRepository conversationRepository;

    public PendingActionService(ConversationRepository conversationRepository) {
        this.conversationRepository = conversationRepository;
    }

    /**
     * Set a pending action on a conversation.
     *
     * @param conversationId The conversation ID
     * @param toolCallId The ID of the tool call that failed
     * @param toolName The name of the tool
     * @param toolArguments The arguments for the tool call
     * @param waitingFor What we're waiting for (e.g., "credential:gmail")
     * @param originalRequest The original user request
     * @param contextSummary A summary of what was happening
     * @return true if successful
     */
    @Transactional
    public boolean setPendingAction(String conversationId,
                                    String toolCallId,
                                    String toolName,
                                    Map<String, Object> toolArguments,
                                    String waitingFor,
                                    String originalRequest,
                                    String contextSummary) {

        logger.info("Setting pending action on conversation {} - waiting for: {}", conversationId, waitingFor);

        Optional<Conversation> convOpt = conversationRepository.findById(conversationId);
        if (convOpt.isEmpty()) {
            logger.warn("Conversation not found: {}", conversationId);
            return false;
        }

        Conversation conversation = convOpt.get();

        Instant now = Instant.now();
        Instant expiresAt = now.plus(DEFAULT_EXPIRY_HOURS, ChronoUnit.HOURS);

        Map<String, Object> pendingAction = new HashMap<>();
        pendingAction.put("tool_call", Map.of(
            "id", toolCallId,
            "name", toolName,
            "arguments", toolArguments != null ? toolArguments : Map.of()
        ));
        pendingAction.put("waiting_for", waitingFor);
        pendingAction.put("original_request", originalRequest);
        pendingAction.put("context_summary", contextSummary);
        pendingAction.put("created_at", now.toString());
        pendingAction.put("expires_at", expiresAt.toString());

        conversation.setPendingAction(pendingAction);
        conversationRepository.save(conversation);

        logger.info("Pending action set on conversation {} - expires at: {}", conversationId, expiresAt);
        return true;
    }

    /**
     * Get the pending action for a conversation.
     *
     * @param conversationId The conversation ID
     * @return The pending action, or empty if none or expired
     */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> getPendingAction(String conversationId) {
        Optional<Conversation> convOpt = conversationRepository.findById(conversationId);
        if (convOpt.isEmpty()) {
            return Optional.empty();
        }

        Conversation conversation = convOpt.get();
        Map<String, Object> pendingAction = conversation.getPendingAction();

        if (pendingAction == null || pendingAction.isEmpty()) {
            return Optional.empty();
        }

        // Check expiry
        String expiresAtStr = (String) pendingAction.get("expires_at");
        if (expiresAtStr != null) {
            Instant expiresAt = Instant.parse(expiresAtStr);
            if (Instant.now().isAfter(expiresAt)) {
                logger.info("Pending action on conversation {} has expired", conversationId);
                return Optional.empty();
            }
        }

        return Optional.of(pendingAction);
    }

    /**
     * Find all conversations waiting for a specific action type.
     *
     * @param waitingFor The action type (e.g., "credential:gmail")
     * @return List of conversation IDs with matching pending actions
     */
    @Transactional(readOnly = true)
    public List<String> findConversationsWaitingFor(String waitingFor) {
        logger.info("Finding conversations waiting for: {}", waitingFor);

        return conversationRepository.findAll().stream()
            .filter(conv -> conv.hasPendingAction())
            .filter(conv -> waitingFor.equals(conv.getWaitingFor()))
            .filter(this::isNotExpired)
            .map(Conversation::getId)
            .toList();
    }

    /**
     * Find all conversations waiting for a credential type.
     *
     * @param credentialType The credential type (e.g., "gmail", "slack")
     * @return List of conversation IDs
     */
    public List<String> findConversationsWaitingForCredential(String credentialType) {
        return findConversationsWaitingFor("credential:" + credentialType);
    }

    /**
     * Clear the pending action on a conversation.
     *
     * @param conversationId The conversation ID
     */
    @Transactional
    public void clearPendingAction(String conversationId) {
        logger.info("Clearing pending action on conversation: {}", conversationId);

        Optional<Conversation> convOpt = conversationRepository.findById(conversationId);
        if (convOpt.isEmpty()) {
            return;
        }

        Conversation conversation = convOpt.get();
        conversation.clearPendingAction();
        conversationRepository.save(conversation);
    }

    /**
     * Check if a conversation's pending action is not expired.
     */
    private boolean isNotExpired(Conversation conversation) {
        Map<String, Object> pendingAction = conversation.getPendingAction();
        if (pendingAction == null) {
            return false;
        }

        String expiresAtStr = (String) pendingAction.get("expires_at");
        if (expiresAtStr == null) {
            return true; // No expiry = never expires
        }

        try {
            Instant expiresAt = Instant.parse(expiresAtStr);
            return Instant.now().isBefore(expiresAt);
        } catch (Exception e) {
            logger.warn("Failed to parse expires_at: {}", expiresAtStr);
            return false;
        }
    }

    /**
     * Extract the tool call from a pending action.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> extractToolCall(Map<String, Object> pendingAction) {
        if (pendingAction == null) {
            return null;
        }
        return (Map<String, Object>) pendingAction.get("tool_call");
    }

    /**
     * Extract the original request from a pending action.
     */
    public static String extractOriginalRequest(Map<String, Object> pendingAction) {
        if (pendingAction == null) {
            return null;
        }
        return (String) pendingAction.get("original_request");
    }

    /**
     * Set a pending service approval action on a conversation.
     * Used when the agent needs user approval to access external services.
     *
     * @param conversationId The conversation ID
     * @param services List of services requiring approval
     * @param reason Why the services are needed
     * @return true if successful
     */
    @Transactional
    public boolean setServiceApprovalPendingAction(String conversationId,
                                                   List<Map<String, Object>> services,
                                                   String reason,
                                                   boolean needsAttention) {
        logger.info("💾 [PENDING_ACTION] Setting service approval for conversation {} - {} services, needsAttention={}",
                   conversationId, services != null ? services.size() : 0, needsAttention);
        return setPendingActions(conversationId,
            List.of(buildServiceApprovalAction(services, reason, needsAttention)));
    }

    /**
     * Set a pending tool-authorization action on a conversation. Used when a
     * sensitive tool action (e.g. {@code application:acquire}) is gated and the
     * run paused awaiting the user's decision. The card is reconstructed on
     * reload from this pending action ({@code waiting_for = "tool_authorization"}).
     *
     * @param conversationId the conversation ID
     * @param rule           canonical rule key {@code "tool:action"}
     * @param toolName       the gated facade tool (e.g. {@code "application"})
     * @param action         the gated action (e.g. {@code "acquire"})
     * @param toolCallId     the LLM tool-call id that was gated (for correlation)
     * @param argsSummary    short human-readable summary of the call arguments
     * @param applicationId  publication id for application:acquire (so a reload can still open the
     *                       install modal); {@code null} for every other rule
     * @return true if successful
     */
    @Transactional
    public boolean setToolAuthorizationPendingAction(String conversationId,
                                                     String rule,
                                                     String toolName,
                                                     String action,
                                                     String toolCallId,
                                                     String argsSummary,
                                                     String applicationId) {
        return setPendingActions(conversationId,
            List.of(buildToolAuthorizationAction(rule, toolName, action, toolCallId, argsSummary, applicationId)));
    }

    /**
     * Replace the conversation's pending actions with {@code actions} (one per card).
     *
     * <p>The list is the source of truth for the chat's parallel approval/authorization
     * cards. The legacy single {@code pending_action} is kept in sync with the FIRST entry
     * so existing single-action readers ({@code getWaitingFor}, credential-config resume)
     * keep working. A {@code null}/empty list clears both. Called once per turn with the
     * full set of that turn's pending actions (replace semantics - no cross-turn
     * accumulation; the prior turn's actions are cleared when the user resolves or sends a
     * new message).
     */
    @Transactional
    public boolean setPendingActions(String conversationId, List<Map<String, Object>> actions) {
        Optional<Conversation> convOpt = conversationRepository.findById(conversationId);
        if (convOpt.isEmpty()) {
            logger.warn("Conversation not found: {}", conversationId);
            return false;
        }
        Conversation conversation = convOpt.get();
        List<Map<String, Object>> list = compactPendingActions((actions == null) ? List.of() : actions);
        conversation.setPendingActions(new ArrayList<>(list));
        conversation.setPendingAction(list.isEmpty() ? null : list.get(0));
        conversationRepository.save(conversation);
        logger.info("✅ [PENDING_ACTION] Saved {} pending action(s) for conversation {}",
                   list.size(), conversationId);
        return true;
    }

    /**
     * Merge {@code newActions} into the conversation's existing pending actions, deduped by
     * {@link #pendingActionKey(Map)}. Existing actions are preserved so a card raised in a
     * prior turn and not yet resolved survives a later turn that raises different cards. The
     * legacy single {@code pending_action} is re-synced to element[0]. No-op for an
     * empty/null {@code newActions}.
     *
     * @return true if the conversation was updated
     */
    @Transactional
    public boolean addPendingActions(String conversationId, List<Map<String, Object>> newActions) {
        if (newActions == null || newActions.isEmpty()) {
            return false;
        }
        Optional<Conversation> convOpt = conversationRepository.findById(conversationId);
        if (convOpt.isEmpty()) {
            logger.warn("Conversation not found: {}", conversationId);
            return false;
        }
        Conversation conversation = convOpt.get();

        // Seed from the existing list, or from the legacy single action for a pre-V309 row
        // that has a pending_action but an empty list (migration window).
        List<Map<String, Object>> existing = conversation.getPendingActions();
        if ((existing == null || existing.isEmpty()) && conversation.getPendingAction() != null) {
            existing = List.of(conversation.getPendingAction());
        }

        List<Map<String, Object>> combined = new ArrayList<>();
        if (existing != null) {
            combined.addAll(existing);
        }
        combined.addAll(newActions);
        List<Map<String, Object>> merged = compactPendingActions(combined);

        conversation.setPendingActions(merged);
        conversation.setPendingAction(merged.isEmpty() ? null : merged.get(0));
        conversationRepository.save(conversation);
        logger.info("✅ [PENDING_ACTION] Merged {} new pending action(s) into conversation {} - {} total",
                   newActions.size(), conversationId, merged.size());
        return true;
    }

    /**
     * Remove a single pending action identified by its {@link #pendingActionKey(Map)} from
     * the conversation, re-syncing the legacy single {@code pending_action}. Lets the user
     * approve/deny ONE card without wiping the others that are still pending. No-op when the
     * key is blank or matches nothing.
     *
     * @return true if an action was removed
     */
    @Transactional
    public boolean clearOnePendingAction(String conversationId, String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        Optional<Conversation> convOpt = conversationRepository.findById(conversationId);
        if (convOpt.isEmpty()) {
            return false;
        }
        Conversation conversation = convOpt.get();
        List<Map<String, Object>> current = conversation.getPendingActions();

        // Legacy row written before V309 carries only the single pending_action.
        if (current == null || current.isEmpty()) {
            Map<String, Object> single = conversation.getPendingAction();
            if (single != null && key.equals(pendingActionKey(single))) {
                conversation.clearPendingAction();
                conversationRepository.save(conversation);
                return true;
            }
            return false;
        }

        List<Map<String, Object>> remaining = current.stream()
            .filter(a -> !key.equals(pendingActionKey(a)))
            .collect(Collectors.toList());
        if (remaining.size() == current.size()) {
            return false; // nothing matched
        }
        conversation.setPendingActions(remaining);
        conversation.setPendingAction(remaining.isEmpty() ? null : remaining.get(0));
        conversationRepository.save(conversation);
        logger.info("🧹 [PENDING_ACTION] Cleared 1 pending action (key={}) for conversation {} - {} remaining",
                   key, conversationId, remaining.size());
        return true;
    }

    // ===== Builders + key (shared by the persist + clear-one paths) =====

    private static List<Map<String, Object>> compactPendingActions(List<Map<String, Object>> actions) {
        LinkedHashMap<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> action : actions) {
            if (action == null || action.isEmpty()) {
                continue;
            }
            String key = pendingActionKey(action);
            if (key == null || key.isBlank()) {
                continue;
            }
            Map<String, Object> existing = byKey.get(key);
            byKey.put(key, existing == null ? action : mergePendingAction(existing, action));
        }
        return new ArrayList<>(byKey.values());
    }

    private static Map<String, Object> mergePendingAction(Map<String, Object> existing,
                                                          Map<String, Object> incoming) {
        if ("service_approval".equals(existing.get("waiting_for"))
                && "service_approval".equals(incoming.get("waiting_for"))) {
            return mergeServiceApprovalAction(existing, incoming);
        }
        return existing;
    }

    private static Map<String, Object> mergeServiceApprovalAction(Map<String, Object> existing,
                                                                  Map<String, Object> incoming) {
        Map<String, Object> merged = new HashMap<>(existing);
        merged.put("services", mergeServices(existing.get("services"), incoming.get("services")));
        merged.put("reason", mergeReasons(existing.get("reason"), incoming.get("reason")));
        merged.put("needs_attention", truthy(existing.get("needs_attention")) || truthy(incoming.get("needs_attention")));
        Object expiresAt = laterInstantString(existing.get("expires_at"), incoming.get("expires_at"));
        if (expiresAt != null) {
            merged.put("expires_at", expiresAt);
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mergeServices(Object existingServices, Object incomingServices) {
        LinkedHashMap<String, Map<String, Object>> byService = new LinkedHashMap<>();
        addServiceMaps(byService, existingServices);
        addServiceMaps(byService, incomingServices);
        return new ArrayList<>(byService.values());
    }

    @SuppressWarnings("unchecked")
    private static void addServiceMaps(LinkedHashMap<String, Map<String, Object>> byService, Object servicesObj) {
        if (!(servicesObj instanceof List<?> services)) {
            return;
        }
        for (Object serviceObj : services) {
            if (!(serviceObj instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> service = (Map<String, Object>) raw;
            String key = serviceIdentity(service);
            if (key.isBlank()) {
                continue;
            }
            Map<String, Object> current = byService.get(key);
            Map<String, Object> merged = current == null ? new LinkedHashMap<>() : new LinkedHashMap<>(current);
            merged.putAll(service);
            byService.put(key, merged);
        }
    }

    private static String serviceIdentity(Map<String, Object> service) {
        Object value = service.get("serviceType");
        if (value == null) {
            value = service.get("iconSlug");
        }
        if (value == null) {
            value = service.get("serviceName");
        }
        return value == null ? "" : String.valueOf(value).trim().toLowerCase();
    }

    private static String mergeReasons(Object existingReason, Object incomingReason) {
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        addReason(reasons, existingReason);
        addReason(reasons, incomingReason);
        return String.join("\n", reasons);
    }

    private static void addReason(LinkedHashSet<String> reasons, Object reason) {
        if (reason == null) {
            return;
        }
        String value = String.valueOf(reason).trim();
        if (!value.isBlank()) {
            reasons.add(value);
        }
    }

    private static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static Object laterInstantString(Object left, Object right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        try {
            Instant leftInstant = Instant.parse(String.valueOf(left));
            Instant rightInstant = Instant.parse(String.valueOf(right));
            return rightInstant.isAfter(leftInstant) ? right : left;
        } catch (Exception ignored) {
            return right;
        }
    }

    /** Build a service-approval pending-action map ({@code waiting_for=service_approval}). */
    public static Map<String, Object> buildServiceApprovalAction(List<Map<String, Object>> services,
                                                                 String reason, boolean needsAttention) {
        Instant now = Instant.now();
        Map<String, Object> pa = new HashMap<>();
        pa.put("waiting_for", "service_approval");
        pa.put("services", services != null ? services : List.of());
        pa.put("reason", reason != null ? reason : "");
        pa.put("needs_attention", needsAttention);
        pa.put("created_at", now.toString());
        pa.put("expires_at", now.plus(DEFAULT_EXPIRY_HOURS, ChronoUnit.HOURS).toString());
        return pa;
    }

    /** Build a tool-authorization pending-action map ({@code waiting_for=tool_authorization}). */
    public static Map<String, Object> buildToolAuthorizationAction(String rule, String toolName, String action,
                                                                   String toolCallId, String argsSummary,
                                                                   String applicationId) {
        Instant now = Instant.now();
        Map<String, Object> pa = new HashMap<>();
        pa.put("waiting_for", "tool_authorization");
        pa.put("rule", rule);
        pa.put("tool_name", toolName);
        pa.put("action", action);
        pa.put("tool_call_id", toolCallId);
        pa.put("args_summary", argsSummary);
        if (applicationId != null) {
            pa.put("application_id", applicationId);
        }
        pa.put("created_at", now.toString());
        pa.put("expires_at", now.plus(DEFAULT_EXPIRY_HOURS, ChronoUnit.HOURS).toString());
        return pa;
    }

    /**
     * Stable dedup/clear key for a pending action:
     * <ul>
     *   <li>{@code tool_authorization} → {@code "auth:" + rule}</li>
     *   <li>{@code service_approval}   → {@code "svc:" + sorted serviceTypes}</li>
     *   <li>anything else              → its {@code waiting_for} value.</li>
     * </ul>
     * Mirrors the dedup key the agent-service streaming callback uses so the live and
     * persisted views stay aligned.
     */
    public static String pendingActionKey(Map<String, Object> action) {
        if (action == null) {
            return null;
        }
        String waitingFor = (String) action.get("waiting_for");
        if ("tool_authorization".equals(waitingFor)) {
            return "auth:" + action.get("rule");
        }
        if ("service_approval".equals(waitingFor)) {
            return truthy(action.get("needs_attention")) ? "svc:attention" : "svc:connect";
        }
        return waitingFor;
    }
}
