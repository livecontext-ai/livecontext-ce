package com.apimarketplace.agent.tools.remote;

import java.util.Map;

/**
 * Single source of truth for which backend service OWNS a given agent tool, in a
 * microservice deployment. The static routing rules used to be duplicated in every
 * caller that hops to the owning service (conversation-service's {@code ToolServiceRouter},
 * agent-service's {@code RemoteToolExecutionService}); they now resolve here so a new
 * service-owned tool is declared once.
 *
 * <p>Rules (default = orchestrator, which hosts the bulk of the tools):
 * <ul>
 *   <li>{@code agent}, {@code skill} → agent-service</li>
 *   <li>{@code table} → datasource-service</li>
 *   <li>{@code interface} → interface-service</li>
 *   <li>{@code catalog} → catalog-service</li>
 *   <li>everything else → orchestrator-service</li>
 * </ul>
 *
 * <p>Pure and dependency-free (no Spring, no HTTP): each caller maps the returned
 * {@link ServiceKey} to its own configured base URL (or, in agent-service, to local
 * in-process handling for {@code agent}/{@code skill}). This is only about the static
 * name→owner mapping; the cloud MCP server discovers ownership dynamically instead
 * (see {@code AggregatedToolCatalog}).
 */
public final class ToolServiceTopology {

    /** Logical owner of a tool. Callers resolve this to a concrete URL / handler. */
    public enum ServiceKey {
        ORCHESTRATOR,
        AGENT,
        DATASOURCE,
        INTERFACE,
        CATALOG
    }

    private static final Map<String, ServiceKey> TOOL_OWNER = Map.of(
            "agent", ServiceKey.AGENT,
            "skill", ServiceKey.AGENT,
            "table", ServiceKey.DATASOURCE,
            "interface", ServiceKey.INTERFACE,
            "catalog", ServiceKey.CATALOG
    );

    private ToolServiceTopology() {}

    /**
     * @return the service that owns {@code toolName}, or {@link ServiceKey#ORCHESTRATOR}
     *         for any tool without a dedicated owner (the default).
     */
    public static ServiceKey serviceFor(String toolName) {
        if (toolName == null) {
            return ServiceKey.ORCHESTRATOR;
        }
        return TOOL_OWNER.getOrDefault(toolName, ServiceKey.ORCHESTRATOR);
    }

    /** @return true iff {@code toolName} is owned by a service other than orchestrator. */
    public static boolean hasDedicatedService(String toolName) {
        return serviceFor(toolName) != ServiceKey.ORCHESTRATOR;
    }
}
