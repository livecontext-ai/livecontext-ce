package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Node specification for the Task CRUD node.
 * Registers output schema in NodeDefinitionRegistry so frontend can display it.
 */
@Component
public class TaskNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("TASK")
            .label("Task")
            .category("core")
            .variablePrefix("core")
            .description("CRUD operations on agent tasks directly from a workflow")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("node_type")
                    .type("string")
                    .description("Always 'TASK'")
                    .build(),
                OutputFieldDef.builder()
                    .key("operation")
                    .type("string")
                    .description("The operation executed (create_task, get_task, update_task, delete_task, list_tasks)")
                    .build(),
                OutputFieldDef.builder()
                    .key("success")
                    .type("boolean")
                    .description("Whether the operation succeeded")
                    .build(),
                OutputFieldDef.builder()
                    .key("task")
                    .type("object")
                    .description("The task object (for create_task, get_task, update_task)")
                    .build(),
                OutputFieldDef.builder()
                    .key("task_id")
                    .type("string")
                    .description("The deleted task ID (for delete_task only)")
                    .build(),
                OutputFieldDef.builder()
                    .key("tasks")
                    .type("array")
                    .description("Array of task objects (for list_tasks only)")
                    .build(),
                OutputFieldDef.builder()
                    .key("count")
                    .type("number")
                    .description("Number of tasks in current page (for list_tasks only)")
                    .build(),
                OutputFieldDef.builder()
                    .key("total")
                    .type("number")
                    .description("Total matching tasks (for list_tasks only)")
                    .build(),
                OutputFieldDef.builder()
                    .key("resolved_params")
                    .type("object")
                    .description("Snapshot of the resolved request parameters (present on every result, including failures)")
                    .build()
            ))
            .keywords(List.of("task", "crud", "create", "delete", "update", "list", "agent", "assign", "delegation"))
            .build();
    }
}
