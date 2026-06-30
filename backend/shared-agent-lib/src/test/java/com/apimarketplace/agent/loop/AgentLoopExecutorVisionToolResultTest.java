package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.*;
import com.apimarketplace.agent.logging.AgentLogger;
import com.apimarketplace.agent.tool.ToolExecutionService;
import com.apimarketplace.agent.tools.common.ToolMediaMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the direct-API ("API mode") vision gap: when a tool returns an image in
 * its {@code __media__} metadata, an agent that calls the LLM API directly (not via the
 * CLI bridge) must still SEE the image. The loop surfaces tool-result images on a synthetic
 * USER message - each {@link com.apimarketplace.agent.provider.LLMProvider} already
 * serialises USER image attachments to a provider-native vision block.
 *
 * <p>Companion to the bridge path (toolContent.mjs): same {@code __media__} convention,
 * the other consumer. Pre-fix, {@code addToolResultMessages} only copied {@code result
 * .content()} text, so the bytes were dropped and the model never saw the pixels.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentLoopExecutor - direct-API vision: tool-result __media__ → synthetic USER image message")
class AgentLoopExecutorVisionToolResultTest {

    @Mock private ToolExecutionService toolExecutionService;

    private AgentLoopExecutor executor;
    private LoopExecutionState state;

    @BeforeEach
    void setUp() {
        executor = new AgentLoopExecutor(
            toolExecutionService, AgentLogger.NOOP,
            Executors.newSingleThreadExecutor(), 5000L, false);
        state = new LoopExecutionState("run-vision-test", 10, 0);
    }

    private static String b64(String raw) {
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private ToolCall call(String name) {
        return ToolCall.builder().id("call_" + name).toolName(name).arguments(Map.of()).build();
    }

    private ToolResult imageResult(ToolCall tc, String pngText) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ToolMediaMetadata.MEDIA_KEY, List.of(
                ToolMediaMetadata.imageDescriptor("image/png", b64(pngText))));
        return ToolResult.builder()
                .toolCall(tc).success(true).content("{\"vision\":\"inlined\"}").metadata(metadata).build();
    }

    @SuppressWarnings("unchecked")
    private void invokeAddToolResultMessages(List<ToolResult> results) throws Exception {
        Method m = AgentLoopExecutor.class.getDeclaredMethod(
                "addToolResultMessages", LoopExecutionState.class, List.class);
        m.setAccessible(true);
        m.invoke(executor, state, results);
    }

    @Test
    @DisplayName("appends a USER message carrying the IMAGE attachment AFTER the tool_result text")
    void appendsUserImageAfterToolResult() throws Exception {
        ToolCall tc = call("files_view");
        invokeAddToolResultMessages(List.of(imageResult(tc, "PNGPIXELS")));

        List<Message> msgs = state.getMessages();
        // tool_result message first, then the synthetic user image message.
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(0).role()).isEqualTo(Message.Role.TOOL);
        assertThat(msgs.get(0).toolCallId()).isEqualTo("call_files_view");

        Message userImg = msgs.get(1);
        assertThat(userImg.role()).isEqualTo(Message.Role.USER);
        assertThat(userImg.hasAttachments()).isTrue();
        assertThat(userImg.attachments()).hasSize(1);
        MessageAttachment att = userImg.attachments().get(0);
        assertThat(att.type()).isEqualTo(AttachmentType.IMAGE);
        assertThat(att.mimeType()).isEqualTo("image/png");
        assertThat(new String(att.data(), StandardCharsets.UTF_8)).isEqualTo("PNGPIXELS");
    }

    @Test
    @DisplayName("image USER message goes AFTER all tool results, keeping the OpenAI tool-call/result invariant valid")
    void preservesOpenAiSequenceInvariant() throws Exception {
        ToolCall a = call("files_view");
        ToolCall b = call("search");
        // Assistant must precede the tool results for validateSequence to find the batch.
        state.getMessages().add(Message.assistantWithToolCalls("", List.of(a, b)));

        invokeAddToolResultMessages(List.of(
                imageResult(a, "IMG_A"),
                ToolResult.builder().toolCall(b).success(true).content("plain text, no media").build()));

        List<Message> msgs = state.getMessages();
        // assistant(tool_calls) → tool(a) → tool(b) → user(image). No role interleaved between tool results.
        assertThat(msgs).hasSize(4);
        assertThat(msgs.get(1).role()).isEqualTo(Message.Role.TOOL);
        assertThat(msgs.get(2).role()).isEqualTo(Message.Role.TOOL);
        assertThat(msgs.get(3).role()).isEqualTo(Message.Role.USER);
        assertThat(msgs.get(3).hasAttachments()).isTrue();

        ToolCallBatchAppender.ValidationResult validation = ToolCallBatchAppender.validateSequence(msgs);
        assertThat(validation.valid()).as("sequence errors: %s", validation.errors()).isTrue();
    }

    @Test
    @DisplayName("collects images from MULTIPLE tool results into a single USER message")
    void collectsMultipleImagesIntoOneUserMessage() throws Exception {
        ToolCall a = call("view_a");
        ToolCall b = call("view_b");
        invokeAddToolResultMessages(List.of(imageResult(a, "FIRST"), imageResult(b, "SECOND")));

        List<Message> msgs = state.getMessages();
        // two tool_result messages + one combined user image message
        assertThat(msgs).hasSize(3);
        Message userImg = msgs.get(2);
        assertThat(userImg.role()).isEqualTo(Message.Role.USER);
        assertThat(userImg.attachments()).hasSize(2);
        assertThat(userImg.content()).contains("2 images");
    }

    @Test
    @DisplayName("no media → no synthetic USER message is added (unchanged behaviour)")
    void noMediaNoExtraMessage() throws Exception {
        ToolCall tc = call("search");
        invokeAddToolResultMessages(List.of(
                ToolResult.builder().toolCall(tc).success(true).content("just text").build()));

        List<Message> msgs = state.getMessages();
        assertThat(msgs).hasSize(1);
        assertThat(msgs.get(0).role()).isEqualTo(Message.Role.TOOL);
    }

    @Test
    @DisplayName("failed tool result never contributes an image (only success carries vision media)")
    void failedResultContributesNoImage() throws Exception {
        ToolCall tc = call("files_view");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ToolMediaMetadata.MEDIA_KEY, List.of(
                ToolMediaMetadata.imageDescriptor("image/png", b64("SHOULD_NOT_APPEAR"))));
        ToolResult failed = ToolResult.builder()
                .toolCall(tc).success(false).error("boom").metadata(metadata).build();

        invokeAddToolResultMessages(List.of(failed));

        List<Message> msgs = state.getMessages();
        assertThat(msgs).hasSize(1);
        assertThat(msgs.get(0).role()).isEqualTo(Message.Role.TOOL);
        assertThat(msgs.get(0).content()).startsWith("Error:");
    }
}
