package com.apimarketplace.agent.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.dto.AgentTaskDispatchView;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.common.credit.ChatCreditRefusal;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.conversation.client.ConversationClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A task dispatch refused for credits is the customer's to resolve, so it is a WARN.
 *
 * <p>This is the same class of defect as the agent-schedule branch: the task and webhook paths call
 * the same internal sync-chat endpoint, get the same HTTP 402 back, and logged it at ERROR. The task
 * is still failed; only the level changes, and these tests assert both halves so a future
 * "simplification" cannot quietly turn the refusal into a silent success.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskService - a credit refusal is a warning, a fault is an error")
class AgentTaskServiceRefusalLogLevelTest {

    private static final String TENANT = "tenant-1";
    private static final String ORG = "org-1";
    private static final String CONVERSATION_ID = "conversation-1";

    @Mock private AgentTaskRepository taskRepository;
    @Mock private AgentTaskNoteRepository noteRepository;
    @Mock private AgentTaskEventRepository eventRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentExecutionRepository executionRepository;
    @Mock private TaskBoardPublisher taskBoardPublisher;
    @Mock private ConversationClient conversationClient;
    @Mock private AgentTaskService self;

    private AgentTaskService service;
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() {
        service = new AgentTaskService(taskRepository, noteRepository, eventRepository,
                agentRepository, executionRepository, taskBoardPublisher, conversationClient, self);
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AgentTaskService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("out of credits: WARN, no ERROR, and the task is still failed")
    void creditRefusalIsWarnAndStillFailsTheTask() {
        UUID taskId = dispatchWithChatError(ChatCreditRefusal.MESSAGE);

        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("refused").contains(taskId.toString());
        });
        // The behaviour half: a quieter log must never become a quieter outcome.
        verify(self).markExecutionFailed(eq(taskId), eq(TENANT), anyString());
    }

    @Test
    @DisplayName("a genuine fault keeps ERROR - the task log must not go uniformly quiet")
    void platformFaultStaysError() {
        UUID taskId = dispatchWithChatError("NullPointerException in the agent loop");

        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getFormattedMessage()).contains("failed").contains(taskId.toString());
        });
        // Without this, a change that fired BOTH branches would leave the test green.
        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("refused"));
        verify(self).markExecutionFailed(eq(taskId), eq(TENANT), anyString());
    }

    @Test
    @DisplayName("the WRAPPED transport string is recognised too - the chain, not just the unwrap")
    void wrappedTransportStringIsAlsoARefusal() {
        // Both tests above feed the clean sentence, which production only produces BECAUSE
        // ConversationClient now unwraps the body. Revert that unwrap and they would stay green
        // while production went back to ERROR. This one feeds what the transport itself relays,
        // so the two halves of the fix are pinned together rather than each assuming the other.
        UUID taskId = dispatchWithChatError(
            "402  on POST request for \"http://livecontext-livecontext-conversation:8087"
                + "/api/internal/chat/sync\": \"{\"error\":\"Insufficient credits\"}\"");

        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("refused").contains(taskId.toString());
        });
    }

    @Test
    @DisplayName("the REVIEWER branch is a refusal too - the third changed branch in this file")
    void reviewerCreditRefusalIsWarn() {
        UUID taskId = dispatchReviewerWithChatError(ChatCreditRefusal.MESSAGE);

        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("refused").contains(taskId.toString());
        });
        // The behaviour half, matching what the assignee cases assert. The production comment
        // on this branch claims handleReviewerFailureToAct still runs; an early return in the
        // refusal branch would orphan the task in in_review and leave the log assertions green.
        verify(self).incrementReviewAttemptCount(eq(taskId), eq(TENANT), eq(lastReviewerId), any(UUID.class));
    }

    @Test
    @DisplayName("a reviewer failing for a real fault keeps ERROR")
    void reviewerPlatformFaultStaysError() {
        UUID taskId = dispatchReviewerWithChatError("NullPointerException in the agent loop");

        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getFormattedMessage()).contains("failed").contains(taskId.toString());
        });
        // Without this, a change that fired BOTH branches would leave the test green.
        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("refused"));
    }

    /**
     * The reviewer id created by the most recent {@link #dispatchReviewerWithChatError} call,
     * so a test can name it in a verify(). A field rather than a returned pair because the
     * helper already returns the task id, and two return values would read worse than this.
     */
    private UUID lastReviewerId;

    /** Drives the real executeReviewerForTask failure branch with the given chat error string. */
    private UUID dispatchReviewerWithChatError(String chatError) {
        UUID taskId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        this.lastReviewerId = reviewerId;
        AgentTaskEntity task = task(taskId, UUID.randomUUID());
        task.setStatus(AgentTaskEntity.STATUS_IN_REVIEW);
        task.setReviewerAgentId(reviewerId);
        task.setResult("CURRENT_RESULT");
        AgentTaskDispatchView dispatchView = new AgentTaskDispatchView(
                reviewerId, "DeepSeek Reviewer", "deepseek", "deepseek-chat", true);

        lenient().when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.of(task));
        lenient().when(self.tryLockReviewerExecution(eq(taskId), any(UUID.class))).thenReturn(true);
        lenient().when(agentRepository.findTaskDispatchViewByIdAndOrganizationIdStrict(reviewerId, ORG))
                .thenReturn(Optional.of(dispatchView));
        lenient().when(conversationClient.findOrCreateAgentConversation(reviewerId.toString(), TENANT,
                "DeepSeek Reviewer", ORG)).thenReturn(CONVERSATION_ID);
        lenient().when(conversationClient.sendChatSync(eq(TENANT), eq(CONVERSATION_ID), anyString(),
                eq(reviewerId.toString()), eq("deepseek-chat"), eq("deepseek"), eq("TASK_REVIEW"),
                eq(taskId.toString()), eq(ORG), anyString()))
                .thenReturn(Map.of("success", false, "error", chatError));

        TenantResolver.runWithOrgScope(ORG, () -> invokePrivate("executeReviewerForTask", task));
        return taskId;
    }

    /** Drives the real executeAgentForTask failure branch with the given chat error string. */
    private UUID dispatchWithChatError(String chatError) {
        UUID taskId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        AgentTaskEntity task = task(taskId, agentId);
        AgentTaskEntity stillInProgress = task(taskId, agentId);
        AgentTaskDispatchView dispatchView = new AgentTaskDispatchView(
                agentId, "DeepSeek Worker", "deepseek", "deepseek-chat", true);

        lenient().when(self.tryLockAssigneeExecution(eq(taskId), any(UUID.class))).thenReturn(true);
        lenient().when(agentRepository.findTaskDispatchViewByIdAndOrganizationIdStrict(agentId, ORG))
                .thenReturn(Optional.of(dispatchView));
        lenient().when(conversationClient.findOrCreateAgentConversation(agentId.toString(), TENANT, "DeepSeek Worker", ORG))
                .thenReturn(CONVERSATION_ID);
        lenient().when(conversationClient.sendChatSync(eq(TENANT), eq(CONVERSATION_ID), contains("Complete the assigned task"),
                eq(agentId.toString()), eq("deepseek-chat"), eq("deepseek"), eq("TASK"), eq(taskId.toString()), eq(ORG)))
                .thenReturn(Map.of("success", false, "error", chatError));
        lenient().when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.of(stillInProgress));

        TenantResolver.runWithOrgScope(ORG, () -> invokePrivate("executeAgentForTask", task));
        return taskId;
    }

    private AgentTaskEntity task(UUID taskId, UUID agentId) {
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(taskId);
        task.setTenantId(TENANT);
        task.setOrganizationId(ORG);
        task.setAssignedToAgentId(agentId);
        task.setStatus(AgentTaskEntity.STATUS_IN_PROGRESS);
        task.setTitle("DeepSeek task dispatch");
        task.setInstructions("Complete the assigned task with the requested marker.");
        return task;
    }

    private void invokePrivate(String methodName, AgentTaskEntity task) {
        try {
            Method method = AgentTaskService.class.getDeclaredMethod(methodName, AgentTaskEntity.class);
            method.setAccessible(true);
            method.invoke(service, task);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
