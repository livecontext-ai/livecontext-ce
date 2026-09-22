package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SplitNodeExecutor")
class SplitNodeExecutorTest {

    @Mock
    private SplitContextManager contextManager;

    @Mock
    private V2TemplateAdapter templateAdapter;

    @Mock
    private ExecutionContext context;

    private SplitNodeExecutor executor;

    private static final int WORKFLOW_ITEM_INDEX = 0;
    /** Deliberately not 0, so "the executor forwards context.epoch()" cannot pass by default. */
    private static final int EPOCH = 42;

    @BeforeEach
    void setUp() {
        executor = new SplitNodeExecutor(contextManager, templateAdapter);
        lenient().when(context.epoch()).thenReturn(EPOCH);
    }

    @Nested
    @DisplayName("execute()")
    class Execute {

        @Test
        @DisplayName("should evaluate expression and create context with items")
        void shouldEvaluateAndCreateContext() {
            List<Object> items = List.of("item1", "item2", "item3");
            when(templateAdapter.evaluateTemplate(eq("{{trigger:webhook.messages}}"), any()))
                .thenReturn(items);
            when(contextManager.createContext(eq("run1"), eq("core:split1"), eq(WORKFLOW_ITEM_INDEX), isNull(), eq(items), eq(EPOCH)))
                .thenReturn(SplitContext.create("core:split1:0", items, EPOCH));

            NodeExecutionResult result = executor.execute(
                "run1",
                "core:split1",
                "{{trigger:webhook.messages}}",
                0,  // maxItems
                null,  // splitStrategy: not the subject of this test
                WORKFLOW_ITEM_INDEX,
                context
            );

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.output().get("item_count")).isEqualTo(3);
            assertThat(result.output().get("terminated")).isEqualTo(true);
            assertThat(result.output().get("spawn_reason")).isEqualTo("items_spawned");

            // The epoch is stamped on the context: it is what tells this context apart from the
            // same split's context in an earlier epoch when a delivery lands on another replica.
            verify(contextManager).createContext("run1", "core:split1", WORKFLOW_ITEM_INDEX, null, items, EPOCH);
        }

        @Test
        @DisplayName("reports its configuration under the PLAN's key names, strategy included (run-mode Params alignment)")
        @SuppressWarnings("unchecked")
        void reportsResolvedParamsUnderPlanKeyNames() {
            List<Object> items = List.of("a", "b", "c");
            when(templateAdapter.evaluateTemplate(eq("{{trigger:webhook.messages}}"), any()))
                .thenReturn(items);
            when(contextManager.createContext(any(), any(), anyInt(), isNull(), any(), anyInt()))
                .thenReturn(SplitContext.create("core:split1:0", items));

            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", "{{trigger:webhook.messages}}", 5,
                "stop-on-error", WORKFLOW_ITEM_INDEX, context);

            Map<String, Object> resolvedParams =
                (Map<String, Object>) result.output().get("resolved_params");

            // The names the builder form and the plan use. They used to be
            // source_expression / max_items / item_count, and the strategy was not
            // reported at all - so a configured field was invisible in the run view.
            assertThat(resolvedParams)
                .containsEntry("list", "{{trigger:webhook.messages}}")
                .containsEntry("maxItems", 5)
                .containsEntry("splitStrategy", "stop-on-error")
                .containsEntry("itemCount", 3)
                .doesNotContainKeys("source_expression", "max_items", "item_count");
        }

        @Test
        @DisplayName("omits the strategy rather than inventing one when the caller does not know it")
        @SuppressWarnings("unchecked")
        void omitsUnknownSplitStrategy() {
            List<Object> items = List.of("a");
            when(templateAdapter.evaluateTemplate(any(), any())).thenReturn(items);
            when(contextManager.createContext(any(), any(), anyInt(), isNull(), any(), anyInt()))
                .thenReturn(SplitContext.create("core:split1:0", items));

            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", "{{trigger:webhook.messages}}", 0, null, WORKFLOW_ITEM_INDEX, context);

            Map<String, Object> resolvedParams =
                (Map<String, Object>) result.output().get("resolved_params");
            assertThat(resolvedParams).doesNotContainKey("splitStrategy");
            // Same rule for an unset cap: 0 means "no cap was configured", and
            // rendering it would tell the reader they limited the split to nothing.
            assertThat(resolvedParams).doesNotContainKey("maxItems");
            assertThat(resolvedParams).containsEntry("itemCount", 1);
        }

        @Test
        @DisplayName("emitted persisted keys match the non-runtime keys declared by SplitNodeSpec (runtime <-> spec guard)")
        void emittedKeysMatchSplitNodeSpec() {
            List<Object> items = List.of("a", "b", "c");
            when(templateAdapter.evaluateTemplate(eq("{{trigger:webhook.messages}}"), any()))
                .thenReturn(items);
            when(contextManager.createContext(any(), any(), anyInt(), isNull(), any(), anyInt()))
                .thenReturn(SplitContext.create("core:split1:0", items));

            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", "{{trigger:webhook.messages}}", 0, null, WORKFLOW_ITEM_INDEX, context);

            // The live producer's top-level output keys, minus the engine-envelope keys that
            // GenericOutputSchemaMapper strips (node_type / resolved_params / item_index...).
            java.util.Set<String> emittedPersisted = new java.util.TreeSet<>(result.output().keySet());
            emittedPersisted.removeAll(
                com.apimarketplace.orchestrator.services.persistence.schema.GenericOutputSchemaMapper.ENGINE_ENVELOPE_KEYS);

            // The persisted (non-runtimeOnly) keys the agent/inspector schema declares.
            java.util.Set<String> declaredPersisted =
                new com.apimarketplace.orchestrator.execution.v2.nodes.SplitNodeSpec().definition().outputs().stream()
                    .filter(f -> !Boolean.TRUE.equals(f.runtimeOnly()))
                    .map(com.apimarketplace.agent.domain.OutputFieldDef::key)
                    .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));

            assertThat(emittedPersisted)
                .as("SplitNodeExecutor (the live output producer) must emit exactly the persisted keys SplitNodeSpec declares")
                .isEqualTo(declaredPersisted);
        }

        @Test
        @DisplayName("should return COMPLETED immediately after spawning")
        void shouldReturnCompletedImmediately() {
            when(templateAdapter.evaluateTemplate(any(), any()))
                .thenReturn(List.of("a", "b"));
            when(contextManager.createContext(any(), any(), anyInt(), isNull(), any(), anyInt()))
                .thenReturn(SplitContext.create("core:split1:0", List.of("a", "b")));

            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", "{{items}}", 0, null, WORKFLOW_ITEM_INDEX, context);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.output().get("terminated")).isEqualTo(true);
        }

        @Test
        @DisplayName("should handle empty list")
        void shouldHandleEmptyList() {
            when(templateAdapter.evaluateTemplate(any(), any()))
                .thenReturn(List.of());
            when(contextManager.createContext(any(), any(), anyInt(), isNull(), any(), anyInt()))
                .thenReturn(SplitContext.create("core:split1:0", List.of()));

            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", "{{items}}", 0, null, WORKFLOW_ITEM_INDEX, context);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.output().get("item_count")).isEqualTo(0);
            assertThat(result.output().get("spawn_reason")).isEqualTo("empty_list");
            // An empty spawn still REPLACES the scope, so it must be stamped like any other:
            // an UNKNOWN stamp here leaves a permanently un-stale scope behind for the next epoch.
            verify(contextManager).createContext(
                "run1", "core:split1", WORKFLOW_ITEM_INDEX, null, List.of(), EPOCH);
        }

        @Test
        @DisplayName("should apply maxItems limit")
        void shouldApplyMaxItemsLimit() {
            List<Object> items = List.of("a", "b", "c", "d", "e");
            when(templateAdapter.evaluateTemplate(any(), any())).thenReturn(items);
            when(contextManager.createContext(eq("run1"), eq("core:split1"), eq(WORKFLOW_ITEM_INDEX), isNull(), any(), anyInt()))
                .thenAnswer(inv -> {
                    List<Object> limited = inv.getArgument(4);
                    return SplitContext.create("core:split1:0", limited, inv.getArgument(5));
                });

            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", "{{items}}", 3, null, WORKFLOW_ITEM_INDEX, context);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            // The executor should limit items to 3
            verify(contextManager).createContext(eq("run1"), eq("core:split1"), eq(WORKFLOW_ITEM_INDEX),
                isNull(), eq(List.of("a", "b", "c")), eq(EPOCH));
        }

        @Test
        @DisplayName("should return FAILURE when expression evaluation fails")
        void shouldReturnFailureOnEvaluationError() {
            when(templateAdapter.evaluateTemplate(any(), any()))
                .thenThrow(new RuntimeException("Evaluation error"));

            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", "{{invalid}}", 0, null, WORKFLOW_ITEM_INDEX, context);

            assertThat(result.status()).isEqualTo(NodeStatus.FAILED);
            assertThat(result.errorMessage()).isPresent();
        }

        @Test
        @DisplayName("should return FAILURE when expression is null")
        void shouldReturnFailureWhenExpressionNull() {
            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", null, 0, null, WORKFLOW_ITEM_INDEX, context);

            assertThat(result.status()).isEqualTo(NodeStatus.FAILED);
        }

        @Test
        @DisplayName("should return FAILURE when expression is blank")
        void shouldReturnFailureWhenExpressionBlank() {
            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", "   ", 0, null, WORKFLOW_ITEM_INDEX, context);

            assertThat(result.status()).isEqualTo(NodeStatus.FAILED);
        }

        @Test
        @DisplayName("should auto-unwrap a wrapper Map on its 'items' key (Apify/SerpAPI-style)")
        void shouldUnwrapItemsWrapperMap() {
            // {items:[...], status, runId} - the exact prod 2026-05-14 Instagram Profile Scraper shape.
            // Must split over the inner array, NOT wrap the whole Map as a 1-item list.
            Map<String, Object> wrapper = Map.of(
                "items", List.of("a", "b", "c"),
                "status", "SUCCEEDED");
            when(templateAdapter.evaluateTemplate(any(), any())).thenReturn(wrapper);
            when(contextManager.createContext(any(), any(), anyInt(), isNull(), any(), anyInt()))
                .thenAnswer(inv -> SplitContext.create("core:split1:0", inv.getArgument(4)));

            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", "{{run.output}}", 0, null, WORKFLOW_ITEM_INDEX, context);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.output().get("item_count")).isEqualTo(3);
            verify(contextManager).createContext("run1", "core:split1", WORKFLOW_ITEM_INDEX, null,
                List.of("a", "b", "c"), EPOCH);
        }

        @Test
        @DisplayName("should auto-unwrap a wrapper Map on its 'records' key (Airtable-style)")
        void shouldUnwrapRecordsWrapperMap() {
            Map<String, Object> wrapper = Map.of("records", List.of(Map.of("id", 1), Map.of("id", 2)));
            when(templateAdapter.evaluateTemplate(any(), any())).thenReturn(wrapper);
            when(contextManager.createContext(any(), any(), anyInt(), isNull(), any(), anyInt()))
                .thenAnswer(inv -> SplitContext.create("core:split1:0", inv.getArgument(4)));

            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", "{{table.output}}", 0, null, WORKFLOW_ITEM_INDEX, context);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.output().get("item_count")).isEqualTo(2);
        }

        @Test
        @DisplayName("should FAIL LOUD on a Map with no array-bearing key (NO silent 1-item wrap)")
        void shouldFailLoudOnNonArrayMap() {
            // Pre-fix: this single object Map was silently wrapped as a 1-item list and the workflow
            // ran to completion with empty payloads (prod 2026-05-14 Instagram silent-failure shape).
            Map<String, Object> singleObject = Map.of("id", 1, "name", "alice");
            when(templateAdapter.evaluateTemplate(any(), any())).thenReturn(singleObject);

            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", "{{single}}", 0, null, WORKFLOW_ITEM_INDEX, context);

            assertThat(result.status()).isEqualTo(NodeStatus.FAILED);
            assertThat(result.errorMessage()).isPresent();
            assertThat(result.errorMessage().get())
                .contains("resolved to a Map with keys")
                .contains("known array-bearing keys");
        }

        @Test
        @DisplayName("should FAIL LOUD on a scalar (NO silent 1-item wrap)")
        void shouldFailLoudOnScalar() {
            when(templateAdapter.evaluateTemplate(any(), any())).thenReturn("not-a-list");

            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", "{{scalar}}", 0, null, WORKFLOW_ITEM_INDEX, context);

            assertThat(result.status()).isEqualTo(NodeStatus.FAILED);
            assertThat(result.errorMessage()).isPresent();
            assertThat(result.errorMessage().get()).contains("resolved to String");
        }

        @Test
        @DisplayName("should FAIL LOUD when evaluation resolves to null (not silently empty)")
        void shouldFailLoudOnNullResult() {
            // Parity with SplitNode: null = missing step output / unresolved template, distinct
            // from a legitimately empty list. Fail loud rather than complete as a no-op.
            when(templateAdapter.evaluateTemplate(any(), any())).thenReturn(null);

            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", "{{null}}", 0, null, WORKFLOW_ITEM_INDEX, context);

            assertThat(result.status()).isEqualTo(NodeStatus.FAILED);
            assertThat(result.errorMessage()).isPresent();
            assertThat(result.errorMessage().get()).contains("resolved to null");
        }
    }

    /**
     * What a split says about the expression it ran on. The Params column is opened by a
     * reader whose split produced nothing, and until now it answered a different question
     * on every path: the configured expression here, the resolved value in SplitNode, and
     * one lone error message on the failure path.
     */
    @Nested
    @DisplayName("what `list` resolved to")
    class ListResolved {

        @Test
        @DisplayName("the 0-item path says whether the array was empty - the one path where the resolved value IS the diagnosis")
        @SuppressWarnings("unchecked")
        void reportsWhatAnEmptySpawnResolvedTo() {
            when(templateAdapter.evaluateTemplate(any(), any())).thenReturn(List.of());
            when(contextManager.createContext(any(), any(), anyInt(), isNull(), any(), anyInt()))
                .thenReturn(SplitContext.create("core:split1:0", List.of()));

            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", "{{core:fetch.output.rows}}", 0, null, WORKFLOW_ITEM_INDEX, context);

            Map<String, Object> resolvedParams =
                (Map<String, Object>) result.output().get("resolved_params");
            assertThat(resolvedParams)
                .containsEntry("list", "{{core:fetch.output.rows}}")
                .containsEntry("listResolved", "List(size=0)")
                .containsEntry("itemCount", 0);
        }

        @Test
        @DisplayName("an empty wrapper object is told apart from an empty array, which the item count alone cannot do")
        @SuppressWarnings("unchecked")
        void tellsAnEmptyWrapperApartFromAnEmptyArray() {
            // Both spawn 0 items. "The upstream node returned no rows" and "the reference
            // points one level too high" are different bugs with different fixes, and the
            // reader had nothing to tell them apart with.
            Map<String, Object> wrapper = Map.of("items", List.of());
            when(templateAdapter.evaluateTemplate(any(), any())).thenReturn(wrapper);
            when(contextManager.createContext(any(), any(), anyInt(), isNull(), any(), anyInt()))
                .thenReturn(SplitContext.create("core:split1:0", List.of()));

            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", "{{core:fetch.output}}", 0, null, WORKFLOW_ITEM_INDEX, context);

            Map<String, Object> resolvedParams =
                (Map<String, Object>) result.output().get("resolved_params");
            assertThat(resolvedParams).containsEntry("listResolved", "Map(keys=[items])");
            assertThat(resolvedParams).containsEntry("itemCount", 0);
        }

        @Test
        @DisplayName("the spawn path reports the same two keys, so two rows of one run answer one question")
        @SuppressWarnings("unchecked")
        void reportsWhatASpawnResolvedTo() {
            when(templateAdapter.evaluateTemplate(any(), any())).thenReturn(List.of("a", "b"));
            when(contextManager.createContext(any(), any(), anyInt(), isNull(), any(), anyInt()))
                .thenReturn(SplitContext.create("core:split1:0", List.of("a", "b")));

            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", "{{items}}", 0, null, WORKFLOW_ITEM_INDEX, context);

            Map<String, Object> resolvedParams =
                (Map<String, Object>) result.output().get("resolved_params");
            assertThat(resolvedParams)
                .containsEntry("list", "{{items}}")
                .containsEntry("listResolved", "List(size=2)");
        }

        @Test
        @DisplayName("a failed split reports its whole configuration, not just the error the Output column already carries")
        @SuppressWarnings("unchecked")
        void reportsTheConfigurationOnFailure() {
            Map<String, Object> singleObject = Map.of("id", 1);
            when(templateAdapter.evaluateTemplate(any(), any())).thenReturn(singleObject);

            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", "{{single}}", 7, "stop-on-error", WORKFLOW_ITEM_INDEX, context);

            assertThat(result.status()).isEqualTo(NodeStatus.FAILED);
            Map<String, Object> resolvedParams =
                (Map<String, Object>) result.output().get("resolved_params");
            assertThat(resolvedParams)
                .containsEntry("list", "{{single}}")
                .containsEntry("listResolved", "Map(keys=[id])")
                .containsEntry("maxItems", 7)
                .containsEntry("splitStrategy", "stop-on-error");
            assertThat(resolvedParams).containsKey("error");
            // No item count: the split never got one, and reporting 0 would read as an
            // empty array rather than as a split that never ran.
            assertThat(resolvedParams).doesNotContainKey("itemCount");
        }

        @Test
        @DisplayName("nothing evaluated, nothing reported: a blank expression leaves the key out rather than inventing a value")
        @SuppressWarnings("unchecked")
        void reportsNoResolvedValueWhenNothingWasEvaluated() {
            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", "   ", 0, null, WORKFLOW_ITEM_INDEX, context);

            assertThat(result.status()).isEqualTo(NodeStatus.FAILED);
            Map<String, Object> resolvedParams =
                (Map<String, Object>) result.output().get("resolved_params");
            assertThat(resolvedParams).doesNotContainKey("listResolved");
            assertThat(resolvedParams).containsKey("error");
        }
    }

    @Nested
    @DisplayName("hasExistingContext()")
    class HasExistingContext {

        @Test
        @DisplayName("should return true when context exists")
        void shouldReturnTrueWhenContextExists() {
            when(contextManager.getContext("run1", "core:split1", 0))
                .thenReturn(java.util.Optional.of(SplitContext.create("core:split1:0", List.of())));

            assertThat(executor.hasExistingContext("run1", "core:split1", 0)).isTrue();
        }

        @Test
        @DisplayName("should return false when context does not exist")
        void shouldReturnFalseWhenNoContext() {
            when(contextManager.getContext("run1", "core:split1", 0))
                .thenReturn(java.util.Optional.empty());

            assertThat(executor.hasExistingContext("run1", "core:split1", 0)).isFalse();
        }
    }

    @Nested
    @DisplayName("clearContext()")
    class ClearContext {

        @Test
        @DisplayName("should delegate to context manager")
        void shouldDelegateToContextManager() {
            executor.clearContext("run1", "core:split1", 0);

            verify(contextManager).removeContext("run1", "core:split1", 0);
        }
    }

    /**
     * The pre-resolved-items entry point. It has NO production caller today (verified
     * 2026-08-14), so this is deliberately minimal: it pins the obligation a future caller
     * inherits - the scope it builds must carry the epoch, or a delivery landing on a pod that
     * holds an earlier epoch's scope reuses that one. BOTH branches are pinned because both
     * write a scope: the empty one replaces it just as destructively as the populated one.
     */
    @Nested
    @DisplayName("executeWithItems()")
    class ExecuteWithItems {

        @Test
        @DisplayName("stamps the epoch on the empty-items branch, which also replaces the scope")
        void stampsTheEpochOnTheEmptyBranch() {
            when(contextManager.createContext(any(), any(), anyInt(), isNull(), any(), anyInt()))
                .thenReturn(SplitContext.create("core:split1:0", List.of(), EPOCH));

            NodeExecutionResult result = executor.executeWithItems(
                "run1", "core:split1", List.of(), 0, WORKFLOW_ITEM_INDEX, context);

            assertThat(result.output().get("spawn_reason")).isEqualTo("empty_list");
            verify(contextManager).createContext(
                "run1", "core:split1", WORKFLOW_ITEM_INDEX, null, List.of(), EPOCH);
        }

        @Test
        @DisplayName("reports the cap it applied, and no expression, because none was evaluated on this path")
        @SuppressWarnings("unchecked")
        void reportsTheConfigurationItWasGiven() {
            // The third producer named in SplitParamsReport's own javadoc: it used to report
            // nothing at all. It has no expression to report - the items arrive resolved -
            // so `list` and `listResolved` are absent rather than blank, and the cap that
            // decided how many items were kept is the one thing it CAN state.
            when(contextManager.createContext(any(), any(), anyInt(), isNull(), any(), anyInt()))
                .thenAnswer(inv -> SplitContext.create("core:split1:0", inv.getArgument(4), inv.getArgument(5)));

            NodeExecutionResult result = executor.executeWithItems(
                "run1", "core:split1", List.of("a", "b", "c", "d"), 2, WORKFLOW_ITEM_INDEX, context);

            Map<String, Object> resolvedParams =
                (Map<String, Object>) result.output().get("resolved_params");
            assertThat(resolvedParams)
                .containsEntry("maxItems", 2)
                .containsEntry("itemCount", 2)
                .doesNotContainKeys("list", "listResolved");
        }

        @Test
        @DisplayName("stamps the context with the execution context's epoch, after maxItems is applied")
        void stampsTheEpoch() {
            when(contextManager.createContext(any(), any(), anyInt(), isNull(), any(), anyInt()))
                .thenAnswer(inv -> SplitContext.create("core:split1:0", inv.getArgument(4), inv.getArgument(5)));

            NodeExecutionResult result = executor.executeWithItems(
                "run1", "core:split1", List.of("a", "b", "c", "d"), 2, WORKFLOW_ITEM_INDEX, context);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.output().get("item_count")).isEqualTo(2);
            verify(contextManager).createContext(
                "run1", "core:split1", WORKFLOW_ITEM_INDEX, null, List.of("a", "b"), EPOCH);
        }
    }
}
