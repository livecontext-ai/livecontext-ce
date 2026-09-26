package com.apimarketplace.orchestrator.integration.repository;

import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity.RequestStatus;
import com.apimarketplace.orchestrator.repository.ChatAuthorizationRequestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deadline, enforced by the statement that takes the decision.
 *
 * <p>A request is retired by a scheduler: on a timer, 200 rows a pass. Between the moment its TTL
 * runs out and the moment that sweep reaches it, the row still reads {@code SENT} and the message
 * is still sitting in somebody's chat with two live buttons. A press in that window used to be
 * honoured, which authorized an action on a run that had already been told its question expired,
 * and in an unattended agent that question has usually been asked and answered again since. The
 * service checks the deadline before it claims, but a check and a write are two statements, so the
 * predicate lives in the claim itself: that is what makes the answer to "may this still be
 * decided?" unable to change in between.
 *
 * <p>These run the SHIPPED JPQL through a real persistence context rather than asserting on the
 * annotation's text, so a predicate that does not translate, or that Hibernate applies to the
 * wrong column, fails here instead of in production.
 */
@DataJpaIntegrationTest
@DisplayName("chat authorization claim - the deadline is part of the statement")
class ChatAuthorizationRequestRepositoryClaimPredicateTest {

    private static final String TENANT = "tenant-a";
    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");

    @Autowired
    private ChatAuthorizationRequestRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    /** A delivered request, still {@code SENT}, with the given deadline (null for none). */
    private ChatAuthorizationRequestEntity delivered(Instant expiresAt) {
        ChatAuthorizationRequestEntity row = new ChatAuthorizationRequestEntity();
        row.setTenantId(TENANT);
        // No request scope in a sliced context, so the listener cannot fill this one in.
        row.setOrganizationId(TENANT);
        row.setLinkId(UUID.randomUUID());
        row.setChannel("telegram");
        row.setCredentialId(9L);
        row.setChatId("-100123");
        row.setCallbackToken(UUID.randomUUID().toString());
        row.setConversationId("conv-1");
        row.setGateKey("call-" + UUID.randomUUID());
        row.setRule("publish_post");
        row.setFingerprint(UUID.randomUUID().toString());
        row.setMessageId("555");
        row.setStatus(RequestStatus.SENT);
        row.setExpiresAt(expiresAt);
        entityManager.persist(row);
        entityManager.flush();
        return row;
    }

    /**
     * A delivered question of one call's group, written through the REPOSITORY.
     *
     * <p>Not through {@code TestEntityManager}: these two tests deliberately run with no
     * ambient transaction, which is the condition under test, and that helper needs one. The
     * repository's own {@code save} brings its own, exactly as it does on the webhook thread.
     */
    private UUID savedInGroup(String groupKey, String conversationId) {
        ChatAuthorizationRequestEntity row = new ChatAuthorizationRequestEntity();
        row.setTenantId(TENANT);
        row.setOrganizationId(TENANT);
        row.setLinkId(UUID.randomUUID());
        row.setChannel("telegram");
        row.setCredentialId(9L);
        row.setChatId("-100123");
        row.setCallbackToken(UUID.randomUUID().toString());
        row.setConversationId(conversationId);
        row.setGateKey("call-" + UUID.randomUUID() + ":ask");
        row.setGroupKey(groupKey);
        row.setRule("ask_user");
        row.setFingerprint(UUID.randomUUID().toString());
        row.setMessageId("555");
        row.setStatus(RequestStatus.RESOLVED);
        row.setExpiresAt(NOW.plusSeconds(3600));
        return repository.save(row).getId();
    }

    /** Read back through the repository, for the same reason. */
    private Instant appliedAt(UUID id) {
        return repository.findById(id)
                .map(ChatAuthorizationRequestEntity::getAppliedAt)
                .orElse(null);
    }

    /** Re-read from the database, past the first-level cache the claim clears. */
    private ChatAuthorizationRequestEntity reload(ChatAuthorizationRequestEntity row) {
        entityManager.clear();
        return entityManager.find(ChatAuthorizationRequestEntity.class, row.getId());
    }

    @Test
    @DisplayName("claims a request whose deadline has not passed")
    void claimsALiveRequest() {
        ChatAuthorizationRequestEntity row = delivered(NOW.plusSeconds(3600));

        assertThat(repository.claim(row.getId(), NOW)).isEqualTo(1);
        assertThat(reload(row).getStatus()).isEqualTo(RequestStatus.RESOLVED);
    }

    @Test
    @DisplayName("refuses a request whose deadline has passed, even though the sweep has not been round")
    void refusesARequestPastItsDeadline() {
        // The state that only exists between the TTL and the sweep: overdue and still SENT.
        ChatAuthorizationRequestEntity row = delivered(NOW.minusSeconds(1));

        assertThat(repository.claim(row.getId(), NOW)).isZero();
        // Untouched, not half-claimed: the sweep must still find it and close its message.
        assertThat(reload(row).getStatus()).isEqualTo(RequestStatus.SENT);
    }

    @Test
    @DisplayName("refuses a request at the exact instant it expires")
    void refusesOnTheDeadlineItself() {
        ChatAuthorizationRequestEntity row = delivered(NOW);

        // The deadline is the moment the request is over, not the last moment it is live.
        // The boundary is worth pinning because it is the one an off-by-one rewrites silently.
        assertThat(repository.claim(row.getId(), NOW)).isZero();
        assertThat(reload(row).getStatus()).isEqualTo(RequestStatus.SENT);
    }

    @Test
    @DisplayName("cannot even store a request without a deadline")
    void everyRequestCarriesADeadline() {
        // Why the claim needs no null branch, asserted rather than assumed: the deadline is
        // mandatory, so "is it expired?" is a question every row can answer.
        //
        // The specific exception matters. isInstanceOf(Exception.class) passes on ANY failure,
        // including a typo in the fixture, which would make this test green while proving
        // nothing. The DDL here is derived from the ENTITY (@Column(nullable = false)), not
        // from the migration, so what this pins is the entity contract; relaxing the column in
        // a migration alone would not fail here, and the claim predicate would then silently
        // skip those rows.
        assertThatThrownBy(() -> delivered(null))
                .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class)
                .hasMessageContaining("EXPIRES_AT");
    }

    @Test
    @DisplayName("still lets exactly one of two presses through")
    void secondPressStillLoses() {
        ChatAuthorizationRequestEntity row = delivered(NOW.plusSeconds(3600));

        // The property the claim existed for before the deadline was added to it: adding a
        // predicate to a conditional UPDATE is exactly how the original condition gets lost.
        assertThat(repository.claim(row.getId(), NOW)).isEqualTo(1);
        assertThat(repository.claim(row.getId(), NOW)).isZero();
    }

    @Test
    @DisplayName("the group claim runs where it is really called from: a thread with no transaction")
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void groupClaimWorksWithoutAnAmbientTransaction() {
        UUID first = savedInGroup("group-1", "conv-9");
        UUID second = savedInGroup("group-1", "conv-9");

        // NOT_SUPPORTED because that is the truth about the caller: the hand-over runs on the
        // webhook's async thread, which has no transaction of its own. A @Modifying query with
        // no @Transactional throws TransactionRequiredException there and nowhere else, so a
        // test that inherits the class's transaction would pass against code that cannot work.
        // It shipped exactly that way once: the claim meant to make the hand-over happen ONCE
        // made it happen never, and the throw became a single counter increment.
        assertThat(repository.claimGroupForApply("conv-9", "group-1", NOW)).isEqualTo(2);

        // And it is a claim, not a timestamp: the second caller matches nothing.
        assertThat(repository.claimGroupForApply("conv-9", "group-1", NOW)).isZero();
        assertThat(appliedAt(first)).isNotNull();
        assertThat(appliedAt(second)).isNotNull();
    }

    @Test
    @DisplayName("the group claim takes one conversation's rows, never another's")
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void groupClaimIsScopedToItsConversation() {
        UUID mine = savedInGroup("shared-key", "conv-a");
        UUID theirs = savedInGroup("shared-key", "conv-b");

        // The group key is minted per delivery now, but the scope is belt and braces: a
        // provider that mints its own ids (Gemini numbers calls call_0, call_1) is what made
        // this collide in the first place, and a claim that crossed conversations would hand
        // one workspace's answers to another's agent.
        assertThat(repository.claimGroupForApply("conv-a", "shared-key", NOW)).isEqualTo(1);
        assertThat(appliedAt(mine)).isNotNull();
        assertThat(appliedAt(theirs)).isNull();
    }

    @Test
    @DisplayName("a claim handed back can be claimed again, while the deadline holds")
    void releasedClaimIsClaimableAgain() {
        ChatAuthorizationRequestEntity row = delivered(NOW.plusSeconds(3600));
        assertThat(repository.claim(row.getId(), NOW)).isEqualTo(1);

        // The decision could not be applied, so the row goes back. It must be answerable
        // again: a question nobody answered may not sit there looking decided.
        assertThat(repository.releaseClaim(row.getId())).isEqualTo(1);
        assertThat(reload(row).getStatus()).isEqualTo(RequestStatus.SENT);
        assertThat(repository.claim(row.getId(), NOW)).isEqualTo(1);
    }

    @Test
    @DisplayName("a claim handed back after its deadline is not claimable again")
    void releasedClaimPastTheDeadlineStaysUnclaimable() {
        ChatAuthorizationRequestEntity row = delivered(NOW.plusSeconds(3600));
        assertThat(repository.claim(row.getId(), NOW)).isEqualTo(1);
        assertThat(repository.releaseClaim(row.getId())).isEqualTo(1);

        // Handing the row back restores SENT, which is what the duplicate rule reads. It must
        // not also restore answerability past the deadline: the retry belongs to the agent's
        // next run, not to a button pressed a day later.
        Instant afterwards = NOW.plusSeconds(7200);
        assertThat(repository.claim(row.getId(), afterwards)).isZero();
        assertThat(reload(row).getStatus()).isEqualTo(RequestStatus.SENT);
    }
}
