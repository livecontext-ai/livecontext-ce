package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.domain.UserProfileEntity;
import com.apimarketplace.auth.repository.UserProfileRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.UserService;
import com.apimarketplace.auth.service.VerifiedAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same feature on a SELF-HOSTED deployment, where it must not exist.
 *
 * <p>A self-hosted install promotes its very first user to ADMIN
 * ({@code FirstAdminBootstrap}), so a role-derived badge would hand a blue check to
 * every solo install owner, which says nothing to anyone. The gate lives in
 * {@code VerifiedAccountService} so it holds for every surface at once.
 *
 * <p>The decisive case is {@link #storedGrantIsIgnored()}: the flag is genuinely
 * {@code true} in the database and still reads as unverified. A gate that only
 * skipped the query would pass a weaker test and leak the badge on a cloud-linked
 * install whose rows came from elsewhere.
 *
 * <p>The four flags beside {@code app.edition} are not decoration: the edition
 * provider refuses to start CE_FREE with cloud auth or billing settings, so a context
 * without them fails before a single assertion runs.
 */
@IntegrationTest
@Import(IntegrationTestConfig.class)
@TestPropertySource(properties = {
        "app.edition=ce",
        "auth.mode=embedded",
        "credit.unlimited=true",
        "billing.provider=none",
        "plan-limits.enabled=false",
})
@DisplayName("Verified accounts on a self-hosted install")
class VerifiedAccountCeIntegrationTest {

    @Autowired private VerifiedAccountService verifiedAccountService;
    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserProfileRepository userProfileRepository;

    private Long adminId;
    private Long grantedId;

    @BeforeEach
    void setUp() {
        adminId = persistUser("vac-admin", Set.of("USER", "ADMIN"), "vac-admin", false);
        grantedId = persistUser("vac-granted", Set.of("USER"), "vac-granted", true);
    }

    private Long persistUser(String username, Set<String> roles, String handle, boolean manuallyVerified) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setAuthProvider(AuthProvider.LOCAL);
        user.setProviderId("it-" + username);
        user.setEnabled(true);
        user.setRoles(roles);
        User saved = userRepository.save(user);

        UserProfileEntity profile = new UserProfileEntity(saved.getId());
        profile.setHandle(handle);
        profile.setProfileVisibility(UserProfileEntity.VISIBILITY_PUBLIC);
        profile.setVerified(manuallyVerified);
        userProfileRepository.save(profile);
        return saved.getId();
    }

    @Test
    @DisplayName("the feature reports itself off")
    void featureIsOff() {
        assertThat(verifiedAccountService.isFeatureEnabled()).isFalse();
    }

    @Test
    @DisplayName("the platform admin is NOT verified - the first install user is always one")
    void adminIsNotVerified() {
        assertThat(verifiedAccountService.isVerified(userRepository.findById(adminId).orElseThrow())).isFalse();
    }

    @Test
    @DisplayName("a grant STORED in the database still reads as unverified")
    void storedGrantIsIgnored() {
        assertThat(userProfileRepository.findByUserId(grantedId).orElseThrow().isVerified()).isTrue();

        assertThat(verifiedAccountService.isVerified(userRepository.findById(grantedId).orElseThrow())).isFalse();
        assertThat(userService.getPublicProfile(userRepository.findById(grantedId).orElseThrow())
                .orElseThrow().verified()).isFalse();
    }

    @Test
    @DisplayName("both batch lookups answer empty")
    void batchLookupsAreEmpty() {
        assertThat(verifiedAccountService.verifiedAmong(List.of(adminId, grantedId))).isEmpty();
        assertThat(verifiedAccountService.verifiedHandlesAmong(List.of("vac-admin", "vac-granted"))).isEmpty();
    }

    @Test
    @DisplayName("the admin write is refused and stores nothing")
    void writeIsRefused() {
        assertThat(verifiedAccountService.setManualVerified(adminId, true, adminId)).isEmpty();

        assertThat(userProfileRepository.findByUserId(adminId).orElseThrow().isVerified()).isFalse();
    }
}
