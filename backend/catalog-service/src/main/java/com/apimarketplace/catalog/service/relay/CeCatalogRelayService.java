package com.apimarketplace.catalog.service.relay;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.dto.CeCatalogRelayRequest;
import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.service.CatalogV1Service;
import com.apimarketplace.catalog.service.http.CredentialModeContext;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.credit.SourceIdBuilder;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.FrozenMarkupDto;
import com.apimarketplace.credential.client.dto.PlatformCredentialLookupDto;
import com.apimarketplace.credential.client.dto.PricingVersionDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cloud-side execution of catalog tools relayed from a linked CE install.
 *
 * <p>The CE never supplies credential or billing input: this service resolves
 * the API's platform credential and its published markup pricing server-side,
 * reserves the markup on the linked cloud account BEFORE the upstream call,
 * executes locally through {@link CatalogV1Service} with a forced
 * {@code credentialSource="platform"}, then commits the reservation on upstream
 * success or releases it on failure (the CE user is never billed for a failed
 * upstream call).
 *
 * <p><b>Anti-free-ride posture:</b> unlike local cloud execution (which
 * proceeds free when a platform credential has no published pricing, see
 * {@code CatalogToolBillingService}), the relay REFUSES to execute without a
 * strictly positive markup ({@link RelayResult.Status#PLATFORM_NOT_AVAILABLE}).
 * A linked install must never obtain platform-funded API access the cloud
 * cannot bill.
 *
 * <p><b>Billing sourceId is SERVER-generated</b> (random UUID per call, never
 * derived from CE input): the ledger dedups on a globally-unique source_id, so
 * a client-controlled key would let an install replay one key for unlimited
 * calls billed once. Same posture as the CE web-search and LLM relays.
 *
 * <p><b>Rate limiting</b> is an in-memory per-install fixed-window counter
 * (Caffeine-backed). Per-pod semantics: each catalog replica enforces the
 * window independently, so the effective cluster-wide ceiling is
 * {@code limit * replicas}. Acceptable for an abuse brake; it is not an exact
 * quota.
 */
@Slf4j
@Service
public class CeCatalogRelayService {

    /** scopeKind presented to the auth-side markup reserve gate. Unknown to the
     * pin subsystem on purpose: auth-side {@code tryReserveMarkup} refuses
     * unknown scopeKinds while the account is delinquent, which is exactly the
     * fail-safe posture this relay wants. */
    static final String CE_RELAY_SCOPE_KIND = "CE_RELAY";

    /** Serialized-parameters cap for a relayed call (512 KB). */
    static final int MAX_PARAMETERS_BYTES = 512 * 1024;

    private final ApiRepository apiRepository;
    private final ApiToolRepository apiToolRepository;
    private final CredentialClient credentialClient;
    private final CreditConsumptionClient creditClient;
    private final CatalogV1Service catalogV1Service;
    private final ObjectMapper objectMapper;
    private final int reserveTtlMinutes;
    private final int rateLimitPerMinute;

    /** Fixed-window counters keyed by {@code installId:epochMinute}; entries
     * outlive their window slightly and expire on their own. */
    private final Cache<String, AtomicInteger> rateWindows = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(2))
            .maximumSize(10_000)
            .build();

    public CeCatalogRelayService(ApiRepository apiRepository,
                                 ApiToolRepository apiToolRepository,
                                 CredentialClient credentialClient,
                                 CreditConsumptionClient creditClient,
                                 CatalogV1Service catalogV1Service,
                                 ObjectMapper objectMapper,
                                 @Value("${ce-catalog-relay.reserve-ttl-minutes:10}") int reserveTtlMinutes,
                                 @Value("${ce-catalog-relay.rate-limit-per-minute:120}") int rateLimitPerMinute) {
        this.apiRepository = apiRepository;
        this.apiToolRepository = apiToolRepository;
        this.credentialClient = credentialClient;
        this.creditClient = creditClient;
        this.catalogV1Service = catalogV1Service;
        this.objectMapper = objectMapper;
        this.reserveTtlMinutes = reserveTtlMinutes;
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    /**
     * Fixed-window rate check for one relayed call. {@code true} = proceed.
     * A limit of 0 (or negative) disables rate limiting.
     */
    public boolean tryAcquire(String installId) {
        if (rateLimitPerMinute <= 0) {
            return true;
        }
        long windowMinute = System.currentTimeMillis() / 60_000L;
        String key = installId + ":" + windowMinute;
        AtomicInteger counter = rateWindows.get(key, k -> new AtomicInteger());
        return counter.incrementAndGet() <= rateLimitPerMinute;
    }

    /**
     * True when the serialized parameters exceed {@link #MAX_PARAMETERS_BYTES}.
     * Unserializable parameters count as too large (fail-closed to a 400).
     */
    public boolean parametersTooLarge(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return false;
        }
        try {
            return objectMapper.writeValueAsBytes(parameters).length > MAX_PARAMETERS_BYTES;
        } catch (Exception e) {
            log.warn("CE catalog relay: failed to size-check parameters: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Executes one relayed catalog tool call with the reserve → execute →
     * commit/release lifecycle described on the class. Caller (controller) has
     * already enforced authentication, link ownership, subscription, rate limit,
     * and request validity.
     */
    public RelayResult execute(long cloudUserId,
                               String installId,
                               String apiSlug,
                               String toolSlug,
                               CeCatalogRelayRequest request) {
        Optional<ApiEntity> apiOpt = apiRepository.findByApiSlug(apiSlug)
                .filter(api -> Boolean.TRUE.equals(api.getIsActive()));
        if (apiOpt.isEmpty()) {
            return RelayResult.of(RelayResult.Status.TOOL_NOT_FOUND);
        }
        ApiEntity api = apiOpt.get();
        Optional<ApiToolEntity> toolOpt = apiToolRepository.findByApiIdAndToolSlug(api.getId(), toolSlug)
                .filter(tool -> Boolean.TRUE.equals(tool.getIsActive()));
        if (toolOpt.isEmpty()) {
            return RelayResult.of(RelayResult.Status.TOOL_NOT_FOUND);
        }
        ApiToolEntity tool = toolOpt.get();

        // Phase 1 restriction: OAuth2 user consent cannot be relayed - only
        // api_key/bearer/basic/none integrations execute with a shared platform secret.
        if ("oauth2".equalsIgnoreCase(api.getAuthType())) {
            return RelayResult.of(RelayResult.Status.OAUTH_NOT_RELAYABLE);
        }

        Optional<PlatformCredentialLookupDto> credential = resolveRelayableCredential(api);
        if (credential.isEmpty()) {
            return RelayResult.of(RelayResult.Status.PLATFORM_NOT_AVAILABLE);
        }
        Long platformCredentialId = credential.get().getId();

        Optional<BigDecimal> markupOpt = resolveMarkup(platformCredentialId, tool.getId());
        if (markupOpt.isEmpty()) {
            // MANDATORY pricing: no published positive markup → refuse (no free ride).
            return RelayResult.of(RelayResult.Status.PLATFORM_NOT_AVAILABLE);
        }
        BigDecimal markup = markupOpt.get();

        // Server-generated billing key: never derived from CE input (a
        // client-controlled key would let an install replay one key for
        // unlimited calls billed once). No SourceIdBuilder factory fits the
        // CE-relay shape (RUN/STREAM/INIT are the existing families), so the
        // key is built from the public markup prefix directly.
        String sourceId = SourceIdBuilder.MARKUP_DEBIT_PREFIX + ":CE:" + UUID.randomUUID();
        CreditConsumptionClient.ScopeReserveResult reserve = creditClient.scopeReserve(
                cloudUserId, sourceId, api.getApiName(), toolSlug,
                markup, null, reserveTtlMinutes,
                CE_RELAY_SCOPE_KIND, installId, false);
        if (!reserve.success()) {
            return RelayResult.refused(reserve.error(), reserve.delinquent());
        }

        String toolId = apiSlug + "/" + toolSlug;
        ToolExecutionResponse response;
        // Forced platform source, mirroring CatalogV1Controller.executeToolInternal:
        // set before the call, always cleared in finally before the thread returns
        // to the pool.
        CredentialModeContext.setExplicitSource("platform");
        CredentialModeContext.setSelectedCredentialId(null);
        try {
            response = catalogV1Service.executeTool(
                    toolId,
                    buildExecutionRequest(request, platformCredentialId),
                    String.valueOf(cloudUserId),
                    null,
                    "ce-relay-" + UUID.randomUUID().toString().substring(0, 8));
        } catch (Exception e) {
            // An execution-layer exception is an upstream failure for billing
            // purposes: release the reservation and relay a failed result (the
            // frozen contract returns upstream errors as 200 + success=false).
            creditClient.scopeRelease(sourceId, "ce-relay execution error");
            log.warn("CE catalog relay execution error for install={} tool={}: {}",
                    installId, toolId, e.getMessage());
            return RelayResult.ok(ToolExecutionResponse.builder()
                    .success(false)
                    .error("Tool execution failed: " + e.getMessage())
                    .toolId(toolId)
                    .build(), BigDecimal.ZERO);
        } finally {
            CredentialModeContext.clear();
        }

        if (response != null && response.isSuccess()) {
            creditClient.scopeCommit(sourceId, markup, api.getApiName(), toolSlug);
            return RelayResult.ok(response, markup);
        }
        creditClient.scopeRelease(sourceId, "ce-relay upstream failure");
        if (response == null) {
            response = ToolExecutionResponse.builder()
                    .success(false)
                    .error("Tool execution returned no response")
                    .toolId(toolId)
                    .build();
        }
        return RelayResult.ok(response, BigDecimal.ZERO);
    }

    /**
     * Read-only availability probe for one integration, mirroring steps 1-4 of
     * {@link #execute} without side effects. Per-tool markup when
     * {@code apiToolId} is given, else the integration-level default markup.
     * An unknown integration returns the {@code available=false} shape, never
     * an error.
     */
    public PlatformInfo platformInfo(String integrationName, UUID apiToolId) {
        Optional<ApiEntity> apiOpt = apiRepository.findByPlatformCredentialName(integrationName)
                .filter(api -> Boolean.TRUE.equals(api.getIsActive()));
        boolean relayEligible = apiOpt.isPresent()
                && !"oauth2".equalsIgnoreCase(apiOpt.get().getAuthType());

        Optional<PlatformCredentialLookupDto> credential =
                credentialClient.findPlatformCredentialByName(integrationName)
                        .filter(dto -> !"bridge".equalsIgnoreCase(dto.getProviderKind()));
        if (credential.isEmpty()) {
            return new PlatformInfo(integrationName, false, null, false, null, relayEligible);
        }
        Long platformCredentialId = credential.get().getId();

        BigDecimal markup = apiToolId != null
                ? resolveMarkup(platformCredentialId, apiToolId).orElse(null)
                : credentialClient.getLatestPricingVersion(platformCredentialId)
                        .map(PricingVersionDto::getDefaultMarkupCredits)
                        .filter(m -> m.signum() > 0)
                        .orElse(null);
        boolean hasPricing = markup != null;
        return new PlatformInfo(integrationName, true, platformCredentialId,
                hasPricing, hasPricing ? markup.toPlainString() : null, relayEligible);
    }

    /**
     * The API's platform credential, when one exists and is relay-usable.
     * Bridge credentials do their own internal accounting and are never
     * relayed. Empty → {@code PLATFORM_NOT_AVAILABLE}.
     */
    private Optional<PlatformCredentialLookupDto> resolveRelayableCredential(ApiEntity api) {
        String integrationName = api.getPlatformCredentialName();
        if (integrationName == null || integrationName.isBlank()) {
            return Optional.empty();
        }
        return credentialClient.findPlatformCredentialByName(integrationName)
                .filter(dto -> !"bridge".equalsIgnoreCase(dto.getProviderKind()));
    }

    /**
     * The strictly positive per-call markup frozen in the credential's latest
     * published pricing version. Empty when no pricing version exists, the
     * tool has no resolvable rate, or the rate is not positive.
     */
    private Optional<BigDecimal> resolveMarkup(Long platformCredentialId, UUID apiToolId) {
        return credentialClient.getLatestPricingVersion(platformCredentialId)
                .map(PricingVersionDto::getPricingVersionId)
                .flatMap(versionId -> credentialClient.resolveFrozenMarkup(versionId, apiToolId))
                .map(FrozenMarkupDto::getEffectiveMarkup)
                .filter(markup -> markup.signum() > 0);
    }

    private static ToolExecutionRequest buildExecutionRequest(CeCatalogRelayRequest request,
                                                              Long platformCredentialId) {
        return ToolExecutionRequest.builder()
                .parameters(request.getParameters())
                .expand(request.getExpand())
                .maxItems(request.getMaxItems())
                .inlineBinaries(request.getInlineBinaries())
                // Server-resolved, authoritative: never taken from CE input.
                .credentialSource("platform")
                .platformCredentialId(platformCredentialId)
                // NO billingScope fields: ToolExecutionManager's internal billing
                // hook no-ops on missing scope, so the reserve/commit lifecycle in
                // this service is the ONLY billing path (no double billing).
                .build();
    }

    /**
     * Typed outcome of {@link #execute}. {@code billedCredits} is the amount
     * committed on the linked account ({@code 0} when the reservation was
     * released), surfaced for the controller's audit log.
     */
    public record RelayResult(Status status,
                              ToolExecutionResponse response,
                              String error,
                              boolean delinquent,
                              BigDecimal billedCredits) {

        public enum Status { OK, TOOL_NOT_FOUND, OAUTH_NOT_RELAYABLE, PLATFORM_NOT_AVAILABLE, INSUFFICIENT_CREDITS }

        static RelayResult of(Status status) {
            return new RelayResult(status, null, null, false, BigDecimal.ZERO);
        }

        static RelayResult refused(String error, boolean delinquent) {
            return new RelayResult(Status.INSUFFICIENT_CREDITS, null, error, delinquent, BigDecimal.ZERO);
        }

        static RelayResult ok(ToolExecutionResponse response, BigDecimal billedCredits) {
            return new RelayResult(Status.OK, response, null, false, billedCredits);
        }
    }

    /**
     * Read-only availability shape for {@code GET /platform-info/{integrationName}}.
     * {@code markupCredits} is a plain decimal string (never scientific notation)
     * so the CE can display it without BigDecimal round-tripping.
     */
    public record PlatformInfo(String integrationName,
                               boolean available,
                               Long platformCredentialId,
                               boolean hasPricing,
                               String markupCredits,
                               boolean relayEligible) {
    }
}
