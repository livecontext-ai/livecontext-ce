package com.apimarketplace.publication.purge;

import com.apimarketplace.auth.client.AuthClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * The publication follower against a real Postgres with the ONE constraint that matters:
 * {@code image_screening_decisions.publication_id} references {@code workflow_publications}
 * with {@code ON DELETE RESTRICT} (V274). A mocked JdbcTemplate cannot refuse a DELETE, so
 * the first version of the follower would have stalled on the first org that ever had a
 * screened image, forever, and every test stayed green (audit 2026-09-02). This one seeds
 * exactly that shape and asserts the purge goes through.
 *
 * <p>Runs when {@code PUBLICATION_TEST_PG_URL} is set (CI sets it; FAILS there if unset).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("PublicationPurgeFollower - real Postgres, RESTRICT child")
class PublicationPurgeFollowerPostgresTest {

    private static final String URL = System.getenv("PUBLICATION_TEST_PG_URL");
    private static final String USER = System.getenv().getOrDefault("PUBLICATION_TEST_PG_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("PUBLICATION_TEST_PG_PASSWORD", "postgres");
    private static final String ORG = "11111111-1111-1111-1111-111111111111";

    private JdbcTemplate jdbc;
    private PublicationPurgeFollower follower;

    @BeforeAll
    void setUpSchema() {
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
        DriverManagerDataSource ds = new DriverManagerDataSource(URL, USER, PASSWORD);
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS publication");
        jdbc.execute("DROP TABLE IF EXISTS publication.image_screening_decisions");
        jdbc.execute("DROP TABLE IF EXISTS publication.publication_receipts");
        jdbc.execute("DROP TABLE IF EXISTS publication.workflow_publications");
        jdbc.execute("DROP TABLE IF EXISTS publication.purge_cursor");
        jdbc.execute("DROP TABLE IF EXISTS publication.creator_follows");
        jdbc.execute("CREATE TABLE publication.workflow_publications (id UUID PRIMARY KEY, owner_type VARCHAR(8) NOT NULL, owner_id VARCHAR(64) NOT NULL)");
        // The production constraint, verbatim in spirit: RESTRICT, not CASCADE.
        jdbc.execute("CREATE TABLE publication.image_screening_decisions (id BIGSERIAL PRIMARY KEY, "
                + "publication_id UUID NOT NULL REFERENCES publication.workflow_publications(id) ON DELETE RESTRICT)");
        jdbc.execute("CREATE TABLE publication.publication_receipts (id BIGSERIAL PRIMARY KEY, organization_id VARCHAR(64) NOT NULL)");
        jdbc.execute("CREATE TABLE publication.purge_cursor (id SMALLINT PRIMARY KEY CHECK (id = 1), last_seq BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        jdbc.execute("CREATE TABLE publication.creator_follows (follower_id VARCHAR(255) NOT NULL, creator_id VARCHAR(255) NOT NULL, "
                + "created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), PRIMARY KEY (follower_id, creator_id))");
        jdbc.update("INSERT INTO publication.purge_cursor (id) VALUES (1)");
        follower = new PublicationPurgeFollower(jdbc, mock(AuthClient.class), false);
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE publication.image_screening_decisions, publication.publication_receipts, publication.workflow_publications, publication.creator_follows");
    }

    private UUID publication(String ownerType, String ownerId, boolean screened) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO publication.workflow_publications (id, owner_type, owner_id) VALUES (?, ?, ?)", id, ownerType, ownerId);
        if (screened) {
            jdbc.update("INSERT INTO publication.image_screening_decisions (publication_id) VALUES (?)", id);
        }
        return id;
    }

    @Test
    @DisplayName("An ORG purge succeeds when the org's publication carries a screening decision, and leaves other owners alone")
    void orgPurgeSurvivesTheRestrictChild() {
        publication("ORG", ORG, true);
        publication("ORG", ORG, false);
        UUID other = publication("ORG", "other-org", true);
        UUID user = publication("USER", "42", true);
        jdbc.update("INSERT INTO publication.publication_receipts (organization_id) VALUES (?)", ORG);

        assertThatCode(() -> follower.purgeOrganization(ORG)).doesNotThrowAnyException();

        assertThat(jdbc.queryForList("SELECT id FROM publication.workflow_publications", UUID.class))
                .containsExactlyInAnyOrder(other, user);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM publication.image_screening_decisions", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM publication.publication_receipts", Long.class)).isZero();
    }

    @Test
    @DisplayName("A USER purge succeeds when the account's publication carries a screening decision")
    void userPurgeSurvivesTheRestrictChild() {
        publication("USER", "42", true);
        UUID keep = publication("USER", "43", true);

        assertThatCode(() -> follower.purgeUser("42")).doesNotThrowAnyException();

        assertThat(jdbc.queryForList("SELECT id FROM publication.workflow_publications", UUID.class)).containsExactly(keep);
    }

    @Test
    @DisplayName("A USER purge drops the account's follows in both directions and keeps everyone else's")
    void userPurgeDropsFollowsBothWays() {
        jdbc.update("INSERT INTO publication.creator_follows (follower_id, creator_id) VALUES ('42', '7'), ('8', '42'), ('8', '7')");

        follower.purgeUser("42");

        assertThat(jdbc.queryForList("SELECT follower_id || '->' || creator_id FROM publication.creator_follows", String.class))
                .containsExactly("8->7");
    }
}
