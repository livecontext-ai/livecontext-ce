package com.apimarketplace.catalog.service.billing;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.ResolvedScopeMarkupDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a refusal SAYS, and who it is actually addressed to.
 *
 * <p>Two failures used to share one sentence, and it was wrong for one of them.
 * "The platform does not sell this" is the account owner's decision and the
 * caller can only route around it; "you did not say how big the call is" is the
 * caller's own omission and they fix it on their next turn. Sending the second
 * one to an administrator is how a correct, published price gets reported as a
 * missing one.
 *
 * <p>And a refusal about the caller's BALANCE is addressed to nobody at all when
 * the caller has their own provider key: the agentic path tries that key first,
 * so the platform was never going to charge them.
 */
class CatalogToolBillingRefusalTest {

    private static final UUID TOOL_ID = UUID.randomUUID();
    private static final UUID API_ID = UUID.randomUUID();

    private CreditConsumptionClient creditClient;
    private CredentialClient credentialClient;
    private ApiToolRepository apiToolRepository;
    private ApiRepository apiRepository;
    private CatalogToolBillingService service;

    @BeforeEach
    void setUp() {
        creditClient = mock(CreditConsumptionClient.class);
        credentialClient = mock(CredentialClient.class);
        apiToolRepository = mock(ApiToolRepository.class);
        apiRepository = mock(ApiRepository.class);
        service = new CatalogToolBillingService(creditClient, credentialClient,
                apiToolRepository, apiRepository, true);

        ApiEntity api = new ApiEntity();
        api.setId(API_ID);
        api.setApiSlug("seedance");
        api.setIconSlug("seedance");
        when(apiRepository.findByApiSlug("seedance")).thenReturn(Optional.of(api));
        when(apiRepository.findById(API_ID)).thenReturn(Optional.of(api));

        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(TOOL_ID);
        tool.setApiId(API_ID);
        tool.setToolSlug("create-video-task");
        tool.setGenerationSpec("{\"kind\":\"video\"}");
        when(apiToolRepository.findByApiIdAndToolSlug(API_ID, "create-video-task"))
                .thenReturn(Optional.of(tool));
        when(apiToolRepository.findById(TOOL_ID)).thenReturn(Optional.of(tool));
    }

    /** The endpoint is an ORDINARY one: no generation descriptor on the row. */
    private void givenOrdinaryEndpoint() {
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(TOOL_ID);
        tool.setApiId(API_ID);
        tool.setToolSlug("create-video-task");
        when(apiToolRepository.findById(TOOL_ID)).thenReturn(Optional.of(tool));
    }

    private void givenResolvedPrice(BigDecimal credits) {
        ResolvedScopeMarkupDto dto = new ResolvedScopeMarkupDto();
        dto.setFound(true);
        dto.setPinId(1L);
        dto.setPricingVersionId(1L);
        dto.setEffectiveMarkup(credits);
        when(credentialClient.resolveScopeMarkupRate(anyString(), anyString(), anyLong(), anyLong(),
                any(UUID.class), any(), any(), any())).thenReturn(Optional.of(dto));
    }

    private void givenNoPricePublished() {
        when(credentialClient.resolveScopeMarkupRate(anyString(), anyString(), anyLong(), anyLong(),
                any(UUID.class), any(), any(), any())).thenReturn(Optional.empty());
    }

    private void givenReserveAnswers(boolean success, String error, boolean delinquent) {
        when(creditClient.scopeReserve(any(), any(), any(), any(), any(), any(),
                anyInt(), any(), any(), anyBoolean()))
                .thenReturn(new CreditConsumptionClient.ScopeReserveResult(success, error, delinquent, null));
    }

    private void givenUserOwnsCredentialFor(String... integrations) {
        when(credentialClient.getConfiguredIntegrations("7")).thenReturn(Set.of(integrations));
    }

    private CatalogToolBillingService.BillingScope scope(String credentialSource,
                                                          String modelId, BigDecimal quantity) {
        return scope(credentialSource, modelId, quantity, "call-1");
    }

    private CatalogToolBillingService.BillingScope scope(String credentialSource,
                                                          String modelId, BigDecimal quantity,
                                                          String callRef) {
        return CatalogToolBillingService.BillingScope.of(
                7L, credentialSource, 42L, "cloud", "seedance", null,
                "seedance/create-video-task",
                "run-1", null, "step-1", callRef, 10,
                modelId, quantity);
    }

    @Nested
    @DisplayName("a per-unit generation whose size was never stated")
    class SizeNeverStated {

        @Test
        @DisplayName("is refused as the CALLER's omission, naming the parameters that state a size")
        void namesTheParameterInsteadOfAnAdministrator() {
            givenResolvedPrice(BigDecimal.ZERO);

            var decision = service.preflightReserve(scope(null, "seedance-2.0", null));

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.error())
                    .startsWith("GENERATION_SIZE_UNKNOWN")
                    .contains("seedance-2.0")
                    .contains("duration_seconds")
                    .contains("n for a number of images");
        }

        @Test
        @DisplayName("never sends the caller to an administrator for a price that IS published")
        void doesNotBlameTheAdministrator() {
            givenResolvedPrice(BigDecimal.ZERO);

            var decision = service.preflightReserve(scope(null, "seedance-2.0", null));

            assertThat(decision.error())
                    .doesNotContain("administrator")
                    .doesNotContain("no price is published")
                    .doesNotContain("PLATFORM_NOT_AVAILABLE");
        }

        @Test
        @DisplayName("reads a quantity of zero the same way as none: neither states a size")
        void zeroQuantityIsTreatedAsUnstated() {
            givenResolvedPrice(BigDecimal.ZERO);

            var decision = service.preflightReserve(scope(null, "seedance-2.0", BigDecimal.ZERO));

            assertThat(decision.error()).startsWith("GENERATION_SIZE_UNKNOWN");
        }

        @Test
        @DisplayName("never reaches the ledger: a call that cannot be priced is not reserved for")
        void reservesNothing() {
            givenResolvedPrice(BigDecimal.ZERO);

            service.preflightReserve(scope(null, "seedance-2.0", null));

            verify(creditClient, never()).scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean());
        }
    }

    @Nested
    @DisplayName("a generation the platform does not sell")
    class NotSoldOnThePlatformKey {

        @Test
        @DisplayName("is refused as the OWNER's decision, with the one remedy the caller can apply")
        void pointsAtTheCallersOwnKey() {
            // A size WAS stated here, so the only thing missing really is a
            // published price.
            givenNoPricePublished();

            var decision = service.preflightReserve(scope(null, "seedance-2.0", new BigDecimal("5")));

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.error())
                    .startsWith("PLATFORM_NOT_AVAILABLE")
                    .contains("no price is published")
                    .contains("credential_source='user'")
                    .contains("seedance-2.0");
        }

        @Test
        @DisplayName("tells a caller who named no model to name one, since the price hangs off the model")
        void tellsAnUnnamedModelCallToNameOne() {
            givenResolvedPrice(BigDecimal.ZERO);

            var decision = service.preflightReserve(scope(null, null, null));

            assertThat(decision.error())
                    .startsWith("PLATFORM_NOT_AVAILABLE")
                    .contains("Name a generation model");
        }
    }

    @Nested
    @DisplayName("a caller who has their own provider key")
    class CallerWithTheirOwnKey {

        @Test
        @DisplayName("is NOT refused when their balance is empty: the platform was never going to charge them")
        void emptyBalanceDoesNotBlockTheirOwnKey() {
            // The agentic path resolves the caller's credential FIRST and only
            // falls back to the platform's. The reservation is taken on the
            // assumption that the platform key answers; being unable to pay for
            // an assumption is not a reason to refuse the call.
            givenOrdinaryEndpoint();
            givenResolvedPrice(new BigDecimal("120"));
            givenReserveAnswers(false, "Insufficient credits", false);
            givenUserOwnsCredentialFor("seedance");

            var decision = service.preflightReserve(scope(null, null, null));

            assertThat(decision.allowed()).isTrue();
            assertThat(decision.sourceId()).isNull();      // nothing held, nothing to settle
            assertThat(decision.error()).isNull();
        }

        @Test
        @DisplayName("is not blocked by a delinquent account either, for the same reason")
        void delinquencyDoesNotBlockTheirOwnKey() {
            givenOrdinaryEndpoint();
            givenResolvedPrice(new BigDecimal("120"));
            givenReserveAnswers(false, "account delinquent - top up to resume", true);
            givenUserOwnsCredentialFor("seedance");

            assertThat(service.preflightReserve(scope(null, null, null)).allowed()).isTrue();
        }

        @Test
        @DisplayName("IS refused when they explicitly asked for the platform key, which they cannot pay for")
        void anExplicitPlatformChoiceIsStillRefused() {
            // Running them on another pool than the one they named would hide
            // the fact that the one they named is unavailable to them.
            givenOrdinaryEndpoint();
            givenResolvedPrice(new BigDecimal("120"));
            givenReserveAnswers(false, "Insufficient credits", false);
            givenUserOwnsCredentialFor("seedance");

            var decision = service.preflightReserve(scope("PLATFORM", null, null));

            assertThat(decision.allowed()).isFalse();
        }

        @Test
        @DisplayName("costs nothing to check: the lookup only happens on a refusal")
        void theLookupIsOffTheHappyPath() {
            givenOrdinaryEndpoint();
            givenResolvedPrice(new BigDecimal("120"));
            givenReserveAnswers(true, null, false);

            assertThat(service.preflightReserve(scope(null, null, null)).allowed()).isTrue();
            verify(credentialClient, never()).getConfiguredIntegrations(anyString());
        }
    }

    @Nested
    @DisplayName("a caller who has NO key of their own")
    class CallerWithoutTheirOwnKey {

        @Test
        @DisplayName("is refused, and told the one thing they can do about it themselves")
        void refusalCarriesTheOwnKeyRemedy() {
            givenOrdinaryEndpoint();
            givenResolvedPrice(new BigDecimal("120"));
            givenReserveAnswers(false, "Insufficient credits", false);
            givenUserOwnsCredentialFor("gmail");   // a different service entirely

            var decision = service.preflightReserve(scope(null, null, null));

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.error())
                    .contains("Insufficient credits")
                    .contains("credential_source='user'");
        }

        @Test
        @DisplayName("keeps the delinquent flag, so the account owner still gets the banner they need")
        void delinquentFlagSurvives() {
            givenOrdinaryEndpoint();
            givenResolvedPrice(new BigDecimal("120"));
            givenReserveAnswers(false, "account delinquent - top up to resume", true);
            givenUserOwnsCredentialFor();

            var decision = service.preflightReserve(scope(null, null, null));

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.delinquent()).isTrue();
        }

        @Test
        @DisplayName("a credential lookup that fails REFUSES, rather than handing out the platform key unbilled")
        void lookupFailureFailsClosed() {
            givenOrdinaryEndpoint();
            givenResolvedPrice(new BigDecimal("120"));
            givenReserveAnswers(false, "Insufficient credits", false);
            when(credentialClient.getConfiguredIntegrations("7"))
                    .thenThrow(new RuntimeException("auth down"));

            assertThat(service.preflightReserve(scope(null, null, null)).allowed()).isFalse();
        }

        @Test
        @DisplayName("a plan refusal reaches the caller in the words that name the remedy, instead of a generic shortfall")
        void thePlanRefusalSurvivesToTheCaller() {
            // Generation is sold on a subscription or a credit top-up, never on
            // the monthly free grant, and that rule lives in the credit service.
            // What matters HERE is that the sentence explaining it is not
            // swallowed and replaced on the way out: the account owner is the
            // only person who can act on it, and "Insufficient credits" tells
            // them to buy more of what they already have.
            // The default fixture is the resold generation endpoint, which is
            // the one this rule is about.
            givenResolvedPrice(new BigDecimal("300"));
            givenReserveAnswers(false,
                    "PLAN_EXCLUDES_THIS: your monthly Free credits fund workflow runs only, so they cannot "
                            + "pay for this. An active subscription or a credit top-up pays for it.", false);
            givenUserOwnsCredentialFor();

            var decision = service.preflightReserve(scope(null, "seedance-2.0", new BigDecimal("5")));

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.error())
                    .contains("PLAN_EXCLUDES_THIS")
                    .contains("subscription")
                    // and the one remedy that costs the platform nothing is
                    // still offered alongside it
                    .contains("credential_source='user'");
        }
    }
}
