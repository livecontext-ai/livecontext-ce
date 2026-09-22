package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.domain.UserOnboarding;
import com.apimarketplace.auth.domain.UserProfileEntity;
import com.apimarketplace.auth.dto.PublicProfileDto;
import com.apimarketplace.auth.dto.UserProfileUpdateRequest;
import com.apimarketplace.auth.repository.UserOnboardingRepository;
import com.apimarketplace.auth.repository.UserProfileRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.validation.AgeValidator;
import com.apimarketplace.auth.validation.UsernameValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The public @handle is the whole path segment of {@code /app/u/{handle}}, so
 * it has to be a URL slug, not a username.
 *
 * <p>It was a username: the handle was slugified with
 * {@link UsernameValidator#normalize}, whose {@code [a-z0-9._-]} class is right
 * for an account name that may look like an email and wrong for a path segment.
 * The display name "theo p." produced the handle {@code theo_p.}, and
 * {@code /app/u/theo_p.} answered the not-found page with a 200 while
 * {@code /app/u/theo_p} would have rendered: a healthy, enabled, UNLISTED
 * account whose profile page simply could not be opened, with no error raised
 * anywhere. Five accounts in production were in that state.
 *
 * <p>These tests use the REAL {@link UsernameValidator}, not a mock: the defect
 * lived in the seam between what it returns and what a URL accepts, and a
 * stubbed normalize would have let either side be wrong unnoticed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("public @handle is URL-safe")
class PublicHandleUrlSafetyTest {

    @Mock private UserRepository userRepository;
    @Mock private UserOnboardingRepository onboardingRepository;
    @Mock private UserProfileRepository userProfileRepository;
    @Mock private AgeValidator ageValidator;
    @Mock private com.apimarketplace.common.storage.service.StorageService storageService;
    @Mock private AccountDeactivationMailer deactivationMailer;
    @Mock private VerifiedAccountService verifiedAccountService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        UsernameValidator usernameValidator = new UsernameValidator(userRepository);
        userService = new UserService(userRepository, onboardingRepository, userProfileRepository,
                usernameValidator, ageValidator, storageService, deactivationMailer, verifiedAccountService);
        when(userProfileRepository.existsByHandle(anyString())).thenReturn(false);
        when(userProfileRepository.findByHandle(anyString())).thenReturn(Optional.empty());
    }

    private User enabledUser(long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEnabled(true);
        return user;
    }

    /** The handle the lazy generator persists on the first view of a profile. */
    private String generatedHandleFor(String displayName) {
        User user = enabledUser(1L, "account_name");
        UserOnboarding onboarding = new UserOnboarding();
        onboarding.setDisplayName(displayName);
        when(onboardingRepository.findByUserId(1L)).thenReturn(Optional.of(onboarding));
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());

        Optional<PublicProfileDto> profile = userService.getPublicProfile(user);
        assertThat(profile).isPresent();
        return profile.get().handle();
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
            // The exact display name that shipped an unreachable profile.
            "'theo p.',           theo_p",
            "'Sophie Mercier',    sophie_mercier",
            "'antoine.d',         antoine_d",
            "'Marion  Delcourt',  marion_delcourt",
            "'.leading',          leading",
            "'trailing...',       trailing",
            "'a.b.c',             a_b_c",
    })
    @DisplayName("a generated handle never carries a dot, wherever the name put one")
    void generatedHandleIsUrlSafe(String displayName, String expected) {
        assertThat(generatedHandleFor(displayName)).isEqualTo(expected);
    }

    @Test
    @DisplayName("accents are still folded, as they always were")
    void accentsAreStillFolded() {
        assertThat(generatedHandleFor("Aïcha Ndiaye")).isEqualTo("aicha_ndiaye");
    }

    @Test
    @DisplayName("a name that slugifies to nothing still yields a usable handle")
    void unusableNameFallsBackRatherThanProducingAnEmptyPath() {
        // An empty handle would build the URL /app/u/ , which is a different
        // page entirely - the fallback is what keeps every profile addressable.
        assertThat(generatedHandleFor("...")).isEqualTo("user");
    }

    @Test
    @DisplayName("a handle typed with dots in settings is folded, not silently rejected")
    void userSuppliedHandleIsFolded() {
        User user = enabledUser(1L, "account_name");
        UserProfileEntity profile = new UserProfileEntity(1L);
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));

        UserProfileUpdateRequest request = new UserProfileUpdateRequest();
        request.setHandle("Jade.L");
        userService.updateProfile(user, request);

        ArgumentCaptor<UserProfileEntity> captor = ArgumentCaptor.forClass(UserProfileEntity.class);
        verify(userProfileRepository).save(captor.capture());
        // Pre-fix this stored "jade.l", which the pattern accepted and the router
        // could not reach. Folding beats refusing: the user gets the handle they
        // meant rather than a silently ignored save.
        assertThat(captor.getValue().getHandle()).isEqualTo("jade_l");
    }

    @Test
    @DisplayName("a handle that slugifies to fewer than two characters is refused, not stored")
    void tooShortHandleIsRefused() {
        User user = enabledUser(1L, "account_name");
        UserProfileEntity profile = new UserProfileEntity(1L);
        profile.setHandle("keepme");
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));

        UserProfileUpdateRequest request = new UserProfileUpdateRequest();
        request.setHandle("x");
        userService.updateProfile(user, request);

        ArgumentCaptor<UserProfileEntity> captor = ArgumentCaptor.forClass(UserProfileEntity.class);
        verify(userProfileRepository).save(captor.capture());
        assertThat(captor.getValue().getHandle()).isEqualTo("keepme");
    }
}
