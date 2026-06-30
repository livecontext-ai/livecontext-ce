package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SetNode (Set / Edit Fields).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SetNode")
class SetNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private V2TemplateAdapter mockTemplateAdapter;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            new HashMap<>(),
            mockPlan
        );
    }

    private SetNode buildNode(Core.SetConfig config, Map<String, Object> resolved) {
        SetNode node = new SetNode("core:set", config);
        node.setTemplateAdapter(mockTemplateAdapter);
        when(mockTemplateAdapter.resolveTemplates(anyMap(), any())).thenAnswer(inv -> {
            Map<String, Object> in = inv.getArgument(0);
            Map<String, Object> out = new HashMap<>();
            for (Map.Entry<String, Object> e : in.entrySet()) {
                Object v = resolved.getOrDefault(e.getKey(), e.getValue());
                out.put(e.getKey(), v);
            }
            return out;
        });
        return node;
    }

    @Test
    @DisplayName("assigns simple string fields")
    void assignsSimpleFields() {
        Core.SetConfig config = new Core.SetConfig(List.of(
            new Core.SetFieldAssignment("greeting", "hello", "string"),
            new Core.SetFieldAssignment("name", "world", "string")
        ), true, null);

        SetNode node = buildNode(config, Map.of("__v__", "hello"));
        // Override the resolver: identity behavior is fine for static strings
        when(mockTemplateAdapter.resolveTemplates(anyMap(), any())).thenAnswer(inv -> inv.getArgument(0));

        NodeExecutionResult result = node.execute(context);

        assertTrue(result.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.output().get("output");
        assertEquals("hello", output.get("greeting"));
        assertEquals("world", output.get("name"));
        assertEquals(2, result.output().get("count"));
    }

    @Test
    @DisplayName("coerces values to number type")
    void coercesNumberType() {
        Core.SetConfig config = new Core.SetConfig(List.of(
            new Core.SetFieldAssignment("price", "42", "number")
        ), true, null);

        SetNode node = new SetNode("core:set", config);
        node.setTemplateAdapter(mockTemplateAdapter);
        when(mockTemplateAdapter.resolveTemplates(anyMap(), any())).thenAnswer(inv -> inv.getArgument(0));

        NodeExecutionResult result = node.execute(context);

        assertTrue(result.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) result.output().get("fields");
        Object price = fields.get("price");
        assertTrue(price instanceof Number, "expected number, got " + (price == null ? "null" : price.getClass()));
        assertEquals(42L, ((Number) price).longValue());
    }

    @Test
    @DisplayName("coerces values to boolean type")
    void coercesBooleanType() {
        Core.SetConfig config = new Core.SetConfig(List.of(
            new Core.SetFieldAssignment("active", "true", "boolean")
        ), true, null);

        SetNode node = new SetNode("core:set", config);
        node.setTemplateAdapter(mockTemplateAdapter);
        when(mockTemplateAdapter.resolveTemplates(anyMap(), any())).thenAnswer(inv -> inv.getArgument(0));

        NodeExecutionResult result = node.execute(context);

        assertTrue(result.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) result.output().get("fields");
        assertEquals(Boolean.TRUE, fields.get("active"));
    }

    @Test
    @DisplayName("fails at execute() when assignments are missing - NOT in constructor (which would crash tree build)")
    void failsWhenAssignmentsMissing() {
        // Constructor must NOT throw - it's called during execution-tree build, before any
        // user-visible run, so a throw would kill every other node in the workflow.
        SetNode nullCfg = new SetNode("core:set", null);
        NodeExecutionResult r1 = nullCfg.execute(context);
        assertFalse(r1.isSuccess(), "null config must fail at execute()");
        assertTrue(r1.errorMessage().orElse("").toLowerCase().contains("assignments"));

        Core.SetConfig empty = new Core.SetConfig(List.of(), false, null);
        SetNode emptyCfg = new SetNode("core:set", empty);
        NodeExecutionResult r2 = emptyCfg.execute(context);
        assertFalse(r2.isSuccess(), "empty assignments must fail at execute()");
        assertTrue(r2.errorMessage().orElse("").toLowerCase().contains("assignments"));
    }

    @Test
    @DisplayName("merges with input data when keepOnlySet is false")
    void mergesWithInputWhenKeepOnlySetFalse() {
        Core.SetConfig config = new Core.SetConfig(List.of(
            new Core.SetFieldAssignment("status", "active", "string")
        ), false, "{{input}}");

        SetNode node = new SetNode("core:set", config);
        node.setTemplateAdapter(mockTemplateAdapter);

        Map<String, Object> resolvedInput = new HashMap<>();
        resolvedInput.put("__input__", Map.of("id", 1, "status", "draft"));
        Map<String, Object> resolvedValue = new HashMap<>();
        resolvedValue.put("__v__", "active");

        when(mockTemplateAdapter.resolveTemplates(anyMap(), any())).thenAnswer(inv -> {
            Map<String, Object> in = inv.getArgument(0);
            if (in.containsKey("__input__")) return resolvedInput;
            return resolvedValue;
        });

        NodeExecutionResult result = node.execute(context);
        assertTrue(result.isSuccess());

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.output().get("output");
        assertEquals(1, output.get("id"));
        assertEquals("active", output.get("status"), "assignment should override input field");
    }
}
