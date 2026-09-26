package com.apimarketplace.conversation.service;

import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.entity.Message;
import com.apimarketplace.conversation.mapper.MessageMapper;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.MessageAttachmentRepository;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link MessageService#persistAttemptAndError} against a REAL JPA session, behind the REAL Spring
 * transaction proxy, with no open-in-view: the exact conditions of the sync chat 402 path in prod.
 *
 * <p><b>The bug this pins (prod, 2026-09-25).</b> The method called {@code addMessage} on
 * {@code this}, so the proxy never saw the call and {@code addMessage}'s {@code @Transactional}
 * did not apply. {@code addMessage} then appended to the LAZY {@code Conversation.messages}
 * collection of an entity loaded with no session open, and both writes died on
 * {@code LazyInitializationException}. The conversation stayed empty, so a user whose scheduled
 * run was skipped for lack of credits was never told why.
 *
 * <p><b>Why the Mockito suite could not see it.</b> {@code MessageServiceTest} builds the service
 * with {@code new} over mocked repositories: there is no proxy to bypass and no lazy collection to
 * initialise, so it was green on the broken code. Only a proxied bean over a real session can fail
 * the way production did. H2 rather than a testcontainer so the default build runs it (same trade
 * as {@code ConversationKindQueryTest}); the defect is Hibernate session scoping, not SQL dialect.
 */
@SpringJUnitConfig(MessageServicePersistAttemptJpaTest.Config.class)
@DisplayName("MessageService.persistAttemptAndError - real JPA session behind the transaction proxy")
class MessageServicePersistAttemptJpaTest {

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(
            basePackageClasses = ConversationRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = {ConversationRepository.class, MessageRepository.class}))
    static class Config {

        @Bean
        DataSource dataSource() {
            // Own in-memory database: the persistence unit's URL is shared with
            // ConversationKindQueryTest, whose hbm2ddl=create would drop our tables mid-run.
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:persist-attempt;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                            + "INIT=CREATE SCHEMA IF NOT EXISTS conversation", "sa", "");
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
            // Maps exactly Conversation + Message, the association closure addMessage walks.
            emf.setPersistenceUnitName("conversation-kind-h2-pu");
            emf.setDataSource(dataSource);
            return emf;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }

        @Bean
        MessageService messageService(ConversationRepository conversationRepository,
                                      MessageRepository messageRepository,
                                      PlatformTransactionManager transactionManager) {
            return new MessageService(conversationRepository, messageRepository,
                    mock(MessageAttachmentRepository.class), new MessageMapper(), mock(EventBus.class),
                    new ObjectMapper(), mock(StorageBreakdownService.class), null, transactionManager);
        }
    }

    private String convId;

    @Autowired private MessageService messageService;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;

    @BeforeEach
    void seedConversation() {
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            messageRepository.deleteAll();
            conversationRepository.deleteAll();
            Conversation conv = new Conversation("user-1", "Scheduled agent", "model", "provider");
            conv.setOrganizationId("org-1");
            conv.setActive(true);
            convId = conversationRepository.save(conv).getId();
        });
    }

    @Test
    @DisplayName("the bean under test is the transactional proxy, so a self-invocation really bypasses it")
    void serviceIsProxied() {
        // Guards the test itself: without a proxy there is nothing to bypass and the
        // regression below could never fail, whatever the production code does.
        assertThat(AopUtils.isAopProxy(messageService)).isTrue();
    }

    @Test
    @DisplayName("refused run: the user prompt AND the typed error are both persisted to the conversation")
    void persistsBothLinesWithoutAnOpenSession() {
        messageService.persistAttemptAndError(convId, "summarise my inbox",
                "[Error] Insufficient credits - this scheduled run was skipped.");

        List<Message> stored = storedMessages();
        assertThat(stored).extracting(Message::getRole)
                .containsExactly(Message.MessageRole.USER, Message.MessageRole.ASSISTANT);
        assertThat(stored).extracting(Message::getContent)
                .containsExactly("summarise my inbox",
                        "[Error] Insufficient credits - this scheduled run was skipped.");
    }

    @Test
    @DisplayName("refused run: the conversation's updated_at is bumped so it resurfaces in the sidebar")
    void bumpsConversationUpdatedAt() {
        // @UpdateTimestamp would overwrite a value set through JPA, so age the row in SQL: only a
        // real write by persistAttemptAndError can bring updated_at back to the present.
        LocalDateTime longAgo = LocalDateTime.of(2020, 1, 1, 0, 0);
        new JdbcTemplate(dataSource).update(
                "UPDATE conversation.conversations SET updated_at = ? WHERE id = ?", longAgo, convId);

        messageService.persistAttemptAndError(convId, "prompt", "[Error] x");

        LocalDateTime updatedAt = conversationRepository.findById(convId).orElseThrow().getUpdatedAt();
        assertThat(updatedAt).isAfter(longAgo.plusYears(1));
    }

    @Test
    @DisplayName("called inside a caller's transaction that later rolls back, both lines still survive")
    void linesSurviveAnOuterRollback() {
        // The one thing REQUIRES_NEW buys over REQUIRED: the refusal trace is committed on its own
        // and cannot be erased by whatever the caller does with its transaction afterwards.
        new TransactionTemplate(transactionManager).executeWithoutResult(outer -> {
            messageService.persistAttemptAndError(convId, "summarise my inbox", "[Error] Insufficient credits");
            outer.setRollbackOnly();
        });

        assertThat(storedMessages()).extracting(Message::getRole)
                .containsExactly(Message.MessageRole.USER, Message.MessageRole.ASSISTANT);
    }

    @Test
    @DisplayName("a rejected user line does not roll back or block the error line (one transaction per message)")
    void rejectedUserLineDoesNotBlockErrorLine() {
        // A blank prompt is refused by addMessage's validation (USER needs content), so the first
        // write fails for real inside its own transaction. The error line must still land.
        messageService.persistAttemptAndError(convId, "   ", "[Error] Insufficient credits");

        List<Message> stored = storedMessages();
        assertThat(stored).extracting(Message::getRole).containsExactly(Message.MessageRole.ASSISTANT);
        assertThat(stored).extracting(Message::getContent).containsExactly("[Error] Insufficient credits");
    }

    private List<Message> storedMessages() {
        return new TransactionTemplate(transactionManager).execute(s -> messageRepository.findAll().stream()
                .filter(m -> convId.equals(m.getConversation().getId()))
                .sorted(Comparator.comparing(Message::getCreatedAt).thenComparing(Message::getTimestamp))
                .toList());
    }
}
