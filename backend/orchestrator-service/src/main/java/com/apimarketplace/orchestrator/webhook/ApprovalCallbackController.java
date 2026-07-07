package com.apimarketplace.orchestrator.webhook;

import com.apimarketplace.orchestrator.services.approvalchannel.ApprovalCallbackInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Dedicated public endpoint for delegated-approval callbacks from bots that are
 * NOT otherwise webhooked to a workflow trigger. The bot owner points Telegram's
 * setWebhook at {@code <base-url>/approval-callback/telegram} once per bot; the
 * same public URL works on cloud (gateway route approval-callback-public) and CE
 * (ServicePrefixRewriteFilter rewrite + MonolithSecurityFilter allow-list entry).
 *
 * Route (both editions): /approval-callback/** -> /api/internal/approval-callback/**
 *
 * Bots already webhooked to a workflow trigger do NOT need this endpoint: the
 * generic webhook path diverts approval callbacks itself (see WebhookController).
 *
 * Response discipline: ALWAYS 2xx for well-formed updates, including non-callback
 * update types (messages, edits, ...) which are simply ignored here - Telegram
 * retries non-2xx aggressively and would flood the endpoint. The only non-2xx is
 * the optional shared-secret mismatch (X-Telegram-Bot-Api-Secret-Token, set via
 * setWebhook's secret_token and the orchestrator.approval.telegram.webhook-secret
 * property; blank property = check disabled).
 */
@RestController
@RequestMapping("/api/internal/approval-callback")
public class ApprovalCallbackController {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalCallbackController.class);

    private final ApprovalCallbackInterceptor interceptor;
    private final String webhookSecret;

    public ApprovalCallbackController(
            ApprovalCallbackInterceptor interceptor,
            @Value("${orchestrator.approval.telegram.webhook-secret:}") String webhookSecret) {
        this.interceptor = interceptor;
        this.webhookSecret = webhookSecret;
    }

    @PostMapping("/telegram")
    public ResponseEntity<Map<String, String>> handleTelegram(
            @RequestBody(required = false) Map<String, Object> update,
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secretToken) {

        if (webhookSecret != null && !webhookSecret.isBlank() && !webhookSecret.equals(secretToken)) {
            logger.warn("[approval-callback] rejected Telegram update with bad secret token");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("status", "forbidden"));
        }

        if (update != null && interceptor.isApprovalCallback(update)) {
            interceptor.handleAsync(update);
            return ResponseEntity.ok(Map.of("status", "approval_callback_handled"));
        }

        return ResponseEntity.ok(Map.of("status", "ignored"));
    }
}
