package com.apimarketplace.catalog.service.http;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.service.UserCredentialService;
import com.apimarketplace.catalog.service.exception.CredentialSelectionException;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.apimarketplace.credential.client.dto.CredentialIdentityDto;
import com.apimarketplace.credential.client.dto.CredentialScopesDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A credential chosen FOR THIS RUN must be used or refused, never substituted.
 *
 * <p>Credential resolution here is deliberately forgiving: a pin that cannot be
 * verified is read as "no pin" and the call proceeds on the integration default
 * key, so a credential deleted long after a workflow was written does not break
 * it. That trade is sound for a choice made once, at design time, that someone
 * can go and correct.
 *
 * <p>It is not sound for a choice made per run. "Publish to the account named in
 * this row" softened into "publish to whichever account is the default" is a call
 * that succeeds, against the wrong account, and reports success. These tests pin
 * both sides of that line: the run-time choice refuses, and the author-time pin
 * keeps the forgiving behaviour it has always had.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpExecutionService - a credential chosen at run time")
class HttpExecutionServiceRunTimeSelectionTest {

    private static final String USER = "tenant-1";
    private static final String REQUIREMENT = "instagram-credential";

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
                new ObjectMapper(), jdbcTemplate, restTemplate, new ErrorPolicyEngine(2, 10_000L));
        CredentialModeContext.setExplicitSource("user");
    }

    @AfterEach
    void tearDown() {
        CredentialModeContext.clear();
    }

    private static CredentialIdentityDto identity(Long id, String name, String integration) {
        return new CredentialIdentityDto(id, name, integration, "active");
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

    private Optional<HttpExecutionService.CredentialResolution> resolve() {
        return service.tryGetCredentialResolution(USER, REQUIREMENT, api());
    }

    @Nested
    @DisplayName("choosing the account by name")
    class ByName {

        @Test
        @DisplayName("the named account is the one that runs, not the default one")
        void namedAccountRuns() {
            // The whole point of the feature: one workflow, several accounts. The
            // account holds two Instagram credentials and the run named the second.
            CredentialModeContext.setSelectedCredentialName("Client B");
            CredentialModeContext.setSelectionStrict(true);
            when(userCredentialService.listIdentities(USER)).thenReturn(List.of(
                    identity(10L, "Client A", "instagram"),
                    identity(11L, "Client B", "instagram")));
            when(userCredentialService.getAccessTokenInfoById(USER, 11L))
                    .thenReturn(Optional.of(token("client-b-token")));

            Optional<HttpExecutionService.CredentialResolution> resolved = resolve();

            assertThat(resolved).isPresent();
            assertThat(resolved.get().value()).isEqualTo("client-b-token");
            // The proof is the absence: the default key was never fetched.
            verify(userCredentialService, never()).getAccessToken(anyString(), anyString());
        }

        @Test
        @DisplayName("a name that matches nothing refuses, instead of using the default account")
        void unknownNameRefuses() {
            CredentialModeContext.setSelectedCredentialName("Client Z");
            CredentialModeContext.setSelectionStrict(true);
            when(userCredentialService.listIdentities(USER))
                    .thenReturn(List.of(identity(10L, "Client A", "instagram")));

            assertThatThrownBy(HttpExecutionServiceRunTimeSelectionTest.this::resolve)
                    .isInstanceOf(CredentialSelectionException.class)
                    .hasMessageContaining("Client Z")
                    // The two refusals must stay distinguishable in BOTH directions:
                    // this one really is a name problem, and telling the author to
                    // rename a duplicate they do not have is the mirror-image misdirect.
                    .hasMessageContaining("does not match one")
                    .hasMessageNotContaining("two or more ACTIVE credentials");

            verify(userCredentialService, never()).getAccessToken(anyString(), anyString());
            verify(userCredentialService, never()).getAccessTokenInfoById(anyString(), anyLong());
        }

        @Test
        @DisplayName("a name that belongs to another provider refuses, it does not reach that provider")
        void foreignIntegrationRefuses() {
            // Same name on a Stripe key. Honouring it would decrypt the Stripe
            // secret into a request aimed at Instagram.
            CredentialModeContext.setSelectedCredentialName("Client A");
            CredentialModeContext.setSelectionStrict(true);
            when(userCredentialService.listIdentities(USER))
                    .thenReturn(List.of(identity(10L, "Client A", "stripe")));

            assertThatThrownBy(HttpExecutionServiceRunTimeSelectionTest.this::resolve)
                    .isInstanceOf(CredentialSelectionException.class);

            verify(userCredentialService, never()).getAccessTokenInfoById(anyString(), anyLong());
        }

        @Test
        @DisplayName("two accounts under one name refuse, rather than being right half the time")
        void ambiguousNameRefuses() {
            CredentialModeContext.setSelectedCredentialName("Client A");
            CredentialModeContext.setSelectionStrict(true);
            when(userCredentialService.listIdentities(USER)).thenReturn(List.of(
                    identity(10L, "Client A", "instagram"),
                    identity(11L, "Client A", "instagram")));

            assertThatThrownBy(HttpExecutionServiceRunTimeSelectionTest.this::resolve)
                    .isInstanceOf(CredentialSelectionException.class)
                    // Asserting the type alone let this refusal share its wording with
                    // "no credential is named that", which sends an agent hunting a
                    // spelling mistake in a name that is spelled correctly. The agent-facing
                    // surfaces all teach the duplicate-name rule; this is the one an agent
                    // reads at the moment it bites, so it has to say the rule too.
                    .hasMessageContaining("two or more ACTIVE credentials")
                    .hasMessageContaining("Rename one of them")
                    .hasMessageNotContaining("does not match one");

            // NamedVerdict exists so the refusal can say WHY without counting again.
            // Counting again would be a second round trip on the one path whose whole
            // design goal is to make exactly one, and only this branch could regress it.
            verify(userCredentialService, times(1)).listIdentities(USER);
            verify(userCredentialService, never()).getAccessTokenInfoById(anyString(), anyLong());
        }

        @Test
        @DisplayName("deciding which account was meant never fetches a secret")
        void identitiesOnly() {
            CredentialModeContext.setSelectedCredentialName("Client B");
            CredentialModeContext.setSelectionStrict(true);
            when(userCredentialService.listIdentities(USER)).thenReturn(List.of(
                    identity(10L, "Client A", "instagram"),
                    identity(11L, "Client B", "instagram")));
            when(userCredentialService.getAccessTokenInfoById(USER, 11L))
                    .thenReturn(Optional.of(token("client-b-token")));

            resolve();

            // Client A was considered and rejected; its material must never have
            // been asked for in order to reject it.
            verify(userCredentialService, never()).getCredentialDataMapById(USER, 10L);
            verify(userCredentialService, never()).getAccessTokenInfoById(USER, 10L);
        }
    }

    @Nested
    @DisplayName("the author-time pin keeps the behaviour it has always had")
    class NonStrictIsUnchanged {

        @Test
        @DisplayName("a pin on another provider still falls back to the default key, with no refusal")
        void foreignPinStillFallsBack() {
            // THE regression guard for this change. Without the strict flag the
            // resolution must behave exactly as it did before the flag existed:
            // ignore the pin, use the default, do not throw. A workflow written
            // months ago whose pinned credential was deleted keeps running.
            CredentialModeContext.setSelectedCredentialId(42L);
            when(userCredentialService.getCredentialScopesById(USER, 42L))
                    .thenReturn(Optional.of(scopes("stripe", "My Stripe key")));
            when(userCredentialService.getAccessToken(USER, REQUIREMENT))
                    .thenReturn(Optional.of("default-instagram-token"));

            Optional<HttpExecutionService.CredentialResolution> resolved = resolve();

            assertThat(resolved).isPresent();
            assertThat(resolved.get().value()).isEqualTo("default-instagram-token");
        }

        @Test
        @DisplayName("with no choice at all, nothing new happens and no identity is listed")
        void noChoiceAtAll() {
            when(userCredentialService.getAccessToken(USER, REQUIREMENT))
                    .thenReturn(Optional.of("default-instagram-token"));

            Optional<HttpExecutionService.CredentialResolution> resolved = resolve();

            assertThat(resolved).isPresent();
            assertThat(resolved.get().value()).isEqualTo("default-instagram-token");
            // An extra round trip on every unpinned call would be a real cost on a
            // path that runs for every catalog step of every workflow.
            verify(userCredentialService, never()).listIdentities(anyString());
        }

        @Test
        @DisplayName("the same unmatched pin DOES refuse once the choice was made for this run")
        void strictFlipsTheSameCase() {
            // Same inputs as foreignPinStillFallsBack, one flag apart, so the flag
            // is provably what decides and nothing else changed underneath.
            CredentialModeContext.setSelectedCredentialId(42L);
            CredentialModeContext.setSelectionStrict(true);
            when(userCredentialService.getCredentialScopesById(USER, 42L))
                    .thenReturn(Optional.of(scopes("stripe", "My Stripe key")));

            assertThatThrownBy(HttpExecutionServiceRunTimeSelectionTest.this::resolve)
                    .isInstanceOf(CredentialSelectionException.class);

            verify(userCredentialService, never()).getAccessToken(anyString(), anyString());
        }

        private CredentialScopesDto scopes(String integration, String name) {
            CredentialScopesDto dto = new CredentialScopesDto();
            dto.setIntegration(integration);
            dto.setName(name);
            return dto;
        }
    }

    @Nested
    @DisplayName("identifying the account is only half of honouring it")
    class RefusesToSubstituteAfterMatching {

        /**
         * The defect this class exists for, and the case the first version of these
         * tests missed by always stubbing the token as present. The name matches, the
         * account is found, and the material then comes back empty because the
         * credential was revoked or never finished authorising. One line below the
         * strict check, the old code fetched the integration default and published to
         * the wrong account with a 200.
         */
        @Test
        @DisplayName("a matched account whose token is unusable refuses, it does not use the default one")
        void matchedButUnusableRefuses() {
            CredentialModeContext.setSelectedCredentialName("Client B");
            CredentialModeContext.setSelectionStrict(true);
            when(userCredentialService.listIdentities(USER)).thenReturn(List.of(
                    identity(10L, "Client A", "instagram"),
                    identity(11L, "Client B", "instagram")));
            when(userCredentialService.getAccessTokenInfoById(USER, 11L)).thenReturn(Optional.empty());

            assertThatThrownBy(HttpExecutionServiceRunTimeSelectionTest.this::resolve)
                    .isInstanceOf(CredentialSelectionException.class)
                    .hasMessageContaining("11");

            verify(userCredentialService, never()).getAccessToken(anyString(), anyString());
        }

        /**
         * Prod regression 2026-09-23: every Telegram chat channel was refused. A Telegram bot
         * credential keeps its key under {@code token} (injected into the URL by
         * {@code bot_token_in_url}), which the access-token lookup does not know, so it answered
         * empty and the strict refusal called a perfectly good credential "unusable".
         */
        @Test
        @DisplayName("a chosen account whose key is not an access token (Telegram's token) is neither refused nor swapped for the default")
        void keyOfAnotherKindIsNotRefused() {
            CredentialModeContext.setSelectedCredentialId(40L);
            CredentialModeContext.setSelectionStrict(true);
            when(userCredentialService.getCredentialScopesById(USER, 40L))
                    .thenReturn(Optional.of(scopesOf("instagram", "Ops bot")));
            when(userCredentialService.getAccessTokenInfoById(USER, 40L)).thenReturn(Optional.empty());
            when(userCredentialService.getCredentialDataMapById(USER, 40L)).thenReturn(Map.of("token", "123:abc"));

            Optional<HttpExecutionService.CredentialResolution> resolved = resolve();

            // Empty here, read later through the data map of THIS credential.
            assertThat(resolved).isEmpty();
            verify(userCredentialService, never()).getAccessToken(anyString(), anyString());
            verify(userCredentialService, never()).getAccessTokenInfo(anyString(), anyString());
        }

        @Test
        @DisplayName("the variant lookup, reached first by every real call, lets the Telegram account through without asking for the default")
        void variantLookupLetsKeyOfAnotherKindThrough() {
            CredentialModeContext.setSelectedCredentialId(40L);
            CredentialModeContext.setSelectionStrict(true);
            when(userCredentialService.getCredentialScopesById(USER, 40L))
                    .thenReturn(Optional.of(scopesOf("instagram", "Ops bot")));
            when(userCredentialService.getAccessTokenInfoById(USER, 40L)).thenReturn(Optional.empty());
            when(userCredentialService.getCredentialDataMapById(USER, 40L)).thenReturn(Map.of("token", "123:abc"));

            // No variant to project from a non-token key: no filter, and above all no refusal.
            assertThat(service.resolveCredentialVariant(USER, REQUIREMENT, api())).isNull();
            verify(userCredentialService, never()).getAccessTokenInfo(anyString(), anyString());
        }

        @Test
        @DisplayName("the variant lookup still refuses an OAuth account that never finished authorising")
        void variantLookupRefusesUnfinishedOAuth() {
            CredentialModeContext.setSelectedCredentialId(40L);
            CredentialModeContext.setSelectionStrict(true);
            when(userCredentialService.getCredentialScopesById(USER, 40L))
                    .thenReturn(Optional.of(scopesOf("instagram", "Client B")));
            when(userCredentialService.getAccessTokenInfoById(USER, 40L)).thenReturn(Optional.empty());
            when(userCredentialService.getCredentialDataMapById(USER, 40L))
                    .thenReturn(Map.of("client_id", "id", "client_secret", "secret"));

            assertThatThrownBy(() -> service.resolveCredentialVariant(USER, REQUIREMENT, api()))
                    .isInstanceOf(CredentialSelectionException.class)
                    .hasMessageContaining("40");
            verify(userCredentialService, never()).getAccessTokenInfo(anyString(), anyString());
        }

        @Test
        @DisplayName("an OAuth account that never finished authorising still refuses: client id and secret are not a key")
        void unfinishedOAuthStillRefuses() {
            CredentialModeContext.setSelectedCredentialId(40L);
            CredentialModeContext.setSelectionStrict(true);
            when(userCredentialService.getCredentialScopesById(USER, 40L))
                    .thenReturn(Optional.of(scopesOf("instagram", "Client B")));
            when(userCredentialService.getAccessTokenInfoById(USER, 40L)).thenReturn(Optional.empty());
            when(userCredentialService.getCredentialDataMapById(USER, 40L))
                    .thenReturn(Map.of("client_id", "id", "client_secret", "secret"));

            assertThatThrownBy(HttpExecutionServiceRunTimeSelectionTest.this::resolve)
                    .isInstanceOf(CredentialSelectionException.class)
                    .hasMessageContaining("40");
            verify(userCredentialService, never()).getAccessToken(anyString(), anyString());
        }

        @Test
        @DisplayName("the same unusable credential still falls back when the pin was author-time")
        void matchedButUnusableStillFallsBackWhenNotStrict() {
            // The mirror of the case above, one flag apart. A workflow pinned months
            // ago whose credential has since been revoked must keep running on the
            // default key: that forgiveness is deliberate and must not be collateral
            // damage of the strict path.
            CredentialModeContext.setSelectedCredentialId(11L);
            when(userCredentialService.getCredentialScopesById(USER, 11L))
                    .thenReturn(Optional.of(scopesOf("instagram", "Client B")));
            when(userCredentialService.getAccessTokenInfoById(USER, 11L)).thenReturn(Optional.empty());
            when(userCredentialService.getAccessToken(USER, REQUIREMENT))
                    .thenReturn(Optional.of("default-instagram-token"));

            Optional<HttpExecutionService.CredentialResolution> resolved = resolve();

            assertThat(resolved).isPresent();
            assertThat(resolved.get().value()).isEqualTo("default-instagram-token");
        }
    }

    @Nested
    @DisplayName("only active credentials can be selected")
    class ActiveOnly {

        @Test
        @DisplayName("an EXPIRING account is refused, even though the listing calls it usable")
        void expiringIsNotSelected() {
            // The gap this pins, and the one that made the agent-facing text wrong: the
            // account listing an agent reads describes 'expiring' as "still works, token
            // expiring soon", while the usable set here is filtered to 'active' on
            // purpose. An agent trusting the listing selects an expiring account and gets
            // the fail-closed refusal, so the text has to say 'active' rather than
            // enumerate needs_reauth and error. Its siblings below cover the revoked
            // case; only this status reads as usable in the very listing it comes from.
            CredentialModeContext.setSelectedCredentialName("Client B");
            CredentialModeContext.setSelectionStrict(true);
            when(userCredentialService.listIdentities(USER)).thenReturn(List.of(
                    new CredentialIdentityDto(11L, "Client B", "instagram", "expiring")));

            assertThatThrownBy(HttpExecutionServiceRunTimeSelectionTest.this::resolve)
                    .isInstanceOf(CredentialSelectionException.class);

            verify(userCredentialService, never()).getAccessTokenInfoById(anyString(), anyLong());
            verify(userCredentialService, never()).getAccessToken(anyString(), anyString());
        }

        @Test
        @DisplayName("a revoked account carrying the name is not selected")
        void revokedIsNotSelected() {
            // Selecting it would hand a credential that cannot produce a key to the
            // step, which then lands in the substitution case above. The id path never
            // had this hole (auth-side resolution is active-only), so the name path
            // must not open one.
            CredentialModeContext.setSelectedCredentialName("Client B");
            CredentialModeContext.setSelectionStrict(true);
            when(userCredentialService.listIdentities(USER)).thenReturn(List.of(
                    new CredentialIdentityDto(11L, "Client B", "instagram", "needs_reauth")));

            assertThatThrownBy(HttpExecutionServiceRunTimeSelectionTest.this::resolve)
                    .isInstanceOf(CredentialSelectionException.class);

            verify(userCredentialService, never()).getAccessTokenInfoById(anyString(), anyLong());
        }

        @Test
        @DisplayName("a revoked namesake does not make an unambiguous choice look ambiguous")
        void revokedNamesakeIsNotAConflict() {
            // Reconnecting an account leaves the old row behind under the same name.
            // Counting it would refuse a choice that has exactly one usable answer.
            CredentialModeContext.setSelectedCredentialName("Client B");
            CredentialModeContext.setSelectionStrict(true);
            when(userCredentialService.listIdentities(USER)).thenReturn(List.of(
                    new CredentialIdentityDto(10L, "Client B", "instagram", "needs_reauth"),
                    new CredentialIdentityDto(11L, "Client B", "instagram", "active")));
            when(userCredentialService.getAccessTokenInfoById(USER, 11L))
                    .thenReturn(Optional.of(token("client-b-token")));

            Optional<HttpExecutionService.CredentialResolution> resolved = resolve();

            assertThat(resolved).isPresent();
            assertThat(resolved.get().value()).isEqualTo("client-b-token");
        }
    }

    @Nested
    @DisplayName("the name is resolved once per call")
    class ResolvedOncePerCall {

        @Test
        @DisplayName("several helpers resolving the same name cost one credential listing")
        void oneListingPerCall() {
            // Five helpers resolve a credential during one execution and each lands in
            // the same lookup. Without a per-request memo every dynamic step pays
            // several full credential-list fetches, and an auth blip between two of
            // them could have one helper honour the name and another fall through,
            // inside a single call.
            CredentialModeContext.setSelectedCredentialName("Client B");
            CredentialModeContext.setSelectionStrict(true);
            when(userCredentialService.listIdentities(USER))
                    .thenReturn(List.of(identity(11L, "Client B", "instagram")));
            when(userCredentialService.getAccessTokenInfoById(USER, 11L))
                    .thenReturn(Optional.of(token("client-b-token")));

            resolve();
            resolve();
            resolve();

            verify(userCredentialService, times(1)).listIdentities(USER);
        }
    }

    private static CredentialScopesDto scopesOf(String integration, String name) {
        CredentialScopesDto dto = new CredentialScopesDto();
        dto.setIntegration(integration);
        dto.setName(name);
        return dto;
    }

    @Nested
    @DisplayName("what counts as the same name")
    class NameMatching {

        @Test
        @DisplayName("two accounts differing only in punctuation stay two accounts")
        void punctuationIsNotCollapsed() {
            // The integration check collapses provider spellings on purpose
            // (stability-ai vs stabilityai). Applying that to a label a PERSON typed
            // would make "Client 1" and "Client-1" the same credential, so an account
            // holding both could never select either: every run would refuse as
            // ambiguous. The refusal message and the field help both promise an exact
            // name, so the matcher has to be one.
            CredentialModeContext.setSelectedCredentialName("Client 1");
            CredentialModeContext.setSelectionStrict(true);
            when(userCredentialService.listIdentities(USER)).thenReturn(List.of(
                    identity(10L, "Client 1", "instagram"),
                    identity(11L, "Client-1", "instagram")));
            when(userCredentialService.getAccessTokenInfoById(USER, 10L))
                    .thenReturn(Optional.of(token("client-1-token")));

            Optional<HttpExecutionService.CredentialResolution> resolved = resolve();

            assertThat(resolved).isPresent();
            assertThat(resolved.get().value()).isEqualTo("client-1-token");
        }

        @Test
        @DisplayName("case and surrounding spaces do not matter, because typing is not the test")
        void caseAndSpacingAreForgiven() {
            CredentialModeContext.setSelectedCredentialName("  client b  ");
            CredentialModeContext.setSelectionStrict(true);
            when(userCredentialService.listIdentities(USER))
                    .thenReturn(List.of(identity(11L, "Client B", "instagram")));
            when(userCredentialService.getAccessTokenInfoById(USER, 11L))
                    .thenReturn(Optional.of(token("client-b-token")));

            assertThat(resolve()).isPresent();
        }

        @Test
        @DisplayName("a name that only matches once punctuation is deleted does NOT match")
        void looseMatchesAreRefused() {
            // "clienta" would reach "Client A" under slug normalisation. Accepting it
            // means the account a run acts on depends on a normalisation nobody was
            // told about.
            CredentialModeContext.setSelectedCredentialName("clienta");
            CredentialModeContext.setSelectionStrict(true);
            when(userCredentialService.listIdentities(USER))
                    .thenReturn(List.of(identity(10L, "Client A", "instagram")));

            assertThatThrownBy(HttpExecutionServiceRunTimeSelectionTest.this::resolve)
                    .isInstanceOf(CredentialSelectionException.class);
        }
    }

    @Nested
    @DisplayName("a selection that names nothing")
    class StrictWithNoSelection {

        @Test
        @DisplayName("strict with neither an id nor a name refuses instead of being dropped")
        void strictNamingNothingRefuses() {
            // The controller door lets this through (the source IS user), so the
            // service is the layer that has to catch it. Ignored, the call runs on the
            // integration default while the request said an account had been chosen.
            CredentialModeContext.setSelectionStrict(true);

            assertThatThrownBy(HttpExecutionServiceRunTimeSelectionTest.this::resolve)
                    .isInstanceOf(CredentialSelectionException.class);

            verify(userCredentialService, never()).getAccessToken(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("the per-request memo")
    class MemoisedVerdict {

        @Test
        @DisplayName("a REFUSAL is remembered too, so one call cannot answer twice")
        void refusalIsCachedLikeAMatch() {
            // Five helpers resolve a credential during one execution. If only matches
            // were remembered, an auth blip between two of them could have one helper
            // refuse and another proceed, inside a single call, on different accounts.
            CredentialModeContext.setSelectedCredentialName("Client Z");
            CredentialModeContext.setSelectionStrict(true);
            when(userCredentialService.listIdentities(USER))
                    .thenReturn(List.of(identity(10L, "Client A", "instagram")));

            assertThatThrownBy(HttpExecutionServiceRunTimeSelectionTest.this::resolve)
                    .isInstanceOf(CredentialSelectionException.class);
            assertThatThrownBy(HttpExecutionServiceRunTimeSelectionTest.this::resolve)
                    .isInstanceOf(CredentialSelectionException.class);

            verify(userCredentialService, times(1)).listIdentities(USER);
        }
    }

    @Nested
    @DisplayName("the sub-resource token cache")
    class SubResourceTokenCacheKey {

        /**
         * The cache key is private, so the property is asserted through the only thing
         * that can observe it: two resolutions under two different accounts must not
         * collide. Reading the key itself would pin an implementation; this pins the
         * behaviour that matters.
         */
        @Test
        @DisplayName("two accounts of one integration do not share a cached sub-token")
        void twoAccountsDoNotShareASubToken() {
            // Before run-time selection existed nothing in one tenant could ask for the
            // same sub-resource under two accounts inside the TTL. Now an agency runs
            // this workflow for account A and then for account B, and B would be served
            // the token minted from A: a strict choice honoured at resolution and
            // quietly substituted one layer down.
            when(userCredentialService.listIdentities(USER)).thenReturn(List.of(
                    identity(10L, "Client A", "instagram"),
                    identity(11L, "Client B", "instagram")));
            when(userCredentialService.getAccessTokenInfoById(USER, 10L))
                    .thenReturn(Optional.of(token("token-A")));
            when(userCredentialService.getAccessTokenInfoById(USER, 11L))
                    .thenReturn(Optional.of(token("token-B")));

            CredentialModeContext.setSelectedCredentialName("Client A");
            CredentialModeContext.setSelectionStrict(true);
            Optional<HttpExecutionService.CredentialResolution> first = resolve();

            CredentialModeContext.clear();
            CredentialModeContext.setExplicitSource("user");
            CredentialModeContext.setSelectedCredentialName("Client B");
            CredentialModeContext.setSelectionStrict(true);
            Optional<HttpExecutionService.CredentialResolution> second = resolve();

            assertThat(first).isPresent();
            assertThat(second).isPresent();
            assertThat(first.get().value()).isEqualTo("token-A");
            assertThat(second.get().value()).isEqualTo("token-B");
        }
    }
}
