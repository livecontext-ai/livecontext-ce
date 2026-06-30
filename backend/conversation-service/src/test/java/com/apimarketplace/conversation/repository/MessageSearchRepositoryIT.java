package com.apimarketplace.conversation.repository;

import com.apimarketplace.conversation.dto.MessageSearchHit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link MessageSearchRepositoryImpl} against a real
 * PostgreSQL 16 testcontainer. We run the SQL of V141 verbatim against a
 * minimal hand-rolled baseline mirroring the relevant slices of V10
 * (conversations + messages tables only) so the test focuses on the FTS
 * search contract and stays decoupled from unrelated migrations.
 *
 * <p>What this pins:
 * <ul>
 *   <li>Keyword search returns matching messages with ranked excerpts.</li>
 *   <li>Excerpts contain {@code ⟦…⟧} unicode-bracket markers, not HTML.</li>
 *   <li>Cursor pagination is stable: page 2 starts exactly where page 1
 *       ended, no duplicates, no gaps.</li>
 *   <li>Date filters ({@code since}/{@code until}), role filter, and tool
 *       name filter all narrow the result correctly.</li>
 *   <li>Conversations not in {@code conversationIds} are NEVER returned -
 *       proves tenant isolation at the repository layer.</li>
 *   <li>{@code includeInactive=false} drops conversations with active=false.</li>
 *   <li>Multi-language content (FR + EN) coexists under tokenizer 'simple'.</li>
 * </ul>
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("MessageSearchRepositoryImpl - FTS against real Postgres")
class MessageSearchRepositoryIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("conv_test")
            .withUsername("test")
            .withPassword("test");

    private MessageSearchRepositoryImpl repository;
    private JdbcTemplate jdbc;
    private EntityManagerFactory emf;

    // Test fixture IDs - created in @BeforeAll
    private static final String CONV_A = "conv-a";   // user-1, active
    private static final String CONV_B = "conv-b";   // user-1, active
    private static final String CONV_C = "conv-c";   // user-2, active (foreign - never authorized)
    private static final String CONV_D = "conv-d";   // user-1, inactive

    @BeforeAll
    void setUpSchemaAndFixtures() throws Exception {
        // 1. JdbcTemplate for raw SQL
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName(POSTGRES.getDriverClassName());
        jdbc = new JdbcTemplate(ds);

        // 2. Schema - minimal slice of V10 + V141 (skip the rest, irrelevant here)
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS conversation");
        jdbc.execute("""
                CREATE TABLE conversation.conversations (
                    id VARCHAR(255) PRIMARY KEY,
                    user_id VARCHAR(255) NOT NULL,
                    title VARCHAR(255),
                    active BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbc.execute("""
                CREATE TABLE conversation.messages (
                    id VARCHAR(255) PRIMARY KEY,
                    conversation_id VARCHAR(255) NOT NULL
                        REFERENCES conversation.conversations(id) ON DELETE CASCADE,
                    role VARCHAR(20) NOT NULL,
                    content TEXT,
                    tool_name VARCHAR(100),
                    agent_id VARCHAR(255),
                    execution_id VARCHAR(255),
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);

        // V141 verbatim
        jdbc.execute("""
                ALTER TABLE conversation.messages
                    ADD COLUMN search_vector tsvector
                    GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content, ''))) STORED
                """);
        jdbc.execute("CREATE INDEX idx_messages_search_vector ON conversation.messages USING GIN(search_vector)");

        // 3. Conversations
        jdbc.update("INSERT INTO conversation.conversations (id, user_id, title, active) VALUES (?,?,?,?)",
                CONV_A, "user-1", "Conv A - invoices", true);
        jdbc.update("INSERT INTO conversation.conversations (id, user_id, title, active) VALUES (?,?,?,?)",
                CONV_B, "user-1", "Conv B - support", true);
        jdbc.update("INSERT INTO conversation.conversations (id, user_id, title, active) VALUES (?,?,?,?)",
                CONV_C, "user-2", "Conv C - foreign tenant", true);
        jdbc.update("INSERT INTO conversation.conversations (id, user_id, title, active) VALUES (?,?,?,?)",
                CONV_D, "user-1", "Conv D - archived", false);

        // 4. Messages - varied content, roles, dates, tools
        Instant base = Instant.parse("2026-04-20T10:00:00Z");
        insertMsg("m1", CONV_A, "USER", "Trouve la facture client X de mars 2026", null, base.plusSeconds(0));
        insertMsg("m2", CONV_A, "ASSISTANT", "La facture INV-3041 a été émise le 15 mars", null, base.plusSeconds(60));
        insertMsg("m3", CONV_A, "TOOL", "Search results: invoice INV-3041 found", "web_search", base.plusSeconds(120));
        insertMsg("m4", CONV_A, "ASSISTANT", "Je vous ai envoyé la facture par email", null, base.plusSeconds(180));

        insertMsg("m5", CONV_B, "USER", "Help me debug this error in production", null, base.plusSeconds(240));
        insertMsg("m6", CONV_B, "ASSISTANT", "Looking at logs, the issue is a timeout", null, base.plusSeconds(300));
        insertMsg("m7", CONV_B, "TOOL", "grep result: timeout in service.go:42", "terminal", base.plusSeconds(360));

        // CONV_C - foreign tenant; must NEVER appear when only user-1 conv ids passed
        insertMsg("m8", CONV_C, "USER", "I also need to find an invoice for my account", null, base.plusSeconds(420));

        // CONV_D - inactive; must be excluded when includeInactive=false
        insertMsg("m9", CONV_D, "USER", "Old archived conversation about facture", null, base.plusSeconds(480));

        // 5. Persist + EM for repo
        Map<String, String> jpaProps = new HashMap<>();
        jpaProps.put("jakarta.persistence.jdbc.url", POSTGRES.getJdbcUrl());
        jpaProps.put("jakarta.persistence.jdbc.user", POSTGRES.getUsername());
        jpaProps.put("jakarta.persistence.jdbc.password", POSTGRES.getPassword());
        jpaProps.put("jakarta.persistence.jdbc.driver", POSTGRES.getDriverClassName());
        jpaProps.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        jpaProps.put("hibernate.hbm2ddl.auto", "none");
        // Register no entities - we only use native queries, so a bare JPA setup
        // is enough.
        emf = Persistence.createEntityManagerFactory("test-pu", jpaProps);

        // Instantiate the repo and inject the EM via reflection (it's a
        // package-private @PersistenceContext field - easier than wiring the
        // full Spring container for one method-under-test).
        repository = new MessageSearchRepositoryImpl();
        EntityManager em = emf.createEntityManager();
        Field emField = MessageSearchRepositoryImpl.class.getDeclaredField("em");
        emField.setAccessible(true);
        emField.set(repository, em);
    }

    private void insertMsg(String id, String convId, String role, String content,
                            String toolName, Instant createdAt) {
        jdbc.update(
                "INSERT INTO conversation.messages (id, conversation_id, role, content, tool_name, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
                id, convId, role, content, toolName, java.sql.Timestamp.from(createdAt));
    }

    @Nested
    @DisplayName("Basic search")
    class BasicSearch {

        @Test
        @DisplayName("Keyword 'facture' matches all 3 french messages in CONV_A")
        void keywordMatch() {
            List<MessageSearchHit> hits = repository.search(
                    List.of(CONV_A, CONV_B), "facture",
                    null, null, null, null, false, null, null, 50);

            assertThat(hits).extracting(MessageSearchHit::messageId)
                    .containsExactlyInAnyOrder("m1", "m2", "m4");
            assertThat(hits).allSatisfy(h ->
                    assertThat(h.conversationId()).isEqualTo(CONV_A));
        }

        @Test
        @DisplayName("Excerpts highlight matched terms with ⟦…⟧ unicode brackets, no HTML")
        void excerptsUseUnicodeBrackets() {
            List<MessageSearchHit> hits = repository.search(
                    List.of(CONV_A), "facture",
                    null, null, null, null, false, null, null, 50);

            assertThat(hits).isNotEmpty();
            assertThat(hits.get(0).excerpt()).contains("⟦").contains("⟧");
            assertThat(hits.get(0).excerpt()).doesNotContain("<b>").doesNotContain("</b>");
        }

        @Test
        @DisplayName("Each hit carries a non-null rank score from ts_rank")
        void rankPopulated() {
            List<MessageSearchHit> hits = repository.search(
                    List.of(CONV_A), "facture",
                    null, null, null, null, false, null, null, 50);

            assertThat(hits).allSatisfy(h ->
                    assertThat(h.rank()).isNotNull().isGreaterThan(0.0));
        }
    }

    @Nested
    @DisplayName("Tenant isolation")
    class TenantIsolation {

        @Test
        @DisplayName("CONV_C messages NEVER returned when only CONV_A and CONV_B are authorized")
        void foreignTenantNotLeaked() {
            // CONV_C contains 'invoice' which would match an English query; we
            // pass only user-1's conversations as the authorized scope.
            List<MessageSearchHit> hits = repository.search(
                    List.of(CONV_A, CONV_B), "invoice",
                    null, null, null, null, false, null, null, 50);

            assertThat(hits).extracting(MessageSearchHit::conversationId)
                    .doesNotContain(CONV_C);
            assertThat(hits).extracting(MessageSearchHit::messageId)
                    .doesNotContain("m8");
        }

        @Test
        @DisplayName("Empty conversationIds is safe (no leak via 'IN ()' edge cases)")
        void emptyConvIdsReturnsEmpty() {
            // Note: implementation short-circuits on empty list before hitting SQL,
            // but exercising it through the impl confirms the contract.
            List<MessageSearchHit> hits = repository.search(
                    List.of(), "facture",
                    null, null, null, null, false, null, null, 50);
            assertThat(hits).isEmpty();
        }
    }

    @Nested
    @DisplayName("Active flag")
    class ActiveFlag {

        @Test
        @DisplayName("includeInactive=false drops CONV_D (active=false)")
        void inactiveExcluded() {
            List<MessageSearchHit> hits = repository.search(
                    List.of(CONV_A, CONV_D), "facture",
                    null, null, null, null, false, null, null, 50);

            assertThat(hits).extracting(MessageSearchHit::messageId)
                    .doesNotContain("m9")
                    .contains("m1", "m2", "m4");
        }

        @Test
        @DisplayName("includeInactive=true reaches into CONV_D")
        void inactiveIncluded() {
            List<MessageSearchHit> hits = repository.search(
                    List.of(CONV_A, CONV_D), "facture",
                    null, null, null, null, true, null, null, 50);

            assertThat(hits).extracting(MessageSearchHit::messageId)
                    .contains("m9");
        }
    }

    @Nested
    @DisplayName("Filters")
    class Filters {

        @Test
        @DisplayName("Date filter via since= drops messages created before the cutoff")
        void sinceFilter() {
            // Only m4 ("envoyé la facture") is at +180s, others are earlier
            Instant cutoff = Instant.parse("2026-04-20T10:02:30Z"); // +150s
            List<MessageSearchHit> hits = repository.search(
                    List.of(CONV_A), "facture",
                    cutoff, null, null, null, false, null, null, 50);

            assertThat(hits).extracting(MessageSearchHit::messageId)
                    .containsExactly("m4");
        }

        @Test
        @DisplayName("Role filter restricts results to assistant messages only")
        void roleFilter() {
            List<MessageSearchHit> hits = repository.search(
                    List.of(CONV_A), "facture",
                    null, null, List.of("ASSISTANT"), null, false, null, null, 50);

            assertThat(hits).extracting(MessageSearchHit::role)
                    .containsOnly("ASSISTANT");
            assertThat(hits).extracting(MessageSearchHit::messageId)
                    .containsExactlyInAnyOrder("m2", "m4");
        }

        @Test
        @DisplayName("tool_name filter narrows to a single tool")
        void toolNameFilter() {
            List<MessageSearchHit> hits = repository.search(
                    List.of(CONV_A, CONV_B), "search OR timeout OR result",
                    null, null, null, "web_search", false, null, null, 50);

            assertThat(hits).extracting(MessageSearchHit::messageId)
                    .containsExactly("m3");
        }
    }

    @Nested
    @DisplayName("Cursor pagination")
    class CursorPagination {

        @Test
        @DisplayName("Cursor walks all matches once - no duplicates, no gaps")
        void cursorIsStable() {
            // 3 matches: m1, m2, m4 (ordered by created_at DESC = m4, m2, m1)
            List<MessageSearchHit> page1 = repository.search(
                    List.of(CONV_A), "facture",
                    null, null, null, null, false, null, null, 2);
            assertThat(page1).hasSize(2);
            assertThat(page1).extracting(MessageSearchHit::messageId)
                    .containsExactly("m4", "m2");

            // Use last hit of page1 as cursor
            MessageSearchHit last = page1.get(page1.size() - 1);
            List<MessageSearchHit> page2 = repository.search(
                    List.of(CONV_A), "facture",
                    null, null, null, null, false,
                    last.createdAt(), last.messageId(), 2);

            assertThat(page2).extracting(MessageSearchHit::messageId)
                    .containsExactly("m1");

            // No duplicates across pages
            assertThat(page2).extracting(MessageSearchHit::messageId)
                    .doesNotContainAnyElementsOf(
                            page1.stream().map(MessageSearchHit::messageId).toList());
        }
    }

    @Nested
    @DisplayName("Multi-language")
    class MultiLanguage {

        @Test
        @DisplayName("FR ('facture') and EN ('error') queries each match their own corpus")
        void frAndEnCoexist() {
            List<MessageSearchHit> fr = repository.search(
                    List.of(CONV_A, CONV_B), "facture",
                    null, null, null, null, false, null, null, 50);
            assertThat(fr).allSatisfy(h ->
                    assertThat(h.conversationId()).isEqualTo(CONV_A));

            List<MessageSearchHit> en = repository.search(
                    List.of(CONV_A, CONV_B), "error",
                    null, null, null, null, false, null, null, 50);
            assertThat(en).extracting(MessageSearchHit::messageId).contains("m5");
        }
    }
}
