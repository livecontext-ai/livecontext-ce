package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.NodePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * What {@code workflow(action='describe')} says about a node's execution policy.
 *
 * <p><b>Why this needs pinning.</b> The help topic tells the agent, in as many words, that
 * {@code describe} shows the node's current nodePolicy block. That sentence is only true while this
 * code exists, and an agent that trusts it and gets nothing back has no other way to check what it
 * set: it cannot see the canvas, and the block is not a node parameter it could read another way.
 * Deleting the read-back left every test in the repository green.
 */
@DisplayName("workflow(action='describe') - the node's execution policy read back")
class WorkflowBuilderViewerNodePolicyTest {

    private WorkflowBuilderViewer viewer() {
        NodeDescriptionBuilder descriptionBuilder = mock(NodeDescriptionBuilder.class);
        // The description itself is another unit's job; these tests are about the policy block the
        // viewer adds beside it.
        org.mockito.Mockito.lenient()
                .when(descriptionBuilder.buildDescription(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyMap(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(NodeDescriptionBuilder.DescriptionResult.empty());
        return new WorkflowBuilderViewer(
                descriptionBuilder,
                mock(com.apimarketplace.orchestrator.tools.workflow.builder.viewer.FlowRepresentationBuilder.class),
                mock(WorkflowBuilderValidator.class),
                mock(ResponseContextBuilder.class),
                mock(AgentWorkflowFireService.class));
    }

    private WorkflowBuilderSession sessionWithPolicy(Map<String, Object> policy) {
        WorkflowBuilderSession session = WorkflowBuilderSession.builder()
                .sessionId("s").tenantId("t").workflowName("W")
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "mcp:publish");
        node.put("type", "mcp");
        node.put("label", "Publish");
        if (policy != null) {
            node.put(NodePolicy.JSON_KEY, new LinkedHashMap<>(policy));
        }
        session.getMcps().add(node);
        return session;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> describe(WorkflowBuilderSession session) {
        ToolExecutionResult result = viewer().executeDescribe(session, Map.of("node", "Publish"));
        assertThat(result.success()).as(String.valueOf(result.error())).isTrue();
        return (Map<String, Object>) result.data();
    }

    @Test
    @DisplayName("a configured policy comes back with a hint saying how to change or clear it")
    void aConfiguredPolicyIsReadBack() {
        Map<String, Object> data = describe(sessionWithPolicy(
                Map.of("retryCount", 2, "providerRetryMaxWaitSec", 0)));

        assertThat(data.get(NodePolicy.JSON_KEY))
                .isEqualTo(Map.of("retryCount", 2, "providerRetryMaxWaitSec", 0));
        assertThat(String.valueOf(data.get("node_policy_hint")))
                .contains("nodePolicy={}")
                .contains("action='modify'");
    }

    @Test
    @DisplayName("a node with no policy grows no field, so an ordinary describe is unchanged")
    void aNodeWithoutAPolicyIsUnchanged() {
        Map<String, Object> data = describe(sessionWithPolicy(null));

        assertThat(data).doesNotContainKey(NodePolicy.JSON_KEY);
        assertThat(data).doesNotContainKey("node_policy_hint");
    }

    @Test
    @DisplayName("an EMPTY block is treated as no policy, not reported as one")
    void anEmptyBlockIsNotReported() {
        // A node can end up with an empty block from an older plan. Announcing it would tell the
        // agent a policy governs the node when nothing does.
        Map<String, Object> data = describe(sessionWithPolicy(Map.of()));

        assertThat(data).doesNotContainKey(NodePolicy.JSON_KEY);
    }
}
