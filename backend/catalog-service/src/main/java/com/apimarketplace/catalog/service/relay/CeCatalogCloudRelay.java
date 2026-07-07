package com.apimarketplace.catalog.service.relay;

import com.apimarketplace.agent.cloud.CloudLlmRuntimeAccess;
import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.service.ToolContextService;
import com.apimarketplace.credential.client.CredentialClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * CE-side decision + execution of the catalog credential relay: when a workflow node calls
 * a catalog tool with {@code credentialSource="platform"} and the CE install has NO local
 * platform credential for the integration, the whole execution is delegated to the linked
 * cloud, which injects its platform credential and bills the linked account.
 *
 * <p>Always a required bean of catalog-service, internally inert outside CE:
 * {@link CloudLlmRuntimeAccess} is injected {@code required = false} - the implementation
 * bean exists ONLY in the CE monolith context ({@code marketplace.mode=remote}), so on
 * cloud deployments the field is null and {@link #tryRelay} always returns empty.
 *
 * <p>Precedence is LOCAL-FIRST: a locally configured platform credential always wins; the
 * relay only fills the gap. When the relay does not apply (BYOK catalog source, unlinked
 * install, local credential present), empty is returned and the caller proceeds with the
 * unchanged local path (which surfaces the existing {@code credentials_required} failure).
 */
@Component
@Slf4j
public class CeCatalogCloudRelay {

    private final CloudLlmRuntimeAccess runtimeAccess;
    private final ToolContextService toolContextService;
    private final ApiRepository apiRepository;
    private final ApiToolRepository apiToolRepository;
    private final CredentialClient credentialClient;
    private final CloudCatalogRelayClient relayClient;

    public CeCatalogCloudRelay(@Autowired(required = false) CloudLlmRuntimeAccess runtimeAccess,
                               ToolContextService toolContextService,
                               ApiRepository apiRepository,
                               ApiToolRepository apiToolRepository,
                               CredentialClient credentialClient,
                               CloudCatalogRelayClient relayClient) {
        this.runtimeAccess = runtimeAccess;
        this.toolContextService = toolContextService;
        this.apiRepository = apiRepository;
        this.apiToolRepository = apiToolRepository;
        this.credentialClient = credentialClient;
        this.relayClient = relayClient;
    }

    /**
     * @return the relayed response (success OR failure - both terminal for this call) when
     *         the relay applies; empty when the caller must proceed with the local path
     */
    public Optional<ToolExecutionResponse> tryRelay(String toolIdOrSlug,
                                                    ToolExecutionRequest request,
                                                    String userId,
                                                    String requestId) {
        // Never let relay plumbing break the tool call: this branch runs BEFORE the
        // manager's own try/catch, so an unexpected exception here (e.g. a
        // publication-service hiccup while resolving the cloud runtime) would escape
        // as a 500. Falling back to the unchanged local path preserves the exact
        // pre-feature behavior on any relay-plumbing failure. Relay REFUSALS
        // (CatalogRelayException) are still mapped to failed responses inside.
        try {
            return doTryRelay(toolIdOrSlug, request, userId, requestId);
        } catch (RuntimeException e) {
            log.warn("[CeCatalogCloudRelay] Relay eligibility check failed for tool {} - "
                    + "falling back to the local path: {}", toolIdOrSlug, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ToolExecutionResponse> doTryRelay(String toolIdOrSlug,
                                                       ToolExecutionRequest request,
                                                       String userId,
                                                       String requestId) {
        // 1. Inert on cloud deployments (no runtime access bean) and for every call that
        //    did not explicitly pin the platform credential source.
        if (runtimeAccess == null || request == null
                || !"platform".equalsIgnoreCase(request.getCredentialSource())) {
            return Optional.empty();
        }

        // 2. Resolve the tool's API and its platform integration name. Anything missing
        //    means the local path handles it (including its own not-found error).
        Optional<ToolContextService.ToolContext> contextOpt = toolContextService.loadToolContext(toolIdOrSlug);
        if (contextOpt.isEmpty()) {
            return Optional.empty();
        }
        ToolContextService.ToolContext context = contextOpt.get();
        ApiEntity api = resolveApi(context);
        if (api == null || api.getPlatformCredentialName() == null) {
            return Optional.empty();
        }

        // 3. LOCAL-FIRST precedence: a locally configured platform credential always wins;
        //    the relay only fills the gap. This MUST use the direct by-name lookup
        //    (/api/internal/credentials/platform/by-name, a plain platform_credentials
        //    table read) and NEVER credentialClient.platformCredentialAvailable: that
        //    probe routes through GET /api/platform-credentials/{integration}/public-info,
        //    which this very feature made delegate to the CLOUD when no local row exists.
        //    Using it here re-enters that delegation and poisons the precedence check
        //    (the cloud's "available" reads as a local credential, so the relay never
        //    fires and the local path then fails credentials_required). The by-name
        //    lookup is empty on not-found AND on transport failure; falling through to
        //    the relay on a transport failure is acceptable because an unreachable
        //    credential API could not have resolved credentials for the local path either.
        if (credentialClient.findPlatformCredentialByName(api.getPlatformCredentialName()).isPresent()) {
            return Optional.empty();
        }

        // 4. Relay applies only when the install is cloud-linked AND the admin set the
        //    catalog source toggle to CLOUD. Otherwise the local path fails with the
        //    existing credentials_required behavior, unchanged.
        Optional<CloudLlmRuntimeCredentials> runtime = runtimeAccess.resolveCatalogCloudRuntime(userId);
        if (runtime.isEmpty()) {
            return Optional.empty();
        }

        String apiSlug = api.getApiSlug();
        String toolSlug = resolveToolSlug(context);
        if (apiSlug == null || apiSlug.isBlank() || toolSlug == null || toolSlug.isBlank()) {
            log.debug("[CeCatalogCloudRelay] Cannot build relay slugs for tool {} - falling back to local path",
                    toolIdOrSlug);
            return Optional.empty();
        }

        // 5. Relay the execution. Only the execution payload travels - never billing ids
        //    or credential ids (the cloud resolves its own credential and billing).
        long start = System.currentTimeMillis();
        try {
            ToolExecutionResponse relayed = relayClient.execute(
                    runtime.get(),
                    apiSlug,
                    toolSlug,
                    request.getParameters(),
                    request.getExpand(),
                    request.getMaxItems(),
                    request.getInlineBinaries());
            log.info("[CeCatalogCloudRelay] Relayed tool {}/{} to cloud: success={}",
                    apiSlug, toolSlug, relayed.isSuccess());
            return Optional.of(relayed);
        } catch (CloudCatalogRelayClient.CatalogRelayException e) {
            log.info("[CeCatalogCloudRelay] Cloud refused relay for tool {}/{}: {}",
                    apiSlug, toolSlug, e.getErrorCode() != null ? e.getErrorCode() : "unparseable error");
            return Optional.of(failedResponse(context, requestId, start,
                    relayErrorMessage(e.getErrorCode(), e.isDelinquent())));
        } catch (RuntimeException e) {
            log.warn("[CeCatalogCloudRelay] Cloud relay unreachable for tool {}/{}: {}",
                    apiSlug, toolSlug, e.getMessage());
            return Optional.of(failedResponse(context, requestId, start,
                    "Cloud relay unreachable: the linked LiveContext Cloud could not be contacted "
                            + "for this platform-credential call. Retry later, or configure a local "
                            + "credential for this integration."));
        }
    }

    private ApiEntity resolveApi(ToolContextService.ToolContext context) {
        String apiIdStr = context.getApiId();
        if (apiIdStr == null || apiIdStr.isBlank()) {
            return null;
        }
        try {
            return apiRepository.findById(UUID.fromString(apiIdStr)).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String resolveToolSlug(ToolContextService.ToolContext context) {
        if (context.getToolId() == null) {
            return null;
        }
        try {
            return apiToolRepository.findById(UUID.fromString(context.getToolId()))
                    .map(ApiToolEntity::getToolSlug)
                    .orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Agent-actionable failure text per relay error code. The reader is an LLM agent or
     * workflow user with no shell/REST access, so every message points at something they
     * (or the install administrator) can actually do.
     */
    private static String relayErrorMessage(String errorCode, boolean delinquent) {
        if (errorCode == null) {
            return "Platform credentials via LiveContext Cloud are unavailable right now. "
                    + "Retry later, or configure a local credential for this integration.";
        }
        return switch (errorCode) {
            case "SUBSCRIPTION_REQUIRED" -> "Platform credentials via LiveContext Cloud require an active "
                    + "subscription on the linked cloud account. Ask the install administrator to upgrade "
                    + "the linked account, or configure a local credential for this integration.";
            case "INSUFFICIENT_CREDITS" -> "The linked LiveContext Cloud account does not have enough "
                    + "credits for this platform-credential call"
                    + (delinquent ? " (the account also has an outstanding payment)" : "")
                    + ". Ask the install administrator to top up the linked cloud account, or configure "
                    + "a local credential for this integration.";
            case "CE_LINK_NOT_ACTIVE" -> "This install's cloud link is no longer active. Ask the install "
                    + "administrator to reconnect the cloud account in settings, or configure a local "
                    + "credential for this integration.";
            case "OAUTH_NOT_RELAYABLE" -> "This integration requires a locally configured OAuth "
                    + "credential and cannot run through LiveContext Cloud. Connect your own account "
                    + "for this integration and switch the step to the user credential source.";
            case "PLATFORM_NOT_AVAILABLE" -> "Platform credentials are not offered for this integration "
                    + "on LiveContext Cloud. Configure your own credential for this integration and "
                    + "switch the step to the user credential source.";
            case "TOOL_NOT_FOUND" -> "This tool is not available for platform-credential execution on "
                    + "LiveContext Cloud. Configure a local credential for this integration.";
            case "RATE_LIMITED" -> "LiveContext Cloud is rate limiting platform-credential calls for "
                    + "this install. Retry later.";
            default -> "Platform credentials via LiveContext Cloud rejected this call. Ask the install "
                    + "administrator to reconnect the cloud account in settings, or configure a local "
                    + "credential for this integration.";
        };
    }

    /** Failed response shaped like the manager's error path (toolName/iconSlug metadata). */
    private static ToolExecutionResponse failedResponse(ToolContextService.ToolContext context,
                                                        String requestId,
                                                        long start,
                                                        String errorMessage) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("toolName", context.getToolName());
        if (context.getIconSlug() != null) {
            metadata.put("iconSlug", context.getIconSlug());
        }
        metadata.put("cloudRelay", true);
        return ToolExecutionResponse.builder()
                .success(false)
                .error(errorMessage)
                .metadata(metadata)
                .executionTimeMs(System.currentTimeMillis() - start)
                .toolId(context.getToolId())
                .requestId(requestId)
                .build();
    }
}
