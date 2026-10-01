package com.apimarketplace.publication.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The follow feature's SQL against a real Postgres: the V551 migration file itself (table,
 * self-follow check, widened notification subject constraint, and the backfill that decides
 * which existing listings count as already announced), and the repository's native
 * {@code insertIfAbsent} statement, read from its {@code @Query} so the test runs the exact
 * string the application runs.
 *
 * <p>Runs when {@code PUBLICATION_TEST_PG_URL} is set (CI sets it; FAILS there if unset). The
 * whole test runs in one transaction that is rolled back, so the shared database is untouched.
 */
@DisplayName("Creator follows - real Postgres (V551 + insertIfAbsent)")
class CreatorFollowPostgresTest {

    private static final String URL = System.getenv("PUBLICATION_TEST_PG_URL");
    private static final String USER = System.getenv().getOrDefault("PUBLICATION_TEST_PG_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("PUBLICATION_TEST_PG_PASSWORD", "postgres");
    private static final Path V551 = Path.of("..", "migration-service", "src", "main", "resources",
            "db", "migration", "V551__creator_follows.sql");

    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        if (URL == null || URL.isBlank()) {
            if (System.getenv("CI") != null && !System.getenv("CI").isBlank()) {
                throw new IllegalStateException("PUBLICATION_TEST_PG_URL is unset on CI; this test must run there");
            }
            Assumptions.abort("no scratch Postgres: set PUBLICATION_TEST_PG_URL to run this locally");
        }
        String database = URL.substring(URL.lastIndexOf('/') + 1).split("\\?")[0];
        if (!database.toLowerCase(Locale.ROOT).contains("test")) {
            throw new IllegalStateException("PUBLICATION_TEST_PG_URL must name a scratch database containing 'test', got " + database);
        }
        conn = DriverManager.getConnection(URL, USER, PASSWORD);
        conn.setAutoCommit(false);
        exec("CREATE SCHEMA IF NOT EXISTS publication");
        exec("CREATE SCHEMA IF NOT EXISTS orchestrator");
        exec("DROP TABLE IF EXISTS publication.creator_follows");
        exec("DROP TABLE IF EXISTS publication.workflow_publications CASCADE");
        exec("DROP TABLE IF EXISTS orchestrator.notifications CASCADE");
        // Only the columns V551 reads, in their production types.
        exec("CREATE TABLE publication.workflow_publications (id INT PRIMARY KEY, visibility VARCHAR(20) NOT NULL, "
                + "status VARCHAR(20) NOT NULL, reviewed_at TIMESTAMPTZ, use_count INT NOT NULL DEFAULT 0, "
                + "published_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL)");
        exec("CREATE TABLE orchestrator.notifications (id SERIAL PRIMARY KEY, subject_type VARCHAR(40) NOT NULL, "
                + "CONSTRAINT chk_notif_subject_type_v1 CHECK (subject_type IN ('WORKFLOW', 'BILLING')))");
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (conn != null) {
            conn.rollback();
            conn.close();
        }
    }

    private void exec(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private void listing(int id, String visibility, String status, String reviewedAt, int useCount, String updatedLag)
            throws SQLException {
        exec("INSERT INTO publication.workflow_publications VALUES (" + id + ", '" + visibility + "', '" + status + "', "
                + (reviewedAt == null ? "NULL" : "'" + reviewedAt + "'") + ", " + useCount
                + ", NOW() - INTERVAL '10 days', NOW() - INTERVAL '10 days' + INTERVAL '" + updatedLag + "')");
    }

    private void applyV551() throws Exception {
        exec(Files.readString(V551));
    }

    private List<Integer> stampedIds() throws SQLException {
        List<Integer> ids = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM publication.workflow_publications "
                     + "WHERE followers_notified_at IS NOT NULL ORDER BY id")) {
            while (rs.next()) ids.add(rs.getInt(1));
        }
        return ids;
    }

    @Test
    @DisplayName("the backfill stamps every listing that has been live, and only those")
    void backfillStampsListingsThatHaveBeenLive() throws Exception {
        listing(1, "PUBLIC", "ACTIVE", "2026-09-01", 0, "0 seconds");          // live today
        listing(2, "PUBLIC", "PENDING_REVIEW", null, 0, "0 seconds");          // first submission, never live
        listing(3, "PUBLIC", "PENDING_REVIEW", "2026-08-01", 0, "0 seconds");  // reviewed before
        listing(4, "PUBLIC", "PENDING_REVIEW", null, 12, "0 seconds");         // republished: reviewed_at cleared, but installed
        listing(5, "PUBLIC", "PENDING_REVIEW", null, 0, "3 days");             // republished: modified long after first submission
        listing(6, "PRIVATE", "ACTIVE", "2026-09-01", 4, "3 days");            // never on the marketplace
        listing(7, "UNLISTED", "ACTIVE", "2026-09-01", 4, "3 days");           // never on the marketplace

        applyV551();

        assertThat(stampedIds()).containsExactly(1, 3, 4, 5);
    }

    @Test
    @DisplayName("the constraint in force admits PUBLICATION, and a self-follow is refused by the table")
    void constraintsHold() throws Exception {
        applyV551();

        exec("INSERT INTO orchestrator.notifications (subject_type) VALUES ('PUBLICATION')");
        assertThatThrownBy(() -> exec("INSERT INTO publication.creator_follows (follower_id, creator_id) VALUES ('7', '7')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_creator_follows_not_self");
    }

    @Test
    @DisplayName("insertIfAbsent (the exact @Query string) inserts once and is a no-op on a repeat, never a key violation")
    void insertIfAbsentIsIdempotent() throws Exception {
        applyV551();
        String sql = CreatorFollowRepository.class
                .getMethod("insertIfAbsent", String.class, String.class)
                .getAnnotation(Query.class).value()
                .replace(":followerId", "?").replace(":creatorId", "?");

        int first;
        int second;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "5");
            ps.setString(2, "7");
            first = ps.executeUpdate();
            second = ps.executeUpdate();
        }

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM publication.creator_follows")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }
}
