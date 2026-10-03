package com.apimarketplace.auth.repository;

import com.apimarketplace.testsupport.ScratchPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V559 and V560 on a real Postgres: the CHECKs a partner offer cannot pass (a plan, tier or cycle
 * the price list does not sell, a Starter above its credit cap), the unique token, one delivery per
 * offer, client and app (the guard against a replayed payment installing twice), the re-runs, and
 * the account purge statements. {@code ddl-auto} cannot express any of it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("V559 + V560 partner offers migrations - constraints on a real Postgres")
class PartnerOfferMigrationPostgresTest {

    private static final ScratchPostgres DB = ScratchPostgres.forPrefix(
            "CREDENTIAL_TEST_PG",
            "it is the only proof that a partner offer can never name a plan, credit tier or cycle "
                    + "the price list does not sell, which only V559's CHECKs enforce");

    private JdbcTemplate jdbc;

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
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS auth");
        jdbc.execute("DROP TABLE IF EXISTS auth.partner_offer_delivery CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS auth.partner_offer CASCADE");
        String v559 = migration("V559__partner_offer.sql");
        jdbc.execute(v559);
        jdbc.execute(v559); // idempotent: a re-run must not fail
        String v560 = migration("V560__partner_offer_apps.sql");
        jdbc.execute(v560);
        jdbc.execute(v560);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE auth.partner_offer, auth.partner_offer_delivery RESTART IDENTITY");
    }

    private void insert(String token, long partner, String plan, int tier, String cycle) {
        jdbc.update("INSERT INTO auth.partner_offer (token, partner_user_id, reward_code_id, plan_code, credit_tier_index, billing_cycle) "
                + "VALUES (?, ?, 400, ?, ?, ?)", token, partner, plan, tier, cycle);
    }

    @Test
    @DisplayName("a sellable offer is stored, active, with created_at set by the database")
    void validOffer() {
        insert("ABCDEFGHJK", 42, "PRO", 5, "monthly");

        assertThat(jdbc.queryForObject("SELECT active AND created_at IS NOT NULL FROM auth.partner_offer", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("refused: an unknown plan, a tier off the price list, a Starter above 100K credits, an unknown cycle")
    void checks() {
        assertThatThrownBy(() -> insert("T000000001", 42, "ENTERPRISE", 5, "monthly")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("T000000002", 42, "PRO", 10, "monthly")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("T000000003", 42, "STARTER", 5, "monthly")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("T000000004", 42, "PRO", 5, "weekly")).isInstanceOf(DataIntegrityViolationException.class);
        insert("T000000005", 42, "STARTER", 4, "yearly");
    }

    @Test
    @DisplayName("a token is unique")
    void uniqueToken() {
        insert("ABCDEFGHJK", 42, "PRO", 5, "monthly");

        assertThatThrownBy(() -> insert("ABCDEFGHJK", 43, "TEAM", 7, "yearly")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("account purge: the partner's offers are deleted, other partners' kept")
    void purge() {
        insert("ABCDEFGHJK", 42, "PRO", 5, "monthly");
        insert("LMNPQRSTUV", 43, "PRO", 5, "monthly");

        jdbc.update(com.apimarketplace.auth.service.AccountPurgeService.DELETE_PARTNER_OFFERS_SQL, 42L);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth.partner_offer", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT partner_user_id FROM auth.partner_offer", Long.class)).isEqualTo(43L);
    }

    // ---- V560: the apps an offer gives, and their delivery ----

    private static final String APP = "6f1c0d2e-0000-4000-8000-00000000000a";

    private long offerId(String token) {
        return jdbc.queryForObject("SELECT id FROM auth.partner_offer WHERE token = ?", Long.class, token);
    }

    private int deliver(long offer, long client, String app) {
        return jdbc.update(
                "INSERT INTO auth.partner_offer_delivery (offer_id, client_user_id, publication_id, invoice_id) "
                        + "VALUES (?, ?, ?::uuid, 'in_1') ON CONFLICT (offer_id, client_user_id, publication_id) DO NOTHING",
                offer, client, app);
    }

    @Test
    @DisplayName("V560: an offer created before apps existed gives none (empty list, never null)")
    void appsDefaultEmpty() {
        insert("ABCDEFGHJK", 42, "PRO", 5, "monthly");

        assertThat(jdbc.queryForObject("SELECT app_publication_ids::text FROM auth.partner_offer", String.class)).isEqualTo("[]");
    }

    @Test
    @DisplayName("V560: one delivery per offer, client and app; a replayed payment inserts nothing")
    void oneDeliveryPerApp() {
        insert("ABCDEFGHJK", 42, "PRO", 5, "monthly");
        long offer = offerId("ABCDEFGHJK");

        assertThat(deliver(offer, 7, APP)).isEqualTo(1);
        assertThat(deliver(offer, 7, APP)).isZero();
        assertThat(deliver(offer, 8, APP)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status = 'PENDING' AND attempts = 0 AND next_attempt_at IS NOT NULL FROM auth.partner_offer_delivery WHERE client_user_id = 7",
                Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("V560: a delivery is AWAITING_PAYMENT, PENDING, INSTALLED or FAILED, and belongs to an existing offer")
    void deliveryChecks() {
        insert("ABCDEFGHJK", 42, "PRO", 5, "monthly");
        long offer = offerId("ABCDEFGHJK");
        deliver(offer, 7, APP);

        assertThatThrownBy(() -> jdbc.update("UPDATE auth.partner_offer_delivery SET status = 'LOST'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> deliver(offer + 1000, 7, APP)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("V560 purge: a purged partner's offers take their deliveries; a purged client's deliveries go, others stay")
    void deliveryPurge() {
        insert("ABCDEFGHJK", 42, "PRO", 5, "monthly");
        insert("LMNPQRSTUV", 43, "PRO", 5, "monthly");
        deliver(offerId("ABCDEFGHJK"), 7, APP);
        deliver(offerId("LMNPQRSTUV"), 7, APP);
        deliver(offerId("LMNPQRSTUV"), 8, APP);

        jdbc.update(com.apimarketplace.auth.service.AccountPurgeService.DELETE_PARTNER_OFFERS_SQL, 42L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth.partner_offer_delivery", Long.class)).isEqualTo(2L);

        jdbc.update(com.apimarketplace.auth.service.AccountPurgeService.DELETE_PARTNER_OFFER_DELIVERIES_SQL, 7L);
        assertThat(jdbc.queryForObject("SELECT client_user_id FROM auth.partner_offer_delivery", Long.class)).isEqualTo(8L);
    }
}
