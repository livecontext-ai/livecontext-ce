package com.apimarketplace.conversation.service;

import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.entity.ToolResult;
import com.apimarketplace.conversation.repository.ToolResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ToolResultService Tests")
class ToolResultServiceTest {

    private static final String ORG_ID = "org-1";

    @Mock
    private ToolResultRepository repository;

    @Mock
    private StorageBreakdownService storageBreakdownService;

    @Mock
    private ConversationQueryService conversationQueryService;

    @Mock
    private ConversationDto conversationDto;

    private ToolResultService toolResultService;

    @BeforeEach
    void setUp() {
        toolResultService = new ToolResultService(repository, storageBreakdownService, conversationQueryService);
    }

    private ToolResult buildSavedResult(UUID id) {
        ToolResult result = new ToolResult("conv-1", "tenant-1", "my_tool", "call-1",
                true, 100L, "full content", null);
        result.setId(id);
        result.setCreatedAt(LocalDateTime.now());
        return result;
    }

    // ================================================================
    // save()
    // ================================================================

    @Nested
    @DisplayName("save()")
    class Save {

        @Test
        @DisplayName("should persist tool result and return saved entity")
        void shouldPersistToolResult() {
            UUID id = UUID.randomUUID();
            when(repository.save(any(ToolResult.class))).thenAnswer(inv -> {
                ToolResult r = inv.getArgument(0);
                r.setId(id);
                r.setCreatedAt(LocalDateTime.now());
                return r;
            });

            ToolResult saved = toolResultService.save("conv-1", "tenant-1", "my_tool",
                    "call-1", true, 100L, "content", null);

            assertThat(saved.getId()).isEqualTo(id);
            assertThat(saved.getConversationId()).isEqualTo("conv-1");
            assertThat(saved.getTenantId()).isEqualTo("tenant-1");
            assertThat(saved.getToolName()).isEqualTo("my_tool");
            assertThat(saved.isSuccess()).isTrue();

            verify(repository).save(any(ToolResult.class));
        }

        @Test
        @DisplayName("should persist tool result with metadata")
        void shouldPersistWithMetadata() {
            Map<String, Object> metadata = Map.of("iconSlug", "bolt", "displayToolName", "Lightning");

            when(repository.save(any(ToolResult.class))).thenAnswer(inv -> {
                ToolResult r = inv.getArgument(0);
                r.setId(UUID.randomUUID());
                r.setCreatedAt(LocalDateTime.now());
                return r;
            });

            ToolResult saved = toolResultService.save("conv-1", "tenant-1", "my_tool",
                    "call-1", true, 100L, "content", null, metadata);

            ArgumentCaptor<ToolResult> captor = ArgumentCaptor.forClass(ToolResult.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getMetadata()).containsEntry("iconSlug", "bolt");
            assertThat(captor.getValue().getMetadata()).containsEntry("displayToolName", "Lightning");
        }

        @Test
        @DisplayName("should persist tool result with null metadata")
        void shouldPersistWithNullMetadata() {
            when(repository.save(any(ToolResult.class))).thenAnswer(inv -> {
                ToolResult r = inv.getArgument(0);
                r.setId(UUID.randomUUID());
                r.setCreatedAt(LocalDateTime.now());
                return r;
            });

            ToolResult saved = toolResultService.save("conv-1", "tenant-1", "my_tool",
                    "call-1", true, 100L, "content", null, null);

            ArgumentCaptor<ToolResult> captor = ArgumentCaptor.forClass(ToolResult.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getMetadata()).isNull();
        }

        @Test
        @DisplayName("should persist tool result with error message")
        void shouldPersistWithErrorMessage() {
            when(repository.save(any(ToolResult.class))).thenAnswer(inv -> {
                ToolResult r = inv.getArgument(0);
                r.setId(UUID.randomUUID());
                r.setCreatedAt(LocalDateTime.now());
                return r;
            });

            ToolResult saved = toolResultService.save("conv-1", "tenant-1", "my_tool",
                    "call-1", false, 50L, null, "Connection timeout");

            ArgumentCaptor<ToolResult> captor = ArgumentCaptor.forClass(ToolResult.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().isSuccess()).isFalse();
            assertThat(captor.getValue().getErrorMessage()).isEqualTo("Connection timeout");
            assertThat(captor.getValue().getContentFull()).isNull();
        }

        @Test
        @DisplayName("should persist tool result with null content")
        void shouldPersistWithNullContent() {
            when(repository.save(any(ToolResult.class))).thenAnswer(inv -> {
                ToolResult r = inv.getArgument(0);
                r.setId(UUID.randomUUID());
                r.setCreatedAt(LocalDateTime.now());
                return r;
            });

            ToolResult saved = toolResultService.save("conv-1", "tenant-1", "my_tool",
                    "call-1", true, null, null, null);

            ArgumentCaptor<ToolResult> captor = ArgumentCaptor.forClass(ToolResult.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getContentFull()).isNull();
            assertThat(captor.getValue().getDurationMs()).isNull();
        }
    }

    // ================================================================
    // buildPreview() - ensures get_tool_result hint is always injected
    // ================================================================

    @Nested
    @DisplayName("buildPreview()")
    class BuildPreview {

        @Test
        @DisplayName("should return content as-is when under 3000 chars")
        void shouldReturnContentAsIsWhenShort() {
            String content = "Short content";
            String preview = ToolResultService.buildPreview(content, "call-123");
            assertThat(preview).isEqualTo(content);
        }

        @Test
        @DisplayName("should return content as-is when exactly 3000 chars")
        void shouldReturnContentAsIsWhenExactlyAtLimit() {
            String content = "x".repeat(3000);
            String preview = ToolResultService.buildPreview(content, "call-123");
            assertThat(preview).isEqualTo(content);
        }

        @Test
        @DisplayName("should truncate and inject get_tool_result hint when over 3000 chars")
        void shouldTruncateWithHint() {
            String content = "x".repeat(5000);
            String preview = ToolResultService.buildPreview(content, "call-123");

            assertThat(preview).hasSize(3000 + "...[truncated, use get_tool_result(tool_call_id=\"call-123\") for full content]".length());
            assertThat(preview).startsWith("x".repeat(3000));
            assertThat(preview).contains("get_tool_result");
            assertThat(preview).contains("call-123");
        }

        @Test
        @DisplayName("should use ??? placeholder when toolCallId is null")
        void shouldUsePlaceholderWhenToolCallIdNull() {
            String content = "x".repeat(5000);
            String preview = ToolResultService.buildPreview(content, null);

            assertThat(preview).contains("get_tool_result(tool_call_id=\"???\")");
        }

        @Test
        @DisplayName("should return null when content is null")
        void shouldReturnNullWhenContentNull() {
            String preview = ToolResultService.buildPreview(null, "call-123");
            assertThat(preview).isNull();
        }

        @Test
        @DisplayName("should preserve exact 3000-char prefix of original content")
        void shouldPreserveExactPrefix() {
            String prefix = "HEADER_";
            String content = prefix + "x".repeat(5000);
            String preview = ToolResultService.buildPreview(content, "call-1");

            assertThat(preview).startsWith(content.substring(0, 3000));
        }

        @Test
        @DisplayName("hint should never be itself truncated")
        void hintShouldNeverBeTruncated() {
            // Even with very long toolCallId, the hint must be complete
            String longCallId = "call_" + "a".repeat(200);
            String content = "x".repeat(10000);
            String preview = ToolResultService.buildPreview(content, longCallId);

            String expectedHint = "...[truncated, use get_tool_result(tool_call_id=\"" + longCallId + "\") for full content]";
            assertThat(preview).endsWith(expectedHint);
        }

        @Test
        @DisplayName("preview is always set when saving via ToolResultService")
        void previewAlwaysSetOnSave() {
            String largeContent = "x".repeat(5000);

            when(repository.save(any(ToolResult.class))).thenAnswer(inv -> {
                ToolResult r = inv.getArgument(0);
                r.setId(UUID.randomUUID());
                r.setCreatedAt(LocalDateTime.now());
                return r;
            });

            toolResultService.save("conv-1", "tenant-1", "web_search",
                    "call-ws-1", true, 200L, largeContent, null);

            ArgumentCaptor<ToolResult> captor = ArgumentCaptor.forClass(ToolResult.class);
            verify(repository).save(captor.capture());

            ToolResult saved = captor.getValue();
            assertThat(saved.getContentPreview()).isNotNull();
            assertThat(saved.getContentPreview()).contains("get_tool_result");
            assertThat(saved.getContentPreview()).contains("call-ws-1");
            assertThat(saved.getContentFull()).isEqualTo(largeContent);
        }

        @Test
        @DisplayName("getContentForHistory returns preview when available")
        void getContentForHistoryReturnsPreview() {
            ToolResult result = new ToolResult("conv-1", "tenant-1", "web_search", "call-1",
                    true, 100L, "x".repeat(5000), null);
            result.setContentPreview(ToolResultService.buildPreview("x".repeat(5000), "call-1"));

            String historyContent = result.getContentForHistory();
            assertThat(historyContent).contains("get_tool_result");
            assertThat(historyContent).hasSize(3000 + "...[truncated, use get_tool_result(tool_call_id=\"call-1\") for full content]".length());
        }

        @Test
        @DisplayName("getContentForHistory falls back to contentFull when no preview")
        void getContentForHistoryFallsBackToFull() {
            ToolResult result = new ToolResult("conv-1", "tenant-1", "web_search", "call-1",
                    true, 100L, "full content here", null);
            // Don't set preview

            String historyContent = result.getContentForHistory();
            assertThat(historyContent).isEqualTo("full content here");
        }
    }

    // ================================================================
    // getById()
    // ================================================================

    @Nested
    @DisplayName("getById()")
    class GetById {

        @Test
        @DisplayName("should return result when found and parent conversation accessible")
        void shouldReturnResultWhenFound() {
            UUID id = UUID.randomUUID();
            ToolResult result = buildSavedResult(id);

            when(repository.findById(id)).thenReturn(Optional.of(result));
            when(conversationQueryService.getConversationById("conv-1", "tenant-1", ORG_ID))
                    .thenReturn(Optional.of(conversationDto));

            Optional<ToolResult> found = toolResultService.getById(id, "tenant-1", ORG_ID);

            assertThat(found).isPresent();
            assertThat(found.get().getId()).isEqualTo(id);
        }

        @Test
        @DisplayName("should return empty when parent conversation not visible in caller scope")
        void shouldReturnEmptyWhenConversationScopeMismatch() {
            UUID id = UUID.randomUUID();
            ToolResult result = buildSavedResult(id);

            when(repository.findById(id)).thenReturn(Optional.of(result));
            when(conversationQueryService.getConversationById("conv-1", "tenant-1", "other-org"))
                    .thenReturn(Optional.empty());

            Optional<ToolResult> found = toolResultService.getById(id, "tenant-1", "other-org");

            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("should return empty when ID does not exist")
        void shouldReturnEmptyWhenIdDoesNotExist() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.empty());

            Optional<ToolResult> found = toolResultService.getById(id, "tenant-1", ORG_ID);

            assertThat(found).isEmpty();
            verifyNoInteractions(conversationQueryService);
        }
    }

    // ================================================================
    // getByToolCallId()
    // ================================================================

    @Nested
    @DisplayName("getByToolCallId()")
    class GetByToolCallId {

        @Test
        @DisplayName("should return result by tool call ID when parent conversation accessible")
        void shouldReturnByToolCallId() {
            ToolResult result = buildSavedResult(UUID.randomUUID());
            when(repository.findByToolCallId("call-1")).thenReturn(Optional.of(result));
            when(conversationQueryService.getConversationById("conv-1", "tenant-1", ORG_ID))
                    .thenReturn(Optional.of(conversationDto));

            Optional<ToolResult> found = toolResultService.getByToolCallId("call-1", "tenant-1", ORG_ID);

            assertThat(found).isPresent();
            assertThat(found.get().getToolCallId()).isEqualTo("call-1");
        }

        @Test
        @DisplayName("should return empty when tool call ID not found")
        void shouldReturnEmptyWhenNotFound() {
            when(repository.findByToolCallId("unknown")).thenReturn(Optional.empty());

            Optional<ToolResult> found = toolResultService.getByToolCallId("unknown", "tenant-1", ORG_ID);

            assertThat(found).isEmpty();
            verifyNoInteractions(conversationQueryService);
        }

        @Test
        @DisplayName("should return empty when parent conversation not visible in caller scope")
        void shouldReturnEmptyWhenConversationScopeMismatch() {
            ToolResult result = buildSavedResult(UUID.randomUUID());
            when(repository.findByToolCallId("call-1")).thenReturn(Optional.of(result));
            when(conversationQueryService.getConversationById("conv-1", "tenant-1", "other-org"))
                    .thenReturn(Optional.empty());

            Optional<ToolResult> found = toolResultService.getByToolCallId("call-1", "tenant-1", "other-org");

            assertThat(found).isEmpty();
        }
    }

    // ================================================================
    // getByConversation()
    // ================================================================

    @Nested
    @DisplayName("getByConversation()")
    class GetByConversation {

        @Test
        @DisplayName("should return all results for conversation when caller can see parent")
        void shouldReturnAllForConversation() {
            ToolResult r1 = buildSavedResult(UUID.randomUUID());
            ToolResult r2 = buildSavedResult(UUID.randomUUID());

            when(conversationQueryService.getConversationById("conv-1", "tenant-1", ORG_ID))
                    .thenReturn(Optional.of(conversationDto));
            when(repository.findByConversationIdOrderByCreatedAtAsc("conv-1"))
                    .thenReturn(List.of(r1, r2));

            List<ToolResult> results = toolResultService.getByConversation("conv-1", "tenant-1", ORG_ID);

            assertThat(results).hasSize(2);
        }

        @Test
        @DisplayName("should return empty list when no results exist")
        void shouldReturnEmptyList() {
            when(conversationQueryService.getConversationById("conv-empty", "tenant-1", ORG_ID))
                    .thenReturn(Optional.of(conversationDto));
            when(repository.findByConversationIdOrderByCreatedAtAsc("conv-empty"))
                    .thenReturn(List.of());

            List<ToolResult> results = toolResultService.getByConversation("conv-empty", "tenant-1", ORG_ID);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("should return empty list when parent conversation not visible in caller scope")
        void shouldReturnEmptyWhenConversationScopeMismatch() {
            when(conversationQueryService.getConversationById("conv-1", "tenant-1", "other-org"))
                    .thenReturn(Optional.empty());

            List<ToolResult> results = toolResultService.getByConversation("conv-1", "tenant-1", "other-org");

            assertThat(results).isEmpty();
            verifyNoInteractions(repository);
        }
    }
}
