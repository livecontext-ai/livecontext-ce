package com.apimarketplace.conversation.exception;

import com.apimarketplace.conversation.service.ai.BridgeAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleConversationNotFoundReturns404() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleConversationNotFound(new ConversationNotFoundException("conv"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("CONVERSATION_NOT_FOUND");
    }

    @Test
    void handleConversationInactiveReturns409() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleConversationInactive(new ConversationInactiveException("conv"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("CONVERSATION_INACTIVE");
    }

    @Test
    void handleInvalidMessageReturns400() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleInvalidMessage(new InvalidMessageException("bad"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("INVALID_MESSAGE");
    }

    @Test
    void handleBridgeAccessDeniedQuotaReturns429WithReasonAndRemaining() {
        // Rationale: when a user exhausts their per-day CLI-bridge quota, the
        // frontend needs a distinct 429 so it can show "try again tomorrow"
        // rather than the generic "access denied" that a 403 triggers.
        BridgeAccessDeniedException ex = new BridgeAccessDeniedException(
                "claude-code", "daily_quota_exhausted", 0);

        ResponseEntity<Map<String, Object>> response = handler.handleBridgeAccessDenied(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("code")).isEqualTo("BRIDGE_ACCESS_DENIED");
        assertThat(body.get("provider")).isEqualTo("claude-code");
        assertThat(body.get("reason")).isEqualTo("daily_quota_exhausted");
        assertThat(body.get("remainingRequestsToday")).isEqualTo(0);
    }

    @Test
    void handleBridgeAccessDeniedPolicyReturns403WithoutRemaining() {
        // Rationale: policy denials (disabled / admin-only / allowlist) map to
        // 403 since there's no retry-after semantic - the user must ask the
        // admin. The remainingRequestsToday field is omitted for deny reasons
        // that aren't quota-driven (BridgeAccessEnforcer returns null there).
        BridgeAccessDeniedException ex = new BridgeAccessDeniedException(
                "codex", "not_in_allowlist", null);

        ResponseEntity<Map<String, Object>> response = handler.handleBridgeAccessDenied(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("code")).isEqualTo("BRIDGE_ACCESS_DENIED");
        assertThat(body.get("provider")).isEqualTo("codex");
        assertThat(body.get("reason")).isEqualTo("not_in_allowlist");
        assertThat(body).doesNotContainKey("remainingRequestsToday");
    }

    @Test
    void handleBridgeAccessDeniedGuardUnavailableReturns403() {
        // Rationale: a transport failure surfaces as guard_unavailable. Mapping
        // it to 403 (not 500) keeps the chat path fail-CLOSED from the client's
        // perspective - they see "access denied" instead of "server error",
        // which correctly discourages retrying until the admin fixes the wiring.
        BridgeAccessDeniedException ex = new BridgeAccessDeniedException(
                "gemini-cli", "guard_unavailable", null);

        ResponseEntity<Map<String, Object>> response = handler.handleBridgeAccessDenied(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("reason")).isEqualTo("guard_unavailable");
    }
}
