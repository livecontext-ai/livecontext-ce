package com.apimarketplace.orchestrator.services.channel;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.UUID;

/**
 * What an agent says about its own delivery: its display name, and the destination it chose
 * (V523, {@code chatChannelLinkId}; null = the workspace default).
 *
 * <p>One lookup for both, shared by the two delivery surfaces (a permission request and an
 * {@code ask_user} question), so they cannot disagree about where an agent is reached.
 *
 * <p><b>A failed lookup is "unknown", never "no choice".</b> When the agent cannot be read there
 * is no way to tell whether it picked a destination, and guessing the default would put, say, a
 * finance approval in front of whoever reads the default group: exactly what choosing exists to
 * prevent. The callers therefore send nothing and say why. Only a request with no agent at all, or
 * an agent read successfully without a choice, goes to the workspace default.
 */
final class AgentChannelChoice {

    private static final Logger logger = LoggerFactory.getLogger(AgentChannelChoice.class);

    /**
     * The agent's name (may be null), its chosen destination (null = workspace default), and
     * whether the lookup failed, in which case neither is known and nothing may be sent.
     */
    record Choice(String agentName, UUID linkId, boolean unknown, boolean switchedOff) {
        static final Choice NONE = new Choice(null, null, false, false);
        static final Choice UNKNOWN = new Choice(null, null, true, false);
    }

    /** What the person is told when the agent could not be read. */
    static final String UNKNOWN_DESTINATION =
            "Could not read which chat this agent sends to, so nothing was sent. Decide in the app.";

    private AgentChannelChoice() {}

    static Choice lookup(ObjectProvider<AgentClient> agentClientProvider, String tenantId,
                         String organizationId, String agentId) {
        UUID id = parseUuid(agentId);
        if (id == null) {
            // Not an agent's request (or no id to read): nothing was ever chosen for it.
            return Choice.NONE;
        }
        AgentClient client = agentClientProvider != null ? agentClientProvider.getIfAvailable() : null;
        if (client == null) {
            // A deployment without the agent client cannot store a choice either.
            return Choice.NONE;
        }
        try {
            AgentDto agent = client.getAgent(id, tenantId, organizationId);
            if (agent == null) {
                logger.warn("[chat-channel] agent {} not found while delivering its request", agentId);
                return Choice.UNKNOWN;
            }
            // V524: an agent switched off reaches nobody; null (an older payload) means on.
            boolean off = Boolean.FALSE.equals(agent.getChatChannelEnabled());
            return new Choice(agent.getName(), agent.getChatChannelLinkId(), false, off);
        } catch (Exception ex) {
            logger.warn("[chat-channel] agent lookup failed for {}: {}", agentId, ex.getMessage());
            return Choice.UNKNOWN;
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
