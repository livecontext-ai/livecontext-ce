package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.CredentialModels.Credential;
import com.apimarketplace.auth.credential.repository.CredentialRepository;
import com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTerminalException;
import com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTransientException;
import com.apimarketplace.notification.client.NotificationClient;
import com.apimarketplace.notification.client.dto.NotificationEmitRequest;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Proactively refreshes OAuth2 access tokens whose {@code expires_at} falls inside a
 * configurable look-ahead window. Runs on a ShedLock-guarded schedule so exactly one
 * auth-service pod owns any given sweep, and delegates per-credential refresh to
 * {@link OAuth2Service#refreshToken}, which itself holds a per-credential Redis lock
 * to prevent collisions with user-initiated force-refreshes.
 *
 * <p><strong>What this is NOT:</strong> a fallback for providers that don't return
 * {@code expires_at}. Those tokens stay in place until a caller hits a 401, at which
 * point {@link InternalCredentialService#forceRefreshAndGetToken} kicks in on the
 * lazy path. The scheduler only handles the proactive window - tokens that we know
 * are about to expire get rotated before the next API call fails.
 *
 * <p><strong>Rotating refresh tokens (Xero/HubSpot/Zoom):</strong> handled implicitly.
 * Each proactive access-token refresh also rotates the refresh_token (when the provider
 * opts in via {@code rotatesRefreshToken=true}), resetting its TTL clock. As long as
 * the scheduler keeps the access token warm, the refresh token stays warm too.
 */
@Component
public class OAuth2RefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2RefreshScheduler.class);

    private final CredentialRepository credentialRepository;
    private final OAuth2Service oAuth2Service;
    private final Duration lookAhead;

    /**
     * P5: cross-service notification client. Optional ({@code required=false})
     * so unit tests with mock repositories don't need to wire it. When
     * unwired, {@link #emitCredExpired} is a no-op.
     */
    @Autowired(required = false)
    private NotificationClient notificationClient;

    public OAuth2RefreshScheduler(
            CredentialRepository credentialRepository,
            OAuth2Service oAuth2Service,
            @Value("${oauth2.refresh.scheduler.look-ahead-minutes:10}") long lookAheadMinutes
    ) {
        this.credentialRepository = credentialRepository;
        this.oAuth2Service = oAuth2Service;
        this.lookAhead = Duration.ofMinutes(lookAheadMinutes);
    }

    /**
     * Sweep every 5 minutes by default. The look-ahead is 10 minutes, so even under
     * worst-case clock skew between the scheduler tick and the provider's expiry
     * calculation, tokens have at least ~5 minutes of headroom after refresh.
     *
     * <p>The ShedLock {@code lockAtMostFor} is 4 minutes - less than the 5-minute
     * tick, so a stuck sweep cannot hold the lock across two ticks.
     */
    @Scheduled(fixedRateString = "${oauth2.refresh.scheduler.interval-ms:300000}")
    @SchedulerLock(
            name = "oauth2_refresh_scheduler",
            lockAtMostFor = "PT4M",
            lockAtLeastFor = "PT30S"
    )
    public void refreshExpiringTokens() {
        Instant threshold = Instant.now().plus(lookAhead);
        List<Credential> expiring;
        try {
            expiring = credentialRepository.findOAuth2CredentialsExpiringBefore(threshold);
        } catch (Exception e) {
            log.error("OAuth2 refresh sweep failed during candidate lookup: {}", e.getMessage(), e);
            return;
        }

        if (expiring.isEmpty()) {
            return;
        }

        int refreshed = 0;
        int unsupported = 0;
        int inProgress = 0;
        int terminal = 0;
        int transientRetry = 0;
        int failed = 0;

        for (Credential cred : expiring) {
            try {
                oAuth2Service.refreshToken(cred.id(), cred.tenantId());
                refreshed++;
            } catch (RefreshTerminalException e) {
                // Credential has been flipped to needs_reauth or error; subsequent sweep ticks
                // will skip it via the status-NOT-IN predicate. Emit a single structured log
                // line - metrics take over from here (oauth2_refresh_terminal_total{reason}).
                terminal++;
                log.warn("Credential {} (tenant {}) promoted to terminal: bucket={} provider_code={}",
                        cred.id(), cred.tenantId(), e.bucket(), e.providerCode());
                // P5: surface CRED_EXPIRED in the user's bell so they can re-auth proactively.
                emitCredExpired(cred, e.bucket() != null ? e.bucket().name() : null, e.providerCode());
            } catch (RefreshTransientException e) {
                // Provider blip (5xx, 429, socket timeout, Retry-After). OAuth2Service has
                // already written a refresh_cooldown_until into the credential JSONB + Redis
                // cooldown sentinel; the next sweep tick will skip this row until the cooldown
                // elapses. No action needed from the scheduler.
                transientRetry++;
                log.debug("Transient refresh failure for cred {} (tenant {}): bucket={} attempt={}",
                        cred.id(), cred.tenantId(), e.bucket(), e.attempt());
            } catch (IllegalStateException e) {
                // Legacy non-typed states surfaced by OAuth2Service:
                //   - "refresh_not_supported: <reason>"  → provider doesn't issue refresh tokens
                //   - "refresh_in_progress"              → another refresh path holds the lock
                //   - "No refresh token available"       → row missed the repo predicate (race)
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.startsWith("refresh_not_supported")) {
                    unsupported++;
                    log.debug("Skipping cred {} (tenant {}) - provider does not support refresh: {}",
                            cred.id(), cred.tenantId(), msg);
                } else if ("refresh_in_progress".equals(msg)) {
                    inProgress++;
                    log.debug("Skipping cred {} (tenant {}) - refresh already in progress",
                            cred.id(), cred.tenantId());
                } else {
                    failed++;
                    log.warn("Failed to refresh cred {} (tenant {}): {}",
                            cred.id(), cred.tenantId(), msg);
                }
            } catch (Exception e) {
                failed++;
                log.warn("Failed to refresh cred {} (tenant {}): {}",
                        cred.id(), cred.tenantId(), e.getMessage());
            }
        }

        log.info(
                "OAuth2 refresh sweep complete: candidates={} refreshed={} unsupported={} in_progress={} terminal={} transient_retry={} failed={}",
                expiring.size(), refreshed, unsupported, inProgress, terminal, transientRetry, failed);
    }

    // ========================================================================
    // P5 - CRED_EXPIRED bell emission
    // ========================================================================

    /**
     * Subject_id contract: V172 schema requires {@code subject_id UUID NOT NULL}, but
     * {@code Credential.id} is {@code Long} (BIGSERIAL). We project the Long into a
     * deterministic UUID via {@link UUID#nameUUIDFromBytes(byte[])}, scoped to the
     * {@code "cred-"} namespace so other categories that use Long-based subject IDs
     * (none today) cannot collide. The frontend reads {@code payload.credentialId}
     * for click-through routing, not the synthetic UUID.
     */
    private static UUID credentialUuid(Long credId) {
        return UUID.nameUUIDFromBytes(("cred-" + credId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Best-effort. Failures swallowed by {@link NotificationClient#emit} - the
     * scheduler's primary work (mark cred terminal) MUST NOT roll back because
     * the bell write blipped.
     */
    private void emitCredExpired(Credential cred, String bucket, String providerCode) {
        if (notificationClient == null) return;
        if (cred == null || cred.tenantId() == null || cred.id() == null) return;

        Instant now = Instant.now();
        long epochDay = now.truncatedTo(ChronoUnit.DAYS).getEpochSecond() / 86_400L;

        NotificationEmitRequest req = new NotificationEmitRequest();
        req.setTenantId(cred.tenantId());
        req.setCategory("CRED_EXPIRED");
        req.setSeverity("warning");
        req.setSubjectType("CREDENTIAL");
        req.setSubjectId(credentialUuid(cred.id()));
        // Per master brief: source_id = credentialId + ':' + expiry_epoch_day.
        // Using current-day for terminal failures: re-failures within the same day
        // collapse to one bell row; tomorrow's re-fail is a new event.
        req.setSourceId(cred.id() + ":" + epochDay);

        Map<String, Object> payload = new HashMap<>();
        payload.put("status", "expired");
        if (bucket != null) payload.put("reason", bucket);
        if (providerCode != null) payload.put("provider", providerCode);
        if (cred.integration() != null) payload.put("integration", cred.integration());
        if (cred.name() != null) payload.put("subjectName", cred.name());
        // credentialId for click-through: subject_id is a synthetic UUID, the
        // frontend needs the real BIGINT to navigate to /app/credentials/<id>.
        payload.put("credentialId", cred.id());
        req.setPayload(payload);
        req.setOccurredAt(now);

        try {
            notificationClient.emit(req);
        } catch (Exception ex) {
            // emit() already swallows internally; this is a defense-in-depth guard
            // in case a future emit impl re-throws.
            log.warn("CRED_EXPIRED emit threw for cred {} (tenant {}): {}",
                    cred.id(), cred.tenantId(), ex.getMessage());
        }
    }
}
