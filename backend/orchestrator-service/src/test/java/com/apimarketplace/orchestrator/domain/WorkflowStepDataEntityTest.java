package com.apimarketplace.orchestrator.domain;

import com.apimarketplace.orchestrator.domain.workflow.DiagnosticFieldLimits;
import com.apimarketplace.orchestrator.domain.workflow.ErrorMessageLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for the V187 widening of {@code selected_branch} and
 * {@code item_id} from VARCHAR(255) to TEXT, plus the 16 384-char cap applied
 * in the entity setters.
 *
 * <p>These tests pin the same contract as the V186 fix on {@code error_message}:
 * a long upstream-controlled value (raw Classify agent output for
 * {@code selected_branch}, JWT/URL for {@code item_id}) must propagate intact
 * up to the shared {@link ErrorMessageLimits#MAX_LENGTH} cap, and beyond it
 * must be truncated with the audit marker - never silently dropped by a
 * column-length constraint.
 */
@DisplayName("WorkflowStepDataEntity column caps")
class WorkflowStepDataEntityTest {

    @Nested
    @DisplayName("setSelectedBranch")
    class SetSelectedBranch {

        @Test
        @DisplayName("Persists a 2 000-char Classify category intact (would overflow pre-V187 VARCHAR(255))")
        void persistsLongClassifyCategoryIntact() {
            // Pre-V187 the column was VARCHAR(255). The raw Classify agent
            // output at StepDataPersistenceService.java:339 wrote whatever the
            // LLM emitted - a 2 K category label triggered the same silent-drop
            // family as the 2026-05-11 incident on error_message.
            WorkflowStepDataEntity entity = new WorkflowStepDataEntity();
            String longCategory = "category_" + "x".repeat(2_000);

            entity.setSelectedBranch(longCategory);

            assertEquals(longCategory, entity.getSelectedBranch(),
                    "A 2 K value must reach the entity intact post-V187 - would have been "
                            + "rejected by VARCHAR(255) pre-V187, dropped silently by the "
                            + "StepDataPersistenceService catch-all.");
        }

        @Test
        @DisplayName("Caps a 20 K Classify output at ErrorMessageLimits.MAX_LENGTH with marker")
        void capsExtremeClassifyOutputAtSharedLimit() {
            WorkflowStepDataEntity entity = new WorkflowStepDataEntity();
            int original = 20_000;
            String hugeCategory = "x".repeat(original);

            entity.setSelectedBranch(hugeCategory);

            String stored = entity.getSelectedBranch();
            assertNotNull(stored);
            assertTrue(stored.length() <= ErrorMessageLimits.MAX_LENGTH,
                    "Cap must hold - got " + stored.length());
            assertTrue(stored.endsWith("…[truncated, was " + original + " chars]"),
                    "Marker must record the original length so operators know to "
                            + "consult S3 storage for the full payload");
        }

        @Test
        @DisplayName("Null passes through unchanged (fast-path identity)")
        void nullPassesThrough() {
            WorkflowStepDataEntity entity = new WorkflowStepDataEntity();
            entity.setSelectedBranch(null);
            assertNull(entity.getSelectedBranch());
        }
    }

    @Nested
    @DisplayName("setItemId")
    class SetItemId {

        @Test
        @DisplayName("Persists a 2 000-char Transform output intact (would overflow pre-V187 VARCHAR(255))")
        void persistsLongTransformOutputIntact() {
            // Real observed payload class: JWT (~1.5 KB) or concatenated URLs
            // emitted by a Transform node into item_id (line 199 of
            // StepDataPersistenceService.enrichEntityWithNodeTypeFields).
            WorkflowStepDataEntity entity = new WorkflowStepDataEntity();
            String longItemId = "jwt." + "a".repeat(2_000) + ".sig";

            entity.setItemId(longItemId);

            assertEquals(longItemId, entity.getItemId(),
                    "A 2 K Transform output must reach the entity intact post-V187");
        }

        @Test
        @DisplayName("Caps a 20 K item_id at ErrorMessageLimits.MAX_LENGTH with marker")
        void capsExtremeItemIdAtSharedLimit() {
            WorkflowStepDataEntity entity = new WorkflowStepDataEntity();
            int original = 20_000;
            String hugeItemId = "a".repeat(original);

            entity.setItemId(hugeItemId);

            String stored = entity.getItemId();
            assertNotNull(stored);
            assertTrue(stored.length() <= ErrorMessageLimits.MAX_LENGTH);
            assertTrue(stored.endsWith("…[truncated, was " + original + " chars]"));
        }

        @Test
        @DisplayName("Null passes through unchanged (fast-path identity)")
        void nullPassesThrough() {
            WorkflowStepDataEntity entity = new WorkflowStepDataEntity();
            entity.setItemId(null);
            assertNull(entity.getItemId());
        }
    }

    @Nested
    @DisplayName("F4 / F1 bundle 2 prep - identifier column caps")
    class IdentifierCaps {

        @Test
        @DisplayName("stepAlias: 800-char node label is hash-suffix capped (collision-safe for unique index v6)")
        void stepAliasCappedWithHash() {
            WorkflowStepDataEntity e = new WorkflowStepDataEntity();
            e.setStepAlias("my_node_" + "x".repeat(800));
            assertTrue(e.getStepAlias().length() <= DiagnosticFieldLimits.STEP_ALIAS_MAX);
            assertTrue(e.getStepAlias().matches(".*hash=[0-9a-f]{8}\\]$"),
                    "step_alias is in unique index v6 - collision-safe hash suffix required");
        }

        @Test
        @DisplayName("Two long aliases differing past the cap get DIFFERENT capped keys - pre-cap would have collided on v6 index")
        void stepAliasDistinctKeysAfterCap() {
            WorkflowStepDataEntity a = new WorkflowStepDataEntity();
            WorkflowStepDataEntity b = new WorkflowStepDataEntity();
            // Both share the first 600 chars (past the cap=500), then diverge.
            a.setStepAlias("shared_prefix_" + "p".repeat(600) + "_alpha");
            b.setStepAlias("shared_prefix_" + "p".repeat(600) + "_omega");
            assertNotEquals(a.getStepAlias(), b.getStepAlias(),
                    "F4/F1-bundle-2 collision safety: ON CONFLICT DO NOTHING would silently drop the second insert "
                            + "if these two aliases truncated to the same key");
        }

        @Test
        @DisplayName("toolId: 1200-char custom API slug is plain-capped (no index, no hash)")
        void toolIdCappedPlain() {
            WorkflowStepDataEntity e = new WorkflowStepDataEntity();
            e.setToolId("myorg/" + "t".repeat(1_200));
            assertTrue(e.getToolId().length() <= DiagnosticFieldLimits.TOOL_ID_MAX);
            assertTrue(e.getToolId().endsWith("…[truncated,was=1206]"));
        }

        @Test
        @DisplayName("triggerId: trigger:default sentinel is preserved unchanged (V164 NOT NULL default)")
        void triggerIdSentinelPreserved() {
            WorkflowStepDataEntity e = new WorkflowStepDataEntity();
            e.setTriggerId("trigger:default");
            assertEquals("trigger:default", e.getTriggerId(),
                    "F4 sentinel rule: the V164 default must never be hashed - that would mutate the unique-index "
                            + "partition key for the entire 'no specific trigger' cohort");
        }

        @Test
        @DisplayName("triggerId: long webhook trigger is hash-suffix capped")
        void triggerIdLongCappedWithHash() {
            WorkflowStepDataEntity e = new WorkflowStepDataEntity();
            e.setTriggerId("trigger:my_webhook_" + "w".repeat(800));
            assertTrue(e.getTriggerId().length() <= DiagnosticFieldLimits.TRIGGER_ID_MAX);
            assertTrue(e.getTriggerId().matches(".*hash=[0-9a-f]{8}\\]$"));
        }

        @Test
        @DisplayName("normalizedKey: long derived key is hash-suffix capped (in V155 aggregate index)")
        void normalizedKeyCappedWithHash() {
            WorkflowStepDataEntity e = new WorkflowStepDataEntity();
            e.setNormalizedKey("mcp:" + "n".repeat(800));
            assertTrue(e.getNormalizedKey().length() <= DiagnosticFieldLimits.NORMALIZED_KEY_MAX);
            assertTrue(e.getNormalizedKey().matches(".*hash=[0-9a-f]{8}\\]$"));
        }

        @Test
        @DisplayName("loopId, skipSourceNode: plain-capped (not indexed)")
        void plainCappedFields() {
            WorkflowStepDataEntity e = new WorkflowStepDataEntity();
            e.setLoopId("core:split_" + "l".repeat(1_200));
            e.setSkipSourceNode("core:check_" + "s".repeat(1_200));
            assertTrue(e.getLoopId().length() <= DiagnosticFieldLimits.LOOP_ID_MAX);
            assertTrue(e.getSkipSourceNode().length() <= DiagnosticFieldLimits.SKIP_SOURCE_NODE_MAX);
            assertTrue(e.getLoopId().endsWith("]"));
            assertTrue(e.getSkipSourceNode().endsWith("]"));
        }

        @Test
        @DisplayName("@PrePersist/@PreUpdate lifecycle re-applies caps - closes native-write bypass")
        void prePersistLifecycleAppliesCaps() throws Exception {
            // Simulate a write path that bypasses the setters (e.g. Hibernate
            // field-access reflection or a raw JdbcTemplate that builds the
            // entity via reflection). Use reflection to set the field directly
            // - this matches what those bypass paths do - then invoke the
            // @PrePersist hook and assert the cap fired.
            WorkflowStepDataEntity e = new WorkflowStepDataEntity();
            java.lang.reflect.Field stepAliasField = WorkflowStepDataEntity.class.getDeclaredField("stepAlias");
            stepAliasField.setAccessible(true);
            stepAliasField.set(e, "bypass_alias_" + "z".repeat(800));

            // Invoke the package-private lifecycle method directly. In a real
            // JPA flush, Hibernate calls this. In integration tests it fires
            // automatically; here we exercise the contract in isolation.
            java.lang.reflect.Method hook =
                    WorkflowStepDataEntity.class.getDeclaredMethod("applyIdentifierCapsBeforeFlush");
            hook.setAccessible(true);
            hook.invoke(e);

            assertTrue(e.getStepAlias().length() <= DiagnosticFieldLimits.STEP_ALIAS_MAX,
                    "PrePersist hook must re-apply the cap even when the field was set bypassing the setter");
            assertTrue(e.getStepAlias().matches(".*hash=[0-9a-f]{8}\\]$"),
                    "Hash-suffix must still be present after the lifecycle re-application");
        }

        @Test
        @DisplayName("17-arg constructor routes stepAlias + toolId through the capped setters (no field bypass)")
        void constructorRoutesThroughSetters() {
            // Pre-fix the ctor wrote `this.stepAlias = stepAlias` directly,
            // bypassing the cap. A 500-char alias coming through the ctor
            // would have hit the DB at full length and triggered the
            // VARCHAR(255) overflow → DataIntegrityViolation → silent drop.
            // Post-fix the ctor invokes setStepAlias() and setToolId(), so
            // the cap fires uniformly regardless of construction path.
            WorkflowStepDataEntity e = new WorkflowStepDataEntity(
                    java.util.UUID.randomUUID(), "run-1",
                    "alias_" + "a".repeat(800),
                    "tool/" + "t".repeat(1_200),
                    null, null, null, "COMPLETED",
                    null, null, null, "tenant-1",
                    0, 0, 0, 0, null
            );
            assertTrue(e.getStepAlias().length() <= DiagnosticFieldLimits.STEP_ALIAS_MAX,
                    "Constructor must apply the cap - got " + e.getStepAlias().length());
            assertTrue(e.getToolId().length() <= DiagnosticFieldLimits.TOOL_ID_MAX);
        }
    }
}
