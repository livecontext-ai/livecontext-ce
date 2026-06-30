package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V148+ invariant: the new reservation lifecycle MUST NOT write ledger rows
 * with the legacy {@code IMAGE_GENERATION} or {@code IMAGE_GENERATION_BYOK}
 * source types. Those values are display-only for historical rows now -
 * any new write would mean the legacy {@code consumeForImageGeneration} path
 * was reactivated by mistake and the unified path is leaking.
 *
 * <p>This is a regression guard. If the assertion ever fails, it means
 * someone re-introduced a code path that bypasses the unified scope
 * reservation flow - investigate before merging.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("V148+ invariant: no legacy IMAGE_GENERATION ledger writes")
class NoLegacyImageGenerationLedgerWriteTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private CreditLedgerRepository ledgerRepository;
    @Mock private ModelPricingService pricingService;

    private CreditService creditService;

    @BeforeEach
    void setUp() {
        creditService = new CreditService(subscriptionRepository, ledgerRepository, pricingService, false);
    }

    private Subscription sub() {
        Subscription s = new Subscription();
        s.setId(1L);
        s.setRemainingCredits(new BigDecimal("100"));
        s.setDelinquent(false);
        return s;
    }

    @Test
    @DisplayName("tryReserveMarkup writes PLATFORM_MARKUP_RESERVE - never IMAGE_GENERATION*")
    void reserveDoesNotWriteLegacySourceType() {
        when(ledgerRepository.existsBySourceId(any())).thenReturn(false);
        when(subscriptionRepository.findActiveByUserIdForUpdate(1L)).thenReturn(Optional.of(sub()));

        creditService.tryReserveMarkup(
                1L, "platform-markup:STREAM:s1:t:0", "openai", "gpt-image-1.5-medium",
                new BigDecimal("34"), 7L, 10, "STREAM", "s1", false);

        ArgumentCaptor<CreditLedgerEntry> cap = ArgumentCaptor.forClass(CreditLedgerEntry.class);
        verify(ledgerRepository).save(cap.capture());
        String written = cap.getValue().getSourceType();
        assertThat(written)
                .as("V148+ unified path must write PLATFORM_MARKUP_RESERVE - IMAGE_GENERATION* is dead")
                .isEqualTo("PLATFORM_MARKUP_RESERVE")
                .doesNotStartWith("IMAGE_GENERATION");
    }

    @Test
    @DisplayName("commitReservation flips to PLATFORM_MARKUP - never IMAGE_GENERATION*")
    void commitDoesNotWriteLegacySourceType() {
        CreditLedgerEntry row = new CreditLedgerEntry();
        row.setUserId(1L);
        row.setSourceId("platform-markup:STREAM:s1:t:0");
        row.setSourceType("PLATFORM_MARKUP_RESERVE");
        row.setAmount(new BigDecimal("-34"));
        when(ledgerRepository.findFirstBySourceId(any())).thenReturn(Optional.of(row));
        when(subscriptionRepository.findActiveByUserIdForUpdate(1L)).thenReturn(Optional.of(sub()));

        creditService.commitReservation(
                "platform-markup:STREAM:s1:t:0", new BigDecimal("30"), "openai", "gpt-image-1.5-medium");

        // After commit, row source_type must be PLATFORM_MARKUP, not IMAGE_GENERATION*
        assertThat(row.getSourceType())
                .as("V148+ unified path must flip to PLATFORM_MARKUP - IMAGE_GENERATION* is dead")
                .isEqualTo("PLATFORM_MARKUP")
                .doesNotStartWith("IMAGE_GENERATION");
    }

    @Test
    @DisplayName("releaseReservation flips to PLATFORM_MARKUP_RELEASED* - never IMAGE_GENERATION*")
    void releaseDoesNotWriteLegacySourceType() {
        CreditLedgerEntry row = new CreditLedgerEntry();
        row.setUserId(1L);
        row.setSourceId("platform-markup:STREAM:s1:t:0");
        row.setSourceType("PLATFORM_MARKUP_RESERVE");
        row.setAmount(new BigDecimal("-34"));
        when(ledgerRepository.findFirstBySourceId(any())).thenReturn(Optional.of(row));
        when(subscriptionRepository.findActiveByUserIdForUpdate(1L)).thenReturn(Optional.of(sub()));

        creditService.releaseReservation(
                "platform-markup:STREAM:s1:t:0", "auto-release-timeout: TTL elapsed");

        assertThat(row.getSourceType())
                .as("V148+ unified path must flip to PLATFORM_MARKUP_RELEASED_TIMEOUT - IMAGE_GENERATION* is dead")
                .isEqualTo("PLATFORM_MARKUP_RELEASED_TIMEOUT")
                .doesNotStartWith("IMAGE_GENERATION");
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
