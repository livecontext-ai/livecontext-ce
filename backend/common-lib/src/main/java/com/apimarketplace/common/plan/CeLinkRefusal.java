package com.apimarketplace.common.plan;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The ONE place the refusal bodies of a CE-link-gated cloud endpoint are built, so the
 * register / heartbeat endpoints (auth-service) and every relay (agent, catalog,
 * orchestrator) answer a linked self-hosted install with byte-identical errors.
 *
 * <ul>
 *   <li>Not linked: {@code 403 {"error":"CE_LINK_NOT_ACTIVE"}} (unchanged wire shape).</li>
 *   <li>Linked but the account is not on a paid plan:
 *       {@code 403 {"error":"CLOUD_LINK_PLAN_REQUIRED","planCode":"FREE","message":"..."}}.
 *       The {@code message} is there for installs already in the field that do not know
 *       the code: they surface the cloud's error text as is.</li>
 * </ul>
 */
public final class CeLinkRefusal {

    /** Error code: the caller owns no ACTIVE link to the install. */
    public static final String NOT_LINKED_ERROR = "CE_LINK_NOT_ACTIVE";

    /** Error code: the link is suspended because the governing plan is not paid. */
    public static final String PLAN_REQUIRED_ERROR = "CLOUD_LINK_PLAN_REQUIRED";

    /** Human-readable explanation sent with {@link #PLAN_REQUIRED_ERROR}. */
    public static final String PLAN_REQUIRED_MESSAGE =
            "Linking a self-hosted install to LiveContext Cloud requires a paid plan. "
                    + "Choose a plan at https://livecontext.ai/app/settings/pricing "
                    + "and your install reconnects automatically.";

    private CeLinkRefusal() {
    }

    /**
     * The error code to refuse with, or {@code null} when access is {@link CeLinkAccess#ACTIVE}.
     * For a streaming endpoint that reports the refusal as an event rather than a body.
     */
    public static String errorCode(CeLinkAccessResult result) {
        if (result == null) {
            return NOT_LINKED_ERROR;
        }
        return switch (result.access()) {
            case ACTIVE -> null;
            case NOT_LINKED -> NOT_LINKED_ERROR;
            case PLAN_REQUIRED -> PLAN_REQUIRED_ERROR;
        };
    }

    /** The {@code CLOUD_LINK_PLAN_REQUIRED} body. A null or blank plan code reads as FREE. */
    public static Map<String, Object> planRequiredBody(String planCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", PLAN_REQUIRED_ERROR);
        body.put("planCode", planCode == null || planCode.isBlank() ? PlanTier.FREE : planCode);
        body.put("message", PLAN_REQUIRED_MESSAGE);
        return body;
    }

    /** The {@code CE_LINK_NOT_ACTIVE} body. */
    public static Map<String, Object> notLinkedBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", NOT_LINKED_ERROR);
        return body;
    }

    /**
     * The 403 to send for {@code result}, or {@code null} when the call may proceed.
     * A null result is treated as not linked (fail closed).
     */
    public static ResponseEntity<Map<String, Object>> response(CeLinkAccessResult result) {
        if (result != null && result.isActive()) {
            return null;
        }
        Map<String, Object> body = result != null && result.access() == CeLinkAccess.PLAN_REQUIRED
                ? planRequiredBody(result.planCode())
                : notLinkedBody();
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}
