package com.apimarketplace.orchestrator.services.triggers;

import com.apimarketplace.orchestrator.domain.workflow.ChatMatchConfig;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ChatTriggerResolver")
class ChatTriggerResolverTest {

    private ChatTriggerResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ChatTriggerResolver();
        // TriggerUserResolver returns "" when authClient is null (test mode), so a real instance is safe.
        ReflectionTestUtils.setField(resolver, "triggerUserResolver", new TriggerUserResolver());
    }

    private Trigger chatTrigger() {
        return new Trigger("trigger-1", "chat", null, "chat", null, null);
    }

    private Trigger chatTriggerWithMatch(ChatMatchConfig matchConfig) {
        return new Trigger("trigger-1", "chat", null, "chat", null, matchConfig);
    }

    @Nested
    @DisplayName("canHandle()")
    class CanHandleTests {
        @Test
        @DisplayName("Should handle 'chat' type")
        void shouldHandleChatType() {
            assertTrue(resolver.canHandle("chat"));
            assertTrue(resolver.canHandle("Chat"));
            assertTrue(resolver.canHandle("CHAT"));
        }

        @Test
        @DisplayName("Should not handle other types")
        void shouldNotHandleOtherTypes() {
            assertFalse(resolver.canHandle("webhook"));
            assertFalse(resolver.canHandle("form"));
            assertFalse(resolver.canHandle("manual"));
        }
    }

    @Nested
    @DisplayName("conversation_id passthrough")
    class ConversationIdTests {
        @Test
        @DisplayName("Should include conversation_id in output when camelCase conversationId present in inputs")
        void shouldPassThroughConversationId() {
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("message", "Hello");
            inputs.put("conversationId", "conv-abc-123");

            Map<String, Object> result = resolver.resolve(chatTrigger(), "tenant-1", inputs);

            assertEquals("conv-abc-123", result.get("conversation_id"));
            assertNull(result.get("conversationId"), "camelCase must not leak to output");
            assertEquals("Hello", result.get("message"));
        }

        @Test
        @DisplayName("Should include conversation_id in output when snake_case conversation_id present in inputs")
        void shouldPassThroughConversationIdSnakeCase() {
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("message", "Hello");
            inputs.put("conversation_id", "conv-snake-456");

            Map<String, Object> result = resolver.resolve(chatTrigger(), "tenant-1", inputs);

            assertEquals("conv-snake-456", result.get("conversation_id"));
        }

        @Test
        @DisplayName("Should not include conversation_id when absent from inputs")
        void shouldNotIncludeConversationIdWhenAbsent() {
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("message", "Hello");

            Map<String, Object> result = resolver.resolve(chatTrigger(), "tenant-1", inputs);

            assertNull(result.get("conversation_id"));
        }

        @Test
        @DisplayName("Should not include conversation_id when inputs are null")
        void shouldNotIncludeConversationIdWhenInputsNull() {
            Map<String, Object> result = resolver.resolve(chatTrigger(), "tenant-1", null);

            assertNull(result.get("conversation_id"));
        }

        @Test
        @DisplayName("Should preserve conversation_id alongside matched message data")
        void shouldPreserveConversationIdWithMatchedMessage() {
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("message", "Hello world");
            inputs.put("conversationId", "conv-xyz");

            Map<String, Object> result = resolver.resolve(chatTrigger(), "tenant-1", inputs);

            assertEquals("conv-xyz", result.get("conversation_id"));
            assertEquals(true, result.get("matched"));
            assertEquals("Hello world", result.get("message"));
            assertEquals("success", result.get("status"));
        }

        @Test
        @DisplayName("Should preserve conversation_id even when message does not match")
        void shouldPreserveConversationIdWhenUnmatched() {
            ChatMatchConfig equalsConfig = new ChatMatchConfig(
                ChatMatchConfig.MatchType.EQUALS, "specific", false, false, false
            );
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("message", "different");
            inputs.put("conversationId", "conv-xyz");

            Map<String, Object> result = resolver.resolve(
                chatTriggerWithMatch(equalsConfig), "tenant-1", inputs
            );

            assertEquals("conv-xyz", result.get("conversation_id"));
            assertEquals(false, result.get("matched"));
            assertEquals("no_match", result.get("status"));
        }
    }

    @Nested
    @DisplayName("attachments flattening")
    class AttachmentTests {

        private Map<String, Object> fileRef(String name, String mimeType) {
            return Map.of(
                "_type", "file",
                "path", "tenant1/wf1/run1/trigger/" + name,
                "name", name,
                "mimeType", mimeType,
                "size", 1024
            );
        }

        @Test
        @DisplayName("Should include flattened attachments in output when present")
        void shouldFlattenAttachments() {
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("message", "See attached");
            inputs.put("attachments", List.of(fileRef("report.pdf", "application/pdf")));

            Map<String, Object> result = resolver.resolve(chatTrigger(), "tenant-1", inputs);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> attachments = (List<Map<String, Object>>) result.get("attachments");
            assertNotNull(attachments);
            assertEquals(1, attachments.size());
            Map<String, Object> first = attachments.get(0);
            // Canonical FileRef shape only (PR2 2026-05-15): { _type:'file', path, name, mimeType, size }
            assertEquals("file", first.get("_type"));
            assertEquals("tenant1/wf1/run1/trigger/report.pdf", first.get("path"));
            assertEquals("report.pdf", first.get("name"));
            assertEquals("application/pdf", first.get("mimeType"));
            assertEquals(1024, first.get("size"));
            // No legacy flat fields - clean break aligns with the 4 file-producer nodes.
            assertFalse(first.containsKey("file_url"),     "PR2 clean break: file_url must NOT be emitted on attachments");
            assertFalse(first.containsKey("file_name"),    "PR2 clean break: file_name must NOT be emitted on attachments");
            assertFalse(first.containsKey("file_size"),    "PR2 clean break: file_size must NOT be emitted on attachments");
            assertFalse(first.containsKey("content_type"), "PR2 clean break: content_type must NOT be emitted on attachments");
            // Also in data item
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
            assertNotNull(data);
            assertEquals(1, data.size());
            assertNotNull(data.get(0).get("attachments"));
        }

        @Test
        @DisplayName("Should handle multiple attachments")
        void shouldHandleMultipleAttachments() {
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("message", "Files");
            inputs.put("attachments", List.of(
                fileRef("a.pdf", "application/pdf"),
                fileRef("b.png", "image/png")
            ));

            Map<String, Object> result = resolver.resolve(chatTrigger(), "tenant-1", inputs);

            @SuppressWarnings("unchecked")
            List<?> attachments = (List<?>) result.get("attachments");
            assertEquals(2, attachments.size());
        }

        @Test
        @DisplayName("Should not include attachments when absent")
        void shouldNotIncludeAttachmentsWhenAbsent() {
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("message", "No files");

            Map<String, Object> result = resolver.resolve(chatTrigger(), "tenant-1", inputs);

            assertNull(result.get("attachments"));
        }

        @Test
        @DisplayName("Should not include attachments when empty list")
        void shouldNotIncludeAttachmentsWhenEmpty() {
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("message", "Empty");
            inputs.put("attachments", List.of());

            Map<String, Object> result = resolver.resolve(chatTrigger(), "tenant-1", inputs);

            assertNull(result.get("attachments"));
        }

        /**
         * Regression: ChatTriggerResolver.flattenAttachments previously normalised ANY Map in the
         * attachments list regardless of whether _type='file' was present. A plain Map field like
         * {path, name} without _type was silently treated as a FileRef and rewritten. The strict
         * contract requires _type='file'; without it the raw map must be passed through unchanged
         * so downstream nodes see the user-uploaded shape verbatim.
         */
        @Test
        @DisplayName("attachmentWithoutTypeIsLeftAlone")
        void attachmentWithoutTypeIsLeftAlone() {
            Map<String, Object> noTypeAttachment = new HashMap<>();
            noTypeAttachment.put("path", "some/path/doc.pdf");
            noTypeAttachment.put("name", "doc.pdf");
            // no _type key

            Map<String, Object> inputs = new HashMap<>();
            inputs.put("message", "see attached");
            inputs.put("attachments", List.of(noTypeAttachment));

            Map<String, Object> result = resolver.resolve(chatTrigger(), "tenant-1", inputs);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> attachments = (List<Map<String, Object>>) result.get("attachments");
            assertNotNull(attachments, "attachments list must be present");
            assertEquals(1, attachments.size());
            Map<String, Object> att = attachments.get(0);
            // Raw map must be passed through - NOT rewritten to canonical FileRef shape
            assertFalse(att.containsKey("mimeType"), "mimeType must NOT be synthesised for non-FileRef map");
            assertFalse(att.containsKey("size"), "size must NOT be synthesised for non-FileRef map");
            // Original keys must still be present
            assertEquals("some/path/doc.pdf", att.get("path"));
            assertEquals("doc.pdf", att.get("name"));
        }

        @Test
        @DisplayName("Should not include attachments in dataItem when unmatched")
        void shouldNotIncludeAttachmentsWhenUnmatched() {
            ChatMatchConfig equalsConfig = new ChatMatchConfig(
                ChatMatchConfig.MatchType.EQUALS, "specific", false, false, false
            );
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("message", "different");
            inputs.put("attachments", List.of(fileRef("a.pdf", "application/pdf")));

            Map<String, Object> result = resolver.resolve(
                chatTriggerWithMatch(equalsConfig), "tenant-1", inputs
            );

            // Top-level still has flattened attachments (they were sent)
            assertNotNull(result.get("attachments"));
            // But data is empty (no match)
            @SuppressWarnings("unchecked")
            List<?> data = (List<?>) result.get("data");
            assertTrue(data.isEmpty());
        }
    }

    @Nested
    @DisplayName("resolve() canonical output shape - regression guard")
    class CanonicalShapeTests {

        /**
         * Regression: resolver previously emitted camelCase keys (extractedMessage, matchType,
         * matchValue, conversationId) causing SpEL templates like
         * {{trigger:chat.output.extracted_message}} to resolve null at runtime.
         * Attachments were raw FileRef maps instead of flattened proxy-URL objects.
         */
        @Test
        @DisplayName("resolverEmitsCanonicalSnakeCaseShape")
        void resolverEmitsCanonicalSnakeCaseShape() {
            ChatMatchConfig startsWithConfig = new ChatMatchConfig(
                ChatMatchConfig.MatchType.STARTS_WITH, "/cmd", false, true, false
            );
            Map<String, Object> fileRef = new HashMap<>();
            fileRef.put("_type", "file");
            fileRef.put("path", "t1/wf1/run1/trigger/doc.pdf");
            fileRef.put("name", "doc.pdf");
            fileRef.put("mimeType", "application/pdf");
            fileRef.put("size", 2048);

            Map<String, Object> inputs = new HashMap<>();
            inputs.put("message", "/cmd hello");
            inputs.put("conversationId", "conv-99");
            inputs.put("attachments", List.of(fileRef));

            Map<String, Object> result = resolver.resolve(
                chatTriggerWithMatch(startsWithConfig), "tenant-1", inputs);

            // Snake_case keys must be present
            assertNotNull(result.get("extracted_message"), "extracted_message must be present");
            assertNotNull(result.get("match_type"), "match_type must be present");
            assertEquals("conv-99", result.get("conversation_id"), "conversation_id must be present");

            // camelCase keys must NOT be present (would cause runtime SpEL null)
            assertNull(result.get("extractedMessage"), "camelCase extractedMessage must not leak");
            assertNull(result.get("matchType"), "camelCase matchType must not leak");
            assertNull(result.get("matchValue"), "camelCase matchValue must not leak");
            assertNull(result.get("conversationId"), "camelCase conversationId must not leak");

            // Attachments must be normalised to the canonical FileRef shape (PR2 2026-05-15)
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> attachments = (List<Map<String, Object>>) result.get("attachments");
            assertNotNull(attachments, "attachments must be present");
            assertEquals(1, attachments.size());
            Map<String, Object> att = attachments.get(0);
            assertEquals("file", att.get("_type"));
            assertEquals("t1/wf1/run1/trigger/doc.pdf", att.get("path"));
            assertEquals("doc.pdf", att.get("name"));
            assertEquals("application/pdf", att.get("mimeType"));
            assertEquals(2048, att.get("size"));
            assertFalse(att.containsKey("file_url"),     "PR2 clean break: file_url must NOT leak on chat attachments");
            assertFalse(att.containsKey("file_name"),    "PR2 clean break: file_name must NOT leak on chat attachments");
            assertFalse(att.containsKey("file_size"),    "PR2 clean break: file_size must NOT leak on chat attachments");
            assertFalse(att.containsKey("content_type"), "PR2 clean break: content_type must NOT leak on chat attachments");

            // match_value present when set
            assertEquals("starts_with", result.get("match_type"));
        }
    }

    @Nested
    @DisplayName("resolve() basic behavior")
    class ResolveBasicTests {
        @Test
        @DisplayName("Should include standard fields in output")
        void shouldIncludeStandardFields() {
            Map<String, Object> inputs = Map.of("message", "Test message");

            Map<String, Object> result = resolver.resolve(chatTrigger(), "tenant-1", inputs);

            assertEquals("trigger-1", result.get("triggerId"));
            assertEquals("tenant-1", result.get("tenantId"));
            assertEquals("chat", result.get("type"));
            assertEquals("chat", result.get("source"));
            // Canonical snake_case field name (see ChatTriggerResolver#resolve and CLAUDE.md
            // "Node Output Schema - 3-Way Alignment").
            assertNotNull(result.get("triggered_at"));
        }

        @Test
        @DisplayName("Should match any message with default config")
        void shouldMatchAnyMessageByDefault() {
            Map<String, Object> inputs = Map.of("message", "Any message");

            Map<String, Object> result = resolver.resolve(chatTrigger(), "tenant-1", inputs);

            assertEquals(true, result.get("matched"));
            assertEquals("success", result.get("status"));
            assertEquals("Any message", result.get("message"));
        }

        @Test
        @DisplayName("Should handle empty message")
        void shouldHandleEmptyMessage() {
            Map<String, Object> inputs = Map.of("message", "");

            Map<String, Object> result = resolver.resolve(chatTrigger(), "tenant-1", inputs);

            assertEquals("", result.get("message"));
        }

        @Test
        @DisplayName("Should handle null inputs gracefully")
        void shouldHandleNullInputs() {
            Map<String, Object> result = resolver.resolve(chatTrigger(), "tenant-1", null);

            assertNotNull(result);
            assertEquals("", result.get("message"));
        }
    }
}
