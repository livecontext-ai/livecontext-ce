package com.apimarketplace.orchestrator.services.markup;

import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.PricingVersionDto;
import com.apimarketplace.credential.client.dto.RunPricingPinDto;
import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Owns the run-scoped lifecycle of platform-credential pricing pins.
 * <p>
 * A <em>pin</em> freezes the pricing version that applies to every MCP tool
 * call made through a platform-owned credential during a workflow run, so mid-run
 * rate changes don't leak into billing. Pins are created at run-init (before any
 * step fires) and cancelled at the run terminal chokepoint so stragglers can't
 * keep charging markup.
 * <p>
 * This service is deliberately fail-open on pin creation - if pricing resolution
 * or the save RPC falls over, the run still starts, markup simply won't be
 * charged for the affected credential. Preferring "run executes, revenue lost"
 * over "run blocked, user confused" because: (1) the user has enough credits to
 * cover base node cost and (2) markup is a revenue-side concern the platform
 * can reconcile later.
 */
@Service
public class PlatformMarkupPinService {

    private static final Logger logger = LoggerFactory.getLogger(PlatformMarkupPinService.class);

    private final CredentialClient credentialClient;
    private final boolean markupEnabled;

    public PlatformMarkupPinService(CredentialClient credentialClient,
                                    @Value("${credentials.platform.markup.enabled:true}") boolean markupEnabled) {
        this.credentialClient = credentialClient;
        this.markupEnabled = markupEnabled;
        if (!markupEnabled) {
            logger.info("PlatformMarkupPinService: markup billing DISABLED - no pricing pins will be created, debits will also be skipped at auth-side");
        }
    }

    /**
     * Create pins for every distinct platform-owned credential referenced by MCP
     * steps in the plan. Tables are CRUD on user-owned datasources and never
     * carry markup, so they are not scanned.
     *
     * <p>When the {@code credentials.platform.markup.enabled} flag is false we
     * skip pinning entirely. Pins are free but pointless when the debit path is
     * also gated off - and skipping here means a disabled deploy has zero new
     * rows in {@code workflow_run_pricing_pin} so ops can audit rollout state
     * by inspecting table volume.
     *
     * @return count of pins created (0 when no platform-sourced MCP steps exist
     *         OR markup is disabled by feature flag)
     */
    public int createPinsForRun(String runId, Long userId, WorkflowPlan plan) {
        if (!markupEnabled) {
            return 0;
        }
        if (runId == null || runId.isBlank() || userId == null || plan == null) {
            return 0;
        }

        Set<Long> platformCredentialIds = collectPlatformCredentialIds(plan.getMcps());
        if (platformCredentialIds.isEmpty()) {
            return 0;
        }

        int created = 0;
        for (Long credentialId : platformCredentialIds) {
            if (createPinForCredential(runId, userId, credentialId)) {
                created++;
            }
        }
        logger.info("Created {} platform-markup pricing pin(s) for run {}", created, runId);
        return created;
    }

    /**
     * Terminal chokepoint - cancel every pin the run still holds. Safe to call
     * more than once (downstream DELETE is idempotent). Never throws.
     *
     * <p>V148+ uses the unified scope-aware endpoint
     * ({@code DELETE /markup/scope-pin?scopeKind=RUN&scopeId=runId}) which
     * handles both legacy {@code workflow_run_pricing_pin} rows AND new
     * scope_kind='RUN' rows in one call.
     */
    public int cancelPinsForRun(String runId) {
        if (runId == null || runId.isBlank()) return 0;
        int cancelled = credentialClient.cancelScopePin("RUN", runId);
        if (cancelled < 0) {
            logger.warn("Transport failure cancelling pins for run {}", runId);
            return 0;
        }
        if (cancelled > 0) {
            logger.info("Cancelled {} platform-markup pricing pin(s) for run {} (scope-aware)", cancelled, runId);
        }
        return cancelled;
    }

    private Set<Long> collectPlatformCredentialIds(List<Step> steps) {
        Set<Long> ids = new HashSet<>();
        if (steps == null) return ids;
        for (Step step : steps) {
            if (step.usesPlatformCredential() && step.platformCredentialId() != null) {
                ids.add(step.platformCredentialId());
            }
        }
        return ids;
    }

    private boolean createPinForCredential(String runId, Long userId, Long credentialId) {
        Optional<PricingVersionDto> latest = credentialClient.getLatestPricingVersion(credentialId);
        if (latest.isEmpty()) {
            logger.debug("No pricing version published for credential {} - skipping pin for run {}",
                    credentialId, runId);
            return false;
        }
        Optional<RunPricingPinDto> pin = credentialClient.saveRunPricingPin(
                runId, userId, credentialId, latest.get().getPricingVersionId());
        if (pin.isEmpty()) {
            logger.warn("Pin RPC failed for run={}, credential={}, version={} - markup will be skipped",
                    runId, credentialId, latest.get().getPricingVersionId());
            return false;
        }
        return true;
    }
}
