package com.apimarketplace.agent.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Message record.
 */
@DisplayName("Message")
class MessageTest {

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("system() should create system message")
        void shouldCreateSystemMessage() {
            Message msg = Message.system("You are helpful");

            assertThat(msg.role()).isEqualTo(Message.Role.SYSTEM);
            assertThat(msg.content()).isEqualTo("You are helpful");
            assertThat(msg.toolCallId()).isNull();
            assertThat(msg.toolCalls()).isNull();
        }

        @Test
        @DisplayName("user() should create user message")
        void shouldCreateUserMessage() {
            Message msg = Message.user("Hello");

            assertThat(msg.role()).isEqualTo(Message.Role.USER);
            assertThat(msg.content()).isEqualTo("Hello");
        }

        @Test
        @DisplayName("assistant() should create assistant message")
        void shouldCreateAssistantMessage() {
            Message msg = Message.assistant("Hi there!");

            assertThat(msg.role()).isEqualTo(Message.Role.ASSISTANT);
            assertThat(msg.content()).isEqualTo("Hi there!");
        }

        @Test
        @DisplayName("assistantWithToolCalls() should create assistant message with tool calls")
        void shouldCreateAssistantWithToolCalls() {
            ToolCall tc = ToolCall.builder().id("tc-1").toolName("search").build();
            Message msg = Message.assistantWithToolCalls("Searching...", List.of(tc));

            assertThat(msg.role()).isEqualTo(Message.Role.ASSISTANT);
            assertThat(msg.content()).isEqualTo("Searching...");
            assertThat(msg.toolCalls()).hasSize(1);
            assertThat(msg.toolCalls().get(0).toolName()).isEqualTo("search");
        }

        @Test
        @DisplayName("toolResult() should create tool response message")
        void shouldCreateToolResult() {
            Message msg = Message.toolResult("tc-1", "search", "{\"results\":[]}");

            assertThat(msg.role()).isEqualTo(Message.Role.TOOL);
            assertThat(msg.content()).isEqualTo("{\"results\":[]}");
            assertThat(msg.toolCallId()).isEqualTo("tc-1");
            assertThat(msg.toolName()).isEqualTo("search");
        }

        @Test
        @DisplayName("userWithAttachments() should create user message with attachments")
        void shouldCreateUserWithAttachments() {
            MessageAttachment att = MessageAttachment.image(
                    new byte[]{1, 2, 3}, "image/png", "photo.png");
            Message msg = Message.userWithAttachments("See this image", List.of(att));

            assertThat(msg.role()).isEqualTo(Message.Role.USER);
            assertThat(msg.content()).isEqualTo("See this image");
            assertThat(msg.attachments()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("hasAttachments()")
    class HasAttachmentsTests {

        @Test
        @DisplayName("should return true when attachments exist")
        void shouldReturnTrueWithAttachments() {
            MessageAttachment att = MessageAttachment.image(
                    new byte[]{1}, "image/png", "img.png");
            Message msg = Message.userWithAttachments("text", List.of(att));

            assertThat(msg.hasAttachments()).isTrue();
        }

        @Test
        @DisplayName("should return false when attachments is null")
        void shouldReturnFalseWhenNull() {
            Message msg = Message.user("Hello");

            assertThat(msg.hasAttachments()).isFalse();
        }

        @Test
        @DisplayName("should return false when attachments is empty")
        void shouldReturnFalseWhenEmpty() {
            Message msg = Message.builder()
                    .role(Message.Role.USER)
                    .content("text")
                    .attachments(List.of())
                    .build();

            assertThat(msg.hasAttachments()).isFalse();
        }
    }

    @Nested
    @DisplayName("Role enum")
    class RoleTests {

        @Test
        @DisplayName("should have 4 roles")
        void shouldHaveFourRoles() {
            assertThat(Message.Role.values()).hasSize(4);
        }

        @Test
        @DisplayName("should contain expected roles")
        void shouldContainExpectedRoles() {
            assertThat(Message.Role.values())
                    .containsExactly(
                            Message.Role.SYSTEM,
                            Message.Role.USER,
                            Message.Role.ASSISTANT,
                            Message.Role.TOOL
                    );
        }
    }
}
