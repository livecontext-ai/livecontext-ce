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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The free-workflow-node hook in {@link CreditService#consumeForWorkflowNode}:
 * when the executor holds an active FREE_NODE_COUNTER benefit, the node is free
 * and a 0-cost {@code WORKFLOW_NODE_PROMO} ledger row is written (sourceType kept
 * byte-identical after the promo-to-reward rename). Replaces the deleted
 * CreditServicePromoNodeTest, now driven through {@link RewardService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreditService - reward free workflow node")
class CreditServiceRewardNodeTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private CreditLedgerRepository ledgerRepository;
    @Mock private ModelPricingService pricingService;
    @Mock private RewardService rewardService;

    @Captor private ArgumentCaptor<CreditLedgerEntry> ledgerCaptor;

    private static final Long USER = 42L;
    private static final String SRC = "run-1:node:classify:0";

    private CreditService metered() {
        CreditService s = new CreditService(subscriptionRepository, ledgerRepository, pricingService, false);
        s.setRewardService(rewardService);
        return s;
    }

    private void mockBalance(String credits) {
        Subscription sub = new Subscription();
        sub.setId(1L);
        sub.setRemainingCredits(new BigDecimal(credits));
        lenient().when(subscriptionRepository.findActiveByUserId(USER)).thenReturn(Optional.of(sub));
        lenient().when(subscriptionRepository.findActiveByUserIdForUpdate(USER)).thenReturn(Optional.of(sub));
    }

    @BeforeEach
    void resetClaimToFalse() {
        lenient().when(rewardService.claimFreeWorkflowNode(USER)).thenReturn(false);
    }

    @Test
    @DisplayName("claim succeeds -> 0-cost WORKFLOW_NODE_PROMO row, wallet untouched")
    void freeWhenClaimed() {
        mockBalance("100");
        when(ledgerRepository.existsBySourceId(SRC)).thenReturn(false);
        when(rewardService.claimFreeWorkflowNode(USER)).thenReturn(true);

        CreditConsumeResult result = metered().consumeForWorkflowNode(USER, SRC);

        assertThat(result.success()).isTrue();
        assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);

        verify(ledgerRepository).save(ledgerCaptor.capture());
        CreditLedgerEntry e = ledgerCaptor.getValue();
        assertThat(e.getSourceType()).isEqualTo("WORKFLOW_NODE_PROMO");
        assertThat(e.getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(e.getSourceId()).isEqualTo(SRC);
        assertThat(e.getExecutorUserId()).isEqualTo(USER);
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("no claim -> normal 1-credit WORKFLOW_NODE debit")
    void debitWhenNoClaim() {
        mockBalance("100");
        when(ledgerRepository.existsBySourceId(SRC)).thenReturn(false);
        when(rewardService.claimFreeWorkflowNode(USER)).thenReturn(false);

        CreditConsumeResult result = metered().consumeForWorkflowNode(USER, SRC);

        assertThat(result.success()).isTrue();
        assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ONE);

        verify(ledgerRepository).save(ledgerCaptor.capture());
        CreditLedgerEntry e = ledgerCaptor.getValue();
        assertThat(e.getSourceType()).isEqualTo("WORKFLOW_NODE");
        assertThat(e.getAmount()).isEqualByComparingTo(BigDecimal.ONE.negate());
    }

    @Test
    @DisplayName("idempotent retry (sourceId already charged) never consults the reward benefit")
    void idempotentSkipsReward() {
        mockBalance("100");
        when(ledgerRepository.existsBySourceId(SRC)).thenReturn(true);

        CreditConsumeResult result = metered().consumeForWorkflowNode(USER, SRC);

        assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(rewardService, never()).claimFreeWorkflowNode(anyLong());
        verify(ledgerRepository, never()).save(any());
    }
}
