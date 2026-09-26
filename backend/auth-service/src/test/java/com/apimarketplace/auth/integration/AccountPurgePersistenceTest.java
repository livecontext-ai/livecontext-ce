package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.CeLink;
import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationInvitation;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationMemberQuotaLimit;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.RefreshToken;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.domain.UserOnboarding;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.OrganizationInvitationRepository;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.RefreshTokenRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserOnboardingRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.AccountPurgeService;
import com.apimarketplace.auth.service.StripeBillingService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The nightly account purge, on a REAL Postgres.
 *
 * <p><b>Why this cannot be a mocked test.</b> From 2026-05-26 to 2026-09-25 the purge failed on
 * every run in production and its mocked tests stayed green. It bound every id as a string:
 * {@code organization_invitation.organization_id} is uuid and {@code user_roles.user_id} (with
 * seven other user columns) is bigint, and Postgres refuses {@code uuid = varchar} and
 * {@code bigint = varchar}. Its helper then swallowed the error without a savepoint, so the
 * aborted transaction failed every later statement with "current transaction is aborted" and
 * the whole purge rolled back. Only a real Postgres can say whether a bound parameter matches
 * its column, so every test here runs the real service against real tables.
 *
 * <p>A second defect sat behind the first and only a real transaction shows it: the Stripe
 * cancellation joined the purge's transaction, and for an account with no Stripe subscription
 * (every FREE one) it threw, which marked the purge rollback-only although the exception was
 * caught; the commit then failed with {@code UnexpectedRollbackException}. The seeded accounts
 * here have no linked Stripe subscription unless a test links one.
 *
 * <p>Note that a type mismatch raises even when no row matches, so a purge that completes
 * without throwing proves the binding of every statement it ran. Delete ORDER against the NO
 * ACTION / RESTRICT foreign keys needs rows, which {@link #everyRowThatBlocksTheUserDeleteIsRemoved}
 * seeds.
 *
 * <p>Deliberately NOT {@code @Transactional}: the purge's own transaction boundary (commit, or
 * roll back as a whole) is part of what is under test. The container is shared with every other
 * Postgres suite; {@link #removeSeededRows} cleans up, and the table renames in
 * {@link #failingStatementsRollEverythingBackAndAreAllNamed} rely on surefire running test
 * classes one after another in one fork (the module's configuration), never in parallel.
 */
@SpringBootTest
@TestPropertySource(properties = {
        // Production's configuration: cloud edition, Keycloak identities. Only the HTTP calls to
        // Keycloak are replaced (see stubKeycloak); the identity branch has its own suite,
        // AccountPurgeIdentityTest.
        "auth.mode=keycloak",
        "keycloak.admin.server-url=http://keycloak.test",
        "keycloak.admin.client-secret=test-secret",
        "account.purge.cron=-"          // the test drives purgeUser directly
})
@DisplayName("Account purge on a real Postgres - typed binding, all or nothing")
class AccountPurgePersistenceTest extends AuthPostgresIntegrationTest {

    @Autowired private AccountPurgeService purgeService;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;
    @Autowired private OrganizationInvitationRepository invitationRepository;
    @Autowired private UserOnboardingRepository onboardingRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private BillingCustomerRepository billingCustomerRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private EntityManager em;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    /** Only Keycloak goes through it here: token request, then the identity DELETE. */
    @MockitoBean private RestTemplate restTemplate;

    /** Real bean; only the tests that link a Stripe subscription stub its cancellation. */
    @MockitoSpyBean private StripeBillingService stripeBillingService;

    /** Everything this class seeded; the Postgres container is shared with every other class. */
    private final List<Long> seededUsers = new ArrayList<>();

    @BeforeEach
    void stubKeycloak() {
        Mockito.reset(stripeBillingService);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("access_token", "t")));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.DELETE), any(), eq(Void.class)))
                .thenReturn(ResponseEntity.noContent().build());
    }

    /**
     * Makes the test schema say what production says. {@code ddl-auto} builds it from the
     * entities, which differ from the Flyway schema in three ways that matter here: some purge
     * tables have no entity, some foreign keys are plain {@code Long} columns in the entity (so
     * no FK), and {@code org_member_quota_limit.created_by_user_id} is NOT NULL in the entity but
     * nullable in production (which the purge relies on). All copied from production,
     * {@code information_schema} / {@code pg_constraint}, 2026-09-25.
     */
    @BeforeEach
    void alignSchemaWithProduction() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS auth.purge_log ("
                + "seq BIGSERIAL PRIMARY KEY, subject_type VARCHAR(8) NOT NULL, subject_id VARCHAR(64) NOT NULL, "
                + "source VARCHAR(32) NOT NULL, purged_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        jdbc.execute("CREATE TABLE IF NOT EXISTS auth.org_resource_restrictions ("
                + "id BIGSERIAL PRIMARY KEY, organization_id VARCHAR(50) NOT NULL, member_user_id VARCHAR(50) NOT NULL, "
                + "resource_type VARCHAR(20) NOT NULL, resource_id VARCHAR(50) NOT NULL, restricted_by VARCHAR(50) NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS auth.usage_cycle ("
                + "id BIGSERIAL PRIMARY KEY, subscription_id BIGINT NOT NULL REFERENCES auth.subscription(id), "
                + "cycle_start TIMESTAMPTZ NOT NULL, cycle_end TIMESTAMPTZ NOT NULL)");
        // No entity maps auth.credentials, so this is the only place its shape comes from.
        jdbc.execute("CREATE TABLE IF NOT EXISTS auth.credentials ("
                + "id BIGSERIAL PRIMARY KEY, tenant_id VARCHAR(255) NOT NULL, name VARCHAR(255) NOT NULL, "
                + "type VARCHAR(50) NOT NULL, organization_id VARCHAR(255))");
        jdbc.execute("ALTER TABLE auth.org_member_quota_limit ALTER COLUMN created_by_user_id DROP NOT NULL");
        addFk("fk_test_ce_link_user", "auth.ce_link", "user_id", "ON DELETE RESTRICT");
        addFk("fk_test_ce_link_revoked_by", "auth.ce_link", "revoked_by_user_id", "");
        addFk("fk_test_pending_upgrade_user", "auth.pending_credit_upgrade", "user_id", "");
        addFk("fk_test_quota_created_by", "auth.org_member_quota_limit", "created_by_user_id", "");
        // V337. ddl-auto never creates a partial index, and without this one the suite accepted a
        // handover that promoted the new owner while the old one still held the role: refused in
        // production on every run, green here. If this CREATE ever fails, an earlier suite left an
        // org with two 'owner' rows in the shared container.
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_organization_member_one_owner_per_org "
                + "ON auth.organization_member (organization_id) WHERE role = 'owner'");
    }

    /**
     * Undoes {@link #alignSchemaWithProduction} on the shared container, so no other suite runs
     * against foreign keys and an index its fixtures were never written for. The tables it
     * created are left: they are empty and nothing else maps them.
     */
    @AfterAll
    static void restoreSharedSchema() throws java.sql.SQLException {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             java.sql.Statement st = c.createStatement()) {
            st.execute("ALTER TABLE auth.ce_link DROP CONSTRAINT IF EXISTS fk_test_ce_link_user");
            st.execute("ALTER TABLE auth.ce_link DROP CONSTRAINT IF EXISTS fk_test_ce_link_revoked_by");
            st.execute("ALTER TABLE auth.pending_credit_upgrade DROP CONSTRAINT IF EXISTS fk_test_pending_upgrade_user");
            st.execute("ALTER TABLE auth.org_member_quota_limit DROP CONSTRAINT IF EXISTS fk_test_quota_created_by");
            st.execute("DROP INDEX IF EXISTS auth.uq_organization_member_one_owner_per_org");
            st.execute("ALTER TABLE auth.org_member_quota_limit ALTER COLUMN created_by_user_id SET NOT NULL");
        }
    }

    private void addFk(String name, String table, String column, String onDelete) {
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = '" + name + "') THEN "
                + "ALTER TABLE " + table + " ADD CONSTRAINT " + name + " FOREIGN KEY (" + column + ") "
                + "REFERENCES auth.users(id) " + onDelete + "; END IF; END $$");
    }

    /**
     * The other suites reset with {@code userRepository.deleteAll()}, which fails on the FK from
     * an organization this class left behind (a handed-over team keeps its new owner).
     */
    @AfterEach
    void removeSeededRows() {
        for (Long uid : seededUsers) {
            jdbc.update("DELETE FROM auth.pending_credit_upgrade WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM auth.ce_link WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM auth.org_member_quota_limit WHERE user_id = ? OR created_by_user_id = ? OR org_id IN "
                    + "(SELECT id FROM auth.organization WHERE owner_id = ?)", uid, uid, uid);
            jdbc.update("DELETE FROM auth.organization_invitation WHERE invited_by = ? OR organization_id IN "
                    + "(SELECT id FROM auth.organization WHERE owner_id = ?)", uid, uid);
            jdbc.update("DELETE FROM auth.organization_member WHERE user_id = ? OR organization_id IN "
                    + "(SELECT id FROM auth.organization WHERE owner_id = ?)", uid, uid);
            jdbc.update("DELETE FROM auth.organization WHERE owner_id = ?", uid);
            jdbc.update("DELETE FROM auth.user_onboarding WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM auth.refresh_tokens WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM auth.usage_cycle WHERE subscription_id IN (SELECT s.id FROM auth.subscription s "
                    + "JOIN auth.billing_customer bc ON s.billing_customer_id = bc.id WHERE bc.user_id = ?)", uid);
            jdbc.update("DELETE FROM auth.subscription WHERE billing_customer_id IN "
                    + "(SELECT id FROM auth.billing_customer WHERE user_id = ?)", uid);
            jdbc.update("DELETE FROM auth.billing_customer WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM auth.credentials WHERE tenant_id = ?", uid.toString());
            jdbc.update("DELETE FROM auth.user_roles WHERE user_id = ?", uid);
        }
        for (Long uid : seededUsers) {
            jdbc.update("DELETE FROM auth.users WHERE id = ?", uid);
        }
        seededUsers.clear();
    }

    @Test
    @DisplayName("the columns the purge binds have their production types in the test schema")
    void testSchemaUsesProductionColumnTypes() {
        // If ddl-auto ever maps one of these differently from the Flyway schema, the other tests
        // would bind happily against the wrong type and prove nothing. Pinned from prod.
        // (auth.credentials is left out on purpose: no entity maps it, the table above creates it.)
        Map<String, String> expected = Map.ofEntries(
                Map.entry("organization_invitation.organization_id", "uuid"),
                Map.entry("organization_invitation.invited_by", "bigint"),
                Map.entry("organization_member.organization_id", "uuid"),
                Map.entry("organization_member.user_id", "bigint"),
                Map.entry("organization.id", "uuid"),
                Map.entry("organization.owner_id", "bigint"),
                Map.entry("users.id", "bigint"),
                Map.entry("user_roles.user_id", "bigint"),
                Map.entry("user_onboarding.user_id", "bigint"),
                Map.entry("user_changelog_seen.user_id", "bigint"),
                Map.entry("user_acquisition.user_id", "bigint"),
                Map.entry("refresh_tokens.user_id", "bigint"),
                Map.entry("email_verification_codes.user_id", "bigint"),
                Map.entry("credit_ledger.user_id", "bigint"),
                Map.entry("credit_reconciliation_log.user_id", "bigint"),
                Map.entry("credit_consumption_dead_letter.tenant_id", "character varying"),
                Map.entry("ce_link.user_id", "bigint"),
                Map.entry("ce_link.revoked_by_user_id", "bigint"),
                Map.entry("org_member_quota_limit.user_id", "bigint"),
                Map.entry("org_member_quota_limit.created_by_user_id", "bigint"),
                Map.entry("pending_credit_upgrade.user_id", "bigint"),
                Map.entry("billing_customer.user_id", "bigint"));
        expected.forEach((col, type) -> {
            String[] parts = col.split("\\.");
            String actual = jdbc.queryForObject(
                    "SELECT data_type FROM information_schema.columns "
                            + "WHERE table_schema = 'auth' AND table_name = ? AND column_name = ?",
                    String.class, parts[0], parts[1]);
            assertThat(actual).as(col).isEqualTo(type);
        });
    }

    @Test
    @DisplayName("a deactivated account with a personal workspace is deleted, rows and all")
    void personalAccountIsFullyPurged() throws Exception {
        User user = seedDeactivatedUser("solo");
        Organization org = seedOrg(user, "solo", true);
        memberRepository.save(new OrganizationMember(org, user, OrganizationRole.OWNER, true));
        invitationRepository.save(new OrganizationInvitation(org, "guest-" + user.getId() + "@test.local",
                OrganizationRole.MEMBER, user));
        onboardingRepository.save(new UserOnboarding(user, "Solo " + user.getId()));
        refreshTokenRepository.save(new RefreshToken("hash-" + UUID.randomUUID(), user,
                LocalDateTime.now().plusDays(1)));
        Long subId = seedSubscription(user, null);
        jdbc.update("INSERT INTO auth.usage_cycle (subscription_id, cycle_start, cycle_end) VALUES (?, now(), now())", subId);
        jdbc.update("INSERT INTO auth.credentials (tenant_id, name, type) VALUES (?, 'k', 'API_KEY')",
                user.getId().toString());
        jdbc.update("INSERT INTO auth.credentials (tenant_id, name, type, organization_id) VALUES ('someone', 'k', 'API_KEY', ?)",
                org.getId().toString());

        boolean purged = purgeService.purgeUser(user.getId());

        assertThat(purged).isTrue();
        Long uid = user.getId();
        UUID oid = org.getId();
        assertThat(count("auth.users WHERE id = ?", uid)).as("user row").isZero();
        assertThat(count("auth.user_roles WHERE user_id = ?", uid)).as("roles").isZero();
        assertThat(count("auth.user_onboarding WHERE user_id = ?", uid)).as("onboarding").isZero();
        assertThat(count("auth.refresh_tokens WHERE user_id = ?", uid)).as("refresh tokens").isZero();
        assertThat(count("auth.organization WHERE id = ?", oid)).as("personal org").isZero();
        assertThat(count("auth.organization_member WHERE organization_id = ?", oid)).as("members").isZero();
        assertThat(count("auth.organization_invitation WHERE organization_id = ?", oid)).as("invitations").isZero();
        assertThat(count("auth.billing_customer WHERE user_id = ?", uid)).as("billing customer").isZero();
        assertThat(count("auth.usage_cycle WHERE subscription_id = ?", subId)).as("usage cycles").isZero();
        assertThat(count("auth.credentials WHERE tenant_id = ?", uid.toString())).as("user credentials").isZero();
        assertThat(count("auth.credentials WHERE organization_id = ?", oid.toString())).as("org credentials").isZero();
        // The outbox promises the other services the same deletion.
        assertThat(count("auth.purge_log WHERE subject_type = 'ORG' AND subject_id = ?", oid.toString())).isEqualTo(1);
        assertThat(count("auth.purge_log WHERE subject_type = 'USER' AND subject_id = ?", uid.toString())).isEqualTo(1);
        // A FREE account: Stripe is never called, which is what used to doom the commit.
        verify(stripeBillingService, never()).cancelSubscriptionAtPeriodEnd(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("every row that references the user with NO ACTION / RESTRICT is removed or unlinked first")
    void everyRowThatBlocksTheUserDeleteIsRemoved() {
        User leaving = seedDeactivatedUser("fk");
        User other = seedActiveUser("fk-other");
        User third = seedActiveUser("fk-third");
        Organization othersTeam = seedOrg(other, "fk-team", false);
        memberRepository.save(new OrganizationMember(othersTeam, other, OrganizationRole.OWNER, true));
        memberRepository.save(new OrganizationMember(othersTeam, leaving, OrganizationRole.ADMIN, false));
        memberRepository.save(new OrganizationMember(othersTeam, third, OrganizationRole.MEMBER, false));
        // An invitation the leaving user sent in a workspace they do not own (invited_by NO ACTION).
        invitationRepository.save(new OrganizationInvitation(othersTeam, "invitee-" + leaving.getId() + "@test.local",
                OrganizationRole.MEMBER, leaving));
        // A quota the leaving user set on another member (created_by_user_id NO ACTION).
        tx.executeWithoutResult(s -> em.persist(new OrganizationMemberQuotaLimit(othersTeam.getId(), third.getId(), leaving.getId())));
        // Their own CE link (RESTRICT) and a link of someone else they revoked (NO ACTION).
        tx.executeWithoutResult(s -> em.persist(new CeLink(UUID.randomUUID(), leaving.getId(), "mine")));
        UUID othersInstall = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            CeLink link = new CeLink(othersInstall, other.getId(), "theirs");
            link.revoke(CeLink.RevokeReason.ADMIN, leaving.getId());
            em.persist(link);
        });
        // A pending upgrade tied to the user but to someone else's subscription (user_id NO ACTION).
        Long othersSub = seedSubscription(other, null);
        jdbc.update("INSERT INTO auth.pending_credit_upgrade (user_id, subscription_id, provider_subscription_id, "
                + "stripe_invoice_id, stripe_invoice_item_id, target_tier_index, target_credit_quantity, "
                + "target_credit_price_id, status, created_at, updated_at) "
                + "VALUES (?, ?, 'sub_x', ?, 'ii_x', 1, 1, 'price_x', 'pending', now(), now())",
                leaving.getId(), othersSub, "in_" + UUID.randomUUID());

        assertThat(purgeService.purgeUser(leaving.getId())).isTrue();

        Long uid = leaving.getId();
        assertThat(count("auth.users WHERE id = ?", uid)).as("user row").isZero();
        assertThat(count("auth.organization_invitation WHERE invited_by = ?", uid)).as("invitations sent").isZero();
        assertThat(count("auth.organization_member WHERE user_id = ?", uid)).as("foreign memberships").isZero();
        assertThat(count("auth.ce_link WHERE user_id = ?", uid)).as("own CE links").isZero();
        assertThat(jdbc.queryForObject("SELECT revoked_by_user_id FROM auth.ce_link WHERE install_id = ?", Long.class, othersInstall))
                .as("someone else's link survives, unlinked from the revoker").isNull();
        assertThat(jdbc.queryForObject("SELECT created_by_user_id FROM auth.org_member_quota_limit WHERE org_id = ? AND user_id = ?",
                Long.class, othersTeam.getId(), third.getId()))
                .as("the other member keeps the quota, unlinked from its author").isNull();
        assertThat(count("auth.pending_credit_upgrade WHERE user_id = ?", uid)).as("pending upgrades").isZero();
        assertThat(count("auth.organization WHERE id = ?", othersTeam.getId())).as("the other owner's team survives").isEqualTo(1);
    }

    @Test
    @DisplayName("a team workspace goes to the earliest ADMIN, promoted to owner, not to an earlier MEMBER")
    void teamOrgIsHandedToTheRemainingAdminAsOwner() {
        User leaving = seedDeactivatedUser("leaver");
        User member = seedActiveUser("member");
        User admin = seedActiveUser("admin");
        Organization team = seedOrg(leaving, "team", false);
        memberRepository.save(new OrganizationMember(team, leaving, OrganizationRole.OWNER, true));
        memberRepository.save(member(team, member, OrganizationRole.MEMBER, LocalDateTime.now().minusDays(10)));
        memberRepository.save(member(team, admin, OrganizationRole.ADMIN, LocalDateTime.now().minusDays(1)));

        assertThat(purgeService.purgeUser(leaving.getId())).isTrue();

        // Pre-fix the candidate lookup itself raised (uuid = varchar) and aborted the purge.
        assertThat(ownerOf(team)).as("ownership moves to the admin, though the member joined first")
                .isEqualTo(admin.getId());
        // The membership role is what every owner-only action checks; owner_id alone left the
        // team with nobody able to invite, pay or delete.
        assertThat(roleOf(team, admin)).isEqualTo("owner");
        assertThat(roleOf(team, member)).as("the other members are untouched").isEqualTo("member");
        assertThat(count("auth.organization_member WHERE organization_id = ? AND user_id = ?", team.getId(), leaving.getId()))
                .as("the departing member is removed").isZero();
        assertThat(count("auth.purge_log WHERE subject_type = 'ORG' AND subject_id = ?", team.getId().toString()))
                .as("a handed-over workspace is NOT purged by the followers").isZero();
    }

    @Test
    @DisplayName("with no admin left, a team workspace goes to the earliest member, promoted to owner")
    void teamOrgFallsBackToAPlainMember() {
        User leaving = seedDeactivatedUser("leaver2");
        User first = seedActiveUser("first");
        User second = seedActiveUser("second");
        Organization team = seedOrg(leaving, "team2", false);
        memberRepository.save(new OrganizationMember(team, leaving, OrganizationRole.OWNER, true));
        memberRepository.save(member(team, second, OrganizationRole.VIEWER, LocalDateTime.now().minusDays(1)));
        memberRepository.save(member(team, first, OrganizationRole.MEMBER, LocalDateTime.now().minusDays(5)));

        assertThat(purgeService.purgeUser(leaving.getId())).isTrue();

        assertThat(ownerOf(team)).isEqualTo(first.getId());
        assertThat(roleOf(team, first)).isEqualTo("owner");
    }

    @Test
    @DisplayName("a team workspace with nobody left is deleted with the account, members and invitations too")
    void emptyTeamOrgIsDeleted() {
        User leaving = seedDeactivatedUser("alone");
        Organization team = seedOrg(leaving, "empty-team", false);
        memberRepository.save(new OrganizationMember(team, leaving, OrganizationRole.OWNER, true));
        invitationRepository.save(new OrganizationInvitation(team, "never-" + leaving.getId() + "@test.local",
                OrganizationRole.MEMBER, leaving));

        assertThat(purgeService.purgeUser(leaving.getId())).isTrue();

        assertThat(count("auth.organization WHERE id = ?", team.getId())).isZero();
        assertThat(count("auth.organization_member WHERE organization_id = ?", team.getId())).isZero();
        assertThat(count("auth.organization_invitation WHERE organization_id = ?", team.getId())).isZero();
        assertThat(count("auth.purge_log WHERE subject_type = 'ORG' AND subject_id = ?", team.getId().toString()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("failing statements roll the whole purge back, leave the identity alone, and are all named")
    void failingStatementsRollEverythingBackAndAreAllNamed() {
        User user = seedDeactivatedUser("rollback");
        Organization org = seedOrg(user, "rollback", true);
        memberRepository.save(new OrganizationMember(org, user, OrganizationRole.OWNER, true));
        long logBefore = count("auth.purge_log WHERE 1 = ?", 1);

        // Three drifted tables, the class of failure the savepoints exist for: one pass must name
        // all of them, instead of one per night. org_resource_restrictions is deleted by
        // WorkspaceDataPurger's strict variant, whose failures must land in the same list.
        jdbc.execute("ALTER TABLE auth.credit_reconciliation_log RENAME TO credit_reconciliation_log_moved");
        jdbc.execute("ALTER TABLE auth.user_acquisition RENAME TO user_acquisition_moved");
        jdbc.execute("ALTER TABLE auth.org_resource_restrictions RENAME TO org_resource_restrictions_moved");
        try {
            assertThatThrownBy(() -> purgeService.purgeUser(user.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("3 statement(s) failed")
                    .hasMessageContaining("credit_reconciliation_log")
                    .hasMessageContaining("user_acquisition")
                    .hasMessageContaining("org_resource_restrictions")
                    .hasMessageContaining("identity was not touched")
                    // The savepoint kept the transaction alive: no statement reports the
                    // secondary "transaction is aborted" error that used to hide the real cause.
                    .hasMessageNotContaining("current transaction is aborted");
        } finally {
            jdbc.execute("ALTER TABLE auth.credit_reconciliation_log_moved RENAME TO credit_reconciliation_log");
            jdbc.execute("ALTER TABLE auth.user_acquisition_moved RENAME TO user_acquisition");
            jdbc.execute("ALTER TABLE auth.org_resource_restrictions_moved RENAME TO org_resource_restrictions");
        }

        // The identity is removed only once the database work succeeded, so the person can still
        // sign in and restore the account while it waits for the next pass.
        verify(restTemplate, never()).exchange(anyString(), eq(HttpMethod.DELETE), any(), eq(Void.class));
        // All or nothing: the statements that did succeed were rolled back with the rest.
        assertThat(count("auth.users WHERE id = ?", user.getId())).as("user row survives").isEqualTo(1);
        assertThat(count("auth.organization WHERE id = ?", org.getId())).as("org survives").isEqualTo(1);
        assertThat(count("auth.organization_member WHERE organization_id = ?", org.getId())).isEqualTo(1);
        assertThat(count("auth.purge_log WHERE 1 = ?", 1))
                .as("no outbox promise is left behind for a purge that did not happen").isEqualTo(logBefore);

        // And the next pass, once the tables are back, completes.
        assertThat(purgeService.purgeUser(user.getId())).isTrue();
        assertThat(count("auth.users WHERE id = ?", user.getId())).isZero();
    }

    @Test
    @DisplayName("a linked Stripe subscription is cancelled in its own transaction, then the purge commits")
    void linkedStripeSubscriptionIsCancelledThenPurged() throws Exception {
        User user = seedDeactivatedUser("stripe-ok");
        Long subId = seedSubscription(user, "sub_" + UUID.randomUUID());
        Long uid = user.getId();
        // The real method calls Stripe, then writes cancel_at_period_end on the local row. Only
        // the Stripe HTTP call is left out; the write is the part that could wait on the purge's
        // own locks, so it is performed for real, in the transaction the purge gives it.
        java.util.concurrent.atomic.AtomicReference<String> stripeTxName = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean stripeTxActive = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(inv -> {
            stripeTxActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            stripeTxName.set(String.valueOf(TransactionSynchronizationManager.getCurrentTransactionName()));
            jdbc.update("UPDATE auth.subscription SET cancel_at_period_end = true WHERE id = ?", subId);
            return Map.of("success", true);
        }).when(stripeBillingService).cancelSubscriptionAtPeriodEnd(eq(uid), anyString(), anyString());

        // A self-deadlock (the inner transaction waiting on a lock the purge holds) would hang
        // here rather than fail, hence the timeout.
        assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
                assertThat(purgeService.purgeUser(uid)).isTrue());

        verify(stripeBillingService).cancelSubscriptionAtPeriodEnd(eq(uid), anyString(), anyString());
        // Its own transaction, not the purge's (whose name is the purgeUser method): a joined one
        // is exactly what made every FREE purge fail at commit.
        assertThat(stripeTxActive).as("the cancellation runs inside a transaction").isTrue();
        assertThat(stripeTxName.get()).as("and it is not the purge's transaction")
                .doesNotContain("purgeUser");
        assertThat(count("auth.users WHERE id = ?", uid)).isZero();
        assertThat(count("auth.subscription WHERE id = ?", subId)).isZero();
    }

    @Test
    @DisplayName("a failing Stripe cancellation keeps the account and its subscription row")
    void failingStripeCancellationKeepsTheAccount() throws Exception {
        User user = seedDeactivatedUser("stripe-down");
        Long subId = seedSubscription(user, "sub_" + UUID.randomUUID());
        doThrow(new com.stripe.exception.ApiConnectionException("stripe unreachable"))
                .when(stripeBillingService).cancelSubscriptionAtPeriodEnd(eq(user.getId()), anyString(), anyString());

        assertThatThrownBy(() -> purgeService.purgeUser(user.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Stripe cancellation failed");

        // Deleting the subscription row would drop the only link to a subscription Stripe keeps
        // charging. It stays, with the account, for the next pass.
        assertThat(count("auth.users WHERE id = ?", user.getId())).isEqualTo(1);
        assertThat(count("auth.subscription WHERE id = ?", subId)).isEqualTo(1);
        verify(restTemplate, never()).exchange(anyString(), eq(HttpMethod.DELETE), any(), eq(Void.class));
    }

    // ----------------------------------------------------------------- fixtures

    private User seedDeactivatedUser(String tag) {
        User user = newUser(tag);
        user.setProviderId(UUID.randomUUID().toString());
        user.setEnabled(false);
        user.setDeactivatedAt(LocalDateTime.now().minusDays(40));
        user.getRoles().add("USER");
        return track(userRepository.save(user));
    }

    private User seedActiveUser(String tag) {
        return track(userRepository.save(newUser(tag)));
    }

    private User track(User saved) {
        seededUsers.add(saved.getId());
        return saved;
    }

    private static User newUser(String tag) {
        // username is @Size(max = 50): keep the unique suffix short.
        String handle = tag + "-" + UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setEmail(handle + "@test.local");
        user.setUsername(handle);
        return user;
    }

    private Organization seedOrg(User owner, String tag, boolean personal) {
        return organizationRepository.save(new Organization(tag + " workspace",
                tag + "-" + UUID.randomUUID(), personal, owner));
    }

    private static OrganizationMember member(Organization org, User user, OrganizationRole role, LocalDateTime joinedAt) {
        OrganizationMember m = new OrganizationMember(org, user, role, false);
        m.setJoinedAt(joinedAt);
        return m;
    }

    private Long ownerOf(Organization org) {
        return jdbc.queryForObject("SELECT owner_id FROM auth.organization WHERE id = ?", Long.class, org.getId());
    }

    private String roleOf(Organization org, User user) {
        return jdbc.queryForObject("SELECT role FROM auth.organization_member WHERE organization_id = ? AND user_id = ?",
                String.class, org.getId(), user.getId());
    }

    private Long seedSubscription(User user, String stripeSubscriptionId) {
        Plan plan = planRepository.findAll().stream()
                .filter(p -> "FREE".equals(p.getCode())).findFirst()
                .orElseGet(() -> {
                    Plan p = new Plan();
                    p.setCode("FREE");
                    p.setName("Free");
                    return planRepository.save(p);
                });
        BillingCustomer bc = billingCustomerRepository.save(new BillingCustomer(user,
                stripeSubscriptionId == null ? "internal" : "stripe"));
        Subscription sub = new Subscription();
        sub.setBillingCustomer(bc);
        sub.setPlan(plan);
        sub.setProvider(stripeSubscriptionId == null ? "internal" : "stripe");
        sub.setProviderSubscriptionId(stripeSubscriptionId);
        sub.setStatus("active");
        sub.setCadence("monthly");
        sub.setQuantity(1);
        sub.setCreditQuantity(0);
        sub.setCancelAtPeriodEnd(false);
        sub.setDelinquent(false);
        sub.setRemainingCredits(BigDecimal.ZERO);
        sub.setPaygRemainingCredits(BigDecimal.ZERO);
        sub.setCurrentPeriodStart(LocalDateTime.now().minusDays(3));
        sub.setCurrentPeriodEnd(LocalDateTime.now().plusDays(27));
        return subscriptionRepository.save(sub).getId();
    }

    private long count(String fromWhere, Object... args) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + fromWhere, Long.class, args);
        return n == null ? 0 : n;
    }
}
