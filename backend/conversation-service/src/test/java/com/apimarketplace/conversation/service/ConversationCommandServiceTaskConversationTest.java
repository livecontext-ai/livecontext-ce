package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.mapper.ConversationMapper;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LC-066 (review finding r5-2): the conversation a RESTRICTED delegated task's turns run in, so
 * the agent's main conversation never stores them. Keyed by (agent, task), same user and workspace,
 * memory off (never a candidate for the one-primary-conversation-per-agent indexes).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationCommandService - per-task conversation (LC-066)")
class ConversationCommandServiceTaskConversationTest {

    private static final String USER = "user-1";
    private static final String ORG = "org-1";
    private static final String AGENT = "agent-1";
    private static final String TASK = "11111111-1111-1111-1111-111111111111";

    @Mock private ConversationRepository conversationRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private WorkflowContextProvider workflowContextProvider;
    @Mock private UserChatDefaultsService userChatDefaultsService;
    @Mock private ConversationCommandService self;

    private ConversationCommandService service;

    @BeforeEach
    void setUp() {
        service = new ConversationCommandService(conversationRepository, messageRepository, new ConversationMapper(),
                workflowContextProvider, userChatDefaultsService, self);
        // The proxied REQUIRES_NEW insert runs the real method.
        lenient().when(self.insertTaskConversationInNewTransaction(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> service.insertTaskConversationInNewTransaction(inv.getArgument(0),
                        inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), inv.getArgument(4)));
    }

    @Test
    @DisplayName("first turn: creates a conversation keyed by the task, owned by the user and workspace, memory off")
    void createsTaskConversation() {
        when(conversationRepository.findTaskConversations(ORG, AGENT, TASK)).thenReturn(List.of());
        when(conversationRepository.saveAndFlush(any(Conversation.class))).thenAnswer(inv -> {
            Conversation c = inv.getArgument(0);
            c.setId("conv-task");
            return c;
        });

        ConversationDto dto = service.findOrCreateTaskConversation(USER, ORG, AGENT, TASK, "Worker - task 11111111");

        ArgumentCaptor<Conversation> saved = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationRepository).saveAndFlush(saved.capture());
        Conversation c = saved.getValue();
        assertThat(c.getTaskId()).isEqualTo(TASK);
        assertThat(c.getAgentId()).isEqualTo(AGENT);
        assertThat(c.getUserId()).isEqualTo(USER);
        assertThat(c.getOrganizationId()).isEqualTo(ORG);
        assertThat(c.getMemoryEnabled()).isFalse();
        assertThat(c.getActive()).isTrue();
        assertThat(c.getTitle()).isEqualTo("Worker - task 11111111");
        assertThat(dto.getId()).isEqualTo("conv-task");
        // Exposed so the UI tells it from the agent's own conversation (both carry the agent id).
        assertThat(dto.getTaskId()).isEqualTo(TASK);
    }

    @Test
    @DisplayName("an ordinary conversation exposes no task id")
    void ordinaryConversationHasNoTaskId() {
        Conversation main = new Conversation(USER, "Worker", null, null);
        main.setAgentId(AGENT);
        assertThat(new ConversationMapper().toDto(main).getTaskId()).isNull();
    }

    @Test
    @DisplayName("later turns (review, retry): the same conversation is reused, nothing created")
    void reusesTaskConversation() {
        Conversation existing = new Conversation(USER, "Worker - task 11111111", null, null);
        existing.setId("conv-task");
        existing.setOrganizationId(ORG);
        existing.setAgentId(AGENT);
        existing.setTaskId(TASK);
        when(conversationRepository.findTaskConversations(ORG, AGENT, TASK)).thenReturn(List.of(existing));

        ConversationDto dto = service.findOrCreateTaskConversation(USER, ORG, AGENT, TASK, "ignored");

        assertThat(dto.getId()).isEqualTo("conv-task");
        verify(conversationRepository, never()).saveAndFlush(any(Conversation.class));
    }

    @Test
    @DisplayName("no task id, no agent id: refused (it would be an unkeyed conversation)")
    void refusesUnkeyedRequests() {
        assertThatThrownBy(() -> service.findOrCreateTaskConversation(USER, ORG, AGENT, " ", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.findTaskConversation(ORG, null, TASK))
                .isInstanceOf(IllegalArgumentException.class);
        verify(conversationRepository, never()).saveAndFlush(any(Conversation.class));
    }

    @Test
    @DisplayName("LC-066 race: the insert hits the unique index (V565), the winner's conversation is returned")
    void lostRaceReturnsTheWinner() {
        Conversation winner = new Conversation(USER, "Worker - task 11111111", null, null);
        winner.setId("conv-winner");
        winner.setOrganizationId(ORG);
        winner.setAgentId(AGENT);
        winner.setTaskId(TASK);
        when(conversationRepository.findTaskConversations(ORG, AGENT, TASK))
                .thenReturn(List.of())              // both callers saw none
                .thenReturn(List.of(winner));      // re-read after the violation
        when(conversationRepository.saveAndFlush(any(Conversation.class)))
                .thenThrow(new DataIntegrityViolationException("uq_conversations_task_per_agent"));

        ConversationDto dto = service.findOrCreateTaskConversation(USER, ORG, AGENT, TASK, "Worker - task 11111111");

        assertThat(dto.getId()).isEqualTo("conv-winner");
    }

    @Test
    @DisplayName("LC-066 race: a violation with no winner to read is not swallowed")
    void violationWithoutWinnerPropagates() {
        when(conversationRepository.findTaskConversations(ORG, AGENT, TASK)).thenReturn(List.of());
        when(conversationRepository.saveAndFlush(any(Conversation.class)))
                .thenThrow(new DataIntegrityViolationException("other constraint"));

        assertThatThrownBy(() -> service.findOrCreateTaskConversation(USER, ORG, AGENT, TASK, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("LC-066 race: two concurrent creates for the same task end on ONE conversation id")
    void twoConcurrentCreatesShareOneConversation() throws Exception {
        // A table with the V565 unique key: the second insert of (org, agent, task) is refused.
        List<Conversation> table = new CopyOnWriteArrayList<>();
        CountDownLatch bothLookedUp = new CountDownLatch(2);
        when(conversationRepository.findTaskConversations(ORG, AGENT, TASK)).thenAnswer(inv -> {
            List<Conversation> hits = List.copyOf(table);
            bothLookedUp.countDown();
            return hits;
        });
        java.util.concurrent.atomic.AtomicInteger ids = new java.util.concurrent.atomic.AtomicInteger();
        when(conversationRepository.saveAndFlush(any(Conversation.class))).thenAnswer(inv -> {
            // Both callers found nothing before either inserts: the find-then-insert race.
            assertThat(bothLookedUp.await(5, TimeUnit.SECONDS)).isTrue();
            Conversation c = inv.getArgument(0);
            synchronized (table) {
                boolean taken = table.stream().anyMatch(r -> ORG.equals(r.getOrganizationId())
                        && AGENT.equals(r.getAgentId()) && TASK.equals(r.getTaskId()));
                if (taken) {
                    throw new DataIntegrityViolationException("uq_conversations_task_per_agent");
                }
                c.setId("conv-" + ids.incrementAndGet());
                table.add(c);
            }
            return c;
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ConversationDto> a = pool.submit(() -> service.findOrCreateTaskConversation(USER, ORG, AGENT, TASK, "t"));
            Future<ConversationDto> b = pool.submit(() -> service.findOrCreateTaskConversation(USER, ORG, AGENT, TASK, "t"));
            String first = a.get(10, TimeUnit.SECONDS).getId();
            String second = b.get(10, TimeUnit.SECONDS).getId();

            assertThat(first).isEqualTo(second);
            assertThat(table).hasSize(1);
            verify(conversationRepository, times(2)).saveAndFlush(any(Conversation.class));
        } finally {
            pool.shutdownNow();
        }
    }
}
