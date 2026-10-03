package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.common.classification.DataSensitivity;
import com.apimarketplace.common.classification.RestrictedDataPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Keeps Google restricted-scope content already in a conversation (Gmail, Drive tool results
 * and the assistant text quoting them) away from LLM providers that may not receive it
 * (CASA LC-004).
 *
 * <p>A chat turn re-sends the conversation history to the model. Once a conversation holds
 * restricted content, every later turn transfers it again, to whatever provider the user picks
 * next. This guard runs where the turn is built, for BOTH dispatch routes (agent-service and the
 * direct CLI-bridge path, which bypasses agent-service):
 * <ul>
 *   <li>it stamps {@link DataSensitivity#CREDENTIAL_KEY} on the execution credentials, so
 *       agent-service's loop and tool layer treat the whole execution as restricted;</li>
 *   <li>it refuses the turn outright when the resolved provider is not allowed to receive
 *       restricted data ({@link RestrictedDataPolicy#mayReceiveRestricted}).</li>
 * </ul>
 * Content that was redacted by the retention purge no longer counts: its rows are REDACTED.
 */
@Component
public class RestrictedDataTransferGuard {

    private static final Logger log = LoggerFactory.getLogger(RestrictedDataTransferGuard.class);

    private final JdbcTemplate jdbc;

    public RestrictedDataTransferGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** True when the conversation still holds restricted tool results or messages. */
    public boolean conversationHoldsRestrictedData(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return false;
        }
        Boolean restricted = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM conversation.tool_results "
                        + "WHERE conversation_id = ? AND data_sensitivity = 'RESTRICTED') "
                        + "OR EXISTS (SELECT 1 FROM conversation.messages "
                        + "WHERE conversation_id = ? AND data_sensitivity = 'RESTRICTED')",
                Boolean.class, conversationId, conversationId);
        return Boolean.TRUE.equals(restricted);
    }

    /**
     * Classify the turn and enforce the provider allow-list.
     *
     * @param credentials the execution credentials being built; tagged in place when restricted
     * @throws RestrictedDataTransferDeniedException when the conversation holds restricted data
     *                                               and {@code provider} may not receive it
     */
    public void apply(String conversationId, String provider, Map<String, Object> credentials) {
        boolean restricted = DataSensitivity.fromCredentials(credentials).isRestricted()
                || conversationHoldsRestrictedData(conversationId);
        if (!restricted) {
            return;
        }
        if (credentials != null) {
            credentials.put(DataSensitivity.CREDENTIAL_KEY, DataSensitivity.RESTRICTED.name());
        }
        if (!RestrictedDataPolicy.mayReceiveRestricted(provider)) {
            log.info("Restricted-data transfer refused: conversation={} provider={}", conversationId, provider);
            throw new RestrictedDataTransferDeniedException(provider);
        }
    }

    /** The turn would send Gmail / Drive content to a provider outside the allow-list. */
    public static class RestrictedDataTransferDeniedException extends RuntimeException {

        private final String providerName;

        public RestrictedDataTransferDeniedException(String providerName) {
            super(RestrictedDataPolicy.refusalMessage(providerName));
            this.providerName = providerName;
        }

        public String getProviderName() {
            return providerName;
        }
    }
}
