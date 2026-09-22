package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.domain.UserProfileEntity;
import com.apimarketplace.auth.repository.UserProfileRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.common.web.AppEditionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("VerifiedAccountService")
class VerifiedAccountServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private UserProfileRepository userProfileRepository;
    @Mock private AppEditionProvider editionProvider;

    private VerifiedAccountService service;

    @BeforeEach
    void setUp() {
        service = new VerifiedAccountService(userRepository, userProfileRepository, editionProvider);
        onManagedCloud();
        // JpaRepository.save always hands the entity back, and the service reads the saved
        // row to compute what the target's readers will actually see. A mock returning
        // null here would be a fiction that NPEs. Lenient because the read-only cases
        // never save.
        lenient().when(userProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /**
     * Lenient on this one stub only: every self-hosted case overrides it, which strict
     * stubbing would report as an unused stubbing. Kept narrow deliberately - a
     * class-level LENIENT would switch the check off for every other stub in the file.
     */
    private void onManagedCloud() {
        lenient().when(editionProvider.isManagedCloud()).thenReturn(true);
    }

    private void onSelfHosted() {
        when(editionProvider.isManagedCloud()).thenReturn(false);
    }

    private static User user(long id, boolean enabled, String... roles) {
        User u = new User();
        u.setId(id);
        u.setEnabled(enabled);
        u.setRoles(Set.of(roles));
        return u;
    }

    private static UserProfileEntity profile(long userId, boolean verified) {
        UserProfileEntity p = new UserProfileEntity(userId);
        p.setVerified(verified);
        return p;
    }

    private static UserProfileEntity privateProfile(long userId, boolean verified) {
        UserProfileEntity p = profile(userId, verified);
        p.setProfileVisibility(UserProfileEntity.VISIBILITY_PRIVATE);
        return p;
    }

    @Nested
    @DisplayName("isVerified()")
    class IsVerified {

        @Test
        @DisplayName("a platform admin is verified by the role alone, with nothing stored on the profile")
        void adminRoleGrantsTheBadge() {
            when(userProfileRepository.findByUserId(7L)).thenReturn(Optional.empty());

            assertThat(service.isVerified(user(7L, true, "USER", "ADMIN"))).isTrue();
            // The profile row IS read, but only to check that the page has not been
            // withdrawn - nothing about the grant itself is stored there for an admin.
            verify(userProfileRepository).findByUserId(7L);
        }

        @Test
        @DisplayName("a non-admin with the manual grant is verified")
        void manualGrantIsHonoured() {
            when(userProfileRepository.findByUserId(7L)).thenReturn(Optional.of(profile(7L, true)));

            assertThat(service.isVerified(user(7L, true, "USER"))).isTrue();
        }

        @Test
        @DisplayName("a non-admin without a grant is not verified")
        void plainUserIsNotVerified() {
            when(userProfileRepository.findByUserId(7L)).thenReturn(Optional.of(profile(7L, false)));

            assertThat(service.isVerified(user(7L, true, "USER"))).isFalse();
        }

        @Test
        @DisplayName("a user with no profile row at all is not verified")
        void missingProfileIsNotVerified() {
            when(userProfileRepository.findByUserId(7L)).thenReturn(Optional.empty());

            assertThat(service.isVerified(user(7L, true, "USER"))).isFalse();
        }

        @Test
        @DisplayName("a PRIVATE profile loses the badge, even for an admin - it is a PUBLIC claim")
        void withdrawnProfileLosesTheBadge() {
            // The user took their public page down. Keeping a public claim about them
            // would tell an anonymous caller more than /by-handle does, which answers
            // 404 for exactly this state.
            when(userProfileRepository.findByUserId(7L)).thenReturn(Optional.of(privateProfile(7L, true)));

            assertThat(service.isVerified(user(7L, true, "ADMIN"))).isFalse();
            assertThat(service.isVerified(user(7L, true, "USER"))).isFalse();
        }

        @Test
        @DisplayName("an UNLISTED profile keeps the badge - unlisted is the default, not a withdrawal")
        void unlistedProfileKeepsTheBadge() {
            UserProfileEntity unlisted = profile(7L, true);
            unlisted.setProfileVisibility(UserProfileEntity.VISIBILITY_UNLISTED);
            when(userProfileRepository.findByUserId(7L)).thenReturn(Optional.of(unlisted));

            assertThat(service.isVerified(user(7L, true, "USER"))).isTrue();
        }

        @Test
        @DisplayName("the overload trusts the row it is handed, and never re-reads it")
        void overloadUsesTheGivenRow() {
            // What UserService.getPublicProfile calls: it already holds the row.
            assertThat(service.isVerified(user(7L, true, "USER"), profile(7L, true))).isTrue();
            assertThat(service.isVerified(user(7L, true, "USER"), privateProfile(7L, true))).isFalse();
            // No row at all: the account never edited its profile, which is visible by
            // default, so an admin still carries the badge.
            assertThat(service.isVerified(user(7L, true, "ADMIN"), null)).isTrue();
            assertThat(service.isVerified(user(7L, true, "USER"), null)).isFalse();

            verify(userProfileRepository, never()).findByUserId(any());
        }

        @Test
        @DisplayName("a DISABLED admin loses the badge - it is a claim about a live account")
        void disabledAccountIsNeverVerified() {
            assertThat(service.isVerified(user(7L, false, "ADMIN"))).isFalse();
        }

        @Test
        @DisplayName("null user answers false instead of throwing")
        void nullUserIsNotVerified() {
            assertThat(service.isVerified(null)).isFalse();
        }

        @Test
        @DisplayName("on a self-hosted deployment even an admin is not verified, and no query runs")
        void selfHostedIsAlwaysFalse() {
            onSelfHosted();

            assertThat(service.isVerified(user(7L, true, "ADMIN"))).isFalse();
            verifyNoInteractions(userProfileRepository);
        }
    }

    @Nested
    @DisplayName("verifiedAmong()")
    class VerifiedAmong {

        @Test
        @DisplayName("unions the role-granted and the manually granted ids")
        void unionsBothSources() {
            when(userRepository.findAdminIdsIn(anyCollection())).thenReturn(List.of(1L));
            when(userProfileRepository.findManuallyVerifiedIdsIn(anyCollection())).thenReturn(List.of(2L));

            assertThat(service.verifiedAmong(List.of(1L, 2L, 3L))).containsExactlyInAnyOrder(1L, 2L);
        }

        @Test
        @DisplayName("an id whose profile is PRIVATE is subtracted, whichever source granted it")
        void privateProfilesAreSubtracted() {
            when(userRepository.findAdminIdsIn(anyCollection())).thenReturn(List.of(1L));
            when(userProfileRepository.findManuallyVerifiedIdsIn(anyCollection())).thenReturn(List.of(2L));
            when(userProfileRepository.findPrivateProfileIdsIn(anyCollection())).thenReturn(List.of(1L, 2L));

            assertThat(service.verifiedAmong(List.of(1L, 2L))).isEmpty();
        }

        @Test
        @DisplayName("an id verified by BOTH sources appears once")
        void deduplicatesAcrossSources() {
            when(userRepository.findAdminIdsIn(anyCollection())).thenReturn(List.of(1L));
            when(userProfileRepository.findManuallyVerifiedIdsIn(anyCollection())).thenReturn(List.of(1L));

            assertThat(service.verifiedAmong(List.of(1L, 1L))).containsExactly(1L);
        }

        @Test
        @DisplayName("duplicate and null inputs are collapsed before the query runs")
        void normalisesTheInput() {
            when(userRepository.findAdminIdsIn(anyCollection())).thenReturn(List.of());
            when(userProfileRepository.findManuallyVerifiedIdsIn(anyCollection())).thenReturn(List.of());

            service.verifiedAmong(java.util.Arrays.asList(5L, 5L, null, 6L));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Collection<Long>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
            verify(userRepository).findAdminIdsIn(captor.capture());
            assertThat(captor.getValue()).containsExactlyInAnyOrder(5L, 6L);
        }

        @Test
        @DisplayName("an empty or all-null input answers empty without querying")
        void emptyInputShortCircuits() {
            assertThat(service.verifiedAmong(List.of())).isEmpty();
            assertThat(service.verifiedAmong(java.util.Collections.singletonList(null))).isEmpty();
            verifyNoInteractions(userRepository);
        }

        @Test
        @DisplayName("on a self-hosted deployment it answers empty without querying")
        void selfHostedAnswersEmpty() {
            onSelfHosted();

            assertThat(service.verifiedAmong(List.of(1L, 2L))).isEmpty();
            verifyNoInteractions(userRepository);
            verifyNoInteractions(userProfileRepository);
        }
    }

    @Nested
    @DisplayName("verifiedHandlesAmong()")
    class VerifiedHandlesAmong {

        @Test
        @DisplayName("matches a handle case-insensitively and answers with the caller's spelling")
        void echoesTheRequestedSpelling() {
            when(userProfileRepository.findIdsByHandles(anyCollection()))
                    .thenReturn(List.<Object[]>of(new Object[]{"ada", 1L}));
            when(userRepository.findAdminIdsIn(anyCollection())).thenReturn(List.of(1L));
            when(userProfileRepository.findManuallyVerifiedIdsIn(anyCollection())).thenReturn(List.of());

            assertThat(service.verifiedHandlesAmong(List.of("Ada"))).containsExactly("Ada");
        }

        @Test
        @DisplayName("a handle whose owner is not verified is absent from the answer")
        void unverifiedHandleIsAbsent() {
            when(userProfileRepository.findIdsByHandles(anyCollection()))
                    .thenReturn(List.<Object[]>of(new Object[]{"ada", 1L}));
            when(userRepository.findAdminIdsIn(anyCollection())).thenReturn(List.of());
            when(userProfileRepository.findManuallyVerifiedIdsIn(anyCollection())).thenReturn(List.of());

            assertThat(service.verifiedHandlesAmong(List.of("ada"))).isEmpty();
        }

        @Test
        @DisplayName("an unknown handle is simply absent - it cannot be used to probe existence")
        void unknownHandleIsAbsent() {
            when(userProfileRepository.findIdsByHandles(anyCollection())).thenReturn(List.<Object[]>of());

            assertThat(service.verifiedHandlesAmong(List.of("nobody"))).isEmpty();
        }

        @Test
        @DisplayName("blank and null handles are dropped before the query runs")
        void dropsBlankHandles() {
            assertThat(service.verifiedHandlesAmong(java.util.Arrays.asList(null, "", "   "))).isEmpty();
            verifyNoInteractions(userProfileRepository);
        }

        @Test
        @DisplayName("on a self-hosted deployment it answers empty without querying")
        void selfHostedAnswersEmpty() {
            onSelfHosted();

            assertThat(service.verifiedHandlesAmong(List.of("ada"))).isEmpty();
            verifyNoInteractions(userProfileRepository);
        }
    }

    @Nested
    @DisplayName("setManualVerified()")
    class SetManualVerified {

        @Test
        @DisplayName("granting stamps the flag, the time and the granting admin")
        void grantStampsProvenance() {
            when(userRepository.findById(7L)).thenReturn(Optional.of(user(7L, true, "USER")));
            when(userProfileRepository.findByUserId(7L)).thenReturn(Optional.of(profile(7L, false)));

            Optional<VerifiedAccountService.VerificationState> state =
                    service.setManualVerified(7L, true, 42L);

            ArgumentCaptor<UserProfileEntity> saved = ArgumentCaptor.forClass(UserProfileEntity.class);
            verify(userProfileRepository).save(saved.capture());
            assertThat(saved.getValue().isVerified()).isTrue();
            assertThat(saved.getValue().getVerifiedAt()).isNotNull();
            assertThat(saved.getValue().getVerifiedBy()).isEqualTo(42L);
            assertThat(state).isPresent();
            assertThat(state.get().manuallyVerified()).isTrue();
            assertThat(state.get().verifiedByRole()).isFalse();
            assertThat(state.get().effectivelyVerified()).isTrue();
        }

        @Test
        @DisplayName("revoking clears the flag AND its provenance, so no stale grant date survives")
        void revokeClearsProvenance() {
            UserProfileEntity existing = profile(7L, true);
            existing.setVerifiedAt(java.time.LocalDateTime.now());
            existing.setVerifiedBy(42L);
            when(userRepository.findById(7L)).thenReturn(Optional.of(user(7L, true, "USER")));
            when(userProfileRepository.findByUserId(7L)).thenReturn(Optional.of(existing));

            service.setManualVerified(7L, false, 42L);

            ArgumentCaptor<UserProfileEntity> saved = ArgumentCaptor.forClass(UserProfileEntity.class);
            verify(userProfileRepository).save(saved.capture());
            assertThat(saved.getValue().isVerified()).isFalse();
            assertThat(saved.getValue().getVerifiedAt()).isNull();
            assertThat(saved.getValue().getVerifiedBy()).isNull();
        }

        @Test
        @DisplayName("a user who never edited their profile can still be verified - the row is created")
        void createsTheProfileRowWhenAbsent() {
            when(userRepository.findById(7L)).thenReturn(Optional.of(user(7L, true, "USER")));
            when(userProfileRepository.findByUserId(7L)).thenReturn(Optional.empty());

            service.setManualVerified(7L, true, 42L);

            ArgumentCaptor<UserProfileEntity> saved = ArgumentCaptor.forClass(UserProfileEntity.class);
            verify(userProfileRepository).save(saved.capture());
            assertThat(saved.getValue().getUserId()).isEqualTo(7L);
            assertThat(saved.getValue().isVerified()).isTrue();
        }

        @Test
        @DisplayName("revoking on an admin reports that the role still grants the badge")
        void revokeOnAdminReportsRoleGrant() {
            when(userRepository.findById(7L)).thenReturn(Optional.of(user(7L, true, "ADMIN")));
            when(userProfileRepository.findByUserId(7L)).thenReturn(Optional.of(profile(7L, true)));

            Optional<VerifiedAccountService.VerificationState> state =
                    service.setManualVerified(7L, false, 42L);

            assertThat(state).isPresent();
            assertThat(state.get().manuallyVerified()).isFalse();
            assertThat(state.get().verifiedByRole()).isTrue();
            // What the target's readers see is unchanged - the admin screen says so.
            assertThat(state.get().effectivelyVerified()).isTrue();
            assertThat(state.get().profileWithdrawn()).isFalse();
        }

        @Test
        @DisplayName("granting on a WITHDRAWN profile stores the flag and reports it as dormant")
        void grantOnWithdrawnProfileIsReportedDormant() {
            // Otherwise the screen would tell the operator the badge is showing, when no
            // visitor can see it - the exact lie this field exists to prevent.
            when(userRepository.findById(7L)).thenReturn(Optional.of(user(7L, true, "USER")));
            when(userProfileRepository.findByUserId(7L)).thenReturn(Optional.of(privateProfile(7L, false)));

            Optional<VerifiedAccountService.VerificationState> state =
                    service.setManualVerified(7L, true, 42L);

            assertThat(state).isPresent();
            assertThat(state.get().manuallyVerified()).isTrue();
            assertThat(state.get().profileWithdrawn()).isTrue();
            assertThat(state.get().effectivelyVerified()).isFalse();
        }

        @Test
        @DisplayName("an unknown user answers empty and writes nothing")
        void unknownUserWritesNothing() {
            when(userRepository.findById(7L)).thenReturn(Optional.empty());

            assertThat(service.setManualVerified(7L, true, 42L)).isEmpty();
            verify(userProfileRepository, never()).save(any());
        }

        @Test
        @DisplayName("on a self-hosted deployment the write is refused outright")
        void selfHostedRefusesTheWrite() {
            onSelfHosted();

            assertThat(service.setManualVerified(7L, true, 42L)).isEmpty();
            verifyNoInteractions(userRepository);
            verifyNoInteractions(userProfileRepository);
        }
    }

    @Nested
    @DisplayName("isFeatureEnabled()")
    class FeatureFlag {

        @Test
        @DisplayName("true on managed cloud, false on a self-hosted deployment")
        void tracksTheEdition() {
            assertThat(service.isFeatureEnabled()).isTrue();
            onSelfHosted();
            assertThat(service.isFeatureEnabled()).isFalse();
        }
    }
}
