package com.apimarketplace.orchestrator.services.failure;

import com.apimarketplace.common.credit.ChatCreditRefusal;
import com.apimarketplace.orchestrator.services.credit.CreditExhaustion;
import com.apimarketplace.orchestrator.services.plan.NodePlanGate;

/**
 * Tells a failure the TENANT can fix from one only the PLATFORM can fix.
 *
 * <p>The distinction is a logging one, and it is not cosmetic. A workspace that has run out
 * of credits, or a node pointed at a credential nobody configured, is the product working as
 * designed: the run is refused, the reason is already on the node, and the person who can act
 * is the customer. Logging that at ERROR puts a customer's empty wallet in the same bucket as
 * a dependency being down, which has two costs: the dashboards show incidents that are not
 * incidents, and the real errors drown. Observed on 2026-09-16, a single workspace sitting at
 * 0.55 credits since the 13th produced eight ERROR lines in three minutes, because its webhook
 * caller kept retrying a run the credit gate kept refusing, correctly, every time.
 *
 * <p>So: user-actionable goes to WARN (still visible, still greppable, not an alert), and
 * everything else keeps ERROR. This class never decides whether a run FAILS - the gates and the
 * nodes already did that, and their behaviour is unchanged.
 *
 * <p><b>Matching is on the message</b>, because that is all a relaying caller has: a trigger
 * reports {@code TriggerExecutionResult.message()}, a node catch block reports
 * {@code e.getMessage()}. The two canonical codes ({@link CreditExhaustion#ERROR_CODE},
 * {@link NodePlanGate#ERROR_CODE}) are matched as tokens, so a caller that prefixes the text
 * ("V2 execution failed: ...") is still recognised.
 *
 * <p><b>The CHAT vocabulary ({@link ChatCreditRefusal}) lives here and NOT in
 * {@link CreditExhaustion}</b>, which is the obvious-looking home and the wrong one.
 * {@code CreditExhaustion.isCreditExhausted} has three consumers that are not logging:
 * {@code TriggerController}, {@code WebhookDispatchService} and
 * {@code DatasourceTriggerDispatchService} each turn a true answer into a 402 or an
 * "insufficient credits" response body. Widening it would change what those endpoints ANSWER,
 * not just how a line is logged. Every one of THIS class's call sites is a {@code warn} /
 * {@code error} branch and nothing else, so it is the only place where widening the vocabulary
 * cannot change behaviour.
 *
 * <p><b>Second known false positive, stated rather than hidden</b> (the first is the credential
 * one below). {@code CreditConsumptionClient.checkCredits} is deliberately FAIL-CLOSED: when
 * auth-service is unreachable and no valid cached verdict exists it returns {@code false}, and
 * the sync-chat endpoint then answers the SAME 402 with the SAME {@link ChatCreditRefusal#MESSAGE}
 * it uses for a genuinely empty wallet. So during an auth-service outage those refusals are
 * logged at WARN, not ERROR. Accepted, for the same reason as the credential case: the client
 * logs the real cause itself at its source ("no valid cache - blocking execution
 * (fail-closed)") - at WARN, which is the same level this classifier chooses, so the signal
 * exists but is not an alert either - and telling "refused" from "could not ask" means giving
 * that gate a third answer, which is a change to its contract rather than to a log level.
 *
 * <p><b>Third known false positive: a RESOLD generation paid on the platform's own key.</b>
 * {@link ChatCreditRefusal#isChatCreditRefusal} is a substring test, so it also matches a
 * catalog tool's refusal relayed up to {@code StepNode} and {@code FindNode}. When the tenant's
 * own key pays, that is the same customer condition and belongs at WARN. When the PLATFORM's
 * pooled credential pays (resold generation), an upstream "insufficient credits" is a platform
 * fault wearing the customer's wording, and it is now logged at WARN. Bounded rather than
 * closed: the match is case-sensitive, so a provider that lower-cases the phrase is unaffected,
 * and a pinned test keeps it that way. Closing it properly means the catalog refusal carrying
 * WHO paid, which is a change to that response's shape and not to a log level.
 */
public final class UserActionableFailure {

    private UserActionableFailure() {
    }

    /**
     * The sentence every "you have not configured this credential" refusal ends with, in all
     * five nodes that can produce one (database, email inbox, send email, sftp, ssh).
     *
     * <p>It is matched rather than an error code because those five messages predate any code
     * and are user-facing copy: re-wording them to carry a token would change what the customer
     * reads, for a benefit only the log level would see. The safety net is a test that pins each
     * of the five literals against this classifier AND reads the five nodes back, so re-wording
     * one fails the build instead of silently sending that node's refusals back to ERROR.
     *
     * <p><b>Known false positive, stated rather than hidden.</b> {@code CredentialClient
     * .getDefaultCredential} turns a transport failure into an empty {@code Optional}, so when
     * auth-service is DOWN a node reports "no credential configured" even though one is. That
     * message is a lie in that case and this classifier believes it, so the two nodes that THROW
     * this condition log WARN where they used to log ERROR. Accepted: the credential client logs
     * the real cause itself, and the alternative - telling "absent" from "unreachable" - is a
     * change to that client's contract, not to a log level. (The three sibling nodes return a
     * failure instead of throwing, so they never logged this condition at any level.)
     */
    public static final String CREDENTIAL_NOT_CONFIGURED_HINT =
        "credential and set it on this node before running.";

    /**
     * True when {@code message} describes something the tenant can resolve themselves: add
     * credits, upgrade the plan, configure the credential the node asks for.
     *
     * <p>Unknown shapes answer {@code false}, i.e. they keep ERROR. That default is deliberate:
     * mis-classifying a platform fault as routine hides it, while mis-classifying a customer
     * condition as an error only leaves the noise this class exists to remove.
     */
    public static boolean isUserActionable(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return CreditExhaustion.isCreditExhausted(message)
            || ChatCreditRefusal.isChatCreditRefusal(message)
            || message.contains(NodePlanGate.ERROR_CODE)
            || message.contains(CREDENTIAL_NOT_CONFIGURED_HINT);
    }
}
