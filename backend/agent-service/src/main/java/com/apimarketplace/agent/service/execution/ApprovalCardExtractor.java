package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.domain.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Recognises the three shapes a tool result uses to ask the user for something, and
 * normalises them into one description a caller can act on.
 *
 * <p>Two callers need this decision and they MUST agree, which is why it lives here
 * instead of being inlined twice: {@link ToolApprovalGate}'s call site decides whether to
 * park the call, and {@code ConversationRedisStreamingCallback} decides whether to paint
 * a card for a result that was NOT parked. If the two ever drifted, a parked call could
 * paint no card (the turn hangs with nothing to click) or an unparked one could paint two.
 *
 * <p>The shapes, unchanged from the behaviour that predates the gate:
 * <ol>
 *   <li>{@code serviceApprovalRequested} metadata with a non-empty {@code services} list,</li>
 *   <li>{@code toolAuthorizationRequired} metadata carrying a {@code rule},</li>
 *   <li>{@code approval_needed} JSON in the content (the catalog credential pre-flight),</li>
 *   <li>{@code userQuestionRequested} metadata carrying a {@code userQuestion} payload (the
 *       {@code ask_user} tool, when its park ran out of time and the question is still open).</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalCardExtractor {

    private final ObjectMapper objectMapper;

    /** What the user is being asked, plus the per-turn key used to dedup identical cards. */
    public record ApprovalCard(
            Kind kind,
            List<Map<String, Object>> services,
            String reason,
            boolean needsAttention,
            Map<String, Object> authorizationMetadata,
            String dedupKey) {

        public enum Kind {
            /** "Connect this service to continue" - a missing credential. */
            SERVICE,
            /** "Authorize this action" - a sensitive action gated by policy. */
            AUTHORIZATION,
            /**
             * "Pick one" - a question the agent put to the person ({@code ask_user}). The
             * card payload travels in {@code authorizationMetadata} under {@code userQuestion}.
             */
            USER_QUESTION
        }

        /** The question payload, for {@link Kind#USER_QUESTION}; {@code null} otherwise. */
        @SuppressWarnings("unchecked")
        public Map<String, Object> userQuestion() {
            return authorizationMetadata != null
                    && authorizationMetadata.get(USER_QUESTION_KEY) instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : null;
        }
    }

    /** Metadata flag set by the {@code ask_user} tool when its question is still open. */
    public static final String USER_QUESTION_REQUESTED_KEY = "userQuestionRequested";
    /** Metadata entry carrying the question payload ({@code toolCallId} + {@code questions}). */
    public static final String USER_QUESTION_KEY = "userQuestion";

    /**
     * @return the card this result asks for, or empty when it asks for nothing. A failed
     *         result never asks: the gates surface their request as a SUCCESSFUL result
     *         carrying {@code executed: false}, so a genuine execution failure must not be
     *         mistaken for a permission prompt.
     */
    @SuppressWarnings("unchecked")
    public Optional<ApprovalCard> extract(ToolResult result) {
        if (result == null || !result.success()) {
            return Optional.empty();
        }
        Map<String, Object> metadata = result.metadata();

        // 1) Service approval requested via request_credential metadata.
        if (metadata != null && Boolean.TRUE.equals(metadata.get("serviceApprovalRequested"))) {
            List<Map<String, Object>> services = metadata.get("services") instanceof List<?> l
                    ? (List<Map<String, Object>>) l : List.of();
            if (!services.isEmpty()) {
                List<Map<String, Object>> infos = toApprovalInfos(services);
                boolean needsAttention = Boolean.TRUE.equals(metadata.get("needsAttention"));
                return Optional.of(new ApprovalCard(
                        ApprovalCard.Kind.SERVICE, infos, (String) metadata.get("reason"), needsAttention,
                        null, "svc:" + (needsAttention ? "attention:" : "connect:") + serviceKey(infos)));
            }
        }

        // 2) Sensitive action gated by ToolAuthorizationPolicy (any tool).
        if (metadata != null && Boolean.TRUE.equals(metadata.get("toolAuthorizationRequired"))) {
            String rule = (String) metadata.get("rule");
            if (rule != null && !rule.isBlank()) {
                return Optional.of(new ApprovalCard(
                        ApprovalCard.Kind.AUTHORIZATION, null, null, false, metadata, "auth:" + rule));
            }
            return Optional.empty();
        }

        // 3) A question the agent put to the person and that nobody has answered yet.
        if (metadata != null && Boolean.TRUE.equals(metadata.get(USER_QUESTION_REQUESTED_KEY))
                && metadata.get(USER_QUESTION_KEY) instanceof Map<?, ?> question) {
            Object toolCallId = question.get("toolCallId");
            if (toolCallId != null && !String.valueOf(toolCallId).isBlank()) {
                return Optional.of(new ApprovalCard(
                        ApprovalCard.Kind.USER_QUESTION, null, null, false, metadata, "ask:" + toolCallId));
            }
            return Optional.empty();
        }

        // 4) Soft "approval_needed" JSON returned in the tool content (catalog credential miss).
        String content = result.content();
        if (content != null && content.contains("approval_needed")) {
            try {
                Map<String, Object> contentMap = objectMapper.readValue(content, Map.class);
                if ("approval_needed".equals(contentMap.get("status"))) {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("serviceType", contentMap.get("serviceType"));
                    info.put("serviceName", contentMap.get("serviceName"));
                    info.put("iconSlug", contentMap.get("iconSlug"));
                    info.put("toolName", contentMap.get("toolName"));
                    info.put("toolId", contentMap.get("toolId"));
                    info.put("description", contentMap.get("message"));
                    List<Map<String, Object>> infos = List.of(info);
                    return Optional.of(new ApprovalCard(
                            ApprovalCard.Kind.SERVICE, infos, (String) contentMap.get("message"), false,
                            null, "svc:connect:" + serviceKey(infos)));
                }
            } catch (Exception e) {
                log.debug("Tool result content not JSON for approval check: {}", e.getMessage());
            }
        }

        return Optional.empty();
    }

    /** Convert service metadata maps into the approval-info shape the card consumes. */
    private static List<Map<String, Object>> toApprovalInfos(List<Map<String, Object>> services) {
        List<Map<String, Object>> infos = new ArrayList<>();
        for (Map<String, Object> svc : services) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("serviceType", svc.get("serviceType"));
            info.put("serviceName", svc.get("serviceName"));
            info.put("iconSlug", svc.get("iconSlug"));
            info.put("toolName", svc.get("toolName"));
            info.put("toolId", svc.get("toolId"));
            info.put("description", svc.get("description"));
            // The scope gap, when the refusal carried one. Copied only if present, so an
            // ordinary connect request is byte-identical to what it was.
            //
            // This whitelist is why the fields have to be named here at all: a key the
            // producer sets and this method does not copy is dropped silently, somewhere
            // between the tool result and the wire, and the card then renders "connect
            // this" over an account that is already connected. Anything a service entry
            // must carry to the browser belongs in this list.
            copyIfPresent(svc, info, "requiredScopes");
            copyIfPresent(svc, info, "grantedScopes");
            copyIfPresent(svc, info, "missingScopes");
            copyIfPresent(svc, info, "credentialType");
            infos.add(info);
        }
        return infos;
    }

    /** Carry a key over only when the producer set it, so absent stays absent rather than null. */
    private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String key) {
        Object value = from.get(key);
        if (value != null) {
            to.put(key, value);
        }
    }

    /** Stable signature for a set of services (sorted serviceTypes). */
    private static String serviceKey(List<Map<String, Object>> services) {
        return services.stream()
                .map(s -> String.valueOf(s.get("serviceType")))
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
    }
}
