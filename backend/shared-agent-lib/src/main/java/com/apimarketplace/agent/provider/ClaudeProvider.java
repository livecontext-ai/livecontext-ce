package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.compaction.ContextCompactionTools;
import com.apimarketplace.agent.domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Anthropic (Claude) LLM Provider implementation.
 * Supports both synchronous and streaming completions.
 */
@Slf4j
@Component("sharedAgentClaudeProvider")
public class ClaudeProvider extends AbstractLLMProvider {

    @Value("${ai.agent.providers.anthropic.api-key:${ANTHROPIC_API_KEY:}}")
    private String apiKey;

    @Value("${ai.agent.providers.anthropic.api-url:https://api.anthropic.com/v1/messages}")
    private String apiUrl;

    /**
     * Configured list. No hardcoded fallback - if YAML omits the key, this provider reports
     * zero models and is dropped from the catalog. Fail-closed, so a misconfiguration never
     * resurrects stale 2024 IDs.
     */
    @Value("${ai.agent.providers.anthropic.models:}")
    private List<String> configuredModels;

    @Value("${ai.agent.providers.anthropic.display-order:2}")
    private int displayOrder;

    // Stage 1a.8 - inline-attachment byte cap. See AttachmentSizeGuard#DEFAULT_MAX_INLINE_BYTES
    // for the canonical constant; keep the three provider @Value defaults in lockstep with it.
    @Value("${ai.attachments.max-inline-bytes:262144}")
    private int maxInlineAttachmentBytes;

    // Images are tokenised by dimensions, not bytes - a larger cap (aligned with the
    // producer's files.view.image-inline-max-bytes) keeps real screenshots/photos visible.
    @Value("${ai.attachments.image-max-inline-bytes:3600000}")
    private int maxInlineImageBytes;

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    // Stage 1b.2 - Anthropic native context-management thresholds.
    // Matches the shape in CONTEXT_OPTIMIZATION_PLAN.md Stage 1b.2 and
    // the Python SDK's BetaInputTokensTrigger / BetaInputTokensClearAtLeast.
    // Literals are pinned at the test layer so a future tuning change
    // (say, lowering the trigger for tighter budgets) surfaces in diff.
    static final long NATIVE_COMPACT_TRIGGER_TOKENS = 180_000L;
    static final long NATIVE_COMPACT_CLEAR_AT_LEAST_VALUE = 140_000L;
    static final Duration THINKING_IDLE_THRESHOLD = Duration.ofHours(1);

    /**
     * Clock seam for {@link #buildContextManagement} - production path
     * uses {@link Clock#systemUTC()}; {@code ClaudeClearsThinkingAfterIdleTest}
     * injects a fixed clock via {@link #setClock} to pin the idle-boundary
     * behaviour without depending on wall-clock time.
     */
    private Clock clock = Clock.systemUTC();

    void setClock(Clock clock) {
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Override
    public int getDisplayOrder() {
        return displayOrder;
    }

    @Override
    public String getProviderName() {
        return "anthropic";
    }

    /** This serialiser emits USER image attachments as native Anthropic image blocks. */
    @Override
    public boolean supportsImageAttachments() {
        return true;
    }

    @Override
    public String getDefaultModel() {
        return configuredModels != null && !configuredModels.isEmpty()
            ? configuredModels.get(0)
            : null;
    }

    @Override
    public List<String> getSupportedModels() {
        return configuredModels != null && !configuredModels.isEmpty()
            ? configuredModels
            : List.of();
    }

    @Override
    protected String getApiKey() {
        return apiKey;
    }

    @Override
    protected String getApiUrl() {
        return apiUrl;
    }

    @Override
    protected HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", resolveApiKey());
        headers.set("anthropic-version", ANTHROPIC_VERSION);
        headers.set("anthropic-beta", "prompt-caching-2024-07-31");
        return headers;
    }

    @Override
    protected Map<String, Object> buildRequestBody(CompletionRequest request) {
        Map<String, Object> body = new HashMap<>();

        String model = request.model() != null ? request.model() : getDefaultModel();
        body.put("model", model);
        body.put("max_tokens", request.maxTokens() != null ? request.maxTokens() : 4096);

        // System prompt with caching (separate in Claude API).
        // Stage 1a.1: when the caller provides layered systemBlocks, emit a
        // native system: [...] array so breakpoint-closing blocks carry
        // cache_control: {type: ephemeral}. Legacy string callers keep the
        // single-block behavior (one breakpoint at the end).
        List<Map<String, Object>> systemArray = buildClaudeSystemArray(request);
        if (!systemArray.isEmpty()) {
            body.put("system", systemArray);
        }

        // Messages (without system)
        body.put("messages", buildClaudeMessages(request));

        // Sampling params are REMOVED from the API on Fable/Mythos (any),
        // Opus 4.7+, and Sonnet 5+ - sending temperature/top_p there returns
        // 400 invalid_request_error on every request (platform.claude.com
        // migration guide, verified 2026-07-02). Same carve-out shape as
        // OpenAIProvider's reasoning models. Older models keep the historical
        // default temperature 0.7 (prevents infinite tool call loops).
        if (!rejectsSamplingParams(model)) {
            body.put("temperature", request.temperature() != null ? request.temperature() : 0.7);
            if (request.topP() != null) {
                body.put("top_p", request.topP());
            }
        }

        // Anthropic categorical effort (output_config.effort, GA - no beta
        // header; default high when omitted). Emitted only when the caller
        // resolved a level AND the model supports the parameter, clamped to
        // the nearest level the model accepts (mirrors Claude Code's
        // fall-back-to-highest-supported-at-or-below behavior).
        String effort = resolveEffortForModel(request.reasoningEffort(), model);
        if (effort != null) {
            body.put("output_config", Map.of("effort", effort));
        }

        // Tools (Claude format)
        if (request.tools() != null && !request.tools().isEmpty()) {
            body.put("tools", buildClaudeTools(request.tools()));
        }

        // Stage 1b.2 - opt-in native context management. Emitted only when
        // a strategy applies, so unrelated turns keep their current byte
        // shape (and their cached prefix hit rate).
        Map<String, Object> contextManagement = buildContextManagement(request);
        if (contextManagement != null) {
            body.put("context_management", contextManagement);
        }

        return body;
    }

    // ── Anthropic model-capability gates ────────────────────────────────────
    //
    // Derived from the model id because the Messages API has no capability
    // pre-flight. Matrix verified live 2026-07-02 against
    // platform.claude.com/docs/en/build-with-claude/effort.md and the
    // migration guide:
    //   sampling params (temperature/top_p) removed → 400:
    //       fable/mythos (any), opus >= 4.7, sonnet >= 5
    //   output_config.effort supported:
    //       fable/mythos (any), opus >= 4.5, sonnet >= 4.6
    //   xhigh: fable/mythos, opus >= 4.7, sonnet >= 5
    //   max:   fable/mythos, opus >= 4.6, sonnet >= 4.6
    //   haiku: neither effort nor sampling removal (as of Haiku 4.5).
    //
    // Id grammar: modern ids are claude-<family>-<major>[-<minor>] where the
    // minor is 1-2 digits; 6-8 digit DATE segments (claude-opus-4-20250514,
    // claude-opus-4-7-20260416) never parse as a minor thanks to the
    // 2-digit cap + boundary lookahead - the same convention BridgeAllowlist
    // uses. Legacy reversed ids (claude-3-5-sonnet-20241022) don't match and
    // keep the historical behavior (sampling sent, no effort).

    private static final java.util.regex.Pattern MODERN_CLAUDE_ID =
        java.util.regex.Pattern.compile("^claude-(opus|sonnet|haiku)-(\\d+)(?:-(\\d{1,2})(?=$|-))?");

    /** family+version as major*100+minor, or -1 when the id is not a modern opus/sonnet/haiku id. */
    private static int versionOf(String model, String family) {
        if (model == null) {
            return -1;
        }
        var m = MODERN_CLAUDE_ID.matcher(model.toLowerCase(Locale.ROOT).trim());
        if (!m.find() || !family.equals(m.group(1))) {
            return -1;
        }
        int major = Integer.parseInt(m.group(2));
        int minor = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
        return major * 100 + minor;
    }

    private static boolean isFableFamily(String model) {
        if (model == null) {
            return false;
        }
        String v = model.toLowerCase(Locale.ROOT);
        return v.contains("fable") || v.contains("mythos");
    }

    /** True when the API rejects temperature/top_p with a 400 for this model. */
    static boolean rejectsSamplingParams(String model) {
        return isFableFamily(model)
            || versionOf(model, "opus") >= 407
            || versionOf(model, "sonnet") >= 500;
    }

    private static boolean supportsEffort(String model) {
        return isFableFamily(model)
            || versionOf(model, "opus") >= 405
            || versionOf(model, "sonnet") >= 406;
    }

    private static boolean supportsXhighEffort(String model) {
        return isFableFamily(model)
            || versionOf(model, "opus") >= 407
            || versionOf(model, "sonnet") >= 500;
    }

    private static boolean supportsMaxEffort(String model) {
        return isFableFamily(model)
            || versionOf(model, "opus") >= 406
            || versionOf(model, "sonnet") >= 406;
    }

    /**
     * Map the resolved reasoning-effort level to the {@code output_config.effort}
     * value this model accepts, or {@code null} to omit the parameter (unknown
     * level, no level chosen, or model without effort support). Clamping mirrors
     * Claude Code: an unsupported level falls back to the highest supported
     * level at or below it ({@code minimal} → {@code low}: the API has no
     * minimal; {@code xhigh}/{@code max} → {@code high} where unavailable).
     */
    static String resolveEffortForModel(String rawEffort, String model) {
        ReasoningEffort level = ReasoningEffort.fromString(rawEffort);
        if (level == null || !supportsEffort(model)) {
            return null;
        }
        return switch (level) {
            case MINIMAL -> "low";
            case LOW, MEDIUM, HIGH -> level.wire();
            case XHIGH -> supportsXhighEffort(model) ? "xhigh" : "high";
            case MAX -> supportsMaxEffort(model) ? "max" : "high";
        };
    }

    /**
     * Stage 1b.2 - build the top-level {@code context_management} field
     * for the Claude Messages API. Delegates COLD-history masking to
     * Anthropic: the server edits our request <em>after</em> reading the
     * cached prefix, so the cache is NOT invalidated. This is the key
     * distinction versus client-side compaction (Stage 3): the same
     * concept, executed at a seam that preserves the cache prefix.
     *
     * <p>Emits up to two {@code edits} entries:
     * <ul>
     *   <li><b>{@code clear_tool_uses_20250919}</b> - when the estimated
     *       prompt crosses {@link #NATIVE_COMPACT_TRIGGER_TOKENS}, ask
     *       Anthropic to clear {@code tool_use.input} payloads of
     *       known-safe tools
     *       ({@link ContextCompactionTools#COMPACTABLE_TOOLS}) while
     *       preserving credential / publish / approval surfaces
     *       ({@link ContextCompactionTools#NEVER_MASK_TOOLS}).</li>
     *   <li><b>{@code clear_thinking_20251015}</b> - when the previous
     *       assistant turn (see {@link CompletionRequest#lastTurnAt})
     *       was more than {@link #THINKING_IDLE_THRESHOLD} ago, keep
     *       only the last thinking turn. The cache is cold by then
     *       anyway, so the drop is free.</li>
     * </ul>
     *
     * <p>Returns {@code null} when neither strategy applies, so the
     * caller skips adding the key entirely. This keeps the byte shape
     * of short turns identical to pre-Stage-1b.2 - a guard against
     * inadvertent cache-prefix drift.
     */
    Map<String, Object> buildContextManagement(CompletionRequest request) {
        List<Map<String, Object>> edits = new ArrayList<>();

        // Strategy 1: clear_tool_uses_20250919. Gate on estimated prompt
        // tokens (history + system + tools). This is the same estimator
        // used for rate-limit preflight, so the threshold reading stays
        // consistent with the rest of the provider.
        int promptTokens = estimatePromptTokensForContextMgmt(request);
        if (promptTokens > NATIVE_COMPACT_TRIGGER_TOKENS) {
            Map<String, Object> edit = new LinkedHashMap<>();
            edit.put("type", "clear_tool_uses_20250919");
            edit.put("trigger", Map.of(
                    "type", "input_tokens",
                    "value", NATIVE_COMPACT_TRIGGER_TOKENS));
            edit.put("clear_at_least", Map.of(
                    "type", "input_tokens",
                    "value", NATIVE_COMPACT_CLEAR_AT_LEAST_VALUE));
            edit.put("clear_tool_inputs", sortedList(ContextCompactionTools.COMPACTABLE_TOOLS));
            edit.put("exclude_tools", sortedList(ContextCompactionTools.NEVER_MASK_TOOLS));
            edits.add(edit);
        }

        // Strategy 2: clear_thinking_20251015. Only fires when the caller
        // populated lastTurnAt - non-conversation entry points leave it
        // null and skip this edit entirely.
        if (request.lastTurnAt() != null) {
            Duration idle = Duration.between(request.lastTurnAt(), Instant.now(clock));
            if (idle.compareTo(THINKING_IDLE_THRESHOLD) > 0) {
                Map<String, Object> edit = new LinkedHashMap<>();
                edit.put("type", "clear_thinking_20251015");
                edit.put("keep", Map.of(
                        "type", "thinking_turns",
                        "value", 1));
                edits.add(edit);
            }
        }

        if (edits.isEmpty()) {
            return null;
        }
        return Map.of("edits", edits);
    }

    private int estimatePromptTokensForContextMgmt(CompletionRequest request) {
        // estimateTokens() is the shared AbstractLLMProvider helper
        // (Jtokkit when wired, chars/4 fallback otherwise). Subtract
        // maxTokens so the comparison is against prompt-only, matching
        // Anthropic's trigger semantics ("input_tokens").
        int total = estimateTokens(request);
        int reserved = request.maxTokens() != null ? request.maxTokens() : 0;
        return Math.max(0, total - reserved);
    }

    private static List<String> sortedList(Set<String> values) {
        // Byte-stable ordering so the request body hashes identically
        // across retries - same discipline as buildClaudeTools.
        List<String> out = new ArrayList<>(values);
        Collections.sort(out);
        return out;
    }

    /**
     * Stage 1a.1 - serialize the system prompt as a native Claude
     * {@code system: [...]} array. Each layered block becomes one
     * {@code {type:"text", text:"…"}} element; blocks marked
     * {@link SystemBlock#cacheBreakpoint()} get {@code cache_control: ephemeral}
     * appended so Anthropic closes a prompt-cache segment at that boundary.
     *
     * <p>Fallback: when the caller only populated the legacy
     * {@link CompletionRequest#systemPrompt()} string, we emit one block with
     * a single trailing breakpoint - matching the previous behavior. Empty /
     * blank blocks are skipped so {@code cache_control} never lands on an
     * empty-text element (Anthropic's hash would otherwise diverge across
     * turns whenever an optional block toggles between blank and populated).
     *
     * <p>Returns an empty list when neither systemBlocks nor systemPrompt is
     * set, so the caller can skip adding the {@code system} field entirely.
     */
    private static List<Map<String, Object>> buildClaudeSystemArray(CompletionRequest request) {
        if (request.hasSystemBlocks()) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (SystemBlock block : request.systemBlocks()) {
                if (block.isBlank()) {
                    // A breakpoint on an empty block would waste one of
                    // Anthropic's 4 cache_control slots and, more importantly,
                    // make the serialized bytes depend on whether the block
                    // happens to be empty this turn - invalidating the cache.
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", "text");
                entry.put("text", block.text());
                if (block.cacheBreakpoint()) {
                    entry.put("cache_control", Map.of("type", "ephemeral"));
                }
                out.add(entry);
            }
            return out;
        }
        if (request.systemPrompt() != null && !request.systemPrompt().isEmpty()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", "text");
            entry.put("text", request.systemPrompt());
            entry.put("cache_control", Map.of("type", "ephemeral"));
            return List.of(entry);
        }
        return List.of();
    }

    private List<Map<String, Object>> buildClaudeMessages(CompletionRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();

        if (request.conversationHistory() != null) {
            for (Message msg : request.conversationHistory()) {
                if (msg.role() != Message.Role.SYSTEM) {
                    messages.add(convertClaudeMessage(msg));
                }
            }
        }

        // Stage 1a.1: mark the LAST history message with cache_control=ephemeral.
        // Anthropic prompt-cache breakpoint #3 of 4 (after tools + system). The last
        // history message is the stable prefix boundary: on turn N it is message M;
        // on turn N+1 a new exchange is appended, but M is still byte-identical at
        // the same position - so everything up to and including M becomes a cache
        // HIT on turn N+1. We do NOT mark the new userPrompt (it is the changing
        // suffix that invalidates on every turn). Requires both a history AND a
        // userPrompt for the sliding-window pattern to fire.
        int historyLen = messages.size();
        if (historyLen > 0 && request.userPrompt() != null) {
            markLastContentBlockForCache(messages.get(historyLen - 1));
        }

        if (request.userPrompt() != null) {
            messages.add(Map.of(
                "role", "user",
                "content", request.userPrompt()
            ));
        }

        return messages;
    }

    /**
     * Adds {@code cache_control: {type: ephemeral}} to the last content block of a
     * message. A plain-string content is promoted to a single-element content-block
     * list (Anthropic only accepts cache_control on blocks, not on strings). For an
     * existing content-block list, the last block is copied into a {@code LinkedHashMap}
     * and the marker is appended - the copy is required because {@link Map#of} blocks
     * are immutable.
     */
    @SuppressWarnings("unchecked")
    private static void markLastContentBlockForCache(Map<String, Object> message) {
        Object content = message.get("content");
        if (content instanceof String text) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "text");
            block.put("text", text);
            block.put("cache_control", Map.of("type", "ephemeral"));
            message.put("content", new ArrayList<>(List.of(block)));
        } else if (content instanceof List<?> list && !list.isEmpty()) {
            List<Map<String, Object>> blocks = new ArrayList<>((List<Map<String, Object>>) list);
            // The source block was built by convertClaudeMessage / buildMultimodalContent
            // using Map.of, whose iteration order is spec-undefined but stable within a JVM.
            // Copying into a LinkedHashMap freezes that order, then we append cache_control
            // at the end - giving byte-stable serialization for the cached prefix.
            Map<String, Object> lastBlock = new LinkedHashMap<>(blocks.get(blocks.size() - 1));
            lastBlock.put("cache_control", Map.of("type", "ephemeral"));
            blocks.set(blocks.size() - 1, lastBlock);
            message.put("content", blocks);
        }
        // content == null → no-op; Anthropic rejects such messages upstream anyway.
    }

    private Map<String, Object> convertClaudeMessage(Message message) {
        Map<String, Object> msg = new HashMap<>();

        switch (message.role()) {
            case USER -> {
                msg.put("role", "user");
                // Check for multimodal content (attachments)
                if (message.hasAttachments()) {
                    msg.put("content", buildMultimodalContent(message));
                } else {
                    msg.put("content", message.content());
                }
            }
            case ASSISTANT -> {
                msg.put("role", "assistant");
                // Stage 4a.6 (R29/R44) - preserve tool_use structure on replay.
                //
                // When an assistant turn produced tool calls, Claude requires that
                // every subsequent TOOL (tool_result) message reference a tool_use
                // id that was EMITTED by the assistant in the same conversation.
                // Collapsing the assistant message into a plain string drops those
                // ids entirely → Claude rejects the next turn with "tool_result
                // refers to unknown tool_use_id".
                //
                // AbstractLLMProvider.convertToolCalls is OpenAI-shaped
                // ({id, type:"function", function:{name, arguments:<JSON string>}})
                // and cannot be reused here - Claude expects content blocks of the
                // form {type:"tool_use", id, name, input:<object>} alongside any
                // text block. The converter below emits that shape natively.
                if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                    msg.put("content", buildClaudeAssistantToolUseBlocks(message));
                } else {
                    msg.put("content", message.content());
                }
            }
            case TOOL -> {
                msg.put("role", "user");
                msg.put("content", List.of(Map.of(
                    "type", "tool_result",
                    "tool_use_id", message.toolCallId(),
                    "content", message.content()
                )));
            }
            default -> {
                msg.put("role", "user");
                msg.put("content", message.content());
            }
        }

        return msg;
    }

    /**
     * Stage 4a.6 (R29/R44) - build the Claude-native content-block list for an
     * ASSISTANT message that emitted tool calls.
     *
     * <p>Shape per Anthropic Messages API:
     * <pre>
     * [
     *   {"type":"text","text":"..."},           // only when content is non-blank
     *   {"type":"tool_use","id":"...","name":"...","input":{...}},
     *   ...
     * ]
     * </pre>
     *
     * <p>Text block comes first when present - this mirrors the order Claude
     * produces itself in {@code parseResponse} (text accumulated before
     * tool_use blocks from the response array) and is what the model expects
     * to see on replay. A null/blank {@code content} skips the text block
     * entirely rather than emitting {@code {"type":"text","text":""}}, which
     * Anthropic rejects. A null {@code arguments} map is normalised to an
     * empty object so the {@code input} field is always present (required by
     * the API).
     *
     * <p>Uses {@link LinkedHashMap} for byte-stable serialisation within the
     * cache prefix - see the ordering discipline in
     * {@link #markLastContentBlockForCache}.
     */
    private List<Map<String, Object>> buildClaudeAssistantToolUseBlocks(Message message) {
        List<Map<String, Object>> blocks = new ArrayList<>();

        String text = message.content();
        if (text != null && !text.isBlank()) {
            Map<String, Object> textBlock = new LinkedHashMap<>();
            textBlock.put("type", "text");
            textBlock.put("text", text);
            blocks.add(textBlock);
        }

        for (ToolCall tc : message.toolCalls()) {
            if (tc == null) continue;
            Map<String, Object> toolUse = new LinkedHashMap<>();
            toolUse.put("type", "tool_use");
            toolUse.put("id", tc.id());
            toolUse.put("name", tc.toolName());
            toolUse.put("input", tc.arguments() != null ? tc.arguments() : Map.of());
            blocks.add(toolUse);
        }

        return blocks;
    }

    /**
     * Build multimodal content array for Claude API.
     * Claude supports: text, image (base64), document (PDF base64)
     */
    private List<Map<String, Object>> buildMultimodalContent(Message message) {
        List<Map<String, Object>> contentParts = new ArrayList<>();

        // Add attachments first - enforce 1a.8 size cap to prevent multi-MB base64
        // blobs from dominating input tokens.
        for (MessageAttachment attachment : message.attachments()) {
            MessageAttachment guarded = com.apimarketplace.agent.attachment.AttachmentSizeGuard
                    .enforceSizeCap(attachment, maxInlineAttachmentBytes, maxInlineImageBytes, "anthropic");
            Map<String, Object> part = buildClaudeAttachment(guarded);
            if (part != null) {
                contentParts.add(part);
            }
        }

        // Add text content last
        if (message.content() != null && !message.content().isBlank()) {
            contentParts.add(Map.of("type", "text", "text", message.content()));
        }

        return contentParts;
    }

    /**
     * Build a Claude attachment block based on type.
     */
    private Map<String, Object> buildClaudeAttachment(MessageAttachment attachment) {
        return switch (attachment.type()) {
            case IMAGE -> Map.of(
                "type", "image",
                "source", Map.of(
                    "type", "base64",
                    "media_type", attachment.mimeType(),
                    "data", Base64.getEncoder().encodeToString(attachment.data())
                )
            );
            case PDF -> Map.of(
                "type", "document",
                "source", Map.of(
                    "type", "base64",
                    "media_type", "application/pdf",
                    "data", Base64.getEncoder().encodeToString(attachment.data())
                )
            );
            case TEXT -> Map.of(
                "type", "text",
                "text", "[File: " + attachment.fileName() + "]\n" + attachment.getTextContent()
            );
            case OTHER -> Map.of(
                "type", "text",
                "text", "[Attachment: " + attachment.fileName() + " - unsupported format]"
            );
        };
    }

    private List<Map<String, Object>> buildClaudeTools(List<ToolDefinition> tools) {
        List<Map<String, Object>> result = new ArrayList<>();

        // Stage 1a.1: sort by tool name and build each tool as a LinkedHashMap so pod
        // restarts (ConcurrentHashMap iteration in CoreToolsProvider, HashMap rehash
        // across JVMs) do not shuffle the serialized tools array. A byte-stable prefix
        // is required for Anthropic prompt caching - one reordered tool or one swapped
        // JSON key invalidates the entire cached tools block.
        List<ToolDefinition> sorted = tools.stream()
            .sorted(Comparator.comparing(ToolDefinition::name,
                    Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

        for (int i = 0; i < sorted.size(); i++) {
            ToolDefinition tool = sorted.get(i);
            Map<String, Object> claudeTool = new LinkedHashMap<>();
            claudeTool.put("name", tool.name());
            claudeTool.put("description", tool.description());
            claudeTool.put("input_schema", buildParametersSchema(tool));
            // Stage 1a.1: mark the LAST tool with cache_control=ephemeral. Anthropic treats
            // this as prompt-cache breakpoint #1 of 4 (order: tools → system → messages);
            // everything in the tools array up to and including this marker is cached as
            // one prefix. Tools are the most stable prefix, so a breakpoint here gives the
            // biggest cache-hit surface across turns. Requires deterministic tool order
            // (sort above) + byte-stable key order (LinkedHashMap) to actually hit.
            if (i == sorted.size() - 1) {
                claudeTool.put("cache_control", Map.of("type", "ephemeral"));
            }
            result.add(claudeTool);
        }

        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected CompletionResponse parseResponse(Map<String, Object> response) {
        List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
        String stopReason = (String) response.get("stop_reason");
        String model = (String) response.get("model");
        Map<String, Object> usage = (Map<String, Object>) response.get("usage");

        StringBuilder textContent = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();

        if (content != null) {
            for (Map<String, Object> block : content) {
                String type = (String) block.get("type");

                if ("text".equals(type)) {
                    textContent.append(block.get("text"));
                } else if ("tool_use".equals(type)) {
                    String id = (String) block.get("id");
                    String name = (String) block.get("name");
                    Map<String, Object> input = (Map<String, Object>) block.get("input");

                    toolCalls.add(ToolCall.builder()
                        .id(id)
                        .toolName(name)
                        .arguments(input != null ? input : Map.of())
                        .index(toolCalls.size())
                        .build());
                }
            }
        }

        return CompletionResponse.builder()
            .content(textContent.toString())
            .toolCalls(toolCalls.isEmpty() ? null : toolCalls)
            .finishReason(stopReason)
            .usage(parseClaudeUsage(usage))
            .model(model)
            .build();
    }

    private UsageInfo parseClaudeUsage(Map<String, Object> usage) {
        if (usage == null) {
            return null;
        }

        Integer input = getIntValue(usage, "input_tokens");
        Integer output = getIntValue(usage, "output_tokens");
        Integer cacheCreation = getIntValue(usage, "cache_creation_input_tokens");
        Integer cacheRead = getIntValue(usage, "cache_read_input_tokens");

        return UsageInfo.builder()
            .promptTokens(input)
            .completionTokens(output)
            .totalTokens((input != null ? input : 0) + (output != null ? output : 0))
            .cacheCreationInputTokens(cacheCreation)
            .cacheReadInputTokens(cacheRead)
            .build();
    }

    @Override
    protected String processStreamingLine(String line) {
        // Claude format: event: content_block_delta\ndata: {"delta":{"text":"..."}}
        if (!line.startsWith("data: ")) {
            return null;
        }

        String data = line.substring(6).trim();
        if (data.isEmpty()) {
            return null;
        }

        try {
            JsonNode node = objectMapper.readTree(data);

            // Check for content_block_delta type
            String type = node.has("type") ? node.get("type").asText() : null;
            if ("content_block_delta".equals(type)) {
                JsonNode delta = node.get("delta");
                if (delta != null && delta.has("text")) {
                    JsonNode textNode = delta.get("text");
                    // Guard JSON null: asText() on a NullNode returns the literal "null".
                    if (textNode != null && !textNode.isNull()) {
                        return textNode.asText();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error parsing Claude streaming line: {}", e.getMessage());
        }

        return null;
    }

    @Override
    protected List<ToolCall> parseStreamingToolCalls(String line) {
        // Tool calls are now accumulated via accumulateStreamingToolCalls
        // This method is kept for compatibility but returns null
        // The actual tool calls are built in the accumulator
        return null;
    }

    @Override
    protected void accumulateStreamingToolCalls(String line, Map<Integer, StreamingToolCallAccumulator> accumulators) {
        if (!line.startsWith("data: ")) {
            return;
        }

        String data = line.substring(6).trim();
        if (data.isEmpty()) {
            return;
        }

        try {
            JsonNode node = objectMapper.readTree(data);
            String type = node.has("type") ? node.get("type").asText() : null;

            // content_block_start with tool_use: capture id and name
            if ("content_block_start".equals(type)) {
                JsonNode contentBlock = node.get("content_block");
                if (contentBlock != null && "tool_use".equals(contentBlock.get("type").asText())) {
                    int index = node.has("index") ? node.get("index").asInt() : 0;
                    StreamingToolCallAccumulator acc = accumulators.computeIfAbsent(index, k -> new StreamingToolCallAccumulator());
                    acc.id = contentBlock.get("id").asText();
                    acc.name = contentBlock.get("name").asText();
                    log.debug("Claude tool call start: index={}, id={}, name={}", index, acc.id, acc.name);
                }
            }
            // content_block_delta with input_json_delta: accumulate partial JSON
            else if ("content_block_delta".equals(type)) {
                JsonNode delta = node.get("delta");
                if (delta != null && "input_json_delta".equals(delta.get("type").asText())) {
                    int index = node.has("index") ? node.get("index").asInt() : 0;
                    StreamingToolCallAccumulator acc = accumulators.get(index);
                    if (acc != null) {
                        String partialJson = delta.has("partial_json") ? delta.get("partial_json").asText() : "";
                        acc.arguments.append(partialJson);
                        log.trace("Claude tool call delta: index={}, partial={}", index, partialJson);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error accumulating Claude streaming tool calls: {}", e.getMessage());
        }
    }

    @Override
    protected UsageInfo extractStreamingUsage(String line) {
        if (!line.startsWith("data: ")) {
            return null;
        }

        String data = line.substring(6).trim();
        if (data.isEmpty()) {
            return null;
        }

        try {
            JsonNode node = objectMapper.readTree(data);
            String type = node.has("type") ? node.get("type").asText() : null;

            // message_start contains input token count + cache tokens
            // Format: {"type":"message_start","message":{"usage":{"input_tokens":N,"cache_creation_input_tokens":N,"cache_read_input_tokens":N}}}
            if ("message_start".equals(type)) {
                JsonNode message = node.get("message");
                if (message != null && message.has("usage")) {
                    JsonNode usage = message.get("usage");
                    Integer inputTokens = usage.has("input_tokens") ? usage.get("input_tokens").asInt() : null;
                    Integer cacheCreation = usage.has("cache_creation_input_tokens") ? usage.get("cache_creation_input_tokens").asInt() : null;
                    Integer cacheRead = usage.has("cache_read_input_tokens") ? usage.get("cache_read_input_tokens").asInt() : null;
                    if (inputTokens != null) {
                        log.debug("📊 [CLAUDE USAGE] message_start: input_tokens={}, cache_creation={}, cache_read={}",
                            inputTokens, cacheCreation, cacheRead);
                        return UsageInfo.builder()
                            .promptTokens(inputTokens)
                            .completionTokens(0)
                            .totalTokens(inputTokens)
                            .cacheCreationInputTokens(cacheCreation)
                            .cacheReadInputTokens(cacheRead)
                            .build();
                    }
                }
            }

            // message_delta contains output token count
            // Format: {"type":"message_delta","usage":{"output_tokens":N}}
            if ("message_delta".equals(type)) {
                JsonNode usage = node.get("usage");
                if (usage != null) {
                    Integer outputTokens = usage.has("output_tokens") ? usage.get("output_tokens").asInt() : null;
                    if (outputTokens != null) {
                        log.debug("📊 [CLAUDE USAGE] message_delta: output_tokens={}", outputTokens);
                        return UsageInfo.builder()
                            .promptTokens(0)
                            .completionTokens(outputTokens)
                            .totalTokens(outputTokens)
                            .build();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting Claude streaming usage: {}", e.getMessage());
        }

        return null;
    }

    @Override
    protected boolean isEndOfStream(String line) {
        if (line.startsWith("data: ")) {
            String data = line.substring(6).trim();
            try {
                JsonNode node = objectMapper.readTree(data);
                String type = node.has("type") ? node.get("type").asText() : null;
                return "message_stop".equals(type);
            } catch (Exception e) {
                // Ignore
            }
        }
        return false;
    }
}
