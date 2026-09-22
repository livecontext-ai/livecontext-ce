package com.apimarketplace.auth.web;

import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.UserOnboardingRepository;
import com.apimarketplace.auth.service.CeLinkEntitlementsService;
import com.apimarketplace.auth.service.CeLinkService;
import com.apimarketplace.auth.service.CreditConsumptionDeadLetterService;
import com.apimarketplace.auth.service.ModelPricingService;
import com.apimarketplace.auth.service.OnboardingService;
import com.apimarketplace.auth.service.OrgRestrictionQueryService;
import com.apimarketplace.auth.service.PlanLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The HTTP boundary of the pricing mirror.
 *
 * <p>The catalog is the source of truth for what a token costs and it reaches billing
 * only through this endpoint, so what it accepts IS the billing contract. The cache
 * rates (V491) are the interesting part: a rate that arrives unusable must land as
 * {@code null}, meaning "unknown, fall back to the family multiplier", and never as
 * {@code 0}, which {@link ModelPricingService} would otherwise have to read as "cached
 * input is free". The sending side is pinned by {@code AuthPricingSyncClientTest}; this
 * pins the receiving side, so a sender that starts emitting a zero (or a JSON string)
 * cannot quietly zero out a model's cache price.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalAuthController - model-pricing sync (cache rates)")
class InternalAuthControllerPricingSyncTest {

    @Mock private OrgRestrictionQueryService restrictionService;
    @Mock private CreditConsumptionDeadLetterService deadLetterService;
    @Mock private UserOnboardingRepository onboardingRepository;
    @Mock private OnboardingService onboardingService;
    @Mock private ModelPricingService modelPricingService;
    @Mock private PlanLimitService planLimitService;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private ObjectProvider<CeLinkService> ceLinkServiceProvider;
    @Mock private ObjectProvider<CeLinkEntitlementsService> ceLinkEntitlementsServiceProvider;

    private InternalAuthController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalAuthController(
                restrictionService, deadLetterService, onboardingRepository, onboardingService,
                modelPricingService, planLimitService, memberRepository, ceLinkServiceProvider,
                ceLinkEntitlementsServiceProvider,
                org.mockito.Mockito.mock(com.apimarketplace.auth.repository.UserProfileRepository.class),
                org.mockito.Mockito.mock(com.apimarketplace.auth.repository.UserRepository.class));
    }

    private Map<String, Object> body(Object cacheRead, Object cacheWrite) {
        Map<String, Object> body = new HashMap<>();
        body.put("provider", "anthropic");
        body.put("model", "claude-fable-5-1");
        body.put("inputRate", 10.0);
        body.put("outputRate", 50.0);
        if (cacheRead != null) body.put("cacheReadRate", cacheRead);
        if (cacheWrite != null) body.put("cacheWriteRate", cacheWrite);
        return body;
    }

    private BigDecimal[] capturedCacheRates() {
        ArgumentCaptor<BigDecimal> read = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> write = ArgumentCaptor.forClass(BigDecimal.class);
        verify(modelPricingService).upsertPricing(
                eq("anthropic"), eq("claude-fable-5-1"), any(), any(), any(),
                read.capture(), write.capture(), any());
        return new BigDecimal[] { read.getValue(), write.getValue() };
    }

    @Test
    @DisplayName("a usable cache rate reaches the service unchanged, in the same USD/1M units as input and output")
    void forwardsUsableRates() {
        ResponseEntity<?> response = controller.syncModelPricing(body(0.25, 12.5));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        BigDecimal[] rates = capturedCacheRates();
        assertThat(rates[0]).isEqualByComparingTo("0.25");
        assertThat(rates[1]).isEqualByComparingTo("12.5");
    }

    @Test
    @DisplayName("an absent cache rate arrives as null - the mirror keeps what it had rather than being told the cache is free")
    void absentRatesArriveAsNull() {
        controller.syncModelPricing(body(null, null));

        BigDecimal[] rates = capturedCacheRates();
        assertThat(rates[0]).isNull();
        assertThat(rates[1]).isNull();
    }

    @Test
    @DisplayName("ZERO is read as unknown, not as free - this is the value the catalog really stores for 'no cache-write charge'")
    void zeroIsUnknownNotFree() {
        // Every DeepSeek row in the catalog carries price_cache_write = 0. Forwarding that
        // as a rate would make a cache write cost nothing; the honest reading is "unknown",
        // which sends billing to the family multiplier.
        controller.syncModelPricing(body(0, 0.0));

        BigDecimal[] rates = capturedCacheRates();
        assertThat(rates[0]).isNull();
        assertThat(rates[1]).isNull();
    }

    @Test
    @DisplayName("a negative sentinel rate is refused rather than stored, the way the sending side refuses it")
    void negativeRateIsRefused() {
        controller.syncModelPricing(body(-1.0, -1000000.0));

        BigDecimal[] rates = capturedCacheRates();
        assertThat(rates[0]).isNull();
        assertThat(rates[1]).isNull();
    }

    @Test
    @DisplayName("a non-numeric cache rate is ignored instead of throwing, so one malformed field cannot 500 the whole sync")
    void nonNumericRateIsIgnored() {
        // A JSON string "0.25" is dropped, not parsed. The trade is deliberate: a sender
        // that regresses to string-encoding loses the discount (visible in the ledger)
        // rather than taking the pricing mirror down.
        controller.syncModelPricing(body("0.25", true));

        BigDecimal[] rates = capturedCacheRates();
        assertThat(rates[0]).isNull();
        assertThat(rates[1]).isNull();
    }

    @Test
    @DisplayName("a request missing a mandatory rate is rejected 400 and nothing is written")
    void missingMandatoryRateIsRejected() {
        Map<String, Object> incomplete = body(0.25, 12.5);
        incomplete.remove("inputRate");

        ResponseEntity<?> response = controller.syncModelPricing(incomplete);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(modelPricingService, never()).upsertPricing(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("an invalid providerKind is rejected 400 before any write, cache rates included")
    void invalidProviderKindIsRejected() {
        Map<String, Object> bad = body(0.25, 12.5);
        bad.put("providerKind", "not-a-kind");

        ResponseEntity<?> response = controller.syncModelPricing(bad);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(modelPricingService, never()).upsertPricing(
                any(), any(), any(), any(), any(), any(), any(), any());
    }
}
