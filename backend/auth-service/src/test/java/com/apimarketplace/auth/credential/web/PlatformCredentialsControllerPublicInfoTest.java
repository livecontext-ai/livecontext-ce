package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredentialResponse;
import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
import com.apimarketplace.auth.credential.domain.PricingVersionEntry;
import com.apimarketplace.auth.credential.service.PlatformCredentialPricingService;
import com.apimarketplace.auth.credential.service.PlatformCredentialService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Covers {@link PlatformCredentialsController#publicInfo} - the non-admin
 * endpoint the workflow inspector uses to decide whether to show the
 * "Platform credential" source toggle on a node.
 *
 * <p>The endpoint has two modes:
 * <ol>
 *   <li>No {@code apiToolId} → integration-level "any non-zero rate" answer.</li>
 *   <li>With {@code apiToolId} → per-endpoint rate resolution (override beats
 *       default; null default + missing override = zero = no pricing).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformCredentialsController - public-info endpoint")
class PlatformCredentialsControllerPublicInfoTest {

    @Mock
    private PlatformCredentialService service;

    @Mock
    private PlatformCredentialPricingService pricingService;

    @Mock
    private com.apimarketplace.auth.credential.service.CredentialService credentialService;

    @InjectMocks
    private PlatformCredentialsController controller;

    private static final String INTEGRATION = "gmail";

    @Test
    @DisplayName("returns available=false and no id when no credential row exists")
    void missingCredential_returnsUnavailable() {
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, null, null, null, null, null, null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("integrationName")).isEqualTo(INTEGRATION);
        assertThat(body.get("available")).isEqualTo(false);
        assertThat(body.get("showUnverifiedAppWarning")).isEqualTo(false);
        assertThat(body.get("hasPricing")).isEqualTo(false);
        assertThat(body).doesNotContainKey("platformCredentialId");
        assertThat(body).doesNotContainKey("defaultMarkupCredits");
    }

    @Test
    @DisplayName("returns available=false when credential exists but is disabled")
    void disabledCredential_returnsUnavailable() {
        PlatformCredentialResponse cred = credential(false, true);
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
        when(pricingService.findLatest(cred.id())).thenReturn(Optional.empty());
        when(pricingService.hasAnyNonZeroMarkup(cred.id())).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, null, null, null, null, null, null);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("available")).isEqualTo(false);
        assertThat(body.get("platformCredentialId")).isEqualTo(cred.id());
        assertThat(body.get("hasPricing")).isEqualTo(false);
    }

    @Test
    @DisplayName("returns available=false when credential is enabled but has no secret")
    void enabledWithoutSecret_returnsUnavailable() {
        PlatformCredentialResponse cred = credential(true, false);
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
        when(pricingService.findLatest(cred.id())).thenReturn(Optional.empty());
        when(pricingService.hasAnyNonZeroMarkup(cred.id())).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, null, null, null, null, null, null);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("available")).isEqualTo(false);
    }

    @Test
    @DisplayName("no apiToolId - hasPricing reflects 'any non-zero rate on this integration'")
    void noToolId_hasPricingReflectsIntegrationWide() {
        PlatformCredentialResponse cred = credential(true, true);
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
        when(pricingService.findLatest(cred.id())).thenReturn(Optional.empty());
        when(pricingService.hasAnyNonZeroMarkup(cred.id())).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, null, null, null, null, null, null);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("available")).isEqualTo(true);
        assertThat(body.get("showUnverifiedAppWarning")).isEqualTo(true);
        assertThat(body.get("hasPricing")).isEqualTo(true);
        // No per-tool rate is reported when no apiToolId was supplied.
        assertThat(body).doesNotContainKey("markupCredits");
    }

    @Test
    @DisplayName("showUnverifiedAppWarning=false is exposed so the frontend can suppress the OAuth warning for verified providers")
    void publicInfo_exposesSuppressedUnverifiedWarningFlag() {
        PlatformCredentialResponse cred = credential(true, true, false);
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
        when(pricingService.findLatest(cred.id())).thenReturn(Optional.empty());
        when(pricingService.hasAnyNonZeroMarkup(cred.id())).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, null, null, null, null, null, null);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("available")).isEqualTo(true);
        assertThat(body.get("showUnverifiedAppWarning")).isEqualTo(false);
    }

    @Test
    @DisplayName("non-OAuth credentials never expose the OAuth unverified-app warning")
    void publicInfo_nonOAuthSuppressesUnverifiedWarningFlag() {
        PlatformCredentialResponse cred = credential("api_key", true, true, true);
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
        when(pricingService.findLatest(cred.id())).thenReturn(Optional.empty());
        when(pricingService.hasAnyNonZeroMarkup(cred.id())).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, null, null, null, null, null, null);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("available")).isEqualTo(true);
        assertThat(body.get("showUnverifiedAppWarning")).isEqualTo(false);
    }


    @Test
    @DisplayName("no apiToolId - returns defaultMarkupCredits and pricingVersion when a version exists")
    void noToolId_exposesVersionDefault() {
        PlatformCredentialResponse cred = credential(true, true);
        PlatformCredentialPricingVersion version = pricingVersion(cred.id(), 3, new BigDecimal("0.25"));
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
        when(pricingService.findLatest(cred.id())).thenReturn(Optional.of(version));
        when(pricingService.hasAnyNonZeroMarkup(cred.id())).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, null, null, null, null, null, null);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("hasPricing")).isEqualTo(true);
        assertThat(body.get("defaultMarkupCredits")).isEqualTo("0.25");
        assertThat(body.get("pricingVersion")).isEqualTo(3);
    }

    @Test
    @DisplayName("no apiToolId - omits defaultMarkupCredits when the latest version has null default")
    void noToolId_nullDefaultOmitsKey() {
        PlatformCredentialResponse cred = credential(true, true);
        PlatformCredentialPricingVersion version = pricingVersion(cred.id(), 4, null);
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
        when(pricingService.findLatest(cred.id())).thenReturn(Optional.of(version));
        when(pricingService.hasAnyNonZeroMarkup(cred.id())).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, null, null, null, null, null, null);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        // null default = overrides-only pricing; no defaultMarkupCredits field
        assertThat(body).doesNotContainKey("defaultMarkupCredits");
        assertThat(body.get("pricingVersion")).isEqualTo(4);
    }

    @Test
    @DisplayName("with apiToolId - hasPricing=true and markupCredits echoes the per-tool rate")
    void withToolId_returnsPerToolRate() {
        PlatformCredentialResponse cred = credential(true, true);
        UUID toolId = UUID.randomUUID();
        PlatformCredentialPricingVersion version = pricingVersion(cred.id(), 2, new BigDecimal("0.05"));
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
        when(pricingService.findLatest(cred.id())).thenReturn(Optional.of(version));
        when(pricingService.quoteLatest(cred.id(), toolId, null, null, null))
                .thenReturn(Optional.of(new PlatformCredentialPricingService.Quote(new BigDecimal("0.42"), 1L, null, null)));

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, toolId.toString(), null, null, null, null, null);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("hasPricing")).isEqualTo(true);
        assertThat(body.get("markupCredits")).isEqualTo("0.42");
        // Version default still surfaced for admin diagnostics.
        assertThat(body.get("defaultMarkupCredits")).isEqualTo("0.05");
    }

    @Test
    @DisplayName("the quote echoes the quantity it CHARGED for, not the platform measurement it was sent")
    void quoteEchoesTheBilledQuantityNotTheMeasurement() {
        // The inspector prints the rate's unit and the quantity side by side.
        // A 60 second call quoted against a per-minute rate is one minute, so
        // echoing the 60 back would make the note contradict its own rate.
        PlatformCredentialResponse cred = credential(true, true);
        UUID toolId = UUID.randomUUID();
        PlatformCredentialPricingVersion version = pricingVersion(cred.id(), 3, null);
        PricingVersionEntry perMinute = new PricingVersionEntry();
        perMinute.setMarkupCredits(BigDecimal.ZERO);
        perMinute.setPriceUnit("minute");
        perMinute.setUnitCredits(new BigDecimal("480"));
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
        when(pricingService.findLatest(cred.id())).thenReturn(Optional.of(version));
        when(pricingService.quoteLatest(cred.id(), toolId, "music-v1", new BigDecimal("60"), null))
                .thenReturn(Optional.of(new PlatformCredentialPricingService.Quote(
                        new BigDecimal("480"), 1L, perMinute, BigDecimal.ONE)));

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(
                INTEGRATION, toolId.toString(), "music-v1", new BigDecimal("60"), null, null, null);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("markupCredits")).isEqualTo("480");
        assertThat(body.get("priceUnit")).isEqualTo("minute");
        assertThat(body.get("quantity")).isEqualTo("1");
    }

    @Test
    @DisplayName("with apiToolId - hasPricing=false when the tool resolves to zero markup")
    void withToolId_zeroRateMeansNoPricing() {
        // This is the bug the user reported: an admin prices one endpoint only;
        // every OTHER endpoint of the same API should see hasPricing=false so
        // the inspector hides the platform/user toggle there.
        PlatformCredentialResponse cred = credential(true, true);
        UUID toolId = UUID.randomUUID();
        PlatformCredentialPricingVersion version = pricingVersion(cred.id(), 1, null);
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
        when(pricingService.findLatest(cred.id())).thenReturn(Optional.of(version));
        when(pricingService.quoteLatest(cred.id(), toolId, null, null, null))
                .thenReturn(Optional.of(new PlatformCredentialPricingService.Quote(BigDecimal.ZERO, 1L, null, null)));

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, toolId.toString(), null, null, null, null, null);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("hasPricing")).isEqualTo(false);
        assertThat(body).doesNotContainKey("markupCredits");
    }

    @Test
    @DisplayName("with apiToolId - hasPricing=false when the credential has no published version")
    void withToolId_noVersionMeansNoPricing() {
        PlatformCredentialResponse cred = credential(true, true);
        UUID toolId = UUID.randomUUID();
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
        when(pricingService.findLatest(cred.id())).thenReturn(Optional.empty());
        when(pricingService.quoteLatest(cred.id(), toolId, null, null, null))
                .thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, toolId.toString(), null, null, null, null, null);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("hasPricing")).isEqualTo(false);
        assertThat(body).doesNotContainKey("markupCredits");
    }

    @Test
    @DisplayName("invalid apiToolId string falls back to integration-level hasPricing")
    void invalidToolId_fallsBackToIntegrationWide() {
        // Garbage apiToolId must not 500 the endpoint; we silently fall back
        // to the no-tool branch so the inspector still gets a sane response.
        PlatformCredentialResponse cred = credential(true, true);
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
        when(pricingService.findLatest(cred.id())).thenReturn(Optional.empty());
        when(pricingService.hasAnyNonZeroMarkup(cred.id())).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, "not-a-uuid", null, null, null, null, null);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("hasPricing")).isEqualTo(true);
    }

    /**
     * A price for the ordinary calls of an API is not a price for a generation
     * on the same key.
     *
     * <p>When a generation model has no per-model row and its endpoint has no
     * per-endpoint row, the quote still resolves: the credential-wide default
     * applies and comes back POSITIVE. Execution then refuses that exact call
     * ({@code CatalogToolBillingService}, "a generation is not sold on a
     * catch-all"), so advertising the amount here quotes a number the server
     * will not charge - and the surfaces that read it rendered it as a hard
     * price beside the button that spends it.
     */
    @Nested
    @DisplayName("a generation quote that only reached the credential-wide default")
    class VersionDefaultOnlyGenerationQuote {

        @Test
        @DisplayName("is not advertised as pricing, and no amount is emitted for it")
        void notAdvertisedAsPricing() {
            PlatformCredentialResponse cred = credential(true, true);
            UUID toolId = UUID.randomUUID();
            PlatformCredentialPricingVersion version = pricingVersion(cred.id(), 7, new BigDecimal("0.05"));
            when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
            when(pricingService.findLatest(cred.id())).thenReturn(Optional.of(version));
            // entry == null is exactly "the version default applied".
            when(pricingService.quoteLatest(cred.id(), toolId, "seedance-2.0", new BigDecimal("10"), null))
                    .thenReturn(Optional.of(new PlatformCredentialPricingService.Quote(
                            new BigDecimal("0.05"), 1L, null, new BigDecimal("10"))));

            Map<String, Object> body = controller.publicInfo(
                    INTEGRATION, toolId.toString(), "seedance-2.0", new BigDecimal("10"), null, null, null).getBody();

            assertThat(body).isNotNull();
            assertThat(body.get("hasPricing")).isEqualTo(false);
            // Not merely absent from the caller's reading: absent from the wire,
            // so no surface can find a number to print.
            assertThat(body).doesNotContainKey("markupCredits");
            assertThat(body).doesNotContainKey("priceUnit");
            assertThat(body).doesNotContainKey("quantity");
        }

        @Test
        @DisplayName("says WHY, so a surface can name the missing decision instead of going quiet")
        void saysWhy() {
            PlatformCredentialResponse cred = credential(true, true);
            UUID toolId = UUID.randomUUID();
            when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
            when(pricingService.findLatest(cred.id()))
                    .thenReturn(Optional.of(pricingVersion(cred.id(), 7, new BigDecimal("0.05"))));
            when(pricingService.quoteLatest(cred.id(), toolId, "seedance-2.0", null, null))
                    .thenReturn(Optional.of(new PlatformCredentialPricingService.Quote(
                            new BigDecimal("0.05"), 1L, null, null)));

            Map<String, Object> body = controller.publicInfo(
                    INTEGRATION, toolId.toString(), "seedance-2.0", null, null, null, null).getBody();

            assertThat(body).isNotNull();
            assertThat(body.get("versionDefaultOnly")).isEqualTo(true);
        }

        @Test
        @DisplayName("a per-model row published for that model IS a price, and is quoted in full")
        void aPublishedPerModelRowIsStillQuoted() {
            // The guard must key on where the amount CAME FROM, not on the mere
            // presence of a model id, or it would suppress every real quote.
            PlatformCredentialResponse cred = credential(true, true);
            UUID toolId = UUID.randomUUID();
            PricingVersionEntry perSecond = new PricingVersionEntry();
            perSecond.setMarkupCredits(BigDecimal.ZERO);
            perSecond.setPriceUnit("second");
            perSecond.setUnitCredits(new BigDecimal("60"));
            when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
            when(pricingService.findLatest(cred.id()))
                    .thenReturn(Optional.of(pricingVersion(cred.id(), 7, new BigDecimal("0.05"))));
            when(pricingService.quoteLatest(cred.id(), toolId, "seedance-2.0", new BigDecimal("10"), null))
                    .thenReturn(Optional.of(new PlatformCredentialPricingService.Quote(
                            new BigDecimal("600"), 1L, perSecond, new BigDecimal("10"))));

            Map<String, Object> body = controller.publicInfo(
                    INTEGRATION, toolId.toString(), "seedance-2.0", new BigDecimal("10"), null, null, null).getBody();

            assertThat(body).isNotNull();
            assertThat(body.get("hasPricing")).isEqualTo(true);
            assertThat(body.get("markupCredits")).isEqualTo("600");
            assertThat(body.get("priceUnit")).isEqualTo("second");
            assertThat(body).doesNotContainKey("versionDefaultOnly");
        }

        @Test
        @DisplayName("the factor the caller's choices reached is priced with, and echoed back")
        void aPriceFactorIsQuotedAndExplained() {
            // The amount alone cannot be checked: 1200 beside "60 credits per second" and a size of
            // 10 reads as an arithmetic error unless the third number is there to be named. And a
            // quote that dropped the factor would show 600 next to a button that spends 1200.
            PlatformCredentialResponse cred = credential(true, true);
            UUID toolId = UUID.randomUUID();
            PricingVersionEntry perSecond = new PricingVersionEntry();
            perSecond.setMarkupCredits(BigDecimal.ZERO);
            perSecond.setPriceUnit("second");
            perSecond.setUnitCredits(new BigDecimal("60"));
            when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
            when(pricingService.findLatest(cred.id()))
                    .thenReturn(Optional.of(pricingVersion(cred.id(), 7, new BigDecimal("0.05"))));
            when(pricingService.quoteLatest(cred.id(), toolId, "seedance-2.0", new BigDecimal("10"),
                    new BigDecimal("2")))
                    .thenReturn(Optional.of(new PlatformCredentialPricingService.Quote(
                            new BigDecimal("1200"), 1L, perSecond, new BigDecimal("10"),
                            new BigDecimal("2"))));

            Map<String, Object> body = controller.publicInfo(
                    INTEGRATION, toolId.toString(), "seedance-2.0", new BigDecimal("10"), null, null,
                    new BigDecimal("2")).getBody();

            assertThat(body).isNotNull();
            assertThat(body.get("markupCredits")).isEqualTo("1200");
            assertThat(body.get("priceMultiplier")).isEqualTo("2");
        }

        @Test
        @DisplayName("a factor above the ceiling is dropped: no descriptor can reach it")
        void aFactorAboveTheCeilingIsDropped() {
            // The parser refuses a model whose modifiers can reach more than 100 TOGETHER, so a
            // larger value here did not come from a price this platform published. The quote falls
            // back to the published rate rather than failing the panel: this is a read.
            PlatformCredentialResponse cred = credential(true, true);
            UUID toolId = UUID.randomUUID();
            PricingVersionEntry perSecond = new PricingVersionEntry();
            perSecond.setMarkupCredits(BigDecimal.ZERO);
            perSecond.setPriceUnit("second");
            perSecond.setUnitCredits(new BigDecimal("60"));
            when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
            when(pricingService.findLatest(cred.id()))
                    .thenReturn(Optional.of(pricingVersion(cred.id(), 7, new BigDecimal("0.05"))));
            when(pricingService.quoteLatest(cred.id(), toolId, "seedance-2.0", new BigDecimal("10"), null))
                    .thenReturn(Optional.of(new PlatformCredentialPricingService.Quote(
                            new BigDecimal("600"), 1L, perSecond, new BigDecimal("10"))));

            Map<String, Object> body = controller.publicInfo(
                    INTEGRATION, toolId.toString(), "seedance-2.0", new BigDecimal("10"), null, null,
                    new BigDecimal("250")).getBody();

            assertThat(body).isNotNull();
            assertThat(body.get("markupCredits")).isEqualTo("600");
            assertThat(body).doesNotContainKey("priceMultiplier");
        }

        @Test
        @DisplayName("a factor that could not come from any descriptor is dropped, not quoted")
        void anAbsurdFactorIsDropped() {
            // Zero would advertise a free generation the server then charges for, and this is a
            // READ: it must fall back to the published rate rather than fail the panel.
            PlatformCredentialResponse cred = credential(true, true);
            UUID toolId = UUID.randomUUID();
            PricingVersionEntry perSecond = new PricingVersionEntry();
            perSecond.setMarkupCredits(BigDecimal.ZERO);
            perSecond.setPriceUnit("second");
            perSecond.setUnitCredits(new BigDecimal("60"));
            when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
            when(pricingService.findLatest(cred.id()))
                    .thenReturn(Optional.of(pricingVersion(cred.id(), 7, new BigDecimal("0.05"))));
            when(pricingService.quoteLatest(cred.id(), toolId, "seedance-2.0", new BigDecimal("10"), null))
                    .thenReturn(Optional.of(new PlatformCredentialPricingService.Quote(
                            new BigDecimal("600"), 1L, perSecond, new BigDecimal("10"))));

            Map<String, Object> body = controller.publicInfo(
                    INTEGRATION, toolId.toString(), "seedance-2.0", new BigDecimal("10"), null, null,
                    BigDecimal.ZERO).getBody();

            assertThat(body).isNotNull();
            assertThat(body.get("markupCredits")).isEqualTo("600");
            assertThat(body).doesNotContainKey("priceMultiplier");
        }

        @Test
        @DisplayName("a per-unit rate with NO size is not quoted: that amount covers one unit, not the call")
        void aPerUnitRateWithoutASizeIsNotQuoted() {
            // The biller's THIRD generation refusal, which this quote was
            // missing while the CE relay applied it. Resolved without a
            // quantity, a per-unit row yields the price of ONE unit: the
            // inspector printed "60 credits per second" for a step that is then
            // refused GENERATION_SIZE_UNKNOWN on every run. An mcp: step bound
            // straight to a generation endpoint is exactly that caller, since
            // it sends the endpoint and never a size.
            PlatformCredentialResponse cred = credential(true, true);
            UUID toolId = UUID.randomUUID();
            PricingVersionEntry perSecond = new PricingVersionEntry();
            perSecond.setMarkupCredits(BigDecimal.ZERO);
            perSecond.setPriceUnit("second");
            perSecond.setUnitCredits(new BigDecimal("60"));
            when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
            when(pricingService.findLatest(cred.id()))
                    .thenReturn(Optional.of(pricingVersion(cred.id(), 7, new BigDecimal("0.05"))));
            when(pricingService.quoteLatest(cred.id(), toolId, null, null, null))
                    .thenReturn(Optional.of(new PlatformCredentialPricingService.Quote(
                            new BigDecimal("60"), 1L, perSecond, null)));

            Map<String, Object> body = controller.publicInfo(
                    INTEGRATION, toolId.toString(), null, null, true, null, null).getBody();

            assertThat(body).isNotNull();
            assertThat(body.get("hasPricing")).isEqualTo(false);
            assertThat(body).doesNotContainKey("markupCredits");
        }

        @Test
        @DisplayName("the same per-unit rate WITH a size is quoted, so the rule is about the size and not the rate")
        void aPerUnitRateWithASizeIsQuoted() {
            PlatformCredentialResponse cred = credential(true, true);
            UUID toolId = UUID.randomUUID();
            PricingVersionEntry perSecond = new PricingVersionEntry();
            perSecond.setMarkupCredits(BigDecimal.ZERO);
            perSecond.setPriceUnit("second");
            perSecond.setUnitCredits(new BigDecimal("60"));
            when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
            when(pricingService.findLatest(cred.id()))
                    .thenReturn(Optional.of(pricingVersion(cred.id(), 7, new BigDecimal("0.05"))));
            when(pricingService.quoteLatest(cred.id(), toolId, "seedance-2.0", new BigDecimal("10"), null))
                    .thenReturn(Optional.of(new PlatformCredentialPricingService.Quote(
                            new BigDecimal("600"), 1L, perSecond, new BigDecimal("10"))));

            Map<String, Object> body = controller.publicInfo(
                    INTEGRATION, toolId.toString(), "seedance-2.0", new BigDecimal("10"),
                    true, "second", null).getBody();

            assertThat(body).isNotNull();
            assertThat(body.get("hasPricing")).isEqualTo(true);
            assertThat(body.get("markupCredits")).isEqualTo("600");
        }

        @Test
        @DisplayName("an ORDINARY per-unit endpoint is still quoted without a size, because it IS billed")
        void anOrdinaryPerUnitEndpointIsStillQuoted() {
            // The rule is about generations. An ordinary endpoint priced per
            // unit is sold and charged at its base amount, so suppressing its
            // quote would hide a price the customer does pay.
            PlatformCredentialResponse cred = credential(true, true);
            UUID toolId = UUID.randomUUID();
            PricingVersionEntry perUnit = new PricingVersionEntry();
            perUnit.setMarkupCredits(BigDecimal.ZERO);
            perUnit.setPriceUnit("second");
            perUnit.setUnitCredits(new BigDecimal("60"));
            when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
            when(pricingService.findLatest(cred.id()))
                    .thenReturn(Optional.of(pricingVersion(cred.id(), 7, new BigDecimal("0.05"))));
            when(pricingService.quoteLatest(cred.id(), toolId, null, null, null))
                    .thenReturn(Optional.of(new PlatformCredentialPricingService.Quote(
                            new BigDecimal("60"), 1L, perUnit, null)));

            Map<String, Object> body = controller.publicInfo(
                    INTEGRATION, toolId.toString(), null, null, null, null, null).getBody();

            assertThat(body).isNotNull();
            assertThat(body.get("hasPricing")).isEqualTo(true);
        }

        @Test
        @DisplayName("a rate of the wrong DIMENSION is not quoted, because no run of it can be charged")
        void aRateThatCannotPriceThisCallIsNotQuoted() {
            // The pair both billers refuse: a row published per IMAGE against a
            // call counted in SECONDS. Publishing cannot catch it, since its own
            // guard arms only on a re-publish. Quoting it shows a number and
            // then every run is refused, which is the single thing a quote must
            // never do.
            PlatformCredentialResponse cred = credential(true, true);
            UUID toolId = UUID.randomUUID();
            PricingVersionEntry perImage = new PricingVersionEntry();
            perImage.setMarkupCredits(BigDecimal.ZERO);
            perImage.setPriceUnit("image");
            perImage.setUnitCredits(new BigDecimal("60"));
            when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
            when(pricingService.findLatest(cred.id()))
                    .thenReturn(Optional.of(pricingVersion(cred.id(), 7, new BigDecimal("0.05"))));
            when(pricingService.quoteLatest(cred.id(), toolId, "seedance-2.0", new BigDecimal("10"), null))
                    .thenReturn(Optional.of(new PlatformCredentialPricingService.Quote(
                            new BigDecimal("600"), 1L, perImage, new BigDecimal("10"))));

            Map<String, Object> body = controller.publicInfo(
                    INTEGRATION, toolId.toString(), "seedance-2.0", new BigDecimal("10"),
                    true, "second", null).getBody();

            assertThat(body).isNotNull();
            assertThat(body.get("hasPricing")).isEqualTo(false);
            assertThat(body).doesNotContainKey("markupCredits");
        }

        @Test
        @DisplayName("the SAME dimension at another scale is quoted: per minute prices a call in seconds")
        void aRateOfTheSameDimensionIsStillQuoted() {
            // The negative half. Without it, suppressing every quote would score
            // as a pass on the test above.
            PlatformCredentialResponse cred = credential(true, true);
            UUID toolId = UUID.randomUUID();
            PricingVersionEntry perMinute = new PricingVersionEntry();
            perMinute.setMarkupCredits(BigDecimal.ZERO);
            perMinute.setPriceUnit("minute");
            perMinute.setUnitCredits(new BigDecimal("3600"));
            when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
            when(pricingService.findLatest(cred.id()))
                    .thenReturn(Optional.of(pricingVersion(cred.id(), 7, new BigDecimal("0.05"))));
            when(pricingService.quoteLatest(cred.id(), toolId, "seedance-2.0", new BigDecimal("10"), null))
                    .thenReturn(Optional.of(new PlatformCredentialPricingService.Quote(
                            new BigDecimal("600"), 1L, perMinute, new BigDecimal("10"))));

            Map<String, Object> body = controller.publicInfo(
                    INTEGRATION, toolId.toString(), "seedance-2.0", new BigDecimal("10"),
                    true, "second", null).getBody();

            assertThat(body).isNotNull();
            assertThat(body.get("hasPricing")).isEqualTo(true);
            assertThat(body.get("markupCredits")).isEqualTo("600");
        }

        @Test
        @DisplayName("a caller that cannot state the unit gets the answer it always got")
        void anAbsentUnitLeavesTheQuestionUnasked() {
            // Absent means "this surface cannot say", not "no unit". Every
            // caller predating this parameter must keep its behaviour, and the
            // flag must never be usable to obtain a quote: it can only ever
            // turn one off.
            PlatformCredentialResponse cred = credential(true, true);
            UUID toolId = UUID.randomUUID();
            PricingVersionEntry perImage = new PricingVersionEntry();
            perImage.setMarkupCredits(BigDecimal.ZERO);
            perImage.setPriceUnit("image");
            perImage.setUnitCredits(new BigDecimal("60"));
            when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
            when(pricingService.findLatest(cred.id()))
                    .thenReturn(Optional.of(pricingVersion(cred.id(), 7, new BigDecimal("0.05"))));
            when(pricingService.quoteLatest(cred.id(), toolId, "seedance-2.0", new BigDecimal("10"), null))
                    .thenReturn(Optional.of(new PlatformCredentialPricingService.Quote(
                            new BigDecimal("600"), 1L, perImage, new BigDecimal("10"))));

            Map<String, Object> body = controller.publicInfo(
                    INTEGRATION, toolId.toString(), "seedance-2.0", new BigDecimal("10"),
                    true, null, null).getBody();

            assertThat(body).isNotNull();
            assertThat(body.get("hasPricing")).isEqualTo(true);
        }

        @Test
        @DisplayName("an ORDINARY endpoint still inherits the default, which is what a default is for")
        void ordinaryEndpointStillInheritsTheDefault() {
            // Neither half of the generation question is answered yes here: no
            // model was named AND the caller did not report a generation
            // descriptor on the endpoint. So this is an ordinary call, and the
            // version default is precisely the price the owner published for it.
            // Refusing here would take the default away from the 1000+ endpoints
            // it exists for.
            PlatformCredentialResponse cred = credential(true, true);
            UUID toolId = UUID.randomUUID();
            when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
            when(pricingService.findLatest(cred.id()))
                    .thenReturn(Optional.of(pricingVersion(cred.id(), 7, new BigDecimal("0.05"))));
            when(pricingService.quoteLatest(cred.id(), toolId, null, null, null))
                    .thenReturn(Optional.of(new PlatformCredentialPricingService.Quote(
                            new BigDecimal("0.05"), 1L, null, null)));

            Map<String, Object> body = controller.publicInfo(
                    INTEGRATION, toolId.toString(), null, null, null, null, null).getBody();

            assertThat(body).isNotNull();
            assertThat(body.get("hasPricing")).isEqualTo(true);
            assertThat(body.get("markupCredits")).isEqualTo("0.05");
            assertThat(body).doesNotContainKey("versionDefaultOnly");
        }

        /**
         * The caller that cannot name a model, and the one the model-id
         * discriminator got wrong.
         *
         * <p>An {@code mcp:} step bound straight to a generation endpoint
         * ({@code seedance/create_video_task}) sends an apiToolId and no model:
         * there is nowhere on that node to put one. The billing path still
         * treats it as a generation, because it reads the endpoint's descriptor
         * rather than the call's model id, and refuses the credential-wide
         * default for it. This quote used to answer the opposite, so the
         * inspector printed the catch-all rate and offered the platform toggle
         * on a step the server will not run.
         */
        @Test
        @DisplayName("an mcp: step on a generation endpoint is not sold on the default either, model or no model")
        void generationEndpointWithoutAModelIsNotSoldOnTheDefault() {
            PlatformCredentialResponse cred = credential(true, true);
            UUID toolId = UUID.randomUUID();
            when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
            when(pricingService.findLatest(cred.id()))
                    .thenReturn(Optional.of(pricingVersion(cred.id(), 7, new BigDecimal("0.05"))));
            when(pricingService.quoteLatest(cred.id(), toolId, null, null, null))
                    .thenReturn(Optional.of(new PlatformCredentialPricingService.Quote(
                            new BigDecimal("0.05"), 1L, null, null)));

            Map<String, Object> body = controller.publicInfo(
                    INTEGRATION, toolId.toString(), null, null, true, null, null).getBody();

            assertThat(body).isNotNull();
            assertThat(body.get("hasPricing")).isEqualTo(false);
            assertThat(body.get("versionDefaultOnly")).isEqualTo(true);
            // Absent from the wire, so no surface can find a number to print
            // beside a step the server refuses to run.
            assertThat(body).doesNotContainKey("markupCredits");
        }

        @Test
        @DisplayName("a generation endpoint WITH a published row of its own is quoted in full, model or no model")
        void generationEndpointWithItsOwnPublishedRowIsStillQuoted() {
            // The guard keys on where the amount came from, not on the endpoint
            // being a generation: an admin who published a per-endpoint price
            // made exactly the decision the guard demands.
            PlatformCredentialResponse cred = credential(true, true);
            UUID toolId = UUID.randomUUID();
            PricingVersionEntry perEndpoint = new PricingVersionEntry();
            perEndpoint.setMarkupCredits(new BigDecimal("250"));
            perEndpoint.setPriceUnit("call");
            perEndpoint.setUnitCredits(BigDecimal.ZERO);
            when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
            when(pricingService.findLatest(cred.id()))
                    .thenReturn(Optional.of(pricingVersion(cred.id(), 7, new BigDecimal("0.05"))));
            when(pricingService.quoteLatest(cred.id(), toolId, null, null, null))
                    .thenReturn(Optional.of(new PlatformCredentialPricingService.Quote(
                            new BigDecimal("250"), 1L, perEndpoint, null)));

            Map<String, Object> body = controller.publicInfo(
                    INTEGRATION, toolId.toString(), null, null, true, null, null).getBody();

            assertThat(body).isNotNull();
            assertThat(body.get("hasPricing")).isEqualTo(true);
            assertThat(body.get("markupCredits")).isEqualTo("250");
            assertThat(body).doesNotContainKey("versionDefaultOnly");
        }

        @Test
        @DisplayName("generation=false is not a way to buy a generation at the catch-all rate when a model is named")
        void namingAModelStillSettlesItWhenTheCallerSaysNo() {
            // The two halves are an OR, exactly as CatalogToolBillingService
            // asks it. A caller that says "ordinary endpoint" while naming a
            // generation model is answered on the model, so a stale or hostile
            // client cannot talk the quote into a number the server refuses.
            PlatformCredentialResponse cred = credential(true, true);
            UUID toolId = UUID.randomUUID();
            when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
            when(pricingService.findLatest(cred.id()))
                    .thenReturn(Optional.of(pricingVersion(cred.id(), 7, new BigDecimal("0.05"))));
            when(pricingService.quoteLatest(cred.id(), toolId, "seedance-2.0", null, null))
                    .thenReturn(Optional.of(new PlatformCredentialPricingService.Quote(
                            new BigDecimal("0.05"), 1L, null, null)));

            Map<String, Object> body = controller.publicInfo(
                    INTEGRATION, toolId.toString(), "seedance-2.0", null, false, null, null).getBody();

            assertThat(body).isNotNull();
            assertThat(body.get("hasPricing")).isEqualTo(false);
            assertThat(body.get("versionDefaultOnly")).isEqualTo(true);
        }
    }

    private PlatformCredentialResponse credential(boolean enabled, boolean hasSecret) {
        return credential(enabled, hasSecret, true);
    }

    private PlatformCredentialResponse credential(boolean enabled, boolean hasSecret, boolean showUnverifiedAppWarning) {
        return credential("oauth2", enabled, hasSecret, showUnverifiedAppWarning);
    }

    private PlatformCredentialResponse credential(
            String authType,
            boolean enabled,
            boolean hasSecret,
            boolean showUnverifiedAppWarning
    ) {
        return new PlatformCredentialResponse(
                42L,
                INTEGRATION,
                "Gmail",
                authType,
                "aaaa****zzzz",
                "oauth2".equals(authType) && hasSecret, // hasClientSecret
                !"oauth2".equals(authType) && hasSecret, // hasApiKey
                false,     // hasBasicAuth
                false,     // hasCustomFields
                "https://auth",
                "https://token",
                "scope1",
                "gmail",
                "productivity",
                "Send and read Gmail",
                showUnverifiedAppWarning,
                enabled,
                BigDecimal.ZERO,
                0,
                List.of(),
                Instant.EPOCH,
                Instant.EPOCH,
                null,
                "primary"
        );
    }

    private PlatformCredentialPricingVersion pricingVersion(long credentialId, int version, BigDecimal defaultMarkup) {
        PlatformCredentialPricingVersion v = new PlatformCredentialPricingVersion();
        v.setId(version * 1000L + credentialId);
        v.setPlatformCredentialId(credentialId);
        v.setVersion(version);
        v.setDefaultMarkupCredits(defaultMarkup);
        v.setCreatedAt(Instant.EPOCH);
        return v;
    }
}
