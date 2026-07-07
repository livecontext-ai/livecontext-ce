package com.apimarketplace.orchestrator.services.approvalchannel;

import com.apimarketplace.orchestrator.services.approvalchannel.telegram.TelegramApprovalCallbackHandler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Thin facade the public webhook endpoints use to detect and divert delegated-
 * approval callbacks. A Telegram bot has exactly ONE webhook URL, usually already
 * pointed at a workflow trigger; approval button clicks therefore arrive on the
 * generic webhook path and must be recognized there (by the namespaced
 * {@code lcapr:} callback_data), handled, and NOT dispatched to the workflow
 * (dispatching would open a spurious epoch on the host workflow).
 *
 * v1 delegates to the Telegram handler only; a new channel with an inbound
 * callback adds its detection here.
 */
@Component
public class ApprovalCallbackInterceptor {

    private final TelegramApprovalCallbackHandler telegramHandler;

    public ApprovalCallbackInterceptor(TelegramApprovalCallbackHandler telegramHandler) {
        this.telegramHandler = telegramHandler;
    }

    /** True when the webhook payload is a delegated-approval callback to divert. */
    public boolean isApprovalCallback(Map<String, Object> payload) {
        return telegramHandler.isApprovalCallback(payload);
    }

    /**
     * Handle the callback off-thread (the webhook endpoint must 200 fast: Telegram
     * retries non-2xx aggressively). The handler swallows every failure.
     */
    @Async("approvalDelegationExecutor")
    public void handleAsync(Map<String, Object> payload) {
        telegramHandler.handle(payload);
    }
}
