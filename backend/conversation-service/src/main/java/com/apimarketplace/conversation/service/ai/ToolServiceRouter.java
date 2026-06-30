package com.apimarketplace.conversation.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Routes tool execution requests to the correct microservice.
 *
 * Instead of sending all tool calls to orchestrator (which then hops to the actual service),
 * this router sends calls directly to the owning service:
 *   - agent, skill → agent-service
 *   - table → datasource-service
 *   - interface → interface-service
 *   - catalog → catalog-service
 *   - workflow, application, web_search, ... → orchestrator-service
 *
 * All services expose the same /api/agent-tools/execute endpoint format.
 */
@Slf4j
@Component
public class ToolServiceRouter {

    private static final Set<String> AGENT_SERVICE_TOOLS = Set.of("agent", "skill");
    private static final Set<String> DATASOURCE_SERVICE_TOOLS = Set.of("table");
    private static final Set<String> INTERFACE_SERVICE_TOOLS = Set.of("interface");
    private static final Set<String> CATALOG_SERVICE_TOOLS = Set.of("catalog");

    private final String orchestratorUrl;
    private final String agentServiceUrl;
    private final String datasourceServiceUrl;
    private final String interfaceServiceUrl;
    private final String catalogServiceUrl;

    public ToolServiceRouter(
            @Value("${orchestrator.service.url:http://localhost:8099}") String orchestratorUrl,
            @Value("${services.agent-service.url:http://localhost:8090}") String agentServiceUrl,
            @Value("${services.datasource-service.url:http://localhost:8088}") String datasourceServiceUrl,
            @Value("${services.interface-service.url:http://localhost:8089}") String interfaceServiceUrl,
            @Value("${services.catalog-service.url:http://localhost:8081}") String catalogServiceUrl) {
        this.orchestratorUrl = orchestratorUrl;
        this.agentServiceUrl = agentServiceUrl;
        this.datasourceServiceUrl = datasourceServiceUrl;
        this.interfaceServiceUrl = interfaceServiceUrl;
        this.catalogServiceUrl = catalogServiceUrl;

        log.info("ToolServiceRouter initialized - routes: agent/skill→{}, table→{}, interface→{}, catalog→{}, default→{}",
            agentServiceUrl, datasourceServiceUrl, interfaceServiceUrl, catalogServiceUrl, orchestratorUrl);
    }

    /**
     * Get the base URL for the service that handles the given tool.
     *
     * @param toolName the tool name (e.g., "table", "interface", "workflow")
     * @return the base service URL (e.g., "http://localhost:8088")
     */
    public String getServiceUrl(String toolName) {
        if (AGENT_SERVICE_TOOLS.contains(toolName)) {
            return agentServiceUrl;
        }
        if (DATASOURCE_SERVICE_TOOLS.contains(toolName)) {
            return datasourceServiceUrl;
        }
        if (INTERFACE_SERVICE_TOOLS.contains(toolName)) {
            return interfaceServiceUrl;
        }
        if (CATALOG_SERVICE_TOOLS.contains(toolName)) {
            return catalogServiceUrl;
        }
        return orchestratorUrl;
    }

    /**
     * Get the full execute endpoint URL for the given tool.
     *
     * @param toolName the tool name
     * @return the full URL (e.g., "http://localhost:8088/api/agent-tools/execute")
     */
    public String getExecuteUrl(String toolName) {
        return getServiceUrl(toolName) + "/api/agent-tools/execute";
    }

    /**
     * Get the service name for logging/debugging.
     */
    public String getServiceName(String toolName) {
        if (AGENT_SERVICE_TOOLS.contains(toolName)) return "agent-service";
        if (DATASOURCE_SERVICE_TOOLS.contains(toolName)) return "datasource-service";
        if (INTERFACE_SERVICE_TOOLS.contains(toolName)) return "interface-service";
        if (CATALOG_SERVICE_TOOLS.contains(toolName)) return "catalog-service";
        return "orchestrator-service";
    }
}
