package com.apimarketplace.monolith.ws;

import com.apimarketplace.agent.service.execution.AgentActivitySnapshotService;
import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.conversation.streaming.StreamStateService;
import com.apimarketplace.orchestrator.controllers.internal.InternalAccessController;
import com.apimarketplace.orchestrator.controllers.internal.InternalSbsController;
import com.apimarketplace.orchestrator.controllers.internal.InternalSignalController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CE twin of the gateway WS VIEWER gate: MonolithWsActionHandler called the internal signal
 * and SBS controllers with the session org but no role, so a CE VIEWER could approve a gate
 * or step a run over the socket. The role is now read from the DB at action time (the JWT
 * can be stale after a demotion); a VIEWER is refused with an action.error and nothing runs;
 * other roles are forwarded WITH the role so the internal endpoints apply the deny-list.
 */
@DisplayName("MonolithWsActionHandler - VIEWER cannot drive runs over the CE socket")
class MonolithWsActionViewerGateTest {

    private static final UUID ORG = UUID.fromString("0f1e2d3c-4b5a-4968-8778-695a4b3c2d1e");

    private InternalSignalController signalController;
    private InternalSbsController sbsController;
    private OrganizationMemberRepository memberRepository;
    private WebSocketSession session;
    private MonolithWsActionHandler handler;

    @BeforeEach
    void setUp() {
        signalController = mock(InternalSignalController.class);
        sbsController = mock(InternalSbsController.class);
        memberRepository = mock(OrganizationMemberRepository.class);
        session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        handler = new MonolithWsActionHandler(signalController, sbsController, mock(InternalAccessController.class),
                new ObjectMapper(), mock(StreamStateService.class), mock(StringRedisTemplate.class),
                mock(AgentActivitySnapshotService.class), memberRepository);
    }

    private void role(OrganizationRole role) {
        User user = new User();
        user.setId(42L);
        Organization org = new Organization();
        org.setId(ORG);
        org.setName("o");
        org.setSlug("o-" + ORG);
        org.setOwner(user);
        when(memberRepository.findActiveByOrganizationIdAndUserId(ORG, 42L))
                .thenReturn(Optional.of(new OrganizationMember(org, user, role, false)));
    }

    @Test
    @DisplayName("VIEWER signal.resolve and sbs.execute: action.error 403, internal controllers never called")
    void viewerRefused() throws Exception {
        role(OrganizationRole.VIEWER);
        CountDownLatch sent = new CountDownLatch(2);
        AtomicReference<String> last = new AtomicReference<>();
        doAnswer(inv -> {
            last.set(((TextMessage) inv.getArgument(0)).getPayload());
            sent.countDown();
            return null;
        }).when(session).sendMessage(any());

        handler.handle("42", ORG.toString(), session, "m-1", "signal.resolve", Map.of("signalId", "7"));
        handler.handle("42", ORG.toString(), session, "m-2", "sbs.execute", Map.of("runId", "r", "nodeId", "n"));

        assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(last.get()).contains("action.error").contains("403");
        verifyNoInteractions(signalController, sbsController);
    }

    @Test
    @DisplayName("MEMBER sbs.execute is forwarded with the DB role MEMBER")
    void memberForwardedWithRole() throws Exception {
        role(OrganizationRole.MEMBER);
        CountDownLatch invoked = new CountDownLatch(1);
        doAnswer(inv -> {
            invoked.countDown();
            return ResponseEntity.ok(Map.<String, Object>of("accepted", true));
        }).when(sbsController).executeNode(eq("r"), eq("n"), eq("42"), eq(ORG.toString()), eq("MEMBER"), anyMap());

        handler.handle("42", ORG.toString(), session, "m-1", "sbs.execute", Map.of("runId", "r", "nodeId", "n"));

        assertThat(invoked.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("removed member (no active membership in the org): action.error 403, internal controllers never called")
    void removedMemberRefused() throws Exception {
        when(memberRepository.findActiveByOrganizationIdAndUserId(ORG, 42L)).thenReturn(Optional.empty());
        CountDownLatch sent = new CountDownLatch(2);
        AtomicReference<String> last = new AtomicReference<>();
        doAnswer(inv -> {
            last.set(((TextMessage) inv.getArgument(0)).getPayload());
            sent.countDown();
            return null;
        }).when(session).sendMessage(any());

        handler.handle("42", ORG.toString(), session, "m-1", "signal.resolve", Map.of("signalId", "7"));
        handler.handle("42", ORG.toString(), session, "m-2", "sbs.execute", Map.of("runId", "r", "nodeId", "n"));

        assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(last.get()).contains("action.error").contains("403").contains("no longer a member");
        verifyNoInteractions(signalController, sbsController);
    }
}
