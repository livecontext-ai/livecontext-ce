package com.apimarketplace.conversation.integration;

import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.entity.Message;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.common.storage.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.client.RestTemplate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for ConversationRepository using H2 in-memory database.
 * Tests JPA queries and database interactions without mocking.
 */
@IntegrationTest
@Import(IntegrationTestConfig.class)
class ConversationRepositoryIntegrationTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @MockitoBean
    private RestTemplate restTemplate;

    @MockitoBean
    private StorageService storageService;

    private static final String USER_ID = "user-123";
    private static final String OTHER_USER_ID = "user-456";

    @BeforeEach
    void setUp() {
        // Clear data between tests
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        entityManager.flush();
        entityManager.clear();
    }

    // ========================== Helper Methods ==========================

    private Conversation createConversation(String userId, String title, String model, String provider) {
        Conversation conv = new Conversation(userId, title, model, provider);
        conv.setActive(true);
        conv.setUpdatedAt(LocalDateTime.now());
        // V263 fail-loud: OrgScopedEntity requires organizationId on persist.
        // Stamp from userId since this helper persists directly without a request-bound thread.
        conv.setOrganizationId(userId);
        return conversationRepository.saveAndFlush(conv);
    }

    private Conversation createInactiveConversation(String userId, String title) {
        Conversation conv = new Conversation(userId, title, "gpt-4o", "openai");
        conv.setActive(false);
        conv.setUpdatedAt(LocalDateTime.now());
        // V263 fail-loud: OrgScopedEntity requires organizationId on persist.
        conv.setOrganizationId(userId);
        return conversationRepository.saveAndFlush(conv);
    }

    private Conversation createWorkflowConversation(String userId, String workflowId) {
        Conversation conv = new Conversation(userId, "Workflow Chat", "gpt-4o", "openai");
        conv.setActive(true);
        conv.setWorkflowId(workflowId);
        conv.setUpdatedAt(LocalDateTime.now());
        // V263 fail-loud: OrgScopedEntity requires organizationId on persist.
        conv.setOrganizationId(userId);
        return conversationRepository.saveAndFlush(conv);
    }

    private void addMessageToConversation(Conversation conversation, Message.MessageRole role, String content) {
        Message message = new Message(role, content);
        message.setTimestamp(java.time.Instant.now().toString());
        conversation.addMessage(message);
        messageRepository.saveAndFlush(message);
    }

    // ========================== Tests ==========================

    @Nested
    @DisplayName("Basic CRUD operations")
    class BasicCrud {

        @Test
        @DisplayName("should save and retrieve conversation by ID")
        void shouldSaveAndRetrieveConversation() {
            Conversation saved = createConversation(USER_ID, "Test Chat", "gpt-4o", "openai");

            entityManager.clear();
            Optional<Conversation> found = conversationRepository.findById(saved.getId());

            assertThat(found).isPresent();
            assertThat(found.get().getTitle()).isEqualTo("Test Chat");
            assertThat(found.get().getUserId()).isEqualTo(USER_ID);
            assertThat(found.get().getModel()).isEqualTo("gpt-4o");
            assertThat(found.get().getProvider()).isEqualTo("openai");
            assertThat(found.get().getActive()).isTrue();
        }

        @Test
        @DisplayName("should return empty for non-existent conversation")
        void shouldReturnEmptyForNonExistent() {
            Optional<Conversation> found = conversationRepository.findById("non-existent-id");
            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("should update conversation fields")
        void shouldUpdateConversation() {
            Conversation saved = createConversation(USER_ID, "Original Title", "gpt-4o", "openai");

            saved.setTitle("Updated Title");
            saved.setModel("gpt-5");
            conversationRepository.saveAndFlush(saved);

            entityManager.clear();
            Conversation updated = conversationRepository.findById(saved.getId()).orElseThrow();
            assertThat(updated.getTitle()).isEqualTo("Updated Title");
            assertThat(updated.getModel()).isEqualTo("gpt-5");
        }

        @Test
        @DisplayName("should delete conversation permanently")
        void shouldDeleteConversation() {
            Conversation saved = createConversation(USER_ID, "To Delete", "gpt-4o", "openai");
            String id = saved.getId();

            conversationRepository.deleteById(id);
            conversationRepository.flush();

            assertThat(conversationRepository.findById(id)).isEmpty();
        }
    }

    @Nested
    @DisplayName("findByUserIdAndActiveTrueOrderByUpdatedAtDesc")
    class FindActiveByUser {

        @Test
        @DisplayName("should return only active conversations for user")
        void shouldReturnOnlyActiveConversations() {
            createConversation(USER_ID, "Active 1", "gpt-4o", "openai");
            createConversation(USER_ID, "Active 2", "gpt-4o", "openai");
            createInactiveConversation(USER_ID, "Inactive");
            createConversation(OTHER_USER_ID, "Other User", "gpt-4o", "openai");

            Page<Conversation> result = conversationRepository
                    .findByUserIdAndActiveTrueOrderByUpdatedAtDesc(USER_ID, PageRequest.of(0, 10));

            assertThat(result.getContent()).hasSize(2);
            assertThat(result.getContent()).allMatch(c -> c.getActive());
            assertThat(result.getContent()).allMatch(c -> c.getUserId().equals(USER_ID));
        }

        @Test
        @DisplayName("should support pagination")
        void shouldSupportPagination() {
            for (int i = 0; i < 5; i++) {
                createConversation(USER_ID, "Chat " + i, "gpt-4o", "openai");
            }

            Page<Conversation> firstPage = conversationRepository
                    .findByUserIdAndActiveTrueOrderByUpdatedAtDesc(USER_ID, PageRequest.of(0, 2));
            Page<Conversation> secondPage = conversationRepository
                    .findByUserIdAndActiveTrueOrderByUpdatedAtDesc(USER_ID, PageRequest.of(1, 2));

            assertThat(firstPage.getContent()).hasSize(2);
            assertThat(secondPage.getContent()).hasSize(2);
            assertThat(firstPage.getTotalElements()).isEqualTo(5);
            assertThat(firstPage.getTotalPages()).isEqualTo(3);
        }

        @Test
        @DisplayName("should return empty page for user with no conversations")
        void shouldReturnEmptyForNoConversations() {
            Page<Conversation> result = conversationRepository
                    .findByUserIdAndActiveTrueOrderByUpdatedAtDesc("no-user", PageRequest.of(0, 10));

            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
        }
    }

    @Nested
    @DisplayName("Search queries")
    class SearchQueries {

        @Test
        @DisplayName("should find conversations by title containing search term (case insensitive)")
        void shouldFindByTitleContaining() {
            createConversation(USER_ID, "Weather forecast discussion", "gpt-4o", "openai");
            createConversation(USER_ID, "Code review session", "gpt-4o", "openai");
            createConversation(USER_ID, "WEATHER alert system", "gpt-4o", "openai");

            Page<Conversation> result = conversationRepository
                    .findByUserIdAndTitleContainingIgnoreCase(USER_ID, "weather", PageRequest.of(0, 10));

            assertThat(result.getContent()).hasSize(2);
        }

        @Test
        @DisplayName("should find conversations by message content")
        void shouldFindByMessageContent() {
            Conversation conv1 = createConversation(USER_ID, "Chat 1", "gpt-4o", "openai");
            addMessageToConversation(conv1, Message.MessageRole.USER, "How to deploy a Kubernetes cluster?");

            Conversation conv2 = createConversation(USER_ID, "Chat 2", "gpt-4o", "openai");
            addMessageToConversation(conv2, Message.MessageRole.USER, "What is machine learning?");

            Conversation conv3 = createConversation(USER_ID, "Chat 3", "gpt-4o", "openai");
            addMessageToConversation(conv3, Message.MessageRole.USER, "Kubernetes best practices");

            entityManager.flush();
            entityManager.clear();

            Page<Conversation> result = conversationRepository
                    .findByUserIdAndMessageContentContaining(USER_ID, "kubernetes", PageRequest.of(0, 10));

            assertThat(result.getContent()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Workflow conversations")
    class WorkflowConversations {

        @Test
        @DisplayName("should find conversation by user and workflow ID")
        void shouldFindByUserAndWorkflowId() {
            String workflowId = "wf-001";
            createWorkflowConversation(USER_ID, workflowId);

            Optional<Conversation> result = conversationRepository
                    .findByUserIdAndWorkflowIdAndActiveTrue(USER_ID, workflowId);

            assertThat(result).isPresent();
            assertThat(result.get().getWorkflowId()).isEqualTo(workflowId);
        }

        @Test
        @DisplayName("should not find inactive workflow conversation")
        void shouldNotFindInactiveWorkflowConversation() {
            String workflowId = "wf-002";
            Conversation conv = createWorkflowConversation(USER_ID, workflowId);
            conv.setActive(false);
            conversationRepository.saveAndFlush(conv);

            Optional<Conversation> result = conversationRepository
                    .findByUserIdAndWorkflowIdAndActiveTrue(USER_ID, workflowId);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should find all conversations by workflow ID")
        void shouldFindAllByWorkflowId() {
            String workflowId = "wf-003";
            createWorkflowConversation(USER_ID, workflowId);
            createWorkflowConversation(OTHER_USER_ID, workflowId);

            List<Conversation> result = conversationRepository.findByWorkflowId(workflowId);

            assertThat(result).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Listing filter - empty workflow conversations")
    class EmptyWorkflowConversationListingFilter {

        @Test
        @DisplayName("excludes a message-less workflow conversation from the active sidebar listing")
        void excludesEmptyWorkflowConversation() {
            // helpers stamp organizationId = userId, so the strict-org finder
            // reads this row through orgId = USER_ID.
            createWorkflowConversation(USER_ID, "wf-empty"); // no messages
            entityManager.flush();
            entityManager.clear();

            Page<Conversation> result = conversationRepository
                    .findByOrganizationIdStrictAndActiveTrueOrderByUpdatedAtDesc(USER_ID, PageRequest.of(0, 10));

            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
        }

        @Test
        @DisplayName("includes the workflow conversation once it carries at least one message (self-healing)")
        void includesWorkflowConversationWithMessage() {
            Conversation conv = createWorkflowConversation(USER_ID, "wf-used");
            addMessageToConversation(conv, Message.MessageRole.USER, "hello");
            entityManager.flush();
            entityManager.clear();

            Page<Conversation> result = conversationRepository
                    .findByOrganizationIdStrictAndActiveTrueOrderByUpdatedAtDesc(USER_ID, PageRequest.of(0, 10));

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getWorkflowId()).isEqualTo("wf-used");
        }

        @Test
        @DisplayName("keeps an empty NON-workflow conversation (the filter only targets workflow conversations)")
        void keepsEmptyNonWorkflowConversation() {
            createConversation(USER_ID, "Plain empty chat", "gpt-4o", "openai"); // no workflowId, no messages
            entityManager.flush();
            entityManager.clear();

            Page<Conversation> result = conversationRepository
                    .findByOrganizationIdStrictAndActiveTrueOrderByUpdatedAtDesc(USER_ID, PageRequest.of(0, 10));

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getWorkflowId()).isNull();
        }

        @Test
        @DisplayName("active count matches the listing - empty workflow conversations are not counted")
        void countExcludesEmptyWorkflowConversation() {
            createConversation(USER_ID, "Plain chat", "gpt-4o", "openai");      // counted (non-workflow)
            Conversation used = createWorkflowConversation(USER_ID, "wf-used"); // counted (has a message)
            addMessageToConversation(used, Message.MessageRole.USER, "hi");
            createWorkflowConversation(USER_ID, "wf-empty");                    // NOT counted (empty workflow)
            entityManager.flush();
            entityManager.clear();

            long count = conversationRepository.countByOrganizationIdStrictAndActiveTrue(USER_ID);

            assertThat(count).isEqualTo(2);
        }

        @Test
        @DisplayName("the empty workflow conversation is still reusable by the find-or-create lookup (self-healing)")
        void emptyWorkflowConversationStillFoundByIdempotencyLookup() {
            createWorkflowConversation(USER_ID, "wf-empty"); // hidden from the listing, but must stay reusable
            entityManager.flush();
            entityManager.clear();

            // The listing hides it, but the workflow-bound find-or-create lookup must
            // still return it so the next real message lands on the SAME row (which
            // then reappears in the listing) instead of minting a second conversation.
            Optional<Conversation> reused = conversationRepository
                    .findByOrganizationIdStrictAndWorkflowIdAndActiveTrue(USER_ID, "wf-empty");

            assertThat(reused).isPresent();
            assertThat(reused.get().getWorkflowId()).isEqualTo("wf-empty");
        }

        @Test
        @DisplayName("paginated total-elements counts only the visible rows on a mixed dataset (derived count stays consistent)")
        void paginatedTotalElementsExcludesEmptyWorkflowConversations() {
            createConversation(USER_ID, "Plain A", "gpt-4o", "openai");          // visible (non-workflow)
            createConversation(USER_ID, "Plain B", "gpt-4o", "openai");          // visible (non-workflow)
            Conversation used = createWorkflowConversation(USER_ID, "wf-used");  // visible (has a message)
            addMessageToConversation(used, Message.MessageRole.USER, "hi");
            createWorkflowConversation(USER_ID, "wf-empty-1");                   // hidden (empty workflow)
            createWorkflowConversation(USER_ID, "wf-empty-2");                   // hidden (empty workflow)
            entityManager.flush();
            entityManager.clear();

            // Page size 2 over 3 visible rows: the DERIVED count query must report
            // 3 (the filtered total), not 5, so pagination stays correct.
            Page<Conversation> page = conversationRepository
                    .findByOrganizationIdStrictAndActiveTrueOrderByUpdatedAtDesc(USER_ID, PageRequest.of(0, 2));

            assertThat(page.getTotalElements()).isEqualTo(3);
            assertThat(page.getTotalPages()).isEqualTo(2);
            assertThat(page.getContent()).hasSize(2);
        }

        @Test
        @DisplayName("excludes empty workflow conversations from the include-inactive listing too")
        void excludesFromIncludeInactiveListing() {
            createWorkflowConversation(USER_ID, "wf-empty-active");            // active + empty → excluded
            Conversation inactive = createWorkflowConversation(USER_ID, "wf-empty-inactive");
            inactive.setActive(false);
            conversationRepository.saveAndFlush(inactive);                     // inactive + empty → excluded
            createConversation(USER_ID, "Plain", "gpt-4o", "openai");          // kept (non-workflow)
            entityManager.flush();
            entityManager.clear();

            Page<Conversation> result = conversationRepository
                    .findByOrganizationIdStrictOrderByUpdatedAtDesc(USER_ID, PageRequest.of(0, 10));

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getWorkflowId()).isNull();
        }

        @Test
        @DisplayName("excludes empty workflow conversations from strict-org title search")
        void excludesEmptyWorkflowConversationFromTitleSearch() {
            Conversation emptyWorkflow = createWorkflowConversation(USER_ID, "wf-empty-title");
            Conversation usedWorkflow = createWorkflowConversation(USER_ID, "wf-used-title");
            addMessageToConversation(usedWorkflow, Message.MessageRole.USER, "hello from workflow");
            Conversation plain = createConversation(USER_ID, "Workflow planning chat", "gpt-4o", "openai");
            entityManager.flush();
            entityManager.clear();

            Page<Conversation> result = conversationRepository
                    .findByOrganizationIdStrictAndTitleContainingIgnoreCase(USER_ID, "workflow", PageRequest.of(0, 10));

            assertThat(result.getContent()).extracting(Conversation::getId)
                    .contains(usedWorkflow.getId(), plain.getId())
                    .doesNotContain(emptyWorkflow.getId());
            assertThat(result.getTotalElements()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Count queries")
    class CountQueries {

        @Test
        @DisplayName("should count active conversations for user")
        void shouldCountActiveConversations() {
            createConversation(USER_ID, "Active 1", "gpt-4o", "openai");
            createConversation(USER_ID, "Active 2", "gpt-4o", "openai");
            createInactiveConversation(USER_ID, "Inactive");
            createConversation(OTHER_USER_ID, "Other User", "gpt-4o", "openai");

            long count = conversationRepository.countByUserIdAndActiveTrue(USER_ID);

            assertThat(count).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Conversation with messages")
    class ConversationWithMessages {

        @Test
        @DisplayName("should persist conversation with messages via cascade")
        void shouldPersistConversationWithMessages() {
            Conversation conv = createConversation(USER_ID, "Chat with messages", "gpt-4o", "openai");

            addMessageToConversation(conv, Message.MessageRole.USER, "Hello!");
            addMessageToConversation(conv, Message.MessageRole.ASSISTANT, "Hi there!");

            entityManager.clear();

            Conversation loaded = conversationRepository.findById(conv.getId()).orElseThrow();
            assertThat(loaded.getMessages()).hasSize(2);
            assertThat(loaded.getMessages().get(0).getRole()).isEqualTo(Message.MessageRole.USER);
            assertThat(loaded.getMessages().get(1).getRole()).isEqualTo(Message.MessageRole.ASSISTANT);
        }

        @Test
        @DisplayName("should bulk delete all messages for one conversation")
        void shouldBulkDeleteMessagesByConversationId() {
            Conversation target = createConversation(USER_ID, "Target chat", "gpt-4o", "openai");
            Conversation other = createConversation(USER_ID, "Other chat", "gpt-4o", "openai");
            addMessageToConversation(target, Message.MessageRole.USER, "delete me");
            addMessageToConversation(target, Message.MessageRole.ASSISTANT, "delete me too");
            addMessageToConversation(other, Message.MessageRole.USER, "keep me");
            entityManager.flush();
            entityManager.clear();

            int deleted = messageRepository.deleteByConversationId(target.getId());
            entityManager.flush();
            entityManager.clear();

            assertThat(deleted).isEqualTo(2);
            assertThat(messageRepository.findByConversationIdOrderByCreatedAtAsc(target.getId())).isEmpty();
            assertThat(messageRepository.findByConversationIdOrderByCreatedAtAsc(other.getId())).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Pending actions and approved services")
    class PendingActionsAndApprovals {

        @Test
        @DisplayName("should persist pending action as JSON")
        void shouldPersistPendingAction() {
            Conversation conv = createConversation(USER_ID, "Pending Action Chat", "gpt-4o", "openai");

            conv.setPendingAction(java.util.Map.of(
                    "waiting_for", "credential:gmail",
                    "original_request", "Check my Gmail"
            ));
            conversationRepository.saveAndFlush(conv);
            entityManager.clear();

            Conversation loaded = conversationRepository.findById(conv.getId()).orElseThrow();
            assertThat(loaded.hasPendingAction()).isTrue();
            assertThat(loaded.getWaitingFor()).isEqualTo("credential:gmail");
        }

        @Test
        @DisplayName("should persist approved services as JSON")
        void shouldPersistApprovedServices() {
            Conversation conv = createConversation(USER_ID, "Approved Services Chat", "gpt-4o", "openai");

            conv.approveService("gmail");
            conv.approveService("slack");
            conversationRepository.saveAndFlush(conv);
            entityManager.clear();

            Conversation loaded = conversationRepository.findById(conv.getId()).orElseThrow();
            assertThat(loaded.isServiceApproved("gmail")).isTrue();
            assertThat(loaded.isServiceApproved("slack")).isTrue();
            assertThat(loaded.isServiceApproved("stripe")).isFalse();
        }
    }
}
