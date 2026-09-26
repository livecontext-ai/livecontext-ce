package com.apimarketplace.orchestrator.services.channel;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity.RequestStatus;
import com.apimarketplace.orchestrator.repository.ChatAuthorizationRequestRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Closes authorization requests nobody answered in time.
 *
 * <p><b>This is not housekeeping, it is what makes the feature repeatable.</b> The
 * partial unique index that stops a nightly agent asking the same question every
 * night counts only rows still marked {@code SENT}. Without something that retires
 * them, the FIRST unanswered question would block that agent from ever asking
 * again: every later run would be told "you already asked", about a message whose
 * buttons the person may never press. Expiring the row is what lets tomorrow's run
 * ask afresh.
 *
 * <p>Also takes the buttons away from the message it expires, so nobody presses one
 * on a question that has stopped being answerable and gets no visible reaction.
 */
@Component
public class ChatAuthorizationExpiryScheduler {

    private static final Logger logger = LoggerFactory.getLogger(ChatAuthorizationExpiryScheduler.class);

    /**
     * Enough per pass for any realistic backlog, small enough never to hold the lock long.
     *
     * <p>Applied by the QUERY, not by the loop. The sweep is platform-wide, so the set it
     * selects is every unanswered request in every workspace, and a provider outage is
     * exactly the moment that set stops being small. Anything left over is taken by the
     * next pass, five minutes later, oldest first.
     */
    private static final int MAX_PER_PASS = 200;

    private final ChatAuthorizationRequestRepository requestRepository;
    private final ChatChannelConnectorRegistry connectors;

    public ChatAuthorizationExpiryScheduler(ChatAuthorizationRequestRepository requestRepository,
                                            ChatChannelConnectorRegistry connectors) {
        this.requestRepository = requestRepository;
        this.connectors = connectors;
    }

    @Scheduled(fixedDelayString = "${orchestrator.channel.authorization.expiry-scan-ms:300000}")
    @SchedulerLock(name = "chat-authorization-expiry", lockAtMostFor = "PT2M")
    public void expireOverdueRequests() {
        List<ChatAuthorizationRequestEntity> overdue;
        try {
            overdue = requestRepository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                    RequestStatus.SENT, Instant.now(), PageRequest.of(0, MAX_PER_PASS));
        } catch (Exception ex) {
            logger.warn("[chat-auth] expiry scan failed: {}", ex.getMessage());
            return;
        }
        if (overdue.isEmpty()) {
            return;
        }
        for (ChatAuthorizationRequestEntity request : overdue) {
            try {
                // Status first. The row is what the duplicate rule reads, so it must
                // be retired even if the message edit below fails.
                request.setStatus(RequestStatus.EXPIRED);
                requestRepository.save(request);
                closeMessage(request);
            } catch (Exception ex) {
                logger.warn("[chat-auth] could not expire request {}: {}", request.getId(), ex.getMessage());
            }
        }
        logger.info("[chat-auth] expired {} unanswered request(s)", overdue.size());
    }

    private void closeMessage(ChatAuthorizationRequestEntity request) {
        connectors.forChannel(request.getChannel()).ifPresent(connector ->
                // The workspace scope this row belongs to, re-bound for the catalog call:
                // this runs on a scheduler thread with no request context, and the send
                // has to resolve the same workspace credential the message went out with.
                TenantResolver.runWithOrgScope(request.getOrganizationId(), () ->
                        connector.closeDecisionRequest(request.getTenantId(), request.getCredentialId(),
                                request.getChatId(), request.getMessageId(),
                                // The body AS SENT, not a third wording of it. Rebuilding it here
                                // rewrote the question on the way out, so the record a phone user
                                // keeps of an expired ask no longer matched the one they were shown.
                                AgentAuthorizationChannelService.closingBody(request),
                                AgentAuthorizationChannelService.Verdict.EXPIRED.line())));
    }
}
