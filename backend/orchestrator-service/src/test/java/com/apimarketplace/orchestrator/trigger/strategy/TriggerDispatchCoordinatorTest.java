package com.apimarketplace.orchestrator.trigger.strategy;

import com.apimarketplace.orchestrator.trigger.ProductionRunResolver;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TriggerDispatchCoordinator} - the round-7 PR4 router that
 * registers per-trigger strategies, resolves verdicts via {@link ProductionRunResolver},
 * and emits the unified {@code trigger_dispatch_total} Prometheus counter.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TriggerDispatchCoordinator - PR4 strategy routing + observability")
class TriggerDispatchCoordinatorTest {

    @Mock private ProductionRunResolver resolver;

    private MeterRegistry meterRegistry;
    private TriggerDispatchCoordinator coordinator;

    private static final UUID WORKFLOW_ID = UUID.randomUUID();

    private TriggerExecutionStrategy schedule = new ScheduleExecutionStrategy();
    private TriggerExecutionStrategy webhook = new WebhookExecutionStrategy();
    private TriggerExecutionStrategy form = new FormExecutionStrategy();
    private TriggerExecutionStrategy chat = new ChatExecutionStrategy();
    private TriggerExecutionStrategy chain = new WorkflowChainExecutionStrategy();

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        coordinator = new TriggerDispatchCoordinator(
            List.of(schedule, webhook, form, chat, chain),
            resolver, meterRegistry);
    }

    private ProductionRunResolver.Resolution resolution(ProductionRunResolver.Outcome outcome) {
        return new ProductionRunResolver.Resolution(Optional.empty(), outcome, "test-wf");
    }

    @Nested
    @DisplayName("Routing")
    class RoutingTests {

        @Test
        @DisplayName("Schedule strategy uses LATEST_WAITING_TRIGGER policy")
        void schedulePolicy() {
            assertThat(coordinator.policyFor(TriggerType.SCHEDULE))
                .isEqualTo(ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER);
        }

        @Test
        @DisplayName("Webhook/Form/Chat/Workflow strategies use BY_PRODUCTION_RUN_ID policy")
        void otherPolicies() {
            assertThat(coordinator.policyFor(TriggerType.WEBHOOK))
                .isEqualTo(ProductionRunResolver.RunSelectionPolicy.BY_PRODUCTION_RUN_ID);
            assertThat(coordinator.policyFor(TriggerType.FORM))
                .isEqualTo(ProductionRunResolver.RunSelectionPolicy.BY_PRODUCTION_RUN_ID);
            assertThat(coordinator.policyFor(TriggerType.CHAT))
                .isEqualTo(ProductionRunResolver.RunSelectionPolicy.BY_PRODUCTION_RUN_ID);
            assertThat(coordinator.policyFor(TriggerType.WORKFLOW))
                .isEqualTo(ProductionRunResolver.RunSelectionPolicy.BY_PRODUCTION_RUN_ID);
        }

        @Test
        @DisplayName("Unregistered trigger type → safe LATEST_TRUSTED fallback for policy")
        void unregisteredPolicyFallback() {
            assertThat(coordinator.policyFor(TriggerType.MANUAL))
                .isEqualTo(ProductionRunResolver.RunSelectionPolicy.LATEST_TRUSTED);
        }

        @Test
        @DisplayName("Two strategies for the same TriggerType → constructor throws")
        void duplicateStrategyRejected() {
            ScheduleExecutionStrategy s1 = new ScheduleExecutionStrategy();
            ScheduleExecutionStrategy s2 = new ScheduleExecutionStrategy();
            assertThatThrownBy(() -> new TriggerDispatchCoordinator(
                    List.of(s1, s2), resolver, meterRegistry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Two strategies registered for SCHEDULE");
        }
    }

    @Nested
    @DisplayName("Verdict resolution")
    class VerdictTests {

        @Test
        @DisplayName("FOUND outcome → FIRE verdict")
        void foundIsFire() {
            when(resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER))
                .thenReturn(resolution(ProductionRunResolver.Outcome.FOUND));

            DispatchVerdict v = coordinator.resolveVerdict(TriggerType.SCHEDULE, WORKFLOW_ID);

            assertThat(v).isEqualTo(DispatchVerdict.FIRE);
        }

        @Test
        @DisplayName("NOT_PINNED outcome → REFUSE_NOT_PINNED verdict")
        void notPinnedMaps() {
            when(resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.BY_PRODUCTION_RUN_ID))
                .thenReturn(resolution(ProductionRunResolver.Outcome.NOT_PINNED));

            DispatchVerdict v = coordinator.resolveVerdict(TriggerType.WEBHOOK, WORKFLOW_ID);

            assertThat(v).isEqualTo(DispatchVerdict.REFUSE_NOT_PINNED);
        }

        @Test
        @DisplayName("NO_PRODUCTION_RUN → REFUSE_RUN_MISSING")
        void noProdRunMaps() {
            when(resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.BY_PRODUCTION_RUN_ID))
                .thenReturn(resolution(ProductionRunResolver.Outcome.NO_PRODUCTION_RUN));

            assertThat(coordinator.resolveVerdict(TriggerType.FORM, WORKFLOW_ID))
                .isEqualTo(DispatchVerdict.REFUSE_RUN_MISSING);
        }

        @Test
        @DisplayName("WORKFLOW_MISSING → REFUSE_WORKFLOW_MISSING")
        void workflowMissingMaps() {
            when(resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.BY_PRODUCTION_RUN_ID))
                .thenReturn(resolution(ProductionRunResolver.Outcome.WORKFLOW_MISSING));

            assertThat(coordinator.resolveVerdict(TriggerType.CHAT, WORKFLOW_ID))
                .isEqualTo(DispatchVerdict.REFUSE_WORKFLOW_MISSING);
        }

        @Test
        @DisplayName("Unregistered trigger type → REFUSE_NO_TRIGGER")
        void unregisteredTypeReturnsNoTrigger() {
            assertThat(coordinator.resolveVerdict(TriggerType.MANUAL, WORKFLOW_ID))
                .isEqualTo(DispatchVerdict.REFUSE_NO_TRIGGER);
        }
    }

    @Nested
    @DisplayName("Observability - Prometheus counter")
    class ObservabilityTests {

        @Test
        @DisplayName("recordVerdict increments trigger_dispatch_total{trigger_type, verdict}")
        void counterIncrement() {
            coordinator.recordVerdict(TriggerType.SCHEDULE, DispatchVerdict.FIRE);
            coordinator.recordVerdict(TriggerType.SCHEDULE, DispatchVerdict.FIRE);
            coordinator.recordVerdict(TriggerType.SCHEDULE, DispatchVerdict.REFUSE_RUN_MISSING);

            double fires = meterRegistry.counter("trigger_dispatch_total",
                "trigger_type", "schedule", "verdict", "FIRE").count();
            double refuses = meterRegistry.counter("trigger_dispatch_total",
                "trigger_type", "schedule", "verdict", "REFUSE_RUN_MISSING").count();
            assertThat(fires).isEqualTo(2.0);
            assertThat(refuses).isEqualTo(1.0);
        }

        @Test
        @DisplayName("resolveVerdict path also records the counter")
        void resolveAlsoRecords() {
            when(resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER))
                .thenReturn(resolution(ProductionRunResolver.Outcome.FOUND));

            coordinator.resolveVerdict(TriggerType.SCHEDULE, WORKFLOW_ID);

            assertThat(meterRegistry.counter("trigger_dispatch_total",
                "trigger_type", "schedule", "verdict", "FIRE").count())
                .isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Strategy declarations")
    class StrategyShapeTests {

        @Test
        @DisplayName("Each strategy declares its TriggerType correctly")
        void strategyTypes() {
            assertThat(schedule.triggerType()).isEqualTo(TriggerType.SCHEDULE);
            assertThat(webhook.triggerType()).isEqualTo(TriggerType.WEBHOOK);
            assertThat(form.triggerType()).isEqualTo(TriggerType.FORM);
            assertThat(chat.triggerType()).isEqualTo(TriggerType.CHAT);
            assertThat(chain.triggerType()).isEqualTo(TriggerType.WORKFLOW);
        }

        @Test
        @DisplayName("defaultVerdictFor covers all 4 outcomes")
        void defaultMapping() {
            assertThat(TriggerExecutionStrategy.defaultVerdictFor(
                ProductionRunResolver.Outcome.FOUND)).isEqualTo(DispatchVerdict.FIRE);
            assertThat(TriggerExecutionStrategy.defaultVerdictFor(
                ProductionRunResolver.Outcome.NOT_PINNED)).isEqualTo(DispatchVerdict.REFUSE_NOT_PINNED);
            assertThat(TriggerExecutionStrategy.defaultVerdictFor(
                ProductionRunResolver.Outcome.NO_PRODUCTION_RUN)).isEqualTo(DispatchVerdict.REFUSE_RUN_MISSING);
            assertThat(TriggerExecutionStrategy.defaultVerdictFor(
                ProductionRunResolver.Outcome.WORKFLOW_MISSING)).isEqualTo(DispatchVerdict.REFUSE_WORKFLOW_MISSING);
        }
    }
}
