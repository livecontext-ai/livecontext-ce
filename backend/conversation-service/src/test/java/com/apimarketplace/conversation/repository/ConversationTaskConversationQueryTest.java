package com.apimarketplace.conversation.repository;

import com.apimarketplace.conversation.entity.Conversation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LC-066 (review finding r5-2): a RESTRICTED delegated task's turns run in a conversation of their
 * own (task_id set), and the agent's main-conversation lookup must never return it, whatever the
 * order of creation: otherwise the agent's next chat, schedule or normal task would land in the
 * restricted task conversation and be refused or tagged. Parsed and executed on H2 (same pattern as
 * {@link ConversationKindQueryTest}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ConversationRepository - per-task conversations (LC-066)")
class ConversationTaskConversationQueryTest {

    private static final String ORG = "org-1";
    private static final String LONELY_AGENT = "agent-only-task-conversation";
    private static final String AGENT = "agent-with-both";
    private static final String TASK = "11111111-1111-1111-1111-111111111111";

    private EntityManagerFactory emf;
    private EntityManager em;
    private ConversationRepository repository;

    @BeforeAll
    void setUp() {
        emf = Persistence.createEntityManagerFactory("conversation-task-h2-pu");
        em = emf.createEntityManager();
        repository = new JpaRepositoryFactory(em).getRepository(ConversationRepository.class);

        em.getTransaction().begin();
        // An agent whose ONLY conversation is a task conversation (its first job was a RESTRICTED task).
        persist("task-only", LONELY_AGENT, TASK, false);
        // An agent with a task conversation created BEFORE its main conversation.
        persist("task-first", AGENT, TASK, false);
        persist("main", AGENT, null, true);
        em.getTransaction().commit();
    }

    @AfterAll
    void tearDown() {
        if (em != null && em.isOpen()) em.close();
        if (emf != null && emf.isOpen()) emf.close();
    }

    private void persist(String title, String agentId, String taskId, boolean memoryEnabled) {
        Conversation c = new Conversation("user-1", title, null, null);
        c.setOrganizationId(ORG);
        c.setAgentId(agentId);
        c.setTaskId(taskId);
        c.setMemoryEnabled(memoryEnabled);
        c.setActive(true);
        c.setUpdatedAt(LocalDateTime.now());
        em.persist(c);
    }

    @Test
    @DisplayName("the agent-conversation lookup never returns a task conversation, even the only one")
    void mainLookupIgnoresTaskConversations() {
        assertThat(repository.findByOrganizationIdStrictAndAgentIdAndActiveTrueOrderByCreatedAtAsc(ORG, LONELY_AGENT))
                .isEmpty();
        List<Conversation> main = repository.findByOrganizationIdStrictAndAgentIdAndActiveTrueOrderByCreatedAtAsc(ORG, AGENT);
        assertThat(main).extracting(Conversation::getTitle).containsExactly("main");
    }

    @Test
    @DisplayName("the task lookup returns the task's conversation for that agent only")
    void taskLookupReturnsTheTaskConversation() {
        assertThat(repository.findTaskConversations(ORG, AGENT, TASK))
                .extracting(Conversation::getTitle).containsExactly("task-first");
        assertThat(repository.findTaskConversations(ORG, AGENT, "22222222-2222-2222-2222-222222222222")).isEmpty();
        assertThat(repository.findTaskConversations("other-org", AGENT, TASK)).isEmpty();
    }
}
