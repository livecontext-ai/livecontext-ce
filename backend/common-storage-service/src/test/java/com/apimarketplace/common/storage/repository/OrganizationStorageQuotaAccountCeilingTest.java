package com.apimarketplace.common.storage.repository;

import com.apimarketplace.common.storage.domain.OrganizationStorageQuota;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which ceiling an account's shared pool is measured against, run against a real database rather
 * than a stubbed repository.
 *
 * <p>This exists because the rule cannot be verified by mocking the method that implements it.
 * An earlier round ordered the rows by {@code updated_at} and every service test stubbed the
 * lookup, so they all passed while the query itself was wrong in a way that could lock an entire
 * account out of its storage: {@code updated_at} is bumped by every UPLOAD, so one small write
 * into a workspace still carrying the 100 MB default would make that row the freshest, drop the
 * account's ceiling to 100 MB, refuse every workspace at once, and leave no way back, since
 * repairing it needs a write that is now blocked.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("Account ceiling resolution")
class OrganizationStorageQuotaAccountCeilingTest {

    private static final String ACCOUNT = "acct-1";
    private static final long TEAM = 107_374_182_400L; // 100 GB
    private static final long FREE = 104_857_600L;     // 100 MB

    @Autowired private TestEntityManager em;
    @Autowired private OrganizationStorageQuotaRepository repository;

    /** A row as the migration leaves it: attributed, but with no allowance clock yet. */
    private OrganizationStorageQuota legacyRow(String orgId, long ceiling) {
        OrganizationStorageQuota q = new OrganizationStorageQuota(orgId, ceiling);
        q.setAccountId(ACCOUNT);
        q.setUsedBytes(0L);
        q.setLimitsUpdatedAt(null);
        return q;
    }

    private OrganizationStorageQuota row(String orgId, long ceiling, Instant limitsWrittenAt) {
        OrganizationStorageQuota q = new OrganizationStorageQuota(orgId, ceiling);
        q.setAccountId(ACCOUNT);
        q.setUsedBytes(0L);
        q.setLimitsUpdatedAt(limitsWrittenAt);
        q.setUpdatedAt(limitsWrittenAt);
        return em.persist(q);
    }

    @Test
    @DisplayName("takes the ceiling whose ALLOWANCE was written last")
    void freshestAllowanceWins() {
        Instant old = Instant.now().minus(10, ChronoUnit.DAYS);
        row("org-stale", FREE, old);
        row("org-current", TEAM, old.plus(1, ChronoUnit.DAYS));
        em.flush();

        assertThat(repository.currentCeilingForAccount(ACCOUNT)).contains(TEAM);
    }

    @Test
    @DisplayName("a downgrade binds: the newer, SMALLER allowance wins over an older larger one")
    void downgradeWins() {
        Instant old = Instant.now().minus(10, ChronoUnit.DAYS);
        row("org-was-team", TEAM, old);
        row("org-downgraded", FREE, old.plus(1, ChronoUnit.DAYS));
        em.flush();

        assertThat(repository.currentCeilingForAccount(ACCOUNT)).contains(FREE);
    }

    @Test
    @DisplayName("an UPLOAD into a stale workspace does not move the account's ceiling")
    void usageWriteDoesNotChangeTheCeiling() {
        // The failure this rule exists to prevent, reproduced end to end: a stale FREE row gets
        // written to (used_bytes + updated_at change, the allowance does not), and the account's
        // ceiling must stay the TEAM allowance. Ordering on updated_at returns FREE here.
        Instant old = Instant.now().minus(10, ChronoUnit.DAYS);
        OrganizationStorageQuota stale = row("org-stale", FREE, old);
        row("org-current", TEAM, old.plus(1, ChronoUnit.DAYS));
        em.flush();

        stale.setUsedBytes(1024L);
        stale.setUpdatedAt(Instant.now()); // now the most recently written-to row
        em.persist(stale);
        em.flush();

        assertThat(repository.currentCeilingForAccount(ACCOUNT)).contains(TEAM);
    }

    @Test
    @DisplayName("a stamped row beats an unstamped legacy one, whichever is larger")
    void stampedBeatsUnstamped() {
        row("org-current", TEAM, Instant.now().minus(1, ChronoUnit.DAYS));
        em.persist(legacyRow("org-legacy", FREE));
        em.flush();

        assertThat(repository.currentCeilingForAccount(ACCOUNT)).contains(TEAM);
    }

    @Test
    @DisplayName("a downgrade still binds against an unstamped legacy row")
    void stampedDowngradeBeatsUnstampedLegacy() {
        // The stamped row is the smaller one here: freshness decides, not size.
        row("org-downgraded", FREE, Instant.now());
        em.persist(legacyRow("org-legacy", TEAM));
        em.flush();

        assertThat(repository.currentCeilingForAccount(ACCOUNT)).contains(FREE);
    }

    @Test
    @DisplayName("no row stamped yet -> the LARGEST allowance, never an arbitrary one")
    void unstampedAccountFallsBackToTheWidest() {
        // Every row migrated in before the allowance clock existed looks like this. Picking a row
        // arbitrarily could land on one still holding the 100 MB default and refuse a paying
        // account outright; the maximum can only be too generous, and only until the next real
        // allowance write settles it.
        em.persist(legacyRow("org-a", FREE));
        em.persist(legacyRow("org-b", TEAM));
        em.flush();

        assertThat(repository.currentCeilingForAccount(ACCOUNT)).contains(TEAM);
    }

    @Test
    @DisplayName("an account with no attributed workspace has no ceiling to report")
    void noRowsNoCeiling() {
        assertThat(repository.currentCeilingForAccount("acct-unknown")).isEmpty();
    }

    @Test
    @DisplayName("only this account's workspaces count toward its ceiling and its total")
    void otherAccountsAreNotCounted() {
        row("org-mine", TEAM, Instant.now().minus(1, ChronoUnit.DAYS));
        OrganizationStorageQuota theirs = new OrganizationStorageQuota("org-theirs", 5L);
        theirs.setAccountId("acct-2");
        theirs.setUsedBytes(999L);
        theirs.setLimitsUpdatedAt(Instant.now());
        em.persist(theirs);
        em.flush();

        assertThat(repository.currentCeilingForAccount(ACCOUNT)).contains(TEAM);
        assertThat(repository.sumUsedBytesForAccount(ACCOUNT)).isZero();
    }

    @Test
    @DisplayName("the total sums every workspace of the account, counting each once")
    void sumsTheAccountsWorkspaces() {
        Instant now = Instant.now();
        OrganizationStorageQuota a = row("org-a", TEAM, now);
        OrganizationStorageQuota b = row("org-b", TEAM, now);
        a.setUsedBytes(3_000L);
        b.setUsedBytes(4_000L);
        em.persist(a);
        em.persist(b);
        em.flush();

        assertThat(repository.sumUsedBytesForAccount(ACCOUNT)).isEqualTo(7_000L);
    }
}
