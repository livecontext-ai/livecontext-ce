package com.apimarketplace.orchestrator.services.triggers;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for OOM 2026-05-06 12:22 incident: pre-fix, every UI page
 * load on a scheduled run logged ERROR + stack trace because
 * {@code TriggerResolverService.resolveTrigger} threw {@code
 * IllegalArgumentException("Unsupported trigger type: schedule")} - schedule
 * triggers had no registered handler. This resolver fills the gap and returns
 * a valid no-fan-out payload so {@code V2TriggerLoadingService} can proceed.
 */
@DisplayName("ScheduleTriggerResolver")
class ScheduleTriggerResolverTest {

    private ScheduleTriggerResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ScheduleTriggerResolver();
    }

    private Trigger scheduleTrigger() {
        // Trigger record: (id, label, strategy, type, params, chatMatch)
        return new Trigger("trigger:cron", "cron", "single", "schedule", Map.of(), null);
    }

    private Trigger scheduleTriggerWithParams(Map<String, Object> params) {
        return new Trigger("trigger:cron", "cron", "single", "schedule", params, null);
    }

    @Nested
    @DisplayName("canHandle()")
    class CanHandleTests {
        @Test
        @DisplayName("Handles 'schedule' regardless of case")
        void shouldHandleScheduleType() {
            assertTrue(resolver.canHandle("schedule"));
            assertTrue(resolver.canHandle("Schedule"));
            assertTrue(resolver.canHandle("SCHEDULE"));
        }

        @Test
        @DisplayName("Refuses other trigger types so the resolver-chain dispatch stays unambiguous")
        void shouldRefuseOtherTypes() {
            assertFalse(resolver.canHandle("webhook"));
            assertFalse(resolver.canHandle("manual"));
            assertFalse(resolver.canHandle("chat"));
            assertFalse(resolver.canHandle("datasource"));
            assertFalse(resolver.canHandle("workflow"));
            assertFalse(resolver.canHandle("form"));
            assertFalse(resolver.canHandle(""));
            assertFalse(resolver.canHandle(null));
        }
    }

    @Nested
    @DisplayName("resolve()")
    class ResolveTests {
        @Test
        @DisplayName("Returns the canonical no-fan-out payload - never throws (regression: OOM 12:22 ERROR storm)")
        void shouldReturnCanonicalPayload() {
            Map<String, Object> result = resolver.resolve(scheduleTrigger(), "tenant-1", Map.of());

            assertEquals("trigger:cron", result.get("triggerId"));
            assertEquals("schedule", result.get("type"));
            assertEquals("schedule", result.get("source"));
            assertEquals("success", result.get("status"));
            assertEquals("schedule", result.get("triggered_by"));
            assertNotNull(result.get("triggered_at"),
                "triggered_at must always be set - frontend schema + node_type_documentation alignment");
            assertEquals(0, result.get("count"),
                "Schedule triggers do NOT iterate - no phantom item for downstream split fan-out");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
            assertNotNull(data);
            assertTrue(data.isEmpty(),
                "data must be empty: pre-fix V2TriggerLoading caught IllegalArgument and cached []; we preserve that contract");
        }

        @Test
        @DisplayName("Custom params propagate to top-level payload only (data is empty by design - no per-item fan-out)")
        void shouldIncludeCustomParams() {
            Map<String, Object> params = Map.of(
                "cron", "0 0 * * *",
                "timezone", "Europe/Paris",
                "customField", "customValue"
            );
            Map<String, Object> result = resolver.resolve(scheduleTriggerWithParams(params), "tenant-1", Map.of());

            assertEquals("0 0 * * *", result.get("cron"));
            assertEquals("Europe/Paris", result.get("timezone"));
            assertEquals("customValue", result.get("customField"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
            assertTrue(data.isEmpty(),
                "Schedule trigger params describe the FIRE - they are NOT iterable items");
        }

        @Test
        @DisplayName("Null/empty params do not crash and do not pollute the payload")
        void shouldHandleNullParams() {
            Map<String, Object> result = resolver.resolve(scheduleTrigger(), "tenant-1", null);

            assertNotNull(result);
            assertEquals(0, result.get("count"));
            // Standard fields still present - null params don't suppress them.
            assertNotNull(result.get("triggered_at"));
            assertEquals("schedule", result.get("type"));
        }
    }
}
