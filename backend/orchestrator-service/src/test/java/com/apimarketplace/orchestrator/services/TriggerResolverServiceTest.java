package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.config.WorkflowExecutionConfig;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.services.triggers.DataSourceTriggerResolver;
import com.apimarketplace.orchestrator.services.triggers.ScheduleTriggerResolver;
import com.apimarketplace.orchestrator.services.triggers.TriggerItemContextBuilder;
import com.apimarketplace.orchestrator.services.triggers.TriggerPayloadBuilder;
import com.apimarketplace.orchestrator.services.triggers.TriggerTypeHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Composition-level test for the trigger-resolver dispatch chain.
 *
 * <p>Audit 2026-05-06 P1 #11: this test verifies that {@link TriggerResolverService}
 * correctly dispatches to {@link ScheduleTriggerResolver} when given a {@code
 * "schedule"} trigger type AND that an empty handler list still throws
 * {@code "Unsupported trigger type"} (no silent graceful fallback that would
 * mask real bugs).
 *
 * <p><b>Scope caveat (audit 2026-05-06 round 3 #2 honesty pass).</b> The handler
 * list is HAND-BUILT here ({@code List.of(new ScheduleTriggerResolver())}), so
 * removing {@code @Component} from {@link ScheduleTriggerResolver} would NOT
 * fail this test. The Spring DI wire-up (autowired
 * {@code List&lt;TriggerTypeHandler&gt;} discovers all {@code @Component}-annotated
 * beans) is exercised by the application context startup and integration tests -
 * a dedicated {@code @SpringBootTest} slice would be heavy for one resolver and
 * the round-3 audit consensus accepted this scope. The OOM 2026-05-06 12:22
 * incident root cause was the missing {@code ScheduleTriggerResolver} class
 * itself, not a missing annotation; both this test (presence in dispatch chain)
 * and {@link ScheduleTriggerResolverTest} (resolver behaviour) guard against it.
 */
@DisplayName("TriggerResolverService - composition / dispatch")
class TriggerResolverServiceTest {

    @Test
    @DisplayName("Schedule trigger dispatches to ScheduleTriggerResolver - no IllegalArgumentException")
    void scheduleTriggerIsDispatched() {
        // Hand-build the handler list. Note: this verifies dispatch logic only -
        // it does NOT exercise Spring's autowired discovery (see class Javadoc
        // for the scope caveat). A removed @Component on ScheduleTriggerResolver
        // would still pass this test because the handler is constructed directly.
        List<TriggerTypeHandler> handlers = List.of(new ScheduleTriggerResolver());
        TriggerResolverService service = new TriggerResolverService(
                handlers,
                mock(TriggerPayloadBuilder.class),
                mock(TriggerItemContextBuilder.class),
                mock(DataSourceTriggerResolver.class),
                mock(WorkflowExecutionConfig.class));

        Trigger schedule = new Trigger("trigger:cron", "cron", "single", "schedule", Map.of(), null);

        Map<String, Object> result = service.resolveTrigger(schedule, "tenant-1");

        assertThat(result).as("Resolver chain must dispatch to ScheduleTriggerResolver, not throw").isNotNull();
        assertThat(result.get("type")).isEqualTo("schedule");
        assertThat(result.get("count")).isEqualTo(0);
    }

    @Test
    @DisplayName("Truly-unhandled trigger type still throws (no graceful fallback masking real bugs)")
    void unhandledTriggerTypeStillThrows() {
        // Empty handler list → no dispatch matches "schedule" or anything else.
        // The framework should THROW so an operator sees a clear "unsupported"
        // signal - silent fallback would mask real bugs (e.g. typo in trigger.type).
        TriggerResolverService service = new TriggerResolverService(
                List.of(),
                mock(TriggerPayloadBuilder.class),
                mock(TriggerItemContextBuilder.class),
                mock(DataSourceTriggerResolver.class),
                mock(WorkflowExecutionConfig.class));

        Trigger schedule = new Trigger("trigger:cron", "cron", "single", "schedule", Map.of(), null);

        assertThatThrownBy(() -> service.resolveTrigger(schedule, "tenant-1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported trigger type: schedule");
    }
}
