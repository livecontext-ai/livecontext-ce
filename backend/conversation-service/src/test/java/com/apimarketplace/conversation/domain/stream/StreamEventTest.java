package com.apimarketplace.conversation.domain.stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StreamEvent")
class StreamEventTest {

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethods {

        @Test
        @DisplayName("started should create StreamStarted with correct fields")
        void startedShouldCreateStreamStarted() {
            StreamEvent.StreamStarted event = StreamEvent.started("s-1", "c-1", "gpt-4");

            assertThat(event.streamId()).isEqualTo("s-1");
            assertThat(event.conversationId()).isEqualTo("c-1");
            assertThat(event.model()).isEqualTo("gpt-4");
            assertThat(event.timestamp()).isNotNull();
        }

        @Test
        @DisplayName("content should create ContentChunk")
        void contentShouldCreateChunk() {
            StreamEvent.ContentChunk event = StreamEvent.content("s-1", "Hello");

            assertThat(event.streamId()).isEqualTo("s-1");
            assertThat(event.content()).isEqualTo("Hello");
            assertThat(event.timestamp()).isNotNull();
        }

        @Test
        @DisplayName("thinking should create ThinkingChunk")
        void thinkingShouldCreateChunk() {
            StreamEvent.ThinkingChunk event = StreamEvent.thinking("s-1", "Analyzing...");

            assertThat(event.streamId()).isEqualTo("s-1");
            assertThat(event.thinking()).isEqualTo("Analyzing...");
        }

        @Test
        @DisplayName("thinkingSection should create ThinkingSection")
        void thinkingSectionShouldCreate() {
            StreamEvent.ThinkingSection event = StreamEvent.thinkingSection("s-1", "Step 1", "Details");

            assertThat(event.title()).isEqualTo("Step 1");
            assertThat(event.content()).isEqualTo("Details");
        }

        @Test
        @DisplayName("toolCall should create ToolCall")
        void toolCallShouldCreate() {
            StreamEvent.ToolCall event = StreamEvent.toolCall("s-1", "my_tool", "call-1", Map.of("key", "val"));

            assertThat(event.toolName()).isEqualTo("my_tool");
            assertThat(event.toolId()).isEqualTo("call-1");
            assertThat(event.arguments()).containsEntry("key", "val");
        }

        @Test
        @DisplayName("toolResult should create ToolResult with all fields")
        void toolResultShouldCreate() {
            StreamEvent.Visualization viz = new StreamEvent.Visualization("datasource", "ds-1", "Table");
            StreamEvent.ToolResult event = StreamEvent.toolResult(
                    "s-1", "call-1", "my_tool", true, 150L,
                    "result-1", null, viz, "icon", "Display Name", "label",
                    Map.of("approval", true));

            assertThat(event.success()).isTrue();
            assertThat(event.durationMs()).isEqualTo(150L);
            assertThat(event.resultId()).isEqualTo("result-1");
            assertThat(event.visualization()).isNotNull();
            assertThat(event.visualization().type()).isEqualTo("datasource");
            assertThat(event.iconSlug()).isEqualTo("icon");
            assertThat(event.displayToolName()).isEqualTo("Display Name");
            assertThat(event.label()).isEqualTo("label");
        }

        @Test
        @DisplayName("completed should create StreamCompleted")
        void completedShouldCreate() {
            StreamEvent.StreamCompleted event = StreamEvent.completed("s-1", "Full content", 500);

            assertThat(event.fullContent()).isEqualTo("Full content");
            assertThat(event.totalTokens()).isEqualTo(500);
        }

        @Test
        @DisplayName("error should create StreamError")
        void errorShouldCreate() {
            StreamEvent.StreamError event = StreamEvent.error("s-1", "timeout", "TIMEOUT", true);

            assertThat(event.error()).isEqualTo("timeout");
            assertThat(event.errorCode()).isEqualTo("TIMEOUT");
            assertThat(event.retryable()).isTrue();
        }

        @Test
        @DisplayName("stopped should create StreamStopped")
        void stoppedShouldCreate() {
            StreamEvent.StreamStopped event = StreamEvent.stopped("s-1", "partial");

            assertThat(event.partialContent()).isEqualTo("partial");
        }

        @Test
        @DisplayName("heartbeat should create Heartbeat")
        void heartbeatShouldCreate() {
            StreamEvent.Heartbeat event = StreamEvent.heartbeat("s-1");
            assertThat(event.streamId()).isEqualTo("s-1");
            assertThat(event.timestamp()).isNotNull();
        }

        @Test
        @DisplayName("titleUpdated should create TitleUpdated")
        void titleUpdatedShouldCreate() {
            StreamEvent.TitleUpdated event = StreamEvent.titleUpdated("s-1", "c-1", "New Title");

            assertThat(event.conversationId()).isEqualTo("c-1");
            assertThat(event.title()).isEqualTo("New Title");
        }

        @Test
        @DisplayName("credentialRequired should create CredentialRequired")
        void credentialRequiredShouldCreate() {
            StreamEvent.CredentialRequired event = StreamEvent.credentialRequired("s-1", "gmail", "email_send", "tool-1");

            assertThat(event.credentialType()).isEqualTo("gmail");
            assertThat(event.toolName()).isEqualTo("email_send");
            assertThat(event.toolId()).isEqualTo("tool-1");
        }

        @Test
        @DisplayName("serviceApprovalRequired should create ServiceApprovalRequired")
        void serviceApprovalRequiredShouldCreate() {
            StreamEvent.ServiceApprovalInfo info = new StreamEvent.ServiceApprovalInfo("gmail", "Gmail", "gmail");
            StreamEvent.ServiceApprovalRequired event = StreamEvent.serviceApprovalRequired("s-1", List.of(info), "Need access", false);

            assertThat(event.services()).hasSize(1);
            assertThat(event.services().get(0).serviceName()).isEqualTo("Gmail");
            assertThat(event.reason()).isEqualTo("Need access");
            assertThat(event.needsAttention()).isFalse();
        }

        @Test
        @DisplayName("awaitingApproval should create StreamAwaitingApproval")
        void awaitingApprovalShouldCreate() {
            StreamEvent.ServiceApprovalInfo info = new StreamEvent.ServiceApprovalInfo("slack", "Slack", "slack");
            StreamEvent.StreamAwaitingApproval event = StreamEvent.awaitingApproval("s-1", "partial", List.of(info));

            assertThat(event.partialContent()).isEqualTo("partial");
            assertThat(event.services()).hasSize(1);
        }

        @Test
        @DisplayName("pendingActionCancelled should create PendingActionCancelled")
        void pendingActionCancelledShouldCreate() {
            StreamEvent.PendingActionCancelled event = StreamEvent.pendingActionCancelled("s-1", "new_msg", "User sent new message");

            assertThat(event.reason()).isEqualTo("new_msg");
            assertThat(event.message()).isEqualTo("User sent new message");
        }
    }

    @Nested
    @DisplayName("Visualization")
    class VisualizationTests {

        @Test
        @DisplayName("should create basic visualization without run info")
        void shouldCreateBasicVisualization() {
            StreamEvent.Visualization viz = new StreamEvent.Visualization("datasource", "ds-1", "My Table");

            assertThat(viz.type()).isEqualTo("datasource");
            assertThat(viz.id()).isEqualTo("ds-1");
            assertThat(viz.title()).isEqualTo("My Table");
            assertThat(viz.runId()).isNull();
            assertThat(viz.runIndex()).isNull();
        }

        @Test
        @DisplayName("should create workflow_run visualization with run info")
        void shouldCreateWorkflowRunVisualization() {
            StreamEvent.Visualization viz = new StreamEvent.Visualization("workflow_run", "wf-1", "My Workflow", "run-1", 3);

            assertThat(viz.type()).isEqualTo("workflow_run");
            assertThat(viz.runId()).isEqualTo("run-1");
            assertThat(viz.runIndex()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("ServiceApprovalInfo")
    class ServiceApprovalInfoTests {

        @Test
        @DisplayName("should create with minimal fields")
        void shouldCreateWithMinimal() {
            StreamEvent.ServiceApprovalInfo info = new StreamEvent.ServiceApprovalInfo("gmail", "Gmail", "gmail");

            assertThat(info.serviceType()).isEqualTo("gmail");
            assertThat(info.serviceName()).isEqualTo("Gmail");
            assertThat(info.iconSlug()).isEqualTo("gmail");
            assertThat(info.toolName()).isNull();
            assertThat(info.toolId()).isNull();
            assertThat(info.description()).isNull();
        }

        @Test
        @DisplayName("should create with all fields")
        void shouldCreateWithAllFields() {
            StreamEvent.ServiceApprovalInfo info = new StreamEvent.ServiceApprovalInfo(
                    "gmail", "Gmail", "gmail", "List Messages", "tool-1", "Need email access");

            assertThat(info.toolName()).isEqualTo("List Messages");
            assertThat(info.description()).isEqualTo("Need email access");
        }
    }
}
