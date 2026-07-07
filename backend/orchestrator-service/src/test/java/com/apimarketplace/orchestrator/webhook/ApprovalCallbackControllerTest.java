package com.apimarketplace.orchestrator.webhook;

import com.apimarketplace.orchestrator.services.approvalchannel.ApprovalCallbackInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ApprovalCallbackController}: the dedicated public
 * Telegram webhook endpoint for bots not otherwise webhooked to a workflow.
 * Pins the always-2xx discipline (Telegram retries non-2xx aggressively), the
 * optional shared-secret check, and the async handoff of approval callbacks.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApprovalCallbackController")
class ApprovalCallbackControllerTest {

    @Mock private ApprovalCallbackInterceptor interceptor;

    private ApprovalCallbackController controller(String secret) {
        return new ApprovalCallbackController(interceptor, secret);
    }

    private Map<String, Object> approvalUpdate() {
        Map<String, Object> update = new HashMap<>();
        update.put("update_id", 1001);
        update.put("callback_query", Map.of(
                "id", "cbq-1",
                "data", "lcapr:AbCdEfGhIjKlMnOpQrStUv:a",
                "from", Map.of("id", 777)));
        return update;
    }

    @Nested
    @DisplayName("without a configured secret")
    class WithoutSecret {

        @Test
        @DisplayName("approval callback: 200 approval_callback_handled + async handoff")
        void approvalCallbackHandled() {
            Map<String, Object> update = approvalUpdate();
            when(interceptor.isApprovalCallback(update)).thenReturn(true);

            ResponseEntity<Map<String, String>> response =
                    controller("").handleTelegram(update, null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("status", "approval_callback_handled");
            verify(interceptor).handleAsync(update);
        }

        @Test
        @DisplayName("non-callback update (plain message): 200 ignored, nothing handled")
        void nonCallbackUpdateIgnored() {
            Map<String, Object> update = new HashMap<>();
            update.put("update_id", 1002);
            update.put("message", Map.of("text", "hello"));
            when(interceptor.isApprovalCallback(update)).thenReturn(false);

            ResponseEntity<Map<String, String>> response =
                    controller("").handleTelegram(update, null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("status", "ignored");
            verify(interceptor, never()).handleAsync(any());
        }

        @Test
        @DisplayName("null body: 200 ignored (never a non-2xx for a well-formed empty update)")
        void nullBodyIgnored() {
            ResponseEntity<Map<String, String>> response =
                    controller("").handleTelegram(null, null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("status", "ignored");
            verifyNoInteractions(interceptor);
        }

        @Test
        @DisplayName("blank secret property disables the check: a stray header is accepted")
        void blankSecretDisablesCheck() {
            Map<String, Object> update = approvalUpdate();
            when(interceptor.isApprovalCallback(update)).thenReturn(true);

            ResponseEntity<Map<String, String>> response =
                    controller("").handleTelegram(update, "whatever-telegram-sends");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("status", "approval_callback_handled");
        }
    }

    @Nested
    @DisplayName("with a configured secret")
    class WithSecret {

        @Test
        @DisplayName("wrong X-Telegram-Bot-Api-Secret-Token: 403 and nothing handled")
        void wrongSecretForbidden() {
            ResponseEntity<Map<String, String>> response =
                    controller("s3cret").handleTelegram(approvalUpdate(), "wrong");

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            assertThat(response.getBody()).containsEntry("status", "forbidden");
            verifyNoInteractions(interceptor);
        }

        @Test
        @DisplayName("missing secret header: 403 and nothing handled")
        void missingSecretForbidden() {
            ResponseEntity<Map<String, String>> response =
                    controller("s3cret").handleTelegram(approvalUpdate(), null);

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            verifyNoInteractions(interceptor);
        }

        @Test
        @DisplayName("matching secret: the approval callback is handled normally")
        void matchingSecretHandled() {
            Map<String, Object> update = approvalUpdate();
            when(interceptor.isApprovalCallback(update)).thenReturn(true);

            ResponseEntity<Map<String, String>> response =
                    controller("s3cret").handleTelegram(update, "s3cret");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("status", "approval_callback_handled");
            verify(interceptor).handleAsync(update);
        }
    }
}
