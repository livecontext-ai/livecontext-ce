package com.apimarketplace.catalog.service.http;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.service.UserCredentialService;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.apimarketplace.credential.client.dto.CredentialScopesDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A pinned credential id may only reach the provider it belongs to.
 *
 * <p>The id resolves through an auth-side lookup that checks the caller OWNS
 * the credential and nothing else. Ownership stops one tenant reading another's
 * key. It does not stop a caller sending their OWN key for provider Q to
 * provider P, which turns a pinned id into a way to have any secret in the
 * account decrypted into an {@code Authorization} header aimed at a host the
 * caller chooses through {@code tool_id}.
 *
 * <p>Every picker on every surface already offers only credentials of the bound
 * integration. These tests are the server saying the same thing to callers that
 * are not pickers: a workflow plan written by hand, and the tool parameters an
 * agent sends.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpExecutionService - a pinned credential stays inside its own integration")
class HttpExecutionServicePinnedCredentialScopeTest {

    private static final String USER = "tenant-1";
    private static final Long PINNED = 42L;

    @Mock private ApiToolParameterRepository apiToolParameterRepository;
    @Mock private UserCredentialService userCredentialService;
    @Mock private CredentialEncryptionService encryptionService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private RestTemplate restTemplate;

    private HttpExecutionService service;

    @BeforeEach
    void setUp() {
        lenient().when(apiToolParameterRepository.findByApiToolId(any())).thenReturn(List.of());
        service = new HttpExecutionService(
                apiToolParameterRepository, userCredentialService, encryptionService,
                new ObjectMapper(), jdbcTemplate, restTemplate, new ErrorPolicyEngine());

        CredentialModeContext.setExplicitSource("user");
        CredentialModeContext.setSelectedCredentialId(PINNED);
    }

    @AfterEach
    void tearDown() {
        CredentialModeContext.clear();
    }

    private static CredentialScopesDto summary(String integration, String name) {
        CredentialScopesDto dto = new CredentialScopesDto();
        dto.setIntegration(integration);
        dto.setName(name);
        return dto;
    }

    private static com.apimarketplace.credential.client.dto.AccessTokenResult token(String value) {
        com.apimarketplace.credential.client.dto.AccessTokenResult result =
                new com.apimarketplace.credential.client.dto.AccessTokenResult();
        result.setAccessToken(value);
        result.setFound(true);
        return result;
    }

    private static ApiEntity api() {
        ApiEntity api = new ApiEntity();
        api.setPlatformCredentialName(null);
        return api;
    }

    @Test
    @DisplayName("a pin on ANOTHER provider's key is ignored, and the integration's default runs instead")
    void aForeignPinIsIgnored() {
        // The account holds a Stripe key. The call is to a Gmail endpoint. If
        // the pin were honoured, the decrypted Stripe secret would leave the
        // platform in a request to Google.
        when(userCredentialService.getCredentialScopesById(USER, PINNED))
                .thenReturn(Optional.of(summary("stripe", "My Stripe key")));
        when(userCredentialService.getAccessToken(USER, "gmail-credential"))
                .thenReturn(Optional.of("gmail-token"));

        Optional<HttpExecutionService.CredentialResolution> resolved =
                service.tryGetCredentialResolution(USER, "gmail-credential", api());

        assertThat(resolved).isPresent();
        assertThat(resolved.get().value()).isEqualTo("gmail-token");
        // The proof is the absence: the pinned id never reached the lookup that
        // decrypts and returns a secret.
        verify(userCredentialService, never()).getAccessTokenInfoById(anyString(), anyLong());
    }

    @Test
    @DisplayName("a pin on the endpoint's OWN provider is honoured, so the feature still works")
    void anOwnPinIsHonoured() {
        // The negative half above proves nothing on its own: refusing every pin
        // would satisfy it and quietly delete the ability to choose a key.
        when(userCredentialService.getCredentialScopesById(USER, PINNED))
                .thenReturn(Optional.of(summary("gmail", "Work Gmail")));
        when(userCredentialService.getAccessTokenInfoById(USER, PINNED))
                .thenReturn(Optional.of(token("pinned-gmail-token")));

        Optional<HttpExecutionService.CredentialResolution> resolved =
                service.tryGetCredentialResolution(USER, "gmail-credential", api());

        assertThat(resolved).isPresent();
        assertThat(resolved.get().value()).isEqualTo("pinned-gmail-token");
        verify(userCredentialService, never()).getAccessToken(anyString(), anyString());
    }

    @Test
    @DisplayName("matching also accepts the credential's NAME, the second way the auth side identifies one")
    void aPinMatchedByNameIsHonoured() {
        // Credentials created for a workflow-native connector carry the
        // requirement's own name rather than an integration slug. Refusing
        // those would break configurations that predate this check.
        when(userCredentialService.getCredentialScopesById(USER, PINNED))
                .thenReturn(Optional.of(summary(null, "smtp-credential")));
        when(userCredentialService.getAccessTokenInfoById(USER, PINNED))
                .thenReturn(Optional.of(token("smtp-secret")));

        Optional<HttpExecutionService.CredentialResolution> resolved =
                service.tryGetCredentialResolution(USER, "smtp-credential", api());

        assertThat(resolved).isPresent();
        assertThat(resolved.get().value()).isEqualTo("smtp-secret");
    }

    @Test
    @DisplayName("a spelling that differs only in its separators is the SAME provider, as it is for every picker")
    void aSlugSpellingDifferenceIsHonoured() {
        // The permissive half of the rule, and the half whose absence is
        // invisible: too narrow a comparison drops a LEGITIMATE pin, runs the
        // account's default key instead, and produces a result that looks
        // exactly like the one that was asked for. The pickers collapse to the
        // canonical slug, so the server has to as well.
        when(userCredentialService.getCredentialScopesById(USER, PINNED))
                .thenReturn(Optional.of(summary("stabilityai", "Studio")));
        when(userCredentialService.getAccessTokenInfoById(USER, PINNED))
                .thenReturn(Optional.of(token("stability-token")));

        Optional<HttpExecutionService.CredentialResolution> resolved =
                service.tryGetCredentialResolution(USER, "stability-ai-credential", api());

        assertThat(resolved).isPresent();
        assertThat(resolved.get().value()).isEqualTo("stability-token");
    }

    @Test
    @DisplayName("a free-text LABEL that happens to read like the provider does not make a key that provider's")
    void aLabelThatLooksLikeTheProviderIsNotEnough() {
        // `name` is whatever a person typed in the wizard. Accepting it against
        // the provider slug would let a Stripe key labelled 'seedance' pass as a
        // Seedance credential, which is the exact disclosure the whole check
        // exists to stop - reached by calling the key a different word.
        // Matching on the label is legitimate only against the REQUIREMENT's own
        // name, which is how workflow-native credentials identify themselves.
        // The label is the requirement name EXACTLY, which is the string that
        // actually gets through: both sides collapse to the same slug, so a
        // near-miss like 'seedance' against 'seedance-credential' would have
        // been refused whatever the rule was, and testing that proves nothing.
        // Anyone able to register a custom API can choose its name, and the
        // requirement is derived from it, so this is the reachable case.
        when(userCredentialService.getCredentialScopesById(USER, PINNED))
                .thenReturn(Optional.of(summary("stripe", "seedance-credential")));
        when(userCredentialService.getAccessToken(USER, "seedance-credential"))
                .thenReturn(Optional.of("seedance-token"));

        Optional<HttpExecutionService.CredentialResolution> resolved =
                service.tryGetCredentialResolution(USER, "seedance-credential", api());

        assertThat(resolved).isPresent();
        assertThat(resolved.get().value()).isEqualTo("seedance-token");
        verify(userCredentialService, never()).getAccessTokenInfoById(anyString(), anyLong());
    }

    @Test
    @DisplayName("the verdict is reached ONCE per call, however many helpers resolve a credential")
    void theVerdictIsReachedOncePerCall() {
        // Five helpers resolve a credential during one execution. Asking each
        // time costs four extra round trips, and a blip mid-call could have some
        // of them honour the pin while others fall back, inside a single
        // request. One lookup, one answer, for the whole call.
        when(userCredentialService.getCredentialScopesById(USER, PINNED))
                .thenReturn(Optional.of(summary("gmail", "Work Gmail")));
        when(userCredentialService.getAccessTokenInfoById(USER, PINNED))
                .thenReturn(Optional.of(token("pinned-gmail-token")));

        service.tryGetCredentialResolution(USER, "gmail-credential", api());
        service.tryGetCredentialResolution(USER, "gmail-credential", api());
        service.resolveCredentialVariant(USER, "gmail-credential", api());

        verify(userCredentialService, times(1)).getCredentialScopesById(USER, PINNED);
    }

    @Test
    @DisplayName("but a DIFFERENT requirement gets its own verdict, never the previous one's")
    void aDifferentRequirementIsAskedAgain() {
        // The cache is what makes one call cheap and consistent; keyed only on
        // the thread it would also make a pin validated against one provider
        // stand in for another, which is the exact failure it exists to
        // prevent. One credential name per execution today, so this is the
        // guard for the day that stops being true.
        when(userCredentialService.getCredentialScopesById(USER, PINNED))
                .thenReturn(Optional.of(summary("gmail", "Work Gmail")));
        when(userCredentialService.getAccessTokenInfoById(USER, PINNED))
                .thenReturn(Optional.of(token("pinned-gmail-token")));
        when(userCredentialService.getAccessToken(USER, "stripe-credential"))
                .thenReturn(Optional.of("stripe-default-token"));

        service.tryGetCredentialResolution(USER, "gmail-credential", api());
        Optional<HttpExecutionService.CredentialResolution> other =
                service.tryGetCredentialResolution(USER, "stripe-credential", api());

        // The Gmail verdict must not answer for Stripe: that pin is foreign
        // there, so the second call falls back to the Stripe default.
        assertThat(other).isPresent();
        assertThat(other.get().value()).isEqualTo("stripe-default-token");
        verify(userCredentialService, times(2)).getCredentialScopesById(USER, PINNED);
    }

    @Test
    @DisplayName("an unresolvable pin falls back to the default rather than widening to it")
    void anUnresolvablePinFallsBack() {
        // The identity lookup failing must never be a reason to trust the pin:
        // "we could not check" is read as "do not use it", and the call still
        // runs on the key it would have used before anyone pinned anything.
        when(userCredentialService.getCredentialScopesById(USER, PINNED)).thenReturn(Optional.empty());
        when(userCredentialService.getAccessToken(USER, "gmail-credential"))
                .thenReturn(Optional.of("gmail-token"));

        Optional<HttpExecutionService.CredentialResolution> resolved =
                service.tryGetCredentialResolution(USER, "gmail-credential", api());

        assertThat(resolved).isPresent();
        assertThat(resolved.get().value()).isEqualTo("gmail-token");
        verify(userCredentialService, never()).getAccessTokenInfoById(anyString(), anyLong());
    }

    @Test
    @DisplayName("there is exactly ONE gate: no other line in this class reads the pinned id straight from the context")
    void thePinnedIdHasASingleReader() throws Exception {
        // Four helpers resolve a pinned credential (token, token-info, scopes,
        // data map) and a fifth refreshes it after a 401. They are protected by
        // one shared gate, and the tests above drive one of them. What keeps the
        // other three safe is not a test each, it is that none of them can reach
        // the raw context value: a fifth helper added later, or a `getSelected`
        // call inlined for convenience, would quietly skip the integration check
        // and put a foreign key back on the wire.
        //
        // The data-map helper is the one that matters most here: it feeds URL
        // template substitution, so a foreign secret would land inside a URL.
        Path source = Path.of("src/main/java/com/apimarketplace/catalog/service/http/HttpExecutionService.java");
        String code = Files.readString(source);

        int rawReads = code.split("CredentialModeContext\\.getSelectedCredentialId\\(\\)", -1).length - 1;
        assertThat(rawReads)
                .as("the pinned id must be read in selectedUserCredentialId and nowhere else")
                .isEqualTo(1);
        assertThat(code)
                .as("and that one read must be the gated helper")
                .contains("private Long selectedUserCredentialId(String userId, String credentialName)");
    }

    @Test
    @DisplayName("the platform branch never consults a pinned id at all")
    void thePlatformBranchIgnoresThePin() {
        CredentialModeContext.setExplicitSource("platform");
        ApiEntity api = api();
        api.setPlatformCredentialName("gmail-platform");
        when(userCredentialService.getAccessToken(eq("PLATFORM"), eq("gmail-platform")))
                .thenReturn(Optional.of("platform-token"));

        Optional<HttpExecutionService.CredentialResolution> resolved =
                service.tryGetCredentialResolution(USER, "gmail-credential", api);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().value()).isEqualTo("platform-token");
        verify(userCredentialService, never()).getCredentialScopesById(anyString(), anyLong());
    }
}
