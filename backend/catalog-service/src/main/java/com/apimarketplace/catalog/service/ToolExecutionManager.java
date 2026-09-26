package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ToolNextHintEntity;
import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ToolNextHintRepository;
import com.apimarketplace.catalog.service.billing.CatalogToolBillingService;
import com.apimarketplace.catalog.service.http.LoggedShape;
import com.apimarketplace.catalog.service.generation.GenerationSpec;
import com.apimarketplace.catalog.service.generation.RelayedGenerationMeasurement;
import com.apimarketplace.catalog.service.exception.InsufficientCreditsException;
import com.apimarketplace.catalog.service.exception.ToolNotFoundException;
import com.apimarketplace.catalog.service.execution.BinaryResponseHandler;
import com.apimarketplace.catalog.service.execution.ToolExecutionOrchestrator;
import com.apimarketplace.catalog.service.relay.CeCatalogCloudRelay;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.PlatformCredentialLookupDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ToolExecutionManager {

    private final ToolContextService toolContextService;
    private final ApiService apiService;
    private final ObjectMapper objectMapper;
    private final ResponseShaper responseShaper;
    private final NextActionBuilder nextActionBuilder;
    private final ResponseCache responseCache;
    private final ToolNextHintRepository toolNextHintRepository;
    private final ToolResponseService toolResponseService;
    private final ToolExecutionOrchestrator toolExecutionOrchestrator;
    private final BinaryResponseHandler binaryResponseHandler;
    private final CatalogToolBillingService catalogBillingService;
    private final CredentialClient credentialClient;
    private final ApiRepository apiRepository;
    private final CeCatalogCloudRelay ceCatalogCloudRelay;

    /**
     * Per-plan availability of the integration being called. Injected through a
     * setter rather than the Lombok constructor so the eight tests that build this
     * manager by hand keep compiling, and so a slice without auth-client simply
     * gates nothing.
     */
    private CatalogPlanAccess catalogPlanAccess;

    @Autowired(required = false)
    public void setCatalogPlanAccess(CatalogPlanAccess catalogPlanAccess) {
        this.catalogPlanAccess = catalogPlanAccess;
    }

    /**
     * Product-analytics emitter for {@code api_call_completed}. Setter-injected for the
     * same reason as {@link #setCatalogPlanAccess}: the hand-built test managers keep
     * compiling, and a slice without the bean simply emits nothing.
     */
    private com.apimarketplace.catalog.service.analytics.ApiCallAnalytics apiCallAnalytics;

    @Autowired(required = false)
    public void setApiCallAnalytics(com.apimarketplace.catalog.service.analytics.ApiCallAnalytics apiCallAnalytics) {
        this.apiCallAnalytics = apiCallAnalytics;
    }

    public ToolExecutionResponse executeTool(String toolIdOrSlug,
                                             ToolExecutionRequest request,
                                             String userId,
                                             String orgId,
                                             String requestId) {
        ToolContextService.ToolContext context = toolContextService.loadToolContext(toolIdOrSlug)
                .orElseThrow(() -> new ToolNotFoundException(toolIdOrSlug));

        // Analytics envelope around the WHOLE execution, so every exit is counted
        // once: the success envelope, the caught-exception envelope, the CE relay,
        // and the refusals that are rethrown for the controller (credits, plan,
        // credential selection). The emitter is best-effort and never throws.
        long analyticsStart = System.currentTimeMillis();
        ToolExecutionResponse response = null;
        RuntimeException failure = null;
        try {
            response = executeResolvedTool(context, toolIdOrSlug, request, userId, orgId, requestId);
            return response;
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            if (apiCallAnalytics != null) {
                apiCallAnalytics.emit(context, request, userId, orgId, response, failure,
                        System.currentTimeMillis() - analyticsStart);
            }
        }
    }

    private ToolExecutionResponse executeResolvedTool(ToolContextService.ToolContext context,
                                                      String toolIdOrSlug,
                                                      ToolExecutionRequest request,
                                                      String userId,
                                                      String orgId,
                                                      String requestId) {
        // Plan gate, before ANY billing or credential work: an integration the
        // account's plan does not include must cost nothing to be refused, and the
        // refusal must name the plan that would include it. Throws
        // PlanUpgradeRequiredException -> HTTP 403. Placed ahead of the CE relay
        // because the relay is a CE path, where the gate is off anyway.
        if (catalogPlanAccess != null) {
            catalogPlanAccess.assertAllowed(userId, context.getApiSlug(), context.getToolSlug(),
                    context.getToolName());
        }

        // CE cloud relay: when the install is cloud-linked, the admin selected the CLOUD
        // catalog source, and NO local platform credential exists for the integration of a
        // credentialSource="platform" call, the whole execution is delegated to the cloud
        // (which injects its platform credential and bills the linked account). Empty =
        // proceed with the unchanged local path. Placed AFTER loadToolContext so unknown
        // tools still surface the local ToolNotFoundException.
        if (ceCatalogCloudRelay != null) {
            Optional<ToolExecutionResponse> relayed =
                    ceCatalogCloudRelay.tryRelay(toolIdOrSlug, request, userId, requestId);
            if (relayed.isPresent()) {
                return relayed.get();
            }
        }

        long start = System.currentTimeMillis();
        // Reservation lifecycle state. Non-null sourceId = credits are held and
        // MUST be settled exactly once, by commit or by release. `settled`
        // guards the finally-block safety net so a normal commit is not
        // followed by a release that would refund a legitimate charge.
        String reservationSourceId = null;
        java.math.BigDecimal reservedAmount = null;
        CatalogToolBillingService.BillingScope billingScope = null;
        boolean reservationSettled = false;
        // Set when the billing pre-flight allowed the call only because the
        // caller has a provider key of their own. Restored in the finally block
        // so the pin never outlives this call on a pooled thread.
        boolean pinnedToCallersOwnKey = false;
        try {
            // Récupérer l'apiId depuis le context (déjà chargé)
            String apiIdStr = context.getApiId();
            if (apiIdStr == null || apiIdStr.isBlank()) {
                throw new RuntimeException("API ID not found for tool " + toolIdOrSlug);
            }
            UUID apiId = UUID.fromString(apiIdStr);
            
            // Convertir les paramètres en JsonNode (format attendu: tableau d'objets)
            Map<String, Object> parameters = request != null && request.getParameters() != null 
                    ? request.getParameters() 
                    : Map.of();
            
            // Log détaillé des paramètres reçus
            // Shape only, never values: a parameter can carry a resolved {{credential.x}}.
            log.info("[ToolExecutionManager] Tool: {}, API: {}, Received parameters map: {}",
                    context.getToolName(), apiId, LoggedShape.of(parameters));
            log.info("[ToolExecutionManager] Tool: {}, API: {}, Parameters count: {}, Keys: {}", 
                    context.getToolName(), apiId, parameters.size(), parameters.keySet());
            
            // ApiService.executeApiTool() attend un JsonNode qui est un tableau d'objets
            // Format: [{"paramName1": "value1"}, {"paramName2": "value2"}]
            // Null values are skipped - they would serialize as {} (empty object) which
            // crashes HttpExecutionService.processQueryParameters (NoSuchElementException
            // on fieldNames().next()). This caused SerpAPI google_flights to lose ALL
            // params when optional fields like stops/children were not provided.
            List<Map<String, Object>> parametersList = new ArrayList<>();
            for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                if (entry.getValue() == null) {
                    log.debug("[ToolExecutionManager] Skipping null parameter: {}", entry.getKey());
                    continue;
                }
                Map<String, Object> paramObj = new LinkedHashMap<>();
                paramObj.put(entry.getKey(), entry.getValue());
                parametersList.add(paramObj);
                log.debug("[ToolExecutionManager] Parameter: {} = {} (type: {})",
                        entry.getKey(), LoggedShape.of(entry.getValue()),
                        entry.getValue().getClass().getSimpleName());
            }
            JsonNode parametersJson = objectMapper.valueToTree(parametersList);
            
            // Cache scoping: STREAM (chat-agent) callers only.
            //
            // The 5-minute response cache exists to dedupe the chat agent's
            // "expand" pattern - same tool, same params, re-fired within a
            // reasoning turn. Workflow callers (scope=RUN, or no scope at all
            // for internal/test fixtures) must always see a fresh upstream
            // call: an hourly trigger that double-fires (e.g. 15:44:11 then
            // 15:44:40) would otherwise re-process the same upstream result,
            // re-classifying / re-labelling / double-charging API quotas. The
            // discriminator mirrors the ResponseShaper.Mode selection below
            // ("STREAM" → AGENT, otherwise → WORKFLOW) so a single signal
            // governs both behaviours.
            // A GENERATION is never cached, whatever its scope. The cache exists
            // for reads, where serving the same answer twice costs nobody
            // anything. A generation is a purchase: the reservation is taken
            // before the cache is consulted and committed after, so a cache HIT
            // charges the customer again for an asset the provider was never
            // asked to make. The same request inside the window would take the
            // money and hand back the previous run's signed URL, which the
            // provider time-limits, so the second charge can also resolve to
            // nothing at all.
            //
            // Recognised BOTH ways, because the two halves cover different
            // callers. A named model is what the generation surface sends and
            // needs no lookup; the endpoint's own descriptor is what identifies
            // the same endpoint reached by raw slug through catalog(execute),
            // which sends no model at all. A first version asked only the first
            // half and left the second caller charging twice.
            boolean cacheEnabled = request != null
                    && "STREAM".equalsIgnoreCase(request.getBillingScopeKind())
                    && !isGenerationCall(request, context)
                    && isCacheable(context);

            // Credential-state versioning of the cache key. Without it, a cached
            // response survives a credential switch: connect a new Gmail account,
            // set it as default, re-ask the agent within the TTL → the agent is
            // served the OLD account's data with no credential resolution at all
            // (prod bug 2026-06-11). The version moves on every credential
            // mutation that can change resolution (connect / delete /
            // set-as-default / edit / token refresh), so a switch lands on a
            // fresh key and forces a real execution. Fetched only for
            // STREAM-scoped (chat-agent) callers - workflow calls never read or
            // populate the cache, so they pay no extra auth round-trip.
            String cacheKey = toolIdOrSlug + ":" + userId;
            if (cacheEnabled) {
                cacheKey = cacheKey + ":" + credentialClient.getCredentialStateVersion(userId);
            }
            // ── V148+ unified billing, step 1 of 2: PRE-FLIGHT RESERVATION ──
            //
            // Runs BEFORE the tool is dispatched, and a refusal returns 402
            // without dispatching. Reserving afterwards would only record that
            // the customer could not pay for something they have already been
            // given: a resold generation produces the video, stores it, hands
            // it over, and the platform owner pays the provider out of pocket.
            //
            // Sits before the cache read on purpose, so the answer to "may this
            // caller have this result?" does not depend on whether a previous
            // identical call happens to still be cached. A cache hit is billed
            // exactly as it is today (see the commit call at the end).
            //
            // Endpoints with no published platform price are untouched:
            // preflightReserve returns allowedWithoutBilling for them (null
            // sourceId), which is every ordinary catalog tool.
            //
            // ── One charge per dispatched call: the `callRef` ──
            //
            // Everything keys off `sourceId`, and the ledger has a unique index
            // on it: the auth-side reserve answers "a row already exists for
            // that sourceId, zero debit, success" for any key it has seen
            // before. That makes the tail of the sourceId the thing that decides
            // whether a call costs anything, and it is why this method mints a
            // fresh `callRef` per invocation rather than reading one off the
            // request.
            //
            // The comment that used to sit here claimed the opposite - that each
            // call "carries its own call index, so it gets its own key" - and
            // described a retry as deliberately free. Neither was true. The
            // index came from ToolExecutionRequest fields no caller ever set, so
            // it was 0 on every call: the first generation in a chat turn or a
            // workflow run was charged and every one after it was free. Nor is
            // there any retry to deduplicate at this layer - one HTTP execute is
            // one dispatch to the provider, so a second one produced a second
            // asset the provider billed the platform owner for, and it has to be
            // a second charge.
            //
            // The value is server-generated for the same reason the generation
            // quantity is: it decides the amount charged, so a caller who could
            // choose it could re-send one key and take everything after the
            // first call for free.
            //
            // Two paths remain safe on top of it:
            //
            // 1. CACHE HIT - reserved and committed exactly like a fresh call,
            //    which is what this path already did before the reservation
            //    moved. Its own callRef means it cannot collide with the call
            //    that populated the cache.
            // 2. EXCEPTION BETWEEN RESERVE AND SETTLE - the finally block below
            //    releases. It cannot refund a committed charge: `settled` is set
            //    by settleReservation, and an auth-side release on a committed
            //    row answers ALREADY_COMMITTED without touching the balance.
            //    If even the release fails, the reservation's TTL expiry sweeps
            //    it, which is why the TTL is sized to the real call ceiling.
            if (catalogBillingService != null) {
                billingScope = buildBillingScope(context, request, intendedCredentialSource(request),
                        userId, apiId, UUID.randomUUID().toString());
                // No scope means no guard below can run, and the call would go
                // out free. That is correct for the 1000+ ordinary catalog
                // endpoints and wrong for a generation, which spends the
                // owner's paid provider key: an unresolvable composite slug or
                // a user id that is not a number would produce the asset with
                // no reservation and no ledger row. Refuse instead, using the
                // one fact available without a scope, which is that the call
                // named a generation model.
                if (billingScope == null && catalogBillingService.generationRequiresAScope(
                        intendedCredentialSource(request),
                        isGenerationCall(request, context),
                        request != null && Boolean.TRUE.equals(request.getBillingOwnedByCaller()))) {
                    // Null-safe on the model: this guard also fires for a call
                    // that named no model and was recognised by the endpoint's
                    // own descriptor, and that caller can arrive with no request
                    // object at all. Logging it unguarded would replace the
                    // refusal with a NullPointerException, which the caller
                    // reads as a server fault rather than as a price decision.
                    log.error("[ToolExecutionManager] Refusing generation {} on tool {}: no billing scope "
                                    + "could be built, so it cannot be charged.",
                            request == null ? null : request.getGenerationModelId(),
                            context.getToolName());
                    throw new InsufficientCreditsException(
                            CatalogToolBillingService.UNBILLABLE_GENERATION_UNIDENTIFIED_CALL, false);
                }
                CatalogToolBillingService.PreflightDecision decision = reserve(billingScope, context);
                if (decision != null) {
                    if (!decision.allowed()) {
                        // No reservation was written, nothing to release. Thrown
                        // rather than returned so the controller can answer 402
                        // instead of a 200 envelope the caller has to introspect.
                        throw new InsufficientCreditsException(decision.error(), decision.delinquent());
                    }
                    // Allowed only because the caller has a provider key of
                    // their own, and unbilled on that basis. Pin the call to
                    // that pool so the premise holds: the agentic path would
                    // otherwise fall back to the PLATFORM credential the moment
                    // their key failed (a revoked key answering 401 is the
                    // ordinary case), producing a resold asset on the platform's
                    // provider quota with no reservation to commit. With the pin
                    // the platform key cannot answer at all, so the unbilled
                    // verdict follows from which key ANSWERED rather than from
                    // which keys the caller happens to have configured.
                    if (decision.restrictToCallersOwnKey()) {
                        com.apimarketplace.catalog.service.http.CredentialModeContext
                                .setExplicitSource("user");
                        pinnedToCallersOwnKey = true;
                        log.info("[ToolExecutionManager] Tool {} runs on the caller's own credential only "
                                        + "(no platform fallback): the platform reservation was refused and "
                                        + "the call is not billed",
                                context.getToolName());
                    }
                    // Null sourceId = allowed, but nothing to bill (BYOK toggle,
                    // bridge credential, no published price, or an ORDINARY
                    // endpoint called with no billing scope - a resold
                    // generation with no scope is refused above, not allowed).
                    reservationSourceId = decision.sourceId();
                    reservedAmount = decision.reservedAmount();
                }
            }

            Object cachedData = cacheEnabled ? responseCache.get(cacheKey, parameters) : null;

            Object resultData;
            boolean success;
            Map<String, Object> executionResult;
            boolean fromCache = false;
            // Per-call binary opt-out: when true, the dehydrator is skipped on
            // BOTH the cache-miss and cache-hit paths. Used by workflow nodes
            // that need the raw bytes inline (e.g. re-encoding to email
            // attachments, transformer nodes that consume the b64 directly).
            boolean shouldKeepBinariesInline = request != null && Boolean.TRUE.equals(request.getInlineBinaries());
            BinaryResponseHandler.DehydrationResult dehydrationResult = null;

            if (cachedData != null) {
                // Cache hit - reuse previous response. Reachable only for
                // STREAM-scoped callers (chat agent) since the workflow-bypass
                // gate above sets cachedData=null for RUN/no-scope callers.
                log.info("[ToolExecutionManager] Cache HIT for tool {} (STREAM scope) - skipping API call", context.getToolName());
                resultData = cachedData;
                success = true;
                executionResult = Map.of("success", true, "data", cachedData, "status", "cached");
                fromCache = true;
            } else {
                // Cache miss - execute API call
                log.info("[ToolExecutionManager] Executing tool {} (API: {}) with {} parameters for userId={}, shape: {}",
                        context.getToolName(), apiId, parameters.size(), userId,
                        LoggedShape.of(parameters));

                executionResult = apiService.executeApiTool(
                        apiId.toString(),
                        context.getToolName(),
                        parametersJson,
                        context.getAllowedParameterNames(),
                        userId
                );

                success = Boolean.TRUE.equals(executionResult.get("success"));
                resultData = executionResult.get("data");

                // ── Inline-binary dehydration (cache-miss path) ─────────────────
                //
                // ORDER MATTERS - must run BEFORE OutputProjector. Reason: the
                // projector calls {@code objectMapper.valueToTree(rawResponse)}
                // which silently coerces {@code String}s that "look like base64"
                // into Jackson {@code BinaryNode}s, then {@code projectField}'s
                // string case re-converts the node to {@code byte[]}. The
                // dehydrator's walker only matches {@code instanceof String}, so
                // a projector-mangled b64 leaks through unmolested → reaches the
                // agent as raw bytes that re-serialise back to base64 in the
                // tool result. This is the OpenAI {@code data[].b64_json} bug
                // (28/04 16:16): Gemini happens to dodge it because its base64
                // sits inside a deeply-typed Object branch where the projector
                // recurses without re-tokenising the leaf.
                //
                // With the dehydrator running FIRST, the tree the projector
                // receives already carries {@code Map{_type:'file', path, …}}
                // FileRef Maps in place of base64 leaves. Note that the schema
                // declares those leaves as {@code type:"string"} (e.g.
                // {@code data[].b64_json} in openai.json), so the projector
                // routes them through its {@code default} (string) case -
                // {@code objectMapper.convertValue(ObjectNode, Object.class)}
                // returns the Map unchanged for an object-shaped JsonNode, so
                // the FileRef shape is preserved end-to-end. The {@code byte[]}
                // corruption can no longer happen because the leaf is no
                // longer a String when the projector sees it.
                //
                // Caching is keyed off the post-dehydration tree (FileRef-only)
                // so cache HITS never re-upload - same invariant as before.
                if (!shouldKeepBinariesInline) {
                    dehydrationResult = binaryResponseHandler.dehydrateInlineBase64(
                            resultData, userId, context.getToolName());
                    if (dehydrationResult != null && dehydrationResult.hasAssets()) {
                        resultData = dehydrationResult.root();
                        log.info("[ToolExecutionManager] Dehydrated {} inline binary asset(s) for tool {} (cache-miss path, pre-projection)",
                                dehydrationResult.assets().size(), context.getToolName());
                    }
                }

                // Typed-execution refactor (Phase 7): project the dehydrated
                // response onto the tool's declared output_schema. When the
                // tool has no schema yet (legacy / not migrated), the projector
                // is a no-op and returns the data unchanged. Running AFTER
                // dehydration means {@code valueToTree}'s base64 sniffing
                // can't corrupt the b64 leaves - they're FileRef Maps now.
                if (success && resultData != null && context.getOutputSchemaJson() != null) {
                    try {
                        resultData = toolExecutionOrchestrator.projectResult(
                            resultData,
                            context.getOutputSchemaJson(),
                            context.getExecutionMode(),
                            responseHeadersOf(executionResult)
                        );
                    } catch (Exception projectorEx) {
                        log.warn("OutputProjector failed for tool {}: {} - falling back to raw response",
                            context.getToolName(), projectorEx.getMessage());
                    }
                }

                // Cache successful responses (now FileRef-replaced). Gated
                // on cacheEnabled so workflow callers never populate the
                // shared key - a chat-agent hitting the same toolId+userId
                // later in the 5-min window must still get a fresh execution
                // and not inherit the workflow's stale tree.
                if (cacheEnabled && success && resultData != null) {
                    responseCache.put(cacheKey, parameters, resultData);

                    // Auto-save tool response for skeleton generation (fire-and-forget)
                    try {
                        UUID toolUuid = context.getToolId() != null ? UUID.fromString(context.getToolId()) : null;
                        Integer httpStatus = executionResult.get("httpStatus") instanceof Map
                            ? (Integer) ((Map<?, ?>) executionResult.get("httpStatus")).get("code")
                            : null;
                        if (toolUuid != null) {
                            toolResponseService.autoSaveFromExecution(toolUuid, resultData, httpStatus);
                        }
                    } catch (Exception e) {
                        log.debug("Auto-save tool response skipped: {}", e.getMessage());
                    }
                }
            }

            long latency = System.currentTimeMillis() - start;

            // Cache-HIT path runs a second dehydration as defense in depth: if
            // anything ever lands in the cache pre-dehydration (legacy entry,
            // bug regression), it's still cleaned up here. The walker is
            // O(N) over the response tree, ~µs for FileRef-only data.
            //
            // When the sweep ACTUALLY dehydrates something (stale b64 in cache),
            // we MUST write the cleaned tree back into the cache - otherwise
            // every subsequent hit re-decodes the same b64, re-uploads with a
            // fresh UUID, and accumulates orphan MinIO objects. Forgetting the
            // write-back was a real leak path flagged in iteration-4 audit.
            if (!shouldKeepBinariesInline && fromCache) {
                BinaryResponseHandler.DehydrationResult hitSweep =
                        binaryResponseHandler.dehydrateInlineBase64(
                                resultData, userId, context.getToolName());
                if (hitSweep != null && hitSweep.hasAssets()) {
                    resultData = hitSweep.root();
                    responseCache.put(cacheKey, parameters, resultData);
                    log.warn("[ToolExecutionManager] Dehydrated {} stale binary asset(s) for tool {} (cache-hit path - cache rewritten to prevent re-upload)",
                            hitSweep.assets().size(), context.getToolName());
                    // Carry the hit-sweep assets forward so attachments[] is
                    // populated for the caller; otherwise the agent sees the
                    // FileRefs in the response tree but not in metadata.
                    dehydrationResult = hitSweep;
                }
            }

            // Apply response shaping (per-leaf truncation + total-budget enforcement
            // + array digest + oversize fallback). AGENT mode is opt-in via
            // billingScopeKind == "STREAM" (case-insensitive); everything else
            // falls back to WORKFLOW mode (per-leaf only, no digest markers,
            // preserves array shapes for SpEL / OutputProjector consumers).
            List<String> expandPaths = request != null ? request.getExpand() : null;
            Integer maxItems = request != null ? request.getMaxItems() : null;
            String scopeKind = request != null ? request.getBillingScopeKind() : null;
            // STEP_OUTPUT only on the caller's explicit word that this result IS a workflow
            // step's output: never inferred from scope RUN, which an agent inside a run sends too.
            ResponseShaper.Mode shapingMode = "STREAM".equalsIgnoreCase(scopeKind)
                    ? ResponseShaper.Mode.AGENT
                    : request != null && request.isStepOutput()
                        ? ResponseShaper.Mode.STEP_OUTPUT
                        : ResponseShaper.Mode.WORKFLOW;
            // An expand set survives the SIZE passes only on a generation call.
            // The signal is the generation model id, which is @JsonIgnore and
            // populated solely from the X-Lc-Generation-Model header this
            // service sets on its own generation path: a caller cannot post it,
            // for the same reason it cannot post the price. Without that gate,
            // an agent naming a large subtree in `expand` would lift the 64 KB
            // ceiling on its own context, and on a generation call the bytes
            // never reach an agent at all, because the module reads the asset
            // out of that subtree and prunes it from the reply.
            boolean expandSurvivesBudget = request != null && request.getGenerationModelId() != null;
            ResponseShaper.ShapingResult shapingResult = responseShaper.shape(
                    resultData, expandPaths, maxItems, shapingMode, expandSurvivesBudget);

            logShapingOutcome(shapingResult, shapingMode, context, userId, requestId);

            // Build final result with httpStatus included at top level
            Object finalResult = shapingResult.data();
            if (executionResult.containsKey("httpStatus")) {
                // If result is a Map, add httpStatus to it; otherwise wrap in a Map
                if (finalResult instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> resultMap = new LinkedHashMap<>((Map<String, Object>) finalResult);
                    resultMap.put("httpStatus", executionResult.get("httpStatus"));
                    finalResult = resultMap;
                } else {
                    Map<String, Object> wrappedResult = new LinkedHashMap<>();
                    wrappedResult.put("data", finalResult);
                    wrappedResult.put("httpStatus", executionResult.get("httpStatus"));
                    finalResult = wrappedResult;
                }
            }

            // Build metadata with optional hint for tools that return IDs only
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("toolName", context.getToolName());
            metadata.put("endpoint", context.getEndpoint());
            metadata.put("method", context.getHttpMethod());
            metadata.put("apiId", apiId.toString());
            if (context.getIconSlug() != null) {
                metadata.put("iconSlug", context.getIconSlug());
            }
            metadata.put("status", executionResult.getOrDefault("status", "unknown"));
            // Only when it happened, so a call that went out once carries nothing new. The wait
            // is inside this call, so the node stayed RUNNING and emitted nothing: this is the
            // only thing that tells a reader afterwards that the provider refused and we came
            // back, rather than the provider simply being slow.
            int providerRetries = com.apimarketplace.catalog.service.http.ProviderRetryContext.getRetries();
            if (providerRetries > 0) {
                metadata.put("providerRetries", providerRetries);
            }

            // Include httpStatus from HttpExecutionService (contains code and error)
            if (executionResult.containsKey("httpStatus")) {
                metadata.put("httpStatus", executionResult.get("httpStatus"));
            }
            if (fromCache) {
                metadata.put("cached", true);
            }

            // Add aggregated truncation info if fields were truncated. New shape
            // (v4): List<{path, count, bytes}> - one entry per canonicalised
            // pattern (e.g. items[].about×10), not one per concrete leaf path.
            if (shapingResult.hasTruncatedPatterns()) {
                List<Map<String, Object>> patternList = new ArrayList<>(shapingResult.truncatedPatterns().size());
                for (ResponseShaper.TruncationPattern p : shapingResult.truncatedPatterns()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("path", p.path());
                    entry.put("count", p.count());
                    entry.put("bytes", p.bytes());
                    patternList.add(entry);
                }
                metadata.put("truncatedFields", patternList);
            }

            // Surface dehydrated binaries for the chat agent + frontend.
            // Build the list by walking the FINAL tree (cache-miss + cache-hit
            // alike) - relying on `dehydrationResult.assets()` would empty
            // attachments[] on cache hits, where the cached tree already
            // contains FileRef Maps but the second sweep produces no new
            // assets. The agent must see the same `metadata.attachments` on
            // every call regardless of cache hit/miss.
            List<Map<String, Object>> attachments = collectFileRefMapsFromTree(finalResult);
            if (!attachments.isEmpty()) {
                metadata.put("attachments", attachments);
            }

            // Surface which credential pool answered ("user" | "platform"). Used by
            // downstream billing dispatchers to distinguish BYOK from platform-cost
            // passthrough. Absent when the call failed before credential resolution.
            if (executionResult.containsKey("credentialSource")) {
                metadata.put("credentialSource", executionResult.get("credentialSource"));
            }

            // Build unified nextAction hint. Priority chain:
            //   shaping-driven (digest > leaves > oversize) > DB hint > none.
            Map<String, Object> nextAction = buildNextAction(context, shapingResult, parameters);
            if (nextAction != null) {
                metadata.put("nextAction", nextAction);
            }

            // ── V148+ unified billing, step 2 of 2: COMMIT or RELEASE ──
            //
            // Exactly one of the two, exactly once, for every reservation taken
            // above. A failed upstream call releases: the customer is never
            // charged for a call that produced nothing.
            Settlement settlement = settleReservation(
                    reservationSourceId, reservedAmount, billingScope, context,
                    success, (String) executionResult.get("credentialSource"));
            reservationSettled = settlement.settled();
            // What this call cost, stated only when it cost something HERE.
            //
            // Absent is not zero. A call on the reader's own key, a released reservation, an
            // endpoint with no published price and a self-hosted install with no ledger at all
            // reach this line with nothing charged, and each of them would read as "free" if the
            // key were written with a zero in it. Downstream this number is shown beside an asset,
            // so a zero over a generation the reader paid their own provider for would be a lie
            // that looks like a fact.
            if (settlement.billedCredits() != null) {
                metadata.put(BILLED_CREDITS_KEY, settlement.billedCredits());
            }

            return ToolExecutionResponse.builder()
                    .success(success)
                    .result(finalResult != null ? finalResult : executionResult)
                    .error(success ? null : (String) executionResult.getOrDefault("error", "Unknown error"))
                    .metadata(metadata)
                    .executionTimeMs(latency)
                    .toolId(context.getToolId())
                    .requestId(requestId)
                    .build();
        } catch (InsufficientCreditsException e) {
            // Refusal, not a failure of the tool: no reservation exists and the
            // provider was never called. Rethrown so the controller answers 402
            // with the error + delinquent flag instead of a 200 envelope.
            throw e;
        } catch (com.apimarketplace.catalog.service.exception.CredentialSelectionException e) {
            // Refusal, not a failure of the tool: the step named a credential for
            // this run, it could not be honoured, and the provider was never called.
            // Rethrown for the same reason InsufficientCreditsException is: swallowed
            // into a generic error envelope, the refusal reads as "the API failed",
            // which sends the reader looking at the provider instead of at the
            // account the workflow asked for.
            throw e;
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.error("Error while executing tool {}: {}", toolIdOrSlug, e.getMessage(), e);

            // Build error metadata with iconSlug for UI display
            Map<String, Object> errorMetadata = new LinkedHashMap<>();
            errorMetadata.put("toolName", context.getToolName());
            if (context.getIconSlug() != null) {
                errorMetadata.put("iconSlug", context.getIconSlug());
            }

            // Add httpStatus for exception cases (code 0 = internal error)
            Map<String, Object> httpStatus = new LinkedHashMap<>();
            httpStatus.put("code", 0);
            httpStatus.put("error", e.getMessage());
            errorMetadata.put("httpStatus", httpStatus);

            // Build error result with httpStatus at top level
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("httpStatus", httpStatus);

            return ToolExecutionResponse.builder()
                    .success(false)
                    .result(errorResult)
                    .error(e.getMessage())
                    .executionTimeMs(latency)
                    .toolId(context.getToolId())
                    .requestId(requestId)
                    .metadata(errorMetadata)
                    .build();
        } finally {
            // Safety net for the third path: an exception thrown BETWEEN the
            // reserve and the settle (dehydration, projection, shaping, an
            // upstream client error, a bug). The reservation is released, so
            // held credits are never abandoned to expire on their own, and the
            // customer is not charged for a call whose result never reached
            // them. Cannot double-refund a committed reservation: `settled` is
            // set by settleReservation itself, and scope-release is idempotent
            // (a second one answers ALREADY_RELEASED).
            if (reservationSourceId != null && !reservationSettled) {
                try {
                    catalogBillingService.releaseOnFailure(reservationSourceId,
                            "catalog: execution aborted before settlement");
                    log.warn("[ToolExecutionManager] Released reservation {} for tool {} - execution aborted before settlement",
                            reservationSourceId, context.getToolName());
                } catch (Exception releaseEx) {
                    // Nothing else to try. The auth-side TTL expiry is the last
                    // line of defence and will return the credits on its own.
                    log.error("[ToolExecutionManager] Could not release reservation {} for tool {}: {}",
                            reservationSourceId, context.getToolName(), releaseEx.getMessage());
                }
            }
            // Drop the caller's-own-key pin with the call that asked for it. The
            // holder is thread-bound and this thread goes back to a pool, so
            // leaving it set would silently deny the platform credential to
            // whatever call lands on the thread next. Only ever applied when the
            // request carried no explicit source of its own (the relaxation
            // requires it), so clearing restores exactly the prior state.
            if (pinnedToCallersOwnKey) {
                com.apimarketplace.catalog.service.http.CredentialModeContext.setExplicitSource(null);
            }
        }
    }

    /**
     * Reservation TTL for one catalog tool call, in minutes.
     *
     * <p>Must outlive the longest a single call can legitimately stay in
     * flight, because the reservation is now taken BEFORE the call rather than
     * after it. The two hard ceilings that bound it:
     * <ul>
     *   <li>10 min - the upstream read timeout ({@code http.client.read-timeout}
     *       = 600000 ms), which is what a synchronous submit can burn.</li>
     *   <li>10 min - {@code AsyncPollExecutor.ABSOLUTE_MAX_WAIT_MS}, the hard
     *       cap on the in-process poll loop a long generation runs through,
     *       whatever the endpoint's own {@code maxWaitMs} claims.</li>
     * </ul>
     * A worst-case async generation is therefore submit + poll = 20 minutes,
     * before the result is dehydrated and uploaded to storage. 30 leaves a
     * third of the window as slack. Under-sizing it is the expensive mistake:
     * the reservation would expire mid-generation and the commit that follows
     * would find nothing to commit, i.e. exactly the free generation this
     * whole path exists to prevent. Over-sizing costs only a held balance on
     * the one path where the process dies between reserve and settle.
     */
    static final int RESERVE_TTL_MINUTES = 30;

    /**
     * The upstream read ceiling a synchronous submit can burn
     * ({@code http.client.read-timeout}).
     */
    public static final long UPSTREAM_READ_CEILING_MS = 600_000L;

    /**
     * How long ONE generation can legitimately stay in flight here, worst case:
     * the synchronous submit plus the poll loop that follows it.
     *
     * <p>Exists as a number rather than as prose because two callers have to be
     * sized on it and neither can afford to guess: the {@code generation} MCP
     * tool's own budget and the {@code agent:generate} node's read timeout. A
     * caller that gives up before this elapses does not cancel anything. The
     * generation completes, the asset is stored and the reservation is
     * COMMITTED, while the caller is told the generation service could not be
     * reached. The customer is charged for an asset they were told they did not
     * get, and nothing releases it, because from this side nothing failed.
     *
     * <p>So every caller budget must be at least this, and
     * {@link #RESERVE_TTL_MINUTES} must outlive the longest of them.
     */
    public static final long GENERATION_WORST_CASE_MS =
            UPSTREAM_READ_CEILING_MS
                    + com.apimarketplace.catalog.service.execution.AsyncPollExecutor.ABSOLUTE_MAX_WAIT_MS;

    /**
     * What a caller of a generation should wait, worst case plus room for the
     * asset to be dehydrated and uploaded to storage after the provider is done.
     *
     * <p>Five minutes of slack, and still five minutes inside
     * {@link #RESERVE_TTL_MINUTES}, so the ordering that matters holds end to
     * end: caller budget >= server worst case, reservation > caller budget.
     */
    public static final long GENERATION_CALLER_BUDGET_MS = GENERATION_WORST_CASE_MS + 300_000L;

    /**
     * Response-metadata key carrying the credits this call was charged.
     *
     * <p>Present ONLY on a call the platform actually billed: it is written from the committed
     * reservation, so a call on the reader's own key and a released one carry no key at all. A
     * self-hosted install carries none for a second, independent reason worth knowing - with markup
     * off, {@code preflightReserve} allows the call without billing, so there is no reservation to
     * settle and this code is never reached. The commit-outcome check is the guard for the case
     * where a reservation DOES exist; both sides of that check answer {@code BILLING_DISABLED}
     * rather than a success word when they metered nothing.
     *
     * <p>Same word as {@code GenerationProvenanceFields.BILLED_CREDITS} and deliberately not the
     * same constant: one names a field of a stored recipe, the other a field of a tool result, and
     * a shared constant would make a rename of either silently rewrite the other's wire format.
     */
    public static final String BILLED_CREDITS_KEY = "billedCredits";

    /**
     * The credential pool this call is going to use, as far as it can be known
     * BEFORE the call. Only the caller's explicit choice is knowable here:
     * {@code "user"} (never billed) or {@code "platform"} (billed).
     *
     * <p>Null - the agentic path, which tries the user's credential and falls
     * back to the platform's - is deliberately left null rather than guessed.
     * Null does not short-circuit {@code shouldBill}, so the reservation is
     * taken on the assumption that the platform key will answer, and
     * {@link #settleReservation} RELEASES it if the response says the user's
     * own credential answered instead. Assuming the cheaper outcome would put
     * us back where we started: the call would go out unreserved and the
     * refusal, if any, would arrive too late to prevent the spend.
     */
    private static String intendedCredentialSource(ToolExecutionRequest request) {
        String source = request != null ? request.getCredentialSource() : null;
        return source != null ? source.toUpperCase() : null;
    }

    /**
     * Take the pre-flight reservation for {@code scope}. Returns the decision,
     * or null when there is nothing to bill.
     *
     * <p>An exception out of {@code preflightReserve} (auth-service down, a
     * database blip, a bug) is not one answer, it is two, because the two
     * classes of endpoint have opposite money outcomes:
     * <ul>
     *   <li><b>Ordinary catalog endpoint</b> - proceeds UNBILLED, with a WARN.
     *       The 1000+ endpoints carry no published price, so the reserve was
     *       going to resolve nothing anyway; taking the whole catalog down
     *       because the credit ledger is unreachable costs more than it
     *       protects.</li>
     *   <li><b>Resold generation</b> - REFUSED with 402, provider never called.
     *       The third-party provider charges the platform owner for the asset
     *       whether or not the ledger answered, so "proceed unbilled" here
     *       means "produce a paid video and give it away".</li>
     * </ul>
     *
     * <p>That is what makes the posture consistent with itself. A transport
     * failure INSIDE {@code CreditConsumptionClient.scopeReserve} already comes
     * back as {@code allowed=false} and refuses; before this, the same outage
     * one frame up allowed the call. Same outage, same answer now.
     *
     * <p>A reserve that is REFUSED (insufficient credits, delinquent, no price
     * published, no billing scope) is unchanged: it arrives as
     * {@code allowed=false} and the caller turns it into a 402.
     */
    private CatalogToolBillingService.PreflightDecision reserve(
            CatalogToolBillingService.BillingScope scope,
            ToolContextService.ToolContext context) {
        if (scope == null) {
            return null;
        }
        try {
            return catalogBillingService.preflightReserve(scope);
        } catch (Exception e) {
            if (catalogBillingService.requiresBillingToProceed(scope)) {
                log.error("[ToolExecutionManager] Pre-flight reservation failed for resold generation {}: {} "
                                + "- refusing the call rather than generating it unbilled",
                        context.getToolName(), e.getMessage());
                throw new InsufficientCreditsException(
                        "PLATFORM_NOT_AVAILABLE: this generation cannot be billed right now, so it is not "
                                + "available on the platform key. Retry in a few minutes, or use your own "
                                + "provider key.", false);
            }
            log.warn("[ToolExecutionManager] Pre-flight reservation failed for tool {}: {} - proceeding unbilled",
                    context.getToolName(), e.getMessage());
            return null;
        }
    }

    /**
     * Close the reservation exactly once: commit what is actually due, or
     * release it. Reports whether the reservation has been settled (or that
     * there was nothing to settle), which is what stops the finally-block
     * safety net from releasing on top of a commit - and, when the commit took
     * the reserved amount whole, how much that was.
     *
     * <p>Three outcomes:
     * <ul>
     *   <li><b>Upstream failed</b> - release. A customer is never charged for a
     *       call that produced nothing.</li>
     *   <li><b>The user's own credential answered</b> - release. The agentic
     *       path cannot know this before the call (see
     *       {@link #intendedCredentialSource}), so BYOK is corrected here
     *       rather than guessed earlier. The reserve/release pair nets to
     *       zero.</li>
     *   <li><b>Success on the platform key</b> - commit the reserved amount.
     *       The projected amount IS the amount due: catalog pricing is resolved
     *       per call from the model and the size of the call, so there is no
     *       later adjustment to make.</li>
     * </ul>
     */
    private Settlement settleReservation(String sourceId,
                                      java.math.BigDecimal reservedAmount,
                                      CatalogToolBillingService.BillingScope scope,
                                      ToolContextService.ToolContext context,
                                      boolean success,
                                      String resolvedCredentialSource) {
        if (sourceId == null) {
            return Settlement.UNBILLED; // nothing reserved - nothing to settle
        }
        try {
            if (!success) {
                catalogBillingService.releaseOnFailure(sourceId, "catalog: upstream call failed");
                return Settlement.UNBILLED;
            }
            if ("USER".equalsIgnoreCase(resolvedCredentialSource)) {
                catalogBillingService.releaseOnFailure(sourceId,
                        "catalog: the user's own credential answered (BYOK) - not billable");
                return Settlement.UNBILLED;
            }
            String outcome = catalogBillingService.commitOnSuccess(sourceId, reservedAmount,
                    scope != null ? scope.provider() : null,
                    scope != null ? scope.model() : null);
            return Settlement.of(outcome, reservedAmount);
        } catch (Exception e) {
            // Report NOT settled so the finally block attempts the release. A
            // commit that threw after the ledger row flipped is safe to chase
            // with a release: scope-release on a committed row is a no-op.
            log.warn("[ToolExecutionManager] Could not settle reservation {} for tool {}: {}",
                    sourceId, context.getToolName(), e.getMessage());
            return Settlement.NOT_SETTLED;
        }
    }

    /**
     * How the reservation ended, and what it took.
     *
     * @param settled      true once the reservation can no longer be released twice - what stops
     *                     the finally-block safety net from refunding on top of a commit
     * @param billedCredits the amount charged, or null when this call charged nothing here
     */
    record Settlement(boolean settled, java.math.BigDecimal billedCredits) {

        /** Settled with no charge: nothing was reserved, the call failed, or the reader's own key answered. */
        static final Settlement UNBILLED = new Settlement(true, null);
        /** The settle itself failed, so the finally block still has to release. */
        static final Settlement NOT_SETTLED = new Settlement(false, null);

        /**
         * The outcome of a commit, read for whether the reserved amount is the amount taken.
         *
         * <p>Only a commit that took the whole reserved amount reports a number, and which
         * outcomes those are is decided once, next to the commit, by
         * {@code CatalogToolBillingService.commitTookTheWholeAmount}. A PARTIAL commit charges less
         * than was reserved and does not say how much, an expired reservation charged nothing, an
         * idempotent retry cannot say which commit it repeats, and a deployment that does not meter
         * reached no ledger. In each of those the amount is left absent, which every reader already
         * handles: it is the same answer as a call on the reader's own key.
         */
        static Settlement of(String commitOutcome, java.math.BigDecimal reservedAmount) {
            if (!CatalogToolBillingService.commitTookTheWholeAmount(commitOutcome)
                    || reservedAmount == null || reservedAmount.signum() <= 0) {
                return UNBILLED;
            }
            return new Settlement(true, reservedAmount);
        }
    }

    /**
     * V148+ unified billing scope. Builds a {@link CatalogToolBillingService.BillingScope}
     * from the request DTO + the tool's API row. Returns null when no billing
     * can apply (unresolvable tool slug, non-numeric user id).
     *
     * <p>Resolves the platform credential numeric id via
     * {@code CredentialClient.findPlatformCredentialByName} since catalog
     * stores credentials by name and the markup subsystem keys on id.
     *
     * <p>Treats user-key calls (BYOK) as no-op via the
     * {@code CatalogToolBillingService.shouldBill} branch - the scope still
     * matters for the bypass logic, but BYOK short-circuits before any pricing
     * lookup.
     *
     * @param callRef server-generated per-dispatch reference, minted by the
     *                caller of this method rather than read off the request:
     *                it is the tail of the ledger key, so it decides whether
     *                this call is charged at all.
     */
    private CatalogToolBillingService.BillingScope buildBillingScope(
                                          ToolContextService.ToolContext context,
                                          ToolExecutionRequest request,
                                          String credentialSource,
                                          String userId,
                                          UUID apiId,
                                          String callRef) {
        String toolSlug = buildCompositeSlug(context, apiId);
        if (toolSlug == null) {
            return null;
        }

        // Resolve the platform credential numeric id and providerKind.
        //
        // Workflow direct calls (toggle=platform) explicitly forward the id on
        // the request - we trust it as authoritative, since the workflow plan's
        // pricing pin is keyed on that exact id. Re-resolving by name here
        // would diverge from the pinned id when an admin keeps multiple
        // platform credential rows under the same `integration_name` (rare but
        // possible), causing the post-success markup lookup to miss and bill
        // at $0 - silent revenue leak. The pin id wins.
        //
        // Agentic calls (no `request.platformCredentialId`) still go through
        // the name lookup; the markup pin is created lazily on first hit and
        // resolved by id thereafter.
        Long platformCredentialId = request != null ? request.getPlatformCredentialId() : null;
        String providerKind = "cloud";
        // Provider/model used for the ledger row description ("Markup reservation: provider/model").
        // For catalog tool calls, neither LLM provider nor LLM model applies - fall back to the
        // human-readable apiName and the tool name from context so the ledger entry is traceable
        // (e.g. "Markup reservation: OpenAI/create_image") instead of the legacy "null/null".
        //
        // A GENERATION names its model instead of its endpoint, because the endpoint does not
        // identify what was bought. One endpoint backs several models at several rates - Gemini's
        // `generate_content` serves both gemini-2.5-flash-image and gemini-3-pro-image, at 78 and
        // 268 credits a call - so a ledger that records the endpoint writes the same two words over
        // two purchases that differ by a factor of three. The usage page filters on this column, so
        // the reader could see the amounts and never learn which model produced them.
        //
        // Ordinary endpoints are untouched: only a generation carries a model id here, and this is
        // a LABEL. Nothing prices, resolves or authorises on it - the price comes from
        // `generationModelId` passed separately to the reserve, and the endpoint is identified by
        // `toolSlug` - so the name written here decides what a person reads, and nothing else.
        //
        // The model is the one the CALL declared, and only that. This path never measures it out of
        // the body the way the CE relay does: there the install is not trusted to state its own
        // size, so the body is measured and the model falls out of that measurement for free, while
        // here the generation surface sends it (as `X-Lc-Generation-Model`, stripped at every edge).
        // Deriving it twice, two ways, for one fact is the duplication that ends with the two
        // disagreeing.
        //
        // So a call that names no model is labelled by endpoint. For a per-model priced endpoint
        // that never happens - such a call is REFUSED before it runs, unpriced - which covers the
        // case this change exists for, two models at two rates behind one endpoint. It does happen
        // for a generation endpoint carrying a single endpoint-level price, reached through
        // catalog(action='execute') with no model named: that row says what it can, which is which
        // endpoint was bought. The same call relayed from a linked install is labelled by model,
        // and those two rows can sit on one usage page. Worth knowing before reading a filter as
        // exhaustive.
        String billingProvider = null;
        String generationModelId = request != null ? request.getGenerationModelId() : null;
        String billingModel = generationModelId != null && !generationModelId.isBlank()
                ? generationModelId
                : (context != null ? context.getToolName() : null);
        var apiOpt = apiRepository.findById(apiId);
        if (apiOpt.isPresent()) {
            ApiEntity api = apiOpt.get();
            billingProvider = api.getApiName();
            // Only call findPlatformCredentialByName when the request didn't
            // supply the id (agentic path) OR we still need providerKind. In
            // the workflow direct path with id set, we still need providerKind
            // for the bridge-skip check at shouldBill:226.
            if (api.getPlatformCredentialName() != null) {
                String integrationName = api.getPlatformCredentialName();
                Optional<PlatformCredentialLookupDto> lookup =
                        credentialClient.findPlatformCredentialByName(integrationName);
                if (lookup.isPresent()) {
                    if (platformCredentialId == null) {
                        platformCredentialId = lookup.get().getId();
                    }
                    if (lookup.get().getProviderKind() != null) {
                        providerKind = lookup.get().getProviderKind();
                    }
                }
            }
        }

        Long userIdLong = parseUserIdSafe(userId);
        if (userIdLong == null) {
            return null;
        }

        // MEASURED from the body, not believed from the headers.
        //
        // The size, the unit, the model and the factor all decide what this call costs, and all
        // four arrived as `X-Lc-Generation-*` headers. Those are stripped at the gateway and at the
        // CE monolith, which is the whole defence - and stripping happens at an EDGE, so anything
        // reaching catalog-service without traversing one (another in-cluster service, a misrouted
        // ingress, a mesh misconfiguration) was believed. A forged `X-Lc-Generation-Multiplier:
        // 0.001` turned a 15 second 4K render from 11,670 credits into 11.67, and a forged
        // quantity of 1 did the same to a ten second clip, with billed_quantity reported back as
        // the forged value so nothing on the invoice looked wrong.
        //
        // The cloud already refuses to believe a linked install for exactly this reason and
        // re-derives everything from the provider-shaped body it was handed
        // (RelayedGenerationMeasurement). The body is right here too, so the same reading applies
        // and the headers stop being authoritative for money on every path.
        //
        // Null when this is not a generation, or when the body carries no model the descriptor
        // knows - and then the headers are used exactly as before, which keeps every ordinary
        // endpoint and every pre-existing caller unchanged.
        RelayedGenerationMeasurement.Measured measured = measureFromBody(context, request);

        CatalogToolBillingService.BillingScope scope = CatalogToolBillingService.BillingScope.of(
                userIdLong,
                credentialSource,
                platformCredentialId,
                providerKind,
                billingProvider,
                billingModel,
                toolSlug,
                request != null ? request.getBillingScopeKind() != null && "RUN".equals(request.getBillingScopeKind()) ? request.getBillingScopeId() : null : null,
                request != null ? request.getBillingScopeKind() != null && "STREAM".equals(request.getBillingScopeKind()) ? request.getBillingScopeId() : null : null,
                request != null ? request.getBillingStepId() : null,
                callRef,
                RESERVE_TTL_MINUTES,
                // V428: which generation model is being priced, and how big the
                // call is. Absent for an ordinary tool, in which case pricing
                // resolves exactly as it did before.
                measured != null && measured.modelId() != null ? measured.modelId() : generationModelId,
                measured != null ? measured.quantity()
                        : (request != null ? request.getGenerationQuantity() : null),
                // The unit that quantity is counted in. Carried so the billing
                // layer can tell a rate and a measurement apart when they
                // describe different things.
                measured != null ? measured.quantityUnit()
                        : (request != null ? request.getGenerationQuantityUnit() : null),
                // What the call's own choices do to the published rate (a 1080p
                // render, a reference image the provider charges to read).
                // Absent for every ordinary tool and for a generation whose
                // model declares no modifiers.
                measured != null ? measured.priceMultiplier()
                        : (request != null ? request.getGenerationPriceMultiplier() : null),
                // The CE relay reserved this call against the linked cloud
                // account before dispatching it and settles it itself. It is
                // wire-sealed on the DTO, so no external caller can claim it.
                request != null && Boolean.TRUE.equals(request.getBillingOwnedByCaller()));

        return scope;
    }

    /** Build {@code <api-slug>/<tool-slug>} from the tool context + api row.
     *  Uses {@code ApiToolEntity.toolSlug} (the canonical kebab slug, e.g.
     *  {@code "google-gemini-generate-content"}), NOT {@code toolName} (display
     *  name like {@code "generate_content"}). The composite slug must round-trip
     *  through {@code CatalogToolBillingService.resolveApiToolUuid} which splits
     *  on "/" and looks up by {@code (apiId, tool_slug)} - wrong slug = silent
     *  bill-skip = revenue leak. */
    /**
     * Whether this call produces a generated asset, asked BOTH ways.
     *
     * <p>A generation is a purchase, so it must never be served from the
     * response cache: the reservation is taken before the cache is read and
     * committed after, so a hit charges again for something the provider was
     * never asked to make.
     *
     * <p>Two callers reach the same endpoint and only one of them names a
     * model. The generation surface sends it, and that is free to read. The
     * ordinary {@code catalog(action='execute', tool_id='<api>/<tool>')} path
     * sends nothing of the sort, so for that one the endpoint's own descriptor
     * is the only signal there is. Asking only the cheap half left the second
     * caller charging twice, which is the defect this method exists to close.
     *
     * <p>Fails OPEN, deliberately: an unreadable catalog answers "not a
     * generation" and the call is merely cacheable, which is the behaviour
     * every ordinary endpoint already has. Failing closed here would disable
     * the cache for the whole surface on a database blip.
     */
    /**
     * Whether serving this endpoint's previous answer is the same as calling it.
     *
     * <p>True for almost everything, which is why the cache pays: a read costs the same
     * answer twice. It is FALSE for an endpoint whose read CONSUMES what it returns, and
     * those exist. Telegram's {@code getUpdates} is the reference case: it drains a
     * server-side queue and advances a cursor, so the bytes are gone once read. Cached, the
     * first call inside the window returns the real batch and every later one replays it,
     * so a message that genuinely arrived in between is never seen. The symptom is not an
     * error, it is a poller that reports an empty inbox forever, which is what production
     * showed: 313 calls over three days, every one of them {@code {"ok":true,"result":[]}},
     * most served from cache in under 40ms.
     *
     * <p>Declared by the endpoint in its {@code execution} block as {@code "cacheable":
     * false}, so the fact lives in the catalog seed beside the endpoint it describes rather
     * than in a list here that nobody updates when an API is added. Absent means cacheable,
     * which keeps every existing endpoint exactly as it was.
     */
    // Package-private so the decision can be tested on its own. Reaching it through a full
    // execution would need a billing scope, a reservation and an HTTP hop to assert one
    // boolean, and the test would then fail for a dozen reasons that are not this rule.
    boolean isCacheable(ToolContextService.ToolContext context) {
        String spec = context == null ? null : context.getExecutionSpecJson();
        if (spec == null || spec.isBlank()) {
            return true;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(spec);
            com.fasterxml.jackson.databind.JsonNode cacheable = node.get("cacheable");
            return cacheable == null || !cacheable.isBoolean() || cacheable.asBoolean();
        } catch (Exception e) {
            // Unreadable spec must not disable the cache for an endpoint that was fine
            // before: fail towards the behaviour every other endpoint already has.
            log.warn("[ToolExecutionManager] Could not read the execution spec of tool {}: {} - "
                    + "treating it as cacheable", context.getToolId(), e.getMessage());
            return true;
        }
    }

    private boolean isGenerationCall(ToolExecutionRequest request, ToolContextService.ToolContext context) {
        if (request != null && request.getGenerationModelId() != null) {
            return true;
        }
        if (apiToolRepoForSlug == null || context == null || context.getToolId() == null) {
            return false;
        }
        try {
            return apiToolRepoForSlug.findById(UUID.fromString(context.getToolId()))
                    .map(com.apimarketplace.catalog.domain.ApiToolEntity::isGeneration)
                    .orElse(false);
        } catch (Exception e) {
            log.warn("[ToolExecutionManager] Could not tell whether tool {} is a generation: {} - "
                    + "treating it as an ordinary endpoint for caching", context.getToolId(), e.getMessage());
            return false;
        }
    }

    /**
     * Read the billing context out of the BODY this call is about to send.
     *
     * <p>Returns null for an ordinary endpoint, for a body that names no model this descriptor
     * knows, or when the descriptor cannot be read at all - in every one of those the caller falls
     * back to what the request carried, which is what this path did before.
     *
     * <p>Deliberately the same class the CE relay uses. Two readers of one body would be two
     * chances to disagree, and a disagreement here is a call charged one amount locally and another
     * through the relay.
     */
    private RelayedGenerationMeasurement.Measured measureFromBody(
            ToolContextService.ToolContext context, ToolExecutionRequest request) {
        if (request == null || request.getParameters() == null || request.getParameters().isEmpty()) {
            return null;
        }
        if (apiToolRepoForSlug == null || context == null || context.getToolId() == null) {
            return null;
        }
        try {
            var tool = apiToolRepoForSlug.findById(UUID.fromString(context.getToolId()));
            if (tool.isEmpty() || !tool.get().isGeneration()) {
                return null;
            }
            GenerationSpec spec = GenerationSpec.parse(
                    objectMapper.readTree(tool.get().getGenerationSpec()), "tool:" + context.getToolId())
                    .orElse(null);
            if (spec == null) {
                return null;
            }
            RelayedGenerationMeasurement.Measured measured =
                    RelayedGenerationMeasurement.measure(spec, request.getParameters());
            // A body whose model this descriptor does not recognise is not something to price from:
            // fall back rather than replace a stated model with nothing.
            return measured != null && measured.modelId() != null ? measured : null;
        } catch (Exception e) {
            // Never fail a call because the descriptor could not be read. Falling back to the
            // request's own values is the behaviour that predates this.
            log.warn("[ToolExecutionManager] Could not measure the body of tool {} for billing: {} - "
                    + "using the values the request carried", context.getToolId(), e.getMessage());
            return null;
        }
    }

    private String buildCompositeSlug(ToolContextService.ToolContext context, UUID apiId) {
        if (apiToolRepoForSlug == null) return null;
        var apiOpt = apiRepository.findById(apiId);
        if (apiOpt.isEmpty() || apiOpt.get().getApiSlug() == null) return null;
        if (context.getToolId() == null) return null;
        try {
            UUID toolUuid = UUID.fromString(context.getToolId());
            var toolOpt = apiToolRepoForSlug.findById(toolUuid);
            if (toolOpt.isEmpty() || toolOpt.get().getToolSlug() == null) return null;
            return apiOpt.get().getApiSlug() + "/" + toolOpt.get().getToolSlug();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.catalog.repository.ApiToolRepository apiToolRepoForSlug;

    private static Long parseUserIdSafe(String userId) {
        if (userId == null || userId.isBlank()) return null;
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException nfe) {
            return null;
        }
    }

    /**
     * Walk a (possibly nested) JSON-shaped tree and collect every leaf Map
     * that looks like a FileRef ({@code _type:"file"}). Used to populate
     * {@code metadata.attachments[]} from the final response so that BOTH
     * cache-miss (where the dehydrator just produced the FileRefs) AND
     * cache-hit (where the cached tree already contains them) callers see
     * the same flat asset list. Dedupes by storage {@code path}.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> collectFileRefMapsFromTree(Object root) {
        List<Map<String, Object>> result = new ArrayList<>();
        java.util.Set<String> seenPaths = new java.util.HashSet<>();
        java.util.Deque<Object> stack = new java.util.ArrayDeque<>();
        if (root != null) stack.push(root);
        while (!stack.isEmpty()) {
            Object node = stack.pop();
            if (node instanceof Map<?, ?> map) {
                if ("file".equals(map.get("_type")) && map.get("path") instanceof String path) {
                    if (seenPaths.add(path)) {
                        result.add((Map<String, Object>) map);
                    }
                    continue; // do not recurse into a FileRef
                }
                for (Object v : map.values()) {
                    if (v != null) stack.push(v);
                }
            } else if (node instanceof List<?> list) {
                for (Object v : list) {
                    if (v != null) stack.push(v);
                }
            }
        }
        return result;
    }

    /**
     * Build a unified nextAction object for LLM guidance.
     *
     * <p>Priority chain (shaping-driven actions always win when shaping fired):
     * <ol>
     *   <li>Array digested → suggest pagination via resolved cursor + size.</li>
     *   <li>Per-leaf truncation only → suggest {@code expand} with top-3 patterns.</li>
     *   <li>Oversize fallback fired → suggest {@code max_items=1}.</li>
     *   <li>None of the above → fall through to the DB next-step hint.</li>
     * </ol>
     *
     * <p>Format:
     * {@snippet :
     *  {
     *    "tool": "catalog",
     *    "hint": "Human readable instruction with concrete param names",
     *    "params": { "tool_id": "...", "parameters": { ...originalParams, "offset": 3, "limit": 1 } }
     *  }
     * }
     */
    private Map<String, Object> buildNextAction(
            ToolContextService.ToolContext context,
            ResponseShaper.ShapingResult shapingResult,
            Map<String, Object> originalParams) {

        // Priority 1-3: shaping-driven actions
        var shapingAction = nextActionBuilder.build(context, shapingResult, originalParams);
        if (shapingAction.isPresent()) {
            return shapingAction.get();
        }

        // Priority 4: DB next-step hint (legacy)
        String dbHint = getNextStepHintFromDb(context);
        if (dbHint != null) {
            Map<String, Object> nextAction = new LinkedHashMap<>();
            nextAction.put("hint", dbHint);
            ToolNextHintEntity hintEntity = getNextStepHintEntity(context);
            if (hintEntity != null) {
                if (hintEntity.getNextToolName() != null) {
                    nextAction.put("tool", hintEntity.getNextToolName());
                }
                if (hintEntity.getNextToolId() != null) {
                    Map<String, Object> suggestedParams = new LinkedHashMap<>();
                    suggestedParams.put("tool_id", hintEntity.getNextToolId().toString());
                    nextAction.put("params", suggestedParams);
                }
            }
            return nextAction;
        }
        return null;
    }

    /**
     * Emit a structured log when shaping modifies the response. INFO for the
     * loud actions ({@code array_digested}, {@code oversize_fallback}); DEBUG
     * for the steady-state {@code leaves_only} (keeps signal-to-noise high);
     * silent on {@code untouched}.
     */
    private void logShapingOutcome(ResponseShaper.ShapingResult shaping,
                                   ResponseShaper.Mode mode,
                                   ToolContextService.ToolContext context,
                                   String userId,
                                   String requestId) {
        if (shaping.action() == ResponseShaper.Action.UNTOUCHED) return;

        String tool = context != null ? context.getToolName() : "<unknown>";
        String iconSlug = context != null ? context.getIconSlug() : null;
        String toolId = (iconSlug != null ? iconSlug : "tool") + "/" + tool;
        String patterns = shaping.truncatedPatterns().stream()
                .limit(5)
                .map(p -> p.path() + "×" + p.count() + "@" + p.bytes() + "B")
                .reduce((a, b) -> a + ", " + b).orElse("");

        String msg = String.format(
                "[SHAPER] tool=%s mode=%s raw=%dB shaped=%dB action=%s patterns=[%s] requestId=%s userId=%s",
                toolId, mode, shaping.rawBytes(), shaping.shapedBytes(),
                shaping.action(), patterns, requestId, userId);

        if (shaping.action() == ResponseShaper.Action.LEAVES_ONLY) {
            log.debug(msg);
        } else {
            log.info(msg);
        }
    }

    /**
     * Get the full hint entity from database.
     */
    private ToolNextHintEntity getNextStepHintEntity(ToolContextService.ToolContext context) {
        try {
            UUID apiToolId = context.getToolId() != null ? UUID.fromString(context.getToolId()) : null;
            UUID toolNameId = parseToolNameId(context);

            List<ToolNextHintEntity> hints = fetchHints(apiToolId, toolNameId);
            return hints.isEmpty() ? null : hints.get(0);
        } catch (Exception e) {
            log.debug("Error retrieving hint entity: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get the next step hint text from database.
     */
    private String getNextStepHintFromDb(ToolContextService.ToolContext context) {
        ToolNextHintEntity entity = getNextStepHintEntity(context);
        return entity != null ? entity.getHint() : null;
    }

    private UUID parseToolNameId(ToolContextService.ToolContext context) {
        String toolNameIdStr = context.getToolNameId();
        if (toolNameIdStr != null && !toolNameIdStr.isBlank()) {
            try {
                return UUID.fromString(toolNameIdStr);
            } catch (IllegalArgumentException e) {
                log.debug("Invalid tool_name_id format: {}", toolNameIdStr);
            }
        }
        return null;
    }

    private List<ToolNextHintEntity> fetchHints(UUID apiToolId, UUID toolNameId) {
        if (apiToolId != null && toolNameId != null) {
            return toolNextHintRepository.findByApiToolIdOrToolNameId(apiToolId, toolNameId);
        } else if (apiToolId != null) {
            return toolNextHintRepository.findByApiToolId(apiToolId);
        } else if (toolNameId != null) {
            return toolNextHintRepository.findByToolNameId(toolNameId);
        }
        return List.of();
    }

    /**
     * The response headers HttpExecutionService captured, if any.
     *
     * <p>They were being dropped here: only {@code executionResult.get("data")} went on to the
     * projection, so a value a provider returns ONLY in a header (LinkedIn's upload ETag, which
     * finalizeUpload requires) was unreachable by any endpoint declaration. Reading them costs
     * nothing for the tools that ignore them.
     */
    static Map<String, String> responseHeadersOf(Map<String, Object> executionResult) {
        if (executionResult == null) {
            return null;
        }
        Object headers = executionResult.get("headers");
        if (!(headers instanceof Map<?, ?> map)) {
            return null;
        }
        // Copied rather than cast. Every producer today builds this from
        // HttpHeaders.toSingleValueMap(), so the values are Strings, but an unchecked cast would
        // turn a future producer's non-String value into a ClassCastException inside a call that
        // had already succeeded. Coercing costs one small map per call and cannot throw.
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        return out;
    }

}
