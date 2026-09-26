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
 *   <li>Linked but the account is not on a paid plan, on a paid relay (LLM, web search,
 *       catalog): {@code 403 {"error":"CLOUD_LINK_PLAN_REQUIRED","planCode":"FREE","message":"..."}}.
 *       The {@code message} is there for installs already in the field that do not know
 *       the code: they surface the cloud's error text as is.</li>
 *   <li>Register by an account that has not finished the cloud onboarding (email code
 *       included): {@code 403 {"error":"CLOUD_LINK_ONBOARDING_REQUIRED","message":"..."}}.</li>
 * </ul>
 */
public final class CeLinkRefusal {

    /** Error code: the caller owns no ACTIVE link to the install. */
    public static final String NOT_LINKED_ERROR = "CE_LINK_NOT_ACTIVE";

    /** Error code: linked, but a paid relay needs a paid plan. */
    public static final String PLAN_REQUIRED_ERROR = "CLOUD_LINK_PLAN_REQUIRED";

    /** Human-readable explanation sent with {@link #PLAN_REQUIRED_ERROR}. */
    public static final String PLAN_REQUIRED_MESSAGE =
            "Cloud models, web search and cloud integrations from a self-hosted install require "
                    + "a paid LiveContext Cloud plan. Your install stays linked: choose a plan at "
                    + "https://livecontext.ai/app/settings/pricing and they work right away.";

    /** Error code: the account has not completed the cloud onboarding, so it may not link yet. */
    public static final String ONBOARDING_REQUIRED_ERROR = "CLOUD_LINK_ONBOARDING_REQUIRED";

    /** Human-readable explanation sent with {@link #ONBOARDING_REQUIRED_ERROR}. */
    public static final String ONBOARDING_REQUIRED_MESSAGE =
            "Finish setting up your LiveContext Cloud account (email verification and profile) at "
                    + "https://livecontext.ai/onboarding, then this install links automatically.";

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

    /** The {@code CLOUD_LINK_ONBOARDING_REQUIRED} body. */
    public static Map<String, Object> onboardingRequiredBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ONBOARDING_REQUIRED_ERROR);
        body.put("message", ONBOARDING_REQUIRED_MESSAGE);
        return body;
    }

    /** The {@code CE_LINK_NOT_ACTIVE} body. */
    public static Map<String, Object> notLinkedBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", NOT_LINKED_ERROR);
        return body;
    }

    /**
     * The 403 for an endpoint that only needs a LINK (model and skill bundles: they spend no
     * cloud money), or {@code null} when the call may proceed. A linked account on any plan
     * passes; a null result is treated as not linked (fail closed).
     */
    public static ResponseEntity<Map<String, Object>> linkOnlyResponse(CeLinkAccessResult result) {
        if (result != null && result.isLinked()) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(notLinkedBody());
    }

    /**
     * The 403 for a PAID relay (LLM, web search, catalog) for {@code result}, or {@code null}
     * when the call may proceed. A null result is treated as not linked (fail closed).
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
