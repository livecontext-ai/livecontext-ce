package com.apimarketplace.auth.repository;

import com.apimarketplace.testsupport.ScratchPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The partner offer delivery SQL on a real Postgres, run from the very constants
 * {@link PartnerOfferDeliveryRepository} declares: the guarantees the delivery rests on (one row
 * per app, a replayed payment changes nothing, two takers never both get a delivery, what is due,
 * what a reconciliation reads and forgets) live in this SQL, which mocks cannot check.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("V560 partner offer delivery SQL - the repository's own statements on a real Postgres")
class PartnerOfferDeliverySqlPostgresTest {

    private static final ScratchPostgres DB = ScratchPostgres.forPrefix(
            "CREDENTIAL_TEST_PG",
            "it is the only proof that a replayed payment installs nothing twice and that two pods "
                    + "never take the same delivery, which only this SQL enforces");

    private static final Instant T0 = Instant.parse("2026-10-02T10:00:00Z");
    private static final UUID APP_A = UUID.fromString("6f1c0d2e-0000-4000-8000-00000000000a");
    private static final UUID APP_B = UUID.fromString("6f1c0d2e-0000-4000-8000-00000000000b");

    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate sql;
    private long offer;

    private static String migration(String name) throws Exception {
        String relative = "migration-service/src/main/resources/db/migration/" + name;
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            Path file = candidate.resolve(relative);
            if (Files.isRegularFile(file)) return Files.readString(file);
        }
        throw new IllegalStateException("could not locate " + relative + " from " + here);
    }

    @BeforeAll
    void setUpSchema() throws Exception {
        DB.require();
        DriverManagerDataSource ds = new DriverManagerDataSource(DB.url(), DB.user(), DB.password());
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);
        sql = new NamedParameterJdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS auth");
        jdbc.execute("DROP TABLE IF EXISTS auth.partner_offer_delivery CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS auth.partner_offer CASCADE");
        jdbc.execute(migration("V559__partner_offer.sql"));
        jdbc.execute(migration("V560__partner_offer_apps.sql"));
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE auth.partner_offer, auth.partner_offer_delivery RESTART IDENTITY");
        jdbc.update("INSERT INTO auth.partner_offer (token, partner_user_id, reward_code_id, plan_code, credit_tier_index, billing_cycle) "
                + "VALUES ('Abc23XyZ9k', 42, 400, 'PRO', 5, 'monthly')");
        offer = jdbc.queryForObject("SELECT id FROM auth.partner_offer", Long.class);
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }

    private int expect(long client, UUID app, Instant now) {
        return sql.update(PartnerOfferDeliveryRepository.EXPECT_SQL, new MapSqlParameterSource()
                .addValue("offerId", offer).addValue("clientUserId", client).addValue("publicationId", app).addValue("now", ts(now)));
    }

    private int paid(long client, UUID app, String invoice, Instant now) {
        return sql.update(PartnerOfferDeliveryRepository.PAID_SQL, new MapSqlParameterSource()
                .addValue("offerId", offer).addValue("clientUserId", client).addValue("publicationId", app)
                .addValue("invoiceId", invoice).addValue("now", ts(now)));
    }

    private int claim(long id, Instant now, Instant lease) {
        return sql.update(PartnerOfferDeliveryRepository.CLAIM_SQL, new MapSqlParameterSource()
                .addValue("id", id).addValue("now", ts(now)).addValue("lease", ts(lease)));
    }

    private List<Long> due(Instant now, int limit) {
        return sql.queryForList(PartnerOfferDeliveryRepository.DUE_SQL,
                new MapSqlParameterSource().addValue("now", ts(now)).addValue("limit", limit), Long.class);
    }

    private String status(long client, UUID app) {
        return jdbc.queryForObject("SELECT status FROM auth.partner_offer_delivery WHERE client_user_id = ? AND publication_id = ?",
                String.class, client, app);
    }

    @Test
    @DisplayName("checkout then payment: the waiting app moves on to install once; a replayed payment changes nothing")
    void waitingThenPaid() {
        assertThat(expect(7, APP_A, T0)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth.partner_offer_delivery", Integer.class)).isEqualTo(1);
        assertThat(status(7, APP_A)).isEqualTo("AWAITING_PAYMENT");
        assertThat(due(T0.plusSeconds(60), 10)).isEmpty();

        assertThat(paid(7, APP_A, "in_1", T0.plusSeconds(60))).isEqualTo(1);
        assertThat(status(7, APP_A)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT invoice_id FROM auth.partner_offer_delivery", String.class)).isEqualTo("in_1");

        assertThat(paid(7, APP_A, "in_1", T0.plusSeconds(90))).isZero();
        jdbc.update("UPDATE auth.partner_offer_delivery SET status = 'INSTALLED'");
        assertThat(paid(7, APP_A, "in_1", T0.plusSeconds(120))).isZero();
        assertThat(status(7, APP_A)).isEqualTo("INSTALLED");
    }

    @Test
    @DisplayName("regression: a checkout opened again days later restarts the wait (its row is not forgotten as abandoned, and is checked anew), while a paid row is left alone")
    void reopenedCheckoutRestartsTheWait() {
        expect(7, APP_A, T0);
        sql.update(PartnerOfferDeliveryRepository.MARK_CHECKED_SQL, new MapSqlParameterSource()
                .addValue("offerId", offer).addValue("clientUserId", 7L).addValue("now", ts(T0.plusSeconds(600))));
        Instant later = T0.plus(Duration.ofDays(2));

        assertThat(expect(7, APP_A, later)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT created_at FROM auth.partner_offer_delivery", Timestamp.class)).isEqualTo(ts(later));
        assertThat(jdbc.queryForObject("SELECT checked_at FROM auth.partner_offer_delivery", Timestamp.class)).isNull();

        paid(7, APP_A, "in_1", later.plusSeconds(60));
        assertThat(expect(7, APP_A, later.plusSeconds(120))).isZero();
        assertThat(status(7, APP_A)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT created_at FROM auth.partner_offer_delivery", Timestamp.class)).isEqualTo(ts(later));
    }

    @Test
    @DisplayName("a payment with no checkout record (it failed, or the payment came from elsewhere) still writes the delivery")
    void paidWithoutRecord() {
        assertThat(paid(8, APP_B, "in_2", T0)).isEqualTo(1);
        assertThat(status(8, APP_B)).isEqualTo("PENDING");
        assertThat(due(T0, 10)).hasSize(1);
    }

    @Test
    @DisplayName("regression: two takers of the same due delivery, one gets it; it is due again only once its lease ends")
    void claimOnce() {
        paid(7, APP_A, "in_1", T0);
        long id = jdbc.queryForObject("SELECT id FROM auth.partner_offer_delivery", Long.class);
        Instant lease = T0.plus(Duration.ofMinutes(3));

        assertThat(claim(id, T0, lease)).isEqualTo(1);
        assertThat(claim(id, T0, lease)).isZero();
        assertThat(jdbc.queryForObject("SELECT attempts FROM auth.partner_offer_delivery", Integer.class)).isEqualTo(1);
        assertThat(due(T0.plusSeconds(60), 10)).isEmpty();
        assertThat(due(lease, 10)).containsExactly(id);

        jdbc.update("UPDATE auth.partner_offer_delivery SET status = 'FAILED'");
        assertThat(claim(id, lease, lease.plusSeconds(180))).isZero();
    }

    @Test
    @DisplayName("what is due: pending and past its time only, oldest first, bounded; one client's own list")
    void dueOrderAndScope() {
        paid(7, APP_A, "in_1", T0.plusSeconds(20));
        paid(7, APP_B, "in_1", T0.plusSeconds(10));
        paid(9, APP_A, "in_3", T0.plusSeconds(30));
        expect(10, APP_A, T0);

        List<Long> all = due(T0.plusSeconds(60), 10);
        assertThat(all).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT publication_id FROM auth.partner_offer_delivery WHERE id = ?", UUID.class, all.get(0))).isEqualTo(APP_B);
        assertThat(due(T0.plusSeconds(60), 2)).hasSize(2);
        assertThat(due(T0.plusSeconds(15), 10)).hasSize(1);

        List<Long> mine = sql.queryForList(PartnerOfferDeliveryRepository.DUE_FOR_CLIENT_SQL,
                new MapSqlParameterSource().addValue("clientUserId", 7L).addValue("now", ts(T0.plusSeconds(60))), Long.class);
        assertThat(mine).hasSize(2);
    }

    private List<Long> awaitingClients(Instant now, int limit) {
        return sql.queryForList(PartnerOfferDeliveryRepository.AWAITING_SQL, new MapSqlParameterSource()
                        .addValue("oldest", ts(now.minus(Duration.ofDays(3)))).addValue("settled", ts(now.minus(Duration.ofMinutes(10))))
                        .addValue("limit", limit))
                .stream().map(row -> ((Number) row.get("client_user_id")).longValue()).toList();
    }

    @Test
    @DisplayName("regression: a long queue is gone through in turn: the least recently checked first, at most the batch, every pair reached")
    void reconciliationRotates() {
        for (long client = 1; client <= 5; client++) expect(client, APP_A, T0.plusSeconds(client));
        Instant now = T0.plus(Duration.ofHours(1));

        List<Long> first = awaitingClients(now, 2);
        assertThat(first).containsExactly(1L, 2L);
        for (Long client : first) {
            sql.update(PartnerOfferDeliveryRepository.MARK_CHECKED_SQL, new MapSqlParameterSource()
                    .addValue("offerId", offer).addValue("clientUserId", client).addValue("now", ts(now)));
        }
        assertThat(awaitingClients(now, 2)).containsExactly(3L, 4L);
        sql.update(PartnerOfferDeliveryRepository.MARK_CHECKED_SQL, new MapSqlParameterSource()
                .addValue("offerId", offer).addValue("clientUserId", 3L).addValue("now", ts(now.plusSeconds(1))));
        sql.update(PartnerOfferDeliveryRepository.MARK_CHECKED_SQL, new MapSqlParameterSource()
                .addValue("offerId", offer).addValue("clientUserId", 4L).addValue("now", ts(now.plusSeconds(1))));
        // The never-checked one first, then the oldest checks.
        assertThat(awaitingClients(now, 3)).containsExactly(5L, 1L, 2L);
    }

    @Test
    @DisplayName("a client who paid without the offer has only that offer's waiting rows forgotten")
    void forgetPair() {
        expect(7, APP_A, T0);
        expect(7, APP_B, T0);
        paid(8, APP_A, "in_8", T0);

        assertThat(sql.update(PartnerOfferDeliveryRepository.FORGET_PAIR_SQL, new MapSqlParameterSource()
                .addValue("offerId", offer).addValue("clientUserId", 7L))).isEqualTo(2);
        assertThat(sql.update(PartnerOfferDeliveryRepository.FORGET_PAIR_SQL, new MapSqlParameterSource()
                .addValue("offerId", offer).addValue("clientUserId", 8L))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth.partner_offer_delivery", Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("reconciliation: reads the checkouts waiting in its window, promotes one pair, forgets the abandoned ones")
    void reconcileStatements() {
        expect(7, APP_A, T0);
        expect(7, APP_B, T0);
        expect(8, APP_A, T0.minus(Duration.ofDays(4)));
        expect(9, APP_A, T0.plus(Duration.ofMinutes(55)));
        Instant now = T0.plus(Duration.ofHours(1));

        List<java.util.Map<String, Object>> pairs = sql.queryForList(PartnerOfferDeliveryRepository.AWAITING_SQL, new MapSqlParameterSource()
                .addValue("oldest", ts(now.minus(Duration.ofDays(3)))).addValue("settled", ts(now.minus(Duration.ofMinutes(10)))).addValue("limit", 50));
        assertThat(pairs).hasSize(1);
        assertThat(((Number) pairs.get(0).get("client_user_id")).longValue()).isEqualTo(7L);

        assertThat(sql.update(PartnerOfferDeliveryRepository.PROMOTE_SQL, new MapSqlParameterSource()
                .addValue("offerId", offer).addValue("clientUserId", 7L).addValue("now", ts(now)))).isEqualTo(2);
        assertThat(status(7, APP_B)).isEqualTo("PENDING");

        assertThat(sql.update(PartnerOfferDeliveryRepository.FORGET_ABANDONED_SQL,
                new MapSqlParameterSource().addValue("oldest", ts(now.minus(Duration.ofDays(3)))))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth.partner_offer_delivery", Long.class)).isEqualTo(3L);
        // Only waiting rows are ever forgotten.
        jdbc.update("UPDATE auth.partner_offer_delivery SET created_at = ?", ts(T0.minus(Duration.ofDays(30))));
        assertThat(sql.update(PartnerOfferDeliveryRepository.FORGET_ABANDONED_SQL,
                new MapSqlParameterSource().addValue("oldest", ts(now.minus(Duration.ofDays(3)))))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth.partner_offer_delivery WHERE status = 'PENDING'", Long.class)).isEqualTo(2L);
    }
}
