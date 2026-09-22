package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.domain.AttachmentType;
import com.apimarketplace.agent.domain.MessageAttachment;
import com.apimarketplace.agent.loop.AgentLoopContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

/**
 * Pin-test for {@code ConversationAgentService.buildExecutionRequest} carrying each
 * attachment's {@code fileRef} into the {@code attachmentMaps} sent over the wire - the
 * single point both downstream consumers read from: {@code AgentRemoteExecutionService
 * .mapToMessageAttachment} (direct-API path, agent-service) and the bridge's
 * {@code attachmentPrompt.mjs} (CLI path, reads the JSON verbatim, no Java conversion
 * needed there).
 *
 * <p>Without this, {@code AttachmentService} (conversation-service) could build a correct
 * FileRef and it would still never leave this service: the DTO crossing the HTTP boundary
 * would carry {@code type/mimeType/fileName/data/extractedText} only, silently dropping the
 * one field the fix depends on.
 */
@DisplayName("ConversationAgentService - attachmentMaps fileRef pass-through (input_image unreachable-from-chat regression)")
class ConversationAgentServiceAttachmentFileRefTest {

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> invoke(List<MessageAttachment> attachments) throws Exception {
        ConversationAgentService service = mock(ConversationAgentService.class, CALLS_REAL_METHODS);
        Method m = ConversationAgentService.class.getDeclaredMethod(
            "buildExecutionRequest", AgentLoopContext.class, String.class, String.class,
            Double.class, String.class, String.class, String.class);
        m.setAccessible(true);

        AgentLoopContext context = AgentLoopContext.builder()
            .userPrompt("hi")
            .currentMessageAttachments(attachments)
            .build();

        AgentExecutionRequestDto dto = (AgentExecutionRequestDto) m.invoke(
            service, context, "stream-1", "conv-1", null, "task-1", "chat", "exec-1");
        return dto.attachments();
    }

    private static MessageAttachment withFileRef(String fileName, Map<String, Object> fileRef) {
        return MessageAttachment.builder()
            .type(AttachmentType.IMAGE)
            .mimeType("image/jpeg")
            .fileName(fileName)
            .fileRef(fileRef)
            .build();
    }

    @Test
    @DisplayName("attachment WITH a FileRef -> the wire map carries a \"fileRef\" key, verbatim")
    void fileRefCarriedOntoTheWire() throws Exception {
        Map<String, Object> fileRef = Map.of(
            "_type", "file", "path", "user-1/general/chat/photo.jpg",
            "name", "photo.jpg", "mimeType", "image/jpeg", "size", 3,
            "id", "3f1b2c3d-4e5f-6789-abcd-ef0123456789");

        List<Map<String, Object>> maps = invoke(List.of(withFileRef("photo.jpg", fileRef)));

        assertThat(maps).hasSize(1);
        assertThat(maps.get(0)).containsEntry("fileRef", fileRef);
    }

    @Test
    @DisplayName("attachment with fileRef=null (legacy DB-blob row) -> no \"fileRef\" key on the wire, not a null value")
    void noFileRefKeyWhenAbsent() throws Exception {
        List<Map<String, Object>> maps = invoke(List.of(withFileRef("old.png", null)));

        assertThat(maps).hasSize(1);
        assertThat(maps.get(0)).doesNotContainKey("fileRef");
    }
}
