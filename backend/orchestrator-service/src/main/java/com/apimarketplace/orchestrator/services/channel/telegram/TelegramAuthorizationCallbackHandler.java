package com.apimarketplace.orchestrator.services.channel.telegram;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.services.channel.AgentAuthorizationChannelService;
import com.apimarketplace.orchestrator.services.channel.AgentAuthorizationChannelService.AnswerOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles a Telegram button press on an AGENT authorization request
 * ({@code lcaut:<token>:a|r}).
 *
 * <p>Sibling of {@code TelegramApprovalCallbackHandler}, which serves workflow
 * approvals ({@code lcapr:}). Two prefixes rather than one handler with a branch,
 * because the two answer completely different things: that one resolves a
 * persisted workflow signal, this one releases a parked tool call in a
 * conversation. A bot's single webhook carries both, so the prefix is what tells
 * them apart.
 *
 * <p>Everything is swallowed. A non-2xx answer makes Telegram retry aggressively,
 * so an error here would turn one failed decision into a flood.
 *
 * <p><b>The allow-list is a guard against a mistaken tap, not authentication.</b>
 * The user id it checks comes from an unauthenticated payload. The real boundary
 * is membership of the chat plus an unguessable token.
 */
@Component
public class TelegramAuthorizationCallbackHandler {

    private static final Logger logger = LoggerFactory.getLogger(TelegramAuthorizationCallbackHandler.class);

    private static final Pattern CALLBACK_DATA = Pattern.compile(
            "^" + AgentAuthorizationChannelService.CALLBACK_PREFIX + ":([A-Za-z0-9_-]{16,48}):(a|r)$");

    private final AgentAuthorizationChannelService service;
    private final MeterRegistry meterRegistry;

    public TelegramAuthorizationCallbackHandler(AgentAuthorizationChannelService service,
                                                MeterRegistry meterRegistry) {
        this.service = service;
        this.meterRegistry = meterRegistry;
    }

    /** True when this payload is an agent-authorization button press. */
    public boolean isAuthorizationCallback(Map<String, Object> webhookPayload) {
        Map<String, Object> callbackQuery = callbackQueryOf(webhookPayload);
        if (callbackQuery == null) {
            return false;
        }
        Object data = callbackQuery.get("data");
        return data instanceof String s && CALLBACK_DATA.matcher(s).matches();
    }

    /** Handle the press end to end. Always swallows, always returns quickly. */
    public void handle(Map<String, Object> webhookPayload) {
        try {
            Map<String, Object> callbackQuery = callbackQueryOf(webhookPayload);
            if (callbackQuery == null) {
                return;
            }
            Matcher matcher = CALLBACK_DATA.matcher(String.valueOf(callbackQuery.get("data")));
            if (!matcher.matches()) {
                return;
            }
            String token = matcher.group(1);
            boolean approve = "a".equals(matcher.group(2));
            String buttonEventId = callbackQuery.get("id") != null
                    ? String.valueOf(callbackQuery.get("id")) : null;
            String fromId = fromIdOf(callbackQuery);

            AnswerOutcome outcome = service.answer(token, approve, fromId,
                    TelegramPressOrigin.ofCallback(callbackQuery));
            if (!outcome.handled()) {
                // Unknown token: a stale message from a purged workspace, or a guess.
                // With no row there is no credential to even acknowledge the press with.
                meterRegistry.counter("chat.authorization.errors", "type", "UnknownCallbackToken").increment();
                logger.info("[chat-auth-telegram] press with unknown token (stale or forged), ignoring");
                return;
            }
            if (outcome.replyToUser() != null && outcome.request() != null) {
                // Re-bind the workspace scope: this thread serves a public webhook with
                // no org header, and the catalog call that acknowledges the press has to
                // resolve the same workspace credential the message was sent with.
                TenantResolver.runWithOrgScope(outcome.request().getOrganizationId(), () ->
                        service.acknowledge(outcome.request(), buttonEventId,
                                outcome.replyToUser(), outcome.asAlert()));
            }
        } catch (Exception ex) {
            meterRegistry.counter("chat.authorization.errors",
                    "type", ex.getClass().getSimpleName()).increment();
            logger.warn("[chat-auth-telegram] swallowed callback handling: {}", ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> callbackQueryOf(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Object cq = payload.get("callback_query");
        return cq instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String fromIdOf(Map<String, Object> callbackQuery) {
        Object from = callbackQuery.get("from");
        if (from instanceof Map<?, ?> fromMap && fromMap.get("id") != null) {
            return String.valueOf(fromMap.get("id"));
        }
        return null;
    }
}
