package com.apimarketplace.agent.service.budget;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.repository.AgentRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("BudgetResolver")
@ExtendWith(MockitoExtension.class)
class BudgetResolverTest {

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private EntityManager entityManager;

    private BudgetResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new BudgetResolver(agentRepository, entityManager);
    }

    private AgentEntity buildAgent(String mode, BigDecimal budget, BigDecimal consumed, Instant lastReset) {
        AgentEntity entity = new AgentEntity();
        entity.setId(UUID.randomUUID());
        entity.setBudgetResetMode(mode);
        entity.setCreditBudget(budget);
        entity.setCreditsConsumed(consumed);
        entity.setBudgetLastReset(lastReset);
        return entity;
    }

    /** Stub the reset query as succeeding (return 1). */
    private void stubResetSucceeds(AgentEntity agent) {
        when(agentRepository.resetConsumedIfUnreservedAndUnchanged(
                eq(agent.getId()), any(Instant.class), eq(agent.getBudgetLastReset())))
            .thenReturn(1);
    }

    @Test
    @DisplayName("returns disabled state when entity is null")
    void nullEntity() {
        BudgetState state = resolver.resolveAndPersist(null, Instant.now());
        assertThat(state.isEnabled()).isFalse();
        verify(agentRepository, never()).save(any());
    }

    @Test
    @DisplayName("returns disabled state when creditBudget is null")
    void nullBudget() {
        AgentEntity agent = buildAgent("cumulative", null, BigDecimal.ZERO, null);
        BudgetState state = resolver.resolveAndPersist(agent, Instant.now());
        assertThat(state.isEnabled()).isFalse();
        verify(agentRepository, never()).save(any());
    }

    @Test
    @DisplayName("returns disabled state when creditBudget is zero")
    void zeroBudget() {
        AgentEntity agent = buildAgent("cumulative", BigDecimal.ZERO, BigDecimal.ZERO, null);
        BudgetState state = resolver.resolveAndPersist(agent, Instant.now());
        assertThat(state.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("loads org-scoped agent inside the transactional resolver before resolving budget")
    void resolveAndPersistForAgentUsesStrictOrganizationLookup() {
        UUID agentId = UUID.randomUUID();
        Instant now = Instant.parse("2026-05-22T09:00:00Z");
        AgentEntity agent = buildAgent("cumulative", new BigDecimal("10"), new BigDecimal("2.50"), null);
        agent.setId(agentId);
        when(agentRepository.findByIdAndOrganizationIdStrict(agentId, "org-1"))
            .thenReturn(Optional.of(agent));

        BudgetState state = resolver.resolveAndPersistForAgent(agentId, "org-1", now);

        assertThat(state.isEnabled()).isTrue();
        assertThat(state.consumedAfterReset()).isEqualByComparingTo("2.50");
        verify(agentRepository).findByIdAndOrganizationIdStrict(agentId, "org-1");
        verify(agentRepository, never()).findById(agentId);
    }

    @Test
    @DisplayName("agent lookup resolver is transactional to avoid PostgreSQL LOB auto-commit failures")
    void resolveAndPersistForAgentIsTransactional() throws NoSuchMethodException {
        Method method = BudgetResolver.class.getMethod(
            "resolveAndPersistForAgent", UUID.class, String.class, Instant.class);

        assertThat(method.getAnnotation(Transactional.class)).isNotNull();
    }

    @Nested
    @DisplayName("cumulative mode")
    class CumulativeMode {

        @Test
        @DisplayName("never resets, returns historical consumption")
        void neverResets() {
            Instant lastReset = Instant.parse("2020-01-01T00:00:00Z"); // 5 years ago
            AgentEntity agent = buildAgent("cumulative", new BigDecimal("10"), new BigDecimal("4.50"), lastReset);

            BudgetState state = resolver.resolveAndPersist(agent, Instant.now());

            assertThat(state.isEnabled()).isTrue();
            assertThat(state.totalBudget()).isEqualByComparingTo("10");
            assertThat(state.consumedAfterReset()).isEqualByComparingTo("4.50");
            assertThat(state.wasReset()).isFalse();
            verify(agentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("weekly mode")
    class WeeklyMode {

        @Test
        @DisplayName("resets via targeted CAS UPDATE when last reset was more than 7 days ago")
        void resetsAfterSevenDays() {
            Instant now = Instant.parse("2026-04-15T12:00:00Z");
            Instant lastReset = now.minusSeconds(8 * 24 * 60 * 60); // 8 days ago
            AgentEntity agent = buildAgent("weekly", new BigDecimal("10"), new BigDecimal("9.50"), lastReset);
            stubResetSucceeds(agent);

            BudgetState state = resolver.resolveAndPersist(agent, now);

            assertThat(state.wasReset()).isTrue();
            assertThat(state.consumedAfterReset()).isEqualByComparingTo("0");
            verify(agentRepository, times(1)).resetConsumedIfUnreservedAndUnchanged(
                eq(agent.getId()), eq(now), eq(lastReset));
            verify(entityManager, times(1)).detach(agent);
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("does not reset within the 7-day window")
        void doesNotResetWithinWindow() {
            Instant now = Instant.parse("2026-04-15T12:00:00Z");
            Instant lastReset = now.minusSeconds(3 * 24 * 60 * 60); // 3 days ago
            AgentEntity agent = buildAgent("weekly", new BigDecimal("10"), new BigDecimal("4.50"), lastReset);

            BudgetState state = resolver.resolveAndPersist(agent, now);

            assertThat(state.wasReset()).isFalse();
            assertThat(state.consumedAfterReset()).isEqualByComparingTo("4.50");
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("resets via first-reset variant when budgetLastReset is null (avoids Postgres 42P18)")
        void resetsWhenLastResetIsNull() {
            Instant now = Instant.parse("2026-04-15T12:00:00Z");
            AgentEntity agent = buildAgent("weekly", new BigDecimal("10"), new BigDecimal("4.50"), null);
            when(agentRepository.resetConsumedIfFirstReset(
                    eq(agent.getId()), any(Instant.class)))
                .thenReturn(1);

            BudgetState state = resolver.resolveAndPersist(agent, now);

            assertThat(state.wasReset()).isTrue();
            verify(agentRepository, times(1)).resetConsumedIfFirstReset(
                eq(agent.getId()), eq(now));
            verify(agentRepository, never()).resetConsumedIfUnreservedAndUnchanged(
                any(), any(), any());
            verify(entityManager, times(1)).detach(agent);
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("falls through to fresh re-read when CAS UPDATE loses (reservation held or concurrent reset)")
        void casLostRereadsFreshConsumption() {
            Instant now = Instant.parse("2026-04-15T12:00:00Z");
            Instant lastReset = now.minusSeconds(8 * 24 * 60 * 60);
            AgentEntity agent = buildAgent("weekly", new BigDecimal("10"), new BigDecimal("9.50"), lastReset);

            // CAS lost (returned 0) - either credits_reserved > 0 or another thread won the race.
            when(agentRepository.resetConsumedIfUnreservedAndUnchanged(
                    eq(agent.getId()), any(Instant.class), eq(lastReset)))
                .thenReturn(0);
            // Fresh re-read returns a different consumed value (e.g., another thread reset to 0
            // and a sibling call has already consumed 1.25).
            AgentEntity fresh = buildAgent("weekly", new BigDecimal("10"), new BigDecimal("1.25"), now);
            fresh.setId(agent.getId());
            when(agentRepository.findById(agent.getId())).thenReturn(Optional.of(fresh));

            BudgetState state = resolver.resolveAndPersist(agent, now);

            assertThat(state.wasReset()).isFalse();
            assertThat(state.totalBudget()).isEqualByComparingTo("10");
            assertThat(state.consumedAfterReset()).isEqualByComparingTo("1.25");
            verify(entityManager, times(1)).detach(agent);
            verify(agentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("monthly mode")
    class MonthlyMode {

        @Test
        @DisplayName("resets via targeted CAS UPDATE when current month differs from last reset month")
        void resetsAcrossMonthBoundary() {
            Instant lastReset = LocalDateTime.of(2026, 3, 28, 10, 0).toInstant(ZoneOffset.UTC);
            Instant now = LocalDateTime.of(2026, 4, 1, 10, 0).toInstant(ZoneOffset.UTC);
            AgentEntity agent = buildAgent("monthly", new BigDecimal("100"), new BigDecimal("80"), lastReset);
            stubResetSucceeds(agent);

            BudgetState state = resolver.resolveAndPersist(agent, now);

            assertThat(state.wasReset()).isTrue();
            assertThat(state.consumedAfterReset()).isEqualByComparingTo("0");
            verify(agentRepository, times(1)).resetConsumedIfUnreservedAndUnchanged(
                eq(agent.getId()), eq(now), eq(lastReset));
            verify(entityManager, times(1)).detach(agent);
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("does not reset within the same calendar month")
        void doesNotResetSameMonth() {
            Instant lastReset = LocalDateTime.of(2026, 4, 1, 10, 0).toInstant(ZoneOffset.UTC);
            Instant now = LocalDateTime.of(2026, 4, 28, 10, 0).toInstant(ZoneOffset.UTC);
            AgentEntity agent = buildAgent("monthly", new BigDecimal("100"), new BigDecimal("80"), lastReset);

            BudgetState state = resolver.resolveAndPersist(agent, now);

            assertThat(state.wasReset()).isFalse();
            assertThat(state.consumedAfterReset()).isEqualByComparingTo("80");
        }

        @Test
        @DisplayName("resets via first-reset variant when budgetLastReset is null (avoids Postgres 42P18)")
        void resetsWhenLastResetIsNull() {
            Instant now = LocalDateTime.of(2026, 4, 15, 10, 0).toInstant(ZoneOffset.UTC);
            AgentEntity agent = buildAgent("monthly", new BigDecimal("100"), new BigDecimal("80"), null);
            when(agentRepository.resetConsumedIfFirstReset(
                    eq(agent.getId()), any(Instant.class)))
                .thenReturn(1);

            BudgetState state = resolver.resolveAndPersist(agent, now);

            assertThat(state.wasReset()).isTrue();
            verify(agentRepository, times(1)).resetConsumedIfFirstReset(
                eq(agent.getId()), eq(now));
            verify(agentRepository, never()).resetConsumedIfUnreservedAndUnchanged(
                any(), any(), any());
            verify(entityManager, times(1)).detach(agent);
            verify(agentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("unknown mode")
    class UnknownMode {

        @Test
        @DisplayName("treats unknown mode as cumulative (never resets)")
        void unknownDefaultsToCumulative() {
            Instant lastReset = Instant.parse("2020-01-01T00:00:00Z");
            AgentEntity agent = buildAgent("rolling-fortnight", new BigDecimal("10"), new BigDecimal("9"), lastReset);

            BudgetState state = resolver.resolveAndPersist(agent, Instant.now());

            assertThat(state.wasReset()).isFalse();
            assertThat(state.consumedAfterReset()).isEqualByComparingTo("9");
        }
    }
}
