package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.AttachmentType;
import com.apimarketplace.agent.domain.MessageAttachment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pin-test for {@code AgentLoopService.appendFileRefNote} - the direct-API path's half of
 * the chat-attachment FileRef bridge (the bridge/CLI path's matching behavior lives in
 * {@code mcp/bridge/lib/attachmentPrompt.mjs}'s {@code fileRefNotes} block, tested in
 * {@code attachmentPrompt.test.mjs}; keep the two in parity).
 *
 * <p>Before this fix, an attachment reached the model only as a vision/text block: the
 * model could SEE an attached image but had nothing it could pass to a tool argument that
 * expects a whole file object (e.g. {@code generation}'s {@code input_image}, which requires
 * "the whole file object another tool returned"). {@link MessageAttachment#fileRef()} is
 * null for a legacy DB-blob attachment (no durable S3 key to reference), so this must be a
 * no-op in that case - never inventing a key that would fail on first use.
 */
@DisplayName("AgentLoopService - chat attachment FileRef note (input_image unreachable-from-chat regression)")
class AgentLoopServiceFileRefNoteTest {

    /**
     * Reflectively access the private static helper. A pure function of
     * (prompt, attachments) - best tested directly, same rationale as
     * {@link AgentLoopServiceHistoryFallbackTest}.
     */
    private static String invoke(String userPrompt, List<MessageAttachment> attachments) throws Exception {
        Method m = AgentLoopService.class
            .getDeclaredMethod("appendFileRefNote", String.class, List.class);
        m.setAccessible(true);
        return (String) m.invoke(null, userPrompt, attachments);
    }

    private static MessageAttachment withFileRef(String fileName, Map<String, Object> fileRef) {
        return MessageAttachment.builder()
            .type(AttachmentType.IMAGE)
            .mimeType("image/png")
            .fileName(fileName)
            .fileRef(fileRef)
            .build();
    }

    private static Map<String, Object> canonicalFileRef() {
        return Map.of(
            "_type", "file",
            "path", "user-1/general/chat/photo.jpg",
            "name", "photo.jpg",
            "mimeType", "image/jpeg",
            "size", 1234,
            "id", "3f1b2c3d-4e5f-6789-abcd-ef0123456789"
        );
    }

    @Test
    @DisplayName("attachment WITH a FileRef -> prompt gains a note naming the file and its verbatim JSON")
    void appendsNoteWhenFileRefPresent() throws Exception {
        MessageAttachment att = withFileRef("photo.jpg", canonicalFileRef());

        String result = invoke("Edit this photo", List.of(att));

        assertThat(result).startsWith("Edit this photo");
        assertThat(result).contains("photo.jpg");
        assertThat(result).contains("input_image");
        assertThat(result).contains("\"_type\":\"file\"");
        assertThat(result).contains("\"path\":\"user-1/general/chat/photo.jpg\"");
    }

    @Test
    @DisplayName("attachment with fileRef=null (legacy DB-blob row) -> prompt unchanged, no note, no fabricated key")
    void noNoteWhenFileRefAbsent() throws Exception {
        MessageAttachment att = withFileRef("old.png", null);

        String result = invoke("Look at this", List.of(att));

        assertThat(result).isEqualTo("Look at this");
    }

    @Test
    @DisplayName("no attachments -> prompt unchanged")
    void noNoteWhenNoAttachments() throws Exception {
        assertThat(invoke("Hello", List.of())).isEqualTo("Hello");
    }

    @Test
    @DisplayName("mixed attachments -> one note per FileRef-bearing attachment, none for the legacy one")
    void onlyFileRefBearingAttachmentsGetANote() throws Exception {
        MessageAttachment withRef = withFileRef("new.png", canonicalFileRef());
        MessageAttachment withoutRef = withFileRef("legacy.png", null);

        String result = invoke("Compare these", List.of(withRef, withoutRef));

        assertThat(result).contains("new.png");
        assertThat(result).doesNotContain("legacy.png");
    }
}
