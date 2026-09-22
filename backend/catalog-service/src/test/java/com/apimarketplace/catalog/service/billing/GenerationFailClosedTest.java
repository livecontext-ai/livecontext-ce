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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A resold generation must never go out free.
 *
 * <p>The whole point of the feature is that the platform owner's provider quota
 * is spent on the customer's behalf and charged for. An unpriced generation is
 * therefore not a cheap call, it is the owner paying a third party out of pocket
 * with no trace, so this path fails CLOSED where an ordinary tool proceeds.
 *
 * <p>Both ways of reaching that state are ordinary mistakes, not exotic ones:
 * the platform key is provisioned after the catalog import so no version was
 * ever published, or the endpoint carries per-model rows only and is called
 * without naming a model.
 */
class GenerationFailClosedTest {

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
        when(apiRepository.findByApiSlug("seedance")).thenReturn(Optional.of(api));
    }

    /** Register the endpoint, with or without a generation descriptor. */
    private void givenEndpoint(boolean isGeneration) {
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(TOOL_ID);
        tool.setApiId(API_ID);
        tool.setToolSlug("create-video-task");
        if (isGeneration) {
            tool.setGenerationSpec("{\"kind\":\"video\"}");
        }
        when(apiToolRepository.findByApiIdAndToolSlug(API_ID, "create-video-task"))
                .thenReturn(Optional.of(tool));
        when(apiToolRepository.findById(TOOL_ID)).thenReturn(Optional.of(tool));
    }

    /** A price the owner published FOR THIS ENDPOINT, which is the ordinary case. */
    private void givenResolvedPrice(BigDecimal credits) {
        ResolvedScopeMarkupDto dto = new ResolvedScopeMarkupDto();
        dto.setFound(true);
        dto.setPinId(1L);
        dto.setPricingVersionId(1L);
        dto.setEffectiveMarkup(credits);
        dto.setPricedByPublishedRow(true);
        when(credentialClient.resolveScopeMarkupRate(anyString(), anyString(), anyLong(), anyLong(),
                any(UUID.class), any(), any(), any())).thenReturn(Optional.of(dto));
    }

    /**
     * A row that charges per unit, resolved the way auth resolves it: the
     * quantity-less call gets the ONE-unit floor, the sized call gets the real
     * amount. Mirroring that arithmetic here is what makes the difference
     * between the two visible as money rather than as a flag.
     */
    private void givenUnitPricedRow(BigDecimal perUnit, BigDecimal quantityOnTheCall) {
        BigDecimal billedUnits = quantityOnTheCall == null || quantityOnTheCall.signum() <= 0
                ? BigDecimal.ONE
                : quantityOnTheCall;
        ResolvedScopeMarkupDto dto = new ResolvedScopeMarkupDto();
        dto.setFound(true);
        dto.setPinId(1L);
        dto.setPricingVersionId(1L);
        dto.setPriceUnit("second");
        dto.setBaseCredits(BigDecimal.ZERO);
        dto.setUnitCredits(perUnit);
        dto.setQuantity(billedUnits);
        dto.setEffectiveMarkup(perUnit.multiply(billedUnits));
        dto.setPricedByPublishedRow(true);
        when(credentialClient.resolveScopeMarkupRate(anyString(), anyString(), anyLong(), anyLong(),
                any(UUID.class), any(), any(), any())).thenReturn(Optional.of(dto));
    }

    /** A run-scoped call on the platform key, sized or not, with no model named. */
    private CatalogToolBillingService.BillingScope scopeWithQuantity(BigDecimal quantity) {
        return CatalogToolBillingService.BillingScope.of(
                7L, "PLATFORM", 42L, "cloud", "seedance", null,
                "seedance/create-video-task",
                "run-1", null, "step-1", "call-1", 10,
                null, quantity);
    }

    /** The CE relay's shape: no scope, and the charge owned one frame up. */
    private CatalogToolBillingService.BillingScope relayScope() {
        return CatalogToolBillingService.BillingScope.of(
                7L, "PLATFORM", 42L, "cloud", "seedance", null,
                "seedance/create-video-task",
                null, null, null, "call-1", 10,
                null, null, true);
    }

    private CatalogToolBillingService.BillingScope scope(String generationModelId) {
        return CatalogToolBillingService.BillingScope.of(
                7L, "PLATFORM", 42L, "cloud", "seedance", null,
                "seedance/create-video-task",
                "run-1", null, "step-1", "call-1", 10,
                generationModelId, generationModelId == null ? null : new BigDecimal("10"));
    }

    /**
     * The same call with NO run and NO chat stream: what an {@code lc_live_}
     * API key produces on the MCP surface, where there is neither. Both
     * coordinates end up null, which is the state that used to skip billing
     * entirely.
     */
    private CatalogToolBillingService.BillingScope scopeWithoutBillingScope(String credentialSource) {
        return scopeWithoutBillingScope(credentialSource, "seedance-2.0");
    }

    /**
     * @param generationModelId null for an ORDINARY call. Naming a model is what
     *        makes a call a generation when the descriptor cannot be read, so a
     *        fixture that means "ordinary tool" must not name one: an endpoint
     *        with no descriptor reached with a model id is a state the
     *        generation surface cannot produce, and asserting on it would pin
     *        behaviour that no real call has.
     */
    private CatalogToolBillingService.BillingScope scopeWithoutBillingScope(String credentialSource,
                                                                            String generationModelId) {
        return CatalogToolBillingService.BillingScope.of(
                7L, credentialSource, 42L, "cloud", "seedance", null,
                "seedance/create-video-task",
                null, null, null, "call-1", 10,
                generationModelId, new BigDecimal("10"));
    }

    @Nested
    @DisplayName("a resold generation")
    class ResoldGeneration {

        @Test
        @DisplayName("what the reservation is told about an existing pin is what was FOUND, never a constant")
        void theExistingPinAnswerIsForwardedAsFound() {
            // THE LAST UNPINNED ARGUMENT of the reserve call, and the one with
            // teeth: the auth side uses it to decide whether a delinquent
            // account may proceed. Hardcoding it to true left 298 tests green,
            // and every existing verification passes anyBoolean() for it, so a
            // FRESH chat generation would be announced as an in-flight call and
            // slip past the refusal the comment says must apply.
            //
            // Both answers are asserted: a constant satisfies either alone.
            givenEndpoint(true);
            givenResolvedPrice(new BigDecimal("12"));
            when(credentialClient.existsScopePin(any(), any(), any())).thenReturn(true);
            when(creditClient.scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean()))
                    .thenReturn(new CreditConsumptionClient.ScopeReserveResult(
                            true, null, false, new BigDecimal("12")));

            service.preflightReserve(scope("seedance-2.0"));
            verify(creditClient).scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), eq(true));

            reset(creditClient);
            when(credentialClient.existsScopePin(any(), any(), any())).thenReturn(false);
            when(creditClient.scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean()))
                    .thenReturn(new CreditConsumptionClient.ScopeReserveResult(
                            true, null, false, new BigDecimal("12")));

            service.preflightReserve(scope("seedance-2.0"));
            verify(creditClient).scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), eq(false));
        }

        @Test
        @DisplayName("a pin lookup that fails is reported as NO pin, which is the safe answer")
        void aFailedPinLookupIsReportedAsAbsent() {
            // Fail-open on the lookup would be fail-OPEN on the delinquent
            // gate: announcing a pin nobody found lets a delinquent account
            // through. The catch says false; nothing proved it.
            givenEndpoint(true);
            givenResolvedPrice(new BigDecimal("12"));
            when(credentialClient.existsScopePin(any(), any(), any()))
                    .thenThrow(new IllegalStateException("auth unreachable"));
            when(creditClient.scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean()))
                    .thenReturn(new CreditConsumptionClient.ScopeReserveResult(
                            true, null, false, new BigDecimal("12")));

            service.preflightReserve(scope("seedance-2.0"));

            verify(creditClient).scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), eq(false));
        }

        @Test
        @DisplayName("with NEITHER a published price NOR a stated size, it refuses instead of throwing")
        void noPriceAndNoSizeIsARefusalNotACrash() {
            // Both facts are true at once for a per-model endpoint reached
            // without a model, on a credential whose version was never
            // published. The size branch reads the resolved price's unit to
            // name what the caller must state, so with nothing resolved it
            // threw NoSuchElementException: a crash on the one path whose whole
            // purpose is to ANSWER, and a 500 where a refusal belongs.
            //
            // The missing PRICE is what gets reported, because it is the half
            // the caller can do nothing about.
            givenEndpoint(true);
            when(credentialClient.resolveScopeMarkupRate(anyString(), anyString(), anyLong(), anyLong(),
                    any(UUID.class), any(), any(), any())).thenReturn(Optional.empty());

            var decision = service.preflightReserve(CatalogToolBillingService.BillingScope.of(
                    7L, "PLATFORM", 42L, "cloud", "seedance", null,
                    "seedance/create-video-task",
                    "run-1", null, "step-1", "call-1", 10,
                    "seedance-2.0", null));

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.error()).contains("PLATFORM_NOT_AVAILABLE");
            verify(creditClient, never()).scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("is REFUSED when no pricing version was ever published")
        void refusedWhenNoPricingPublished() {
            givenEndpoint(true);
            when(credentialClient.resolveScopeMarkupRate(anyString(), anyString(), anyLong(), anyLong(),
                    any(UUID.class), any(), any(), any())).thenReturn(Optional.empty());

            var decision = service.preflightReserve(scope("seedance-2.0"));

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.error()).contains("PLATFORM_NOT_AVAILABLE");
            verify(creditClient, never()).scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("is REFUSED when the endpoint has per-model rows only and no model was named")
        void refusedWhenCalledWithoutAModel() {
            // The realistic shape of this: an mcp: node pointed straight at the
            // submit endpoint, bypassing the generation surface entirely. The
            // per-model price then never matches and the endpoint resolves to
            // nothing.
            givenEndpoint(true);
            givenResolvedPrice(BigDecimal.ZERO);

            var decision = service.preflightReserve(scope(null));

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.error()).contains("no price is published");
        }

        @Test
        @DisplayName("proceeds normally once a price resolves")
        void proceedsWhenPriced() {
            givenEndpoint(true);
            givenResolvedPrice(new BigDecimal("600"));
            when(creditClient.scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean()))
                    .thenReturn(new CreditConsumptionClient.ScopeReserveResult(true, null, false, null));

            var decision = service.preflightReserve(scope("seedance-2.0"));

            assertThat(decision.allowed()).isTrue();
            assertThat(decision.reservedAmount()).isEqualByComparingTo("600");
        }
    }

    @Nested
    @DisplayName("a published rate that measures something else than the call")
    class DimensionMismatch {

        /** A row priced per {@code unit}, resolved for a call measured in {@code measuredUnit}. */
        private CatalogToolBillingService.BillingScope givenPricePerUnitAndCallMeasuredIn(
                String unit, String measuredUnit, String quantity) {
            return givenPricePerUnitAndCallMeasuredIn(unit, measuredUnit, quantity, "seedance-2.0");
        }

        /**
         * @param generationModelId null for an ORDINARY call. Naming a model is
         *        what makes a call a generation, so a fixture that means
         *        "ordinary tool" must not name one.
         */
        private CatalogToolBillingService.BillingScope givenPricePerUnitAndCallMeasuredIn(
                String unit, String measuredUnit, String quantity, String generationModelId) {
            ResolvedScopeMarkupDto dto = new ResolvedScopeMarkupDto();
            dto.setFound(true);
            dto.setPinId(1L);
            dto.setPricingVersionId(1L);
            dto.setPriceUnit(unit);
            dto.setBaseCredits(BigDecimal.ZERO);
            dto.setUnitCredits(new BigDecimal("30"));
            dto.setQuantity(new BigDecimal(quantity));
            dto.setEffectiveMarkup(new BigDecimal("30").multiply(new BigDecimal(quantity)));
            dto.setPricedByPublishedRow(true);
            when(credentialClient.resolveScopeMarkupRate(anyString(), anyString(), anyLong(), anyLong(),
                    any(UUID.class), any(), any(), any())).thenReturn(Optional.of(dto));
            return CatalogToolBillingService.BillingScope.of(
                    7L, "PLATFORM", 42L, "cloud", "seedance", null,
                    "seedance/create-video-task", "run-1", null, "step-1",
                    "call-1", 10, generationModelId, new BigDecimal(quantity), measuredUnit, false);
        }

        @Test
        @DisplayName("REFUSES rather than multiplying a per-IMAGE rate by a count of SECONDS")
        void perImageRateOnASecondsCallIsRefused() {
            // The publish-time dimension guard only arms on a RE-publish, so a
            // FIRST row can be in any unit. Both halves then look ordinary on
            // their own: a rate, and a bare 10. Multiplied, a ten second clip is
            // charged ten times the per-image rate, and the run reports
            // billed_unit "second" beside a per-image price.
            givenEndpoint(true);
            var scope = givenPricePerUnitAndCallMeasuredIn("image", "second", "10");

            var decision = service.preflightReserve(scope);

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.error()).contains("PLATFORM_NOT_AVAILABLE");
            assertThat(decision.error()).contains("measured in second");
            verify(creditClient, never()).scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("lets a matching dimension through, so the guard is about agreement not about units")
        void aMatchingDimensionProceeds() {
            givenEndpoint(true);
            var scope = givenPricePerUnitAndCallMeasuredIn("second", "second", "10");
            when(creditClient.scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean()))
                    .thenReturn(new CreditConsumptionClient.ScopeReserveResult(true, null, false, null));

            assertThat(service.preflightReserve(scope).allowed()).isTrue();
        }

        @Test
        @DisplayName("a per-MINUTE rate accepts a call measured in SECONDS, which is the shipped shape")
        void minuteAndSecondAreTheSameDimension() {
            // A model listed per minute is measured in seconds by design; the
            // conversion belongs to whoever reads the rate. Refusing this pair
            // would take out every per-minute model the platform sells.
            givenEndpoint(true);
            var scope = givenPricePerUnitAndCallMeasuredIn("minute", "second", "60");
            when(creditClient.scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean()))
                    .thenReturn(new CreditConsumptionClient.ScopeReserveResult(true, null, false, null));

            assertThat(service.preflightReserve(scope).allowed()).isTrue();
        }

        @Test
        @DisplayName("a FLAT rate accepts any measurement, because that is what flat means")
        void aFlatRateIgnoresTheMeasurement() {
            givenEndpoint(true);
            var scope = givenPricePerUnitAndCallMeasuredIn("call", "second", "10");
            when(creditClient.scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean()))
                    .thenReturn(new CreditConsumptionClient.ScopeReserveResult(true, null, false, null));

            assertThat(service.preflightReserve(scope).allowed()).isTrue();
        }

        @Test
        @DisplayName("a call that states NO unit is not refused, so an older caller keeps working")
        void anUnstatedMeasurementIsNotARefusal() {
            // Every path that predates this field sends no unit. Reading its
            // absence as a contradiction would refuse traffic that is fine, and
            // the amount is still checked by the three rules either side.
            givenEndpoint(true);
            var scope = givenPricePerUnitAndCallMeasuredIn("image", null, "10");
            when(creditClient.scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean()))
                    .thenReturn(new CreditConsumptionClient.ScopeReserveResult(true, null, false, null));

            assertThat(service.preflightReserve(scope).allowed()).isTrue();
        }

        @Test
        @DisplayName("an ORDINARY endpoint is never refused on this rule, whatever its units say")
        void ordinaryToolIsUntouched() {
            givenEndpoint(false);
            var scope = givenPricePerUnitAndCallMeasuredIn("image", "second", "10", null);
            when(creditClient.scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean()))
                    .thenReturn(new CreditConsumptionClient.ScopeReserveResult(true, null, false, null));

            assertThat(service.preflightReserve(scope).allowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("a price that came from the credential-wide default rather than this endpoint")
    class VersionDefaultFallback {

        /**
         * The amount is positive and the row shape is flat, so every other
         * guard is satisfied. What is wrong is upstream of all of them: nobody
         * decided this number for a generation. A credential-wide default is
         * written for the ordinary endpoints of an API, where one call costs
         * about what the next one does.
         */
        private void givenOnlyTheVersionDefault(BigDecimal credits) {
            ResolvedScopeMarkupDto dto = new ResolvedScopeMarkupDto();
            dto.setFound(true);
            dto.setPinId(1L);
            dto.setPricingVersionId(1L);
            dto.setEffectiveMarkup(credits);
            dto.setPricedByPublishedRow(false);
            when(credentialClient.resolveScopeMarkupRate(anyString(), anyString(), anyLong(), anyLong(),
                    any(UUID.class), any(), any(), any())).thenReturn(Optional.of(dto));
        }

        @Test
        @DisplayName("REFUSES a resold generation, and does not reserve a single credit for it")
        void resoldGenerationOnTheDefaultIsRefused() {
            givenEndpoint(true);
            givenOnlyTheVersionDefault(new BigDecimal("2"));

            var decision = service.preflightReserve(scope("seedance-2.0"));

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.error()).contains("PLATFORM_NOT_AVAILABLE");
            // The owner has to know WHICH decision is missing, or the message
            // sends them looking at a price that is published and correct.
            assertThat(decision.error()).contains("credential-wide default");
            verify(creditClient, never()).scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("lets the same generation through the moment a price is published for the endpoint")
        void publishedRowOnTheSameGenerationProceeds() {
            // Same tool, same amount, same everything except who chose it. If
            // this failed too, the guard would be refusing generations rather
            // than refusing catch-alls.
            givenEndpoint(true);
            givenResolvedPrice(new BigDecimal("2"));
            when(creditClient.scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean()))
                    .thenReturn(new CreditConsumptionClient.ScopeReserveResult(true, null, false, null));

            var decision = service.preflightReserve(scope("seedance-2.0"));

            assertThat(decision.allowed()).isTrue();
            assertThat(decision.reservedAmount()).isEqualByComparingTo("2");
        }

        @Test
        @DisplayName("leaves an ORDINARY endpoint on the default alone, which is what a default is for")
        void ordinaryToolOnTheDefaultStillProceeds() {
            // The 1000+ ordinary catalog endpoints are the reason the default
            // exists. Refusing them here would break every relayed API call
            // that has ever relied on it.
            givenEndpoint(false);
            givenOnlyTheVersionDefault(new BigDecimal("2"));
            when(creditClient.scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean()))
                    .thenReturn(new CreditConsumptionClient.ScopeReserveResult(true, null, false, null));

            var decision = service.preflightReserve(scope(null));

            assertThat(decision.allowed()).isTrue();
            assertThat(decision.reservedAmount()).isEqualByComparingTo("2");
        }

        @Test
        @DisplayName("an answer that does not mention the flag at all is NOT read as the default")
        void aSilentAnswerIsNotARefusal() {
            // A responder older than this flag says nothing about it. Reading
            // silence as "the default applied" would refuse every resold
            // generation for as long as a rolling deploy has one old peer left,
            // turning a latent mispricing into an outage. Only an explicit
            // false is the statement that triggers the refusal.
            givenEndpoint(true);
            ResolvedScopeMarkupDto silent = new ResolvedScopeMarkupDto();
            silent.setFound(true);
            silent.setPinId(1L);
            silent.setPricingVersionId(1L);
            silent.setEffectiveMarkup(new BigDecimal("600"));
            silent.setPricedByPublishedRow(null);
            when(credentialClient.resolveScopeMarkupRate(anyString(), anyString(), anyLong(), anyLong(),
                    any(UUID.class), any(), any(), any())).thenReturn(Optional.of(silent));
            when(creditClient.scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean()))
                    .thenReturn(new CreditConsumptionClient.ScopeReserveResult(true, null, false, null));

            var decision = service.preflightReserve(scope("seedance-2.0"));

            assertThat(decision.allowed()).isTrue();
            assertThat(decision.reservedAmount()).isEqualByComparingTo("600");
        }

        @Test
        @DisplayName("does not refuse a generation on the user's OWN key, which the platform never charges")
        void ownKeyIsUntouched() {
            givenEndpoint(true);
            givenOnlyTheVersionDefault(new BigDecimal("2"));

            var decision = service.preflightReserve(scopeWithoutBillingScope("user"));

            assertThat(decision.allowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("an ordinary tool")
    class OrdinaryTool {

        @Test
        @DisplayName("still proceeds free at zero, because that IS a deliberate admin choice")
        void zeroPriceStillProceeds() {
            // The fail-closed rule must not leak onto the 1000+ ordinary catalog
            // endpoints, where publishing a zero price is how an admin says
            // "included".
            givenEndpoint(false);
            givenResolvedPrice(BigDecimal.ZERO);

            var decision = service.preflightReserve(scope(null));

            assertThat(decision.allowed()).isTrue();
            assertThat(decision.sourceId()).isNull();
        }

        @Test
        @DisplayName("still proceeds free when no pricing version exists at all")
        void noPricingStillProceeds() {
            givenEndpoint(false);
            when(credentialClient.resolveScopeMarkupRate(anyString(), anyString(), anyLong(), anyLong(),
                    any(UUID.class), any(), any(), any())).thenReturn(Optional.empty());

            assertThat(service.preflightReserve(scope(null)).allowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("a call that carries no billing scope")
    class NoBillingScope {

        @Test
        @DisplayName("REFUSES a resold generation: an API key on the MCP surface has no run and no stream, "
                + "and used to generate unlimited video for free")
        void resoldGenerationIsRefusedWithoutAScope() {
            givenEndpoint(true);

            var decision = service.preflightReserve(scopeWithoutBillingScope("PLATFORM"));

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.error()).contains("PLATFORM_NOT_AVAILABLE");
            // Nothing was even attempted against the ledger: with no scope
            // there is nothing to reserve against, which is exactly why the
            // call has to be refused rather than quoted.
            verify(credentialClient, never()).resolveScopeMarkupRate(any(), any(), any(), any(),
                    any(UUID.class), any(), any(), any());
            verify(creditClient, never()).scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("still lets an ordinary tool through, because refusing every scope-less catalog call "
                + "would take the MCP surface down")
        void ordinaryToolIsUnaffectedWithoutAScope() {
            givenEndpoint(false);

            var decision = service.preflightReserve(scopeWithoutBillingScope("PLATFORM", null));

            assertThat(decision.allowed()).isTrue();
            assertThat(decision.sourceId()).isNull();
        }

        @Test
        @DisplayName("does not refuse a generation on the user's OWN key, which costs the platform nothing")
        void byokGenerationIsUnaffectedWithoutAScope() {
            givenEndpoint(true);

            assertThat(service.preflightReserve(scopeWithoutBillingScope("USER")).allowed()).isTrue();
        }

        @Test
        @DisplayName("does not refuse when markup is OFF (CE), where the platform key is the operator's own "
                + "and nothing is being resold")
        void markupDisabledGenerationIsUnaffectedWithoutAScope() {
            givenEndpoint(true);
            CatalogToolBillingService ceService = new CatalogToolBillingService(
                    creditClient, credentialClient, apiToolRepository, apiRepository, false);

            var decision = ceService.preflightReserve(scopeWithoutBillingScope("PLATFORM"));

            assertThat(decision.allowed()).isTrue();
            assertThat(decision.sourceId()).isNull();
            // A CE install has no credit ledger to reserve against at all.
            verify(creditClient, never()).scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean());
        }
    }

    @Nested
    @DisplayName("a price that charges per unit, on a call that never said how big it is")
    class UnitPricedWithoutASize {

        @Test
        @DisplayName("REFUSES a resold generation: a positive price is not proof the call was priced, "
                + "and one second of a ten second video is a tenth of what the provider charges")
        void resoldGenerationWithoutASizeIsRefused() {
            givenEndpoint(true);
            givenUnitPricedRow(new BigDecimal("60"), null);

            var decision = service.preflightReserve(scopeWithQuantity(null));

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.error()).startsWith("GENERATION_SIZE_UNKNOWN");
            // Nothing is reserved and nothing is dispatched: the refusal happens
            // before the provider is asked for anything.
            verify(creditClient, never()).scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("charges the FULL amount for the same row once the call states its size")
        void resoldGenerationWithASizeIsChargedInFull() {
            givenEndpoint(true);
            givenUnitPricedRow(new BigDecimal("60"), new BigDecimal("10"));
            when(creditClient.scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean()))
                    .thenReturn(new CreditConsumptionClient.ScopeReserveResult(true, null, false, null));

            var decision = service.preflightReserve(scopeWithQuantity(new BigDecimal("10")));

            assertThat(decision.allowed()).isTrue();
            // 60 credits per second x 10 seconds. The one-unit floor would have
            // reserved 60, which is the bug this guard exists to make impossible.
            assertThat(decision.reservedAmount()).isEqualByComparingTo("600");
            verify(creditClient).scopeReserve(eq(7L), anyString(), any(), any(),
                    eq(new BigDecimal("600")), any(), anyInt(), eq("RUN"), eq("run-1"), anyBoolean());
        }

        @Test
        @DisplayName("still bills an ORDINARY endpoint one unit, which is the case the floor was written for")
        void ordinaryEndpointKeepsTheOneUnitFloor() {
            // An ordinary caller genuinely has no notion of a size, and billing
            // the base alone would be zero for a pure per-unit rate. Refusing
            // here instead would break every such endpoint.
            givenEndpoint(false);
            givenUnitPricedRow(new BigDecimal("60"), null);
            when(creditClient.scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean()))
                    .thenReturn(new CreditConsumptionClient.ScopeReserveResult(true, null, false, null));

            var decision = service.preflightReserve(scopeWithQuantity(null));

            assertThat(decision.allowed()).isTrue();
            assertThat(decision.reservedAmount()).isEqualByComparingTo("60");
        }

        @Test
        @DisplayName("leaves a FLAT price alone: no unit rate means there is no size to be missing")
        void flatPricedGenerationIsUnaffected() {
            givenEndpoint(true);
            givenResolvedPrice(new BigDecimal("300"));
            when(creditClient.scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean()))
                    .thenReturn(new CreditConsumptionClient.ScopeReserveResult(true, null, false, null));

            var decision = service.preflightReserve(scopeWithQuantity(null));

            assertThat(decision.allowed()).isTrue();
            assertThat(decision.reservedAmount()).isEqualByComparingTo("300");
        }
    }

    @Nested
    @DisplayName("a call whose charge is owned by the caller (the CE relay)")
    class BillingOwnedByCaller {

        @Test
        @DisplayName("is NOT refused for lack of a scope: the relay reserved it before dispatching it")
        void relayedGenerationIsNotRefused() {
            givenEndpoint(true);

            var decision = service.preflightReserve(relayScope());

            assertThat(decision.allowed()).isTrue();
            assertThat(decision.error()).isNull();
        }

        @Test
        @DisplayName("is NOT charged a second time here, which is what the missing scope used to guarantee")
        void relayedGenerationIsNotBilledTwice() {
            givenEndpoint(true);

            var decision = service.preflightReserve(relayScope());

            assertThat(decision.sourceId()).isNull();
            verify(credentialClient, never()).resolveScopeMarkupRate(any(), any(), any(), any(),
                    any(UUID.class), any(), any(), any());
            verify(creditClient, never()).scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("does not make requiresBillingToProceed true, so an exception in the reserve path "
                + "cannot refuse an already-paid call either")
        void requiresBillingToProceedIsFalse() {
            givenEndpoint(true);

            assertThat(service.requiresBillingToProceed(relayScope())).isFalse();
        }
    }

    @Nested
    @DisplayName("requiresBillingToProceed - the single rule both fail-closed points read")
    class RequiresBillingToProceed {

        @Test
        @DisplayName("is true for a resold generation on the platform key")
        void trueForResoldGeneration() {
            givenEndpoint(true);

            assertThat(service.requiresBillingToProceed(scope("seedance-2.0"))).isTrue();
        }

        @Test
        @DisplayName("is false for an ordinary endpoint, for the user's own key, and when markup is off")
        void falseForEverythingElse() {
            // An ordinary call names no generation model. It is the pair that
            // makes a generation, and an endpoint with no descriptor reached
            // WITH a model id is a state nothing produces.
            givenEndpoint(false);
            assertThat(service.requiresBillingToProceed(scope(null))).isFalse();

            givenEndpoint(true);
            assertThat(service.requiresBillingToProceed(scopeWithoutBillingScope("USER"))).isFalse();
            assertThat(new CatalogToolBillingService(creditClient, credentialClient,
                    apiToolRepository, apiRepository, false)
                    .requiresBillingToProceed(scope("seedance-2.0"))).isFalse();
        }

        @Test
        @DisplayName("stays TRUE when the endpoint cannot be resolved, which is when it matters most")
        void trueEvenWhenTheEndpointCannotBeResolved() {
            // This is the rule the scope-less refusal reads, and it used to ask
            // the descriptor question INSIDE the lookup: an unresolvable slug
            // produced an empty Optional, mapped to nothing, and answered false.
            // So the one call that most needs refusing, a generation with no
            // billing scope on an endpoint the catalog cannot read, went out
            // free on the platform key. A named model needs no lookup.
            when(apiRepository.findByApiSlug("seedance")).thenReturn(Optional.empty());

            assertThat(service.requiresBillingToProceed(scope("seedance-2.0"))).isTrue();
            assertThat(service.requiresBillingToProceed(
                    scopeWithoutBillingScope("PLATFORM", "seedance-2.0"))).isTrue();
        }

        @Test
        @DisplayName("and stays FALSE for an unresolvable ORDINARY call, which must not start refusing")
        void falseForAnUnresolvableOrdinaryCall() {
            when(apiRepository.findByApiSlug("seedance")).thenReturn(Optional.empty());

            assertThat(service.requiresBillingToProceed(scope(null))).isFalse();
        }

        @Test
        @DisplayName("is false for a null scope, so an unresolvable call is never refused on this rule")
        void falseForANullScope() {
            assertThat(service.requiresBillingToProceed(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("degradation")
    class Degradation {

        @Test
        @DisplayName("a lookup failure does NOT refuse: a database blip must not stop ordinary traffic")
        void lookupFailureFailsOpen() {
            givenEndpoint(true);
            when(apiToolRepository.findById(TOOL_ID)).thenThrow(new RuntimeException("db down"));
            givenResolvedPrice(BigDecimal.ZERO);

            assertThat(service.preflightReserve(scope(null)).allowed()).isTrue();
        }

        @Test
        @DisplayName("an UNRESOLVABLE endpoint REFUSES a named generation instead of dispatching it free")
        void unresolvableEndpointRefusesANamedGeneration() {
            // Not knowing which endpoint this is means not knowing it is a
            // generation, so every guard is skipped and the call goes out on the
            // owner's provider key with no reservation and no ledger row. The
            // way in is ordinary: the slug resolver swallows its own exceptions,
            // so one transient catalog-database fault is enough. Naming a model
            // is proof that needs no lookup, which is exactly what is missing.
            when(apiRepository.findByApiSlug("seedance")).thenReturn(Optional.empty());

            var decision = service.preflightReserve(scope("seedance-2.0"));

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.error()).contains("PLATFORM_NOT_AVAILABLE");
            verify(creditClient, never()).scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("an unresolvable endpoint still lets an ORDINARY call through, blip or not")
        void unresolvableEndpointStillPassesOrdinaryTraffic() {
            // The 1000+ catalog endpoints must survive a database blip. The rule
            // separates them by what the call NAMED, not by what could be read.
            when(apiRepository.findByApiSlug("seedance")).thenReturn(Optional.empty());

            assertThat(service.preflightReserve(scope(null)).allowed()).isTrue();
        }

        @Test
        @DisplayName("a descriptor that cannot be READ still refuses a named generation")
        void descriptorLookupFailureStillRefusesANamedGeneration() {
            // lookupFailureFailsOpen above pins that a blip must not stop
            // ordinary traffic, and it does that by answering "not a
            // generation". For a call that NAMED a model that answer is wrong,
            // and it turns the blip into a free video.
            givenEndpoint(true);
            when(apiToolRepository.findById(TOOL_ID)).thenThrow(new RuntimeException("db down"));
            givenResolvedPrice(BigDecimal.ZERO);

            var decision = service.preflightReserve(scope("seedance-2.0"));

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.error()).contains("PLATFORM_NOT_AVAILABLE");
        }

        @Test
        @DisplayName("a user paying with their own key is never refused, since the platform bills nothing")
        void byokIsNeverRefused() {
            givenEndpoint(true);
            var byok = CatalogToolBillingService.BillingScope.of(
                    7L, "USER", 42L, "cloud", "seedance", null,
                    "seedance/create-video-task", "run-1", null, "step-1",
                    "call-1", 10, "seedance-2.0", new BigDecimal("10"));

            assertThat(service.preflightReserve(byok).allowed()).isTrue();
        }
    }
}
