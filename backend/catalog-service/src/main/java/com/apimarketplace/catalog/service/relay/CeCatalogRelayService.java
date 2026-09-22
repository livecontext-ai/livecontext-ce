package com.apimarketplace.catalog.service.relay;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.dto.CeCatalogRelayRequest;
import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.service.CatalogV1Service;
import com.apimarketplace.catalog.service.ToolExecutionManager;
import com.apimarketplace.catalog.service.billing.CatalogToolBillingService;
import com.apimarketplace.catalog.service.generation.RelayedGenerationMeasurement;
import com.apimarketplace.catalog.service.http.CredentialModeContext;
import com.apimarketplace.catalog.service.http.ProviderRetryContext;
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
import java.util.LinkedHashMap;
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

        // The body is passed so a generation can be MEASURED here rather than
        // priced as an unmeasurable one and refused. It is the same body that
        // is about to be executed, so the size that is billed is the size that
        // runs.
        Optional<PricedCall> markupOpt =
                resolveMarkup(platformCredentialId, tool.getId(), request.getParameters());
        if (markupOpt.isEmpty()) {
            // MANDATORY pricing: no published positive markup → refuse (no free ride).
            return RelayResult.of(RelayResult.Status.PLATFORM_NOT_AVAILABLE);
        }
        BigDecimal markup = markupOpt.get().markup();
        // What the ledger row is called. A generation names its MODEL, because the endpoint does
        // not identify what was bought; everything else keeps naming the endpoint. Same rule, and
        // the same reason, as the direct execution path - a linked install's spend lands on the
        // cloud account's usage page beside the cloud's own, and one of the two naming its rows
        // differently would make the model filter answer half a question.
        String ledgerModel = markupOpt.get().modelId() != null && !markupOpt.get().modelId().isBlank()
                ? markupOpt.get().modelId()
                : toolSlug;

        // Server-generated billing key: never derived from CE input (a
        // client-controlled key would let an install replay one key for
        // unlimited calls billed once). No SourceIdBuilder factory fits the
        // CE-relay shape (RUN/STREAM/INIT are the existing families), so the
        // key is built from the public markup prefix directly.
        String sourceId = SourceIdBuilder.MARKUP_DEBIT_PREFIX + ":CE:" + UUID.randomUUID();
        CreditConsumptionClient.ScopeReserveResult reserve = creditClient.scopeReserve(
                cloudUserId, sourceId, api.getApiName(), ledgerModel,
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
        // Second entry point into the execution funnel, so it owns the retry context exactly as
        // the controller does. begin() also resets the RE-SEND COUNT, which does not self-heal the
        // way the budget does: a count left on a pooled thread would be reported on the NEXT
        // request through that thread, for a different tenant, as a re-send that never happened.
        ProviderRetryContext.begin(request.getProviderRetryMaxWaitSeconds());
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
            ProviderRetryContext.clear();
        }

        if (response != null && response.isSuccess()) {
            String outcome = creditClient.scopeCommit(sourceId, markup, api.getApiName(), ledgerModel);
            return RelayResult.ok(withBilledCredits(response, outcome, markup), markup);
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
     * Put the amount the linked account was charged onto the answer the install reads.
     *
     * <p>The relay bills on its own, BEFORE handing the execution to the ordinary path with
     * {@code billingOwnedByCaller}, so that path reserves nothing and reports no amount. Without
     * this the one edition where the charge is least visible - a self-hosted install spending a
     * cloud balance it cannot see - would be the only one whose generated assets could not say what
     * they cost. Same key and same rule as the direct path, so the install's own code reads one
     * shape whichever way the call was routed.
     *
     * <p>Nothing is added unless the commit took the whole amount: a partial charge is smaller than
     * {@code markup} and its real figure is not returned, so reporting the reserved one would state
     * a price nobody paid.
     */
    private static ToolExecutionResponse withBilledCredits(ToolExecutionResponse response,
                                                            String commitOutcome, BigDecimal markup) {
        if (!CatalogToolBillingService.commitTookTheWholeAmount(commitOutcome)
                || markup == null || markup.signum() <= 0) {
            return response;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(
                response.getMetadata() == null ? Map.of() : response.getMetadata());
        metadata.put(ToolExecutionManager.BILLED_CREDITS_KEY, markup);
        response.setMetadata(metadata);
        return response;
    }

    /**
     * Read-only availability probe for one integration, mirroring steps 1-4 of
     * {@link #execute} without side effects. Per-tool markup when
     * {@code apiToolId} is given, else the integration-level default markup.
     * An unknown integration returns the {@code available=false} shape, never
     * an error.
     */
    public PlatformInfo platformInfo(String integrationName, UUID apiToolId) {
        return platformInfo(integrationName, apiToolId, null, null);
    }

    /**
     * V428: the same probe, told which generation model is being asked about
     * and how big the call would be.
     *
     * <p>Without those two the probe can only resolve an ENDPOINT-wide price,
     * and every seeded generation is priced per MODEL, so it answered "nothing
     * published" for a model the relay then executed and charged at its real
     * per-second rate. A self-hosted install therefore showed "not sold on the
     * platform key" beside a button that billed the customer. The execution
     * path measures the call itself and always did; this makes the ANSWER
     * agree with it, which is the whole reason both halves share resolveMarkup.
     *
     * @param modelId  generation model the surface is quoting, or null for an
     *                 ordinary endpoint
     * @param quantity PLATFORM measurement of the call being quoted, or null
     *                 when the surface does not know it yet
     */
    public PlatformInfo platformInfo(String integrationName, UUID apiToolId,
                                      String modelId, BigDecimal quantity) {
        return platformInfo(integrationName, apiToolId, modelId, quantity, null);
    }

    /**
     * The same probe, told what the call's own CHOICES do to the rate.
     *
     * <p>The EXECUTING path derives that factor itself, out of the body it is
     * sent, and charges it. A probe that could not be told about it answered
     * with the unmodified rate, so an install quoted one amount and was billed
     * another for the same request: the two are only ever compared by the
     * customer, after the fact, which is the worst possible place.
     *
     * <p>Nothing here is trusted for EXECUTION. This door only reads, and an
     * install that understated its own factor would misquote a price to itself
     * and still be charged the real one.
     *
     * @param priceMultiplier factor for the call being quoted, or null for one
     *                        at the published rate
     */
    public PlatformInfo platformInfo(String integrationName, UUID apiToolId,
                                      String modelId, BigDecimal quantity,
                                      BigDecimal priceMultiplier) {
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
                ? resolveMarkup(platformCredentialId, apiToolId, modelId, quantity, priceMultiplier)
                        .orElse(null)
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
     *
     * <p><b>A generation is measured here, from the body, before it is priced.</b>
     * A relayed call carries the provider's own parameters, and the cloud owns
     * the descriptor that produced them, so it can read back which model was
     * selected and how big the call is and hand both to the published row. That
     * is what lets a per-model, per-second price apply to a relayed call at
     * all: without it the only resolvable row was the endpoint-wide one, and a
     * seeded generation (priced per model) had none, so every relayed
     * generation was refused.
     *
     * <p>The measurement is taken here rather than accepted from the install,
     * because an install that declared its own size could declare a ten second
     * video as one second. An ordinary endpoint measures nothing and is priced
     * exactly as before. A generation the cloud cannot measure falls back to
     * the quantity-less rate, which still refuses a per-unit row rather than
     * charging one unit for a whole call.
     *
     * <p>Both callers share this method on purpose. One executes the call and
     * one only advertises whether the tool is sold, and a discovery answer that
     * quotes a price execution would refuse is worse than either behaviour on
     * its own, because nothing downstream would ever reveal the disagreement.
     */
    private Optional<BigDecimal> resolveMarkup(Long platformCredentialId, UUID apiToolId) {
        // The probe asks one question - is this tool sold, and for how much - and has no body to
        // measure, so it wants the price alone. The model that comes back beside it is null here by
        // construction.
        return resolveMarkup(platformCredentialId, apiToolId, null).map(PricedCall::markup);
    }

    /**
     * A priced relayed call: what it costs, and WHICH model that price is for.
     *
     * <p>The model travels out with the price because the ledger row needs it as its label. One
     * endpoint backs several models at several rates (Gemini's generate_content serves a 78-credit
     * image model and a 268-credit one), so a row that names the endpoint writes the same two words
     * over purchases that differ by a factor of three, and the usage page's model filter cannot
     * tell them apart. Null for every ordinary endpoint, which then labels by endpoint as before.
     */
    private record PricedCall(BigDecimal markup, String modelId) {}

    private Optional<PricedCall> resolveMarkup(Long platformCredentialId, UUID apiToolId,
                                                Map<String, Object> upstreamParams) {
        // Only generations are held to the stricter rule, so only look the tool
        // up when the answer can change something.
        boolean generation = isGeneration(apiToolId);
        // What this call IS and how big it is, measured here from the body the
        // install sent, never declared by it. See RelayedGenerationMeasurement:
        // a caller that stated its own size could state a ten second video as
        // one second. Empty for every ordinary endpoint and for the discovery
        // caller, which has no body, and both then behave exactly as before.
        RelayedGenerationMeasurement.Measured measured = generation
                ? RelayedGenerationMeasurement.measure(generationSpecOf(apiToolId), upstreamParams)
                : RelayedGenerationMeasurement.Measured.NOTHING;
        return priceFor(platformCredentialId, apiToolId, generation, measured)
                .map(markup -> new PricedCall(markup, measured.modelId()));
    }

    /**
     * Price for a call whose model and size are already known.
     *
     * <p>Used by the read-only probe, where there is no request body to measure
     * and the surface states what it is asking about. Nothing here is trusted
     * for EXECUTION: the executing path never comes through this door, it
     * measures the body it is about to send. A probe that lies about its own
     * quantity misquotes a price to itself and changes nothing that is charged.
     */
    private Optional<BigDecimal> resolveMarkup(Long platformCredentialId, UUID apiToolId,
                                                String modelId, BigDecimal quantity,
                                                BigDecimal priceMultiplier) {
        boolean generation = isGeneration(apiToolId);
        // The unit is DERIVED from the model rather than taken from the caller,
        // exactly as the executing path derives it from the body. The probe
        // does not know it, and a quote that skipped it would answer with an
        // amount the biller refuses: a disagreement that shows up on neither
        // end, since both look like ordinary successes.
        String quantityUnit = generation
                ? RelayedGenerationMeasurement.platformUnitFor(generationSpecOf(apiToolId), modelId)
                : null;
        return priceFor(platformCredentialId, apiToolId, generation,
                new RelayedGenerationMeasurement.Measured(modelId, quantity, quantityUnit,
                        priceMultiplier));
    }

    /**
     * <b>This leg prices against the LATEST published version; the direct path
     * prices against a PINNED one.</b> Not introduced here and not changed by
     * the price factor, but worth naming, because the two are easy to read as
     * one mechanism and they are not.
     *
     * <p>A direct call resolves through {@code resolveScopeMarkupRate}, which
     * takes the pricing version PINNED to the run or stream when it began, so
     * an administrator republishing a rate mid-session cannot move the amount a
     * call in flight is charged. A relayed call has no such scope: it arrives
     * as a single request from an install, with no run to have pinned anything,
     * so it resolves the latest version each time. The consequence is real and
     * narrow: the same generation, run twice within one session across a rate
     * change, is charged the old rate locally and the new rate through the
     * relay.
     *
     * <p>The factor rides both paths identically, so it neither causes this nor
     * widens it. Closing it would mean giving a relayed call a scope to pin
     * against, which is a decision about the relay's billing model rather than
     * about pricing, and it is not taken here.
     */
    private Optional<BigDecimal> priceFor(Long platformCredentialId, UUID apiToolId,
                                           boolean generation,
                                           RelayedGenerationMeasurement.Measured measured) {
        return credentialClient.getLatestPricingVersion(platformCredentialId)
                .map(PricingVersionDto::getPricingVersionId)
                .flatMap(versionId -> credentialClient.resolveFrozenMarkup(
                        versionId, apiToolId, measured.modelId(), measured.quantity(),
                        // What this call's own choices do to the rate, read back
                        // out of the body rather than declared by the install.
                        // Null for every ordinary endpoint and every model with
                        // no declared modifiers, and the amount is then the one
                        // this path resolved before they existed.
                        measured.priceMultiplier()))
                // A per-unit row resolved without a quantity is an amount for ONE
                // unit. Charging it would sell a ten second video for the price
                // of one second, so refuse instead: an amount nobody could
                // measure is not a price. The administrator has a real remedy,
                // publish a flat endpoint price, or let the generation surface
                // measure the call.
                .filter(frozen -> {
                    if (frozen.isPricedPerUnitWithoutQuantity()) {
                        log.warn("Refusing relayed tool {}: its published price is per {} and this path "
                                        + "carries no quantity, so the amount would cover one unit rather "
                                        + "than the call.", apiToolId, frozen.getPriceUnit());
                        return false;
                    }
                    // A credential-wide default is a price for the ordinary
                    // endpoints of an API, where one call costs about what the
                    // next one does. A generation on the same key can cost the
                    // owner dollars per call, so letting it inherit the
                    // catch-all sells it at the price of a lookup, silently and
                    // positively. Refuse for the same reason an unpriced one is
                    // refused: nobody decided this amount for this endpoint.
                    if (generation && frozen.isKnownToBeVersionDefault()) {
                        log.warn("Refusing relayed generation {}: its amount is the credential-wide "
                                        + "default rather than a price published for this endpoint.",
                                apiToolId);
                        return false;
                    }
                    // A positive amount is not proof the price and the call are
                    // talking about the same thing. The publish-time guard only
                    // arms on a RE-publish, so a first "per image" row on a
                    // model measured in seconds reaches here unchallenged and
                    // would bill a ten second clip ten times the per-image
                    // rate. The direct path refuses this same pair; without the
                    // check here the two paths disagree, and since the CE quote
                    // reads this very resolver, both ends would report the same
                    // wrong number with nothing to reveal it.
                    if (!frozen.canPriceMeasurementIn(measured.quantityUnit())) {
                        log.warn("Refusing relayed tool {}: published per {} but this call is measured "
                                        + "in {}, so the two cannot be multiplied.",
                                apiToolId, frozen.getPriceUnit(), measured.quantityUnit());
                        return false;
                    }
                    return true;
                })
                .map(FrozenMarkupDto::getEffectiveMarkup)
                .filter(markup -> markup.signum() > 0);
    }

    /**
     * Whether this endpoint produces a generated asset. A tool that cannot be
     * loaded is treated as ordinary: this decides how STRICTLY to price, and a
     * lookup failure is not evidence of anything, so it must not become a
     * refusal of traffic that was fine a moment earlier.
     */
    /** The generation descriptor on the target endpoint, or null when unreadable. */
    private com.apimarketplace.catalog.service.generation.GenerationSpec generationSpecOf(UUID apiToolId) {
        if (apiToolId == null) return null;
        try {
            return apiToolRepository.findById(apiToolId)
                    .map(ApiToolEntity::getGenerationSpec)
                    .filter(json -> json != null && !json.isBlank())
                    .flatMap(json -> {
                        try {
                            return com.apimarketplace.catalog.service.generation.GenerationSpec.parse(
                                    objectMapper.readTree(json), "relay tool " + apiToolId);
                        } catch (Exception parseFailure) {
                            return Optional.empty();
                        }
                    })
                    .orElse(null);
        } catch (Exception e) {
            // Unmeasurable, not free: the caller then prices it the
            // conservative way, which refuses a per-unit row.
            log.warn("Could not read the generation descriptor for relayed tool {}: {}",
                    apiToolId, e.getMessage());
            return null;
        }
    }

    private boolean isGeneration(UUID apiToolId) {
        if (apiToolId == null) {
            return false;
        }
        try {
            return apiToolRepository.findById(apiToolId)
                    .map(ApiToolEntity::isGeneration)
                    .orElse(false);
        } catch (Exception e) {
            log.warn("Could not determine whether relayed tool {} is a generation: {} - pricing it "
                    + "as an ordinary endpoint", apiToolId, e.getMessage());
            return false;
        }
    }

    private static ToolExecutionRequest buildExecutionRequest(CeCatalogRelayRequest request,
                                                              Long platformCredentialId) {
        return ToolExecutionRequest.builder()
                .parameters(request.getParameters())
                .expand(request.getExpand())
                .maxItems(request.getMaxItems())
                .inlineBinaries(request.getInlineBinaries())
                // The install's node decided this; the cloud caps it at its own budget.
                .providerRetryMaxWaitSeconds(request.getProviderRetryMaxWaitSeconds())
                // Server-resolved, authoritative: never taken from CE input.
                .credentialSource("platform")
                .platformCredentialId(platformCredentialId)
                // NO billingScope fields, and an explicit statement of why: the
                // reserve/commit lifecycle in THIS service is the only billing
                // path for a relayed call (no double billing).
                //
                // Saying it explicitly rather than leaving it to be inferred from
                // the absent scope is the whole point. "No scope" reads as
                // "nobody bills this", which for a resold generation is a reason
                // to refuse, and refusing here would reject a call this service
                // has already reserved and is about to commit. The flag is
                // wire-sealed on the DTO, so only an in-process caller that runs
                // its own reserve/commit can set it.
                .billingOwnedByCaller(true)
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
