package com.apimarketplace.orchestrator.services.triggers;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ManualTriggerResolver")
class ManualTriggerResolverTest {

    private static final String TENANT_ID = "tenant-1";

    @Mock private TriggerUserResolver triggerUserResolver;

    private ManualTriggerResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ManualTriggerResolver();
        ReflectionTestUtils.setField(resolver, "triggerUserResolver", triggerUserResolver);
    }

    @Nested
    @DisplayName("canHandle()")
    class CanHandleTests {
        @Test
        @DisplayName("Handles manual trigger type regardless of case")
        void handlesManualRegardlessOfCase() {
            assertThat(resolver.canHandle("manual")).isTrue();
            assertThat(resolver.canHandle("Manual")).isTrue();
            assertThat(resolver.canHandle("MANUAL")).isTrue();
        }

        @Test
        @DisplayName("Refuses other trigger types")
        void refusesOtherTriggerTypes() {
            assertThat(resolver.canHandle("schedule")).isFalse();
            assertThat(resolver.canHandle("webhook")).isFalse();
            assertThat(resolver.canHandle(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("resolve()")
    class ResolveTests {
        @Test
        @DisplayName("Emits canonical manual payload with display-name actor and custom params")
        void emitsCanonicalManualPayloadWithCustomParams() {
            when(triggerUserResolver.resolveDisplayName(TENANT_ID)).thenReturn("Jane Publisher");
            Trigger trigger = new Trigger(
                    "trigger:manual",
                    "Manual",
                    "single",
                    "manual",
                    Map.of("account_id", "acct_123", "dry_run", true),
                    null);

            Map<String, Object> result = resolver.resolve(trigger, TENANT_ID, Map.of());

            assertThat(result)
                    .containsEntry("triggerId", "trigger:manual")
                    .containsEntry("type", "manual")
                    .containsEntry("status", "success")
                    .containsEntry("source", "manual")
                    .containsEntry("triggered_by", "Jane Publisher")
                    .containsEntry("account_id", "acct_123")
                    .containsEntry("dry_run", true)
                    .containsEntry("count", 1);
            assertThat(result).containsKey("triggered_at");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
            assertThat(data).hasSize(1);
            assertThat(data.get(0))
                    .containsEntry("triggered_by", "Jane Publisher")
                    .containsEntry("account_id", "acct_123")
                    .containsEntry("dry_run", true)
                    .containsKey("triggered_at");
        }
    }
}
