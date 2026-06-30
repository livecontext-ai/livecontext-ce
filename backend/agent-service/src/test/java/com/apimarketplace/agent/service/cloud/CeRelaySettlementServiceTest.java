package com.apimarketplace.agent.service.cloud;

import com.apimarketplace.agent.service.cloud.CeRelaySettlementService.SettleOutcome;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.credit.LlmCacheTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CeRelaySettlementService")
class CeRelaySettlementServiceTest {

    private static final String EID = "exec-1";

    @Mock private CeRelayAccrualStore accrualStore;
    @Mock private CreditConsumptionClient creditClient;

    private CeRelaySettlementService service;

    @BeforeEach
    void setUp() {
        service = new CeRelaySettlementService(accrualStore, creditClient);
    }

    private CeRelayAccrualStore.AccruedSnapshot snap(String userId, CeRelayAccrualStore.AccruedUsage usage) {
        return new CeRelayAccrualStore.AccruedSnapshot(
                userId, "google", "gemini-3-flash-preview", usage, 5000L);
    }

    @Test
    @DisplayName("bills the accrued total as ONE CE_LLM_RELAY line, then drops the accrual")
    void billsAccruedTotalThenDropsAccrual() {
        when(accrualStore.snapshot(EID)).thenReturn(Optional.of(
                snap("42", new CeRelayAccrualStore.AccruedUsage(8449, 111, 0, 3999, 0, 107))));
        when(creditClient.consumeCredits(eq("42"), eq("CE_LLM_RELAY"), eq(EID),
                eq("google"), eq("gemini-3-flash-preview"), eq(8449), eq(111),
                eq(new LlmCacheTokens(0, 3999, 0, 107))))
                .thenReturn(Map.of("success", true));

        SettleOutcome outcome = service.settleFromAccrual(EID);

        assertThat(outcome).isEqualTo(SettleOutcome.BILLED);
        verify(creditClient).consumeCredits(eq("42"), eq("CE_LLM_RELAY"), eq(EID),
                eq("google"), eq("gemini-3-flash-preview"), eq(8449), eq(111),
                eq(new LlmCacheTokens(0, 3999, 0, 107)));
        verify(accrualStore).remove(EID);
    }

    @Test
    @DisplayName("keeps the accrual (RETRY) when the debit hard-fails")
    void keepsAccrualOnDebitFailure() {
        when(accrualStore.snapshot(EID)).thenReturn(Optional.of(
                snap("42", new CeRelayAccrualStore.AccruedUsage(100, 50, 0, 0, 0, 0))));
        when(creditClient.consumeCredits(any(), any(), any(), any(), any(), any(), any(),
                any(LlmCacheTokens.class)))
                .thenReturn(Map.of("success", false, "error", "auth down"));

        SettleOutcome outcome = service.settleFromAccrual(EID);

        assertThat(outcome).isEqualTo(SettleOutcome.RETRY);
        verify(accrualStore, never()).remove(EID);
    }

    @Test
    @DisplayName("dead-letters and drops the accrual on a non-retryable failure (no subscription / 402)")
    void deadLettersNonRetryableFailure() {
        when(accrualStore.snapshot(EID)).thenReturn(Optional.of(
                snap("42", new CeRelayAccrualStore.AccruedUsage(100, 50, 0, 0, 0, 0))));
        when(creditClient.consumeCredits(any(), any(), any(), any(), any(), any(), any(),
                any(LlmCacheTokens.class)))
                .thenReturn(Map.of("success", false, "error", "402 Insufficient credits"));

        SettleOutcome outcome = service.settleFromAccrual(EID);

        // Permanent failure: reconcilable via dead-letter, accrual dropped, NOT retried forever.
        assertThat(outcome).isEqualTo(SettleOutcome.DEAD_LETTERED);
        verify(creditClient).persistRejection(eq("42"), eq("CE_LLM_RELAY"), eq(EID),
                eq("google"), eq("gemini-3-flash-preview"), eq(100), eq(50), anyString());
        verify(accrualStore).remove(EID);
    }

    @Test
    @DisplayName("treats a null credit-service response as retryable - never drops the accrual on ambiguity")
    void nullResponseIsRetryableNotBilled() {
        when(accrualStore.snapshot(EID)).thenReturn(Optional.of(
                snap("42", new CeRelayAccrualStore.AccruedUsage(100, 50, 0, 0, 0, 0))));
        when(creditClient.consumeCredits(any(), any(), any(), any(), any(), any(), any(),
                any(LlmCacheTokens.class)))
                .thenReturn(null);

        SettleOutcome outcome = service.settleFromAccrual(EID);

        assertThat(outcome).isEqualTo(SettleOutcome.RETRY);
        verify(accrualStore, never()).remove(EID);
        verify(creditClient, never()).persistRejection(any(), any(), any(), any(), any(),
                anyInt(), anyInt(), anyString());
    }

    @Test
    @DisplayName("idempotent no-op when the accrual is already gone (drops any dangling index)")
    void nothingToBillWhenAccrualAbsent() {
        when(accrualStore.snapshot(EID)).thenReturn(Optional.empty());

        SettleOutcome outcome = service.settleFromAccrual(EID);

        assertThat(outcome).isEqualTo(SettleOutcome.NOTHING_TO_BILL);
        verify(accrualStore).remove(EID);
        verify(creditClient, never()).consumeCredits(any(), any(), any(), any(), any(), any(), any(),
                any(LlmCacheTokens.class));
    }

    @Test
    @DisplayName("cleans up a zero-usage phantom without writing a ledger row")
    void cleansUpZeroUsagePhantom() {
        when(accrualStore.snapshot(EID)).thenReturn(Optional.of(
                snap("42", CeRelayAccrualStore.AccruedUsage.ZERO)));

        SettleOutcome outcome = service.settleFromAccrual(EID);

        assertThat(outcome).isEqualTo(SettleOutcome.NOTHING_TO_BILL);
        verify(accrualStore).remove(EID);
        verify(creditClient, never()).consumeCredits(any(), any(), any(), any(), any(), any(), any(),
                any(LlmCacheTokens.class));
    }

    @Test
    @DisplayName("leaves an accrual with no userId for inspection (cannot attribute the debit)")
    void leavesAccrualWithoutUserId() {
        when(accrualStore.snapshot(EID)).thenReturn(Optional.of(
                snap(null, new CeRelayAccrualStore.AccruedUsage(100, 50, 0, 0, 0, 0))));

        SettleOutcome outcome = service.settleFromAccrual(EID);

        assertThat(outcome).isEqualTo(SettleOutcome.RETRY);
        verify(accrualStore, never()).remove(EID);
        verify(creditClient, never()).consumeCredits(any(), any(), any(), any(), any(), any(), any(),
                any(LlmCacheTokens.class));
    }

    @Test
    @DisplayName("blank executionId is a no-op")
    void blankExecutionIdIsNoOp() {
        assertThat(service.settleFromAccrual("  ")).isEqualTo(SettleOutcome.NOTHING_TO_BILL);
        verify(accrualStore, never()).snapshot(any());
    }
}
