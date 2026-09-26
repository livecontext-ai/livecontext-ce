package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential;
import com.apimarketplace.auth.credential.domain.PriceSource;
import com.apimarketplace.auth.credential.domain.PriceSpec;
import com.apimarketplace.auth.credential.service.CredentialService;
import com.apimarketplace.auth.credential.service.InternalCredentialService;
import com.apimarketplace.auth.credential.service.PlatformCredentialPricingService;
import com.apimarketplace.auth.credential.service.PlatformCredentialPricingService.BundlePriceApplyResult;
import com.apimarketplace.auth.credential.service.PlatformCredentialService;
import com.apimarketplace.auth.credential.service.PricingVersionService;
import com.apimarketplace.common.security.CredentialEncryptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InternalCredentialController.addNeverPricedCatalogPrices - the cloud gap fill wire contract")
class InternalCredentialControllerNeverPricedTest {

    @Mock private InternalCredentialService credentialService;
    @Mock private CredentialService userCredentialService;
    @Mock private PlatformCredentialService platformCredentialService;
    @Mock private PlatformCredentialPricingService pricingService;
    @Mock private PricingVersionService pricingVersionService;
    @Mock private CredentialEncryptionService encryptionService;

    @InjectMocks private InternalCredentialController controller;

    private static final String TOOL_ID = "624c2566-d8d1-457c-a77b-0b29fcafdeac";

    private static PlatformCredential credential(Long id, String name) {
        return new PlatformCredential(id, name, name, AuthType.API_KEY,
                null, null, null, null, null, null, null, null, null, null, null,
                true, null, BigDecimal.ZERO, 100, null, null, null, null);
    }

    private static InternalCredentialController.BundlePriceEntry wire(String integration, String model, String unit) {
        return new InternalCredentialController.BundlePriceEntry(
                integration, TOOL_ID, model, unit, BigDecimal.ZERO, new BigDecimal("0.5"), null, null);
    }

    @Test
    @DisplayName("Rows are grouped per integration and stamped ADMIN: on the cloud the price is the owner's once it lands")
    void rowsAreGroupedAndStampedAdmin() {
        when(platformCredentialService.getRawCredential("elevenlabs")).thenReturn(Optional.of(credential(7L, "elevenlabs")));
        when(pricingService.addNeverPricedPrices(eq(7L), anyList(), any()))
                .thenReturn(new BundlePriceApplyResult(true, 90L, 4, 2, 0));

        var resp = controller.addNeverPricedCatalogPrices(new InternalCredentialController.ApplyBundlePricesRequest(
                null, List.of(wire("elevenlabs", "eleven_v3", "character"), wire("elevenlabs", "music-v1", "second")),
                "catalog-starting-price"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PriceSpec>> rows = ArgumentCaptor.forClass(List.class);
        verify(pricingService).addNeverPricedPrices(eq(7L), rows.capture(), eq("catalog-starting-price"));
        assertThat(rows.getValue()).hasSize(2).allSatisfy(p -> assertThat(p.source()).isEqualTo(PriceSource.ADMIN));
        assertThat(resp.getBody()).containsEntry("publishedCredentials", 1).containsEntry("appliedPrices", 2);
        // It must never go through the bundle path, whose rule REPLACES bundle-owned rows.
        verify(pricingService, never()).applyBundlePrices(any(), anyList(), any());
    }

    @Test
    @DisplayName("An integration with no platform key is skipped, not failed: the next tick prices it once the key exists")
    void missingKeyIsSkipped() {
        when(platformCredentialService.getRawCredential("heygen")).thenReturn(Optional.empty());

        var resp = controller.addNeverPricedCatalogPrices(new InternalCredentialController.ApplyBundlePricesRequest(
                null, List.of(wire("heygen", "avatar-iv", "second")), null));

        assertThat(resp.getBody()).containsEntry("skippedIntegrations", 1);
        verify(pricingService, never()).addNeverPricedPrices(any(), anyList(), any());
    }

    @Test
    @DisplayName("A unit this build cannot measure drops that row only, never degrades it to a flat price")
    void unknownUnitRowIsDropped() {
        when(platformCredentialService.getRawCredential("flux")).thenReturn(Optional.of(credential(3L, "flux")));
        when(pricingService.addNeverPricedPrices(eq(3L), anyList(), any()))
                .thenReturn(new BundlePriceApplyResult(true, 1L, 1, 1, 0));

        controller.addNeverPricedCatalogPrices(new InternalCredentialController.ApplyBundlePricesRequest(
                null, List.of(wire("flux", "flux-pro", "image"), wire("flux", "flux-x", "megapixel-hour")), null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PriceSpec>> rows = ArgumentCaptor.forClass(List.class);
        verify(pricingService).addNeverPricedPrices(eq(3L), rows.capture(), eq("catalog-starting-price"));
        assertThat(rows.getValue()).extracting(PriceSpec::modelId).containsExactly("flux-pro");
    }

    @Test
    @DisplayName("A refused credential is reported by name and does not stop the others")
    void refusalIsIsolatedPerIntegration() {
        when(platformCredentialService.getRawCredential("a")).thenReturn(Optional.of(credential(1L, "a")));
        when(platformCredentialService.getRawCredential("b")).thenReturn(Optional.of(credential(2L, "b")));
        when(pricingService.addNeverPricedPrices(eq(1L), anyList(), any())).thenThrow(new IllegalArgumentException("bad"));
        when(pricingService.addNeverPricedPrices(eq(2L), anyList(), any()))
                .thenReturn(new BundlePriceApplyResult(true, 5L, 2, 1, 0));

        var resp = controller.addNeverPricedCatalogPrices(new InternalCredentialController.ApplyBundlePricesRequest(
                null, List.of(wire("a", "m1", "call"), wire("b", "m2", "call")), null));

        assertThat(resp.getBody()).containsEntry("publishedCredentials", 1);
        assertThat(resp.getBody().get("failures")).isEqualTo(List.of("a: bad"));
    }
}
