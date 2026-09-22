package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiToolParameterEntity;
import com.apimarketplace.catalog.domain.ToolNextHintEntity;
import com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest;
import com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest.*;
import com.apimarketplace.catalog.domain.dto.ApiResponse;
import com.apimarketplace.catalog.dto.ToolResponseDto;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.repository.ToolNextHintRepository;
import com.apimarketplace.catalog.seed.CatalogSeedCredentialService;
import com.apimarketplace.catalog.service.http.HttpExecutionService;
import com.apimarketplace.catalog.util.CredentialTypeNormalizer;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.common.web.UrlSafetyValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Stream;

/**
 * Service for registering custom APIs from the api-migrations JSON schema format.
 * Used by the agent tool (catalog:register_api) and Settings UI.
 *
 * <p>Converts the api-migrations JSON format into {@link ApiConfigurationRequest}
 * and delegates to {@link ApiService#processApiConfiguration}. Always sets
 * {@code source='custom'} so custom APIs survive catalog re-imports.
 *
 * <p>Post-processing persists synthesis (lexical search index), nextHint,
 * fixtures, and API-level metadata (apiVersion, documentation, rateLimits)
 * that are not part of the McpToolDto/ApiSubmissionOrchestrator flow.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomApiRegistrationService {

    static final Set<String> ALLOWED_METHODS =
            Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    static final Set<String> ALLOWED_PARAM_IN =
            Set.of("query", "path", "body", "header");
    static final Set<String> ALLOWED_OUTPUT_TYPES =
            Set.of("string", "number", "boolean", "datetime", "object", "array", "fileRef");
    /**
     * Auth mechanisms a custom API may declare, in CANONICAL spelling
     * ({@link CredentialTypeNormalizer}), so every accepted spelling of one mechanism
     * ({@code bearer} / {@code bearer_token} / {@code bearerToken}) validates the same way.
     *
     * <p>{@code custom} is deliberately absent: a multi-field custom credential cannot be
     * expressed through this path, and accepting it would mean silently treating it as a single
     * API key, which is the class of bug this set exists to close.
     */
    static final Set<String> ALLOWED_AUTH_TYPES =
            Set.of("none", "api_key", "bearer_token", "basic_auth", "oauth2");

    private final ApiService apiService;
    private final com.apimarketplace.catalog.repository.ApiRepository apiRepository;
    private final ApiToolRepository apiToolRepository;
    private final ApiToolParameterRepository apiToolParameterRepository;
    private final LexicalIndexSyncService lexicalIndexSyncService;
    private final ToolNextHintRepository toolNextHintRepository;
    private final ToolResponseService toolResponseService;
    private final com.apimarketplace.catalog.seed.CatalogSeedCredentialService catalogSeedCredentialService;
    private final com.apimarketplace.credential.client.CredentialClient credentialClient;
    private final ObjectMapper objectMapper;

    /**
     * Register a custom API from the api-migrations JSON format.
     *
     * @param apiJson  The API definition in api-migrations schema format
     * @param tenantId The tenant (user) registering the API
     * @return The created API response
     */
    @Transactional
    public ApiResponse registerCustomApi(JsonNode apiJson, String tenantId) {
        return registerCustomApi(apiJson, tenantId, null);
    }

    /**
     * @param keyAlreadyHeld the credential key the caller already holds on a row this call is
     *                       REPLACING, or null for a fresh registration. Only
     *                       {@link #updateCustomApi} passes one, and it passes the key off the
     *                       row it just deleted. Without it an update is a new registration in
     *                       the eyes of the collision guard, so an API whose key collides with a
     *                       shipped integration becomes impossible to edit: the update deletes
     *                       the row, the re-registration refuses, and the transaction rolls back.
     *                       That is not hypothetical - on production seven of one customer's
     *                       twelve custom APIs carry such a key.
     */
    private ApiResponse registerCustomApi(JsonNode apiJson, String tenantId, String keyAlreadyHeld) {
        log.info("Registering custom API for tenant {}: {}", tenantId, apiJson.path("apiName").asText("unknown"));

        String apiName = requireField(apiJson, "apiName");
        String baseUrl = requireField(apiJson, "baseUrl");
        // Format-only validation at registration (no DNS resolution).
        // Full SSRF check (including DNS) happens at execution time in HttpExecutionService.
        UrlSafetyValidator.validateUrlFormat(baseUrl);

        // Canonicalise ONCE, then branch on the canonical value everywhere below. Validating the
        // canonical form while every other branch tested the raw string left a hole exactly the
        // width of a blank: authType "" canonicalises to "none" and passed validation, then took
        // the AUTHENTICATED branch in all four raw comparisons. The API was marked as needing a
        // user key and register_api told the agent to go ask for one, while linkCredentials hit
        // its own blank guard and created no template at all - so the credential the agent was
        // told to request did not exist and no tool required anything.
        String authType = CredentialTypeNormalizer.normalize(resolveAuthType(apiJson));
        validateAuthType(authType);
        boolean needsCredential = !"none".equals(authType);
        CatalogSeedCredentialService.ApiKeyConfig apiKeyConfig = resolveApiKeyConfig(apiJson);
        JsonNode oauth2Config = resolveOAuth2Config(apiJson, authType);
        String description = apiJson.path("apiDescription").asText(apiName + " API");
        String category = apiJson.path("apiCategory").asText("Custom APIs");
        String categoryGroup = category.endsWith(" APIs") ? category : category + " APIs";
        String subcategory = apiJson.path("apiSubcategory").asText(apiName);
        // ONE canonical slug, computed here and handed to every consumer below.
        //
        // It used to be computed twice from two different inputs: the orchestrator derived
        // apis.icon_slug from the generated apiSlug while this class derived the credential name
        // from the apiName. Those disagree whenever normalize() treats the two differently, and
        // the commonest case is an API whose name ends in "API": "Weather API" yields the slug
        // "weather-api", whose trailing "-api" normalize() strips, so icon_slug became "weather"
        // while the credential was named "weatherapi".
        //
        // Scope of what that actually broke, stated precisely because an earlier version of this
        // comment overstated it: the credential itself stayed consistent, since this class passed
        // the SAME string as both the credential name and catalog.credentials.icon_slug, and both
        // the connection wizard and the builder key on those. What diverged was apis.icon_slug
        // alone - which drives the workflow node type ("MCP:<iconSlug>:<toolSlug>"), the icon
        // file, and the text of the 401/403 a failed call returns. So this is a consistency fix,
        // not a repair of a key nobody could find.
        //
        // Passing the canonical value as the EXPLICIT iconSlug makes all three agree, because
        // deriveIconSlug short-circuits on an explicit value and normalize() is idempotent here.
        // Forward only, with no backfill: on production zero custom APIs are diverged, and
        // rewriting apis.icon_slug would re-key the node type of every workflow node already
        // placed from one of them. A migration with no rows to fix and a real blast radius is
        // not worth writing.
        String canonicalSlug = deriveCanonicalSlug(apiJson, apiName);
        if (needsCredential) {
            rejectSlugOwnedByASharedIntegration(canonicalSlug, apiName, tenantId, keyAlreadyHeld);
        }
        if (canonicalSlug.isBlank() && needsCredential) {
            throw new IllegalArgumentException(
                    "apiName must contain at least one letter or digit when authType is not 'none': "
                            + "it is what the credential for this API is keyed by. Received: '" + apiName + "'.");
        }
        String iconSlug = canonicalSlug.isBlank() ? null : canonicalSlug;
        // Custom APIs are always private - only visible to the creator
        String visibility = "private";

        // Build tools from endpoints array
        List<McpToolDto> tools = buildTools(apiJson, apiName);
        if (tools.isEmpty()) {
            throw new IllegalArgumentException("API must have at least one endpoint");
        }

        // Build the ApiConfigurationRequest - no pre-assigned apiId so the orchestrator
        // uses CrudRepository.save() (auto-generated UUID) instead of jdbcAggregateTemplate.insert()
        ApiConfigurationRequest request = new ApiConfigurationRequest(
                apiName,
                description,
                category,
                categoryGroup,
                subcategory,
                description,
                "",     // subcategoryIconUrl
                "",     // categoryId
                "",     // subcategoryId
                true,   // isCustomCategory
                true,   // isCustomSubcategory
                false,  // isLocal
                iconSlug,
                null,   // apiSlug (auto-generated)
                needsCredential ? "user_key" : null,  // credentialMode
                null,   // platformCredentialName (auto-derived by ApiSubmissionOrchestrator)
                "custom", // source
                apiJson.path("iconUrl").asText(null), // iconUrl
                "",     // toolCategoryIconUrl
                new ApiConfigDto(
                        baseUrl,
                        "",
                        new AuthorizationDto(authType, "Authentication for " + apiName, "Authorization", ""),
                        visibility
                ),
                buildFreeMonetization(),
                tools,
                null    // no pre-assigned apiId - let DB generate
        );

        ApiResponse response;
        try {
            response = apiService.processApiConfiguration(request, tenantId);
        } catch (RuntimeException e) {
            if (isDuplicateKeyException(e)) {
                throw new IllegalArgumentException(
                        "An API named '" + apiName + "' already exists for this user. "
                                + "Use catalog(action='update_api', api_id='<uuid>', api_definition={...}) to modify it, "
                                + "or catalog(action='list_custom_apis') to find its id.", e);
            }
            throw e;
        }

        // Post-processing: set API-level metadata not in ApiSubmissionOrchestrator flow
        UUID apiId = response.id();
        postProcessApiMetadata(apiJson, apiId);
        List<ApiToolEntity> savedTools = apiToolRepository.findByApiIdAndIsActiveTrue(apiId);
        postProcessSynthesisAndHints(apiJson, apiId, savedTools);
        postProcessFixtures(apiJson, apiId, savedTools);

        // Link credentials when authType requires authentication. The credential is keyed by the
        // SAME canonical slug the API entity carries (computed once above), so the name offered
        // for connection and the name execution requires can no longer disagree.
        if (needsCredential) {
            String apiIconUrl = apiJson.path("iconUrl").asText(null);
            catalogSeedCredentialService.linkCredentials(
                    apiId, canonicalSlug, authType, canonicalSlug, apiIconUrl, apiKeyConfig, oauth2Config);
        }

        return response;
    }

    /**
     * Update an existing custom API.
     * Validates tenant ownership, deletes the old API, then re-creates with new definition.
     * Wrapped in a single transaction so a failed re-create rolls back the delete.
     *
     * <p><b>Still true:</b> this generates a new UUID for the API and for every tool, so a
     * workflow step referencing an old tool ID stops resolving and has to be re-pointed. Making
     * the identifiers stable means teaching the submission orchestrator to reuse them, on the
     * path the whole imported catalog shares, which is a change of a different size from this one.
     *
     * <p><b>No longer true:</b> that an update also discards the author's stored key. It did, via
     * the same cleanup {@code delete_api} uses, and said nothing about it.
     */
    @Transactional
    public ApiResponse updateCustomApi(String apiId, JsonNode updates, String tenantId) {
        UUID id = UUID.fromString(apiId);
        var existing = apiRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("API not found: " + apiId));
        if (!"custom".equals(existing.getSource())) {
            throw new IllegalArgumentException("Only custom APIs can be updated via this endpoint");
        }
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (!ScopeGuard.isInStrictScope(tenantId, orgId, existing.getCreatedBy(), existing.getOrganizationId())) {
            throw new IllegalArgumentException("You can only update your own custom APIs");
        }

        // Validate the updates JSON is an object
        if (!updates.isObject()) {
            throw new IllegalArgumentException("Updates must be a JSON object");
        }

        // Drop only the CATALOG-side credential rows (template + tool links). They are rebuilt by
        // the re-registration below, and the tools they point at are about to be replaced.
        //
        // The user's own stored secret is deliberately NOT touched here, though it used to be:
        // this path called the same cleanup as delete_api, which asks auth-service to delete the
        // tenant's platform credential. So editing one description silently threw away the API
        // key, and the response said only that tool IDs had changed. An author fixing a typo got
        // a working key deleted, re-entered it, edited again, lost it again. Deleting an API is
        // where discarding its secret belongs; updating one is not.
        cleanupCatalogCredentialRows(existing);

        // Delete old API and re-create with new definition
        apiService.deleteApi(id);
        // Carry the key this API already held, so the collision guard does not refuse the row it
        // is re-creating. Anything else this definition asks for is still checked normally.
        return registerCustomApi(updates, tenantId, existing.getPlatformCredentialName());
    }

    /**
     * Get full details of a custom API, including endpoints/tools.
     * Validates that the API belongs to the requesting tenant.
     */
    public Map<String, Object> getCustomApiDetails(String apiId, String tenantId) {
        UUID id = UUID.fromString(apiId);
        var entity = apiRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("API not found: " + apiId));
        if (!"custom".equals(entity.getSource())) {
            throw new IllegalArgumentException("Not a custom API");
        }
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (!ScopeGuard.isInStrictScope(tenantId, orgId, entity.getCreatedBy(), entity.getOrganizationId())) {
            throw new IllegalArgumentException("Access denied");
        }

        var apiResponse = apiService.getApiById(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", apiResponse.id().toString());
        result.put("apiName", apiResponse.apiName());
        result.put("description", apiResponse.description());
        result.put("baseUrl", apiResponse.baseUrl());
        result.put("authType", apiResponse.authType() != null ? apiResponse.authType() : "none");
        // The credential key and the auth declaration travel with the details so an editor can
        // hand them back untouched. An editor that cannot see a field rewrites it to the default
        // on the next save, which is how a declared placement would quietly disappear.
        if (entity.getIconSlug() != null && !entity.getIconSlug().isBlank()) {
            result.put("iconSlug", entity.getIconSlug());
        }
        JsonNode declaredAuth = catalogSeedCredentialService.readDeclaredAuth(entity.getPlatformCredentialName());
        if (declaredAuth != null && declaredAuth.isObject() && !declaredAuth.isEmpty()) {
            ObjectNode authEntry = objectMapper.createObjectNode();
            authEntry.put("type", apiResponse.authType() != null ? apiResponse.authType() : "none");
            if (declaredAuth.has("apiKeyConfig")) {
                authEntry.set("apiKeyConfig", declaredAuth.get("apiKeyConfig"));
            }
            if (declaredAuth.has("oauth2Config")) {
                authEntry.set("oauth2Config", declaredAuth.get("oauth2Config"));
            }
            if (authEntry.size() > 1) {
                result.put("auth", objectMapper.createArrayNode().add(authEntry));
            }
        }
        result.put("categoryName", apiResponse.categoryName());
        if (entity.getIconUrl() != null) result.put("iconUrl", entity.getIconUrl());

        // V83: API-level metadata
        if (entity.getApiVersion() != null) result.put("apiVersion", entity.getApiVersion());
        if (entity.getDocumentation() != null) result.put("documentation", entity.getDocumentation());
        if (entity.getRateLimits() != null) {
            try {
                result.put("rateLimits", objectMapper.readTree(entity.getRateLimits()));
            } catch (Exception e) {
                log.warn("Malformed rateLimits JSON for API {}, skipping", apiId);
            }
        }

        // Build tool entity lookup for pagination/nextHint overlay
        List<ApiToolEntity> toolEntities = apiToolRepository.findByApiIdAndIsActiveTrue(id);

        // Map tools to endpoint definitions the frontend can use to pre-populate the form
        List<Map<String, Object>> endpoints = new ArrayList<>();
        if (apiResponse.tools() != null) {
            for (var tool : apiResponse.tools()) {
                Map<String, Object> ep = new LinkedHashMap<>();
                ep.put("name", tool.name());
                ep.put("endpoint", tool.endpoint());
                ep.put("method", tool.method());
                ep.put("description", tool.description());

                // Two different things are stored as header parameters and they must come back
                // as the two different things they were declared as, or the obvious
                // read-modify-write round trip (get_custom_api_details then update_api, which is
                // exactly what the Settings dialog does) silently rewrites the endpoint:
                //
                //   hidden  -> a CONSTANT from the `headers` map. Goes back into `headers`.
                //   visible -> a caller-filled param declared `in: "header"`. Goes back into
                //              `params`, now that ALLOWED_PARAM_IN accepts it.
                //
                // Projecting both as constants lost every visible header param that had no
                // default and froze every one that did. The discriminator is is_hidden, which
                // lives on the parameter ENTITY and not on the DTO, so the entities are resolved
                // BEFORE the loop rather than overlaid after it.
                // Resolved by ID, not by matching derived tool slugs. findMatchingTool is an
                // endsWith heuristic that misses whenever the importer appended a uniqueness
                // suffix, or when one endpoint name is a suffix of another. That used to cost a
                // `hidden` badge; it now decides whether a constant header comes back as a
                // constant or as a caller-settable parameter, so a miss would turn a fixed
                // header into an input an agent can overwrite. The id is right there.
                ApiToolEntity matchedToolEntity = tool.id() != null
                        ? toolEntities.stream().filter(t -> tool.id().equals(t.getId())).findFirst().orElse(null)
                        : null;
                List<ApiToolParameterEntity> paramEntities = tool.id() != null
                        ? apiToolParameterRepository.findByApiToolId(tool.id())
                        : List.of();
                Set<String> hiddenParamNames = new HashSet<>();
                for (ApiToolParameterEntity pe : paramEntities) {
                    if (Boolean.TRUE.equals(pe.getIsHidden()) && pe.getName() != null) {
                        hiddenParamNames.add(pe.getName());
                    }
                }

                List<Map<String, Object>> params = new ArrayList<>();
                Map<String, Object> staticHeaders = new LinkedHashMap<>();
                if (tool.parameters() != null) {
                    for (var p : tool.parameters()) {
                        String in = p.parameterType() != null ? p.parameterType() : "query";
                        boolean hidden = p.name() != null && hiddenParamNames.contains(p.name());
                        // A hidden header with a CONSTANT belongs in the `headers` map, which is
                        // a name-to-value map and therefore cannot hold one with no value. Such a
                        // param comes back as a param carrying hidden:true instead: dropping it
                        // from both, which is what "continue" did unconditionally, deleted a
                        // declared input on the next save. The form can now create exactly that
                        // (in='header' plus the hidden checkbox), so the gap was reachable.
                        if ("header".equalsIgnoreCase(in) && hidden && p.defaultValue() != null) {
                            if (p.name() != null) {
                                staticHeaders.put(p.name(), p.defaultValue());
                            }
                            continue;
                        }
                        Map<String, Object> param = new LinkedHashMap<>();
                        param.put("name", p.name());
                        param.put("in", in);
                        param.put("type", p.type() != null ? p.type() : "string");
                        param.put("required", Boolean.TRUE.equals(p.required()));
                        param.put("description", p.description() != null ? p.description() : "");
                        if (p.defaultValue() != null) param.put("default", p.defaultValue());
                        if (p.exampleValue() != null) param.put("example", p.exampleValue());
                        if (hidden) param.put("hidden", true);
                        params.add(param);
                    }
                }
                ep.put("params", params);
                if (!staticHeaders.isEmpty()) {
                    ep.put("headers", staticHeaders);
                }

                // V83: overlay tool-level fields from tool entities
                if (matchedToolEntity != null) {
                    if (matchedToolEntity.getPagination() != null) {
                        try {
                            ep.put("pagination", objectMapper.readTree(matchedToolEntity.getPagination()));
                        } catch (Exception ignored) { /* skip malformed JSON */ }
                    }
                    if (matchedToolEntity.getNextHint() != null) {
                        ep.put("nextHint", matchedToolEntity.getNextHint());
                    }
                    if (matchedToolEntity.getOutputSchema() != null) {
                        try {
                            ep.put("outputSchema", objectMapper.readTree(matchedToolEntity.getOutputSchema()));
                        } catch (Exception ignored) { /* skip malformed JSON */ }
                    }
                    if (matchedToolEntity.getExecutionSpec() != null) {
                        try {
                            JsonNode execSpec = objectMapper.readTree(matchedToolEntity.getExecutionSpec());
                            ep.put("execution", execSpec);
                        } catch (Exception ignored) { /* skip malformed JSON */ }
                    }
                }

                endpoints.add(ep);
            }
        }
        result.put("endpoints", endpoints);
        return result;
    }

    /**
     * List custom APIs for a tenant.
     */
    public List<Map<String, Object>> listCustomApis(String tenantId) {
        return apiService.getCustomApisForTenant(tenantId, TenantResolver.currentRequestOrganizationId());
    }

    /**
     * Delete a custom API. Only the owner can delete their own custom APIs.
     */
    @Transactional
    public void deleteCustomApi(String apiId, String tenantId) {
        UUID id = UUID.fromString(apiId);
        var entity = apiRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("API not found: " + apiId));
        if (!"custom".equals(entity.getSource())) {
            throw new IllegalArgumentException("Only custom APIs can be deleted via this endpoint");
        }
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (!ScopeGuard.isInStrictScope(tenantId, orgId, entity.getCreatedBy(), entity.getOrganizationId())) {
            throw new IllegalArgumentException("You can only delete your own custom APIs");
        }

        cleanupCredentials(entity, tenantId);
        apiService.deleteApi(id);
        log.info("Deleted custom API {} for tenant {}", apiId, tenantId);
    }

    /**
     * Clean up credential template, tool_credentials, and tenant-scoped platform credential.
     *
     * <p>Called from the DELETE path only. Reads {@code entity.getPlatformCredentialName()},
     * which registration now sets from the one canonical slug it also names the credential by, so
     * create and delete cannot disagree about which row to touch.
     *
     * <p>Both deletes hang off ONE ownership answer, and that is the point rather than a tidy-up.
     * The catalog half learned to refuse a key this API does not own; the tenant half did not, and
     * a key is exactly the same string on both sides. So deleting a custom API that squatted a
     * shipped integration's key left the template alone and then deleted a stored secret FOR THAT
     * SHIPPED INTEGRATION - the one credential a re-import cannot put back, because an import
     * rebuilds templates and never a user's key. Measured on production on 2026-09-18: two custom
     * API rows sit on a key a shipped integration also holds, one of them {@code ghost}, so the
     * collision this protects against is not hypothetical. Asking once and gating both deletes on
     * the answer is what keeps the two from drifting apart again.
     *
     * <p>WHOSE secret: the CALLER's. {@code deleteCustomApi} authorises through
     * {@code ScopeGuard.isInStrictScope}, which inside an organization compares organizations and
     * ignores the creator, so an org-mate may delete a row they did not create - and the delete
     * below is sent under the caller's tenant, not the creator's. The ownership question above is
     * asked about the CREATOR, because that is who owns the row. The two identities are
     * deliberately different, and each is right for its own side.
     *
     * <p>That leaves one case OPEN rather than handled, worth naming because the paragraph above
     * reads like closure. When member-B deletes author-A's API, the secret removed is member-B's:
     * {@code deleteCredential} is scoped to {@code (tenant, org)}, so author-A's row survives,
     * orphaned and enabled, even though author-A is the likelier one to have connected the
     * integration. Deleting it instead would mean one member destroying another's secret on the
     * strength of an API delete, which is the same trade this method refuses everywhere else. So
     * it is left, and its owner can remove it through the user-facing delete of their own
     * connection.
     *
     * <p>Not claimed: that every write of this key is now gated. {@code updateCustomApi} discards
     * this method's answer and re-registers, and that re-registration's {@code linkCredentials}
     * upserts {@code catalog.credentials} under whatever key the new definition canonicalises to -
     * the same one when the name is unchanged, a different one on a rename. Gating it would make
     * an API carrying a shipped key un-editable, a regression already fixed once, so it stays.
     *
     * <p>The auth-service HTTP call is best-effort: if it fails, the API delete still proceeds
     * but the tenant's platform credential may remain orphaned in {@code auth.platform_credentials}.
     */
    private void cleanupCredentials(com.apimarketplace.catalog.domain.ApiEntity entity, String tenantId) {
        String credentialName = entity.getPlatformCredentialName();
        if (credentialName == null || credentialName.isBlank()) return;

        if (!cleanupCatalogCredentialRows(entity)) {
            // Self-contained on purpose: this is the only signal that an UNRECOVERABLE delete was
            // skipped, and the line explaining why is a separate event that interleaves with other
            // requests in an aggregator. It says "any secret", not "the secret", because whether a
            // row exists under that name for this caller is not something this service knows.
            log.warn("Custom API {} deleted without touching credential '{}': the key was not "
                            + "confirmed to belong to it, so any secret caller {} stores under that "
                            + "name is kept, and stays live",
                    entity.getId(), credentialName, tenantId);
            return;
        }
        // Best-effort: delete tenant-scoped platform credential in auth-service. Only the DELETE
        // path does this - an update must leave the author's key in place.
        try {
            credentialClient.deleteTenantPlatformCredential(credentialName, tenantId);
        } catch (Exception e) {
            log.warn("Failed to delete tenant platform credential '{}': {}", credentialName, e.getMessage());
        }
    }

    /**
     * Delete the catalog-side credential template and its tool links, and answer whether this API
     * was entitled to them. The update path calls it for the delete (it rebuilds both rows
     * immediately and touches no secret); the delete path calls it for the ANSWER as much as the
     * delete, because that answer is what decides the tenant's stored secret one frame above.
     *
     * <p><b>Three known limits, none closed here.</b>
     *
     * <p>The first is the owner's own second API. The question is "does anyone ELSE hold this
     * key", and one owner's SECOND API is not anyone else. Two custom APIs of the same owner can
     * end up on one key without either declaring an {@code iconSlug} ("Weather" and "Weather API"
     * both canonicalise to {@code weather}, and the table is unique on
     * {@code (created_by, api_name)}, not on the slug), and deleting either then removes the
     * template and the secret the survivor still needs. Closing it means asking the question with
     * the row being deleted EXCLUDED, which is a different query from the one registration shares,
     * and registration is where an over-strict answer makes an API permanently un-editable - twice
     * already. No production install has such a pair today.
     *
     * <p>The second is that the collision can still be CREATED. Registration only asks about a
     * taken key when the API needs a credential, so an {@code authType: "none"} custom API claims
     * a shipped key today with no refusal at all. Nothing is destroyed when it does (no template
     * is written for it either) and deleting it is exactly what this method refuses, but the
     * population this guard protects is open, not closed.
     *
     * <p>The third is inherited from the predicate and now reaches the secret too. A NATIVE
     * template ({@code imap}, {@code smtp}) has no {@code catalog.apis} row, so the shipped clause
     * cannot see it, and once its {@code catalog.credentials} row has already been destroyed the
     * ownership clause has nothing left to read either. In that state the answer is "yours", and
     * the caller's stored password for it goes with the delete. This is not new here - the
     * previous code deleted that secret unconditionally - but the limit is now part of what this
     * boolean decides, so it belongs in the same list. Production carries both native templates
     * intact, so the state does not exist today.
     *
     * @return true when this API is entitled to the key, so the template delete was issued (which
     *         is not a promise that a row existed to delete); false when it is not entitled, when
     *         ownership could not be established, or when it carries no key at all - in every one
     *         of those cases nothing keyed by that name may be removed.
     */
    private boolean cleanupCatalogCredentialRows(com.apimarketplace.catalog.domain.ApiEntity entity) {
        String credentialName = entity.getPlatformCredentialName();
        if (credentialName == null || credentialName.isBlank()) return false;
        // Never remove a template this API does not own. Do NOT read that as "collisions are
        // legacy": registration refuses a taken name only when the API needs a credential (the
        // check sits behind `if (needsCredential)`), so an authType-none custom API takes a
        // shipped key on a CURRENT build with no refusal, which is the shape production carries.
        // Deleting such a row's key would take an installation-wide template (imap, smtp) or
        // another integration's credential with it.
        //
        // Failing CLOSED here leaves a row behind, which is recoverable; the alternative is not.
        // Be clear about what is left behind though: the kept secret is keyed by
        // (integration_name, tenant_id, organization_id, variant) and nothing on it records WHICH
        // api it was filled in for, so once the squatting API is gone the SHIPPED integration of
        // that name resolves the same row for that tenant and sends the key to a different
        // provider. It stays ENABLED, the only other flag getPlatformAccessToken filters on. A
        // live stale key still beats a deletion nobody can undo, but it is a stale key, not an
        // inert row - and not a dead end either: DELETE /api/platform-credentials/my/{name} lets
        // the owner remove their own connection, more completely than this path would have, since
        // it also runs the dependent-token revocation the internal delete skips.
        if (isCredentialKeyOwnedByAnother(credentialName, entity.getCreatedBy())) {
            // "was not confirmed to belong": reached both when the key demonstrably belongs to
            // someone else and when the lookup failed and fell back to refusing. The message used
            // to claim the first for both.
            log.warn("Credential '{}' was not confirmed to belong to this custom API - leaving the template in place",
                    credentialName);
            return false;
        }
        catalogSeedCredentialService.deleteCredentialByName(credentialName);
        return true;
    }

    /**
     * True when a credential template of this name exists and this API's owner does not own it.
     *
     * <p>This decides TWO deletes: the catalog template and, through it, the author's stored
     * secret. So the owner has to be part of the question. Asking only whether
     * "some custom API" owned the key answered yes for the very API being deleted, which is how a
     * shared template was removed in production: a custom API declaring {@code iconSlug: "ghost"}
     * took the key of the built-in Ghost integration (the key can be DECLARED, it is only derived
     * from apiName when none is given), the check saw a custom owner and allowed the delete, and
     * the built-in Ghost
     * integration lost its credential row and all 41 of its {@code tool_credentials} links for
     * every tenant on the installation. The query now also refuses on a SHIPPED api row carrying
     * the key, which is what survives that deletion and is therefore the only reliable witness.
     */
    private boolean isCredentialKeyOwnedByAnother(String credentialName, String ownerId) {
        try {
            return apiRepository.existsSharedIntegrationWithCredentialKey(credentialName, ownerId);
        } catch (Exception e) {
            // Cannot tell: keep both rows. A template left behind is recoverable and a kept
            // secret is the caller's own; removing a shared template breaks an integration for
            // every tenant on the installation, and a deleted secret cannot be rebuilt at all.
            log.warn("Could not check ownership of credential '{}', leaving it in place: {}",
                    credentialName, e.getMessage());
            return true;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POST-PROCESSING: metadata, synthesis, hints, fixtures
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Post-process API-level metadata fields (apiVersion, documentation, rateLimits)
     * that bypass the ApiConfigurationRequest/ApiSubmissionOrchestrator flow.
     */
    private void postProcessApiMetadata(JsonNode apiJson, UUID apiId) {
        apiRepository.findById(apiId).ifPresent(entity -> {
            boolean updated = false;

            String apiVersion = apiJson.path("apiVersion").asText(null);
            if (apiVersion != null && !apiVersion.isBlank()) {
                entity.setApiVersion(apiVersion);
                updated = true;
            }
            String documentation = apiJson.path("documentation").asText(null);
            if (documentation != null && !documentation.isBlank()) {
                if (documentation.length() > 1000) {
                    documentation = documentation.substring(0, 1000);
                }
                entity.setDocumentation(documentation);
                updated = true;
            }
            JsonNode rateLimitsNode = apiJson.path("rateLimits");
            if (!rateLimitsNode.isMissingNode() && rateLimitsNode.isObject()) {
                entity.setRateLimits(rateLimitsNode.toString());
                updated = true;
            }

            if (updated) {
                apiRepository.save(entity);
                log.info("Post-processed API metadata for {}", apiId);
            }
        });
    }

    /**
     * Persist synthesis (lexical search index) and nextHint for each tool endpoint.
     * These fields are NOT part of the McpToolDto flow -- they need a second pass
     * after the API+tools are created.
     */
    private void postProcessSynthesisAndHints(JsonNode apiJson, UUID apiId, List<ApiToolEntity> savedTools) {
        JsonNode endpoints = apiJson.path("endpoints");
        if (!endpoints.isArray()) return;

        String providerName = apiJson.path("apiName").asText("").toLowerCase().replaceAll("[^a-z0-9]", "_");

        for (JsonNode ep : endpoints) {
            String epName = ep.path("name").asText(ep.path("toolName").asText(""));
            if (epName.isBlank()) continue;

            // Find the matching saved tool by tool_slug
            ApiToolEntity matchedTool = findMatchingTool(savedTools, epName);
            if (matchedTool == null) continue;

            // Persist synthesis to lexical search index
            persistSynthesis(ep, apiJson, matchedTool, providerName, epName);

            // Persist nextHint
            persistNextHint(ep, matchedTool, epName);
        }
    }

    private void persistSynthesis(JsonNode ep, JsonNode apiJson, ApiToolEntity matchedTool,
                                   String providerName, String epName) {
        JsonNode synthesis = ep.path("synthesis");
        if (synthesis.isMissingNode() || !synthesis.isObject()) return;

        try {
            String resource = synthesis.path("resource").asText(epName);
            String action = synthesis.path("action").asText("execute");
            String summary = synthesis.path("summary").asText(ep.path("description").asText(""));
            String summaryExtended = synthesis.path("summaryExtended").asText("");

            List<String> keywordsPrimary = jsonArrayToList(synthesis.path("keywordsPrimary"));
            List<String> keywordsSecondary = jsonArrayToList(synthesis.path("keywordsSecondary"));

            // Build param lists from endpoint params
            List<String> paramsRequired = new ArrayList<>();
            List<String> paramsOptional = new ArrayList<>();
            List<String> paramExamples = new ArrayList<>();
            JsonNode params = ep.path("params");
            if (params.isArray()) {
                for (JsonNode p : params) {
                    String pName = p.path("name").asText("");
                    if (p.path("required").asBoolean(false)) {
                        paramsRequired.add(pName);
                    } else {
                        paramsOptional.add(pName);
                    }
                    String example = p.path("example").asText(null);
                    if (example != null) {
                        paramExamples.add(pName + "=" + example);
                    }
                }
            }

            String toolCategory = ep.path("toolCategory").asText(null);
            String categoryForIndex = toolCategory != null ? toolCategory
                    : apiJson.path("apiCategory").asText("Custom APIs");

            lexicalIndexSyncService.sync(matchedTool.getId(), LexicalIndexSyncService.SyncData.builder()
                    .toolName(epName)
                    .provider(providerName)
                    .resource(resource)
                    .action(action)
                    .endpoint(matchedTool.getEndpoint())
                    .paramsRequired(paramsRequired)
                    .paramsOptional(paramsOptional)
                    .paramExamples(paramExamples)
                    .summary(summary)
                    .summaryExtended(summaryExtended)
                    .keywords(String.join(", ", keywordsPrimary))
                    .keywordsPrimary(keywordsPrimary)
                    .keywordsSynonyms(keywordsSecondary)
                    .keywordsParams(List.of())
                    .useCases(List.of())
                    .category(categoryForIndex)
                    .subcategory(apiJson.path("apiName").asText(""))
                    .build());
            log.info("Persisted synthesis for tool {} ({})", matchedTool.getId(), epName);
        } catch (Exception e) {
            log.warn("Failed to persist synthesis for tool {}: {}", epName, e.getMessage());
        }
    }

    private void persistNextHint(JsonNode ep, ApiToolEntity matchedTool, String epName) {
        String nextHint = ep.path("nextHint").asText(null);
        if (nextHint == null || nextHint.isBlank()) return;

        try {
            // Remove existing hints for this tool to prevent duplicates on re-registration
            List<ToolNextHintEntity> existing = toolNextHintRepository.findByApiToolId(matchedTool.getId());
            if (!existing.isEmpty()) {
                toolNextHintRepository.deleteAll(existing);
            }

            ToolNextHintEntity hint = new ToolNextHintEntity();
            hint.setApiToolId(matchedTool.getId());
            hint.setHint(nextHint);
            hint.setPriority(0);
            hint.setIsActive(true);
            hint.setCreatedAt(System.currentTimeMillis());
            hint.setUpdatedAt(System.currentTimeMillis());
            toolNextHintRepository.save(hint);
            log.info("Persisted nextHint for tool {} ({})", matchedTool.getId(), epName);
        } catch (Exception e) {
            log.warn("Failed to persist nextHint for tool {}: {}", epName, e.getMessage());
        }
    }

    /**
     * Persist API fixtures as example responses via {@link ToolResponseService}.
     * Each fixture becomes a {@code tool_responses} row linked to the matching tool.
     */
    private void postProcessFixtures(JsonNode apiJson, UUID apiId, List<ApiToolEntity> savedTools) {
        JsonNode fixtures = apiJson.path("apiFixtures");
        if (!fixtures.isArray() || fixtures.isEmpty()) return;

        for (JsonNode fixture : fixtures) {
            String endpointName = fixture.path("endpointName").asText("");
            if (endpointName.isBlank()) continue;

            ApiToolEntity matchedTool = findMatchingTool(savedTools, endpointName);
            if (matchedTool == null) {
                log.debug("No matching tool found for fixture endpoint: {}", endpointName);
                continue;
            }

            try {
                JsonNode response = fixture.path("response");
                if (response.isMissingNode() || response.isNull()) continue;

                String exampleJson = objectMapper.writeValueAsString(response);

                ToolResponseDto dto = new ToolResponseDto();
                dto.setToolId(matchedTool.getId());
                dto.setName("fixture_" + endpointName);
                dto.setDescription("Fixture example response for " + endpointName);
                dto.setExample(exampleJson);
                dto.setIsDefault(true);
                dto.setIsActive(true);
                dto.setStatusCode(200);

                toolResponseService.createResponse(dto, null);
                log.info("Persisted fixture for tool {} ({})", matchedTool.getId(), endpointName);
            } catch (Exception e) {
                log.warn("Failed to persist fixture for tool {}: {}", endpointName, e.getMessage());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TOOL BUILDING
    // ═══════════════════════════════════════════════════════════════════════════

    private List<McpToolDto> buildTools(JsonNode apiJson, String apiName) {
        String authType = CredentialTypeNormalizer.normalize(resolveAuthType(apiJson));
        CatalogSeedCredentialService.ApiKeyConfig apiKeyConfig = resolveApiKeyConfig(apiJson);
        List<McpToolDto> tools = new ArrayList<>();
        JsonNode endpoints = apiJson.path("endpoints");

        if (!endpoints.isArray() || endpoints.isEmpty()) {
            return tools;
        }

        // Build a shared runtime_metadata node at API level so every tool on this API
        // has the same {type, baseUrl} shape imported APIs have. Parity with
        // ApiMigrationImporter.buildTool (see backend/catalog-service-import ~line 726).
        String apiBaseUrl = apiJson.path("baseUrl").asText("");
        ObjectNode runtimeMetadata = objectMapper.createObjectNode();
        runtimeMetadata.put("type", "http");
        if (!apiBaseUrl.isEmpty()) {
            runtimeMetadata.put("baseUrl", apiBaseUrl);
        }

        for (JsonNode ep : endpoints) {
            String name = ep.path("name").asText(ep.path("toolName").asText(""));
            if (name.isBlank()) continue;

            String epDescription = ep.path("description").asText(name);
            String endpoint = ep.path("endpoint").asText(ep.path("path").asText(""));

            // Validate against help-declared contracts so silent drops don't mislead the agent.
            String method = validateHttpMethod(ep.path("method").asText("GET"), name);
            validateParams(ep.path("params"), name);

            // Build parameters (supports both "in" and "location" alias)
            List<PathParameterDto> pathParams = buildPathParams(ep);
            List<QueryParameterDto> queryParams = buildQueryParams(ep);
            List<BodyParamDto> bodyParams = buildBodyParams(ep);
            List<HeaderDto> headers = buildHeaders(ep, apiJson, authType, apiKeyConfig);

            // Extract execution spec and output schema
            JsonNode executionSpec = ep.has("execution") ? ep.get("execution") : null;
            JsonNode outputSchema = ep.has("outputSchema") ? ep.get("outputSchema") : null;

            // outputSchema is required - it drives agent workflow hints and variable picker
            if (outputSchema == null || !outputSchema.isArray() || outputSchema.isEmpty()) {
                throw new IllegalArgumentException(
                        "Endpoint '" + name + "' is missing a required outputSchema. " +
                        "Provide a JSON array of {key, type, description} entries describing the response fields.");
            }
            validateOutputSchema(outputSchema, name);

            // Default the execution spec to {"mode":"sync"} so custom tools get the typed
            // execution path (V52) instead of silently falling back to the legacy untyped path
            // when the user omits the block. Mirrors ApiMigrationImporter's default behaviour.
            if (executionSpec == null || !executionSpec.isObject()) {
                executionSpec = objectMapper.createObjectNode().put("mode", "sync");
            }
            String executionMode = executionSpec.path("mode").asText("sync");

            // V83: new fields for McpToolDto pass-through
            JsonNode synthesisNode = ep.has("synthesis") ? ep.get("synthesis") : null;
            JsonNode paginationNode = ep.has("pagination") ? ep.get("pagination") : null;
            String nextHintStr = ep.path("nextHint").asText(null);
            // Use endpoint-level toolCategory if provided, else default to the API name
            // (mirrors the importer which uses synthesis.resource as toolCategory)
            String rawToolCategory = ep.path("toolCategory").asText(apiName);
            // McpToolDto.toolCategory is @Size(max=50)
            String toolCategory = rawToolCategory.length() > 50 ? rawToolCategory.substring(0, 50) : rawToolCategory;

            tools.add(new McpToolDto(
                    ep.path("toolId").asText(null),
                    name,
                    epDescription,
                    endpoint,
                    method,
                    "HTTP",
                    runtimeMetadata,
                    toolCategory,
                    epDescription, // toolCategoryDescription
                    null, // toolCategoryIconUrl
                    null, // toolNameId
                    true, // isCustomCategory
                    true, // isCustomToolName
                    headers,
                    pathParams,
                    queryParams,
                    bodyParams,
                    Map.of(), // defaultHeaders
                    null, // response
                    null, null, null, null, null, // sql/amqp/kafka/mqtt/redis
                    executionSpec,
                    outputSchema,
                    executionMode,
                    synthesisNode,
                    paginationNode,
                    nextHintStr,
                    null  // V166: requiredScopes - custom API registration path
            ));
        }

        return tools;
    }

    private List<PathParameterDto> buildPathParams(JsonNode ep) {
        List<PathParameterDto> params = new ArrayList<>();
        JsonNode paramsNode = ep.path("params");
        if (!paramsNode.isArray()) return params;

        for (JsonNode p : paramsNode) {
            String in = p.path("in").asText(p.path("location").asText(""));
            if ("path".equalsIgnoreCase(in)) {
                boolean hidden = p.path("hidden").asBoolean(false);
                ObjectNode extras = hidden ? objectMapper.createObjectNode().put("hidden", true) : null;

                // Handle example for any value type
                String example = extractExample(p);

                params.add(new PathParameterDto(
                        p.path("name").asText(),
                        p.path("type").asText("string"),
                        p.path("required").asBoolean(true),
                        p.path("description").asText(""),
                        example,
                        null,
                        extractParamDefaultValue(p),
                        extractParamAllowedValues(p),
                        extras
                ));
            }
        }
        return params;
    }

    private List<QueryParameterDto> buildQueryParams(JsonNode ep) {
        List<QueryParameterDto> params = new ArrayList<>();
        JsonNode paramsNode = ep.path("params");
        if (!paramsNode.isArray()) return params;

        for (JsonNode p : paramsNode) {
            String in = p.path("in").asText(p.path("location").asText("query"));
            if ("query".equalsIgnoreCase(in) || in.isBlank()) {
                boolean hidden = p.path("hidden").asBoolean(false);
                ObjectNode extras = hidden ? objectMapper.createObjectNode().put("hidden", true) : null;

                // Handle default value (can be any type)
                String defaultValue = null;
                JsonNode defaultNode = p.path("default");
                if (!defaultNode.isMissingNode() && !defaultNode.isNull()) {
                    defaultValue = defaultNode.isTextual() ? defaultNode.asText() : defaultNode.toString();
                }

                String example = extractExample(p);

                params.add(new QueryParameterDto(
                        p.path("name").asText(),
                        p.path("type").asText("string"),
                        p.path("required").asBoolean(false),
                        p.path("description").asText(""),
                        example,
                        defaultValue,
                        extractParamAllowedValues(p),
                        extras
                ));
            }
        }
        return params;
    }

    private List<BodyParamDto> buildBodyParams(JsonNode ep) {
        List<BodyParamDto> params = new ArrayList<>();
        JsonNode paramsNode = ep.path("params");
        if (!paramsNode.isArray()) return params;

        for (JsonNode p : paramsNode) {
            String in = p.path("in").asText(p.path("location").asText(""));
            if ("body".equalsIgnoreCase(in)) {
                boolean hidden = p.path("hidden").asBoolean(false);
                ObjectNode extras = hidden ? objectMapper.createObjectNode().put("hidden", true) : null;

                params.add(new BodyParamDto(
                        p.path("name").asText(),
                        null,
                        p.path("type").asText("string"),
                        p.path("required").asBoolean(false),
                        p.path("description").asText(""),
                        null,
                        extractParamDefaultValue(p),
                        extractParamAllowedValues(p),
                        extras
                ));
            }
        }
        return params;
    }

    /**
     * Transport/computed headers the HTTP client owns - never inject these from a static
     * catalog header. Mirrors {@code ApiMigrationImporter.STATIC_HEADER_SKIP} so the custom
     * and seed routes agree on what a caller may not set.
     */
    private static final Set<String> STATIC_HEADER_SKIP = Set.of(
            "content-type", "content-length", "host", "connection",
            "transfer-encoding", "accept-encoding");

    /**
     * Reads the primary auth variant's type. {@code auth} is an array of variants (see
     * api-migrations/SCHEMA.md); the first entry is the primary one, and custom APIs expose only
     * that one. The legacy object form is tolerated for in-flight payloads.
     */
    static String resolveAuthType(JsonNode apiJson) {
        JsonNode authArray = apiJson.path("auth");
        JsonNode primaryAuth = (authArray.isArray() && authArray.size() > 0)
                ? authArray.get(0)
                : apiJson.path("auth");
        return primaryAuth.path("type").asText(apiJson.path("authType").asText("none"));
    }

    /**
     * The credential name this API's tools will require, or {@code null} when it needs none.
     *
     * <p>THE one derivation. It is deliberately public and computed from the submitted JSON, so
     * the caller reporting the result and the code linking the credential quote the same string.
     * Deriving this name twice from two different inputs is what let apis.icon_slug drift away
     * from the credential name; see the note in registerCustomApi for what that did and, just as
     * importantly, did not break.
     */
    public static String credentialNameFor(JsonNode apiJson, String apiName) {
        if ("none".equals(CredentialTypeNormalizer.normalize(resolveAuthType(apiJson)))) {
            return null;
        }
        String slug = deriveCanonicalSlug(apiJson, apiName);
        return slug.isBlank() ? null : slug;
    }

    /**
     * THE derivation of the slug that names both the API's icon and its credential. Every caller
     * routes through here: two call sites computing the same expression is how the original
     * defect was born, and leaving a second copy would invite it back even while both agree.
     */
    static String deriveCanonicalSlug(JsonNode apiJson, String apiName) {
        return com.apimarketplace.catalog.util.IconSlugNormalizer
                .deriveIconSlug(apiName, apiJson.path("iconSlug").asText(null));
    }

    /**
     * Refuse an auth type this path cannot honour, naming the accepted spellings.
     *
     * <p>Every OTHER declared value is validated ({@code method}, a parameter's {@code in}, an
     * output type) with a message listing what is allowed. This one was not, and it is the single
     * field where a wrong value routes a SECRET somewhere else: an unrecognised type fell through
     * to the X-API-Key default, so an API declaring {@code bearer_token} - the spelling the
     * catalog itself reports for every built-in API, and the one in every seed file - registered
     * happily and then sent its token in a header the provider ignores. The provider answers 401,
     * nothing on our side mentions auth, and the author has no way to see it. Failing loudly here
     * costs one clear error; the silent default cost hours.
     */
    static void validateAuthType(String authType) {
        String canonical = CredentialTypeNormalizer.normalize(authType);
        if (!ALLOWED_AUTH_TYPES.contains(canonical)) {
            throw new IllegalArgumentException(
                    "Unsupported authType '" + authType + "'. Allowed: none, apikey (alias api_key), "
                            + "bearer (alias bearer_token), basic (alias basic_auth), oauth2. "
                            + "To place the credential somewhere other than the default header, add "
                            + "auth[0].apiKeyConfig {location, headerName, queryParamName, keyName, prefix}. "
                            + "For oauth2, add auth[0].oauth2Config {authorizationUrl, tokenUrl, scopes}.");
        }
    }

    /**
     * Refuse a credential key that a catalogue integration already owns.
     *
     * <p>{@code catalog.credentials.credential_name} is UNIQUE and has NO tenant column, and this
     * path upserts into it. So registering a custom API under the key of a shared integration does
     * not create a private row, it OVERWRITES the shared one for the whole installation - and
     * delete_api then removes the tool links of every tenant using it. One person naming their API
     * "Stripe" could break Stripe for everyone on the deployment.
     *
     * <p>The table cannot be made tenant-scoped from here; refusing the collision can. The check is
     * NOT narrow, and that is deliberate: it refuses a key held by a shipped integration AND one
     * held by anybody else's custom API, because those two collide identically. Only the caller's
     * own rows exempt them, and {@link #updateCustomApi} additionally carries the key its row
     * already held so that re-creating it is not read as a new claim.
     *
     * <p>Two consequences worth stating rather than discovering:
     *
     * <p>ORG-MATES ARE STRANGERS FOR THIS KEY. Ownership here is {@code created_by} alone, while
     * every other ownership decision in this class goes through {@code ScopeGuard}, where an
     * active organization wins over the creator. So two members of one org cannot both register
     * an API deriving the same key, even though each can see the other's. That is the safe
     * direction (the alternative is one silently overwriting the other's credential), but it is a
     * deliberate inconsistency, not an oversight. Widening it means adding the organization to
     * the predicate, which {@code catalog.apis} carries.
     *
     * <p>A NATIVE TEMPLATE THAT SOMEBODY ALREADY SQUATS IS STILL NOT PROTECTED ON DELETE.
     * {@code imap} and {@code smtp} have no {@code catalog.apis} row at all, so the shipped half
     * cannot see them, and if a custom API already holds the key its owner is exempted and the
     * delete proceeds. Nobody squats either key on production today (zero rows), so this is a gap
     * and not an incident. Closing it needs a different discriminator than ownership: a row this
     * path created has EMPTY metadata, a native template carries {@code category}/{@code
     * provider}, and a seeded one carries {@code source: api-migration}.
     */
    private void rejectSlugOwnedByASharedIntegration(String canonicalSlug, String apiName,
                                                     String tenantId, String keyAlreadyHeld) {
        if (canonicalSlug == null || canonicalSlug.isBlank()) {
            return;
        }
        if (keyAlreadyHeld != null && keyAlreadyHeld.equalsIgnoreCase(canonicalSlug)) {
            // Re-registering a key this caller already holds on the row being replaced.
            return;
        }
        if (tenantId == null || tenantId.isBlank()) {
            // Without a caller identity the ownership half cannot say "mine", so it would answer
            // "collision" for every key that has any credential row and refuse a legitimate
            // registration. This site's posture is fail OPEN, so ask only the half that does not
            // need an identity: whether a SHIPPED integration holds the key.
            rejectIfShippedIntegrationHoldsKey(canonicalSlug, apiName);
            return;
        }
        boolean ownedByCatalogue;
        try {
            ownedByCatalogue = apiRepository.existsSharedIntegrationWithCredentialKey(canonicalSlug, tenantId);
        } catch (Exception e) {
            // Fail OPEN: a lookup that cannot run must not block a legitimate registration. The
            // collision it guards against is rare and recoverable; refusing every registration
            // because one query failed is neither.
            log.warn("Could not check whether credential key '{}' is owned by a catalogue integration: {}",
                    canonicalSlug, e.getMessage());
            return;
        }
        if (ownedByCatalogue) {
            throw new CollidingCredentialKeyException(canonicalSlug, apiName);
        }
    }

    /**
     * The identity-free half of the collision check, used when there is no caller to scope the
     * ownership exemption to.
     */
    private void rejectIfShippedIntegrationHoldsKey(String canonicalSlug, String apiName) {
        boolean shipped;
        try {
            shipped = apiRepository.existsShippedIntegrationWithCredentialKey(canonicalSlug);
        } catch (Exception e) {
            log.warn("Could not check whether credential key '{}' is held by a shipped integration: {}",
                    canonicalSlug, e.getMessage());
            return;
        }
        if (shipped) {
            throw new CollidingCredentialKeyException(canonicalSlug, apiName);
        }
    }

    /**
     * One refusal text for both halves of the check, because the caller cannot tell them apart and
     * the remedy is the same either way.
     *
     * <p>It does NOT say "a built-in integration": the check also fires on a credential row held by
     * another user's custom API, and {@code catalog.apis} is unique on {@code (created_by,
     * api_name)}, so two users may legitimately both want the name "Acme CRM". Naming a built-in
     * integration there would be a statement the agent has no action available to verify, and
     * which is simply false in that case.
     */
    private static final class CollidingCredentialKeyException extends IllegalArgumentException {
        CollidingCredentialKeyException(String key, String apiName) {
            super("The credential key '" + key + "' derived from apiName '" + apiName + "' is already "
                    + "in use on this installation, and registering over it would change the credential "
                    + "of whatever holds it. Choose a different apiName, or set iconSlug to a distinct "
                    + "value.");
        }
    }

    /**
     * Read and validate {@code auth[0].oauth2Config}, the block that makes an OAuth2 connection
     * possible at all.
     *
     * <p>OAuth2 was previously accepted, offered in the help and in the settings form, and could
     * never complete: the provider's endpoints are resolved from the credential template's
     * {@code metadata.oauth2Config}, and this path wrote no metadata at all. So the connection
     * form asked for a client id and secret that nothing could exchange for a token. The engine
     * itself was never the problem - 176 catalogue APIs authenticate this way, 163 of them with a
     * guided "create your own app" disclosure - the block simply never reached it.
     *
     * <p>The block is now carried through verbatim, in the same shape the imported catalogue uses,
     * and the two endpoints it cannot work without are REQUIRED. An oauth2 API declaring neither
     * is refused here rather than registering into a dead end, which is the whole point: the
     * option is available exactly when it can succeed.
     *
     * @return the config to persist, or {@code null} when the auth type is not oauth2
     */
    static JsonNode resolveOAuth2Config(JsonNode apiJson, String canonicalAuthType) {
        if (!"oauth2".equals(canonicalAuthType)) {
            return null;
        }
        JsonNode authArray = apiJson.path("auth");
        JsonNode primaryAuth = (authArray.isArray() && authArray.size() > 0)
                ? authArray.get(0)
                : apiJson.path("auth");
        JsonNode cfg = primaryAuth.path("oauth2Config");
        if (!cfg.isObject()) {
            throw new IllegalArgumentException(
                    "authType 'oauth2' requires auth[0].oauth2Config {authorizationUrl, tokenUrl, scopes}. "
                            + "Without the provider's endpoints the connection can never complete. If the "
                            + "provider issues a long-lived token instead, use authType 'bearer' or 'apikey'.");
        }
        String authorizationUrl = requireOAuthUrl(cfg, "authorizationUrl");
        String tokenUrl = requireOAuthUrl(cfg, "tokenUrl");
        // refreshUrl is optional and defaults to tokenUrl at runtime, but validate it when present
        // so a typo fails here rather than at the first token refresh, hours later.
        if (cfg.hasNonNull("refreshUrl") && !cfg.path("refreshUrl").asText("").isBlank()) {
            requireOAuthUrl(cfg, "refreshUrl");
        }
        log.debug("Custom API declares oauth2 against {} / {}", authorizationUrl, tokenUrl);
        return cfg;
    }

    /**
     * An OAuth endpoint must be a well-formed, non-internal URL. It is validated with the same
     * check the baseUrl gets, because a credential is about to be sent to it: a value pointing at
     * a private address would turn the consent flow into a request against our own network.
     */
    private static String requireOAuthUrl(JsonNode cfg, String field) {
        String value = cfg.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "auth[0].oauth2Config." + field + " is required for authType 'oauth2'.");
        }
        try {
            UrlSafetyValidator.validateUrlFormat(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "auth[0].oauth2Config." + field + " is not a usable URL: " + e.getMessage(), e);
        }
        return value;
    }

    /**
     * Read the declared credential placement from {@code auth[0].apiKeyConfig}, the same block the
     * seed files use (scripts/api-migrations/SCHEMA.md).
     *
     * <p>Before this, the block was parsed by nobody on this path: a custom API could only ever
     * put its credential in {@code Authorization: Bearer <token>} or {@code X-API-Key: <token>},
     * while the execution engine has long supported any header name, any prefix and query-string
     * injection, and every built-in API declares exactly this block to use them. An API wanting
     * {@code X-Api-Token}, {@code Authorization: Token <key>} or {@code ?api_key=} was simply not
     * registrable, with nothing saying so.
     *
     * @return the declared placement, or {@code null} when nothing usable was declared
     */
    static CatalogSeedCredentialService.ApiKeyConfig resolveApiKeyConfig(JsonNode apiJson) {
        JsonNode authArray = apiJson.path("auth");
        JsonNode primaryAuth = (authArray.isArray() && authArray.size() > 0)
                ? authArray.get(0)
                : apiJson.path("auth");
        JsonNode cfg = primaryAuth.path("apiKeyConfig");
        if (!cfg.isObject()) {
            return null;
        }
        // A declared-but-empty prefix ("send the credential raw") must be distinguishable from an
        // absent one ("let the engine default to Bearer"), so read presence, not emptiness.
        String prefix = cfg.has("prefix") && !cfg.path("prefix").isNull()
                ? cfg.path("prefix").asText("")
                : null;
        var config = new CatalogSeedCredentialService.ApiKeyConfig(
                cfg.path("location").asText(null),
                cfg.path("headerName").asText(null),
                cfg.path("queryParamName").asText(null),
                cfg.path("keyName").asText(null),
                prefix);
        return config.isBlank() ? null : config;
    }

    /**
     * Header names a declared param may never take: the UNION of the names the runtime drops
     * ({@code HttpExecutionService.TRANSPORT_MANAGED_HEADERS}) and the ones the static-header
     * importer skips. Neither list alone is right. The runtime list omits {@code Content-Type} and
     * {@code Accept-Encoding}, which the client sets itself and a param would override; the static
     * list omits {@code Expect}, {@code Upgrade}, {@code TE}, {@code Trailer}, {@code Keep-Alive}
     * and the two {@code Proxy-} headers, which are accepted and then silently discarded.
     */
    static final Set<String> RESERVED_TRANSPORT_HEADERS = Stream.concat(
            HttpExecutionService.TRANSPORT_MANAGED_HEADERS.stream(),
            STATIC_HEADER_SKIP.stream()).collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** Description carried by an auto-supplied header param. Mirrors the seed importer's wording. */
    static final String STATIC_HEADER_DESCRIPTION = "Required request header (auto-supplied).";

    /**
     * The request header the credential injection occupies at runtime, or {@code null} when the
     * credential travels in the query string instead and reserves no header.
     *
     * <p>This used to be a SECOND copy of the switch that decides the injection, and the two
     * drifted: both sent {@code bearer_token} to the {@code X-API-Key} default, so an API using
     * the catalog's own spelling reserved the wrong name and left {@code Authorization}, the
     * header its secret actually goes into, unguarded. It now delegates to the single derivation
     * in {@link CatalogSeedCredentialService}, which is also what emits the metadata, so a change
     * to one cannot leave the other behind.
     */
    static String credentialHeaderName(String authType, CatalogSeedCredentialService.ApiKeyConfig config) {
        return CatalogSeedCredentialService.credentialHeaderName(authType, config);
    }

    /**
     * Collects the static header params for one endpoint: its own {@code headers} map, then the
     * API-level {@code requiredHeaders} map, exactly as {@code ApiMigrationImporter} does.
     *
     * <p>The value must land in {@code defaultValue}: {@code HttpExecutionService.applyHeaderParameters}
     * injects a header from its parameter DEFAULT, and {@code ApiConfigurationConverter} only
     * propagates {@code defaultValue} when it is non-null. The pre-fix code put the value in
     * {@code value} and left {@code defaultValue} null, so every static header a custom API declared
     * was accepted at registration and then silently dropped from the request - an API requiring a
     * constant header (Anthropic's {@code anthropic-version}) was simply unreachable, answering 400
     * with nothing in the catalog to explain it.
     *
     * <p>The credential header is reserved FIRST, so a declared header can never take the name the
     * runtime injects the secret into. This matters beyond the value itself: an emitted header is
     * {@code required}, and {@code applyHeaderParameters} applies a CALLER-supplied value for a
     * declared header param with no collision guard, so a param named after the auth header would
     * invite an agent to overwrite the credential.
     */
    private List<HeaderDto> buildHeaders(JsonNode ep, JsonNode apiJson, String authType,
                                         CatalogSeedCredentialService.ApiKeyConfig apiKeyConfig) {
        List<HeaderDto> headers = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        if (!"none".equals(authType)) {
            String reserved = credentialHeaderName(authType, apiKeyConfig);
            // null = the credential goes in the query string, so it occupies no header and there
            // is nothing to reserve. Reserving a name anyway would block a legitimate static
            // header for no reason.
            if (reserved != null) {
                emitted.add(reserved);
            }
        }
        // Caller-supplied header params come before the static maps: they are the more deliberate
        // declaration, and a name taken by one of them should win over a constant of the same name.
        addDeclaredHeaderParams(headers, emitted, ep.path("params"), ep.path("name").asText("?"));
        addStaticHeaderParams(headers, emitted, ep.path("headers"));
        addStaticHeaderParams(headers, emitted, apiJson.path("requiredHeaders"));
        return headers;
    }

    /**
     * Appends every {@code params[]} entry declaring {@code in: "header"} as a visible, normal
     * header parameter the caller fills in per request.
     *
     * <p>{@code header} used to be rejected outright, while the engine has always applied header
     * parameters (and their declared defaults). So an API needing a per-request header, which is
     * ordinary, could not be described at all. The rejection at least SAID so, which is why this
     * ranked below the silent defects, but the capability was there the whole time.
     *
     * <p>A collision is a hard error rather than a skip. A static header map may quietly drop a
     * duplicate because it is a bag of constants, but a declared parameter is a specific request,
     * and dropping it would hand back a tool missing an input its author asked for. Taking the
     * credential's header is refused outright: {@code applyHeaderParameters} sets a caller value
     * with no collision guard, so such a param is an invitation to overwrite the secret.
     */
    private void addDeclaredHeaderParams(List<HeaderDto> out, Set<String> emitted, JsonNode paramsNode,
                                         String endpointName) {
        if (paramsNode == null || !paramsNode.isArray()) return;

        for (JsonNode p : paramsNode) {
            String in = p.path("in").asText(p.path("location").asText(""));
            if (!"header".equalsIgnoreCase(in)) continue;

            String name = p.path("name").asText("");
            if (name.isBlank()) continue;
            String key = name.toLowerCase(Locale.ROOT);

            // Validated against the UNION of both reserved lists (RESERVED_TRANSPORT_HEADERS).
            // Neither alone is right: the runtime list omits Content-Type and Accept-Encoding,
            // which the client sets itself, while the static-header list omits Expect, Upgrade,
            // TE, Trailer, Keep-Alive and the two Proxy- headers, which the runtime discards. A
            // name on either list is accepted and then thrown away, so both must refuse here.
            if (RESERVED_TRANSPORT_HEADERS.contains(key)) {
                throw new IllegalArgumentException(
                        "Parameter '" + name + "' on endpoint '" + endpointName + "' declares in='header', but "
                                + "'" + name + "' is managed by the HTTP transport and is dropped before the "
                                + "request is sent. Remove it and let the transport set it.");
            }
            if (!emitted.add(key)) {
                throw new IllegalArgumentException(
                        "Parameter '" + name + "' on endpoint '" + endpointName + "' declares in='header', but that "
                                + "header is already taken on this endpoint - by the credential injection for this "
                                + "API's authType, or by another declared header. Rename the parameter, or remove "
                                + "the duplicate.");
            }

            String defaultValue = p.path("default").isValueNode() && !p.path("default").isNull()
                    ? p.path("default").asText() : null;
            // `value` carries the CONSTANT of a static header; a caller-filled param has none.
            // It must be the empty string rather than null: the converter copies it into a map
            // that keeps nulls, and the persist step reads it with JsonNode.asText(), where a
            // NullNode yields the four-character string "null". Every declared header param would
            // otherwise show the agent an example value of "null".
            // `hidden` is honoured here as it is on every other parameter location. It was
            // hardcoded false, so a declared hidden:true was accepted and dropped - and since a
            // hidden header is projected BACK as a constant, clearing the flag silently changed
            // what the round trip returns as well as what the caller sees.
            out.add(new HeaderDto(
                    name,
                    "",
                    p.path("required").asBoolean(false),
                    defaultValue,
                    extractParamAllowedValues(p),
                    null,
                    p.path("description").asText(""),
                    p.path("hidden").asBoolean(false)));
        }
    }

    /**
     * Appends every LITERAL entry of {@code headersNode} as a hidden, required header param.
     *
     * <p>Guards mirror {@code ApiMigrationImporter.addStaticHeaderParams}, ORDER INCLUDED: a value is
     * tested for literalness BEFORE its name is reserved. Reserving first would let a documentation
     * string ("obtain this via /connections") claim the name and block a later, valid entry for the
     * same header - reintroducing the silent drop this whole method exists to remove.
     *
     * <p>A literal is short and unambiguous: non-blank, at most 64 characters, no whitespace, and no
     * {@code &#123;} / {@code &#125;}. Anything else is prose or a runtime template, and sending it
     * verbatim would put a literal {@code &#123;&#123;api_key&#125;&#125;} on the wire.
     */
    private void addStaticHeaderParams(List<HeaderDto> out, Set<String> emitted, JsonNode headersNode) {
        if (headersNode == null || !headersNode.isObject()) return;

        var it = headersNode.fields();
        while (it.hasNext()) {
            var entry = it.next();
            String name = entry.getKey();
            if (name == null || name.isBlank()) continue;

            String key = name.toLowerCase(Locale.ROOT);
            if (STATIC_HEADER_SKIP.contains(key)) {
                log.debug("Static header '{}' skipped (owned by the HTTP transport)", name);
                continue;
            }

            JsonNode valueNode = entry.getValue();
            // NullNode.isValueNode() is true and NullNode.asText() is the STRING "null", so a
            // JSON null would otherwise pass every literal check and put `X-Foo: null` on the wire.
            String value = valueNode != null && valueNode.isValueNode() && !valueNode.isNull()
                    ? valueNode.asText() : null;
            boolean literal = value != null && !value.isBlank()
                    && value.length() <= 64
                    && value.chars().noneMatch(Character::isWhitespace)
                    && value.indexOf('{') < 0 && value.indexOf('}') < 0;
            if (!literal) {
                log.warn("Static header '{}' not registered: its value is not a short literal "
                        + "(no spaces, at most 64 characters, no braces)", name);
                continue;
            }

            if (!emitted.add(key)) {
                log.debug("Static header '{}' not registered: the name is already taken by the credential "
                        + "or by an earlier header", name);
                continue;
            }

            out.add(new HeaderDto(name, value, true, value, null, null, STATIC_HEADER_DESCRIPTION, true));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Extracts the scalar {@code default} value of a param spec for the typed DTO.
     * Returns {@code null} when {@code default} is missing/null/an array (the legacy
     * array-shape is treated as {@code allowedValues}, see {@link #extractParamAllowedValues}).
     */
    private String extractParamDefaultValue(JsonNode p) {
        JsonNode d = p.path("default");
        if (d.isMissingNode() || d.isNull() || d.isArray()) {
            return null;
        }
        return d.isTextual() ? d.asText() : d.toString();
    }

    /**
     * Extracts the closed enum from a param spec, supporting BOTH the explicit
     * {@code allowedValues} field and the legacy {@code default: [array]} fallback
     * (per {@code SCHEMA.md}). Returns {@code null} when neither is set or the
     * result would be empty.
     */
    private List<String> extractParamAllowedValues(JsonNode p) {
        JsonNode allowed = p.path("allowedValues");
        if (allowed.isArray() && allowed.size() > 0) {
            List<String> out = new ArrayList<>(allowed.size());
            allowed.forEach(v -> out.add(v.isTextual() ? v.asText() : v.toString()));
            return out;
        }
        // Legacy fallback: default-as-array means "exhaustive admissible values"
        JsonNode def = p.path("default");
        if (def.isArray() && def.size() > 0) {
            List<String> out = new ArrayList<>(def.size());
            def.forEach(v -> out.add(v.isTextual() ? v.asText() : v.toString()));
            return out;
        }
        return null;
    }

    private static MonetizationConfigDto buildFreeMonetization() {
        return new MonetizationConfigDto(
                "FREEMIUM", null, null,
                999999,     // freeRequestsPerUser - effectively unlimited
                "per-user", // freeRequestsType
                null, null,
                null, null, null, null, null, null,
                null, null, null, null,
                null, null, null, null,
                null, null, null, null,
                null, null, null, null,
                null, null, null, null,
                null, null, null, null
        );
    }

    private String requireField(JsonNode json, String fieldName) {
        JsonNode node = json.path(fieldName);
        if (node.isMissingNode() || node.asText("").isBlank()) {
            throw new IllegalArgumentException("Required field missing: " + fieldName);
        }
        return node.asText();
    }

    /**
     * Walk the Throwable cause chain and return true if any layer is a
     * Spring {@link DuplicateKeyException}. Required because the orchestrator
     * wraps the original {@link DuplicateKeyException} in a generic
     * {@code RuntimeException("Processing error: ...")}, so a simple
     * {@code catch (DuplicateKeyException)} or message substring match misses it.
     */
    static boolean isDuplicateKeyException(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth++ < 12) {
            if (cur instanceof DuplicateKeyException) return true;
            if (cur == cur.getCause()) return false;
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * Validate HTTP method against the whitelist promised in the help text.
     * Case-insensitive but the returned value is the canonical upper-case form.
     */
    static String validateHttpMethod(String rawMethod, String endpointName) {
        String method = rawMethod == null ? "" : rawMethod.trim().toUpperCase();
        if (!ALLOWED_METHODS.contains(method)) {
            throw new IllegalArgumentException(
                    "Endpoint '" + endpointName + "' has an invalid HTTP method '"
                            + rawMethod + "'. Allowed methods: " + String.join(", ", ALLOWED_METHODS) + ".");
        }
        return method;
    }

    /**
     * Validate that every param declares an {@code in} (or {@code location} alias)
     * from the allowed set. Silently dropping unknown values would lose the param
     * with no feedback to the agent.
     */
    static void validateParams(JsonNode paramsNode, String endpointName) {
        if (paramsNode == null || !paramsNode.isArray()) return;
        for (int i = 0; i < paramsNode.size(); i++) {
            JsonNode p = paramsNode.get(i);
            String pName = p.path("name").asText("");
            String label = pName.isBlank() ? ("params[" + i + "]") : pName;

            // description is declared required in help → enforce here
            JsonNode descNode = p.path("description");
            if (descNode.isMissingNode() || descNode.asText("").isBlank()) {
                throw new IllegalArgumentException(
                        "Parameter '" + label + "' on endpoint '" + endpointName
                                + "' is missing a required description. Describe the parameter's purpose for agents.");
            }

            // "in" - accept "location" alias; default to "query" only when absent entirely
            JsonNode inNode = p.path("in");
            JsonNode locNode = p.path("location");
            String in;
            if (!inNode.isMissingNode() && !inNode.asText("").isBlank()) {
                in = inNode.asText().toLowerCase();
            } else if (!locNode.isMissingNode() && !locNode.asText("").isBlank()) {
                in = locNode.asText().toLowerCase();
            } else {
                in = "query"; // default retained for backward compat
            }
            if (!ALLOWED_PARAM_IN.contains(in)) {
                throw new IllegalArgumentException(
                        "Parameter '" + label + "' on endpoint '" + endpointName
                                + "' has invalid 'in' value '" + in + "'. Allowed: "
                                + String.join(", ", ALLOWED_PARAM_IN) + ".");
            }
        }
    }

    /**
     * Recursively validate {@code outputSchema[].type} against the whitelist
     * promised in help. Walks nested {@code children} arrays for object/array types.
     */
    static void validateOutputSchema(JsonNode outputSchema, String endpointName) {
        if (outputSchema == null || !outputSchema.isArray()) return;
        for (int i = 0; i < outputSchema.size(); i++) {
            JsonNode field = outputSchema.get(i);
            String fieldKey = field.path("key").asText("");
            String label = fieldKey.isBlank() ? ("outputSchema[" + i + "]") : fieldKey;

            String rawType = field.path("type").asText("").trim();
            if (rawType.isEmpty()) {
                throw new IllegalArgumentException(
                        "Field '" + label + "' on endpoint '" + endpointName
                                + "' is missing a required 'type'. Allowed: "
                                + String.join(", ", ALLOWED_OUTPUT_TYPES) + ".");
            }
            // Normalize case so "String" / "FILEREF" match the whitelist the same way
            // validateParams normalizes the `in` value. `fileRef` keeps its canonical
            // camelCase form in the whitelist; accept any casing from the LLM.
            String type = "fileref".equals(rawType.toLowerCase()) ? "fileRef" : rawType.toLowerCase();
            if (!ALLOWED_OUTPUT_TYPES.contains(type)) {
                throw new IllegalArgumentException(
                        "Field '" + label + "' on endpoint '" + endpointName
                                + "' has invalid type '" + rawType + "'. Allowed: "
                                + String.join(", ", ALLOWED_OUTPUT_TYPES) + ".");
            }
            JsonNode children = field.path("children");
            if (children.isArray()) {
                validateOutputSchema(children, endpointName);
            }
        }
    }

    /**
     * Find a tool entity matching the endpoint name by tool_slug.
     */
    private ApiToolEntity findMatchingTool(List<ApiToolEntity> tools, String endpointName) {
        // Normalize: lowercase, spaces→underscores, then also prepare a hyphenated variant
        // because tool_slug uses hyphens (e.g. "test-api-list-items" for endpoint "list_items")
        String normalized = endpointName.toLowerCase().replace(" ", "_");
        String hyphenated = normalized.replace("_", "-");
        return tools.stream()
                .filter(t -> {
                    if (t.getToolSlug() == null) return false;
                    String slug = t.getToolSlug().toLowerCase();
                    return slug.endsWith("-" + hyphenated)
                            || slug.endsWith("_" + normalized)
                            || slug.equals(normalized)
                            || slug.equals(hyphenated);
                })
                .findFirst()
                .orElse(null);
    }

    /**
     * Extract example value from a param node, handling text, number, and other value types.
     */
    private String extractExample(JsonNode p) {
        JsonNode exampleNode = p.path("example");
        if (exampleNode.isMissingNode() || exampleNode.isNull()) return null;
        if (exampleNode.isTextual()) return exampleNode.asText();
        if (exampleNode.isValueNode()) return exampleNode.asText();
        // Object/array examples: serialize to JSON string
        return exampleNode.toString();
    }

    /**
     * Convert a JSON array node to a List of strings.
     */
    private List<String> jsonArrayToList(JsonNode arrayNode) {
        List<String> result = new ArrayList<>();
        if (arrayNode != null && arrayNode.isArray()) {
            for (JsonNode item : arrayNode) {
                String text = item.asText("");
                if (!text.isBlank()) result.add(text);
            }
        }
        return result;
    }
}
