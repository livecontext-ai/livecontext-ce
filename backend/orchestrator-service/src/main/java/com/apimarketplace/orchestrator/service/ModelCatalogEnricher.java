package com.apimarketplace.orchestrator.service;

import com.apimarketplace.agent.client.AgentClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites {@code provider.enum/default} and {@code model.default} from the live
 * {@link com.apimarketplace.agent.service.ModelCatalogService ModelCatalogService}
 * catalog for LLM node types (classify, guardrail).
 *
 * <p>Shared by {@link NodeHelpFormatter} (read-time help) and
 * {@link NodeParamsValidator} (write-time validation) so both layers see the same
 * provider list at the same instant - eliminating the divergence where the help
 * advertised the live catalog while the validator still rejected providers absent
 * from the V11 Flyway seed.
 *
 * <p>Fail-soft contract: if the catalog is unavailable, empty, or throws, the input
 * reference is returned unchanged. Never mutates the input - always deep-copies
 * the affected sub-maps so the entity's JSONB {@code parameters} field is safe
 * from a Hibernate dirty-flush.
 */
@Slf4j
@Service
public class ModelCatalogEnricher {

    private static final Set<String> LLM_NODE_TYPES = Set.of("classify", "guardrail");

    private final AgentClient agentClient;

    public ModelCatalogEnricher(AgentClient agentClient) {
        this.agentClient = agentClient;
    }

    /**
     * For classify/guardrail: return a copy of {@code params} with
     * {@code provider.enum}, {@code provider.default}, and {@code model.default}
     * rewritten from the live catalog. For all other types (or on catalog failure):
     * return the input reference unchanged.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> enrichIfLlm(String nodeType, Map<String, Object> params) {
        if (nodeType == null || !LLM_NODE_TYPES.contains(nodeType)) return params;
        if (params == null) return null;

        Map<String, Object> catalog;
        try {
            catalog = agentClient.getModelsInfo();
        } catch (Exception e) {
            log.debug("Live model catalog unavailable - keeping seeded values: {}", e.getMessage());
            return params;
        }
        if (catalog == null || catalog.isEmpty()) return params;

        List<String> providerNames = extractProviderNames(catalog);
        String defaultProvider = catalog.get("defaultProvider") instanceof String s ? s : null;
        String defaultModel = catalog.get("defaultModel") instanceof String s ? s : null;
        if (providerNames.isEmpty() && defaultProvider == null && defaultModel == null) return params;

        Map<String, Object> newParams = new LinkedHashMap<>(params);

        if (!providerNames.isEmpty() || defaultProvider != null) {
            Object providerRaw = newParams.get("provider");
            if (providerRaw instanceof Map) {
                Map<String, Object> newProvider = new LinkedHashMap<>((Map<String, Object>) providerRaw);
                if (!providerNames.isEmpty()) newProvider.put("enum", providerNames);
                if (defaultProvider != null) newProvider.put("default", defaultProvider);
                newParams.put("provider", newProvider);
            }
        }

        if (defaultModel != null) {
            Object modelRaw = newParams.get("model");
            if (modelRaw instanceof Map) {
                Map<String, Object> newModel = new LinkedHashMap<>((Map<String, Object>) modelRaw);
                newModel.put("default", defaultModel);
                newParams.put("model", newModel);
            }
        }

        return newParams;
    }

    private List<String> extractProviderNames(Map<String, Object> catalog) {
        Object providersRaw = catalog.get("providers");
        if (!(providersRaw instanceof List<?> list)) return List.of();
        List<String> names = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map && map.get("name") instanceof String name && !name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }
}
