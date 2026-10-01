package com.apimarketplace.agent.service;

import com.apimarketplace.agent.config.AgentDefaultsConfig;
import com.apimarketplace.agent.config.AgentNameConflictExceptionHandler;
import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.repository.AgentMetricsAggregationRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Agent names against the REAL V269 index, on a real Postgres, through the real Spring proxy.
 *
 * <p><b>Why this class exists.</b> The unit tests mock the repository, so the workspace lock
 * ({@code pg_advisory_xact_lock}) never runs there, the prefix query is never parsed, and the
 * lost-race translation only ever sees a hand-built error. Here all three are real: the index is
 * created by hand exactly as V269 declares it (hbm2ddl cannot express a PARTIAL unique index, so
 * without it every assertion below would pass against a table that enforces nothing), and the
 * service runs inside Spring's transactional proxy with a real EntityManager.
 *
 * <p>It runs against the scratch database named by {@code AGENT_TEST_PG_URL} (provisioned on the
 * CI step that carries the postgres service); with {@code CI} set and no URL it FAILS rather than
 * skipping. Like {@code AgentRunWindowQueryPostgresTest}, it builds its table in a schema of its
 * OWN, never in {@code agent}, because other Postgres tests share that database.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("agent name allocation - real Postgres, real V269 index, real lock")
class AgentNameAllocationPostgresTest {

    private static final String URL = System.getenv("AGENT_TEST_PG_URL");
    private static final String USER = System.getenv().getOrDefault("AGENT_TEST_PG_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("AGENT_TEST_PG_PASSWORD", "postgres");

    private static final String SCHEMA = "agent_name_alloc_probe";
    private static final String TENANT = "tenant-1";
    private static final String ORG = "44444444-4444-4444-8444-444444444444";

    private AnnotationConfigApplicationContext context;
    private AgentService agentService;
    private AgentRepository agentRepository;
    private TransactionTemplate tx;

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = AgentRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = AgentRepository.class))
    static class Wiring {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(URL, USER, PASSWORD);
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
            emf.setDataSource(dataSource);
            // Only the agents table: scanning the whole domain would create the entities that
            // pin schema "agent" in the shared database.
            emf.setManagedTypes(PersistenceManagedTypes.of(AgentEntity.class.getName()));
            emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            emf.setJpaPropertyMap(Map.of(
                    "hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect",
                    "hibernate.default_schema", SCHEMA,
                    "hibernate.hbm2ddl.auto", "create"));
            return emf;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }

        @Bean
        AgentService agentService(AgentRepository agentRepository) {
            OrgAccessGuard guard = Mockito.mock(OrgAccessGuard.class);
            Mockito.when(guard.canWrite(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                    .thenReturn(true);
            return new AgentService(agentRepository, new AgentDefaultsConfig(), guard,
                    Mockito.mock(AgentMetricsAggregationRepository.class), null, null);
        }
    }

    @BeforeAll
    void setUp() throws Exception {
        if (URL == null || URL.isBlank()) {
            if (System.getenv("CI") != null) {
                fail("AGENT_TEST_PG_URL is not set on CI: this class is the only proof that the agent-name "
                        + "lock and the V269 translation work on a real Postgres, it must not be skipped.");
            }
            org.junit.jupiter.api.Assumptions.abort("AGENT_TEST_PG_URL not set (local run without Postgres)");
        }
        String database = URL.substring(URL.lastIndexOf('/') + 1).split("\\?")[0];
        if (!database.toLowerCase(Locale.ROOT).contains("test")) {
            throw new IllegalStateException("AGENT_TEST_PG_URL must name a scratch database containing 'test', got "
                    + database);
        }
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            s.execute("CREATE SCHEMA " + SCHEMA);
        }
        context = new AnnotationConfigApplicationContext(Wiring.class);
        agentService = context.getBean(AgentService.class);
        agentRepository = context.getBean(AgentRepository.class);
        tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement s = c.createStatement()) {
            // Exactly V269's index, in this class's schema.
            s.execute("CREATE UNIQUE INDEX " + AgentService.AGENT_NAME_UNIQUE_INDEX + " ON " + SCHEMA
                    + ".agents (organization_id, name) WHERE is_active IS TRUE");
        }
    }

    @AfterAll
    void tearDown() throws Exception {
        if (context != null) context.close();
        if (URL != null && !URL.isBlank()) {
            try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
                 Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            }
        }
    }

    @BeforeEach
    void clean() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("DELETE FROM " + SCHEMA + ".agents");
        }
    }

    private AgentEntity create(String name) {
        return agentService.createAgent(TENANT, name, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, ORG, null, null);
    }

    private List<String> activeNames() {
        return agentRepository.findActiveNamesByOrganizationIdStrictAndNamePrefix(ORG, "%");
    }

    @Test
    @DisplayName("regression: a clone waits for a concurrent clone of the same agent instead of racing it to the index")
    void concurrentClonesAreSerializedByTheLock() throws Exception {
        UUID source = create("X").getId();
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        // Clone #1 runs inside an outer transaction held open after its insert, so its name
        // lock is held exactly where a slow commit would hold it.
        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
            String name = agentService.cloneAgent(source, TENANT, null, ORG).getName();
            agentRepository.flush();
            firstInserted.countDown();
            try {
                releaseFirst.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return name;
        }));
        assertThat(firstInserted.await(30, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<String> second = CompletableFuture.supplyAsync(
                () -> agentService.cloneAgent(source, TENANT, null, ORG).getName());

        // Without the lock, clone #2 reads "X (Copy)" as free (clone #1 is uncommitted), then
        // blocks on the unique index and dies with a duplicate key once #1 commits. Verified by
        // disabling lockAgentNameScope: second.get() then throws DataIntegrityViolationException.
        Thread.sleep(1_000);
        assertThat(second).as("the second clone must wait on the workspace name lock").isNotDone();

        releaseFirst.countDown();
        assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo("X (Copy)");
        assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo("X (Copy) (2)");
        assertThat(activeNames()).containsExactlyInAnyOrder("X", "X (Copy)", "X (Copy) (2)");
    }

    @Test
    @DisplayName("the conversation-service call runs after commit: row visible, name lock free, id written back")
    void conversationIsAttachedAfterCommitOutsideTheLock() throws Exception {
        var conversations = Mockito.mock(com.apimarketplace.conversation.client.ConversationClient.class);
        Object target = org.springframework.test.util.AopTestUtils.getTargetObject(agentService);
        ReflectionTestUtils.setField(target, "conversationServiceClient", conversations);
        UUID conversationId = UUID.randomUUID();
        boolean[] seen = new boolean[2];
        Mockito.when(conversations.findOrCreateAgentConversation(Mockito.anyString(), Mockito.eq(TENANT),
                Mockito.eq("Scout"), Mockito.eq(ORG))).thenAnswer(inv -> {
            try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
                 Statement s = c.createStatement()) {
                var rs = s.executeQuery("SELECT count(*) FROM " + SCHEMA + ".agents WHERE id = '"
                        + inv.getArgument(0) + "'");
                rs.next();
                seen[0] = rs.getInt(1) == 1; // committed, visible to another connection
                c.setAutoCommit(false);
                var lock = s.executeQuery("SELECT pg_try_advisory_xact_lock(hashtext('agent-name:org:" + ORG + "'))");
                lock.next();
                seen[1] = lock.getBoolean(1); // nobody holds the workspace name lock any more
                c.rollback();
            }
            return conversationId.toString();
        });
        try {
            AgentEntity created = create("Scout");

            assertThat(seen[0]).as("the agent row is committed before the remote call").isTrue();
            assertThat(seen[1]).as("the workspace name lock is released before the remote call").isTrue();
            assertThat(created.getConversationId()).isEqualTo(conversationId);
            assertThat(agentRepository.findById(created.getId()).orElseThrow().getConversationId())
                    .as("written back in a transaction of its own").isEqualTo(conversationId);
        } finally {
            ReflectionTestUtils.setField(target, "conversationServiceClient", null);
        }
    }

    /** Installs a conversation client on the service behind the proxy; returns it for stubbing. */
    private com.apimarketplace.conversation.client.ConversationClient installConversationClient() {
        var conversations = Mockito.mock(com.apimarketplace.conversation.client.ConversationClient.class);
        ReflectionTestUtils.setField((Object) org.springframework.test.util.AopTestUtils.<Object>getTargetObject(agentService),
                "conversationServiceClient", conversations);
        return conversations;
    }

    private void removeConversationClient() {
        ReflectionTestUtils.setField((Object) org.springframework.test.util.AopTestUtils.<Object>getTargetObject(agentService),
                "conversationServiceClient", null);
    }

    private UUID storedConversationId(UUID agentId) {
        return agentRepository.findById(agentId).orElseThrow().getConversationId();
    }

    @Test
    @DisplayName("the conversation service failing after commit leaves the agent created, without a conversation id")
    void conversationServiceFailureKeepsTheAgent() {
        var conversations = installConversationClient();
        Mockito.when(conversations.findOrCreateAgentConversation(Mockito.anyString(), Mockito.any(),
                Mockito.any(), Mockito.any())).thenThrow(new IllegalStateException("conversation-service down"));
        try {
            AgentEntity created = create("Scout");

            assertThat(created.getId()).isNotNull();
            assertThat(created.getConversationId()).isNull();
            assertThat(storedConversationId(created.getId())).isNull();
        } finally {
            removeConversationClient();
        }
    }

    @Test
    @DisplayName("a write-back that updates 0 rows (already linked meanwhile) neither overwrites the row nor claims the id")
    void writeBackThatMissesClaimsNothing() {
        var conversations = installConversationClient();
        UUID linkedMeanwhile = UUID.randomUUID();
        UUID offered = UUID.randomUUID();
        Mockito.when(conversations.findOrCreateAgentConversation(Mockito.anyString(), Mockito.any(),
                Mockito.any(), Mockito.any())).thenAnswer(inv -> {
            // Someone links the row first (committed, from another connection).
            try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
                 Statement s = c.createStatement()) {
                s.execute("UPDATE " + SCHEMA + ".agents SET conversation_id = '" + linkedMeanwhile
                        + "' WHERE id = '" + inv.getArgument(0) + "'");
            }
            return offered.toString();
        });
        try {
            AgentEntity created = create("Scout");

            assertThat(created.getConversationId()).as("the returned entity never claims an id it did not write")
                    .isNull();
            assertThat(storedConversationId(created.getId())).isEqualTo(linkedMeanwhile);
        } finally {
            removeConversationClient();
        }
    }

    @Test
    @DisplayName("inside a caller's transaction the conversation is attached inline: the returned entity carries it")
    void callerTransactionAttachesInline() {
        var conversations = installConversationClient();
        UUID conversationId = UUID.randomUUID();
        Mockito.when(conversations.findOrCreateAgentConversation(Mockito.anyString(), Mockito.eq(TENANT),
                Mockito.eq("Inline"), Mockito.eq(ORG))).thenReturn(conversationId.toString());
        try {
            AgentEntity created = tx.execute(status -> create("Inline"));

            assertThat(created.getConversationId()).isEqualTo(conversationId);
            assertThat(storedConversationId(created.getId())).isEqualTo(conversationId);
        } finally {
            removeConversationClient();
        }
    }

    @Test
    @DisplayName("two marketplace installs of the same agent in one workspace: the lock holds inside the installer's transaction")
    void concurrentInstallsAreSerialized() throws Exception {
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        // The shape of InternalAgentController.cloneFromSnapshot: one transaction that
        // allocates the publisher's name, inserts, then keeps working (skills) before commit.
        java.util.function.Supplier<String> install = () -> tx.execute(status -> {
            AgentEntity row = new AgentEntity();
            row.setTenantId(TENANT);
            row.setOrganizationId(ORG);
            row.setIsActive(true);
            row.setName(agentService.allocateAgentName(ORG, "Acquired"));
            agentRepository.saveAndFlush(row);
            return row.getName();
        });
        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
            String name = install.get();
            firstInserted.countDown();
            try {
                releaseFirst.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return name;
        }));
        assertThat(firstInserted.await(30, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<String> second = CompletableFuture.supplyAsync(install);

        Thread.sleep(1_000);
        assertThat(second).as("the second install must wait on the workspace name lock").isNotDone();
        releaseFirst.countDown();

        assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo("Acquired");
        assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo("Acquired (2)");
    }

    @Test
    @DisplayName("an explicit create of a taken name is refused before the insert, with the free name from the real query")
    void duplicateCreateRefusedWithSuggestion() {
        create("50%_off");
        UUID existing = agentRepository.findByOrganizationIdStrictAndNameAndIsActiveTrue(ORG, "50%_off")
                .orElseThrow().getId();

        assertThatThrownBy(() -> create("50%_off"))
                .isInstanceOfSatisfying(AgentNameConflictException.class, e -> {
                    assertThat(e.getExistingAgentId()).isEqualTo(existing);
                    // The prefix query ran on Postgres with its wildcards escaped.
                    assertThat(e.getSuggestedName()).isEqualTo("50%_off (2)");
                });
        assertThat(activeNames()).containsExactly("50%_off");
    }

    @Test
    @DisplayName("regression: a write that bypasses the check and loses to the index is answered 409 with the free name")
    void lostRaceIsTranslatedFromTheRealPostgresError() {
        create("Nova");
        AgentEntity duplicate = new AgentEntity();
        duplicate.setTenantId(TENANT);
        duplicate.setOrganizationId(ORG);
        duplicate.setName("Nova");
        duplicate.setIsActive(true);

        DataIntegrityViolationException refused = null;
        try {
            tx.executeWithoutResult(status -> agentRepository.saveAndFlush(duplicate));
        } catch (DataIntegrityViolationException e) {
            refused = e;
        }
        assertThat(refused).as("the index itself must refuse the second 'Nova'").isNotNull();

        // Outside the aborted transaction, as the REST advice does it.
        AgentNameConflictExceptionHandler advice = new AgentNameConflictExceptionHandler();
        ReflectionTestUtils.setField(advice, "agentService", agentService);
        var response = advice.handleDataIntegrity(refused);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody())
                .containsEntry("error", "AGENT_NAME_CONFLICT")
                .containsEntry("name", "Nova")
                .containsEntry("suggestedName", "Nova (2)");
    }
}
