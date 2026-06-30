package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for TriggerTypeDetector.
 */
@DisplayName("TriggerTypeDetector")
class TriggerTypeDetectorTest {

    private TriggerTypeDetector detector;

    @BeforeEach
    void setUp() {
        detector = new TriggerTypeDetector();
    }

    private WorkflowPlan planWithTrigger(String type) {
        Trigger trigger = mock(Trigger.class);
        when(trigger.type()).thenReturn(type);
        WorkflowPlan plan = mock(WorkflowPlan.class);
        when(plan.getTriggers()).thenReturn(List.of(trigger));
        return plan;
    }

    @Nested
    @DisplayName("hasWebhookTrigger (WorkflowPlan)")
    class HasWebhookTriggerPlanTests {

        @Test
        @DisplayName("Should return true when plan has webhook trigger")
        void shouldReturnTrueForWebhookTrigger() {
            assertThat(detector.hasWebhookTrigger(planWithTrigger("webhook"))).isTrue();
        }

        @Test
        @DisplayName("Should return false when plan has non-webhook trigger")
        void shouldReturnFalseForNonWebhookTrigger() {
            assertThat(detector.hasWebhookTrigger(planWithTrigger("manual"))).isFalse();
        }

        @Test
        @DisplayName("Should return false for null plan")
        void shouldReturnFalseForNullPlan() {
            assertThat(detector.hasWebhookTrigger((WorkflowPlan) null)).isFalse();
        }

        @Test
        @DisplayName("Should return false when triggers list is null")
        void shouldReturnFalseWhenTriggersNull() {
            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getTriggers()).thenReturn(null);
            assertThat(detector.hasWebhookTrigger(plan)).isFalse();
        }
    }

    @Nested
    @DisplayName("hasWebhookTrigger (Map)")
    class HasWebhookTriggerMapTests {

        @Test
        @DisplayName("Should return true when map has webhook trigger")
        void shouldReturnTrueForWebhookTrigger() {
            Map<String, Object> planMap = Map.of(
                "triggers", List.of(Map.of("type", "webhook"))
            );
            assertThat(detector.hasWebhookTrigger(planMap)).isTrue();
        }

        @Test
        @DisplayName("Should return false when map has no webhook trigger")
        void shouldReturnFalseForNoWebhookTrigger() {
            Map<String, Object> planMap = Map.of(
                "triggers", List.of(Map.of("type", "manual"))
            );
            assertThat(detector.hasWebhookTrigger(planMap)).isFalse();
        }

        @Test
        @DisplayName("Should return false for null map")
        void shouldReturnFalseForNullMap() {
            assertThat(detector.hasWebhookTrigger((Map<String, Object>) null)).isFalse();
        }
    }

    @Nested
    @DisplayName("hasDatasourceTrigger")
    class HasDatasourceTriggerTests {

        @Test
        @DisplayName("Should return true when plan has datasource trigger")
        void shouldReturnTrueForDatasourceTrigger() {
            assertThat(detector.hasDatasourceTrigger(planWithTrigger("datasource"))).isTrue();
        }

        @Test
        @DisplayName("Should be case-insensitive")
        void shouldBeCaseInsensitive() {
            assertThat(detector.hasDatasourceTrigger(planWithTrigger("DataSource"))).isTrue();
        }

        @Test
        @DisplayName("Should return false for null plan")
        void shouldReturnFalseForNullPlan() {
            assertThat(detector.hasDatasourceTrigger((WorkflowPlan) null)).isFalse();
        }

        @Test
        @DisplayName("Map: Should return true when map has datasource trigger")
        void mapShouldReturnTrueForDatasourceTrigger() {
            Map<String, Object> planMap = Map.of(
                "triggers", List.of(Map.of("type", "datasource"))
            );
            assertThat(detector.hasDatasourceTrigger(planMap)).isTrue();
        }

        @Test
        @DisplayName("Map: Should return false for null map")
        void mapShouldReturnFalseForNullMap() {
            assertThat(detector.hasDatasourceTrigger((Map<String, Object>) null)).isFalse();
        }
    }

    @Nested
    @DisplayName("hasReusableTrigger")
    class HasReusableTriggerTests {

        @Test
        @DisplayName("Should return true for webhook trigger")
        void shouldReturnTrueForWebhook() {
            assertThat(detector.hasReusableTrigger(planWithTrigger("webhook"))).isTrue();
        }

        @Test
        @DisplayName("Should return true for manual trigger")
        void shouldReturnTrueForManual() {
            assertThat(detector.hasReusableTrigger(planWithTrigger("manual"))).isTrue();
        }

        @Test
        @DisplayName("Should return true for chat trigger")
        void shouldReturnTrueForChat() {
            assertThat(detector.hasReusableTrigger(planWithTrigger("chat"))).isTrue();
        }

        @Test
        @DisplayName("Should return false for null plan")
        void shouldReturnFalseForNullPlan() {
            assertThat(detector.hasReusableTrigger((WorkflowPlan) null)).isFalse();
        }

        @Test
        @DisplayName("Map: Should return true for reusable trigger")
        void mapShouldReturnTrueForReusableTrigger() {
            Map<String, Object> planMap = Map.of(
                "triggers", List.of(Map.of("type", "manual"))
            );
            assertThat(detector.hasReusableTrigger(planMap)).isTrue();
        }
    }

    @Nested
    @DisplayName("hasManualTrigger")
    class HasManualTriggerTests {

        @Test
        @DisplayName("Should return true for manual trigger")
        void shouldReturnTrue() {
            assertThat(detector.hasManualTrigger(planWithTrigger("manual"))).isTrue();
        }

        @Test
        @DisplayName("Should return false for non-manual trigger")
        void shouldReturnFalse() {
            assertThat(detector.hasManualTrigger(planWithTrigger("webhook"))).isFalse();
        }
    }

    @Nested
    @DisplayName("hasChatTrigger")
    class HasChatTriggerTests {

        @Test
        @DisplayName("Should return true for chat trigger")
        void shouldReturnTrue() {
            assertThat(detector.hasChatTrigger(planWithTrigger("chat"))).isTrue();
        }
    }

    @Nested
    @DisplayName("hasScheduleTrigger")
    class HasScheduleTriggerTests {

        @Test
        @DisplayName("Should return true for schedule trigger")
        void shouldReturnTrue() {
            assertThat(detector.hasScheduleTrigger(planWithTrigger("schedule"))).isTrue();
        }
    }

    @Nested
    @DisplayName("hasFormTrigger")
    class HasFormTriggerTests {

        @Test
        @DisplayName("Should return true for form trigger")
        void shouldReturnTrue() {
            assertThat(detector.hasFormTrigger(planWithTrigger("form"))).isTrue();
        }
    }

    @Nested
    @DisplayName("getFirstTriggerType")
    class GetFirstTriggerTypeTests {

        @Test
        @DisplayName("Should return type of first trigger")
        void shouldReturnFirstTriggerType() {
            assertThat(detector.getFirstTriggerType(planWithTrigger("webhook"))).isEqualTo("webhook");
        }

        @Test
        @DisplayName("Should return null for null plan")
        void shouldReturnNullForNullPlan() {
            assertThat(detector.getFirstTriggerType(null)).isNull();
        }

        @Test
        @DisplayName("Should return null when triggers list is empty")
        void shouldReturnNullWhenTriggersEmpty() {
            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getTriggers()).thenReturn(List.of());
            assertThat(detector.getFirstTriggerType(plan)).isNull();
        }

        @Test
        @DisplayName("Should return null when triggers is null")
        void shouldReturnNullWhenTriggersNull() {
            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getTriggers()).thenReturn(null);
            assertThat(detector.getFirstTriggerType(plan)).isNull();
        }
    }
}
