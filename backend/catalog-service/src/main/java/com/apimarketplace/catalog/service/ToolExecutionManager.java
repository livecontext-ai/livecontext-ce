package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ToolNextHintEntity;
import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ToolNextHintRepository;
import com.apimarketplace.catalog.service.billing.CatalogToolBillingService;
import com.apimarketplace.catalog.service.exception.ToolNotFoundException;
import com.apimarketplace.catalog.service.execution.BinaryResponseHandler;
import com.apimarketplace.catalog.service.execution.ToolExecutionOrchestrator;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.PlatformCredentialLookupDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    public ToolExecutionResponse executeTool(String toolIdOrSlug,
                                             ToolExecutionRequest request,
                                             String userId,
                                             String orgId,
                                             String requestId) {
        ToolContextService.ToolContext context = toolContextService.loadToolContext(toolIdOrSlug)
                .orElseThrow(() -> new ToolNotFoundException(toolIdOrSlug));

        long start = System.currentTimeMillis();
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
            log.info("[ToolExecutionManager] Tool: {}, API: {}, Received parameters map: {}", 
                    context.getToolName(), apiId, parameters);
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
                        entry.getKey(), entry.getValue(),
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
            boolean cacheEnabled = request != null
                    && "STREAM".equalsIgnoreCase(request.getBillingScopeKind());

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
                log.info("[ToolExecutionManager] Executing tool {} (API: {}) with {} parameters for userId={}, JSON: {}",
                        context.getToolName(), apiId, parameters.size(), userId, parametersJson);

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
                            context.getExecutionMode()
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
            ResponseShaper.Mode shapingMode = "STREAM".equalsIgnoreCase(scopeKind)
                    ? ResponseShaper.Mode.AGENT
                    : ResponseShaper.Mode.WORKFLOW;
            ResponseShaper.ShapingResult shapingResult =
                    responseShaper.shape(resultData, expandPaths, maxItems, shapingMode);

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

            // V148+ unified billing: post-success hook. Workflow-origin (RUN)
            // and chat (STREAM) flow through the same path here, both keyed on
            // the BillingScope built from request headers + credentialSource.
            // Failures are swallowed inside CatalogToolBillingService - billing
            // never blocks a successful tool result reaching the caller. Legacy
            // CatalogBillingDispatcher stays alive (no-op'd by Phase 1b.orchestrator
            // delete) until callers are migrated; this hook is the single entry
            // point for all NEW tool calls.
            if (success && catalogBillingService != null) {
                try {
                    String billingOutcome = invokeCatalogBilling(
                            context, request, executionResult, userId, apiId);
                    if (billingOutcome != null) {
                        metadata.put("billingOutcome", billingOutcome);
                    }
                } catch (Exception billingEx) {
                    log.warn("[ToolExecutionManager] Billing hook failed for tool {}: {} - tool result NOT affected",
                            context.getToolName(), billingEx.getMessage());
                }
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
        }
    }

    /**
     * V148+ unified billing dispatch. Builds a {@link CatalogToolBillingService.BillingScope}
     * from the request DTO + executionResult metadata + the tool's API row,
     * then calls {@code billImmediate}. Returns the outcome enum string (or
     * null when no billing applies - BYOK, bridge, missing scope, etc).
     *
     * <p>Resolves the platform credential numeric id via
     * {@code CredentialClient.findPlatformCredentialByName} since catalog
     * stores credentials by name and the markup subsystem keys on id.
     *
     * <p>Treats user-key calls (BYOK) as no-op via the
     * {@link CatalogToolBillingService#shouldBill} branch - the scope still
     * matters for the bypass logic, but BYOK short-circuits before any pricing
     * lookup.
     */
    private String invokeCatalogBilling(ToolContextService.ToolContext context,
                                          ToolExecutionRequest request,
                                          Map<String, Object> executionResult,
                                          String userId,
                                          UUID apiId) {
        String credentialSource = (String) executionResult.get("credentialSource");
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
        String billingProvider = null;
        String billingModel = context != null ? context.getToolName() : null;
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

        // ttlMinutes default - short for catalog post-flight; callers that
        // reserve longer (e.g. workflow run-init) do that out-of-band.
        int ttlMinutes = 10;

        CatalogToolBillingService.BillingScope scope = CatalogToolBillingService.BillingScope.of(
                userIdLong,
                credentialSource != null ? credentialSource.toUpperCase() : null,
                platformCredentialId,
                providerKind,
                billingProvider,
                billingModel,
                toolSlug,
                request != null ? request.getBillingScopeKind() != null && "RUN".equals(request.getBillingScopeKind()) ? request.getBillingScopeId() : null : null,
                request != null ? request.getBillingScopeKind() != null && "STREAM".equals(request.getBillingScopeKind()) ? request.getBillingScopeId() : null : null,
                request != null ? request.getBillingStepId() : null,
                request != null && request.getBillingEpoch() != null ? request.getBillingEpoch() : 0,
                request != null && request.getBillingSpawn() != null ? request.getBillingSpawn() : 0,
                request != null && request.getBillingIteration() != null ? request.getBillingIteration() : 0,
                request != null && request.getBillingItemIndex() != null ? request.getBillingItemIndex() : 0,
                request != null && request.getBillingCallIndex() != null ? request.getBillingCallIndex() : 0,
                ttlMinutes);

        return catalogBillingService.billImmediate(scope);
    }

    /** Build {@code <api-slug>/<tool-slug>} from the tool context + api row.
     *  Uses {@code ApiToolEntity.toolSlug} (the canonical kebab slug, e.g.
     *  {@code "google-gemini-generate-content"}), NOT {@code toolName} (display
     *  name like {@code "generate_content"}). The composite slug must round-trip
     *  through {@code CatalogToolBillingService.resolveApiToolUuid} which splits
     *  on "/" and looks up by {@code (apiId, tool_slug)} - wrong slug = silent
     *  bill-skip = revenue leak. */
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

}
