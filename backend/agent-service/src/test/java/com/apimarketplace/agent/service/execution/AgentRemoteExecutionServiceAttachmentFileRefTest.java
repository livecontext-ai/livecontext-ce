package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.domain.MessageAttachment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

/**
 * Pin-test for {@code AgentRemoteExecutionService.mapToMessageAttachment} carrying the
 * {@code fileRef} key across the Map -> {@link MessageAttachment} conversion on the
 * direct-API dispatch path (conversation-service -> agent-service).
 *
 * <p>Without this, a FileRef built by {@code AttachmentService} (conversation-service) and
 * placed on the wire by {@code ConversationAgentService}'s {@code attachmentMaps} would
 * reach here and be silently dropped, leaving {@code AgentLoopService.appendFileRefNote}
 * (shared-agent-lib) with nothing to append even though the attachment IS durably S3-backed.
 *
 * <p>{@code mapToMessageAttachment} reads only its {@code Map} argument (no instance
 * state), so a {@code CALLS_REAL_METHODS} mock - real method bodies, no constructor run -
 * exercises it without wiring the service's full dependency list.
 */
@DisplayName("AgentRemoteExecutionService - attachment fileRef pass-through (input_image unreachable-from-chat regression)")
class AgentRemoteExecutionServiceAttachmentFileRefTest {

    private static MessageAttachment invoke(Map<String, Object> attachmentMap) throws Exception {
        AgentRemoteExecutionService service = mock(AgentRemoteExecutionService.class, CALLS_REAL_METHODS);
        Method m = AgentRemoteExecutionService.class.getDeclaredMethod("mapToMessageAttachment", Map.class);
        m.setAccessible(true);
        return (MessageAttachment) m.invoke(service, attachmentMap);
    }

    private static Map<String, Object> baseAttachmentMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "IMAGE");
        map.put("mimeType", "image/jpeg");
        map.put("fileName", "photo.jpg");
        map.put("data", Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}));
        return map;
    }

    @Test
    @DisplayName("map carrying a fileRef -> MessageAttachment.fileRef() is populated verbatim")
    void carriesFileRefThrough() throws Exception {
        Map<String, Object> attachmentMap = baseAttachmentMap();
        Map<String, Object> fileRef = new LinkedHashMap<>();
        fileRef.put("_type", "file");
        fileRef.put("path", "user-1/general/chat/photo.jpg");
        fileRef.put("name", "photo.jpg");
        fileRef.put("mimeType", "image/jpeg");
        fileRef.put("size", 3);
        fileRef.put("id", "3f1b2c3d-4e5f-6789-abcd-ef0123456789");
        attachmentMap.put("fileRef", fileRef);

        MessageAttachment result = invoke(attachmentMap);

        assertThat(result).isNotNull();
        assertThat(result.fileRef()).isEqualTo(fileRef);
    }

    @Test
    @DisplayName("map without a fileRef key (legacy DB-blob attachment) -> MessageAttachment.fileRef() is null, not fabricated")
    void nullWhenFileRefAbsent() throws Exception {
        MessageAttachment result = invoke(baseAttachmentMap());

        assertThat(result).isNotNull();
        assertThat(result.fileRef()).isNull();
    }

    @Test
    @DisplayName("malformed fileRef value (not a Map) -> ignored, rest of the attachment still converts")
    void malformedFileRefIsIgnoredNotFatal() throws Exception {
        Map<String, Object> attachmentMap = baseAttachmentMap();
        attachmentMap.put("fileRef", "not-a-map");

        MessageAttachment result = invoke(attachmentMap);

        assertThat(result).isNotNull();
        assertThat(result.fileRef()).isNull();
        assertThat(result.fileName()).isEqualTo("photo.jpg");
    }
}
