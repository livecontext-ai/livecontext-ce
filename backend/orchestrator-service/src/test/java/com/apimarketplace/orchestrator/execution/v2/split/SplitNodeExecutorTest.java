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

    @BeforeEach
    void setUp() {
        executor = new SplitNodeExecutor(contextManager, templateAdapter);
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
            when(contextManager.createContext(eq("run1"), eq("core:split1"), eq(WORKFLOW_ITEM_INDEX), isNull(), eq(items)))
                .thenReturn(SplitContext.create("core:split1:0", items));

            NodeExecutionResult result = executor.execute(
                "run1",
                "core:split1",
                "{{trigger:webhook.messages}}",
                0,  // maxItems
                WORKFLOW_ITEM_INDEX,
                context
            );

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.output().get("item_count")).isEqualTo(3);
            assertThat(result.output().get("terminated")).isEqualTo(true);
            assertThat(result.output().get("spawn_reason")).isEqualTo("items_spawned");

            verify(contextManager).createContext("run1", "core:split1", WORKFLOW_ITEM_INDEX, null, items);
        }

        @Test
        @DisplayName("should return COMPLETED immediately after spawning")
        void shouldReturnCompletedImmediately() {
            when(templateAdapter.evaluateTemplate(any(), any()))
                .thenReturn(List.of("a", "b"));
            when(contextManager.createContext(any(), any(), anyInt(), isNull(), any()))
                .thenReturn(SplitContext.create("core:split1:0", List.of("a", "b")));

            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", "{{items}}", 0, WORKFLOW_ITEM_INDEX, context);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.output().get("terminated")).isEqualTo(true);
        }

        @Test
        @DisplayName("should handle empty list")
        void shouldHandleEmptyList() {
            when(templateAdapter.evaluateTemplate(any(), any()))
                .thenReturn(List.of());
            when(contextManager.createContext(any(), any(), anyInt(), isNull(), any()))
                .thenReturn(SplitContext.create("core:split1:0", List.of()));

            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", "{{items}}", 0, WORKFLOW_ITEM_INDEX, context);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.output().get("item_count")).isEqualTo(0);
            assertThat(result.output().get("spawn_reason")).isEqualTo("empty_list");
        }

        @Test
        @DisplayName("should apply maxItems limit")
        void shouldApplyMaxItemsLimit() {
            List<Object> items = List.of("a", "b", "c", "d", "e");
            when(templateAdapter.evaluateTemplate(any(), any())).thenReturn(items);
            when(contextManager.createContext(eq("run1"), eq("core:split1"), eq(WORKFLOW_ITEM_INDEX), isNull(), any()))
                .thenAnswer(inv -> {
                    List<Object> limited = inv.getArgument(4);
                    return SplitContext.create("core:split1:0", limited);
                });

            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", "{{items}}", 3, WORKFLOW_ITEM_INDEX, context);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            // The executor should limit items to 3
            verify(contextManager).createContext(eq("run1"), eq("core:split1"), eq(WORKFLOW_ITEM_INDEX),
                isNull(), eq(List.of("a", "b", "c")));
        }

        @Test
        @DisplayName("should return FAILURE when expression evaluation fails")
        void shouldReturnFailureOnEvaluationError() {
            when(templateAdapter.evaluateTemplate(any(), any()))
                .thenThrow(new RuntimeException("Evaluation error"));

            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", "{{invalid}}", 0, WORKFLOW_ITEM_INDEX, context);

            assertThat(result.status()).isEqualTo(NodeStatus.FAILED);
            assertThat(result.errorMessage()).isPresent();
        }

        @Test
        @DisplayName("should return FAILURE when expression is null")
        void shouldReturnFailureWhenExpressionNull() {
            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", null, 0, WORKFLOW_ITEM_INDEX, context);

            assertThat(result.status()).isEqualTo(NodeStatus.FAILED);
        }

        @Test
        @DisplayName("should return FAILURE when expression is blank")
        void shouldReturnFailureWhenExpressionBlank() {
            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", "   ", 0, WORKFLOW_ITEM_INDEX, context);

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
            when(contextManager.createContext(any(), any(), anyInt(), isNull(), any()))
                .thenAnswer(inv -> SplitContext.create("core:split1:0", inv.getArgument(4)));

            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", "{{run.output}}", 0, WORKFLOW_ITEM_INDEX, context);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.output().get("item_count")).isEqualTo(3);
            verify(contextManager).createContext("run1", "core:split1", WORKFLOW_ITEM_INDEX, null,
                List.of("a", "b", "c"));
        }

        @Test
        @DisplayName("should auto-unwrap a wrapper Map on its 'records' key (Airtable-style)")
        void shouldUnwrapRecordsWrapperMap() {
            Map<String, Object> wrapper = Map.of("records", List.of(Map.of("id", 1), Map.of("id", 2)));
            when(templateAdapter.evaluateTemplate(any(), any())).thenReturn(wrapper);
            when(contextManager.createContext(any(), any(), anyInt(), isNull(), any()))
                .thenAnswer(inv -> SplitContext.create("core:split1:0", inv.getArgument(4)));

            NodeExecutionResult result = executor.execute(
                "run1", "core:split1", "{{table.output}}", 0, WORKFLOW_ITEM_INDEX, context);

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
                "run1", "core:split1", "{{single}}", 0, WORKFLOW_ITEM_INDEX, context);

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
                "run1", "core:split1", "{{scalar}}", 0, WORKFLOW_ITEM_INDEX, context);

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
                "run1", "core:split1", "{{null}}", 0, WORKFLOW_ITEM_INDEX, context);

            assertThat(result.status()).isEqualTo(NodeStatus.FAILED);
            assertThat(result.errorMessage()).isPresent();
            assertThat(result.errorMessage().get()).contains("resolved to null");
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
}
