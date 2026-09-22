package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.domain.ModelPricing;
import com.apimarketplace.auth.service.CreditService;
import com.apimarketplace.auth.service.LlmCostEstimateService;
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
    private final LlmCostEstimateService estimateService;

    public CreditController(CreditService creditService, ModelPricingService pricingService,
                            LlmCostEstimateService estimateService) {
        this.creditService = creditService;
        this.pricingService = pricingService;
        this.estimateService = estimateService;
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

        // A turn that ran on the tenant's OWN provider key is billed a flat fee per turn
        // (the provider bills them the tokens), whatever kind of turn it was. The route was
        // decided once per execution by agent-service and rides on the request.
        boolean ownKey = CreditService.KEY_ROUTE_OWN_KEY.equals(request.keyRoute());
        CreditService.CreditConsumeResult result = switch (request.sourceType()) {
            case "AGENT_EXECUTION", "CLASSIFY_EXECUTION", "GUARDRAIL_EXECUTION",
                 "COMPACTION_SUMMARY", "BROWSER_AGENT_EXECUTION",
                 // CLI/bridge sessions (claude-code/codex/gemini): zero billed tokens (the
                 // external CLI pays its own provider), so consumeForAgent computes ~0 credits
                 // and writes a clean CLI_SESSION ledger row. Must be allow-listed here, else
                 // every CLI/bridge observability write would 500 → rejection + dead-letter spam.
                 "CLI_SESSION" -> ownKey
                    ? creditService.consumeForOwnKeyTurn(
                            userId, request.sourceId(), request.provider(), request.model(),
                            request.toTokenBreakdown(), request.sourceType())
                    : creditService.consumeForAgent(
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
            case "CHAT_CONVERSATION" -> ownKey
                    ? creditService.consumeForOwnKeyTurn(
                            userId, request.sourceId(), request.provider(), request.model(),
                            request.toTokenBreakdown(), request.sourceType())
                    : creditService.consumeForChat(
                            userId, request.sourceId(), request.provider(), request.model(),
                            request.toTokenBreakdown());
            case "MARKETPLACE_PURCHASE" -> creditService.consumeForMarketplacePurchase(
                    userId, request.sourceId(), request.cost() != null ? request.cost() : 0);
            case "WEB_SEARCH" -> creditService.consumeForWebSearch(userId, request.sourceId());
            case "WEB_FETCH" -> creditService.consumeForWebFetch(userId, request.sourceId());
            // Still live: publication screening bills its replacement image here.
            // IMAGE_GENERATION_BYOK is gone with the legacy tool's billing strategy - it
            // stays readable on historical ledger rows, but nothing writes it any more.
            case "IMAGE_GENERATION" -> creditService.consumeForImageGeneration(
                    userId, request.sourceId(), request.provider(), request.model(),
                    // imageCount = actualImageCount returned by the provider; defaults
                    // to 1 for backward-compat with old client builds that don't set it.
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
            @RequestHeader("X-User-ID") Long userId,
            // V494: optional, and only read by the LLM budget guards. With them the
            // response states whether the AI allowance is spendable ON THIS MODEL, which
            // the caller cannot work out for itself - the free-tier flag lives in the
            // billing mirror. Without them the field is simply absent and the caller
            // falls back to the wallet, which is the pre-V494 answer.
            @RequestParam(value = "provider", required = false) String provider,
            @RequestParam(value = "model", required = false) String model) {
        CreditService.BalanceBreakdown breakdown = creditService.getBalanceBreakdown(userId);
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "balance", breakdown.balance(),
                "subBalance", breakdown.subBalance(),
                "paygBalance", breakdown.paygBalance(),
                // V494: the monthly AI allowance, kept OUT of `balance` because it
                // only funds agent/chat turns on free-tier models. A wallet surface
                // shows it as its own line, never added to the headline figure.
                "aiBalance", breakdown.aiBalance(),
                "delinquent", breakdown.delinquent(),
                // Whether the monthly bucket is workflow-scoped on this plan, so
                // a surface can warn BEFORE a platform-key purchase instead of
                // deriving the rule from the two balances and getting it wrong
                // for the ordinary paid account.
                "monthlyCreditsAreWorkflowOnly", breakdown.monthlyCreditsAreWorkflowOnly()));
        if (provider != null && !provider.isBlank() && model != null && !model.isBlank()) {
            // The allowance counts toward an LLM budget only on a model an admin opened;
            // on any other model it is money no debit for this turn can draw, and a guard
            // that budgeted against it would let a loop run on credits it cannot spend.
            boolean allowanceApplies = creditService.isAiAllowanceSpendableOn(provider, model);
            body.put("llmSpendableBalance", allowanceApplies
                    ? breakdown.balance().add(breakdown.aiBalance())
                    : breakdown.balance());
        }
        return ResponseEntity.ok(body);
    }

    /**
     * Generic "has at least 1 credit?" gate. {@code sourceType} is optional:
     * when present (e.g. {@code CHAT_CONVERSATION} from the internal/scheduled
     * chat gate) the check applies the FREE-plan bucket scoping - a Free user
     * holding monthly workflow-only credits but no PAYG top-up is refused a
     * chat/agent spend up-front instead of overshooting the PAYG bucket
     * negative post-flight. Absent (workflow launch gates), the legacy
     * total-balance semantics are preserved: the Free monthly bucket IS
     * eligible to fund workflow runs.
     */
    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> checkCredits(
            @RequestHeader("X-User-ID") Long userId,
            @RequestParam(value = "sourceType", required = false) String sourceType,
            // V494: optional, and only meaningful together with an LLM sourceType.
            // With them, a Free account's AI allowance counts toward this gate exactly
            // as the debit will draw it; without them the gate keeps its pre-V494
            // answer, which refuses a turn the pot would have paid for. Callers that
            // know the model MUST send it.
            //
            // These are CALLER-SUPPLIED and not verified against what is actually run:
            // this endpoint is reachable through the gateway, so a caller can ask about
            // a model it will not use. That is deliberate and bounded - the answer is
            // advisory, and the DEBIT re-resolves the real (provider, model) and its
            // free-tier flag server-side, so a lie here buys nothing but a wrong
            // prediction of one's own balance.
            @RequestParam(value = "provider", required = false) String provider,
            @RequestParam(value = "model", required = false) String model) {
        // Blank == absent: `?sourceType=` must not silently apply the FREE
        // PAYG scoping (a blank string is not in the workflow allow-list, so
        // it would scope) - keep it symmetric with the client, which sends no
        // param at all for null/blank.
        if (sourceType != null && sourceType.isBlank()) {
            sourceType = null;
        }
        boolean sufficient = creditService.hasSufficientCredits(userId, sourceType, provider, model);
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

        // Unlimited mode (CE): every billing gate below exists to protect a ledger
        // this deployment does not enforce. In particular the unknown-model
        // fail-closed must NOT fire: a CE whose model catalog is behind the cloud
        // (bundle not yet synced) would otherwise report "insufficient credits"
        // for any newer model - a billing error for what is a catalog-freshness
        // situation, on an install where credits can never be insufficient.
        if (creditService.isUnlimited()) {
            return ResponseEntity.ok(Map.of(
                    "allowed", true,
                    "balance", balance,
                    "estimatedCost", BigDecimal.ZERO));
        }

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
        // V494: passing the model lets the AI allowance count toward this gate when
        // the turn runs on a free-tier model, exactly as the post-flight debit will
        // draw it. Without it a Free account with a full AI pot but an empty PAYG
        // bucket would be refused a turn its own allowance was created to pay for.
        // The source type the CALLER will debit as, not an assumption. Absent reads as
        // CHAT_CONVERSATION, which is what every pre-V494 caller meant.
        String gateSourceType = request.sourceType() == null || request.sourceType().isBlank()
                ? "CHAT_CONVERSATION"
                : request.sourceType();
        boolean allowed = creditService.canAfford(userId, estimatedCost, gateSourceType,
                request.provider(), request.model());
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

    /**
     * Everything a client needs to show a pre-flight cost estimate next to a model,
     * and nothing more: one coefficient per rate, per shape of work.
     *
     * <p>Deliberately NOT a per-model credit table. The client already holds every
     * model's list rates (they come with the model catalogue), so publishing two
     * coefficients instead of 779 pre-computed rows keeps the payload at a few
     * hundred bytes and keeps the MARGIN server-side: a client that restated the
     * multiplier would quote a price the ledger does not charge the day the lever
     * moves. See {@link com.apimarketplace.auth.service.LlmCostEstimateService} for
     * why the multiplier is folded into the coefficients rather than named.
     */
    @GetMapping("/estimate-basis")
    public ResponseEntity<Map<String, Object>> getEstimateBasis(
            @RequestHeader(value = "X-User-ID", required = false) String tenantId) {
        return ResponseEntity.ok(estimateService.buildBasis(tenantId));
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

    /**
     * @param sourceType what the caller will DEBIT this turn as, so the gate scopes the
     *                   balance the same way the debit will. Optional for back-compat;
     *                   absent means {@code CHAT_CONVERSATION}. It matters because the
     *                   AI allowance funds some LLM source types and not others: a CE
     *                   relay turn debits {@code CE_LLM_RELAY}, which the pot may not
     *                   pay for, so gating it as a chat turn would count money the
     *                   debit cannot reach and let the tokens run against nothing.
     */
    public record ChatBudgetRequest(
            String provider,
            String model,
            Integer estimatedPromptTokens,
            Integer estimatedCompletionTokens,
            String sourceType
    ) {
        /** Pre-V494 shape: no source type, which reads as CHAT_CONVERSATION. */
        public ChatBudgetRequest(String provider, String model,
                                 Integer estimatedPromptTokens, Integer estimatedCompletionTokens) {
            this(provider, model, estimatedPromptTokens, estimatedCompletionTokens, null);
        }
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
            Integer reasoningTokens,
            /** V506: {@code OWN_KEY} when the turn ran on the tenant's own provider key; null/PLATFORM otherwise. */
            String keyRoute
    ) {
        /** Pre-V506 shape (no key route = platform route). */
        public CreditConsumeRequest(String sourceType, String sourceId, String provider,
                                     String model, Integer promptTokens, Integer completionTokens,
                                     Integer cost, Integer imageCount,
                                     Integer cacheCreationTokens, Integer cacheReadTokens,
                                     Integer cachedTokens, Integer reasoningTokens) {
            this(sourceType, sourceId, provider, model, promptTokens, completionTokens, cost, imageCount,
                    cacheCreationTokens, cacheReadTokens, cachedTokens, reasoningTokens, null);
        }
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
