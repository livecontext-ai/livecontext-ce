package com.apimarketplace.agent.service.cloud;

import com.apimarketplace.agent.service.cloud.CeRelaySettlementService.SettleOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CeRelayReaperService")
class CeRelayReaperServiceTest {

    private static final long NOW = 10_000_000L;
    private static final long GRACE_SECONDS = 1800L;
    private static final long CUTOFF = NOW - GRACE_SECONDS * 1000L; // 8_200_000

    @Mock private CeRelayAccrualStore accrualStore;
    @Mock private CeRelaySettlementService settlementService;

    private CeRelayReaperService reaper;

    @BeforeEach
    void setUp() {
        reaper = new CeRelayReaperService(accrualStore, settlementService);
        ReflectionTestUtils.setField(reaper, "graceSeconds", GRACE_SECONDS);
        ReflectionTestUtils.setField(reaper, "batchSize", 200);
    }

    @Test
    @DisplayName("settles each abandoned execution found before the grace cutoff, counts only billed")
    void settlesStaleExecutionsAndCountsBilled() {
        when(accrualStore.findStale(CUTOFF, 200)).thenReturn(List.of("a", "b", "c"));
        when(settlementService.settleFromAccrual("a")).thenReturn(SettleOutcome.BILLED);
        when(settlementService.settleFromAccrual("b")).thenReturn(SettleOutcome.NOTHING_TO_BILL);
        when(settlementService.settleFromAccrual("c")).thenReturn(SettleOutcome.RETRY);

        int billed = reaper.reapOnce(NOW);

        // Cutoff math (now - grace) is pinned by the findStale stub.
        verify(accrualStore).findStale(CUTOFF, 200);
        verify(settlementService).settleFromAccrual("a");
        verify(settlementService).settleFromAccrual("b");
        verify(settlementService).settleFromAccrual("c");
        assertThat(billed).isEqualTo(1); // only "a" produced a ledger line
    }

    @Test
    @DisplayName("does nothing when no abandoned executions are found")
    void noopWhenNothingStale() {
        when(accrualStore.findStale(CUTOFF, 200)).thenReturn(List.of());

        int billed = reaper.reapOnce(NOW);

        assertThat(billed).isZero();
        verify(settlementService, never()).settleFromAccrual(org.mockito.ArgumentMatchers.any());
    }
}
