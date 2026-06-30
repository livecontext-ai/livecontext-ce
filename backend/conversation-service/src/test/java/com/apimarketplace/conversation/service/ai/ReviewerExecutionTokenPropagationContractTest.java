package com.apimarketplace.conversation.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Reviewer execution token propagation contract")
class ReviewerExecutionTokenPropagationContractTest {

    @Test
    @DisplayName("Agent context builder exposes reviewer execution token to tool credentials")
    void agentContextBuilderExposesReviewerExecutionTokenCredential() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/apimarketplace/conversation/service/ai/callback/AgentContextBuilder.java"));

        assertThat(source)
                .contains("request.getReviewerExecutionId()")
                .contains("credentials.put(\"__reviewerExecutionId__\", request.getReviewerExecutionId())");
    }

    @Test
    @DisplayName("Conversation tool execution forwards reviewer execution token to agent-service")
    void conversationToolExecutionForwardsReviewerExecutionToken() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/apimarketplace/conversation/service/ai/ConversationToolExecutionService.java"));

        assertThat(source)
                .contains("credentials.get(\"__reviewerExecutionId__\")")
                .contains("request.put(\"reviewerExecutionId\", credentials.get(\"__reviewerExecutionId__\"))");
    }
}
