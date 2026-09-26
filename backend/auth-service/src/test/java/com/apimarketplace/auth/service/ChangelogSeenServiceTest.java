package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.ChangelogEntryFirstSeenRepository;
import com.apimarketplace.auth.repository.UserChangelogSeenRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.ChangelogSeenService.SeenOutcome;
import com.apimarketplace.auth.service.ChangelogSeenService.ChangelogState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The acknowledgement half of the in-app changelog.
 *
 * <p>Four behaviours carry the feature and each fails silently when broken: the deployment switch
 * must make the service inert (not merely hide a panel), an acknowledgement must overwrite the
 * previous one in place (there is no history, and a second row would resurrect the panel), the
 * sealing of a brand-new account must be measured against THIS INSTALL's first sight of the entry
 * (never the entry's publication date, which would blank the announcement for every user a
 * late-upgrading self-hosted install signed up in between), and a key the store would refuse must
 * be rejected before it reaches a constraint.
 */
class ChangelogSeenServiceTest {

    private static final String ENTRY = "2026-09-whats-new";

    private UserChangelogSeenRepository seenRepository;
    private ChangelogEntryFirstSeenRepository firstSeenRepository;
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        seenRepository = mock(UserChangelogSeenRepository.class);
        firstSeenRepository = mock(ChangelogEntryFirstSeenRepository.class);
        userRepository = mock(UserRepository.class);
    }

    private ChangelogSeenService service(boolean enabled) {
        return new ChangelogSeenService(seenRepository, firstSeenRepository, userRepository, enabled);
    }

    /** A real User rather than a mock: stubbing one inside another when(...) is nested stubbing. */
    private User userCreatedAt(LocalDateTime createdAt) {
        User user = new User();
        user.setCreatedAt(createdAt);
        return user;
    }

    /** The account creation instant as this service reads it back (JVM zone, like the writer). */
    private Instant asStored(LocalDateTime createdAt) {
        return createdAt.atZone(ZoneId.systemDefault()).toInstant();
    }

    @Test
    @DisplayName("disabled: the state is empty and NO database read happens at all")
    void disabledStateTouchesNoRepository() {
        ChangelogState state = service(false).state(42L, ENTRY);

        assertThat(state.enabled()).isFalse();
        assertThat(state.seenKey()).isNull();
        assertThat(state.seal()).isFalse();
        // The switch must remove the feature, not hide it: a disabled deployment should not pay
        // for a per-request lookup of something it will never show.
        verifyNoInteractions(seenRepository);
        verifyNoInteractions(firstSeenRepository);
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("disabled: an acknowledgement is refused and nothing is written")
    void disabledMarkSeenWritesNothing() {
        assertThat(service(false).markSeen(42L, ENTRY)).isEqualTo(SeenOutcome.DISABLED);

        verify(seenRepository, never()).acknowledge(anyLong(), anyString());
        verify(seenRepository, never()).save(any());
    }

    @Test
    @DisplayName("an existing account is announced to: it predates this install's first sight of the entry")
    void existingAccountIsAnnouncedTo() {
        when(seenRepository.findEntryKey(42L)).thenReturn(Optional.empty());
        when(firstSeenRepository.findFirstSeenAt(ENTRY))
                .thenReturn(Optional.of(Instant.parse("2026-09-07T10:00:00Z")));
        when(userRepository.findById(42L))
                .thenReturn(Optional.of(userCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0))));

        ChangelogState state = service(true).state(42L, ENTRY);

        assertThat(state.enabled()).isTrue();
        assertThat(state.seenKey()).isNull();
        assertThat(state.seal()).isFalse();
    }

    @Test
    @DisplayName("an account created after this install started announcing the entry is sealed")
    void accountNewerThanTheInstallsFirstSightIsSealed() {
        Instant firstSeen = Instant.parse("2026-09-07T10:00:00Z");
        when(seenRepository.findEntryKey(42L)).thenReturn(Optional.empty());
        when(firstSeenRepository.findFirstSeenAt(ENTRY)).thenReturn(Optional.of(firstSeen));
        when(userRepository.findById(42L)).thenReturn(Optional.of(userCreatedAt(
                LocalDateTime.ofInstant(firstSeen.plusSeconds(3600), ZoneId.systemDefault()))));

        assertThat(service(true).state(42L, ENTRY).seal()).isTrue();
    }

    @Test
    @DisplayName("a LATE-UPGRADING install still announces to the users it signed up in between")
    void lateUpgradeStillAnnouncesToUsersSignedUpSinceThePublicationDate() {
        // The regression this rule exists for. The entry was published 2026-09-07; a self-hosted
        // install adopts that release six months later, on 2027-03-01. Every user it signed up in
        // between is NEWER than the publication date and OLDER than the install's first sight - and
        // must be announced to. Sealing on the publication date would blank the panel for all of
        // them, permanently and with nothing to show why.
        Instant installFirstSaw = Instant.parse("2027-03-01T09:00:00Z");
        when(seenRepository.findEntryKey(42L)).thenReturn(Optional.empty());
        when(firstSeenRepository.findFirstSeenAt(ENTRY)).thenReturn(Optional.of(installFirstSaw));
        when(userRepository.findById(42L))
                .thenReturn(Optional.of(userCreatedAt(LocalDateTime.of(2026, 11, 15, 12, 0))));

        assertThat(service(true).state(42L, ENTRY).seal()).isFalse();
    }

    @Test
    @DisplayName("the first request for an entry stamps this install's first sight and reads it back")
    void firstRequestStampsTheEntry() {
        Instant stamped = Instant.parse("2026-09-07T10:00:00Z");
        when(seenRepository.findEntryKey(42L)).thenReturn(Optional.empty());
        when(firstSeenRepository.findFirstSeenAt(ENTRY))
                .thenReturn(Optional.empty())      // nothing recorded yet
                .thenReturn(Optional.of(stamped)); // what the insert (or the race winner) left
        when(userRepository.findById(42L))
                .thenReturn(Optional.of(userCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0))));

        ChangelogState state = service(true).state(42L, ENTRY);

        verify(firstSeenRepository).stampIfAbsent(ENTRY);
        // Re-read rather than assumed: a concurrent first request may have won, and its EARLIER
        // instant is the one that must decide every subsequent seal.
        verify(firstSeenRepository, times(2)).findFirstSeenAt(ENTRY);
        assertThat(state.seal()).isFalse();
    }

    @Test
    @DisplayName("an already-acknowledged entry costs one query: no stamp, no account lookup")
    void acknowledgedEntrySkipsTheSealWork() {
        when(seenRepository.findEntryKey(7L)).thenReturn(Optional.of(ENTRY));

        ChangelogState state = service(true).state(7L, ENTRY);

        assertThat(state.seenKey()).isEqualTo(ENTRY);
        assertThat(state.seal()).isFalse();
        // This is the common case on every authenticated page load once a user has seen the entry.
        verifyNoInteractions(firstSeenRepository);
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("a caller with no entry gets the acknowledgement alone and never a seal")
    void nullEntryKeyAsksNoSealQuestion() {
        when(seenRepository.findEntryKey(7L)).thenReturn(Optional.of("2026-08-older"));

        ChangelogState state = service(true).state(7L, null);

        assertThat(state.seenKey()).isEqualTo("2026-08-older");
        assertThat(state.seal()).isFalse();
        verifyNoInteractions(firstSeenRepository);
    }

    @Test
    @DisplayName("a malformed entry key on the read path is ignored rather than stamped")
    void malformedEntryKeyIsNotStamped() {
        when(seenRepository.findEntryKey(7L)).thenReturn(Optional.empty());

        ChangelogState state = service(true).state(7L, "not a key");

        // The key is caller input; storing it would put junk in a table keyed by entry.
        assertThat(state.seal()).isFalse();
        verifyNoInteractions(firstSeenRepository);
    }

    @Test
    @DisplayName("an unknown user is announced to rather than sealed")
    void unknownUserIsAnnouncedTo() {
        when(seenRepository.findEntryKey(9L)).thenReturn(Optional.empty());
        when(firstSeenRepository.findFirstSeenAt(ENTRY))
                .thenReturn(Optional.of(Instant.parse("2026-09-07T10:00:00Z")));
        when(userRepository.findById(9L)).thenReturn(Optional.empty());

        // Failing towards one notice too many is harmless; failing the other way hides the panel
        // over something that is not the user's fault.
        assertThat(service(true).state(9L, ENTRY).seal()).isFalse();
    }

    @Test
    @DisplayName("a user with no stored creation date is announced to, not sealed")
    void nullCreatedAtIsAnnouncedTo() {
        when(seenRepository.findEntryKey(9L)).thenReturn(Optional.empty());
        when(firstSeenRepository.findFirstSeenAt(ENTRY))
                .thenReturn(Optional.of(Instant.parse("2026-09-07T10:00:00Z")));
        when(userRepository.findById(9L)).thenReturn(Optional.of(userCreatedAt(null)));

        assertThat(service(true).state(9L, ENTRY).seal()).isFalse();
    }

    @Test
    @DisplayName("the account instant is read back in the zone it was written in")
    void accountInstantIsReadInTheWritersZone() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 7, 12, 0);
        // Sealed only if createdAt is AFTER the stamp, so a stamp one second earlier in the SAME
        // zone must seal. Reading the column as UTC on a non-UTC JVM would move this boundary.
        when(seenRepository.findEntryKey(42L)).thenReturn(Optional.empty());
        when(firstSeenRepository.findFirstSeenAt(ENTRY))
                .thenReturn(Optional.of(asStored(createdAt).minusSeconds(1)));
        when(userRepository.findById(42L)).thenReturn(Optional.of(userCreatedAt(createdAt)));

        assertThat(service(true).state(42L, ENTRY).seal()).isTrue();
    }

    @Test
    @DisplayName("an acknowledgement is ONE upsert, so two tabs cannot race into a duplicate key")
    void acknowledgementIsASingleUpsert() {
        when(seenRepository.acknowledge(42L, ENTRY)).thenReturn(1);

        assertThat(service(true).markSeen(42L, ENTRY)).isEqualTo(SeenOutcome.RECORDED);

        // Read-then-write would race: the @Id is assigned, so save() selects then inserts, and two
        // concurrent first acknowledgements both see "no row". The upsert is what removes that.
        verify(seenRepository).acknowledge(42L, ENTRY);
        verify(seenRepository, never()).findEntryKey(anyLong());
        verify(seenRepository, never()).save(any());
    }

    @Test
    @DisplayName("acknowledging an OLDER key is accepted, because a rollback ships an older entry")
    void olderKeyIsAcceptedOnRollback() {
        // No monotonic guard on purpose: after a rollback the running build announces its own,
        // older entry, and the user must be able to dismiss THAT one for good.
        when(seenRepository.acknowledge(42L, "2026-08-entry")).thenReturn(1);

        assertThat(service(true).markSeen(42L, "2026-08-entry")).isEqualTo(SeenOutcome.RECORDED);

        verify(seenRepository).acknowledge(42L, "2026-08-entry");
    }

    @Test
    @DisplayName("re-acknowledging the same key is idempotent: same statement, still one row")
    void reAcknowledgingIsIdempotent() {
        ChangelogSeenService service = service(true);
        when(seenRepository.acknowledge(42L, ENTRY)).thenReturn(1);

        assertThat(service.markSeen(42L, ENTRY)).isEqualTo(SeenOutcome.RECORDED);
        assertThat(service.markSeen(42L, ENTRY)).isEqualTo(SeenOutcome.RECORDED);

        verify(seenRepository, times(2)).acknowledge(42L, ENTRY);
    }

    @Test
    @DisplayName("changelog seen for a deleted user: the upsert writes 0 rows and the outcome says UNKNOWN_USER")
    void changelogSeenForDeletedUserIsUnknownUserNotAnError() {
        // Prod 2026-09-22: a session that outlived the deletion of its account (the gateway caches
        // user resolution) sent its old id, the insert tripped the foreign key and the request
        // answered 500. The upsert now writes nothing for an absent user; the service must read
        // that 0 as its own outcome rather than claim the acknowledgement was stored.
        when(seenRepository.acknowledge(404L, ENTRY)).thenReturn(0);

        assertThat(service(true).markSeen(404L, ENTRY)).isEqualTo(SeenOutcome.UNKNOWN_USER);

        verify(seenRepository).acknowledge(404L, ENTRY);
        // No separate existence read: the check lives inside the one statement, so there is no
        // window for a delete to land between a read and the write.
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("the write path rejects a key it would not be able to store")
    void markSeenValidatesTheKeyItself() {
        // Validated here and not only at the edge: this is the method that writes the row, so a
        // caller added later would otherwise turn an over-long key into a constraint violation at
        // flush time instead of a clean rejection.
        assertThatThrownBy(() -> service(true).markSeen(42L, "a".repeat(121)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service(true).markSeen(42L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service(true).markSeen(42L, "has space"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(seenRepository, never()).acknowledge(anyLong(), anyString());
    }

    @Test
    @DisplayName("a bad key is refused even on a deployment where the feature is off")
    void invalidKeyIsRefusedBeforeTheDisabledShortCircuit() {
        // Otherwise "disabled" would quietly accept values that the enabled deployment rejects,
        // and the difference would only surface the day the switch is flipped back on.
        assertThatThrownBy(() -> service(false).markSeen(42L, "<script>"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("key validation accepts real keys and rejects anything a browser could invent")
    void keyValidation() {
        assertThat(ChangelogSeenService.isValidKey("2026-09-whats-new")).isTrue();
        assertThat(ChangelogSeenService.isValidKey("v1.2.3_entry")).isTrue();
        assertThat(ChangelogSeenService.isValidKey("a")).isTrue();
        assertThat(ChangelogSeenService.isValidKey("a".repeat(120))).isTrue();

        assertThat(ChangelogSeenService.isValidKey(null)).isFalse();
        assertThat(ChangelogSeenService.isValidKey("")).isFalse();
        assertThat(ChangelogSeenService.isValidKey("-leading-dash")).isFalse();
        assertThat(ChangelogSeenService.isValidKey("has space")).isFalse();
        assertThat(ChangelogSeenService.isValidKey("<script>")).isFalse();
        assertThat(ChangelogSeenService.isValidKey("a".repeat(121))).isFalse();
    }

    @Test
    @DisplayName("the enabled flag is what the caller reads, not a hardcoded true")
    void enabledFlagIsReported() {
        assertThat(service(true).isEnabled()).isTrue();
        assertThat(service(false).isEnabled()).isFalse();
    }
}
