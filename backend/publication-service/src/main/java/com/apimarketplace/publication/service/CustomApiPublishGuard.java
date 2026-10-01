package com.apimarketplace.publication.service;

import com.apimarketplace.publication.config.CatalogInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Refuses to SHARE a workflow or an agent that is built on a CUSTOM API.
 *
 * <p>A custom API ({@code apis.source = 'custom'}) is a private definition its owner
 * registered for their own tenant. It is never part of the shipped catalog, so it
 * cannot travel with a publication: the acquirer's catalog has no such API, no
 * credential template exists for it, and every node built on it fails at run time.
 * Acquirers would install an application that cannot run, with no way to fix it.
 *
 * <p><b>Only shareable publications are gated.</b> A PRIVATE publication is the
 * publisher's own deployment inside their own tenant, where the custom API resolves
 * exactly as it does in the source workflow, so PRIVATE is deliberately allowed.
 *
 * <p>The publish surfaces warn about this before submitting (they call the public
 * {@code POST /api/workflow-inspector/custom-apis}); this guard is the authoritative
 * gate that a direct API call cannot bypass.
 */
@Component
public class CustomApiPublishGuard {

    private static final Logger logger = LoggerFactory.getLogger(CustomApiPublishGuard.class);

    /** Plan key holding the mcp nodes, each carrying the catalog tool it runs as {@code id}. */
    private static final String MCPS_KEY = "mcps";

    /**
     * Keys whose value is an agent tools config carrying an explicit {@code tools} grant.
     * The snapshot variant is what a workflow plan gets at publish time (the raw plan
     * only carries normalised {@code mcp:<label>} refs, which resolve to no catalog API).
     */
    private static final Set<String> TOOLS_CONFIG_KEYS =
            Set.of("toolsConfig", "_snapshot_agent_toolsConfig");

    /**
     * Depth bound, mirroring {@code CeExclusiveFeatureDetector}: a deserialized JSONB
     * snapshot is acyclic, so only depth can run away, and a StackOverflowError on the
     * publish path would be a 500 instead of a refusal.
     */
    private static final int MAX_DEPTH = 1000;

    private final CatalogInternalClient catalogInternalClient;

    public CustomApiPublishGuard(CatalogInternalClient catalogInternalClient) {
        this.catalogInternalClient = catalogInternalClient;
    }

    /**
     * Throw when a shareable publication references a custom API.
     *
     * @param visibility     the publication's EFFECTIVE visibility (PRIVATE is exempt)
     * @param snapshot       a workflow plan or an agent snapshot (walked recursively)
     * @param publisherId    the publishing tenant: its OWN custom APIs are matched more
     *                       broadly (see {@code WorkflowInspectorService.findCustomApiRefs})
     * @param publisherOrgId the publishing workspace, or null for personal scope
     */
    public void assertPublishable(PublicationVisibility visibility,
                                  Map<String, Object> snapshot,
                                  String publisherId,
                                  String publisherOrgId) {
        if (visibility == PublicationVisibility.PRIVATE || snapshot == null || snapshot.isEmpty()) {
            return;
        }
        Set<String> identifiers = collectToolIdentifiers(snapshot);
        if (identifiers.isEmpty()) {
            return;
        }
        List<Map<String, Object>> customApis =
                catalogInternalClient.findCustomApiRefs(identifiers, publisherId, publisherOrgId);
        if (customApis.isEmpty()) {
            return;
        }

        List<String> names = customApis.stream()
                .map(api -> {
                    Object name = api.get("apiName");
                    Object slug = api.get("apiSlug");
                    return name != null && !name.toString().isBlank()
                            ? name.toString()
                            : String.valueOf(slug);
                })
                .collect(Collectors.toList());

        logger.warn("Publish refused: snapshot references {} custom API(s): {}", names.size(), names);
        throw new PublicationValidationException(
                PublicationValidationException.CUSTOM_API_NOT_PUBLISHABLE,
                "Custom APIs cannot be shared. This publication uses " + String.join(", ", names)
                        + ", which exists only in your own account, so anyone installing it would get "
                        + "nodes that cannot run. Replace those nodes with catalog integrations, or keep "
                        + "the publication private.",
                Map.of("customApis", customApis));
    }

    /**
     * Collect every catalog tool identifier a snapshot references, at any depth.
     *
     * <p>Handles both snapshot shapes with one walk, because both nest the same
     * building blocks: a workflow plan carries {@code mcps[].id} plus
     * {@code agents[]} and {@code _snapshot_subworkflows}; an agent snapshot carries
     * {@code agent.toolsConfig.tools} plus {@code workflows.*} plans and
     * {@code subAgents.*} snapshots.
     *
     * <p>Identifiers are returned verbatim in the forms these fields actually hold: an mcp
     * node's {@code apiSlug/toolSlug}, an agent tool grant's {@code apiSlug:toolSlug} (or
     * {@code apiSlug:toolName}, a second convention the agent surfaces also write) or its
     * legacy {@code api_tools.id} UUID, and a bare {@code tool_slug}. catalog-service
     * resolves all of them, and decides how widely each may match. Names that belong to no
     * catalog API at all (core tools, {@code web_search}, a plan's normalised
     * {@code mcp:<label>} ref, ...) simply resolve to nothing.
     */
    public Set<String> collectToolIdentifiers(Object node) {
        Set<String> identifiers = new LinkedHashSet<>();
        collectInto(node, identifiers, 0);
        return identifiers;
    }

    private void collectInto(Object node, Set<String> out, int depth) {
        if (depth > MAX_DEPTH) {
            logger.warn("Stopped collecting tool identifiers at depth {} - snapshot nests deeper "
                    + "than any legitimate plan", MAX_DEPTH);
            return;
        }
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                if (MCPS_KEY.equals(key)) {
                    addMcpNodeIds(value, out);
                } else if (TOOLS_CONFIG_KEYS.contains(key)) {
                    addGrantedTools(value, out);
                }
                collectInto(value, out, depth + 1);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                collectInto(item, out, depth + 1);
            }
        }
    }

    /** {@code mcps[].id} is the catalog tool a node executes. */
    private void addMcpNodeIds(Object mcps, Set<String> out) {
        if (!(mcps instanceof List<?> list)) return;
        for (Object item : list) {
            if (item instanceof Map<?, ?> mcp) {
                addIfText(mcp.get("id"), out);
            }
        }
    }

    /** {@code toolsConfig.tools[]} is the explicit catalog tool grant of an agent. */
    private void addGrantedTools(Object toolsConfig, Set<String> out) {
        if (!(toolsConfig instanceof Map<?, ?> config)) return;
        Object tools = config.get("tools");
        if (!(tools instanceof List<?> list)) return;
        for (Object tool : list) {
            if (tool instanceof Map<?, ?> map) {
                // Tolerate the object form some payloads use: {id|toolSlug: "..."}.
                addIfText(map.get("id"), out);
                addIfText(map.get("toolSlug"), out);
            } else {
                addIfText(tool, out);
            }
        }
    }

    private void addIfText(Object value, Set<String> out) {
        if (value == null) return;
        String text = value.toString().trim();
        if (!text.isEmpty()) {
            out.add(text);
        }
    }
}
