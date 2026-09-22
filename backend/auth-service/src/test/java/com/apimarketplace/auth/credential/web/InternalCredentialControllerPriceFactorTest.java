package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.service.CredentialService;
import com.apimarketplace.auth.credential.service.InternalCredentialService;
import com.apimarketplace.auth.credential.service.PlatformCredentialPricingService;
import com.apimarketplace.auth.credential.service.PlatformCredentialService;
import com.apimarketplace.auth.credential.service.PricingVersionService;
import com.apimarketplace.common.security.CredentialEncryptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The price factor on the two internal lookups that resolve an amount.
 *
 * <p>It multiplies what the customer is charged, so the door it arrives at has to be as strict as
 * the one the quantity arrives at. A value of zero or below cannot be produced by any descriptor
 * this platform accepts: honouring it would multiply a charge away, and ignoring it would hide a
 * fault upstream behind a price that looks perfectly ordinary. Both are refused by name instead.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalCredentialController - the price factor on a resolved amount")
class InternalCredentialControllerPriceFactorTest {

    @Mock private InternalCredentialService credentialService;
    @Mock private CredentialService userCredentialService;
    @Mock private PlatformCredentialService platformCredentialService;
    @Mock private PlatformCredentialPricingService pricingService;
    @Mock private PricingVersionService pricingVersionService;
    @Mock private CredentialEncryptionService encryptionService;

    @InjectMocks private InternalCredentialController controller;

    private static final String TOOL_ID = "624c2566-d8d1-457c-a77b-0b29fcafdeac";

    @Test
    @DisplayName("a factor reaches the scope resolver, because it decides the amount")
    void theScopeResolverIsGivenTheFactor() {
        when(pricingService.resolveScopeMarkup(anyString(), anyString(), anyLong(), anyLong(),
                any(UUID.class), any(), any(), any())).thenReturn(Optional.empty());

        controller.resolveScopeMarkupRate("STREAM", "s-1", 1L, 7L, TOOL_ID,
                "seedance-2.0", new BigDecimal("10"), new BigDecimal("2"));

        verify(pricingService).resolveScopeMarkup("STREAM", "s-1", 1L, 7L,
                UUID.fromString(TOOL_ID), "seedance-2.0", new BigDecimal("10"), new BigDecimal("2"));
    }

    @Test
    @DisplayName("a factor of zero is REFUSED rather than silently ignored")
    void scopeRateRefusesZero() {
        // It cannot come from a descriptor, so something upstream is wrong; charging the
        // unmodified amount anyway would hide that behind a price that looks ordinary.
        ResponseEntity<Map<String, Object>> response = controller.resolveScopeMarkupRate(
                "STREAM", "s-1", 1L, 7L, TOOL_ID, "seedance-2.0", new BigDecimal("10"),
                BigDecimal.ZERO);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(response.getBody())).contains("priceMultiplier");
        verify(pricingService, never()).resolveScopeMarkup(anyString(), anyString(), anyLong(),
                anyLong(), any(UUID.class), any(), any(), any());
    }

    @Test
    @DisplayName("a negative factor is refused for the same reason")
    void scopeRateRefusesNegative() {
        ResponseEntity<Map<String, Object>> response = controller.resolveScopeMarkupRate(
                "STREAM", "s-1", 1L, 7L, TOOL_ID, "seedance-2.0", new BigDecimal("10"),
                new BigDecimal("-2"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("no factor at all resolves exactly as it did before factors existed")
    void scopeRateWithoutAFactorIsUnchanged() {
        when(pricingService.resolveScopeMarkup(anyString(), anyString(), anyLong(), anyLong(),
                any(UUID.class), any(), any(), any())).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.resolveScopeMarkupRate(
                "STREAM", "s-1", 1L, 7L, TOOL_ID, "seedance-2.0", new BigDecimal("10"), null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(pricingService).resolveScopeMarkup("STREAM", "s-1", 1L, 7L,
                UUID.fromString(TOOL_ID), "seedance-2.0", new BigDecimal("10"), null);
    }

    @Test
    @DisplayName("the FROZEN lookup carries the factor too, which is the relay's path")
    void theFrozenLookupIsGivenTheFactor() {
        when(pricingVersionService.resolveFrozenMarkup(anyLong(), any(UUID.class), any(), any(), any()))
                .thenReturn(Optional.empty());

        controller.resolveMarkup(500L, TOOL_ID, "seedance-2.0", new BigDecimal("10"),
                new BigDecimal("2"));

        verify(pricingVersionService).resolveFrozenMarkup(500L, UUID.fromString(TOOL_ID),
                "seedance-2.0", new BigDecimal("10"), new BigDecimal("2"));
    }

    @Test
    @DisplayName("the frozen lookup refuses an absurd factor at the same door")
    void theFrozenLookupRefusesZero() {
        ResponseEntity<Map<String, Object>> response = controller.resolveMarkup(
                500L, TOOL_ID, "seedance-2.0", new BigDecimal("10"), BigDecimal.ZERO);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(pricingVersionService, never()).resolveFrozenMarkup(anyLong(), any(UUID.class),
                any(), any(), any());
    }

    @Test
    @DisplayName("a factor ABOVE the ceiling is refused too, which is the case this guard was added for")
    void scopeRateRefusesAboveTheCeiling() {
        // Only `<= 0` was checked before, so a caller reaching this port directly could name a
        // factor of a million on a row with no maxCredits. The ceiling is the descriptor parser's
        // own, through one shared constant, so nothing a seed can produce is refused here.
        ResponseEntity<Map<String, Object>> response = controller.resolveScopeMarkupRate(
                "STREAM", "s-1", 1L, 7L, TOOL_ID, "seedance-2.0", new BigDecimal("10"),
                com.apimarketplace.common.web.BillingContextHeaders.MAX_GENERATION_MULTIPLIER
                        .add(new BigDecimal("0.01")));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(response.getBody())).contains("priceMultiplier");
        verify(pricingService, never()).resolveScopeMarkup(anyString(), anyString(), anyLong(),
                anyLong(), any(UUID.class), any(), any(), any());
    }

    @Test
    @DisplayName("the ceiling ITSELF is accepted, so the bound is not off by one against a real seed")
    void scopeRateAcceptsTheCeiling() {
        // The parser refuses a model whose modifiers reach MORE than the ceiling, so a product
        // exactly equal to it is a descriptor this platform accepts and must be chargeable.
        when(pricingService.resolveScopeMarkup(anyString(), anyString(), anyLong(), anyLong(),
                any(UUID.class), any(), any(), any())).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.resolveScopeMarkupRate(
                "STREAM", "s-1", 1L, 7L, TOOL_ID, "seedance-2.0", new BigDecimal("10"),
                com.apimarketplace.common.web.BillingContextHeaders.MAX_GENERATION_MULTIPLIER);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("the frozen leg refuses an over-ceiling factor as well, not only a non-positive one")
    void frozenMarkupRefusesAboveTheCeiling() {
        ResponseEntity<Map<String, Object>> response = controller.resolveMarkup(
                500L, TOOL_ID, "seedance-2.0", new BigDecimal("10"),
                com.apimarketplace.common.web.BillingContextHeaders.MAX_GENERATION_MULTIPLIER
                        .add(new BigDecimal("1")));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(pricingVersionService, never()).resolveFrozenMarkup(anyLong(), any(UUID.class),
                any(), any(), any());
    }

    @Test
    @DisplayName("the frozen answer ECHOES the factor, so a relayed charge can be explained")
    void frozenMarkupEchoesTheFactor() {
        // Both new response fields were untested: every case in this file stubbed an empty
        // Optional, so the code that writes them never ran. A relayed generation charged a
        // surcharge whose reason appeared in no log line and no ledger row.
        when(pricingVersionService.resolveFrozenMarkup(anyLong(), any(UUID.class), any(), any(), any()))
                .thenReturn(Optional.of(new PricingVersionService.FrozenMarkup(
                        500L, 7L, 3, new BigDecimal("3648"), "second", new BigDecimal("152"), true)));

        ResponseEntity<Map<String, Object>> response = controller.resolveMarkup(
                500L, TOOL_ID, "seedance-2.0", new BigDecimal("10"), new BigDecimal("2.4"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("priceMultiplier", new BigDecimal("2.4"));
        // And the row shape it needs to be read with: an amount alone cannot be checked.
        assertThat(response.getBody()).containsEntry("priceUnit", "second");
        assertThat(response.getBody()).containsEntry("unitCredits", new BigDecimal("152"));
    }

    @Test
    @DisplayName("a call at the published rate echoes NO factor, rather than a 1 on every answer")
    void frozenMarkupEchoesNothingAtTheBaseRate() {
        when(pricingVersionService.resolveFrozenMarkup(anyLong(), any(UUID.class), any(), any(), any()))
                .thenReturn(Optional.of(new PricingVersionService.FrozenMarkup(
                        500L, 7L, 3, new BigDecimal("1520"), "second", new BigDecimal("152"), true)));

        ResponseEntity<Map<String, Object>> response = controller.resolveMarkup(
                500L, TOOL_ID, "seedance-2.0", new BigDecimal("10"), null);

        assertThat(response.getBody()).doesNotContainKey("priceMultiplier");
    }
}
