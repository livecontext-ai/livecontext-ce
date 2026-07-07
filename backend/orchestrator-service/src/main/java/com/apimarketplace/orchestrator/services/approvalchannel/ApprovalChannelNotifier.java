package com.apimarketplace.orchestrator.services.approvalchannel;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.ApprovalChannelDeliveryEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;

/**
 * One implementation per external approval channel (v1: Telegram). Discovered by
 * {@link ApprovalChannelNotifierRegistry} via Spring's {@code List} injection;
 * adding a channel = adding one bean with a new {@link #channelId()}.
 *
 * <p>Contract: every method is best-effort and MUST NOT throw. A channel failure
 * (API down, revoked bot token, deleted chat) is recorded on the delivery row /
 * logged / counted, never propagated: the approval stays resolvable in-app and
 * the in-app bell notification is the always-on fallback surface.
 */
public interface ApprovalChannelNotifier {

    /** Stable channel id matched against the delegation block's {@code channel} (e.g. "telegram"). */
    String channelId();

    /**
     * Push the pending approval to the channel (v1: send the Telegram message with
     * inline approve/reject buttons). Called AFTER_COMMIT of the signal registration,
     * on the delegation executor. Idempotent across event replays and replicas: the
     * delivery insert is unique-guarded on (signal_wait_id, channel) and the send
     * fires only when this call actually inserted the row.
     *
     * @param workflowName display name of the run's workflow, pre-resolved by the
     *                     emitter (the run entity is DETACHED on the async listener
     *                     thread, so its lazy workflow association must not be
     *                     navigated here); nullable, used for fallback message text
     */
    void notifyPending(SignalWaitEntity signal, ApprovalDelegationConfig config,
                       WorkflowRunEntity run, String workflowName);

    /**
     * Reflect a resolved approval on the channel message (append the verdict line,
     * strip the buttons). Single edit path for ALL resolution origins: channel click,
     * in-app, MCP resolve_approval, timeout.
     */
    void onResolved(ApprovalChannelDeliveryEntity delivery, SignalResolution resolution, String resolvedBy);

    /** Reflect a cancelled signal on the channel message (strip the buttons). */
    void onCancelled(ApprovalChannelDeliveryEntity delivery);
}
