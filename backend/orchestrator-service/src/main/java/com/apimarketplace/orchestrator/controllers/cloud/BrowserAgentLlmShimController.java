package com.apimarketplace.orchestrator.controllers.cloud;

import com.apimarketplace.agent.cloud.CloudLlmRelayRequest;
import com.apimarketplace.agent.cloud.CloudLlmRuntimeAccess;
import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
import com.apimarketplace.orchestrator.tools.websearch.CloudBrowserAgentLlmRelayClient;
import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.MessageAttachment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OpenAI-compatible shim that lets the CE browser agent (browser-use, in the websearch
 * container) drive its per-step LLM calls through the <b>cloud LLM relay</b> when the install
 * is cloud-linked - the same relay the chat / workflow agents use ({@code /api/ce-llm/complete}),
 * billed on the linked cloud account. This closes the gap where the browser agent was the only
 * LLM consumer that required a <i>direct</i> provider key.
 *
 * <p>Flow: {@code BrowserAgentModule} (when {@link CloudLlmRuntimeAccess#resolveActiveCloudRuntime()}
 * is present) routes the runner's {@code llm} block to {@code provider_kind="bridge"} +
 * {@code bridge_url=<this endpoint>}. browser-use's {@code BridgeChatClient} then POSTs an OpenAI
 * chat-completions payload here; we translate it to a {@link CompletionRequest} (system text,
 * conversation turns, and vision {@code image_url} parts &rarr; {@link MessageAttachment}s), relay it
 * via {@link CloudBrowserAgentLlmRelayClient}, and translate the {@link CompletionResponse} back to
 * the OpenAI shape browser-use expects.
 *
 * <p>The model stays clean (unqualified); the provider arrives in the {@code X-LLM-Provider} header
 * so downstream pricing and observability record the real model name.
 *
 * <p>Auth: an optional shared secret ({@code websearch.gateway-secret}) presented in the
 * {@code X-Browser-Agent-Relay-Secret} header. It is deliberately NOT carried in
 * {@code Authorization}: the CE monolith security filter would reject a non-JWT bearer with 401
 * before this controller runs, so a custom header is used and passes through to us. Blank secret
 * (single-host CE default) leaves the endpoint open on the internal network - it is cloud-link
 * gated ({@code resolveActiveCloudRuntime}) and never reachable without an active link.
 */
@Slf4j
@RestController
@RequestMapping("/api/browser-agent/llm")
public class BrowserAgentLlmShimController {

    private final CloudBrowserAgentLlmRelayClient relayClient;
    private final CloudLlmRuntimeAccess runtimeAccess;
    private final String gatewaySecret;

    public BrowserAgentLlmShimController(CloudBrowserAgentLlmRelayClient relayClient,
                                         ObjectProvider<CloudLlmRuntimeAccess> runtimeAccessProvider,
                                         @Value("${websearch.gateway-secret:}") String gatewaySecret) {
        this.relayClient = relayClient;
        // Optional: absent in a standalone cloud orchestrator (no cloud-link there). When null, the
        // endpoint returns "cloud link not active" - but BrowserAgentModule only routes here when a
        // link IS active, so in practice the bean is present wherever this is called.
        this.runtimeAccess = runtimeAccessProvider.getIfAvailable();
        this.gatewaySecret = gatewaySecret;
    }

    @PostMapping("/v1/chat/completions")
    public ResponseEntity<?> chatCompletions(
            @RequestHeader(value = "X-Browser-Agent-Relay-Secret", required = false) String relaySecretHeader,
            @RequestHeader(value = "X-LLM-Provider", required = false) String providerHeader,
            @RequestBody Map<String, Object> body) {

        if (gatewaySecret != null && !gatewaySecret.isBlank()) {
            if (!gatewaySecret.equals(relaySecretHeader == null ? null : relaySecretHeader.trim())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", Map.of("message", "invalid relay secret", "type", "auth")));
            }
        }

        Optional<CloudLlmRuntimeCredentials> credsOpt =
                runtimeAccess == null ? Optional.empty() : runtimeAccess.resolveActiveCloudRuntime();
        if (credsOpt.isEmpty()) {
            // Only reached when linked; a missing link here means the install unlinked mid-run.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", Map.of("message", "cloud link not active", "type", "cloud_link")));
        }

        String rawModel = str(body.get("model"));
        if (rawModel == null || rawModel.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", Map.of("message", "model is required", "type", "invalid_request")));
        }
        // Provider comes from the X-LLM-Provider header (BrowserAgentModule keeps llm.model clean and
        // the runner's BridgeChatClient forwards the provider here). Legacy fallback: a provider-
        // qualified "provider/model" body model - split on the FIRST slash.
        String provider;
        String model = rawModel;
        if (providerHeader != null && !providerHeader.isBlank()) {
            provider = providerHeader.trim();
            int slash = model.indexOf('/');
            if (slash > 0 && model.substring(0, slash).equalsIgnoreCase(provider)) {
                model = model.substring(slash + 1); // defensive: drop a stray provider prefix
            }
        } else {
            int slash = model.indexOf('/');
            if (slash > 0) {
                provider = model.substring(0, slash);
                model = model.substring(slash + 1);
            } else {
                provider = "google";
            }
        }

        boolean jsonMode = wantsJsonObject(body.get("response_format"));

        StringBuilder systemText = new StringBuilder();
        List<Message> history = new ArrayList<>();
        Object rawMessages = body.get("messages");
        if (rawMessages instanceof List<?> list) {
            for (Object m : list) {
                if (!(m instanceof Map<?, ?> msg)) {
                    continue;
                }
                String role = str(msg.get("role"));
                TextAndImages parsed = parseContent(msg.get("content"));
                if ("system".equalsIgnoreCase(role)) {
                    if (!parsed.text().isBlank()) {
                        if (systemText.length() > 0) {
                            systemText.append("\n\n");
                        }
                        systemText.append(parsed.text());
                    }
                    continue;
                }
                Message.Role msgRole = "assistant".equalsIgnoreCase(role) ? Message.Role.ASSISTANT : Message.Role.USER;
                if (parsed.images().isEmpty()) {
                    history.add(Message.builder().role(msgRole).content(parsed.text()).build());
                } else {
                    history.add(Message.builder()
                            .role(msgRole)
                            .content(parsed.text())
                            .attachments(parsed.images())
                            .build());
                }
            }
        }

        if (jsonMode) {
            if (systemText.length() > 0) {
                systemText.append("\n\n");
            }
            systemText.append("Return ONLY a single valid JSON object. Do not wrap it in markdown fences.");
        }

        CompletionRequest completionRequest = CompletionRequest.builder()
                .model(model)
                .systemPrompt(systemText.length() == 0 ? null : systemText.toString())
                .conversationHistory(history)
                .temperature(asDouble(body.get("temperature")))
                .maxTokens(asInteger(body.get("max_tokens")))
                .build();

        CompletionResponse response;
        try {
            response = relayClient.complete(credsOpt.get(), new CloudLlmRelayRequest(provider, completionRequest));
        } catch (RuntimeException e) {
            log.warn("browser_agent cloud LLM relay failed provider={} model={}: {}", provider, model, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", Map.of("message",
                            e.getMessage() == null ? "cloud relay failed" : e.getMessage(), "type", "upstream")));
        }

        String content = response.content() == null ? "" : response.content();
        if (jsonMode) {
            content = stripJsonFences(content);
        }
        return ResponseEntity.ok(openAiResponse(model, content, response));
    }

    // ---- OpenAI <-> platform translation helpers --------------------------------------------

    private record TextAndImages(String text, List<MessageAttachment> images) {}

    /**
     * OpenAI message content is either a plain string or a list of parts
     * ({@code {type:"text",text}} / {@code {type:"image_url",image_url:{url:"data:...;base64,..."}}}).
     * browser-use sends screenshots as {@code image_url} data URLs - we must carry those through as
     * {@link MessageAttachment} images or the agent navigates blind.
     */
    @SuppressWarnings("unchecked")
    private static TextAndImages parseContent(Object content) {
        List<MessageAttachment> images = new ArrayList<>();
        if (content == null) {
            return new TextAndImages("", images);
        }
        if (content instanceof String s) {
            return new TextAndImages(s, images);
        }
        StringBuilder text = new StringBuilder();
        if (content instanceof List<?> parts) {
            for (Object p : parts) {
                if (!(p instanceof Map<?, ?> part)) {
                    continue;
                }
                String type = str(part.get("type"));
                if ("text".equalsIgnoreCase(type)) {
                    String t = str(part.get("text"));
                    if (t != null) {
                        text.append(t);
                    }
                } else if ("image_url".equalsIgnoreCase(type)) {
                    Object iu = part.get("image_url");
                    String url = iu instanceof Map ? str(((Map<String, Object>) iu).get("url")) : str(iu);
                    MessageAttachment img = dataUrlToImage(url);
                    if (img != null) {
                        images.add(img);
                    }
                }
            }
        }
        return new TextAndImages(text.toString(), images);
    }

    /** Decodes a {@code data:<mime>;base64,<payload>} URL into an image attachment (null if not decodable). */
    private static MessageAttachment dataUrlToImage(String url) {
        if (url == null || !url.startsWith("data:")) {
            return null;
        }
        int comma = url.indexOf(',');
        if (comma < 0) {
            return null;
        }
        String meta = url.substring(5, comma); // e.g. "image/png;base64"
        String payload = url.substring(comma + 1);
        String mime = meta.contains(";") ? meta.substring(0, meta.indexOf(';')) : meta;
        if (mime.isBlank()) {
            mime = "image/png";
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(payload);
            return MessageAttachment.image(bytes, mime, "screenshot");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean wantsJsonObject(Object responseFormat) {
        if (responseFormat instanceof Map<?, ?> rf) {
            return "json_object".equalsIgnoreCase(str(((Map<String, Object>) rf).get("type")));
        }
        return false;
    }

    /** Strips a leading/trailing ```json ... ``` fence if the model wrapped its JSON (best-effort). */
    static String stripJsonFences(String content) {
        String trimmed = content.strip();
        if (trimmed.startsWith("```")) {
            int firstNl = trimmed.indexOf('\n');
            if (firstNl > 0) {
                trimmed = trimmed.substring(firstNl + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
            return trimmed.strip();
        }
        return content;
    }

    private static Map<String, Object> openAiResponse(String model, String content, CompletionResponse response) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content);

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", response.finishReason() == null ? "stop" : response.finishReason());

        Map<String, Object> usage = new LinkedHashMap<>();
        int promptTokens = 0;
        int completionTokens = 0;
        if (response.usage() != null) {
            promptTokens = response.usage().promptTokens() != null ? response.usage().promptTokens() : 0;
            completionTokens = response.usage().completionTokens() != null ? response.usage().completionTokens() : 0;
        }
        usage.put("prompt_tokens", promptTokens);
        usage.put("completion_tokens", completionTokens);
        usage.put("total_tokens", promptTokens + completionTokens);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("object", "chat.completion");
        out.put("model", model);
        out.put("choices", List.of(choice));
        out.put("usage", usage);
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Double asDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }

    private static Integer asInteger(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        return null;
    }
}
