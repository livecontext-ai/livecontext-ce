package com.apimarketplace.monolith.chat;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.scaling.lock.InMemorySemaphore;
import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.dto.MessageDto;
import com.apimarketplace.conversation.service.ConversationExecutionLockService;
import com.apimarketplace.conversation.service.ConversationQueryService;
import com.apimarketplace.conversation.service.MessageService;
import com.apimarketplace.conversation.service.ai.AgentObservabilityClient;
import com.apimarketplace.conversation.service.ai.ConversationAgentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MonolithInternalChatController")
class MonolithInternalChatControllerTest {

    private final MessageService messageService = mock(MessageService.class);
    private final ConversationAgentService agentService = mock(ConversationAgentService.class);
    private final CreditConsumptionClient creditClient = mock(CreditConsumptionClient.class);
    private final AgentObservabilityClient observabilityClient = mock(AgentObservabilityClient.class);
    private final ConversationQueryService conversationQueryService = mock(ConversationQueryService.class);
    // The real per-conversation lock on the CE (single-instance) semaphore.
    private final ConversationExecutionLockService executionLockService =
        new ConversationExecutionLockService(new InMemorySemaphore());
    private final MonolithInternalChatController controller =
        new MonolithInternalChatController(messageService, agentService, creditClient, observabilityClient,
            conversationQueryService, executionLockService);

    @Test
    @DisplayName("CE sync chat endpoint preserves ConversationClient task execution contract")
    void ceSyncChatEndpointPreservesConversationClientTaskExecutionContract() {
        ChatRequest request = new ChatRequest();
        request.setConversationId("conv-1");
        request.setMessage("Run the CE task");
        request.setModel("deepseek-chat");
        request.setProvider("deepseek");
        request.setSource("TASK");
        request.setTaskId("task-1");

        // The controller asks the source-type-scoped 4-arg gate (provider + model), not the
        // 2-arg overload this test used to stub (a mock answers false to the unstubbed call).
        when(creditClient.checkCredits("tenant-42", "CHAT_CONVERSATION", "deepseek", "deepseek-chat")).thenReturn(true);
        when(conversationQueryService.isConversationInStrictScope("conv-1", "tenant-42", "org-1")).thenReturn(true);
        when(agentService.executeSync(request, "conv-1"))
            .thenReturn(Map.of("success", true, "content", "done", "conversationId", "conv-1"));

        var response = controller.chatSync(request, "tenant-42", "org-1", null, null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
            .containsEntry("success", true)
            .containsEntry("content", "done")
            .containsEntry("conversationId", "conv-1");
        assertThat(request.getUserId()).isEqualTo("tenant-42");
        assertThat(request.getOrgId()).isEqualTo("org-1");

        ArgumentCaptor<MessageDto> messageCaptor = ArgumentCaptor.forClass(MessageDto.class);
        verify(messageService).addMessage(eq("conv-1"), messageCaptor.capture());
        assertThat(messageCaptor.getValue().getConversationId()).isEqualTo("conv-1");
        assertThat(messageCaptor.getValue().getRole()).isEqualTo("user");
        assertThat(messageCaptor.getValue().getContent()).isEqualTo("Run the CE task");
        assertThat(messageCaptor.getValue().getTimestamp()).isNotBlank();
        verify(agentService).executeSync(request, "conv-1");
    }

    @Test
    @DisplayName("CE sync chat endpoint rejects missing conversationId before executing")
    void ceSyncChatEndpointRejectsMissingConversationIdBeforeExecuting() {
        ChatRequest request = new ChatRequest();
        request.setMessage("missing conversation");
        when(creditClient.checkCredits("tenant-42", "CHAT_CONVERSATION")).thenReturn(true);

        var response = controller.chatSync(request, "tenant-42", null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody())
            .containsEntry("success", false)
            .containsEntry("error", "conversationId is required");
        verify(messageService, never()).addMessage(org.mockito.Mockito.any(), org.mockito.Mockito.any());
        verify(agentService, never()).executeSync(org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }

    @Test
    @DisplayName("regression: orgRole / userRoles come from the headers; roles forged in the JSON body never reach the agent")
    void rolesComeFromHeadersNotFromBody() throws Exception {
        // Exploit body of the CE finding: a VIEWER posting orgRole=OWNER / userRoles=admin.
        ChatRequest request = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
            "{\"conversationId\":\"conv-1\",\"message\":\"hi\",\"orgRole\":\"OWNER\",\"userRoles\":\"admin\"}",
            ChatRequest.class);
        when(creditClient.checkCredits(org.mockito.Mockito.any(), org.mockito.Mockito.any(),
            org.mockito.Mockito.any(), org.mockito.Mockito.any())).thenReturn(true);
        when(conversationQueryService.isConversationInStrictScope("conv-1", "tenant-42", "org-1")).thenReturn(true);
        when(agentService.executeSync(org.mockito.Mockito.any(), eq("conv-1"))).thenReturn(Map.of("success", true));

        controller.chatSync(request, "tenant-42", "org-1", "VIEWER", "user");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(agentService).executeSync(captor.capture(), eq("conv-1"));
        assertThat(captor.getValue().getOrgRole()).isEqualTo("VIEWER");
        assertThat(captor.getValue().getUserRoles()).isEqualTo("user");
    }

    @Test
    @DisplayName("regression: a conversation the caller may not write is refused with 404, nothing written or executed")
    void foreignConversationIsRefused() {
        ChatRequest request = new ChatRequest();
        request.setConversationId("victim-conv");
        request.setMessage("injected");
        when(creditClient.checkCredits(org.mockito.Mockito.any(), org.mockito.Mockito.any(),
            org.mockito.Mockito.any(), org.mockito.Mockito.any())).thenReturn(false);
        when(conversationQueryService.isConversationInStrictScope("victim-conv", "tenant-42", "org-1")).thenReturn(false);

        var response = controller.chatSync(request, "tenant-42", "org-1", null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).containsEntry("success", false);
        // Neither the normal path nor the 402 audit-trail path may write into the conversation.
        verify(messageService, never()).addMessage(org.mockito.Mockito.any(), org.mockito.Mockito.any());
        verify(messageService, never()).persistAttemptAndError(org.mockito.Mockito.any(),
            org.mockito.Mockito.any(), org.mockito.Mockito.any());
        verify(messageService, never()).persistAttemptAndError(org.mockito.Mockito.any(),
            org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any());
        verify(agentService, never()).executeSync(org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }

    @Test
    @DisplayName("regression LC-066 (CE parity): a delegated task tagged RESTRICTED is stored RESTRICTED, "
        + "like the cloud InternalChatController")
    void restrictedTaskTurnIsStoredRestricted() {
        ChatRequest request = taskRequest("RESTRICTED");
        when(creditClient.checkCredits("tenant-42", "CHAT_CONVERSATION", "deepseek", "deepseek-chat")).thenReturn(true);
        when(conversationQueryService.isConversationInStrictScope("conv-1", "tenant-42", "org-1")).thenReturn(true);
        when(agentService.executeSync(request, "conv-1")).thenReturn(Map.of("success", true));

        controller.chatSync(request, "tenant-42", "org-1", null, null);

        ArgumentCaptor<MessageDto> messageCaptor = ArgumentCaptor.forClass(MessageDto.class);
        verify(messageService).addMessage(eq("conv-1"), messageCaptor.capture());
        assertThat(messageCaptor.getValue().getDataSensitivity())
            .as("untagged, the restricted task content would be re-sent to any provider in CE")
            .isEqualTo("RESTRICTED");
    }

    @Test
    @DisplayName("an untagged or NORMAL task turn stays unclassified (null), so the usual classification applies")
    void untaggedTaskTurnStaysUnclassified() {
        ChatRequest request = taskRequest("NORMAL");
        when(creditClient.checkCredits("tenant-42", "CHAT_CONVERSATION", "deepseek", "deepseek-chat")).thenReturn(true);
        when(conversationQueryService.isConversationInStrictScope("conv-1", "tenant-42", "org-1")).thenReturn(true);
        when(agentService.executeSync(request, "conv-1")).thenReturn(Map.of("success", true));

        controller.chatSync(request, "tenant-42", "org-1", null, null);

        ArgumentCaptor<MessageDto> messageCaptor = ArgumentCaptor.forClass(MessageDto.class);
        verify(messageService).addMessage(eq("conv-1"), messageCaptor.capture());
        assertThat(messageCaptor.getValue().getDataSensitivity()).isNull();
    }

    @Test
    @DisplayName("regression LC-066 (CE parity): the 402 audit trail of a RESTRICTED task stores the attempt RESTRICTED")
    void refusedRestrictedTaskAttemptIsStoredRestricted() {
        ChatRequest request = taskRequest("RESTRICTED");
        when(creditClient.checkCredits("tenant-42", "CHAT_CONVERSATION", "deepseek", "deepseek-chat")).thenReturn(false);
        when(conversationQueryService.isConversationInStrictScope("conv-1", "tenant-42", "org-1")).thenReturn(true);

        var response = controller.chatSync(request, "tenant-42", "org-1", null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(402);
        verify(messageService).persistAttemptAndError(eq("conv-1"), eq("Run the CE task"),
            org.mockito.Mockito.anyString(), eq("RESTRICTED"));
        verify(agentService, never()).executeSync(org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }

    @Test
    @DisplayName("regression (CE parity): two task turns on one conversation run one after the other, never interleaved")
    void concurrentTurnsOnOneConversationAreSerialized() throws Exception {
        when(creditClient.checkCredits("tenant-42", "CHAT_CONVERSATION", "deepseek", "deepseek-chat")).thenReturn(true);
        when(conversationQueryService.isConversationInStrictScope("conv-1", "tenant-42", "org-1")).thenReturn(true);
        ChatRequest first = taskRequest(null);
        first.setMessage("first turn");
        ChatRequest second = taskRequest(null);
        second.setMessage("second turn");
        CountDownLatch firstRunning = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        when(agentService.executeSync(org.mockito.ArgumentMatchers.any(), eq("conv-1"))).thenAnswer(invocation -> {
            ChatRequest running = invocation.getArgument(0);
            if ("first turn".equals(running.getMessage())) {
                firstRunning.countDown();
                assertThat(releaseFirst.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return Map.of("success", true);
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstCall = pool.submit(() -> controller.chatSync(first, "tenant-42", "org-1", null, null));
            assertThat(firstRunning.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> secondCall = pool.submit(() -> controller.chatSync(second, "tenant-42", "org-1", null, null));

            // While the first turn runs, the second one writes nothing into the conversation.
            verify(messageService, org.mockito.Mockito.after(500).never())
                .addMessage(eq("conv-1"), org.mockito.ArgumentMatchers.argThat(m -> "second turn".equals(m.getContent())));

            releaseFirst.countDown();
            firstCall.get(10, TimeUnit.SECONDS);
            secondCall.get(10, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(messageService, agentService);
        order.verify(messageService).addMessage(eq("conv-1"),
            org.mockito.ArgumentMatchers.argThat(m -> "first turn".equals(m.getContent())));
        order.verify(agentService).executeSync(first, "conv-1");
        order.verify(messageService).addMessage(eq("conv-1"),
            org.mockito.ArgumentMatchers.argThat(m -> "second turn".equals(m.getContent())));
        order.verify(agentService).executeSync(second, "conv-1");
    }

    @Test
    @DisplayName("a turn that cannot get the conversation in time answers 409 busy, like the cloud, and writes nothing")
    void busyConversationAnswersConflict() {
        ConversationExecutionLockService busyLock = mock(ConversationExecutionLockService.class);
        when(busyLock.withConversationLock(eq("conv-1"), org.mockito.ArgumentMatchers.any()))
            .thenThrow(new ConversationExecutionLockService.ConversationExecutionLockTimeoutException("conversation-execution:conv-1"));
        MonolithInternalChatController busyController = new MonolithInternalChatController(messageService,
            agentService, creditClient, observabilityClient, conversationQueryService, busyLock);
        when(conversationQueryService.isConversationInStrictScope("conv-1", "tenant-42", "org-1")).thenReturn(true);

        var response = busyController.chatSync(taskRequest(null), "tenant-42", "org-1", null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody())
            .containsEntry("success", false)
            .containsEntry("error", "Conversation is busy")
            .containsEntry("conversationId", "conv-1");
        verify(messageService, never()).addMessage(org.mockito.Mockito.any(), org.mockito.Mockito.any());
        verify(agentService, never()).executeSync(org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }

    @Test
    @DisplayName("a refused conversation is answered before any lock is taken")
    void refusedConversationTakesNoLock() {
        ConversationExecutionLockService lock = mock(ConversationExecutionLockService.class);
        MonolithInternalChatController lockedController = new MonolithInternalChatController(messageService,
            agentService, creditClient, observabilityClient, conversationQueryService, lock);
        when(conversationQueryService.isConversationInStrictScope("conv-1", "tenant-42", "org-1")).thenReturn(false);

        var response = lockedController.chatSync(taskRequest(null), "tenant-42", "org-1", null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        org.mockito.Mockito.verifyNoInteractions(lock);
    }

    @Test
    @DisplayName("regression (CE parity): a 402-refused task run is recorded under the execution id its task points at")
    void refusedTaskRunIsRecordedUnderTheCallerExecutionId() {
        String executionId = UUID.randomUUID().toString();
        ChatRequest request = taskRequest(null);
        request.setExecutionId(executionId);
        when(creditClient.checkCredits("tenant-42", "CHAT_CONVERSATION", "deepseek", "deepseek-chat")).thenReturn(false);
        when(conversationQueryService.isConversationInStrictScope("conv-1", "tenant-42", "org-1")).thenReturn(true);

        var response = controller.chatSync(request, "tenant-42", "org-1", null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(402);
        verify(observabilityClient).recordFailureAsync(eq("tenant-42"), eq("org-1"), org.mockito.Mockito.any(),
            eq("TASK"), eq("conv-1"), eq("BUDGET_EXHAUSTED"), org.mockito.Mockito.any(),
            eq("Run the CE task"), org.mockito.Mockito.anyString(), eq("deepseek"), eq("deepseek-chat"),
            eq(executionId));
    }

    private static ChatRequest taskRequest(String dataSensitivity) {
        ChatRequest request = new ChatRequest();
        request.setConversationId("conv-1");
        request.setMessage("Run the CE task");
        request.setModel("deepseek-chat");
        request.setProvider("deepseek");
        request.setSource("TASK");
        request.setTaskId("task-1");
        request.setDataSensitivity(dataSensitivity);
        return request;
    }
}
