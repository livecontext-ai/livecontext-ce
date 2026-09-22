package com.apimarketplace.catalog.service.billing;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.ResolvedScopeMarkupDto;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * That the price factor actually REACHES the biller.
 *
 * <p>Every other seam in this feature was widened with a matcher when the argument was added, which
 * makes a suite compile and proves nothing about the value: replacing the factor with a hard-coded
 * null at the call site left the whole catalog suite green. This one captures the argument instead,
 * so the wiring itself is the thing under test.
 *
 * <p>What is at stake: the amount charged is resolved from this number. Dropped on the way, a 1080p
 * render is billed at the 720p rate on every call, silently, and the only trace is an invoice
 * nobody has a reason to distrust.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GenerationPriceFactorWiringTest {

    private static final UUID TOOL_ID = UUID.randomUUID();
    private static final UUID API_ID = UUID.randomUUID();
    private static final String SLUG = "seedance/seedance-create-video-task";

    @Mock private CreditConsumptionClient creditClient;
    @Mock private CredentialClient credentialClient;
    @Mock private ApiToolRepository apiToolRepository;
    @Mock private ApiRepository apiRepository;

    private CatalogToolBillingService billing;

    @BeforeEach
    void setUp() {
        billing = new CatalogToolBillingService(
                creditClient, credentialClient, apiToolRepository, apiRepository, true);

        // A real, resolvable generation endpoint: the refusal path for an unresolvable one never
        // reaches the resolver at all, so it could not show what argument it is handed.
        ApiEntity api = new ApiEntity();
        api.setId(API_ID);
        api.setApiSlug("seedance");
        when(apiRepository.findByApiSlug("seedance")).thenReturn(Optional.of(api));
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(TOOL_ID);
        tool.setApiId(API_ID);
        tool.setToolSlug("seedance-create-video-task");
        tool.setGenerationSpec("{\"kind\":\"video\"}");
        when(apiToolRepository.findByApiIdAndToolSlug(API_ID, "seedance-create-video-task"))
                .thenReturn(Optional.of(tool));
        when(apiToolRepository.findById(TOOL_ID)).thenReturn(Optional.of(tool));

        ResolvedScopeMarkupDto resolved = new ResolvedScopeMarkupDto();
        resolved.setFound(true);
        resolved.setPinId(1L);
        resolved.setPricingVersionId(1L);
        resolved.setEffectiveMarkup(new BigDecimal("2000"));
        resolved.setPriceUnit("second");
        resolved.setUnitCredits(new BigDecimal("100"));
        resolved.setPricedByPublishedRow(true);
        when(credentialClient.resolveScopeMarkupRate(anyString(), anyString(), anyLong(), anyLong(),
                any(UUID.class), any(), any(), any())).thenReturn(Optional.of(resolved));
        when(credentialClient.existsScopePin(anyString(), anyString(), anyLong())).thenReturn(true);
        when(creditClient.scopeReserve(any(), any(), any(), any(), any(), any(), anyInt(), any(),
                any(), anyBoolean()))
                .thenReturn(new CreditConsumptionClient.ScopeReserveResult(true, null, false, null));
    }

    /** A scope for a ten second clip carrying the given factor. */
    private CatalogToolBillingService.BillingScope scope(BigDecimal factor) {
        return CatalogToolBillingService.BillingScope.of(
                42L, "PLATFORM", 7L, "cloud", "seedance", "seedance-2.0", SLUG,
                null, "stream-1", null, "call-ref-1", 15,
                "seedance-2.0", new BigDecimal("10"), "second", factor, false);
    }

    private BigDecimal factorSentToAuth() {
        ArgumentCaptor<BigDecimal> captor = ArgumentCaptor.forClass(BigDecimal.class);
        org.mockito.Mockito.verify(credentialClient).resolveScopeMarkupRate(
                anyString(), anyString(), anyLong(), anyLong(), any(UUID.class), any(), any(),
                captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("the factor the call reached is the factor auth is asked to price with")
    void theFactorReachesTheResolver() {
        billing.preflightReserve(scope(new BigDecimal("2.4")));

        // A resolver reached with a null here charges the published rate for a call the provider
        // bills more for, and nothing downstream can notice.
        assertThat(factorSentToAuth()).isEqualByComparingTo("2.4");
    }

    @Test
    @DisplayName("a call at the published rate carries no factor, so an ordinary tool is unchanged")
    void baseRateCallsCarryNothing() {
        billing.preflightReserve(scope(null));

        assertThat(factorSentToAuth()).isNull();
    }

    @Test
    @DisplayName("a factor of zero is read as NO factor, never as a free generation")
    void anAbsurdFactorIsNotAFreeCall() {
        // It cannot come from any descriptor this platform accepts, so the honest reading is that
        // nothing said anything about it - not that the call costs nothing.
        billing.preflightReserve(scope(BigDecimal.ZERO));

        assertThat(factorSentToAuth()).isNull();
    }

    @Test
    @DisplayName("a factor ABOVE the ceiling is dropped at the door that takes the money")
    void anOverCeilingFactorIsDroppedHereToo() {
        // Covered at the controller and at both auth endpoints and NOT here, which is the one that
        // reserves and commits. A scope can be built by any caller inside this service, so the
        // controller's check is not the last line: this is.
        //
        // Dropped, not clamped. Clamping would charge a hundred times the published rate for a
        // descriptor no seed can produce; dropping bills the published rate, which is what every
        // path did before factors existed.
        billing.preflightReserve(scope(
                com.apimarketplace.common.web.BillingContextHeaders.MAX_GENERATION_MULTIPLIER
                        .add(new BigDecimal("0.01"))));

        assertThat(factorSentToAuth()).isNull();
    }

    @Test
    @DisplayName("the ceiling ITSELF still reaches the resolver, so a real descriptor is chargeable")
    void theCeilingItselfIsCharged() {
        // The parser refuses a model whose modifiers reach MORE than this together, so a product
        // exactly equal to it is a descriptor this platform accepts. A bound that is off by one
        // here bills such a model at the base rate, silently.
        billing.preflightReserve(scope(
                com.apimarketplace.common.web.BillingContextHeaders.MAX_GENERATION_MULTIPLIER));

        assertThat(factorSentToAuth()).isEqualByComparingTo(
                com.apimarketplace.common.web.BillingContextHeaders.MAX_GENERATION_MULTIPLIER);
    }

    @Test
    @DisplayName("a negative factor is dropped for the same reason")
    void aNegativeFactorIsDropped() {
        billing.preflightReserve(scope(new BigDecimal("-3")));

        assertThat(factorSentToAuth()).isNull();
    }

    @Test
    @DisplayName("the scope keeps the SIZE and the factor apart")
    void sizeAndFactorAreTwoFacts() {
        // Ten seconds stay ten seconds whatever they cost. Folding one into the other would have
        // the run report a duration nobody asked for and no player would show.
        CatalogToolBillingService.BillingScope built = scope(new BigDecimal("2"));

        assertThat(built.generationQuantity()).isEqualByComparingTo("10");
        assertThat(built.generationQuantityUnit()).isEqualTo("second");
        assertThat(built.generationPriceMultiplier()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("the pre-factor construction shape still builds a scope with no factor")
    void theOlderShapeStillWorks() {
        CatalogToolBillingService.BillingScope built = CatalogToolBillingService.BillingScope.of(
                42L, "PLATFORM", 7L, "cloud", "seedance", "seedance-2.0", SLUG,
                null, "stream-1", null, "call-ref-1", 15,
                "seedance-2.0", new BigDecimal("10"), "second", false);

        assertThat(built.generationPriceMultiplier()).isNull();
    }

    @Test
    @DisplayName("an ordinary tool, which has no generation at all, still carries nothing")
    void ordinaryToolsAreUntouched() {
        CatalogToolBillingService.BillingScope built = CatalogToolBillingService.BillingScope.of(
                42L, "PLATFORM", 7L, "cloud", "gmail", null, "gmail/gmail-list",
                null, "stream-1", null, "call-ref-1", 15);

        assertThat(built.generationPriceMultiplier()).isNull();
        assertThat(built.generationQuantity()).isNull();
    }

    @Test
    @DisplayName("the factor never reaches the ledger as part of the model label")
    void theLedgerLabelIsNotPolluted() {
        // The label is what the usage page filters on; a factor spliced into it would make every
        // 1080p run look like a different model.
        assertThat(scope(new BigDecimal("2")).model()).isEqualTo("seedance-2.0");
    }
}
