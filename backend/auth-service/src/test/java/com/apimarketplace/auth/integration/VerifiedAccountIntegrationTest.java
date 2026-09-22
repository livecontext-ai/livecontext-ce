package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.domain.UserProfileEntity;
import com.apimarketplace.auth.dto.PublicProfileDto;
import com.apimarketplace.auth.repository.UserProfileRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.UserService;
import com.apimarketplace.auth.service.VerifiedAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verified badge against a REAL persistence context (H2, real repositories, real
 * queries), on a managed-cloud deployment, which is what the {@code integration-test}
 * profile resolves to.
 *
 * <p>The unit suite mocks both repositories, so it can prove the SERVICE logic but
 * says nothing about whether the three hand-written queries behind it actually run.
 * {@code findManuallyVerifiedIdsIn} joins two entities that have no JPA association
 * ({@code FROM UserProfileEntity p, User u WHERE u.id = p.userId}) and
 * {@code findAdminIdsIn} joins an element collection: both are exactly the shape that
 * compiles, passes a mocked test, and then throws at runtime. This is what executes
 * them.
 */
@IntegrationTest
@Import(IntegrationTestConfig.class)
@DisplayName("Verified accounts on managed cloud")
class VerifiedAccountIntegrationTest {

    @Autowired private VerifiedAccountService verifiedAccountService;
    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserProfileRepository userProfileRepository;

    private Long adminId;
    private Long plainId;
    private Long grantedId;
    private Long disabledAdminId;
    private Long privateAdminId;
    private Long privateGrantedId;

    @BeforeEach
    void setUp() {
        adminId = persistUser("vaa-admin", true, Set.of("USER", "ADMIN"), "vaa-admin", false);
        plainId = persistUser("vaa-plain", true, Set.of("USER"), "vaa-plain", false);
        grantedId = persistUser("vaa-granted", true, Set.of("USER"), "vaa-granted", true);
        disabledAdminId = persistUser("vaa-exadmin", false, Set.of("USER", "ADMIN"), "vaa-exadmin", true);
        // Both badge sources, both behind a WITHDRAWN profile page.
        privateAdminId = persistUser("vaa-hidden-admin", true, Set.of("USER", "ADMIN"),
                "vaa-hidden-admin", false, UserProfileEntity.VISIBILITY_PRIVATE);
        privateGrantedId = persistUser("vaa-hidden-granted", true, Set.of("USER"),
                "vaa-hidden-granted", true, UserProfileEntity.VISIBILITY_PRIVATE);
    }

    private Long persistUser(String username, boolean enabled, Set<String> roles,
                             String handle, boolean manuallyVerified) {
        return persistUser(username, enabled, roles, handle, manuallyVerified,
                UserProfileEntity.VISIBILITY_PUBLIC);
    }

    private Long persistUser(String username, boolean enabled, Set<String> roles,
                             String handle, boolean manuallyVerified, String visibility) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setAuthProvider(AuthProvider.LOCAL);
        user.setProviderId("it-" + username);
        user.setEnabled(enabled);
        user.setRoles(roles);
        User saved = userRepository.save(user);

        UserProfileEntity profile = new UserProfileEntity(saved.getId());
        profile.setHandle(handle);
        profile.setProfileVisibility(visibility);
        profile.setVerified(manuallyVerified);
        userProfileRepository.save(profile);
        return saved.getId();
    }

    @Test
    @DisplayName("the ADMIN role grants the badge with nothing stored on the profile")
    void adminIsVerifiedByRole() {
        assertThat(verifiedAccountService.isVerified(userRepository.findById(adminId).orElseThrow())).isTrue();
        assertThat(userProfileRepository.findByUserId(adminId).orElseThrow().isVerified()).isFalse();
    }

    @Test
    @DisplayName("a plain account is not verified, a manually granted one is")
    void manualGrantIsHonoured() {
        assertThat(verifiedAccountService.isVerified(userRepository.findById(plainId).orElseThrow())).isFalse();
        assertThat(verifiedAccountService.isVerified(userRepository.findById(grantedId).orElseThrow())).isTrue();
    }

    @Test
    @DisplayName("the batch lookup unions both sources in ONE answer, and skips a disabled account")
    void batchLookupRunsBothQueries() {
        Set<Long> verified = verifiedAccountService.verifiedAmong(
                List.of(adminId, plainId, grantedId, disabledAdminId, privateAdminId, privateGrantedId));

        // adminId via the role query, grantedId via the profile query. The disabled
        // account is excluded by BOTH, even though it is an admin AND carries the flag:
        // a badge is a claim about a live account. The two withdrawn profiles are
        // subtracted by the third query.
        assertThat(verified).containsExactlyInAnyOrder(adminId, grantedId);
    }

    @Test
    @DisplayName("a WITHDRAWN profile page takes the badge with it, from both sources and both lookups")
    void withdrawnProfileIsExcluded() {
        // The reason this matters is the anonymous handle lookup: /by-handle answers 404
        // for a PRIVATE profile, so a badge answer for the same handle would disclose
        // strictly more - "this withdrawn account is verified", which today means
        // "this withdrawn account is a platform admin".
        assertThat(verifiedAccountService.verifiedAmong(List.of(privateAdminId, privateGrantedId)))
                .isEmpty();
        assertThat(verifiedAccountService.verifiedHandlesAmong(
                List.of("vaa-hidden-admin", "vaa-hidden-granted"))).isEmpty();
        assertThat(verifiedAccountService.isVerified(
                userRepository.findById(privateAdminId).orElseThrow())).isFalse();
    }

    @Test
    @DisplayName("an id nobody knows is simply absent, so the answer is not an existence oracle")
    void unknownIdIsAbsent() {
        assertThat(verifiedAccountService.verifiedAmong(List.of(adminId, 987654321L)))
                .containsExactly(adminId);
    }

    @Test
    @DisplayName("the handle lookup resolves case-insensitively and answers the caller's spelling")
    void handleLookupRunsForReal() {
        Set<String> verified = verifiedAccountService.verifiedHandlesAmong(
                List.of("VAA-Admin", "vaa-plain", "vaa-granted", "nobody-at-all"));

        assertThat(verified).containsExactlyInAnyOrder("VAA-Admin", "vaa-granted");
    }

    @Test
    @DisplayName("the public profile carries the badge, which is what every surface reads")
    void publicProfileCarriesTheFlag() {
        PublicProfileDto adminProfile = userService.getPublicProfile(
                userRepository.findById(adminId).orElseThrow()).orElseThrow();
        PublicProfileDto plainProfile = userService.getPublicProfile(
                userRepository.findById(plainId).orElseThrow()).orElseThrow();

        assertThat(adminProfile.verified()).isTrue();
        assertThat(plainProfile.verified()).isFalse();
    }

    @Test
    @DisplayName("granting is visible on the very next read, with no republish or backfill")
    void grantIsVisibleImmediately() {
        Optional<VerifiedAccountService.VerificationState> state =
                verifiedAccountService.setManualVerified(plainId, true, adminId);

        assertThat(state).isPresent();
        assertThat(state.get().effectivelyVerified()).isTrue();
        assertThat(verifiedAccountService.verifiedAmong(List.of(plainId))).containsExactly(plainId);
        assertThat(userService.getPublicProfile(userRepository.findById(plainId).orElseThrow())
                .orElseThrow().verified()).isTrue();
    }

    @Test
    @DisplayName("revoking on an admin changes the stored flag but not what readers see")
    void revokeOnAdminLeavesTheRoleGrant() {
        verifiedAccountService.setManualVerified(adminId, false, adminId);

        assertThat(verifiedAccountService.verifiedAmong(List.of(adminId))).containsExactly(adminId);
    }

    @Test
    @DisplayName("granting on a withdrawn profile is stored, and reported as showing nowhere")
    void grantOnWithdrawnProfileIsDormant() {
        Optional<VerifiedAccountService.VerificationState> state =
                verifiedAccountService.setManualVerified(privateAdminId, true, adminId);

        assertThat(state).isPresent();
        assertThat(state.get().manuallyVerified()).isTrue();
        assertThat(state.get().profileWithdrawn()).isTrue();
        // The whole point: the admin screen must not report a badge no visitor can see.
        assertThat(state.get().effectivelyVerified()).isFalse();
        assertThat(verifiedAccountService.verifiedAmong(List.of(privateAdminId))).isEmpty();
    }

    @Test
    @DisplayName("a user with no profile row can be verified: the row is created")
    void createsTheProfileRowWhenAbsent() {
        User fresh = new User();
        fresh.setUsername("vaa-fresh");
        fresh.setEmail("vaa-fresh@example.com");
        fresh.setAuthProvider(AuthProvider.LOCAL);
        fresh.setProviderId("it-vaa-fresh");
        fresh.setEnabled(true);
        fresh.setRoles(Set.of("USER"));
        Long freshId = userRepository.save(fresh).getId();
        assertThat(userProfileRepository.findByUserId(freshId)).isEmpty();

        verifiedAccountService.setManualVerified(freshId, true, adminId);

        assertThat(userProfileRepository.findByUserId(freshId).orElseThrow().isVerified()).isTrue();
        assertThat(verifiedAccountService.verifiedAmong(List.of(freshId))).containsExactly(freshId);
    }
}
