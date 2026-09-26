package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.service.CreditService.CreditConsumeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The branches of the replay gate in {@code CreditService.deductCredits} that the real-Postgres
 * suite ({@code RejectedConsumptionReplayTest}) does not reach: unlimited mode, the zero-cost
 * row, a key held by ANOTHER payer or source type, and the rejection key itself.
 *
 * <p>Regression context (2026-09-25): a consumption refused for insufficient balance left its
 * audit row on the charge's {@code source_id}, and a replay of a consumption already charged had
 * no guard at all, so both ended on {@code idx_cl_source_id_unique} as an HTTP 500.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreditService replay of a consumption: idempotent, never a free pass")
class CreditServiceReplayIdempotencyTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private CreditLedgerRepository ledgerRepository;
    @Mock private ModelPricingService pricingService;
    @Captor private ArgumentCaptor<CreditLedgerEntry> ledgerCaptor;

    private static final Long USER_ID = 42L;
    private static final String SOURCE_ID = "exec-1";
    private static final LlmTokenBreakdown USAGE = LlmTokenBreakdown.of(1000, 100);

    private CreditService metered;

    @BeforeEach
    void setUp() {
        metered = new CreditService(subscriptionRepository, ledgerRepository, pricingService, false);
    }

    private void activeSubscription(BigDecimal balance) {
        Subscription sub = new Subscription();
        sub.setId(1L);
        sub.setRemainingCredits(balance);
        lenient().when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(sub));
        lenient().when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(sub));
    }

    private static CreditLedgerEntry row(Long userId, String sourceType, String sourceId) {
        CreditLedgerEntry e = new CreditLedgerEntry();
        e.setUserId(userId);
        e.setSourceType(sourceType);
        e.setSourceId(sourceId);
        e.setAmount(BigDecimal.ZERO);
        e.setBalanceAfter(BigDecimal.ZERO);
        return e;
    }

    @Test
    @DisplayName("metered: a replay of the same payer's charge debits nothing and writes nothing, even on an empty wallet")
    void meteredReplayIsIdempotentOnAnEmptyWallet() {
        activeSubscription(BigDecimal.ZERO);
        when(pricingService.calculateCost("p", "m", USAGE)).thenReturn(new BigDecimal("5"));
        when(ledgerRepository.findFirstBySourceId(SOURCE_ID))
                .thenReturn(Optional.of(row(USER_ID, "COMPACTION_SUMMARY", SOURCE_ID)));

        CreditConsumeResult result = metered.consumeForAgent(USER_ID, SOURCE_ID, "p", "m", USAGE, "COMPACTION_SUMMARY");

        assertThat(result.success()).isTrue();
        assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(ledgerRepository, never()).save(any());
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("unlimited mode: a replay writes no second tracking row")
    void unlimitedReplayWritesNoSecondRow() {
        CreditService unlimited = new CreditService(subscriptionRepository, ledgerRepository, pricingService, true);
        when(pricingService.calculateCost("p", "m", USAGE)).thenReturn(new BigDecimal("5"));
        when(ledgerRepository.findFirstBySourceId(SOURCE_ID))
                .thenReturn(Optional.of(row(USER_ID, "AGENT_EXECUTION", SOURCE_ID)));

        CreditConsumeResult result = unlimited.consumeForAgent(USER_ID, SOURCE_ID, "p", "m", USAGE, "AGENT_EXECUTION");

        assertThat(result.success()).isTrue();
        assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    @DisplayName("zero-cost row: a replay writes no second audit row")
    void zeroCostReplayWritesNoSecondRow() {
        when(pricingService.calculateCost("p", "m", USAGE)).thenReturn(BigDecimal.ZERO);
        when(ledgerRepository.findFirstBySourceId(SOURCE_ID))
                .thenReturn(Optional.of(row(USER_ID, "CLI_SESSION", SOURCE_ID)));

        CreditConsumeResult result = metered.consumeForAgent(USER_ID, SOURCE_ID, "p", "m", USAGE, "CLI_SESSION");

        assertThat(result.success()).isTrue();
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    @DisplayName("a key held by ANOTHER payer is not a replay: the consumption is still charged, never a free pass")
    void keyHeldByAnotherPayerIsNotAReplay() {
        activeSubscription(new BigDecimal("100"));
        when(pricingService.calculateCost("p", "m", USAGE)).thenReturn(new BigDecimal("5"));
        when(ledgerRepository.findFirstBySourceId(SOURCE_ID))
                .thenReturn(Optional.of(row(99L, "COMPACTION_SUMMARY", SOURCE_ID)));

        CreditConsumeResult result = metered.consumeForAgent(USER_ID, SOURCE_ID, "p", "m", USAGE, "COMPACTION_SUMMARY");

        // The debit is attempted exactly as before the fix. What the INSERT then meets is the
        // unique index's business, which a mocked repository cannot show.
        assertThat(result.creditsUsed()).isEqualByComparingTo(new BigDecimal("5"));
        verify(ledgerRepository).save(any());
    }

    @Test
    @DisplayName("a key held by ANOTHER source type of the same payer is not a replay either")
    void keyHeldByAnotherSourceTypeIsNotAReplay() {
        activeSubscription(new BigDecimal("100"));
        when(pricingService.calculateCost("p", "m", USAGE)).thenReturn(new BigDecimal("5"));
        when(ledgerRepository.findFirstBySourceId(SOURCE_ID))
                .thenReturn(Optional.of(row(USER_ID, "AGENT_EXECUTION", SOURCE_ID)));

        CreditConsumeResult result = metered.consumeForAgent(USER_ID, SOURCE_ID, "p", "m", USAGE, "COMPACTION_SUMMARY");

        assertThat(result.creditsUsed()).isEqualByComparingTo(new BigDecimal("5"));
        verify(ledgerRepository).save(any());
    }

    @Test
    @DisplayName("a legacy rejection row is NOT moved when the consumption is refused again (only a charge frees the key)")
    void legacyRejectionIsNotMovedOnTheRefusalPath() {
        activeSubscription(BigDecimal.ZERO);
        when(pricingService.calculateCost("p", "m", USAGE)).thenReturn(new BigDecimal("5"));
        when(ledgerRepository.findFirstBySourceId(SOURCE_ID))
                .thenReturn(Optional.of(row(USER_ID, "COMPACTION_SUMMARY_REJECTED", SOURCE_ID)));

        CreditConsumeResult result = metered.consumeForAgent(USER_ID, SOURCE_ID, "p", "m", USAGE, "COMPACTION_SUMMARY");

        assertThat(result.success()).isFalse();
        // Moving it here would make the REQUIRES_NEW audit insert wait on the suspended
        // transaction's lock on the same key.
        verify(ledgerRepository, never()).saveAndFlush(any());
        verify(ledgerRepository).save(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("COMPACTION_SUMMARY_REJECTED");
        assertThat(ledgerCaptor.getValue().getSourceId()).isEqualTo(SOURCE_ID + ":rejected");
    }

    @Test
    @DisplayName("promo-free workflow node: a legacy rejection row leaves the key before the PROMO row takes it")
    void promoNodeFreesALegacyRejectionKey() {
        RewardService rewardService = org.mockito.Mockito.mock(RewardService.class);
        metered.setRewardService(rewardService);
        activeSubscription(new BigDecimal("100"));
        when(rewardService.claimFreeWorkflowNode(USER_ID)).thenReturn(true);
        CreditLedgerEntry legacy = row(USER_ID, "WORKFLOW_NODE_REJECTED", SOURCE_ID);
        when(ledgerRepository.findFirstBySourceId(SOURCE_ID)).thenReturn(Optional.of(legacy));

        CreditConsumeResult result = metered.consumeForWorkflowNode(USER_ID, SOURCE_ID);

        // Without the move the PROMO insert meets idx_cl_source_id_unique: a 500.
        assertThat(result.success()).isTrue();
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(ledgerRepository);
        order.verify(ledgerRepository).saveAndFlush(legacy);
        order.verify(ledgerRepository).save(ledgerCaptor.capture());
        assertThat(legacy.getSourceId()).isEqualTo(SOURCE_ID + ":rejected");
        assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("WORKFLOW_NODE_PROMO");
        assertThat(ledgerCaptor.getValue().getSourceId()).isEqualTo(SOURCE_ID);
    }

    private static final String PURCHASE_KEY = "marketplace-purchase:org-1:pub-1";

    @Test
    @DisplayName("regression: a retry of the same marketplace purchase (per-purchase key, same buyer) is an idempotent success, never a second debit")
    void marketplacePurchaseRetryOnItsPurchaseKeyIsAReplay() {
        activeSubscription(new BigDecimal("100"));
        when(ledgerRepository.findFirstBySourceId(PURCHASE_KEY))
                .thenReturn(Optional.of(row(USER_ID, "MARKETPLACE_PURCHASE", PURCHASE_KEY)));

        CreditConsumeResult result = metered.consumeForMarketplacePurchase(USER_ID, PURCHASE_KEY, 10);

        // Pre-fix every MARKETPLACE_PURCHASE was excluded from the gate: the retry debited again
        // and its INSERT met idx_cl_source_id_unique, an HTTP 500 for a purchase already paid.
        assertThat(result.success()).isTrue();
        assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    @DisplayName("a per-purchase key held by ANOTHER payer is not a replay: never a free purchase")
    void marketplacePurchaseKeyOfAnotherPayerIsNotAReplay() {
        activeSubscription(new BigDecimal("100"));
        when(ledgerRepository.findFirstBySourceId(PURCHASE_KEY))
                .thenReturn(Optional.of(row(99L, "MARKETPLACE_PURCHASE", PURCHASE_KEY)));

        CreditConsumeResult result = metered.consumeForMarketplacePurchase(USER_ID, PURCHASE_KEY, 10);

        // Charged and handed to the unique index, which refuses it: never waved through.
        assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.TEN);
        verify(ledgerRepository).save(any());
    }

    @Test
    @DisplayName("a purchase keyed on the bare publication id (the pre-fix shape) is charged, never waved through as a replay")
    void marketplacePurchaseOnABarePublicationIdIsNeverAReplay() {
        activeSubscription(new BigDecimal("100"));
        when(ledgerRepository.findFirstBySourceId("pub-1"))
                .thenReturn(Optional.of(row(USER_ID, "MARKETPLACE_PURCHASE", "pub-1")));

        CreditConsumeResult result = metered.consumeForMarketplacePurchase(USER_ID, "pub-1", 10);

        // That key names the publication, not the purchase, so "same key, same buyer" can be a
        // genuine second purchase (another workspace): the gate stays out of it and the unique
        // index decides. Reachable only from a caller still on the old key during a rollout.
        assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.TEN);
        verify(ledgerRepository).save(any());
    }

    @Test
    @DisplayName("the rejection key is deterministic, fits the 512-char column, and is null for a keyless call")
    void rejectionKeyShape() {
        assertThat(CreditRejectionAuditWriter.rejectionSourceId("abc")).isEqualTo("abc:rejected");
        assertThat(CreditRejectionAuditWriter.rejectionSourceId(null)).isNull();
        // A blank key is no key: ":rejected" would be one key shared by every payer.
        assertThat(CreditRejectionAuditWriter.rejectionSourceId("")).isNull();
        assertThat(CreditRejectionAuditWriter.rejectionSourceId("  ")).isNull();

        String huge = "x".repeat(600);
        String key = CreditRejectionAuditWriter.rejectionSourceId(huge);
        assertThat(key).hasSizeLessThanOrEqualTo(512).endsWith(":rejected");
        assertThat(CreditRejectionAuditWriter.rejectionSourceId(huge)).isEqualTo(key);
    }

    @Test
    @DisplayName("two long ids sharing a 512-char prefix, and a long id vs its :legacy fallback, get distinct rejection keys")
    void longKeysNeverCollide() {
        String prefix = "y".repeat(520);
        String a = CreditRejectionAuditWriter.rejectionSourceId(prefix + "-a");
        String b = CreditRejectionAuditWriter.rejectionSourceId(prefix + "-b");
        String legacy = CreditRejectionAuditWriter.rejectionSourceId(prefix + "-a" + ":legacy");

        assertThat(a).isNotEqualTo(b).isNotEqualTo(legacy);
        assertThat(java.util.List.of(a, b, legacy)).allSatisfy(k -> assertThat(k).hasSizeLessThanOrEqualTo(512));
    }

    @Test
    @DisplayName("regression: a BLANK key (what the client sends for 'no key') is never answered as a replay: the turn is charged")
    void blankKeyIsNeverAnIdempotentReplay() {
        activeSubscription(new BigDecimal("100"));
        when(pricingService.calculateCost("p", "m", USAGE)).thenReturn(new BigDecimal("5"));
        // An earlier keyless consumption of the same payer and type already sits on "".
        lenient().when(ledgerRepository.findFirstBySourceId(""))
                .thenReturn(Optional.of(row(USER_ID, "AGENT_EXECUTION", "")));

        CreditConsumeResult result = metered.consumeForAgent(USER_ID, "", "p", "m", USAGE, "AGENT_EXECUTION");

        assertThat(result.success()).isTrue();
        assertThat(result.creditsUsed()).isEqualByComparingTo("5");
        verify(ledgerRepository).save(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getAmount()).isEqualByComparingTo("-5");
    }

    @Test
    @DisplayName("regression: a blank key on a flat-cost path (web search) is not read as 'already recorded'")
    void blankKeyIsNotAlreadyRecordedOnFlatCostPaths() {
        activeSubscription(new BigDecimal("100"));
        lenient().when(ledgerRepository.existsNonRejectionBySourceId("")).thenReturn(true);
        lenient().when(ledgerRepository.existsNonRejectionBySourceId(" ")).thenReturn(true);

        CreditConsumeResult blank = metered.consumeForWebSearch(USER_ID, "");
        CreditConsumeResult whitespace = metered.consumeForWebSearch(USER_ID, " ");

        assertThat(blank.creditsUsed()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(whitespace.creditsUsed()).isEqualByComparingTo(BigDecimal.ONE);
        verify(ledgerRepository, never()).existsNonRejectionBySourceId("");
    }
}
