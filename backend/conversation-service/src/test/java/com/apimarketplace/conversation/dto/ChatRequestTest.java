package com.apimarketplace.conversation.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChatRequest")
class ChatRequestTest {

    @Nested
    @DisplayName("Getters and setters")
    class GettersSetters {

        @Test
        @DisplayName("should get and set all fields")
        void shouldGetAndSetAllFields() {
            ChatRequest request = new ChatRequest();
            request.setMessage("Hello");
            request.setModel("gpt-4");
            request.setProvider("openai");
            request.setUserId("user-1");
            request.setConversationId("conv-1");
            request.setTimestamp("2024-01-01T00:00:00Z");
            request.setAgentId("agent-1");

            assertThat(request.getMessage()).isEqualTo("Hello");
            assertThat(request.getModel()).isEqualTo("gpt-4");
            assertThat(request.getProvider()).isEqualTo("openai");
            assertThat(request.getUserId()).isEqualTo("user-1");
            assertThat(request.getConversationId()).isEqualTo("conv-1");
            assertThat(request.getTimestamp()).isEqualTo("2024-01-01T00:00:00Z");
            assertThat(request.getAgentId()).isEqualTo("agent-1");
        }

        @Test
        @DisplayName("should set and get conversation history")
        void shouldSetAndGetConversationHistory() {
            ChatRequest request = new ChatRequest();
            ChatRequest.ChatMessage msg = new ChatRequest.ChatMessage();
            msg.setRole("user");
            msg.setContent("Hello");

            request.setConversationHistory(List.of(msg));

            assertThat(request.getConversationHistory()).hasSize(1);
            assertThat(request.getConversationHistory().get(0).getRole()).isEqualTo("user");
        }

        @Test
        @DisplayName("should set and get attachments")
        void shouldSetAndGetAttachments() {
            ChatRequest request = new ChatRequest();
            AttachmentRef ref = new AttachmentRef("storage-1", "IMAGE", "photo.jpg", "image/jpeg");

            request.setAttachments(List.of(ref));

            assertThat(request.getAttachments()).hasSize(1);
            assertThat(request.getAttachments().get(0).getStorageId()).isEqualTo("storage-1");
        }
    }

    @Nested
    @DisplayName("ChatMessage")
    class ChatMessageTests {

        @Test
        @DisplayName("should create with default constructor")
        void shouldCreateWithDefaultConstructor() {
            ChatRequest.ChatMessage msg = new ChatRequest.ChatMessage();
            assertThat(msg.getRole()).isNull();
            assertThat(msg.getContent()).isNull();
        }

        @Test
        @DisplayName("should get and set all fields")
        void shouldGetAndSetAllFields() {
            ChatRequest.ChatMessage msg = new ChatRequest.ChatMessage();
            msg.setRole("assistant");
            msg.setContent("Hi there!");
            msg.setTimestamp("2024-01-01T00:00:00Z");
            msg.setToolCalls("[{\"id\":\"call_1\"}]");

            assertThat(msg.getRole()).isEqualTo("assistant");
            assertThat(msg.getContent()).isEqualTo("Hi there!");
            assertThat(msg.getTimestamp()).isEqualTo("2024-01-01T00:00:00Z");
            assertThat(msg.getToolCalls()).isEqualTo("[{\"id\":\"call_1\"}]");
        }
    }
}
