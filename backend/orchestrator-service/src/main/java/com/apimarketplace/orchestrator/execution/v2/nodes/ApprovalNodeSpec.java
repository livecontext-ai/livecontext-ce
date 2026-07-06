package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ApprovalNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("APPROVAL")
            .label("User Approval")
            .category("core")
            .variablePrefix("core")
            .description("Pauses execution until user approves or rejects")
            .branching(true)
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("approver_roles")
                    .type("array")
                    .description("Roles allowed to approve")
                    .build(),
                OutputFieldDef.builder()
                    .key("required_approvals")
                    .type("number")
                    .description("Number of approvals required")
                    .build(),
                OutputFieldDef.builder()
                    .key("expires_at")
                    .type("datetime")
                    .description("When the approval request expires")
                    .build(),
                OutputFieldDef.builder()
                    .key("selected_port")
                    .type("string")
                    .description("Selected port: approved or rejected")
                    .build(),
                OutputFieldDef.builder()
                    .key("approval_context")
                    .type("string")
                    .description("Resolved approval context: the node's contextTemplate rendered at pause time. Present only when a context template was configured and resolved to non-blank text.")
                    .build()
            ))
            .keywords(List.of("approval", "approve", "reject", "human"))
            .build();
    }
}
