package com.apimarketplace.orchestrator.controllers.cloud;

import com.apimarketplace.agent.cloud.CloudLlmRelayRequest;
import com.apimarketplace.agent.cloud.CloudLlmRuntimeAccess;
import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
import com.apimarketplace.orchestrator.tools.websearch.CloudBrowserAgentLlmRelayClient;
import com.apimarketplace.agent.domain.AttachmentType;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.MessageAttachment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BrowserAgentLlmShimController} - the OpenAI-compatible shim that relays the
 * browser agent's per-step LLM calls to the cloud when the install is cloud-linked. Covers the
 * OpenAI &harr; platform translation (system/turns/vision/json mode), provider-qualified model split,
 * gateway-secret auth, and the unlinked short-circuit.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BrowserAgentLlmShimController")
class BrowserAgentLlmShimControllerTest {

    @Mock private CloudBrowserAgentLlmRelayClient relayClient;
    @Mock private CloudLlmRuntimeAccess runtimeAccess;

    /** The orchestrator's signer: every relay call names a tenant it signed. */
    private final com.apimarketplace.orchestrator.tools.websearch.BrowserAgentRelayTenantSigner signer =
            new com.apimarketplace.orchestrator.tools.websearch.BrowserAgentRelayTenantSigner();
    private static final String TENANT = "42";
    private String sig() {
        return signer.sign(TENANT);
    }

    @SuppressWarnings("unchecked")
    private BrowserAgentLlmShimController controller(String secret, boolean linked) {
        ObjectProvider<CloudLlmRuntimeAccess> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(runtimeAccess);
        if (linked) {
            lenient().when(runtimeAccess.resolveCloudRuntime(TENANT)).thenReturn(
                    Optional.of(new CloudLlmRuntimeCredentials("tok", "install-1", "https://livecontext.ai/api")));
        } else {
            lenient().when(runtimeAccess.resolveCloudRuntime(TENANT)).thenReturn(Optional.empty());
        }
        return new BrowserAgentLlmShimController(relayClient, provider, secret, signer);
    }

    private CloudLlmRelayRequest captureRelay() {
        ArgumentCaptor<CloudLlmRelayRequest> captor = ArgumentCaptor.forClass(CloudLlmRelayRequest.class);
        verify(relayClient).complete(any(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("happy path: splits provider/model, maps system+turns, returns OpenAI shape")
    void happyPath() {
        when(relayClient.complete(any(), any())).thenReturn(CompletionResponse.text("The title is Example Domain"));
        var controller = controller("", true);

        Map<String, Object> body = Map.of(
                "model", "google/gemini-3.1-flash-lite",
                "messages", List.of(
                        Map.of("role", "system", "content", "You are a browser agent."),
                        Map.of("role", "user", "content", "Go to example.com and report the title.")));

        ResponseEntity<?> resp = controller.chatCompletions(null, null, TENANT, sig(), body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> out = (Map<?, ?>) resp.getBody();
        List<?> choices = (List<?>) out.get("choices");
        Map<?, ?> message = (Map<?, ?>) ((Map<?, ?>) choices.get(0)).get("message");
        assertThat(message.get("content")).isEqualTo("The title is Example Domain");

        CloudLlmRelayRequest relayed = captureRelay();
        assertThat(relayed.provider()).isEqualTo("google");
        assertThat(relayed.completionRequest().model()).isEqualTo("gemini-3.1-flash-lite");
        assertThat(relayed.completionRequest().systemPrompt()).contains("You are a browser agent.");
        assertThat(relayed.completionRequest().conversationHistory()).hasSize(1);
        Message userMsg = relayed.completionRequest().conversationHistory().get(0);
        assertThat(userMsg.role()).isEqualTo(Message.Role.USER);
        assertThat(userMsg.content()).contains("Go to example.com");
    }

    @Test
    @DisplayName("provider from X-LLM-Provider header keeps the model clean (no split)")
    void providerFromHeader() {
        when(relayClient.complete(any(), any())).thenReturn(CompletionResponse.text("ok"));
        var controller = controller("", true);

        Map<String, Object> body = Map.of(
                "model", "gemini-3.1-flash-lite",
                "messages", List.of(Map.of("role", "user", "content", "hi")));

        ResponseEntity<?> resp = controller.chatCompletions(null, "google", TENANT, sig(), body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        CloudLlmRelayRequest relayed = captureRelay();
        assertThat(relayed.provider()).isEqualTo("google");
        assertThat(relayed.completionRequest().model()).isEqualTo("gemini-3.1-flash-lite");
    }

    @Test
    @DisplayName("vision: image_url data URL becomes a Message image attachment")
    void visionPassthrough() {
        when(relayClient.complete(any(), any())).thenReturn(CompletionResponse.text("{}"));
        var controller = controller("", true);

        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G'};
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        Map<String, Object> body = Map.of(
                "model", "google/gemini-3.1-flash-lite",
                "messages", List.of(Map.of("role", "user", "content", List.of(
                        Map.of("type", "text", "text", "What is on screen?"),
                        Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))))));

        controller.chatCompletions(null, null, TENANT, sig(), body);

        CloudLlmRelayRequest relayed = captureRelay();
        Message userMsg = relayed.completionRequest().conversationHistory().get(0);
        assertThat(userMsg.content()).contains("What is on screen?");
        assertThat(userMsg.attachments()).hasSize(1);
        MessageAttachment att = userMsg.attachments().get(0);
        assertThat(att.type()).isEqualTo(AttachmentType.IMAGE);
        assertThat(att.mimeType()).isEqualTo("image/png");
        assertThat(att.data()).isEqualTo(png);
    }

    @Test
    @DisplayName("json_object mode: appends a JSON instruction and strips markdown fences from the reply")
    void jsonModeStripsFences() {
        when(relayClient.complete(any(), any()))
                .thenReturn(CompletionResponse.text("```json\n{\"action\":\"done\"}\n```"));
        var controller = controller("", true);

        Map<String, Object> body = Map.of(
                "model", "google/gemini-3.1-flash-lite",
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(Map.of("role", "user", "content", "next step")));

        ResponseEntity<?> resp = controller.chatCompletions(null, null, TENANT, sig(), body);

        Map<?, ?> out = (Map<?, ?>) resp.getBody();
        Map<?, ?> message = (Map<?, ?>) ((Map<?, ?>) ((List<?>) out.get("choices")).get(0)).get("message");
        assertThat(message.get("content")).isEqualTo("{\"action\":\"done\"}");

        CloudLlmRelayRequest relayed = captureRelay();
        assertThat(relayed.completionRequest().systemPrompt()).contains("JSON");
    }

    @Test
    @DisplayName("secret set: wrong X-Browser-Agent-Relay-Secret header is 401, no relay call")
    void rejectsWrongSecret() {
        var controller = controller("s3cret", true);

        ResponseEntity<?> resp = controller.chatCompletions("wrong", null, TENANT, sig(),
                Map.of("model", "google/x", "messages", List.of()));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(relayClient, never()).complete(any(), any());
    }

    @Test
    @DisplayName("secret set: correct X-Browser-Agent-Relay-Secret header is accepted")
    void acceptsCorrectSecret() {
        when(relayClient.complete(any(), any())).thenReturn(CompletionResponse.text("ok"));
        var controller = controller("s3cret", true);

        ResponseEntity<?> resp = controller.chatCompletions("s3cret", null, TENANT, sig(),
                Map.of("model", "google/x", "messages", List.of(Map.of("role", "user", "content", "hi"))));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("secret set: missing header is rejected 401")
    void rejectsMissingSecret() {
        var controller = controller("s3cret", true);

        ResponseEntity<?> resp = controller.chatCompletions(null, null, TENANT, sig(),
                Map.of("model", "google/x", "messages", List.of()));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(relayClient, never()).complete(any(), any());
    }

    @Test
    @DisplayName("missing model is a 400 without calling the relay")
    void missingModelIs400() {
        var controller = controller("", true);

        ResponseEntity<?> resp = controller.chatCompletions(null, null, TENANT, sig(), Map.of("messages", List.of()));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(relayClient, never()).complete(any(), any());
    }

    @Test
    @DisplayName("relay failure surfaces as 502")
    void relayFailureIs502() {
        when(relayClient.complete(any(), any())).thenThrow(new IllegalStateException("cloud 500"));
        var controller = controller("", true);

        ResponseEntity<?> resp = controller.chatCompletions(null, null, TENANT, sig(),
                Map.of("model", "gemini-3.1-flash-lite", "messages", List.of(Map.of("role", "user", "content", "hi"))));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    @DisplayName("no provider header and no slash defaults provider to google")
    void defaultProviderWhenNoHeaderNoSlash() {
        when(relayClient.complete(any(), any())).thenReturn(CompletionResponse.text("ok"));
        var controller = controller("", true);

        controller.chatCompletions(null, null, TENANT, sig(),
                Map.of("model", "gemini-3.1-flash-lite", "messages", List.of(Map.of("role", "user", "content", "hi"))));

        assertThat(captureRelay().provider()).isEqualTo("google");
    }

    @Test
    @DisplayName("undecodable image_url is dropped, not thrown")
    void badImageUrlDropped() {
        when(relayClient.complete(any(), any())).thenReturn(CompletionResponse.text("ok"));
        var controller = controller("", true);

        Map<String, Object> body = Map.of(
                "model", "gemini-3.1-flash-lite",
                "messages", List.of(Map.of("role", "user", "content", List.of(
                        Map.of("type", "text", "text", "look"),
                        Map.of("type", "image_url", "image_url", Map.of("url", "http://not-a-data-url"))))));

        ResponseEntity<?> resp = controller.chatCompletions(null, null, TENANT, sig(), body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Message userMsg = captureRelay().completionRequest().conversationHistory().get(0);
        assertThat(userMsg.attachments() == null || userMsg.attachments().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("runtime-access bean absent: endpoint returns 503, no relay call")
    @SuppressWarnings("unchecked")
    void beanAbsentIs503() {
        ObjectProvider<CloudLlmRuntimeAccess> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        var controller = new BrowserAgentLlmShimController(relayClient, provider, "", signer);

        ResponseEntity<?> resp = controller.chatCompletions(null, null, TENANT, sig(),
                Map.of("model", "google/x", "messages", List.of()));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(relayClient, never()).complete(any(), any());
    }

    @Test
    @DisplayName("not linked: returns 503 cloud-link-not-active without calling the relay")
    void notLinkedShortCircuits() {
        var controller = controller("", false);

        ResponseEntity<?> resp = controller.chatCompletions(null, null, TENANT, sig(),
                Map.of("model", "google/x", "messages", List.of()));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(relayClient, never()).complete(any(), any());
    }

    @Test
    @DisplayName("stripJsonFences: leaves already-clean JSON untouched")
    void stripFencesNoop() {
        assertThat(BrowserAgentLlmShimController.stripJsonFences("{\"a\":1}")).isEqualTo("{\"a\":1}");
        assertThat(BrowserAgentLlmShimController.stripJsonFences("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("regression: with the tenant header the relay runs on THAT tenant's link, never the install's newest one")
    void relaysOnTheCallersOwnLink() {
        var controller = controller("", false);
        // The install's newest link belongs to another user (e.g. FREE): it must never be used.
        lenient().when(runtimeAccess.resolveActiveCloudRuntime()).thenReturn(Optional.of(
                new CloudLlmRuntimeCredentials("other-tok", "install-other", "https://livecontext.ai/api")));
        CloudLlmRuntimeCredentials own = new CloudLlmRuntimeCredentials("own-tok", "install-own", "https://livecontext.ai/api");
        when(runtimeAccess.resolveCloudRuntime(TENANT)).thenReturn(Optional.of(own));
        when(relayClient.complete(any(), any())).thenReturn(CompletionResponse.text("ok"));

        ResponseEntity<?> resp = controller.chatCompletions(null, "google", TENANT, sig(), Map.of(
                "model", "gemini-3.1-flash-lite",
                "messages", List.of(Map.of("role", "user", "content", "hi"))));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<CloudLlmRuntimeCredentials> creds = ArgumentCaptor.forClass(CloudLlmRuntimeCredentials.class);
        verify(relayClient).complete(creds.capture(), any());
        assertThat(creds.getValue()).isEqualTo(own);
        verify(runtimeAccess, never()).resolveActiveCloudRuntime();
    }

    @Test
    @DisplayName("a tenant whose own cloud runtime is not ready gets 503, never another user's link")
    void tenantWithoutOwnRuntimeIsRefused() {
        var controller = controller("", false);

        ResponseEntity<?> resp = controller.chatCompletions(null, "google", TENANT, sig(), Map.of(
                "model", "gemini-3.1-flash-lite",
                "messages", List.of(Map.of("role", "user", "content", "hi"))));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(relayClient, never()).complete(any(), any());
    }

    @Test
    @DisplayName("regression: a tenant without the orchestrator's signature is refused 401, no link is read, no relay")
    void unsignedTenantIsRefused() {
        var controller = controller("", true);

        ResponseEntity<?> resp = controller.chatCompletions(null, "google", TENANT, null, Map.of(
                "model", "gemini-3.1-flash-lite", "messages", List.of(Map.of("role", "user", "content", "hi"))));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(runtimeAccess, never()).resolveCloudRuntime(any());
        verify(runtimeAccess, never()).resolveActiveCloudRuntime();
        verify(relayClient, never()).complete(any(), any());
    }

    @Test
    @DisplayName("regression: naming ANOTHER tenant with a signature made for this one is refused 401 (no picking whose link pays)")
    void signatureOfAnotherTenantIsRefused() {
        var controller = controller("", true);

        ResponseEntity<?> resp = controller.chatCompletions(null, "google", "7", sig(), Map.of(
                "model", "gemini-3.1-flash-lite", "messages", List.of(Map.of("role", "user", "content", "hi"))));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(relayClient, never()).complete(any(), any());
    }

    @Test
    @DisplayName("no tenant at all (an old runner, or an outside caller) is refused 401: no install-wide fallback")
    void noTenantIsRefused() {
        var controller = controller("", true);
        lenient().when(runtimeAccess.resolveActiveCloudRuntime()).thenReturn(Optional.of(
                new CloudLlmRuntimeCredentials("tok", "install-1", "https://livecontext.ai/api")));

        ResponseEntity<?> resp = controller.chatCompletions(null, "google", null, null, Map.of(
                "model", "gemini-3.1-flash-lite", "messages", List.of(Map.of("role", "user", "content", "hi"))));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(relayClient, never()).complete(any(), any());
    }

    @Test
    @DisplayName("the cloud-link state cannot be read: 503 cloud_link (the runner's handled status), never a 500")
    void unreadableLinkStateIs503() {
        var controller = controller("", true);
        when(runtimeAccess.resolveCloudRuntime(TENANT)).thenThrow(new IllegalStateException("publication down"));

        ResponseEntity<?> resp = controller.chatCompletions(null, "google", TENANT, sig(), Map.of(
                "model", "gemini-3.1-flash-lite", "messages", List.of(Map.of("role", "user", "content", "hi"))));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(relayClient, never()).complete(any(), any());
    }
}
