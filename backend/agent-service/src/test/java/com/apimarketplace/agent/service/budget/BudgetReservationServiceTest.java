package com.apimarketplace.agent.service.budget;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("BudgetReservationService")
@ExtendWith(MockitoExtension.class)
class BudgetReservationServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    private BudgetReservationService service;

    @BeforeEach
    void setUp() {
        service = new BudgetReservationService(jdbc);
    }

    @Nested
    @DisplayName("tryReserveChain")
    class TryReserveChain {

        @Test
        @DisplayName("no-op when chain is null")
        void nullChain() {
            service.tryReserveChain(null, BigDecimal.TEN);
            verifyNoInteractions(jdbc);
        }

        @Test
        @DisplayName("no-op when chain is empty")
        void emptyChain() {
            service.tryReserveChain(List.of(), BigDecimal.TEN);
            verifyNoInteractions(jdbc);
        }

        @Test
        @DisplayName("no-op when amount is null")
        void nullAmount() {
            service.tryReserveChain(List.of(UUID.randomUUID()), null);
            verifyNoInteractions(jdbc);
        }

        @Test
        @DisplayName("no-op when amount is zero")
        void zeroAmount() {
            service.tryReserveChain(List.of(UUID.randomUUID()), BigDecimal.ZERO);
            verifyNoInteractions(jdbc);
        }

        @Test
        @DisplayName("no-op when amount is negative")
        void negativeAmount() {
            service.tryReserveChain(List.of(UUID.randomUUID()), new BigDecimal("-5"));
            verifyNoInteractions(jdbc);
        }

        @Test
        @DisplayName("succeeds when every ancestor has sufficient free budget")
        void succeedsWhenChainCoversAmount() {
            UUID parent = UUID.randomUUID();
            UUID grandparent = UUID.randomUUID();
            when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

            assertThatCode(() -> service.tryReserveChain(List.of(parent, grandparent), new BigDecimal("30")))
                .doesNotThrowAnyException();

            verify(jdbc, times(2)).update(contains("UPDATE agent.agents"),
                eq(new BigDecimal("30")), any(UUID.class), eq(new BigDecimal("30")));
        }

        @Test
        @DisplayName("throws InsufficientBudgetException on first ancestor that refuses")
        void throwsOnFirstRefusal() {
            UUID parent = UUID.randomUUID();
            UUID grandparent = UUID.randomUUID();
            // parent succeeds, grandparent refuses
            when(jdbc.update(anyString(), any(Object[].class)))
                .thenReturn(1)
                .thenReturn(0);

            assertThatExceptionOfType(InsufficientBudgetException.class)
                .isThrownBy(() -> service.tryReserveChain(List.of(parent, grandparent), new BigDecimal("30")))
                .satisfies(ex -> {
                    assertThat(ex.getAgentId()).isEqualTo(grandparent);
                    assertThat(ex.getRequested()).isEqualByComparingTo("30");
                });
        }

        @Test
        @DisplayName("throws immediately when the first ancestor refuses (no second update)")
        void throwsImmediatelyOnFirstAncestor() {
            UUID parent = UUID.randomUUID();
            UUID grandparent = UUID.randomUUID();
            when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

            assertThatExceptionOfType(InsufficientBudgetException.class)
                .isThrownBy(() -> service.tryReserveChain(List.of(parent, grandparent), new BigDecimal("30")))
                .satisfies(ex -> assertThat(ex.getAgentId()).isEqualTo(parent));

            // Only one UPDATE attempted - short-circuit on first failure
            verify(jdbc, times(1)).update(anyString(), any(Object[].class));
        }
    }

    @Nested
    @DisplayName("tryReserveOne (package-private)")
    class TryReserveOne {

        @Test
        @DisplayName("returns true when update affects one row")
        void returnsTrueOnUpdate() {
            UUID agentId = UUID.randomUUID();
            when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

            assertThat(service.tryReserveOne(agentId, new BigDecimal("10"))).isTrue();
        }

        @Test
        @DisplayName("returns false when update affects zero rows (insufficient free)")
        void returnsFalseWhenRowUnmatched() {
            UUID agentId = UUID.randomUUID();
            when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

            assertThat(service.tryReserveOne(agentId, new BigDecimal("10"))).isFalse();
        }

        @Test
        @DisplayName("WHERE clause includes free ≥ amount predicate and NULL credit_budget branch")
        void sqlShapeIsCorrect() {
            UUID agentId = UUID.randomUUID();
            when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

            service.tryReserveOne(agentId, new BigDecimal("10"));

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbc).update(sqlCaptor.capture(), any(Object[].class));
            String sql = sqlCaptor.getValue();
            assertThat(sql).contains("credit_budget IS NULL");
            assertThat(sql).contains("credit_budget - credits_consumed - credits_reserved");
            assertThat(sql).contains(">=");
        }
    }

    @Nested
    @DisplayName("settleReservationChain")
    class SettleReservationChain {

        @Test
        @DisplayName("no-op on empty chain")
        void noOpOnEmptyChain() {
            service.settleReservationChain(List.of(), new BigDecimal("30"), new BigDecimal("11"));
            verifyNoInteractions(jdbc);
        }

        @Test
        @DisplayName("no-op on null chain")
        void noOpOnNullChain() {
            service.settleReservationChain(null, new BigDecimal("30"), new BigDecimal("11"));
            verifyNoInteractions(jdbc);
        }

        @Test
        @DisplayName("updates every ancestor in the chain and bumps consumed_from_subagents by actual")
        void updatesEveryAncestor() {
            UUID parent = UUID.randomUUID();
            UUID grandparent = UUID.randomUUID();
            when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

            service.settleReservationChain(List.of(parent, grandparent),
                new BigDecimal("30"), new BigDecimal("11"));

            // SQL bumps THREE columns in one UPDATE: credits_reserved (GREATEST(-reserved,0)),
            // credits_consumed (+actual), and credits_consumed_from_subagents (+actual - every
            // ancestor is by definition a (transitive) parent of the settling execution, so
            // from their perspective this spend IS "from a sub-agent"). The third bind keeps
            // the invariant consumed_from_subagents <= consumed tight by construction.
            verify(jdbc, times(2)).update(contains("credits_consumed_from_subagents = credits_consumed_from_subagents + ?"),
                eq(new BigDecimal("30")), eq(new BigDecimal("11")), eq(new BigDecimal("11")), any(UUID.class));
        }

        @Test
        @DisplayName("zero-cost settle (actual = 0) refunds the full reservation and leaves cascade bucket untouched")
        void zeroCostSettleRefundsAll() {
            UUID parent = UUID.randomUUID();
            when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

            service.settleReservationChain(List.of(parent), new BigDecimal("30"), BigDecimal.ZERO);

            // Third bind is also 0, so consumed_from_subagents stays put - consistent with
            // the existing consumed += 0 behavior.
            verify(jdbc).update(anyString(),
                eq(new BigDecimal("30")), eq(BigDecimal.ZERO), eq(BigDecimal.ZERO), eq(parent));
        }

        @Test
        @DisplayName("null reserved and actual default to zero (safe no-op settle)")
        void nullsDefaultToZero() {
            UUID parent = UUID.randomUUID();
            when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

            service.settleReservationChain(List.of(parent), null, null);

            verify(jdbc).update(anyString(),
                eq(BigDecimal.ZERO), eq(BigDecimal.ZERO), eq(BigDecimal.ZERO), eq(parent));
        }
    }

    @Nested
    @DisplayName("getMinFreeAcrossChain")
    class GetMinFreeAcrossChain {

        @Test
        @DisplayName("returns null on empty chain")
        void returnsNullOnEmptyChain() {
            assertThat(service.getMinFreeAcrossChain(List.of())).isNull();
            verifyNoInteractions(jdbc);
        }

        @Test
        @DisplayName("returns null on null chain")
        void returnsNullOnNullChain() {
            assertThat(service.getMinFreeAcrossChain(null)).isNull();
            verifyNoInteractions(jdbc);
        }
    }

    @Nested
    @DisplayName("clearAllReservations")
    class ClearAllReservations {

        @Test
        @DisplayName("executes single UPDATE zeroing all positive credits_reserved rows")
        void executesSingleUpdate() {
            when(jdbc.update(anyString())).thenReturn(7);

            int cleared = service.clearAllReservations();

            assertThat(cleared).isEqualTo(7);
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbc).update(sqlCaptor.capture());
            String sql = sqlCaptor.getValue();
            assertThat(sql).contains("credits_reserved = 0");
            assertThat(sql).contains("credits_reserved > 0");
        }

        @Test
        @DisplayName("returns zero when no rows matched")
        void returnsZeroOnNoMatches() {
            when(jdbc.update(anyString())).thenReturn(0);

            assertThat(service.clearAllReservations()).isZero();
        }
    }
}
