package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the Gemini tool round-trip (prod 2026-09-26): every chat whose model
 * called a tool died on the NEXT request with one of two HTTP 400s, reproduced live
 * against the Gemini API:
 * <ol>
 *   <li>"Role 'function' is not supported" - the tool result was sent as role "function";</li>
 *   <li>"Function call is missing a thought_signature" - once the role is fixed, the
 *       function call echoed back in history must carry the model's thoughtSignature.</li>
 * </ol>
 */
@DisplayName("GeminiProvider - tool call / tool result turn format")
class GeminiProviderToolTurnFormatTest {

    private final GeminiProvider provider = new GeminiProvider();

    @Test
    @DisplayName("a tool result is sent as a 'user' turn carrying the functionResponse, never role 'function'")
    void toolResultUsesUserRole() {
        Map<String, Object> turn = contentsFor(List.of(
            Message.assistantWithToolCalls("", List.of(call("c1", "get_weather", "SIG-1"))),
            Message.toolResult("c1", "get_weather", "18C sunny")
        )).get(1);

        assertThat(turn.get("role")).isEqualTo("user");
        Map<String, Object> functionResponse = (Map<String, Object>) firstPart(turn).get("functionResponse");
        assertThat(functionResponse.get("name")).isEqualTo("get_weather");
        assertThat(functionResponse.get("response")).isEqualTo(Map.of("output", "18C sunny"));
    }

    @Test
    @DisplayName("the model's thoughtSignature is echoed on its function call")
    void signatureIsEchoed() {
        Map<String, Object> modelTurn = contentsFor(List.of(
            Message.assistantWithToolCalls("", List.of(call("c1", "get_weather", "SIG-1"))),
            Message.toolResult("c1", "get_weather", "ok")
        )).get(0);

        assertThat(modelTurn.get("role")).isEqualTo("model");
        assertThat(firstPart(modelTurn)).containsEntry("thoughtSignature", "SIG-1");
    }

    @Test
    @DisplayName("parallel calls: each keeps its own signature, none is overwritten by the bypass value")
    void parallelCallsKeepTheirSignatures() {
        List<Map<String, Object>> parts = parts(contentsFor(List.of(
            Message.assistantWithToolCalls("", List.of(
                call("c1", "get_weather", "SIG-1"),
                call("c2", "get_weather", null)))
        )).get(0));

        assertThat(parts.get(0)).containsEntry("thoughtSignature", "SIG-1");
        assertThat(parts.get(1)).doesNotContainKey("thoughtSignature");
    }

    @Test
    @DisplayName("a lost signature falls back to the documented bypass value on the FIRST call only")
    void lostSignatureUsesBypassOnFirstCallOnly() {
        List<Map<String, Object>> parts = parts(contentsFor(List.of(
            Message.assistantWithToolCalls("", List.of(
                call("c1", "get_weather", null),
                call("c2", "get_weather", "  ")))
        )).get(0));

        assertThat(parts.get(0)).containsEntry("thoughtSignature", "skip_thought_signature_validator");
        assertThat(parts.get(1)).doesNotContainKey("thoughtSignature");
    }

    @Test
    @DisplayName("assistant text before the calls is kept and never gets a signature")
    void textPartHasNoSignature() {
        List<Map<String, Object>> parts = parts(contentsFor(List.of(
            Message.assistantWithToolCalls("Let me check.", List.of(call("c1", "get_weather", "SIG-1")))
        )).get(0));

        assertThat(parts.get(0)).isEqualTo(Map.of("text", "Let me check."));
        assertThat(parts.get(1)).containsEntry("thoughtSignature", "SIG-1");
    }

    @Test
    @DisplayName("streaming: the thoughtSignature next to a functionCall is captured on the ToolCall")
    void streamingCapturesSignature() {
        String sseLine = "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":["
            + "{\"functionCall\":{\"name\":\"get_weather\",\"args\":{\"city\":\"Paris\"},\"id\":\"call_1\"},"
            + "\"thoughtSignature\":\"SIG-STREAM\"},"
            + "{\"functionCall\":{\"name\":\"get_weather\",\"args\":{\"city\":\"Lyon\"}}}]}}]}";

        List<ToolCall> calls = provider.parseGeminiToolCalls(sseLine);

        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).thoughtSignature()).isEqualTo("SIG-STREAM");
        assertThat(calls.get(0).arguments()).isEqualTo(Map.of("city", "Paris"));
        assertThat(calls.get(1).thoughtSignature()).isNull();
    }

    @Test
    @DisplayName("non-streaming: the thoughtSignature next to a functionCall is captured on the ToolCall")
    void nonStreamingCapturesSignature() {
        Map<String, Object> response = Map.of("candidates", List.of(Map.of(
            "finishReason", "STOP",
            "content", Map.of("role", "model", "parts", List.of(Map.of(
                "functionCall", Map.of("name", "get_weather", "args", Map.of("city", "Paris")),
                "thoughtSignature", "SIG-SYNC"))))));

        CompletionResponse parsed = provider.parseResponse(response);

        assertThat(parsed.toolCalls()).singleElement()
            .extracting(ToolCall::thoughtSignature).isEqualTo("SIG-SYNC");
    }

    @Test
    @DisplayName("round trip: a streamed call rebuilt into history echoes the exact signature Gemini sent")
    void streamedSignatureRoundTrips() {
        String sseLine = "data: {\"candidates\":[{\"content\":{\"parts\":["
            + "{\"functionCall\":{\"name\":\"get_weather\",\"args\":{}},\"thoughtSignature\":\"Eo0CCooC+/=\"}]}}]}";
        ToolCall streamed = provider.parseGeminiToolCalls(sseLine).get(0);

        Map<String, Object> modelTurn = contentsFor(List.of(
            Message.assistantWithToolCalls("", List.of(streamed)),
            Message.toolResult(streamed.id(), "get_weather", "ok")
        )).get(0);

        assertThat(firstPart(modelTurn)).containsEntry("thoughtSignature", "Eo0CCooC+/=");
    }

    @Test
    @DisplayName("a blank signature on the FIRST call is replaced by the bypass value, never sent as-is")
    void blankFirstSignatureUsesBypass() {
        List<Map<String, Object>> parts = parts(contentsFor(List.of(
            Message.assistantWithToolCalls("", List.of(call("c1", "get_weather", "  ")))
        )).get(0));

        assertThat(parts.get(0)).containsEntry("thoughtSignature", "skip_thought_signature_validator");
    }

    @Test
    @DisplayName("history rebuilt without signatures: every step's first call gets the bypass value")
    void eachStepWithoutSignatureGetsBypass() {
        List<Map<String, Object>> contents = contentsFor(List.of(
            Message.assistantWithToolCalls("", List.of(call("c1", "get_weather", null))),
            Message.toolResult("c1", "get_weather", "ok"),
            Message.assistantWithToolCalls("", List.of(call("c2", "get_weather", null))),
            Message.toolResult("c2", "get_weather", "ok")
        ));

        assertThat(firstPart(contents.get(0))).containsEntry("thoughtSignature", "skip_thought_signature_validator");
        assertThat(firstPart(contents.get(2))).containsEntry("thoughtSignature", "skip_thought_signature_validator");
        assertThat(contents.get(1).get("role")).isEqualTo("user");
        assertThat(contents.get(3).get("role")).isEqualTo("user");
    }

    @Test
    @DisplayName("plain text turns keep their roles: user stays 'user', assistant stays 'model'")
    void plainTextTurnRolesUnchanged() {
        List<Map<String, Object>> contents = contentsFor(List.of(
            Message.user("hello"),
            Message.assistant("hi there")
        ));

        assertThat(contents.get(0)).isEqualTo(Map.of("role", "user", "parts", List.of(Map.of("text", "hello"))));
        assertThat(contents.get(1)).isEqualTo(Map.of("role", "model", "parts", List.of(Map.of("text", "hi there"))));
    }

    @Test
    @DisplayName("non-streaming: a non-string thoughtSignature value is ignored instead of throwing")
    void nonStringSignatureIgnored() {
        Map<String, Object> response = Map.of("candidates", List.of(Map.of(
            "finishReason", "STOP",
            "content", Map.of("parts", List.of(Map.of(
                "functionCall", Map.of("name", "get_weather", "args", Map.of()),
                "thoughtSignature", 42))))));

        assertThat(provider.parseResponse(response).toolCalls()).singleElement()
            .extracting(ToolCall::thoughtSignature).isNull();
    }

    @Test
    @DisplayName("JSON: the signature survives a serialize/deserialize round trip (CE cloud relay)")
    void jsonRoundTripKeepsSignature() throws Exception {
        ObjectMapper json = new ObjectMapper();
        ToolCall original = call("c1", "get_weather", "SIG-JSON");

        ToolCall copy = json.readValue(json.writeValueAsString(original), ToolCall.class);

        assertThat(copy).isEqualTo(original);
    }

    @Test
    @DisplayName("JSON: a call without signature serializes exactly as before (no thoughtSignature key)")
    void jsonOmitsNullSignature() throws Exception {
        ObjectMapper json = new ObjectMapper();
        String serialized = json.writeValueAsString(new ToolCall("c1", "t", Map.of(), 0));

        assertThat(serialized).doesNotContain("thoughtSignature");
        assertThat(json.readValue("{\"id\":\"c1\",\"toolName\":\"t\",\"arguments\":{},\"index\":0}", ToolCall.class))
            .isEqualTo(new ToolCall("c1", "t", Map.of(), 0));
    }

    @Test
    @DisplayName("the 4-argument ToolCall constructor used by other providers leaves the signature null")
    void legacyConstructorHasNoSignature() {
        assertThat(new ToolCall("id", "t", Map.of(), 0).thoughtSignature()).isNull();
    }

    @Test
    @DisplayName("parallel calls: their results travel in ONE user turn, one functionResponse per call, in order")
    void parallelResultsShareOneTurn() {
        List<Map<String, Object>> contents = contentsFor(List.of(
            Message.assistantWithToolCalls("", List.of(
                call("c1", "get_weather", "SIG-1"),
                call("c2", "get_time", null))),
            Message.toolResult("c1", "get_weather", "18C"),
            Message.toolResult("c2", "get_time", "10:00")
        ));

        assertThat(contents).hasSize(2);
        assertThat(contents.get(1).get("role")).isEqualTo("user");
        List<Map<String, Object>> responses = parts(contents.get(1));
        assertThat(responses).hasSize(2);
        assertThat((Map<String, Object>) responses.get(0).get("functionResponse")).containsEntry("name", "get_weather");
        assertThat((Map<String, Object>) responses.get(1).get("functionResponse")).containsEntry("name", "get_time");
    }

    @Test
    @DisplayName("results of two successive steps are never merged across the model turn between them")
    void successiveStepsStaySeparate() {
        List<Map<String, Object>> contents = contentsFor(List.of(
            Message.assistantWithToolCalls("", List.of(call("c1", "a", "S1"))),
            Message.toolResult("c1", "a", "1"),
            Message.assistantWithToolCalls("", List.of(call("c2", "b", "S2"))),
            Message.toolResult("c2", "b", "2"),
            Message.user("thanks")
        ));

        assertThat(contents).extracting(c -> c.get("role"))
            .containsExactly("model", "user", "model", "user", "user");
        assertThat(parts(contents.get(1))).hasSize(1);
        assertThat(parts(contents.get(3))).hasSize(1);
        assertThat(parts(contents.get(4))).containsExactly(Map.of("text", "thanks"));
    }

    private static ToolCall call(String id, String name, String signature) {
        return ToolCall.builder().id(id).toolName(name).arguments(Map.of()).thoughtSignature(signature).build();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> contentsFor(List<Message> history) {
        CompletionRequest request = CompletionRequest.builder()
            .model("gemini-flash-latest")
            .conversationHistory(history)
            .build();
        return (List<Map<String, Object>>) provider.buildRequestBody(request).get("contents");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parts(Map<String, Object> turn) {
        return (List<Map<String, Object>>) turn.get("parts");
    }

    private static Map<String, Object> firstPart(Map<String, Object> turn) {
        return parts(turn).get(0);
    }
}
