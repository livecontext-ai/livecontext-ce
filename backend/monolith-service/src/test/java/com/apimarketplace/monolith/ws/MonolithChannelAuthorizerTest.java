package com.apimarketplace.monolith.ws;

import com.apimarketplace.agent.controller.InternalAgentController;
import com.apimarketplace.conversation.entity.DmThread;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.repository.DmThreadRepository;
import com.apimarketplace.conversation.repository.ConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MonolithChannelAuthorizer} - the CE in-JVM mirror of the gateway's
 * per-channel WS ChannelAuthorizer. Verifies the string-compare channels, that the resource
 * channels delegate to the right in-JVM access bean (controller or repository + ScopeGuard),
 * and fail-closed behavior.
 */
class MonolithChannelAuthorizerTest {

    private com.apimarketplace.orchestrator.controllers.internal.InternalAccessController orchestratorAccess;
    private ConversationRepository conversationRepository;
    private DmThreadRepository dmThreadRepository;
    private InternalAgentController agentAccess;
    private MonolithChannelAuthorizer authorizer;

    @BeforeEach
    void setUp() {
        orchestratorAccess = mock(com.apimarketplace.orchestrator.controllers.internal.InternalAccessController.class);
        conversationRepository = mock(ConversationRepository.class);
        dmThreadRepository = mock(DmThreadRepository.class);
        agentAccess = mock(InternalAgentController.class);
        authorizer = new MonolithChannelAuthorizer(orchestratorAccess, conversationRepository, dmThreadRepository, agentAccess);
    }

    @Test
    @DisplayName("user:{id}:notifications - allowed only for the matching numeric user id")
    void userNotificationsMatchOnly() {
        assertThat(authorizer.authorize("5", null, "user:5:notifications")).isTrue();
        assertThat(authorizer.authorize("5", null, "user:6:notifications")).isFalse();
    }

    @Test
    @DisplayName("task:board:{tenantId} - allowed only for the matching numeric user id (tenant = user)")
    void taskBoardMatchOnly() {
        assertThat(authorizer.authorize("5", null, "task:board:5")).isTrue();
        assertThat(authorizer.authorize("5", null, "task:board:6")).isFalse();
    }

    @Test
    @DisplayName("org:{orgId}:notifications - allowed only when the session's active org matches; null org denied")
    void orgNotificationsMatchActiveOrg() {
        assertThat(authorizer.authorize("5", "org-a", "org:org-a:notifications")).isTrue();
        assertThat(authorizer.authorize("5", "org-a", "org:org-b:notifications")).isFalse();
        assertThat(authorizer.authorize("5", null, "org:org-a:notifications")).isFalse();
    }

    @Test
    @DisplayName("dm-inbox:{userId} - allowed only for the matching numeric user id")
    void dmInboxMatchOnly() {
        assertThat(authorizer.authorize("5", null, "dm-inbox:5")).isTrue();
        assertThat(authorizer.authorize("5", null, "dm-inbox:6")).isFalse();
    }

    @Test
    @DisplayName("dm:{threadId} - allowed only for participants, independent of active org")
    void dmThreadParticipantOnly() {
        DmThread thread = new DmThread("5", "9");
        when(dmThreadRepository.findById("thread-1")).thenReturn(Optional.of(thread));
        when(dmThreadRepository.findById("missing")).thenReturn(Optional.empty());

        assertThat(authorizer.authorize("5", "org-a", "dm:thread-1")).isTrue();
        assertThat(authorizer.authorize("9", null, "dm:thread-1")).isTrue();
        assertThat(authorizer.authorize("6", "org-a", "dm:thread-1")).isFalse();
        assertThat(authorizer.authorize("5", "org-a", "dm:missing")).isFalse();
    }

    @Test
    @DisplayName("conversation:{id} - owner match via repository + ScopeGuard; non-owner/other-org denied")
    void conversationOwnerOrOrgViaRepository() {
        Conversation owned = mock(Conversation.class);
        lenient().when(owned.getUserId()).thenReturn("5");
        lenient().when(owned.getOrganizationId()).thenReturn(null);
        when(conversationRepository.findById("conv-mine")).thenReturn(Optional.of(owned));

        Conversation foreign = mock(Conversation.class);
        lenient().when(foreign.getUserId()).thenReturn("999");
        lenient().when(foreign.getOrganizationId()).thenReturn(null);
        when(conversationRepository.findById("conv-other")).thenReturn(Optional.of(foreign));

        when(conversationRepository.findById("conv-missing")).thenReturn(Optional.empty());

        assertThat(authorizer.authorize("5", "org-a", "conversation:conv-mine")).isTrue();
        assertThat(authorizer.authorize("5", "org-a", "conversation:conv-other")).isFalse();
        assertThat(authorizer.authorize("5", "org-a", "conversation:conv-missing")).isFalse();
    }

    @Test
    @DisplayName("workflow:run:{runId}[:steps] - delegates to run access, stripping the :steps suffix")
    void workflowRunDelegatesAndStripsSuffix() {
        when(orchestratorAccess.checkRunAccess(eq("run-1"), eq("5"), any())).thenReturn(ResponseEntity.ok(true));
        assertThat(authorizer.authorize("5", "org-a", "workflow:run:run-1")).isTrue();
        assertThat(authorizer.authorize("5", "org-a", "workflow:run:run-1:steps")).isTrue();
    }

    @Test
    @DisplayName("collab:{workflowId} - delegates to the workflow access controller")
    void collabDelegatesToWorkflowAccess() {
        when(orchestratorAccess.checkWorkflowAccess(eq("wf-1"), eq("5"), any())).thenReturn(ResponseEntity.ok(true));
        assertThat(authorizer.authorize("5", "org-a", "collab:wf-1")).isTrue();
    }

    @Test
    @DisplayName("agent:activity:{agentId} - delegates to the agent access controller; malformed UUID denied")
    void agentActivityDelegatesAndRejectsBadUuid() {
        UUID agentId = UUID.randomUUID();
        when(agentAccess.checkAccess(eq(agentId), eq("5"), any())).thenReturn(ResponseEntity.ok(true));
        assertThat(authorizer.authorize("5", "org-a", "agent:activity:" + agentId)).isTrue();
        assertThat(authorizer.authorize("5", "org-a", "agent:activity:not-a-uuid")).isFalse();
    }

    @Test
    @DisplayName("Fail-closed: unknown channel, null/blank user, and access errors all deny")
    void failClosed() {
        assertThat(authorizer.authorize("5", "org-a", "mystery:channel")).isFalse();
        assertThat(authorizer.authorize(null, "org-a", "user:5:notifications")).isFalse();
        assertThat(authorizer.authorize("", "org-a", "user:5:notifications")).isFalse();
        // repository throwing means deny (not propagate)
        when(conversationRepository.findById("boom")).thenThrow(new RuntimeException("db down"));
        assertThat(authorizer.authorize("5", "org-a", "conversation:boom")).isFalse();
    }
}
