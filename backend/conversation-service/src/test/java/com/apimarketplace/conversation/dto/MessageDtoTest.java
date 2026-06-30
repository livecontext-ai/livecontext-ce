package com.apimarketplace.conversation.dto;

import com.apimarketplace.conversation.entity.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MessageDto")
class MessageDtoTest {

    @Nested
    @DisplayName("Constructors")
    class Constructors {

        @Test
        @DisplayName("should create with default constructor")
        void shouldCreateWithDefault() {
            MessageDto dto = new MessageDto();
            assertThat(dto.getRole()).isNull();
            assertThat(dto.getContent()).isNull();
        }

        @Test
        @DisplayName("should create with role and content")
        void shouldCreateWithRoleAndContent() {
            MessageDto dto = new MessageDto("user", "Hello");

            assertThat(dto.getRole()).isEqualTo("user");
            assertThat(dto.getContent()).isEqualTo("Hello");
        }

        @Test
        @DisplayName("should create with all fields constructor")
        void shouldCreateWithAllFields() {
            MessageDto dto = new MessageDto("assistant", "Reply", "[{\"id\":\"call_1\"}]", "gpt-4", "2024-01-01");

            assertThat(dto.getRole()).isEqualTo("assistant");
            assertThat(dto.getContent()).isEqualTo("Reply");
            assertThat(dto.getToolCalls()).isEqualTo("[{\"id\":\"call_1\"}]");
            assertThat(dto.getModel()).isEqualTo("gpt-4");
            assertThat(dto.getTimestamp()).isEqualTo("2024-01-01");
        }
    }

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethods {

        @Test
        @DisplayName("toolResult should create TOOL role message")
        void shouldCreateToolResult() {
            MessageDto dto = MessageDto.toolResult("call-1", "my_tool", "result content");

            assertThat(dto.getRole()).isEqualTo("tool");
            assertThat(dto.getToolCallId()).isEqualTo("call-1");
            assertThat(dto.getToolName()).isEqualTo("my_tool");
            assertThat(dto.getContent()).isEqualTo("result content");
        }

        @Test
        @DisplayName("assistantWithToolCalls should create ASSISTANT role with tool calls")
        void shouldCreateAssistantWithToolCalls() {
            MessageDto dto = MessageDto.assistantWithToolCalls("[{\"id\":\"call_1\"}]", "gpt-4");

            assertThat(dto.getRole()).isEqualTo("assistant");
            assertThat(dto.getToolCalls()).isEqualTo("[{\"id\":\"call_1\"}]");
            assertThat(dto.getModel()).isEqualTo("gpt-4");
        }
    }

    @Nested
    @DisplayName("isToolResult")
    class IsToolResult {

        @Test
        @DisplayName("should return true for tool role with toolCallId")
        void shouldReturnTrueForToolRole() {
            MessageDto dto = MessageDto.toolResult("call-1", "my_tool", "result");

            assertThat(dto.isToolResult()).isTrue();
        }

        @Test
        @DisplayName("should return false for user role")
        void shouldReturnFalseForUserRole() {
            MessageDto dto = new MessageDto("user", "Hello");

            assertThat(dto.isToolResult()).isFalse();
        }

        @Test
        @DisplayName("should return false when toolCallId is null")
        void shouldReturnFalseWhenNoToolCallId() {
            MessageDto dto = new MessageDto();
            dto.setRole("tool");
            // no toolCallId

            assertThat(dto.isToolResult()).isFalse();
        }
    }

    @Nested
    @DisplayName("hasToolCalls")
    class HasToolCalls {

        @Test
        @DisplayName("should return true for non-empty toolCalls")
        void shouldReturnTrueForNonEmpty() {
            MessageDto dto = new MessageDto();
            dto.setToolCalls("[{\"id\":\"call_1\"}]");

            assertThat(dto.hasToolCalls()).isTrue();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "[]"})
        @DisplayName("should return false for null, empty, blank, or empty array")
        void shouldReturnFalseForEmptyValues(String toolCalls) {
            MessageDto dto = new MessageDto();
            dto.setToolCalls(toolCalls);

            assertThat(dto.hasToolCalls()).isFalse();
        }
    }

    @Nested
    @DisplayName("getRoleEnum and setRoleEnum")
    class RoleEnum {

        @Test
        @DisplayName("should convert string role to enum")
        void shouldConvertToEnum() {
            MessageDto dto = new MessageDto("user", "Hello");

            assertThat(dto.getRoleEnum()).isEqualTo(Message.MessageRole.USER);
        }

        @Test
        @DisplayName("should return null for null role")
        void shouldReturnNullForNullRole() {
            MessageDto dto = new MessageDto();

            assertThat(dto.getRoleEnum()).isNull();
        }

        @Test
        @DisplayName("should set role from enum")
        void shouldSetRoleFromEnum() {
            MessageDto dto = new MessageDto();
            dto.setRoleEnum(Message.MessageRole.ASSISTANT);

            assertThat(dto.getRole()).isEqualTo("assistant");
        }

        @Test
        @DisplayName("should set null when enum is null")
        void shouldSetNullWhenEnumIsNull() {
            MessageDto dto = new MessageDto();
            dto.setRoleEnum(null);

            assertThat(dto.getRole()).isNull();
        }
    }

    @Nested
    @DisplayName("All getters and setters")
    class AllGettersSetters {

        @Test
        @DisplayName("should get and set all fields")
        void shouldGetAndSetAllFields() {
            MessageDto dto = new MessageDto();
            dto.setId("msg-1");
            dto.setConversationId("conv-1");
            dto.setRole("user");
            dto.setContent("Hello");
            dto.setToolCalls("[{\"id\":\"call_1\"}]");
            dto.setToolCallId("call-1");
            dto.setToolName("my_tool");
            dto.setModel("gpt-4");
            dto.setAgentId("agent-1");
            dto.setTimestamp("2024-01-01");
            dto.setCreatedAt(LocalDateTime.of(2024, 1, 1, 0, 0));

            assertThat(dto.getId()).isEqualTo("msg-1");
            assertThat(dto.getConversationId()).isEqualTo("conv-1");
            assertThat(dto.getRole()).isEqualTo("user");
            assertThat(dto.getContent()).isEqualTo("Hello");
            assertThat(dto.getToolCalls()).isEqualTo("[{\"id\":\"call_1\"}]");
            assertThat(dto.getToolCallId()).isEqualTo("call-1");
            assertThat(dto.getToolName()).isEqualTo("my_tool");
            assertThat(dto.getModel()).isEqualTo("gpt-4");
            assertThat(dto.getAgentId()).isEqualTo("agent-1");
            assertThat(dto.getTimestamp()).isEqualTo("2024-01-01");
            assertThat(dto.getCreatedAt()).isEqualTo(LocalDateTime.of(2024, 1, 1, 0, 0));
        }
    }
}
