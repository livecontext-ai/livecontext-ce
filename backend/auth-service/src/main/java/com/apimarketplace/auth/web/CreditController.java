package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.domain.ModelPricing;
import com.apimarketplace.auth.service.CreditService;
import com.apimarketplace.auth.service.LlmTokenBreakdown;
import com.apimarketplace.auth.service.ModelPricingService;
import com.apimarketplace.common.web.TenantResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/credits")
public class CreditController {

    private final CreditService creditService;
    private final ModelPricingService pricingService;

    public CreditController(CreditService creditService, ModelPricingService pricingService) {
        this.creditService = creditService;
        this.pricingService = pricingService;
    }

    /**
     * Owner-pays consume: when a workspace member fires a billable call, the
     * debit is redirected to the workspace billing owner's wallet via
     * {@link CreditService#resolvePayer}. Marketplace + BYOK paths are
     * allow-listed and always stay user-scoped.
     */
    @PostMapping("/consume")
    public ResponseEntity<CreditService.CreditConsumeResult> consume(
            @RequestHeader("X-User-ID") Long userId,
            @RequestBody CreditConsumeRequest request) {

        if (request.sourceType() == null) {
            throw new IllegalArgumentException("sourceType must not be null");
        }

        CreditService.CreditConsumeResult result = switch (request.sourceType()) {
            case "AGENT_EXECUTION", "CLASSIFY_EXECUTION", "GUARDRAIL_EXECUTION",
                 "COMPACTION_SUMMARY", "BROWSER_AGENT_EXECUTION",
                 // CLI/bridge sessions (claude-code/codex/gemini): zero billed tokens (the
                 // external CLI pays its own provider), so consumeForAgent computes ~0 credits
                 // and writes a clean CLI_SESSION ledger row. Must be allow-listed here, else
                 // every CLI/bridge observability write would 500 → rejection + dead-letter spam.
                 "CLI_SESSION" ->
                    creditService.consumeForAgent(
                            userId, request.sourceId(), request.provider(), request.model(),
                            request.toTokenBreakdown(),
                            request.sourceType());
            // CE_LLM_RELAY uses an idempotent post-flight path (keyed on (sourceId,
            // CE_LLM_RELAY)) so the centralized per-execution settle and the crash-recovery
            // reaper can both fire with the same executionId without double-billing. The
            // legacy per-call relay (unique "ce-llm-"+UUID sourceIds) is unaffected - the
            // idempotency guard never matches a fresh id.
            case "CE_LLM_RELAY" -> creditService.consumeForCeRelay(
                    userId, request.sourceId(), request.provider(), request.model(),
                    request.toTokenBreakdown());
            case "WORKFLOW_NODE" -> creditService.consumeForWorkflowNode(userId, request.sourceId());
            case "CHAT_CONVERSATION" -> creditService.consumeForChat(
                    userId, request.sourceId(), request.provider(), request.model(),
                    request.toTokenBreakdown());
            case "MARKETPLACE_PURCHASE" -> creditService.consumeForMarketplacePurchase(
                    userId, request.sourceId(), request.cost() != null ? request.cost() : 0);
            case "WEB_SEARCH" -> creditService.consumeForWebSearch(userId, request.sourceId());
            case "WEB_FETCH" -> creditService.consumeForWebFetch(userId, request.sourceId());
            case "IMAGE_GENERATION" -> creditService.consumeForImageGeneration(
                    userId, request.sourceId(), request.provider(), request.model(),
                    // imageCount = actualImageCount returned by the provider; defaults
                    // to 1 for backward-compat with old client builds that don't set it.
                    request.imageCount() != null ? request.imageCount() : 1);
            case "IMAGE_GENERATION_BYOK" -> creditService.consumeForImageGenerationByok(
                    userId, request.sourceId(), request.provider(), request.model(),
                    request.imageCount() != null ? request.imageCount() : 1);
            default -> throw new IllegalArgumentException("Unknown source type: " + request.sourceType());
        };

        if (!result.success()) {
            return ResponseEntity.status(402).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * V250 - bucket-aware balance view. The {@code balance} field stays the
     * canonical total (sub + payg) for legacy clients; {@code subBalance} +
     * {@code paygBalance} expose the breakdown so the wallet widget can
     * render "X subscription / Y top-up" without a second round-trip.
     */
    @GetMapping("/balance")
    public ResponseEntity<Map<String, Object>> getBalance(
            @RequestHeader("X-User-ID") Long userId) {
        CreditService.BalanceBreakdown breakdown = creditService.getBalanceBreakdown(userId);
        return ResponseEntity.ok(Map.of(
                "balance", breakdown.balance(),
                "subBalance", breakdown.subBalance(),
                "paygBalance", breakdown.paygBalance(),
                "delinquent", breakdown.delinquent()));
    }

    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> checkCredits(
            @RequestHeader("X-User-ID") Long userId) {
        boolean sufficient = creditService.hasSufficientCredits(userId);
        BigDecimal balance = creditService.getBalance(userId);
        if (!sufficient) {
            return ResponseEntity.status(402).body(Map.of("allowed", false, "balance", balance));
        }
        return ResponseEntity.ok(Map.of("allowed", true, "balance", balance));
    }

    /**
     * Cost-aware pre-flight check for a chat turn.
     *
     * <p>The generic {@link #checkCredits} endpoint only answers "balance >= 1 credit?",
     * which leaves a gap: a user with 1.5 credits can pass the gate, the LLM runs, and
     * the post-flight {@code consumeForChat} then fails with 402 because the real cost
     * is higher than the balance. The user got a free answer and the ledger stays clean.
     *
     * <p>This endpoint closes that gap. The caller sends a conservative estimate of
     * prompt + completion tokens for the upcoming turn; we compute the projected cost
     * via {@link ModelPricingService#calculateCost} and reject with 402 if
     * {@code balance < projectedCost}. Non-committing - no ledger row is written.
     */
    @PostMapping("/check-chat")
    public ResponseEntity<Map<String, Object>> checkChatBudget(
            @RequestHeader("X-User-ID") Long userId,
            @RequestBody ChatBudgetRequest request) {
        BigDecimal balance = creditService.getBalance(userId);

        // Fail-closed on missing pricing context. Without a known (provider, model) the
        // pricing service falls back to mid-tier default rates, which under-estimate
        // frontier/bridge models by up to ~70×. The pre-flight gate would let the turn
        // through, the LLM would run, and post-flight debit would fail with 402 - the
        // exact free-inference loophole this endpoint exists to close.
        if (!pricingService.hasPricing(request.provider(), request.model())) {
            return ResponseEntity.status(402).body(Map.of(
                    "allowed", false,
                    "balance", balance,
                    "error", "Unknown provider/model - cannot verify cost",
                    "provider", String.valueOf(request.provider()),
                    "model", String.valueOf(request.model())));
        }

        int promptTokens = request.estimatedPromptTokens() != null ? request.estimatedPromptTokens() : 0;
        int completionTokens = request.estimatedCompletionTokens() != null
                ? request.estimatedCompletionTokens() : 0;
        BigDecimal estimatedCost = pricingService.calculateCost(
                request.provider(), request.model(), promptTokens, completionTokens);
        // Free workflow-credit scoping (Cloud): the chat / agent pre-flight is a
        // non-workflow source, so on the FREE plan it is gated against the PAYG
        // bucket alone (mirrors the post-flight debit routing). No-op on CE
        // (unlimited) and paid plans, where eligible balance == total balance.
        boolean allowed = creditService.canAfford(userId, estimatedCost, "CHAT_CONVERSATION");
        Map<String, Object> body = Map.of(
                "allowed", allowed,
                "balance", balance,
                "estimatedCost", estimatedCost);
        return allowed
                ? ResponseEntity.ok(body)
                : ResponseEntity.status(402).body(body);
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary(
            @RequestHeader("X-User-ID") Long userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestParam(defaultValue = "false") boolean allWorkspaces) {
        // 2026-05-22 audit follow-up: symmetric with /history - bind the active
        // workspace's orgId to TenantResolver's ThreadLocal so
        // resolveCurrentWorkspaceMembership reads it (defense-in-depth even
        // though the servlet-header path already covers in-request callers).
        // V366: usageOrgFilter narrows the consumption breakdown to the active
        // workspace unless allWorkspaces=true (the balance stays the single
        // owner-pays wallet either way).
        String orgFilter = usageOrgFilter(organizationId, allWorkspaces);
        return ResponseEntity.ok(inOrganizationScopeMap(organizationId,
                () -> creditService.getUsageSummary(userId, orgFilter)));
    }

    @GetMapping("/history")
    public ResponseEntity<Page<CreditLedgerEntry>> getHistory(
            @RequestHeader("X-User-ID") Long userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sourceType,
            @RequestParam(defaultValue = "false") boolean allWorkspaces) {

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        String orgFilter = usageOrgFilter(organizationId, allWorkspaces);
        Page<CreditLedgerEntry> history = inOrganizationScope(organizationId, () -> {
            if (sourceType != null && !sourceType.isBlank()) {
                return creditService.getUsageHistoryByType(userId, sourceType, orgFilter, pageRequest);
            }
            return creditService.getUsageHistory(userId, orgFilter, pageRequest);
        });
        return ResponseEntity.ok(history);
    }

    // Backward-compatible direct-call overload for controller unit tests and legacy callers.
    ResponseEntity<Page<CreditLedgerEntry>> getHistory(
            Long userId,
            int page,
            int size,
            String sourceType) {
        return getHistory(userId, null, page, size, sourceType, false);
    }

    /**
     * V366 (ADR-0010) - resolve the org REPORTING filter for the usage reads.
     * Returns the active workspace's orgId to slice usage to that workspace, or
     * {@code null} for the full cross-workspace aggregate. Null when the caller
     * asked for "All workspaces" ({@code allWorkspaces=true}) or there is no
     * active workspace context (blank header = personal / legacy). Never affects
     * the balance (owner-pays single wallet).
     */
    private static String usageOrgFilter(String organizationId, boolean allWorkspaces) {
        if (allWorkspaces || organizationId == null || organizationId.isBlank()) {
            return null;
        }
        return organizationId;
    }

    private Page<CreditLedgerEntry> inOrganizationScope(
            String organizationId,
            Supplier<Page<CreditLedgerEntry>> supplier) {
        AtomicReference<Page<CreditLedgerEntry>> result = new AtomicReference<>();
        TenantResolver.runWithOrgScope(organizationId, () -> result.set(supplier.get()));
        return result.get();
    }

    private Map<String, Object> inOrganizationScopeMap(
            String organizationId,
            Supplier<Map<String, Object>> supplier) {
        AtomicReference<Map<String, Object>> result = new AtomicReference<>();
        TenantResolver.runWithOrgScope(organizationId, () -> result.set(supplier.get()));
        return result.get();
    }

    @GetMapping("/analytics")
    public ResponseEntity<Map<String, Object>> getAnalytics(
            @RequestHeader("X-User-ID") Long userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String model,
            @RequestParam(defaultValue = "false") boolean allWorkspaces) {
        // 2026-05-22 audit follow-up: symmetric with /history.
        String orgFilter = usageOrgFilter(organizationId, allWorkspaces);
        return ResponseEntity.ok(inOrganizationScopeMap(organizationId,
                () -> creditService.getUsageAnalytics(userId, days, sourceType, provider, model, orgFilter)));
    }

    @GetMapping("/analytics/run/{runId}")
    public ResponseEntity<Map<String, Object>> getRunCost(
            @RequestHeader("X-User-ID") Long userId,
            @PathVariable String runId) {
        return ResponseEntity.ok(creditService.getRunCostSummary(userId, runId));
    }

    @GetMapping("/pricing")
    public ResponseEntity<List<ModelPricing>> getPricing() {
        return ResponseEntity.ok(pricingService.getAllActivePricing());
    }

    /**
     * Pre-flight existence check for a {@code (provider, model)} pricing row.
     *
     * <p>Tools that bill flat per-call (web search, image generation) call
     * this before invoking the upstream provider, so a missing migration or
     * catalog drift surfaces as a fail-fast {@code QUOTA_EXCEEDED} response
     * to the agent rather than a silent default-rate fallback or a post-flight
     * 402 after the user has already received the result.
     *
     * <p>Returns 200 with {@code {exists: true|false}} - never throws.
     */
    @GetMapping("/pricing/{provider}/{model}/exists")
    public ResponseEntity<Map<String, Object>> pricingExists(
            @PathVariable String provider,
            @PathVariable String model) {
        boolean exists = pricingService.hasPricing(provider, model);
        return ResponseEntity.ok(Map.of("exists", exists, "provider", provider, "model", model));
    }

    // ========== Platform Credential Markup ==========

    /**
     * Debit platform markup for one MCP tool call. Idempotent on {@code sourceId}
     * so the hot path can safely retry. On 402 returns the failing result so the
     * orchestrator can classify the run as budget-exhausted instead of errored.
     */
    @PostMapping("/markup/consume")
    public ResponseEntity<CreditService.CreditConsumeResult> consumeMarkup(
            @RequestHeader("X-User-ID") Long userId,
            @RequestBody MarkupConsumeRequest request) {
        CreditService.CreditConsumeResult result = creditService.consumePlatformMarkup(
                userId, request.sourceId(), request.apiToolName(), request.amount(), request.runId());
        if (!result.success()) {
            return ResponseEntity.status(402).body(result);
        }
        return ResponseEntity.ok(result);
    }

    // ========== V148+ Reservation Lifecycle (replaces consumeForImageGeneration*) ==========

    /**
     * Pre-flight reservation. Catalog calls this BEFORE the upstream HTTP call
     * to debit the user's balance and reserve the projected cost. Returns 402
     * with {@code delinquent=true} when the gate refuses (insufficient balance,
     * delinquent account, no subscription).
     *
     * <p>Idempotent on {@code sourceId} via the unique index - a duplicate
     * request returns success without re-reserving.
     */
    @PostMapping("/markup/scope-reserve")
    public ResponseEntity<CreditService.CreditConsumeResult> scopeReserve(
            @RequestHeader("X-User-ID") Long userId,
            @RequestBody ScopeReserveRequest request) {
        CreditService.CreditConsumeResult result = creditService.tryReserveMarkup(
                userId, request.sourceId(), request.provider(), request.model(),
                request.projected(), request.pinId(),
                request.ttlMinutes() != null ? request.ttlMinutes() : 15,
                request.scopeKind() != null ? request.scopeKind() : "LEGACY",
                request.scopeId(),
                Boolean.TRUE.equals(request.hasExistingPin()));
        if (!result.success()) {
            return ResponseEntity.status(402).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Post-flight commit. Catalog calls this after a successful upstream call
     * to flip the {@code _RESERVE} row to {@code PLATFORM_MARKUP} with the
     * actual amount (refunds delta if {@code actual &lt; reserved}).
     */
    @PostMapping("/markup/scope-commit")
    public ResponseEntity<Map<String, Object>> scopeCommit(
            @RequestBody ScopeCommitRequest request) {
        CreditService.CommitOutcome outcome = creditService.commitReservation(
                request.sourceId(), request.actualAmount(), request.provider(), request.model());
        return ResponseEntity.ok(Map.of("outcome", outcome.name()));
    }

    /**
     * Failure / partial-result release. Catalog calls this when the upstream
     * call fails or returns fewer items than reserved (n=5 reserved, 3 returned →
     * release 2 unused per-callIndex rows).
     */
    @PostMapping("/markup/scope-release")
    public ResponseEntity<Map<String, Object>> scopeRelease(
            @RequestBody ScopeReleaseRequest request) {
        CreditService.ReleaseOutcome outcome = creditService.releaseReservation(
                request.sourceId(), request.reason());
        return ResponseEntity.ok(Map.of("outcome", outcome.name()));
    }

    public record ScopeReserveRequest(
            String sourceId,
            String provider,
            String model,
            BigDecimal projected,
            Long pinId,
            Integer ttlMinutes,
            String scopeKind,
            String scopeId,
            Boolean hasExistingPin
    ) {
    }

    public record ScopeCommitRequest(
            String sourceId,
            BigDecimal actualAmount,
            String provider,
            String model
    ) {
    }

    public record ScopeReleaseRequest(
            String sourceId,
            String reason
    ) {
    }

    public record MarkupConsumeRequest(
            String sourceId,
            String apiToolName,
            BigDecimal amount,
            String runId
    ) {
    }

    public record ChatBudgetRequest(
            String provider,
            String model,
            Integer estimatedPromptTokens,
            Integer estimatedCompletionTokens
    ) {
    }

    public record CreditConsumeRequest(
            String sourceType,
            String sourceId,
            String provider,
            String model,
            Integer promptTokens,
            Integer completionTokens,
            Integer cost,
            Integer imageCount,
            Integer cacheCreationTokens,
            Integer cacheReadTokens,
            Integer cachedTokens,
            Integer reasoningTokens
    ) {
        /** Backward-compatible 6-arg constructor (cost + imageCount default to null). */
        public CreditConsumeRequest(String sourceType, String sourceId, String provider,
                                     String model, Integer promptTokens, Integer completionTokens) {
            this(sourceType, sourceId, provider, model, promptTokens, completionTokens, null, null);
        }
        /** Backward-compatible 7-arg constructor (imageCount defaults to null). */
        public CreditConsumeRequest(String sourceType, String sourceId, String provider,
                                     String model, Integer promptTokens, Integer completionTokens,
                                     Integer cost) {
            this(sourceType, sourceId, provider, model, promptTokens, completionTokens, cost, null);
        }
        /** Backward-compatible 8-arg constructor (cache/reasoning breakdown defaults to null). */
        public CreditConsumeRequest(String sourceType, String sourceId, String provider,
                                     String model, Integer promptTokens, Integer completionTokens,
                                     Integer cost, Integer imageCount) {
            this(sourceType, sourceId, provider, model, promptTokens, completionTokens, cost, imageCount,
                    null, null, null, null);
        }

        /** Null-safe view of the LLM token fields as a {@link LlmTokenBreakdown}. */
        public LlmTokenBreakdown toTokenBreakdown() {
            return new LlmTokenBreakdown(
                    promptTokens != null ? promptTokens : 0,
                    completionTokens != null ? completionTokens : 0,
                    cacheCreationTokens != null ? cacheCreationTokens : 0,
                    cacheReadTokens != null ? cacheReadTokens : 0,
                    cachedTokens != null ? cachedTokens : 0,
                    reasoningTokens != null ? reasoningTokens : 0);
        }
    }
}
