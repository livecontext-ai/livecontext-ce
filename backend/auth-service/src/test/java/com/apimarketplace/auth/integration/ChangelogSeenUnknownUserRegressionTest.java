package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserChangelogSeenRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.ChangelogSeenService;
import com.apimarketplace.auth.service.ChangelogSeenService.SeenOutcome;
import com.apimarketplace.auth.web.ChangelogController;
import com.apimarketplace.auth.web.ChangelogController.SeenRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /api/changelog/seen} for a user id with no {@code auth.users} row, on a REAL Postgres.
 *
 * <p><b>The incident.</b> Prod, 2026-09-22: five HTTP 500 on this endpoint. The gateway caches the
 * resolution of a user for minutes, so a session outlives the deletion of its account and keeps
 * sending the old id. The acknowledgement was a plain {@code INSERT ... VALUES ... ON CONFLICT}
 * upsert, which tripped the {@code user_changelog_seen_user_id_fkey} foreign key (V480) and
 * surfaced as a {@code DataIntegrityViolationException}.
 *
 * <p><b>Why a real Postgres, and why the foreign key is created by hand.</b> Only Postgres
 * enforces the constraint, and this suite's schema comes from {@code ddl-auto}, which cannot
 * create it: the entity maps {@code user_id} as a plain {@code Long}, not a relation. Without the
 * hand-made constraint every test here would pass against the broken code. {@link #fkIsLive} makes
 * that failure mode loud instead of silent.
 */
@SpringBootTest
@DisplayName("Changelog acknowledgement for a deleted user (real Postgres, real foreign key)")
class ChangelogSeenUnknownUserRegressionTest extends AuthScratchPostgresSpringTest {

    private static final String FK = "user_changelog_seen_user_id_fkey";
    private static final String ENTRY = "2026-09-entry";

    @Autowired private ChangelogSeenService service;
    @Autowired private ChangelogController controller;
    @Autowired private UserChangelogSeenRepository seenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;

    private final List<Long> seededUsers = new ArrayList<>();

    @BeforeEach
    void addTheProductionForeignKey() {
        // Mirrors V480: user_id bigint PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE.
        jdbc.execute("ALTER TABLE auth.user_changelog_seen DROP CONSTRAINT IF EXISTS " + FK);
        jdbc.execute("ALTER TABLE auth.user_changelog_seen ADD CONSTRAINT " + FK
                + " FOREIGN KEY (user_id) REFERENCES auth.users(id) ON DELETE CASCADE");
        assertThat(fkIsLive()).as("the regression cannot be observed without the foreign key").isTrue();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM auth.user_changelog_seen");
        for (Long id : seededUsers) {
            jdbc.update("DELETE FROM auth.users WHERE id = ?", id);
        }
        seededUsers.clear();
        // The context (and so the schema) is shared with other Postgres suites: leave it as found.
        jdbc.execute("ALTER TABLE auth.user_changelog_seen DROP CONSTRAINT IF EXISTS " + FK);
    }

    private boolean fkIsLive() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = ? AND contype = 'f'", Integer.class, FK);
        return n != null && n == 1;
    }

    private Long newUser(String email) {
        User u = new User();
        u.setEmail(email);
        u.setUsername(email);
        Long id = userRepository.save(u).getId();
        seededUsers.add(id);
        return id;
    }

    private long unusedUserId() {
        Long max = jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM auth.users", Long.class);
        return (max == null ? 0 : max) + 1_000_000L;
    }

    private int seenRows(long userId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM auth.user_changelog_seen WHERE user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("changelog seen for a deleted user: no foreign-key violation, nothing written, UNKNOWN_USER")
    void changelogSeenForDeletedUserDoesNotViolateTheForeignKey() {
        long ghost = unusedUserId();

        // Pre-fix: DataIntegrityViolationException on user_changelog_seen_user_id_fkey, i.e. a 500.
        SeenOutcome outcome = service.markSeen(ghost, ENTRY);

        assertThat(outcome).isEqualTo(SeenOutcome.UNKNOWN_USER);
        assertThat(seenRows(ghost)).isZero();
    }

    @Test
    @DisplayName("the endpoint answers 404 for that session, never 500")
    void endpointAnswers404ForADeletedUser() {
        long ghost = unusedUserId();

        ResponseEntity<?> response = controller.markSeen(String.valueOf(ghost), new SeenRequest(ENTRY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(seenRows(ghost)).isZero();
    }

    @Test
    @DisplayName("an existing user is still acknowledged, and a new key overwrites the row in place")
    void existingUserIsAcknowledgedAndUpserted() {
        Long id = newUser("changelog-seen@test.local");

        assertThat(service.markSeen(id, "2026-08-entry")).isEqualTo(SeenOutcome.RECORDED);
        assertThat(service.markSeen(id, ENTRY)).isEqualTo(SeenOutcome.RECORDED);

        assertThat(seenRows(id)).isEqualTo(1);
        assertThat(seenRepository.findEntryKey(id)).contains(ENTRY);
    }

    @Test
    @DisplayName("an account deleted WHILE the acknowledgement runs is UNKNOWN_USER, not a foreign-key race")
    void accountDeletedConcurrentlyIsUnknownUser() throws Exception {
        Long id = newUser("changelog-race@test.local");
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);

        // Holds an uncommitted delete of the account open while the acknowledgement runs.
        CompletableFuture<Void> deleter = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM auth.users WHERE id = ?", id);
            deleted.countDown();
            try {
                assertThat(commit.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }));
        assertThat(deleted.await(30, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<SeenOutcome> ack = CompletableFuture.supplyAsync(() -> service.markSeen(id, ENTRY));
        // FOR KEY SHARE makes the acknowledgement wait for the delete rather than read a row that
        // is about to disappear and then fail on the foreign key.
        Thread.sleep(500);
        assertThat(ack).as("the acknowledgement waits on the pending delete").isNotDone();

        commit.countDown();
        deleter.get(30, TimeUnit.SECONDS);

        assertThat(ack.get(30, TimeUnit.SECONDS)).isEqualTo(SeenOutcome.UNKNOWN_USER);
        assertThat(seenRows(id)).isZero();
    }
}
