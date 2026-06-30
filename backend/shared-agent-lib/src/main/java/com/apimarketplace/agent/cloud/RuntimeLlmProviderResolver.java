package com.apimarketplace.agent.cloud;

import com.apimarketplace.agent.factory.BridgeAvailabilityFilter;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.provider.LLMProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Optional;

/**
 * Tenant-aware provider resolver. In CE cloud mode API providers are wrapped in
 * a relay provider; bridges and BYOK mode continue using the local provider.
 */
@Component
public class RuntimeLlmProviderResolver {

    private static final Logger log = LoggerFactory.getLogger(RuntimeLlmProviderResolver.class);

    private final LLMProviderFactory providerFactory;
    private final CloudLlmRelayClient relayClient;
    private final CloudLlmRuntimeAccess runtimeAccess;

    public RuntimeLlmProviderResolver(LLMProviderFactory providerFactory,
                                      CloudLlmRelayClient relayClient,
                                      @Autowired(required = false) CloudLlmRuntimeAccess runtimeAccess) {
        this.providerFactory = providerFactory;
        this.relayClient = relayClient;
        this.runtimeAccess = runtimeAccess;
    }

    public LLMProvider resolve(String providerName, AgentLoopContext context) {
        LLMProvider localProvider = providerFactory.getProvider(providerName);
        if (runtimeAccess == null || context == null || isBridgeProvider(providerName)) {
            return localProvider;
        }
        String tenantId = context.tenantId();
        if (!runtimeAccess.isCloudSelected(tenantId)) {
            return localProvider;
        }
        if (!CloudRelaySupport.isSupportedProvider(providerName)) {
            throw new LLMProviderException(providerName,
                    "Cloud LLM source selected but provider is not supported by the Cloud relay");
        }
        CloudLlmRuntimeCredentials credentials = runtimeAccess.resolveCloudRuntime(tenantId)
                .orElseThrow(() -> new LLMProviderException(providerName,
                        "Cloud LLM source selected but cloud link is not ready"));
        // Stamp the per-execution id so the cloud bills the whole execution as ONE aggregated
        // CE_LLM_RELAY line (accrue→settle). Null executionId ⇒ the cloud falls back to per-call.
        return new CloudRelayProvider(localProvider, credentials, relayClient, context.executionId());
    }

    /**
     * Terminal settle for a CE relay execution - call once when the agent loop ends. Tells the
     * cloud to bill the usage it accrued across the relayed turns as ONE {@code CE_LLM_RELAY}
     * ledger line. No-op unless this execution actually used the cloud relay with a correlation
     * {@code executionId}. Best-effort: a failure here is backstopped by the cloud's crash-recovery
     * reaper, so it never breaks the loop's own result.
     */
    public void settleCeRelay(AgentLoopContext context) {
        if (runtimeAccess == null || context == null) {
            return;
        }
        String executionId = context.executionId();
        if (executionId == null || executionId.isBlank()) {
            return;
        }
        String tenantId = context.tenantId();
        if (!runtimeAccess.isCloudSelected(tenantId)) {
            return;
        }
        try {
            runtimeAccess.resolveCloudRuntime(tenantId).ifPresent(credentials ->
                    relayClient.settle(credentials, new CeRelaySettleRequest(executionId)));
        } catch (Exception e) {
            // Reaper backstops this - log and move on, never fail the execution on a settle hiccup.
            log.warn("CE relay terminal settle failed for execution {} (reaper will retry): {}",
                    executionId, e.getMessage());
        }
    }

    public String resolveDefaultProviderName(String tenantId) {
        if (runtimeAccess != null && runtimeAccess.isCloudSelected(tenantId)) {
            Optional<LLMProvider> firstApiProvider = providerFactory.getAvailableProviderNames().stream()
                    .filter(CloudRelaySupport::isSupportedProvider)
                    .map(providerFactory::getProvider)
                    .sorted(Comparator.comparingInt(LLMProvider::getDisplayOrder))
                    .findFirst();
            if (firstApiProvider.isPresent()) {
                return firstApiProvider.get().getProviderName();
            }
        }
        return providerFactory.getDefaultProviderName();
    }

    public boolean isCloudMode(String tenantId) {
        return runtimeAccess != null && runtimeAccess.isCloudSelected(tenantId);
    }

    private static boolean isBridgeProvider(String providerName) {
        return providerName != null
                && BridgeAvailabilityFilter.BRIDGE_PROVIDER_TO_CLI_ID.containsKey(providerName.toLowerCase());
    }
}
