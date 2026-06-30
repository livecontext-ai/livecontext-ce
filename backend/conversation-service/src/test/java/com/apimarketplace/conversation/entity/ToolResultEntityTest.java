package com.apimarketplace.conversation.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolResult Entity")
class ToolResultEntityTest {

    @Nested
    @DisplayName("Constructors")
    class Constructors {

        @Test
        @DisplayName("should create with default constructor")
        void shouldCreateWithDefault() {
            ToolResult result = new ToolResult();
            assertThat(result.getId()).isNull();
            assertThat(result.getConversationId()).isNull();
        }

        @Test
        @DisplayName("should create with all-args constructor")
        void shouldCreateWithAllArgs() {
            ToolResult result = new ToolResult(
                    "conv-1", "tenant-1", "my_tool", "call-1",
                    true, 150L, "result content", null);

            assertThat(result.getConversationId()).isEqualTo("conv-1");
            assertThat(result.getTenantId()).isEqualTo("tenant-1");
            assertThat(result.getToolName()).isEqualTo("my_tool");
            assertThat(result.getToolCallId()).isEqualTo("call-1");
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getDurationMs()).isEqualTo(150L);
            assertThat(result.getContentFull()).isEqualTo("result content");
            assertThat(result.getErrorMessage()).isNull();
        }

        @Test
        @DisplayName("should create error result with constructor")
        void shouldCreateErrorResult() {
            ToolResult result = new ToolResult(
                    "conv-1", "tenant-1", "my_tool", "call-1",
                    false, 50L, null, "timeout error");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getContentFull()).isNull();
            assertThat(result.getErrorMessage()).isEqualTo("timeout error");
        }
    }

    @Nested
    @DisplayName("Getters and setters")
    class GettersSetters {

        @Test
        @DisplayName("should get and set all fields")
        void shouldGetAndSetAllFields() {
            ToolResult result = new ToolResult();
            UUID id = UUID.randomUUID();
            LocalDateTime now = LocalDateTime.now();

            result.setId(id);
            result.setConversationId("conv-1");
            result.setTenantId("tenant-1");
            result.setToolName("my_tool");
            result.setToolCallId("call-1");
            result.setSuccess(true);
            result.setDurationMs(200L);
            result.setContentFull("full content");
            result.setErrorMessage("error msg");
            result.setCreatedAt(now);
            result.setMetadata(Map.of("key", "value"));

            assertThat(result.getId()).isEqualTo(id);
            assertThat(result.getConversationId()).isEqualTo("conv-1");
            assertThat(result.getTenantId()).isEqualTo("tenant-1");
            assertThat(result.getToolName()).isEqualTo("my_tool");
            assertThat(result.getToolCallId()).isEqualTo("call-1");
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getDurationMs()).isEqualTo(200L);
            assertThat(result.getContentFull()).isEqualTo("full content");
            assertThat(result.getErrorMessage()).isEqualTo("error msg");
            assertThat(result.getCreatedAt()).isEqualTo(now);
            assertThat(result.getMetadata()).containsEntry("key", "value");
        }

        @Test
        @DisplayName("should handle null metadata")
        void shouldHandleNullMetadata() {
            ToolResult result = new ToolResult();
            result.setMetadata(null);
            assertThat(result.getMetadata()).isNull();
        }

        @Test
        @DisplayName("should handle null durationMs")
        void shouldHandleNullDurationMs() {
            ToolResult result = new ToolResult();
            result.setDurationMs(null);
            assertThat(result.getDurationMs()).isNull();
        }
    }
}
