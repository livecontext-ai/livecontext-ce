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

    /** The catalogue slice holding the decision models a classify node can also run on. */
    private static final String DECISION_CATEGORY = "classification";

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
        providerNames = withExtraEngines(nodeType, providerNames);
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

    /**
     * Adds the providers of any OTHER engine this node type can run on.
     *
     * <p>Only classify has one: it runs on a chat model or on a decision model, and the
     * decision providers are deliberately absent from the chat catalogue (their models
     * cannot hold a conversation, so every conversational surface filters them out). The
     * validator's allow-list is built from this list, so without the second slice an agent
     * authoring a plan would be refused the very engine this node type supports.
     *
     * <p>Guardrail gets nothing extra, and that is the point of doing this per node type
     * rather than widening the catalogue for both: a guardrail writes prose, so a decision
     * provider in its allow-list would be a plan that saves cleanly and fails at run time.
     *
     * <p>A failure to reach the extra slice leaves the chat providers alone: a smaller
     * allow-list refuses a valid plan, which is recoverable and visible, where a wider one
     * accepts an invalid one silently.
     *
     * <p><b>Known divergence from the picker on a cloud-linked CE install.</b> This call
     * carries no tenant, so the catalogue answers without the cloud-source rule; the
     * picker asks WITH one, and for a cloud-selected tenant that rule hides any provider
     * the relay cannot execute, which includes this one (the relay speaks
     * chat-completions, not System One). So on such an install an agent may author a plan
     * naming a provider its owner cannot select. Scoping this lookup to the tenant would
     * close it, but nothing on this path has a tenant to scope it with: the enricher runs
     * for help rendering and plan validation alike, including from a scheduler thread.
     * Left explicit rather than silently inconsistent.
     */
    private List<String> withExtraEngines(String nodeType, List<String> providerNames) {
        if (!"classify".equals(nodeType)) return providerNames;
        try {
            Map<String, Object> decisionCatalog = agentClient.getModelsInfo(DECISION_CATEGORY);
            if (decisionCatalog == null || decisionCatalog.isEmpty()) return providerNames;
            List<String> merged = new ArrayList<>(providerNames);
            for (String name : extractProviderNames(decisionCatalog)) {
                if (!merged.contains(name)) merged.add(name);
            }
            return merged;
        } catch (Exception e) {
            log.debug("Decision-model catalog unavailable - classify keeps the chat providers: {}",
                    e.getMessage());
            return providerNames;
        }
    }

    /**
     * The providers that have at least one model in this catalogue slice.
     *
     * <p><b>The emptiness check is not defensive tidying, it is the filter.</b> The
     * catalogue removes models a category does not accept but deliberately KEEPS the
     * provider entry they hung off, so a provider whose every model was filtered out still
     * arrives here as a name with an empty list. Offering that name as a valid
     * {@code provider} value puts a model the node cannot run into the validator's own
     * allow-list: a guardrail node would accept a decision provider, which cannot write the
     * prose a guardrail exists to produce, and the plan would save cleanly and fail at run
     * time. Requiring a model is what keeps the enum to providers that can actually serve
     * this node type.
     *
     * <p><b>This reaches beyond the decision engine, deliberately.</b> Any provider that
     * arrives as an empty shell now leaves the enum: a provider whose every model an admin
     * disabled, for instance. Refusing a plan that names it is the correct answer, since
     * there is nothing for the node to run, but it is a refusal that did not happen before
     * and a reader tracing one should find it stated rather than infer it.
     */
    private List<String> extractProviderNames(Map<String, Object> catalog) {
        Object providersRaw = catalog.get("providers");
        if (!(providersRaw instanceof List<?> list)) return List.of();
        List<String> names = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map && map.get("name") instanceof String name && !name.isBlank()
                    && hasAnyModel(map)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Whether this provider entry may contribute its name.
     *
     * <p>An EMPTY model list is the discriminator, and an ABSENT one is not the same
     * thing. The catalogue filter leaves the key present and the list empty when it has
     * removed every model a category does not accept, which is the case to reject. A
     * payload that carries no {@code models} key at all is saying nothing about its
     * models, and dropping it would trade one silent exclusion for another - so it is
     * kept, exactly as before this check existed.
     */
    private boolean hasAnyModel(Map<?, ?> providerEntry) {
        Object models = providerEntry.get("models");
        if (!(models instanceof List<?> list)) return true;
        return !list.isEmpty();
    }
}
