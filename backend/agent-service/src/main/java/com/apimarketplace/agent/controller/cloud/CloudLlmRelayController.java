package com.apimarketplace.agent.controller.cloud;

import com.apimarketplace.agent.cloud.CeRelayReleaseRequest;
import com.apimarketplace.agent.cloud.CeRelaySettleRequest;
import com.apimarketplace.agent.cloud.CloudLlmRelayRequest;
import com.apimarketplace.agent.cloud.CloudLlmStreamEvent;
import com.apimarketplace.agent.cloud.CloudRelaySupport;
import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.SystemBlock;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.UsageInfo;
import com.apimarketplace.agent.factory.BridgeAvailabilityFilter;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import com.apimarketplace.agent.service.cloud.CeRelayAccrualStore;
import com.apimarketplace.agent.service.cloud.CeRelaySettlementService;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cloud-side relay used by linked CE installs. It validates the CE link and
 * bills the cloud account, then forwards only the model completion stream.
 * Tool execution, observability, task state, and workflow state remain local
 * to the CE runtime.
 *
 * <p><b>Two billing modes</b> share the {@code CE_LLM_RELAY} sourceType:
 * <ul>
 *   <li><b>Legacy per-call</b> (request {@code executionId == null}, or the kill-switch
 *       {@code ce-relay.centralized-billing.enabled=false}): each forwarded completion bills
 *       immediately with a unique {@code "ce-llm-"+UUID} sourceId - one ledger line per call.</li>
 *   <li><b>Centralized per-execution</b> (request carries an {@code executionId}): forwarded calls
 *       are NOT billed; their usage is accrued in {@link CeRelayAccrualStore}, the per-call gate
 *       checks {@code balance ≥ cost(accrued + nextCall)}, and the execution settles as ONE ledger
 *       line via {@code /settle} (or the crash-recovery reaper). Mirrors the in-cloud agent path's
 *       "aggregate then settle once" granularity.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/ce-llm")
public class CloudLlmRelayController {

    private static final String INSTALL_HEADER = "X-LiveContext-Install-Id";
    private static final String SOURCE_TYPE = "CE_LLM_RELAY";
    private static final int DEFAULT_COMPLETION_ESTIMATE = 8192;

    private final AuthClient authClient;
    private final CreditConsumptionClient creditClient;
    private final LLMProviderFactory providerFactory;
    private final ObjectMapper objectMapper;
    private final CeRelayAccrualStore accrualStore;
    private final CeRelaySettlementService settlementService;
    private final ModelConfigOverrideRepository modelConfigRepository;

    /**
     * Cloud kill-switch. When {@code false}, the relay ignores {@code executionId} and bills every
     * call per-call (legacy) even if a CE install opts into centralized billing - a safe fallback
     * (unique sourceIds, no double- or under-billing), losing only the per-message aggregation.
     */
    private final boolean centralizedBillingEnabled;

    public CloudLlmRelayController(AuthClient authClient,
                                   CreditConsumptionClient creditClient,
                                   LLMProviderFactory providerFactory,
                                   ObjectMapper objectMapper,
                                   CeRelayAccrualStore accrualStore,
                                   CeRelaySettlementService settlementService,
                                   ModelConfigOverrideRepository modelConfigRepository,
                                   @Value("${ce-relay.centralized-billing.enabled:true}") boolean centralizedBillingEnabled) {
        this.authClient = authClient;
        this.creditClient = creditClient;
        this.providerFactory = providerFactory;
        this.objectMapper = objectMapper;
        this.accrualStore = accrualStore;
        this.settlementService = settlementService;
        this.modelConfigRepository = modelConfigRepository;
        this.centralizedBillingEnabled = centralizedBillingEnabled;
    }

    @PostMapping("/complete")
    public ResponseEntity<?> complete(
            @RequestHeader("X-User-ID") Long cloudUserId,
            @RequestHeader(INSTALL_HEADER) String installId,
            @RequestBody CloudLlmRelayRequest relayRequest) {
        ValidationResult validation = validate(cloudUserId, installId, relayRequest);
        if (!validation.ok()) {
            return validation.errorResponse();
        }

        LLMProvider billedProvider = providerFactory.getProvider(relayRequest.provider());
        String billedModel = resolveModel(billedProvider, relayRequest.completionRequest());
        if (isUnmanagedRequestedModel(billedProvider, relayRequest.completionRequest(), billedModel)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "MODEL_NOT_SUPPORTED"));
        }
        // Model execution links deliberately do NOT apply to CE relay traffic: a linked CE install
        // asked for the billed pair and must get exactly that provider's real API. Links (including
        // bridge targets) only reroute in-cloud executions.
        String userId = String.valueOf(cloudUserId);
        boolean centralized = isCentralized(relayRequest);
        CompletionRequest request = withCloudTenant(
                relayRequest.completionRequest(), userId, billedModel, false);
        BudgetEstimate estimate = estimateBudget(request);
        if (!gate(userId, billedProvider.getProviderName(), billedModel, estimate, centralized, relayRequest.executionId())) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(Map.of("error", "INSUFFICIENT_CREDITS"));
        }

        BillingTarget target = new BillingTarget(centralized, userId, relayRequest.executionId(),
                "ce-llm-" + UUID.randomUUID(), billedProvider.getProviderName(), billedModel);
        CompletionResponse response = billedProvider.complete(request);
        TokenUsage usage = usageFrom(response, estimate, response != null ? response.content() : null);
        recordUsageOnce(new AtomicBoolean(false), target, usage);
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/stream", produces = "application/x-ndjson")
    public ResponseEntity<StreamingResponseBody> stream(
            @RequestHeader("X-User-ID") Long cloudUserId,
            @RequestHeader(INSTALL_HEADER) String installId,
            @RequestBody CloudLlmRelayRequest relayRequest) {
        ValidationResult validation = validate(cloudUserId, installId, relayRequest);
        if (!validation.ok()) {
            return ResponseEntity.status(validation.status())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(outputStream -> writeEvent(outputStream,
                            CloudLlmStreamEvent.error(validation.errorCode())));
        }

        LLMProvider billedProvider = providerFactory.getProvider(relayRequest.provider());
        String billedModel = resolveModel(billedProvider, relayRequest.completionRequest());
        if (isUnmanagedRequestedModel(billedProvider, relayRequest.completionRequest(), billedModel)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(outputStream -> writeEvent(outputStream,
                            CloudLlmStreamEvent.error("MODEL_NOT_SUPPORTED")));
        }
        // Model execution links deliberately do NOT apply to CE relay traffic: a linked CE install
        // asked for the billed pair and must get exactly that provider's real API. Links (including
        // bridge targets) only reroute in-cloud executions.
        String userId = String.valueOf(cloudUserId);
        boolean centralized = isCentralized(relayRequest);
        CompletionRequest request = withCloudTenant(
                relayRequest.completionRequest(), userId, billedModel, true);
        BudgetEstimate estimate = estimateBudget(request);
        if (!gate(userId, billedProvider.getProviderName(), billedModel, estimate, centralized, relayRequest.executionId())) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(outputStream -> writeEvent(outputStream,
                            CloudLlmStreamEvent.error("INSUFFICIENT_CREDITS")));
        }

        BillingTarget target = new BillingTarget(centralized, userId, relayRequest.executionId(),
                "ce-llm-" + UUID.randomUUID(), billedProvider.getProviderName(), billedModel);
        StreamingResponseBody body = outputStream -> {
            AtomicInteger streamedContentChars = new AtomicInteger(0);
            AtomicBoolean recorded = new AtomicBoolean(false);
            AtomicBoolean streamClosed = new AtomicBoolean(false);
            try {
                billedProvider.completeStreaming(request, new StreamingCallback() {
                    @Override
                    public void onChunk(String chunk) {
                        if (streamClosed.get()) {
                            return;
                        }
                        if (chunk != null) {
                            streamedContentChars.addAndGet(chunk.length());
                        }
                        writeQuietly(outputStream, CloudLlmStreamEvent.content(chunk), streamClosed);
                    }

                    @Override
                    public void onThinking(String thinking) {
                        if (!streamClosed.get()) {
                            writeQuietly(outputStream, CloudLlmStreamEvent.thinking(thinking), streamClosed);
                        }
                    }

                    @Override
                    public void onToolCall(com.apimarketplace.agent.domain.ToolCall toolCall) {
                        if (!streamClosed.get()) {
                            writeQuietly(outputStream, CloudLlmStreamEvent.toolCall(toolCall), streamClosed);
                        }
                    }

                    @Override
                    public void onComplete(CompletionResponse response) {
                        TokenUsage usage = usageFrom(response, estimate, streamedContentChars.get());
                        recordUsageOnce(recorded, target, usage);
                        if (!streamClosed.get()) {
                            writeQuietly(outputStream, CloudLlmStreamEvent.completed(response), streamClosed);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        if (!streamClosed.get()) {
                            writeQuietly(outputStream, CloudLlmStreamEvent.error(error), streamClosed);
                        }
                    }

                    @Override
                    public boolean shouldStop() {
                        return streamClosed.get();
                    }
                });
            } catch (Exception e) {
                log.warn("CE LLM relay stream failed for cloudUser={} installId={} billed={}/{}: {}",
                        cloudUserId, installId, billedProvider.getProviderName(), billedModel,
                        e.getMessage());
                if (!streamClosed.get()) {
                    writeQuietly(outputStream, CloudLlmStreamEvent.error(e.getMessage()), streamClosed);
                }
            } finally {
                if (streamedContentChars.get() > 0) {
                    recordUsageOnce(recorded, target,
                            usageFrom(null, estimate, streamedContentChars.get()));
                }
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .body(body);
    }

    /**
     * Terminal settle for a centralized execution: writes the ONE {@code CE_LLM_RELAY} ledger line
     * from the cloud-side accrual (idempotent on {@code executionId}) and drops the accrual. A
     * {@code RETRY} outcome (transient debit failure) keeps the accrual so the reaper retries -
     * so a transient auth-service outage never loses the billing.
     */
    @PostMapping("/settle")
    public ResponseEntity<?> settle(
            @RequestHeader("X-User-ID") Long cloudUserId,
            @RequestHeader(INSTALL_HEADER) String installId,
            @RequestBody CeRelaySettleRequest req) {
        ValidationResult validation = validateLink(cloudUserId, installId);
        if (!validation.ok()) {
            return validation.errorResponse();
        }
        if (req == null || req.executionId() == null || req.executionId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_SETTLE_REQUEST"));
        }
        CeRelaySettlementService.SettleOutcome outcome = settlementService.settleFromAccrual(req.executionId());
        return ResponseEntity.ok(Map.of("settled", outcome != CeRelaySettlementService.SettleOutcome.RETRY));
    }

    /**
     * Drop the accrual for an execution that incurred no billable usage (0-call or cancelled
     * before any forwarded completion). Idempotent.
     */
    @PostMapping("/release")
    public ResponseEntity<?> release(
            @RequestHeader("X-User-ID") Long cloudUserId,
            @RequestHeader(INSTALL_HEADER) String installId,
            @RequestBody CeRelayReleaseRequest req) {
        ValidationResult validation = validateLink(cloudUserId, installId);
        if (!validation.ok()) {
            return validation.errorResponse();
        }
        if (req == null || req.executionId() == null || req.executionId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_RELEASE_REQUEST"));
        }
        accrualStore.remove(req.executionId());
        return ResponseEntity.ok(Map.of("released", true));
    }

    private boolean isCentralized(CloudLlmRelayRequest relayRequest) {
        return centralizedBillingEnabled
                && relayRequest.executionId() != null
                && !relayRequest.executionId().isBlank();
    }

    /**
     * Pre-flight budget gate. Legacy bills per call so it gates on the next call's estimate alone;
     * centralized defers the debit to settle, so it must gate on {@code accrued + nextCall} to keep
     * a multi-turn execution from over-spending past the wallet before the terminal settle lands.
     */
    private boolean gate(String userId, String provider, String model,
                         BudgetEstimate estimate, boolean centralized, String executionId) {
        long prompt = estimate.promptTokens();
        long completion = estimate.completionTokens();
        if (centralized) {
            CeRelayAccrualStore.AccruedUsage acc = accrualStore.snapshot(executionId)
                    .map(CeRelayAccrualStore.AccruedSnapshot::usage)
                    .orElse(CeRelayAccrualStore.AccruedUsage.ZERO);
            prompt += acc.promptTokens();
            completion += acc.completionTokens();
        }
        return creditClient.checkChatBudget(userId, provider, model,
                (int) Math.min(Integer.MAX_VALUE, prompt),
                (int) Math.min(Integer.MAX_VALUE, completion));
    }

    private void recordUsageOnce(AtomicBoolean recorded, BillingTarget target, TokenUsage usage) {
        if (!recorded.compareAndSet(false, true)) {
            return;
        }
        if (target.centralized()) {
            accrualStore.accrue(target.executionId(), target.userId(), target.provider(), target.model(),
                    toAccruedDelta(usage), System.currentTimeMillis());
        } else {
            consumeOrPersist(target.userId(), target.sourceId(), target.provider(), target.model(), usage);
        }
    }

    private static CeRelayAccrualStore.AccruedUsage toAccruedDelta(TokenUsage u) {
        return new CeRelayAccrualStore.AccruedUsage(
                u.promptTokens(), u.completionTokens(),
                u.cacheCreationTokens() != null ? u.cacheCreationTokens() : 0,
                u.cacheReadTokens() != null ? u.cacheReadTokens() : 0,
                u.cachedTokens() != null ? u.cachedTokens() : 0,
                u.reasoningTokens() != null ? u.reasoningTokens() : 0);
    }

    private ValidationResult validate(Long cloudUserId, String installId, CloudLlmRelayRequest relayRequest) {
        ValidationResult link = validateLink(cloudUserId, installId);
        if (!link.ok()) {
            return link;
        }
        if (relayRequest == null
                || relayRequest.provider() == null
                || relayRequest.provider().isBlank()
                || relayRequest.completionRequest() == null) {
            return ValidationResult.error(HttpStatus.BAD_REQUEST, "INVALID_RELAY_REQUEST");
        }
        if (BridgeAvailabilityFilter.BRIDGE_PROVIDER_TO_CLI_ID.containsKey(
                relayRequest.provider().toLowerCase())) {
            return ValidationResult.error(HttpStatus.BAD_REQUEST, "BRIDGE_PROVIDER_NOT_RELAYABLE");
        }
        if (!CloudRelaySupport.isSupportedProvider(relayRequest.provider())) {
            return ValidationResult.error(HttpStatus.BAD_REQUEST, "PROVIDER_NOT_RELAYABLE");
        }
        return ValidationResult.success();
    }

    private ValidationResult validateLink(Long cloudUserId, String installId) {
        if (cloudUserId == null) {
            return ValidationResult.error(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED");
        }
        if (!authClient.userOwnsActiveCeLink(String.valueOf(cloudUserId), installId)) {
            return ValidationResult.error(HttpStatus.FORBIDDEN, "CE_LINK_NOT_ACTIVE");
        }
        return ValidationResult.success();
    }

    private static String resolveModel(LLMProvider provider, CompletionRequest request) {
        String model = request.model();
        return model != null && !model.isBlank() ? model : provider.getDefaultModel();
    }

    /**
     * True when the CALLER explicitly asked for a model the cloud no longer curates. A relay
     * completion may only run a model the cloud actually manages: the cloud's
     * {@code model_config_overrides} table is the exact source the catalog bundle is built
     * from, so an explicitly-requested {@code (provider, model)} with no row there is in no
     * bundle a linked CE could be holding - the CE is on a stale (or foreign) catalog and the
     * user picked a model the cloud no longer manages. The caller rejects such a request early
     * with {@code MODEL_NOT_SUPPORTED}, BEFORE the budget gate, so an unknown model can never
     * masquerade as {@code INSUFFICIENT_CREDITS} (the budget check would also fail it, for the
     * wrong reason). The CE turns this code into a "refresh your model bundle" prompt.
     *
     * <p>Only validates an EXPLICIT model: a blank request resolves to the provider default,
     * which is trusted and skipped - the guard targets exactly "the model the user wanted",
     * never a fallback. Absence-only otherwise: a present-but-disabled or deprecated row still
     * relays, so an in-flight install running a model the cloud is sunsetting is not cut off
     * mid-stream - it simply stops being offered once the next bundle drops the row.
     */
    private boolean isUnmanagedRequestedModel(LLMProvider provider, CompletionRequest request, String model) {
        String requested = request.model();
        if (requested == null || requested.isBlank()) {
            return false;
        }
        return modelConfigRepository.findByProviderAndModelId(provider.getProviderName(), model).isEmpty();
    }

    private CompletionRequest withCloudTenant(CompletionRequest source,
                                              String cloudUserId,
                                              String model,
                                              boolean streaming) {
        return CompletionRequest.builder()
                .tenantId(cloudUserId)
                .model(model)
                .systemPrompt(source.systemPrompt())
                .userPrompt(source.userPrompt())
                .conversationHistory(source.conversationHistory())
                .temperature(source.temperature())
                .maxTokens(source.maxTokens())
                .topP(source.topP())
                .frequencyPenalty(source.frequencyPenalty())
                .presencePenalty(source.presencePenalty())
                .tools(source.tools())
                .stream(streaming)
                .metadata(source.metadata())
                .includeThoughts(source.includeThoughts())
                .thinkingBudget(source.thinkingBudget())
                .thinkingLevel(source.thinkingLevel())
                .purpose(source.purpose())
                .systemBlocks(source.systemBlocks())
                .lastTurnAt(source.lastTurnAt())
                // Keep the CE-resolved reasoning effort: ClaudeProvider maps it to
                // output_config.effort on supporting models - dropping it here would
                // silently reset a relayed request to the API default (high).
                .reasoningEffort(source.reasoningEffort())
                .build();
    }

    private BudgetEstimate estimateBudget(CompletionRequest request) {
        int chars = textLength(request.effectiveSystemPrompt())
                + textLength(request.userPrompt())
                + historyLength(request.conversationHistory())
                + systemBlocksLength(request.systemBlocks())
                + serializedLength(request.tools());
        int promptTokens = Math.max(1, (chars + 3) / 4);
        int completionTokens = request.maxTokens() != null && request.maxTokens() > 0
                ? request.maxTokens()
                : DEFAULT_COMPLETION_ESTIMATE;
        return new BudgetEstimate(promptTokens, completionTokens);
    }

    private static int historyLength(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Message message : messages) {
            if (message != null) {
                total += textLength(message.content());
                total += serializedToolCallsLength(message.toolCalls());
            }
        }
        return total;
    }

    private static int systemBlocksLength(List<SystemBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (SystemBlock block : blocks) {
            if (block != null) {
                total += textLength(block.text());
            }
        }
        return total;
    }

    private int serializedLength(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }
        try {
            return objectMapper.writeValueAsString(tools).length();
        } catch (Exception ignored) {
            return tools.size() * 256;
        }
    }

    private static int serializedToolCallsLength(List<com.apimarketplace.agent.domain.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return 0;
        }
        return toolCalls.size() * 128;
    }

    private static TokenUsage usageFrom(CompletionResponse response, BudgetEstimate estimate, String fallbackContent) {
        return usageFrom(response, estimate, textLength(fallbackContent));
    }

    private static TokenUsage usageFrom(CompletionResponse response, BudgetEstimate estimate, int fallbackContentChars) {
        UsageInfo usage = response != null ? response.usage() : null;
        int prompt = usage != null && usage.promptTokens() != null
                ? usage.promptTokens()
                : estimate.promptTokens();
        int completion = usage != null && usage.completionTokens() != null
                ? usage.completionTokens()
                : Math.max(0, fallbackContentChars / 4);
        // Cache/reasoning counters flow through so billing applies the provider's
        // true cache discounts (direct-API semantics: prompt excludes Anthropic cache).
        return new TokenUsage(prompt, completion,
                usage != null ? usage.cacheCreationInputTokens() : null,
                usage != null ? usage.cacheReadInputTokens() : null,
                usage != null ? usage.cachedTokens() : null,
                usage != null ? usage.reasoningTokens() : null);
    }

    /**
     * Debit one ledger line. Returns {@code true} when the debit landed (incl. the idempotent
     * already-settled no-op), {@code false} on a hard failure (transport / no-subscription) so a
     * caller (settle) can keep the accrual for the reaper to retry.
     */
    private boolean consumeOrPersist(String cloudUserId,
                                     String sourceId,
                                     String provider,
                                     String model,
                                     TokenUsage usage) {
        Map<String, Object> result = creditClient.consumeCredits(
                cloudUserId,
                SOURCE_TYPE,
                sourceId,
                provider,
                model,
                usage.promptTokens(),
                usage.completionTokens(),
                new com.apimarketplace.common.credit.LlmCacheTokens(
                        usage.cacheCreationTokens(),
                        usage.cacheReadTokens(),
                        usage.cachedTokens(),
                        usage.reasoningTokens()));
        if (result != null && Boolean.FALSE.equals(result.get("success"))) {
            String reason = String.valueOf(result.getOrDefault("error", "unknown rejection"));
            creditClient.persistRejection(cloudUserId, SOURCE_TYPE, sourceId,
                    provider, model, usage.promptTokens(), usage.completionTokens(), reason);
            return false;
        }
        return true;
    }

    private void writeQuietly(OutputStream outputStream,
                              CloudLlmStreamEvent event,
                              AtomicBoolean streamClosed) {
        try {
            writeEvent(outputStream, event);
        } catch (IOException e) {
            streamClosed.set(true);
        }
    }

    private void writeEvent(OutputStream outputStream, CloudLlmStreamEvent event) throws IOException {
        byte[] payload = objectMapper.writeValueAsBytes(event);
        synchronized (outputStream) {
            outputStream.write(payload);
            outputStream.write('\n');
            outputStream.flush();
        }
    }

    private static int textLength(String text) {
        return text == null ? 0 : text.length();
    }

    private record BudgetEstimate(int promptTokens, int completionTokens) {
    }

    /** Per-request billing context: where this call's usage goes (accrual vs per-call ledger). */
    private record BillingTarget(boolean centralized, String userId, String executionId,
                                 String sourceId, String provider, String model) {
    }

    private record TokenUsage(int promptTokens, int completionTokens,
                              Integer cacheCreationTokens, Integer cacheReadTokens,
                              Integer cachedTokens, Integer reasoningTokens) {
    }

    private record ValidationResult(boolean ok, HttpStatus status, String errorCode) {
        static ValidationResult success() {
            return new ValidationResult(true, HttpStatus.OK, null);
        }

        static ValidationResult error(HttpStatus status, String errorCode) {
            return new ValidationResult(false, status, errorCode);
        }

        ResponseEntity<Map<String, Object>> errorResponse() {
            return ResponseEntity.status(status).body(Map.of("error", errorCode));
        }
    }
}
