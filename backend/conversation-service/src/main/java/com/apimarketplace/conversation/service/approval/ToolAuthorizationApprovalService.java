package com.apimarketplace.conversation.service.approval;

import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Manages user authorization of sensitive tool actions for a conversation.
 *
 * <p>Sibling of {@code ServiceApprovalService} (credential approvals), kept
 * separate because the semantics differ: tool-action rules ({@code tool:action})
 * are gated by {@code ToolAuthorizationGuard}, not by credential existence.
 *
 * <p>Two buckets on {@code conversation.approved_tool_actions}:
 * <ul>
 *   <li><b>always</b> - "Toujours autoriser dans cette conversation". Persisted;
 *       skips the card on every later turn.</li>
 *   <li><b>once</b> - single-shot "Autoriser". Injected into the very next turn's
 *       credentials then cleared ({@link #resolveAndConsumeForTurn}) - the
 *       per-call default.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolAuthorizationApprovalService {

    static final String BUCKET_ALWAYS = "always";
    static final String BUCKET_ONCE = "once";

    private final ConversationRepository conversationRepository;

    /**
     * Authorize a rule. {@code remember=true} → persisted ("always"); otherwise a
     * single-shot grant ("once") consumed by the next turn.
     */
    @Transactional
    public boolean approve(String conversationId, String rule, boolean remember) {
        if (conversationId == null || rule == null || rule.isBlank()) {
            return false;
        }
        Optional<Conversation> convOpt = conversationRepository.findById(conversationId);
        if (convOpt.isEmpty()) {
            log.warn("Cannot authorize tool action: conversation not found: {}", conversationId);
            return false;
        }
        Conversation conversation = convOpt.get();

        Map<String, Object> store = copyStore(conversation.getApprovedToolActions());
        String bucket = remember ? BUCKET_ALWAYS : BUCKET_ONCE;
        Set<String> set = new LinkedHashSet<>(toStringList(store.get(bucket)));
        set.add(rule);
        store.put(bucket, new ArrayList<>(set));
        conversation.setApprovedToolActions(store);
        conversationRepository.save(conversation);

        log.info("Authorized tool action {} for conversation {} (remember={})", rule, conversationId, remember);
        return true;
    }

    /** Read-only union of always + once (no mutation). For inspection / tests. */
    @Transactional(readOnly = true)
    public Set<String> getActiveRules(String conversationId) {
        if (conversationId == null) {
            return Set.of();
        }
        return conversationRepository.findById(conversationId)
                .map(conv -> union(conv.getApprovedToolActions()))
                .orElse(Set.of());
    }

    /**
     * Resolve the rules to inject into the NEXT turn's credentials (union of
     * always + once) and consume the single-shot "once" grants (cleared after).
     *
     * <p>Semantics note: a "once" grant authorizes the rule for the whole resume
     * TURN (every matching call within that turn), not a single tool call - same
     * model as the sibling {@code __approvedServices__}. It does not survive into
     * later turns (cleared here), which is what makes the default per-call.
     *
     * <p>When the conversation has {@code chatConfig.autoAuthorizeTools = true}
     * (the per-conversation "ne plus demander" toggle / card checkbox), the wildcard
     * {@code "*"} is added so {@code RemoteToolExecutionService.isAlreadyAuthorized}
     * lets every sensitive rule through without a card for the rest of the conversation.
     */
    @Transactional
    public List<String> resolveAndConsumeForTurn(String conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        Optional<Conversation> convOpt = conversationRepository.findById(conversationId);
        if (convOpt.isEmpty()) {
            return List.of();
        }
        Conversation conversation = convOpt.get();
        Map<String, Object> store = conversation.getApprovedToolActions();
        List<String> always = toStringList(store.get(BUCKET_ALWAYS));
        List<String> once = toStringList(store.get(BUCKET_ONCE));

        Set<String> resolved = new LinkedHashSet<>(always);
        resolved.addAll(once);
        if (isAutoAuthorizeEnabled(conversation)) {
            resolved.add("*");
        }

        if (!once.isEmpty()) {
            // Consume the single-shot grants so they don't leak into later turns.
            Map<String, Object> updated = copyStore(store);
            updated.put(BUCKET_ONCE, new ArrayList<>());
            conversation.setApprovedToolActions(updated);
            conversationRepository.save(conversation);
        }
        return new ArrayList<>(resolved);
    }

    /** Revoke a previously-authorized rule from both buckets. */
    @Transactional
    public boolean revoke(String conversationId, String rule) {
        if (conversationId == null || rule == null) {
            return false;
        }
        Optional<Conversation> convOpt = conversationRepository.findById(conversationId);
        if (convOpt.isEmpty()) {
            return false;
        }
        Conversation conversation = convOpt.get();
        Map<String, Object> store = copyStore(conversation.getApprovedToolActions());
        boolean changed = false;
        for (String bucket : List.of(BUCKET_ALWAYS, BUCKET_ONCE)) {
            List<String> rules = new ArrayList<>(toStringList(store.get(bucket)));
            if (rules.remove(rule)) {
                store.put(bucket, rules);
                changed = true;
            }
        }
        if (changed) {
            conversation.setApprovedToolActions(store);
            conversationRepository.save(conversation);
        }
        return changed;
    }

    /**
     * The per-conversation blanket grant: {@code chatConfig.autoAuthorizeTools == true}.
     * Set from the composer Options toggle or the card's "ne plus demander dans cette
     * conversation" checkbox (both write {@code conversation.chatConfig}).
     */
    private static boolean isAutoAuthorizeEnabled(Conversation conversation) {
        Map<String, Object> chatConfig = conversation.getChatConfig();
        return chatConfig != null && Boolean.TRUE.equals(chatConfig.get("autoAuthorizeTools"));
    }

    private static Set<String> union(Map<String, Object> store) {
        if (store == null) {
            return Set.of();
        }
        Set<String> all = new LinkedHashSet<>(toStringList(store.get(BUCKET_ALWAYS)));
        all.addAll(toStringList(store.get(BUCKET_ONCE)));
        return all;
    }

    /** Fresh copy so reassigning the field guarantees Hibernate JSONB dirty detection. */
    private static Map<String, Object> copyStore(Map<String, Object> store) {
        return store == null ? new HashMap<>() : new HashMap<>(store);
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object value) {
        if (value instanceof Collection<?> col) {
            List<String> out = new ArrayList<>();
            for (Object o : col) {
                if (o instanceof String s) {
                    out.add(s);
                }
            }
            return out;
        }
        return List.of();
    }
}
