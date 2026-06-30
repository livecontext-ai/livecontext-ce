package com.apimarketplace.monolith.ws;

import com.apimarketplace.agent.service.execution.AgentActivitySnapshotService;
import com.apimarketplace.conversation.streaming.StreamStateService;
import com.apimarketplace.orchestrator.controllers.internal.InternalAccessController;
import com.apimarketplace.orchestrator.controllers.internal.InternalSbsController;
import com.apimarketplace.orchestrator.controllers.internal.InternalSignalController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("MonolithWsActionHandler")
class MonolithWsActionHandlerTest {

    @Mock
    private InternalSignalController signalController;

    @Mock
    private InternalSbsController sbsController;

    @Mock
    private InternalAccessController accessController;

    @Mock
    private StreamStateService streamStateService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private WebSocketSession session;

    @Mock
    private AgentActivitySnapshotService agentActivitySnapshotService;

    private MonolithWsActionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new MonolithWsActionHandler(signalController, sbsController, accessController,
                new ObjectMapper(), streamStateService, redisTemplate, agentActivitySnapshotService);
        // The virtual-thread worker tries to send an ack - drop it.
        lenient().when(session.isOpen()).thenReturn(false);
    }

    @Test
    @DisplayName("regression: sbs.execute passes the session's active org (and numeric user id) to the internal controller")
    void sbsExecuteForwardsOrgScope() throws Exception {
        // Bug class: the monolith passed a null org - SBS steps of org-workspace
        // runs ran with no workspace scope (lost output payloads) and, with the
        // run-scope guard, would now be rejected outright.
        CountDownLatch invoked = new CountDownLatch(1);
        doAnswer(invocation -> {
            invoked.countDown();
            return ResponseEntity.ok(Map.<String, Object>of("accepted", true));
        }).when(sbsController).executeNode(eq("run-1"), eq("core:step"), eq("42"), eq("org-7"), anyMap());

        handler.handle("42", "org-7", session, "msg-1", "sbs.execute",
                Map.of("runId", "run-1", "nodeId", "core:step"));

        assertThat(invoked.await(5, TimeUnit.SECONDS))
                .as("sbs.execute should reach InternalSbsController with userId=42 and org=org-7")
                .isTrue();
    }

    @Test
    @DisplayName("signal.resolve passes the session's active org to the internal controller")
    void signalResolveForwardsOrgScope() throws Exception {
        CountDownLatch invoked = new CountDownLatch(1);
        doAnswer(invocation -> {
            invoked.countDown();
            return ResponseEntity.ok(Map.<String, Object>of("status", "resolved"));
        }).when(signalController).resolveSignal(eq(7L), eq("42"), eq("org-7"), anyMap());

        handler.handle("42", "org-7", session, "msg-1", "signal.resolve",
                Map.of("signalId", "7"));

        assertThat(invoked.await(5, TimeUnit.SECONDS))
                .as("signal.resolve should reach InternalSignalController with userId=42 and org=org-7")
                .isTrue();
    }

    @Test
    @DisplayName("agent:activity snapshot routes the channel's agent id to the shared AgentActivitySnapshotService")
    void agentActivitySnapshotRoutesToSharedService() throws Exception {
        UUID agentId = UUID.randomUUID();
        CountDownLatch invoked = new CountDownLatch(1);
        doAnswer(invocation -> {
            invoked.countDown();
            return 1;
        }).when(agentActivitySnapshotService).publishRunningSnapshot(agentId);

        handler.triggerSnapshot("agent:activity:" + agentId, "42");

        assertThat(invoked.await(5, TimeUnit.SECONDS))
                .as("agent:activity snapshot should call publishRunningSnapshot with the channel's agent id")
                .isTrue();
    }

    @Test
    @DisplayName("a malformed agent:activity channel id is swallowed by the best-effort guard - no service call, no thrown error")
    void malformedAgentActivityChannelIsSwallowed() throws Exception {
        CountDownLatch invoked = new CountDownLatch(1);
        lenient().doAnswer(invocation -> {
            invoked.countDown();
            return 1;
        }).when(agentActivitySnapshotService).publishRunningSnapshot(any());

        // UUID.fromString throws on "not-a-uuid"; the surrounding try/catch must swallow it
        // (mirrors the workflow/conversation branches) - triggerSnapshot returns normally.
        handler.triggerSnapshot("agent:activity:not-a-uuid", "42");

        assertThat(invoked.await(500, TimeUnit.MILLISECONDS))
                .as("malformed agent id must NOT reach the snapshot service")
                .isFalse();
    }
}
