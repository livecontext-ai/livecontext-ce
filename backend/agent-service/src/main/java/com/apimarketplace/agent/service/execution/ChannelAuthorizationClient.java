package com.apimarketplace.agent.service.execution;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.apimarketplace.agent.tools.ask.UserQuestion;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Asks the workspace's linked chat for a permission an agent needs, when nobody is
 * watching the run that needs it.
 *
 * <p>The address book and the delivery live in orchestrator-service, next to the
 * channel machinery that already talks to Telegram. This is the one hop across, and
 * it is deliberately thin: it decides nothing, it only reports what happened so the
 * caller can tell the agent the truth.
 *
 * <p><b>Never throws.</b> A transport failure here must read as "nobody could be
 * asked", never as "the tool failed": the two lead the agent to completely
 * different behaviour, and only one of them is true.
 */
@Slf4j
@Component
public class ChannelAuthorizationClient {

    /** What the delivery attempt achieved, as the caller needs to describe it. */
    public record Delivery(Status status, String channel, String chatLabel, String requestedAt) {

        public enum Status { SENT, ALREADY_PENDING, NO_CHANNEL, FAILED }

        public static Delivery none() {
            return new Delivery(Status.NO_CHANNEL, null, null, null);
        }

        /** True when a live question is in front of the person, new or from an earlier run. */
        public boolean isWaitingOnSomeone() {
            return status == Status.SENT || status == Status.ALREADY_PENDING;
        }

        public boolean isAlreadyPending() {
            return status == Status.ALREADY_PENDING;
        }
    }

    private final RestTemplate restTemplate;
    private final String orchestratorUrl;

    public ChannelAuthorizationClient(
            RestTemplate restTemplate,
            @Value("${services.orchestrator-url:http://localhost:8099}") String orchestratorUrl) {
        this.restTemplate = restTemplate;
        this.orchestratorUrl = orchestratorUrl;
    }

    @SuppressWarnings("unchecked")
    public Delivery request(String tenantId, String organizationId, String conversationId, String gateKey,
                            String rule, String agentId, String summary, String fingerprint) {
        if (organizationId == null || organizationId.isBlank() || conversationId == null
                || conversationId.isBlank() || gateKey == null || gateKey.isBlank()) {
            // Nothing to address the request to. Not a failure: an execution with no
            // workspace scope simply has no channel to reach.
            return Delivery.none();
        }
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", tenantId);
        body.put("organizationId", organizationId);
        body.put("conversationId", conversationId);
        body.put("gateKey", gateKey);
        body.put("rule", rule);
        body.put("agentId", agentId);
        body.put("summary", summary);
        // What identifies the ask, kept separate from the display summary: the summary
        // is frequently absent, and an identity that collapses to the rule would refuse
        // two genuinely different asks as one.
        body.put("fingerprint", fingerprint);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (tenantId != null) {
                headers.set("X-User-ID", tenantId);
            }
            ResponseEntity<Map> response = restTemplate.exchange(
                    orchestratorUrl + "/api/internal/chat-channels/authorization-request",
                    HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
            Map<String, Object> payload = response.getBody();
            if (payload == null) {
                return new Delivery(Delivery.Status.FAILED, null, null, null);
            }
            return new Delivery(
                    parseStatus(payload.get("status")),
                    asString(payload.get("channel")),
                    asString(payload.get("chatLabel")),
                    asString(payload.get("requestedAt")));
        } catch (Exception ex) {
            log.warn("Could not ask the workspace's chat channel for authorization: {}", ex.getMessage());
            return new Delivery(Delivery.Status.FAILED, null, null, null);
        }
    }

    /**
     * Put an agent's questions to the person in the workspace's chat.
     *
     * <p>Same contract as the authorization request above, deliberately: never throws, always
     * reports a status, and a transport failure reads as "nobody could be asked" rather than
     * "the tool failed". The two lead an agent to completely different behaviour and only one
     * of them will have happened.
     *
     * <p>The questions travel as the same wire form the card uses ({@code UserQuestion.toMap}),
     * so orchestrator stores what the person was shown without a second shape to keep in step.
     */
    @SuppressWarnings("unchecked")
    public Delivery askQuestions(String tenantId, String organizationId, String conversationId,
                                 String toolCallId, String gateKey, String agentId,
                                 List<UserQuestion> questions) {
        if (organizationId == null || organizationId.isBlank() || conversationId == null
                || conversationId.isBlank() || toolCallId == null || toolCallId.isBlank()
                || gateKey == null || gateKey.isBlank() || questions == null || questions.isEmpty()) {
            // Nothing to address it to, or nothing to ask. Not a failure: an execution with no
            // workspace scope simply has no channel to reach.
            return Delivery.none();
        }
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", tenantId);
        body.put("organizationId", organizationId);
        body.put("conversationId", conversationId);
        body.put("toolCallId", toolCallId);
        body.put("gateKey", gateKey);
        body.put("agentId", agentId);
        body.put("questions", questions.stream().map(UserQuestion::toMap).toList());
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (tenantId != null) {
                headers.set("X-User-ID", tenantId);
            }
            ResponseEntity<Map> response = restTemplate.exchange(
                    orchestratorUrl + "/api/internal/chat-channels/question-request",
                    HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
            Map<String, Object> payload = response.getBody();
            if (payload == null) {
                return new Delivery(Delivery.Status.FAILED, null, null, null);
            }
            return new Delivery(
                    parseStatus(payload.get("status")),
                    asString(payload.get("channel")),
                    asString(payload.get("chatLabel")),
                    asString(payload.get("requestedAt")));
        } catch (Exception ex) {
            log.warn("Could not put the agent's question to the workspace's chat channel: {}",
                    ex.getMessage());
            return new Delivery(Delivery.Status.FAILED, null, null, null);
        }
    }

    private static Delivery.Status parseStatus(Object raw) {
        if (raw == null) {
            return Delivery.Status.FAILED;
        }
        try {
            return Delivery.Status.valueOf(String.valueOf(raw).trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Delivery.Status.FAILED;
        }
    }

    private static String asString(Object value) {
        return value != null ? String.valueOf(value) : null;
    }
}
