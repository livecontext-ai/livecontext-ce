package com.apimarketplace.conversation.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Message Entity")
class MessageEntityTest {

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("should create with no-args constructor")
        void shouldCreateWithNoArgs() {
            Message message = new Message();

            assertThat(message.getId()).isNull();
            assertThat(message.getRole()).isNull();
            assertThat(message.getContent()).isNull();
            assertThat(message.getConversation()).isNull();
        }

        @Test
        @DisplayName("should create with role and content")
        void shouldCreateWithRoleAndContent() {
            Message message = new Message(Message.MessageRole.USER, "Hello");

            assertThat(message.getRole()).isEqualTo(Message.MessageRole.USER);
            assertThat(message.getContent()).isEqualTo("Hello");
        }

        @Test
        @DisplayName("should create with full constructor")
        void shouldCreateWithFullConstructor() {
            Message message = new Message(
                    Message.MessageRole.ASSISTANT, "Hi there",
                    "[{\"id\": \"call_1\"}]", "gpt-4", "2024-01-01T00:00:00"
            );

            assertThat(message.getRole()).isEqualTo(Message.MessageRole.ASSISTANT);
            assertThat(message.getContent()).isEqualTo("Hi there");
            assertThat(message.getToolCalls()).isEqualTo("[{\"id\": \"call_1\"}]");
            assertThat(message.getModel()).isEqualTo("gpt-4");
            assertThat(message.getTimestamp()).isEqualTo("2024-01-01T00:00:00");
        }
    }

    @Nested
    @DisplayName("Factory Methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("toolResult should create TOOL message")
        void shouldCreateToolResult() {
            Message message = Message.toolResult("call_123", "catalog", "{\"results\": []}");

            assertThat(message.getRole()).isEqualTo(Message.MessageRole.TOOL);
            assertThat(message.getToolCallId()).isEqualTo("call_123");
            assertThat(message.getToolName()).isEqualTo("catalog");
            assertThat(message.getContent()).isEqualTo("{\"results\": []}");
        }

        @Test
        @DisplayName("assistantWithToolCalls should create ASSISTANT message with tools")
        void shouldCreateAssistantWithToolCalls() {
            String toolCallsJson = "[{\"id\":\"call_1\",\"type\":\"function\"}]";
            Message message = Message.assistantWithToolCalls(toolCallsJson, "gpt-4");

            assertThat(message.getRole()).isEqualTo(Message.MessageRole.ASSISTANT);
            assertThat(message.getToolCalls()).isEqualTo(toolCallsJson);
            assertThat(message.getModel()).isEqualTo("gpt-4");
            assertThat(message.getContent()).isNull();
        }
    }

    @Nested
    @DisplayName("hasToolCalls")
    class HasToolCallsTests {

        @Test
        @DisplayName("should return true when tool calls are present")
        void shouldReturnTrueForToolCalls() {
            Message message = new Message();
            message.setToolCalls("[{\"id\":\"call_1\"}]");

            assertThat(message.hasToolCalls()).isTrue();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("should return false for null or empty tool calls")
        void shouldReturnFalseForNullOrEmpty(String toolCalls) {
            Message message = new Message();
            message.setToolCalls(toolCalls);

            assertThat(message.hasToolCalls()).isFalse();
        }

        @Test
        @DisplayName("should return false for blank tool calls")
        void shouldReturnFalseForBlank() {
            Message message = new Message();
            message.setToolCalls("   ");

            assertThat(message.hasToolCalls()).isFalse();
        }

        @Test
        @DisplayName("should return false for empty array")
        void shouldReturnFalseForEmptyArray() {
            Message message = new Message();
            message.setToolCalls("[]");

            assertThat(message.hasToolCalls()).isFalse();
        }
    }

    @Nested
    @DisplayName("isToolResult")
    class IsToolResultTests {

        @Test
        @DisplayName("should return true for TOOL role with toolCallId")
        void shouldReturnTrueForToolResult() {
            Message message = new Message();
            message.setRole(Message.MessageRole.TOOL);
            message.setToolCallId("call_123");

            assertThat(message.isToolResult()).isTrue();
        }

        @Test
        @DisplayName("should return false for TOOL role without toolCallId")
        void shouldReturnFalseWithoutToolCallId() {
            Message message = new Message();
            message.setRole(Message.MessageRole.TOOL);

            assertThat(message.isToolResult()).isFalse();
        }

        @Test
        @DisplayName("should return false for non-TOOL role")
        void shouldReturnFalseForNonToolRole() {
            Message message = new Message();
            message.setRole(Message.MessageRole.ASSISTANT);
            message.setToolCallId("call_123");

            assertThat(message.isToolResult()).isFalse();
        }
    }

    @Nested
    @DisplayName("Getters and Setters")
    class GettersSettersTests {

        @Test
        @DisplayName("should get and set id")
        void shouldGetAndSetId() {
            Message message = new Message();
            message.setId("msg-1");
            assertThat(message.getId()).isEqualTo("msg-1");
        }

        @Test
        @DisplayName("should get and set conversation")
        void shouldGetAndSetConversation() {
            Message message = new Message();
            Conversation conv = new Conversation();
            conv.setId("conv-1");
            message.setConversation(conv);
            assertThat(message.getConversation().getId()).isEqualTo("conv-1");
        }

        @Test
        @DisplayName("should get and set agentId")
        void shouldGetAndSetAgentId() {
            Message message = new Message();
            message.setAgentId("agent-123");
            assertThat(message.getAgentId()).isEqualTo("agent-123");
        }

        @Test
        @DisplayName("should get and set toolName")
        void shouldGetAndSetToolName() {
            Message message = new Message();
            message.setToolName("catalog");
            assertThat(message.getToolName()).isEqualTo("catalog");
        }

        @Test
        @DisplayName("should get and set createdAt")
        void shouldGetAndSetCreatedAt() {
            Message message = new Message();
            LocalDateTime now = LocalDateTime.now();
            message.setCreatedAt(now);
            assertThat(message.getCreatedAt()).isEqualTo(now);
        }
    }

    @Nested
    @DisplayName("MessageRole enum")
    class MessageRoleTests {

        @Test
        @DisplayName("should have all expected roles")
        void shouldHaveAllRoles() {
            assertThat(Message.MessageRole.values()).containsExactlyInAnyOrder(
                    Message.MessageRole.USER,
                    Message.MessageRole.ASSISTANT,
                    Message.MessageRole.SYSTEM,
                    Message.MessageRole.TOOL
            );
        }

        @Test
        @DisplayName("should return correct string values")
        void shouldReturnCorrectStringValues() {
            assertThat(Message.MessageRole.USER.getValue()).isEqualTo("user");
            assertThat(Message.MessageRole.ASSISTANT.getValue()).isEqualTo("assistant");
            assertThat(Message.MessageRole.SYSTEM.getValue()).isEqualTo("system");
            assertThat(Message.MessageRole.TOOL.getValue()).isEqualTo("tool");
        }

        @Test
        @DisplayName("should toString as value")
        void shouldToStringAsValue() {
            assertThat(Message.MessageRole.USER.toString()).isEqualTo("user");
            assertThat(Message.MessageRole.ASSISTANT.toString()).isEqualTo("assistant");
        }

        @ParameterizedTest
        @ValueSource(strings = {"user", "USER", "User"})
        @DisplayName("should parse from string case-insensitively")
        void shouldParseFromString(String value) {
            assertThat(Message.MessageRole.fromString(value)).isEqualTo(Message.MessageRole.USER);
        }

        @Test
        @DisplayName("should parse all roles from string")
        void shouldParseAllRoles() {
            assertThat(Message.MessageRole.fromString("user")).isEqualTo(Message.MessageRole.USER);
            assertThat(Message.MessageRole.fromString("assistant")).isEqualTo(Message.MessageRole.ASSISTANT);
            assertThat(Message.MessageRole.fromString("system")).isEqualTo(Message.MessageRole.SYSTEM);
            assertThat(Message.MessageRole.fromString("tool")).isEqualTo(Message.MessageRole.TOOL);
        }

        @Test
        @DisplayName("should return null for null input")
        void shouldReturnNullForNull() {
            assertThat(Message.MessageRole.fromString(null)).isNull();
        }

        @Test
        @DisplayName("should throw for unknown role")
        void shouldThrowForUnknownRole() {
            assertThatThrownBy(() -> Message.MessageRole.fromString("unknown"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown message role: unknown");
        }
    }
}
