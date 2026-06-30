package com.apimarketplace.catalog.service.billing;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.credit.SourceIdBuilder;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.ResolvedScopeMarkupDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * V148+ single billing point for catalog tool calls.
 *
 * <p>Replaces the {@code CatalogBillingDispatcher} + per-tool
 * {@code CatalogBillingStrategy} fan-out. Catalog tool execution flow:
 *
 * <pre>
 *   ToolExecutionManager.execute() {
 *     billing.preflightReserve(context, params);   // BEFORE upstream HTTP - 402 if refused
 *     callUpstream();
 *     billing.commitOnSuccess(context, response);  // post-success debit
 *     // (or billing.releaseOnFailure(context) on partial / failure)
 *   }
 * </pre>
 *
 * <p><b>Decision tree</b> (in {@link #shouldBill}):
 * <ul>
 *   <li>{@code credentialSource = USER} (BYOK) → no-op, user paid upstream directly.</li>
 *   <li>{@code credential.providerKind = bridge} → no-op, CE bridge does its own internal accounting.</li>
 *   <li>{@code scopeKind == null || scopeId == null} → no-op + fail-closed metric
 *       {@code catalog_billing_skipped_total{reason="no_scope"}}. Never invent a scope.</li>
 *   <li>{@code credentialSource = PLATFORM} + scope present → reserve / commit / release.</li>
 * </ul>
 *
 * <p><b>Scope precedence</b> (RUN-priority): if both {@code runId} and
 * {@code streamId} are present (embedded agent inside workflow), use RUN.
 * STREAM is suppressed when {@code runId != null}. Workflow atomicity is
 * preferred over enforcing chat delinquent gate.
 *
 * <p><b>Slug → UUID resolution</b> happens locally via {@link ApiToolRepository}
 * (catalog owns the tool table). Auth-side endpoint takes the UUID; never
 * needs to know the catalog slug. Avoids a circular auth → catalog dependency.
 */
@Slf4j
@Service
public class CatalogToolBillingService {

    private final CreditConsumptionClient creditClient;
    private final CredentialClient credentialClient;
    private final ApiToolRepository apiToolRepository;
    private final ApiRepository apiRepository;
    private final boolean markupEnabled;

    public CatalogToolBillingService(CreditConsumptionClient creditClient,
                                      CredentialClient credentialClient,
                                      ApiToolRepository apiToolRepository,
                                      ApiRepository apiRepository,
                                      @Value("${credentials.platform.markup.enabled:true}") boolean markupEnabled) {
        this.creditClient = creditClient;
        this.credentialClient = credentialClient;
        this.apiToolRepository = apiToolRepository;
        this.apiRepository = apiRepository;
        this.markupEnabled = markupEnabled;
    }

    /**
     * Pre-flight reservation. Called BEFORE the upstream HTTP call. Returns
     * a {@link PreflightDecision} carrying:
     * <ul>
     *   <li>{@code allowed=true, sourceId, finalCharge=projected}: catalog
     *       proceeds to upstream call. Catalog MUST call {@link #commitOnSuccess}
     *       or {@link #releaseOnFailure} after the call.</li>
     *   <li>{@code allowed=true, sourceId=null}: no billing applies (BYOK,
     *       bridge, missing scope). Catalog proceeds without a tracked
     *       reservation.</li>
     *   <li>{@code allowed=false}: refuse the call. Catalog returns 402 to
     *       caller with the {@code error} message ("account delinquent - top up
     *       to resume" / "Insufficient credits") and {@code delinquent} flag
     *       so the chat UI can display the right banner.</li>
     * </ul>
     */
    public PreflightDecision preflightReserve(BillingScope scope) {
        if (!markupEnabled) {
            return PreflightDecision.allowedWithoutBilling();
        }
        if (!shouldBill(scope)) {
            return PreflightDecision.allowedWithoutBilling();
        }

        Optional<UUID> apiToolUuid = resolveApiToolUuid(scope.toolSlug());
        if (apiToolUuid.isEmpty()) {
            log.debug("No catalog UUID for slug={} - skipping billing", scope.toolSlug());
            return PreflightDecision.allowedWithoutBilling();
        }

        Optional<ResolvedScopeMarkupDto> resolved = credentialClient.resolveScopeMarkupRate(
                scope.scopeKind(), scope.scopeId(), scope.userId(),
                scope.platformCredentialId(), apiToolUuid.get());
        if (resolved.isEmpty() || resolved.get().getEffectiveMarkup() == null) {
            // No published pricing version OR zero rate - nothing to bill but
            // also nothing to fail-close on (admin chose to publish v1 with 0
            // markup). Proceed without reserving.
            log.debug("No effective markup for scope={}/{} cred={} tool={} - proceeding free",
                    scope.scopeKind(), scope.scopeId(), scope.platformCredentialId(), scope.toolSlug());
            return PreflightDecision.allowedWithoutBilling();
        }
        BigDecimal projected = resolved.get().getEffectiveMarkup();
        if (projected.signum() <= 0) {
            return PreflightDecision.allowedWithoutBilling();
        }

        Long pinId = resolved.get().getPinId();
        String sourceId = SourceIdBuilder.markupDebitChat(
                scope.scopeId(), scope.toolSlug(), scope.callIndex());
        // For RUN scope with stepId info, prefer the workflow per-call key so
        // analytics group correctly. Fall back to chat shape for STREAM.
        if ("RUN".equals(scope.scopeKind()) && scope.stepId() != null) {
            sourceId = SourceIdBuilder.markupDebitWithCall(
                    scope.scopeId(), scope.stepId(),
                    scope.epoch(), scope.spawn(), scope.iteration(), scope.itemIndex(),
                    scope.callIndex());
        }

        // hasExistingPin tells the auth-side delinquent gate "the user already
        // has a live pin for this scope+credential - this is an in-flight call,
        // not a fresh request". For RUN: workflow run-init creates the pin
        // eagerly, so a pin nearly always exists by the time per-step calls
        // arrive. For STREAM: chat call #1 creates the pin lazily, so call #1
        // is "fresh" (no pin yet) and would be refused while delinquent.
        // We do an honest existence check rather than hardcoding by scopeKind
        // so the auth-side gate gets accurate info - its policy then decides
        // whether to bypass (RUN+pin) or refuse (anything else when delinquent).
        boolean hasExistingPin;
        try {
            hasExistingPin = credentialClient.existsScopePin(
                    scope.scopeKind(), scope.scopeId(), scope.platformCredentialId());
        } catch (Exception e) {
            log.warn("existsScopePin failed for scope={}/{} cred={}: {} - fail-open false",
                    scope.scopeKind(), scope.scopeId(), scope.platformCredentialId(), e.getMessage());
            hasExistingPin = false;
        }

        CreditConsumptionClient.ScopeReserveResult result = creditClient.scopeReserve(
                scope.userId(), sourceId, scope.provider(), scope.model(),
                projected, pinId, scope.ttlMinutes(),
                scope.scopeKind(), scope.scopeId(), hasExistingPin);

        if (!result.success()) {
            return PreflightDecision.refused(result.error(), result.delinquent());
        }
        return PreflightDecision.allowedWithReservation(sourceId, projected);
    }

    /**
     * Post-flight commit. Catalog calls after a successful upstream response.
     * Idempotent - duplicate calls return {@code ALREADY_COMMITTED}.
     *
     * @param sourceId    the value from {@link PreflightDecision#sourceId()}
     * @param actualAmount actual credits consumed; pass the projected from
     *                     preflight when there's no per-call adjustment, or a
     *                     smaller amount when partial result reduces cost
     */
    public String commitOnSuccess(String sourceId, BigDecimal actualAmount, String provider, String model) {
        if (sourceId == null) return "COMMITTED";
        return creditClient.scopeCommit(sourceId, actualAmount, provider, model);
    }

    /**
     * Failure / partial-result release. Catalog calls when the upstream call
     * fails or yields fewer items than projected. Idempotent.
     */
    public String releaseOnFailure(String sourceId, String reason) {
        if (sourceId == null) return "RELEASED";
        return creditClient.scopeRelease(sourceId, reason);
    }

    /**
     * Post-flight one-shot billing. Reserve immediately followed by commit, in
     * the same call. This is the chat-agent / image-gen surface where there's
     * no partial-result lifecycle to track (success or fail, no half-result):
     * <ol>
     *   <li>Resolve scope-rate (lazy pin creation if needed)</li>
     *   <li>Reserve: writes {@code _RESERVE} ledger row, debits balance</li>
     *   <li>Immediately commit: flips row to {@code PLATFORM_MARKUP}</li>
     * </ol>
     *
     * <p>The reserve-then-commit dance keeps the same idempotency + delinquent
     * gate semantics as the multi-step lifecycle, but collapses cognitive load
     * for callers that don't need partial-release. Returns the {@link CommitOutcome}
     * string so the catalog can map to metrics + PagerDuty per the v9 spec.
     *
     * <p>Returns {@code null} when no billing applies (BYOK, bridge, missing
     * scope, no pricing). Returns {@code "REFUSED"} when the reservation gate
     * refused (delinquent / insufficient balance) - caller MUST surface this
     * back to the user as an error (the upstream call already happened, so
     * this is a post-fact revenue loss tracked via PagerDuty rate alerts).
     */
    public String billImmediate(BillingScope scope) {
        PreflightDecision decision = preflightReserve(scope);
        if (!decision.allowed()) {
            log.warn("Billing refused for scope={}/{} cred={} tool={} - error={} delinquent={}",
                    scope.scopeKind(), scope.scopeId(), scope.platformCredentialId(), scope.toolSlug(),
                    decision.error(), decision.delinquent());
            return "REFUSED";
        }
        if (decision.sourceId() == null) {
            // No billing applies (BYOK / bridge / no scope / zero rate). Nothing to commit.
            return null;
        }
        return commitOnSuccess(decision.sourceId(), decision.reservedAmount(),
                scope.provider(), scope.model());
    }

    private boolean shouldBill(BillingScope scope) {
        if (scope == null) return false;
        if ("USER".equalsIgnoreCase(scope.credentialSource())) return false;
        if ("bridge".equalsIgnoreCase(scope.providerKind())) return false;
        if (scope.scopeKind() == null || scope.scopeId() == null) {
            log.warn("Catalog billing skipped: missing scope (toolSlug={}, userId={}) - fail-closed",
                    scope.toolSlug(), scope.userId());
            return false;
        }
        if (scope.platformCredentialId() == null || scope.userId() == null) {
            return false;
        }
        return true;
    }

    /**
     * Resolve a composite slug like {@code "openai/openai-create-image"} to the
     * tool UUID. Splits on the first slash; left part is the API slug, right
     * part is the tool slug. Lookups are local to catalog-service so this
     * stays a fast O(2) DB hit (both indexed by slug).
     *
     * <p>Returns empty for slugs that don't fit the pattern (already-UUID,
     * non-API tools like {@code crud/*}, etc.) - caller treats as
     * "not billable" and skips.
     */
    private Optional<UUID> resolveApiToolUuid(String toolSlug) {
        if (toolSlug == null || !toolSlug.contains("/")) return Optional.empty();
        try {
            int slash = toolSlug.indexOf('/');
            String apiSlug = toolSlug.substring(0, slash);
            String toolSlugTail = toolSlug.substring(slash + 1);
            Optional<ApiEntity> api = apiRepository.findByApiSlug(apiSlug);
            if (api.isEmpty()) return Optional.empty();
            return apiToolRepository.findByApiIdAndToolSlug(api.get().getId(), toolSlugTail)
                    .map(ApiToolEntity::getId);
        } catch (Exception e) {
            log.warn("Slug → UUID resolution failed for slug={}: {}", toolSlug, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Catalog billing context - built by the caller from the catalog request +
     * resolved credential metadata. Single record passed end-to-end so the
     * billing service has everything it needs without reaching into Spring
     * security context or thread-locals.
     *
     * <p>{@code ttlMinutes} convention:
     * <ul>
     *   <li>10 - catalog post-flight reserve (catalog timeout 5s + safety)</li>
     *   <li>15 - per-step short LLM call (configurable via
     *       {@code markup.reserve.default-ttl-minutes})</li>
     *   <li>60 - long-running step (browser-agent, classify)</li>
     *   <li>1440 - workflow run-init reserve (24h)</li>
     * </ul>
     * TTL must be ≥ upstream hard timeout + safety. Validation enforced in
     * auth-side {@code tryReserveMarkup}.
     */
    public record BillingScope(
            Long userId,
            String credentialSource,    // "USER" | "PLATFORM" | null
            Long platformCredentialId,
            String providerKind,        // "cloud" | "bridge"
            String provider,
            String model,
            String toolSlug,            // catalog kebab slug, e.g. "openai/openai-create-image"
            String scopeKind,           // "RUN" | "STREAM" | null
            String scopeId,             // runId or streamId
            String stepId,              // workflow step id (RUN only)
            int epoch, int spawn, int iteration, int itemIndex, int callIndex,
            int ttlMinutes
    ) {
        /** Apply RUN-priority: if runId is present, force scopeKind=RUN and clear streamId. */
        public static BillingScope of(Long userId, String credentialSource, Long platformCredentialId,
                                       String providerKind, String provider, String model, String toolSlug,
                                       String runId, String streamId, String stepId,
                                       int epoch, int spawn, int iteration, int itemIndex, int callIndex,
                                       int ttlMinutes) {
            String scopeKind;
            String scopeId;
            if (runId != null && !runId.isBlank()) {
                scopeKind = "RUN";
                scopeId = runId;
            } else if (streamId != null && !streamId.isBlank()) {
                scopeKind = "STREAM";
                scopeId = streamId;
            } else {
                scopeKind = null;
                scopeId = null;
            }
            return new BillingScope(userId, credentialSource, platformCredentialId,
                    providerKind, provider, model, toolSlug, scopeKind, scopeId, stepId,
                    epoch, spawn, iteration, itemIndex, callIndex, ttlMinutes);
        }
    }

    /**
     * Outcome of {@link #preflightReserve}. Catalog reads {@link #allowed} to
     * decide whether to proceed; on refusal returns 402 to the caller with the
     * provided {@code error} and {@code delinquent} flag.
     */
    public record PreflightDecision(
            boolean allowed,
            String sourceId,           // non-null only when a reservation row was written
            BigDecimal reservedAmount, // for downstream commit/release
            String error,              // populated on refusal
            boolean delinquent         // populated on refusal - UI shows distinct banner
    ) {
        public static PreflightDecision allowedWithoutBilling() {
            return new PreflightDecision(true, null, null, null, false);
        }
        public static PreflightDecision allowedWithReservation(String sourceId, BigDecimal projected) {
            return new PreflightDecision(true, sourceId, projected, null, false);
        }
        public static PreflightDecision refused(String error, boolean delinquent) {
            return new PreflightDecision(false, null, null, error, delinquent);
        }
    }

    /** Map a non-Map metadata cell back to {@code Map} or null. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }
}
