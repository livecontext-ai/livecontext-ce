package com.apimarketplace.monolith.ws;

import com.apimarketplace.agent.controller.InternalAgentController;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.scope.TolerantScope;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.repository.DmThreadRepository;
import com.apimarketplace.conversation.repository.ConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Per-channel WebSocket subscription authorization for the CE monolith - the in-JVM
 * equivalent of the cloud gateway's {@code ChannelAuthorizer} (which does NOT run in CE,
 * where the gateway is bypassed). Without this, any authenticated CE user could subscribe
 * to another tenant's {@code conversation:}/{@code workflow:run:}/{@code task:board:} channel
 * and receive its events.
 *
 * <p>Mirrors {@code ChannelAuthorizer.doAuthorize} channel-for-channel, but calls the internal
 * access controllers directly (same JVM - no HTTP) the way {@link MonolithWsActionHandler}
 * already calls {@code InternalAccessController}/{@code InternalSignalController}. The two
 * {@code InternalAccessController} beans (orchestrator + conversation) co-exist because the
 * monolith uses a FullyQualified bean-name generator, so by-type injection is unambiguous.
 *
 * <p><b>{@code numericUserId} MUST be the numeric DB user id</b> (the JWT {@code userId} claim,
 * == the {@code X-User-ID} the HTTP layer injects) - NOT the JWT subject - because the channel
 * ids ({@code user:{id}}, {@code task:board:{id}}) and the access controllers' {@code ScopeGuard}
 * compare against the numeric tenant id. Passing the subject would deny everything.
 *
 * <p>Fail-closed: any unknown channel, malformed id, or check error denies the subscription.
 */
@Component
@ConditionalOnProperty(name = "deployment.mode", havingValue = "monolith")
public class MonolithChannelAuthorizer {

    private static final Logger log = LoggerFactory.getLogger(MonolithChannelAuthorizer.class);

    private final com.apimarketplace.orchestrator.controllers.internal.InternalAccessController orchestratorAccess;
    // The conversation InternalAccessController is EXCLUDED from the monolith component scan
    // (MonolithApplication excludes com.apimarketplace.conversation.controller.internal.*, since it
    // depends on reactive streaming), so we run the same owner-or-org check via the repository directly.
    private final ConversationRepository conversationRepository;
    private final DmThreadRepository dmThreadRepository;
    private final InternalAgentController agentAccess;

    public MonolithChannelAuthorizer(
            com.apimarketplace.orchestrator.controllers.internal.InternalAccessController orchestratorAccess,
            ConversationRepository conversationRepository,
            DmThreadRepository dmThreadRepository,
            InternalAgentController agentAccess) {
        this.orchestratorAccess = orchestratorAccess;
        this.conversationRepository = conversationRepository;
        this.dmThreadRepository = dmThreadRepository;
        this.agentAccess = agentAccess;
    }

    /**
     * @param numericUserId the numeric DB user id (JWT {@code userId} claim), as a String
     * @param orgId         the session's active organization id (validated membership), or null
     * @param channel       the WS channel the client wants to subscribe to
     * @return true iff the user may subscribe; false (deny) on any mismatch/error/unknown channel
     */
    @TolerantScope(reason = "WS channel authorization - caller already authenticated by the CE "
            + "session filter; owner-or-org tolerance mirrors conversation-service's "
            + "InternalAccessController#checkAccess (the microservice twin), so an org teammate "
            + "can subscribe to a shared conversation/workflow channel from either workspace")
    public boolean authorize(String numericUserId, String orgId, String channel) {
        if (numericUserId == null || numericUserId.isBlank() || channel == null || channel.isBlank()) {
            return false;
        }
        try {
            // user:{userId}:notifications - must match the authenticated (numeric) user
            if (channel.startsWith("user:") && channel.endsWith(":notifications")) {
                String chUser = channel.substring("user:".length(), channel.length() - ":notifications".length());
                return numericUserId.equals(chUser);
            }

            // org:{orgId}:notifications - must match the session's active org
            if (channel.startsWith("org:") && channel.endsWith(":notifications")) {
                String chOrg = channel.substring("org:".length(), channel.length() - ":notifications".length());
                return orgId != null && !orgId.isBlank() && orgId.equals(chOrg);
            }

            // task:board:{tenantId} - tenant-scoped, tenantId == numeric user id
            if (channel.startsWith("task:board:")) {
                return numericUserId.equals(channel.substring("task:board:".length()));
            }

            // dm-inbox:{userId} - a user's personal DM inbox.
            if (channel.startsWith("dm-inbox:")) {
                return numericUserId.equals(channel.substring("dm-inbox:".length()));
            }

            // dm:{threadId} - identity-level, global DM thread access.
            if (channel.startsWith("dm:")) {
                String threadId = channel.substring("dm:".length());
                return dmThreadRepository.findById(threadId)
                        .map(thread -> thread.hasParticipant(numericUserId))
                        .orElse(false);
            }

            // workflow:run:{runId}[:steps] - owner-or-org access to the run
            if (channel.startsWith("workflow:run:")) {
                String part = channel.substring("workflow:run:".length());
                String runId = part.contains(":") ? part.substring(0, part.indexOf(':')) : part;
                return Boolean.TRUE.equals(orchestratorAccess.checkRunAccess(runId, numericUserId, orgId).getBody());
            }

            // conversation:{convId} - owner-or-org access to the conversation (repo + ScopeGuard,
            // mirroring conversation-service's InternalAccessController#checkAccess which is scan-excluded here)
            if (channel.startsWith("conversation:")) {
                String convId = channel.substring("conversation:".length());
                return conversationRepository.findById(convId)
                        .map(c -> ScopeGuard.isInOwnerOrOrgScope(numericUserId, orgId, c.getUserId(), c.getOrganizationId()))
                        .orElse(false);
            }

            // collab:{workflowId} - owner-or-org access to the workflow
            if (channel.startsWith("collab:")) {
                String workflowId = channel.substring("collab:".length());
                return Boolean.TRUE.equals(orchestratorAccess.checkWorkflowAccess(workflowId, numericUserId, orgId).getBody());
            }

            // agent:activity:{agentId} - owner-or-org access to the agent
            if (channel.startsWith("agent:activity:")) {
                String agentId = channel.substring("agent:activity:".length());
                return Boolean.TRUE.equals(agentAccess.checkAccess(UUID.fromString(agentId), numericUserId, orgId).getBody());
            }

            log.warn("[MonolithWS authz] Denied unknown channel pattern: {}", channel);
            return false;
        } catch (IllegalArgumentException badId) {
            log.warn("[MonolithWS authz] Denied malformed channel id: {}", channel);
            return false;
        } catch (Exception e) {
            log.warn("[MonolithWS authz] Authorization check failed (deny) for channel {}: {}", channel, e.getMessage());
            return false;
        }
    }
}
