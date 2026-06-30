package com.apimarketplace.agent.registry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 4a.7 - MCP boundary guard.
 *
 * <p>Schema slimming (Stage 4a.1) coerces every parameter {@code type} to
 * {@code "string"} and strips {@code description}/{@code enum}/{@code examples}
 * so the LLM prompt stays under token budget. That transformation lives in
 * {@code CoreToolsProvider.minimizeSchema} in <b>conversation-service</b> -
 * a completely separate code path from the MCP tools endpoint, which is
 * served by {@code McpServerController} in <b>orchestrator-service</b> via
 * {@link AgentToolRegistry#getToolsInMcpFormat()}.
 *
 * <p><b>Why a guard test.</b> If a future change wires the slim minimiser
 * into the registry's MCP path (e.g., "reuse the code, we already have it"),
 * external MCP clients - Claude Desktop, Claude Code, the Agent SDK - would
 * suddenly see every parameter typed {@code string} and lose discriminators.
 * The failure would be silent; MCP responses would still be valid JSON. This
 * test is a trip-wire, not a functional test.
 *
 * <p>Assertion shape: with a synthetic tool that uses {@code integer} type and
 * populated {@code description}/{@code enum} values, the registry's MCP output
 * must preserve each of those verbatim - regardless of what
 * {@code conversation.jit.schemas.mode} is set to (which this service
 * doesn't even read).
 */
@DisplayName("AgentToolRegistry.getToolsInMcpFormat - full schema preserved (Stage 4a.7)")
class McpFullSchemaGuardTest {

    private AgentToolDefinition richTool() {
        // A representative rich schema: integer type, description present,
        // enum populated. All three are stripped by the slim minimiser; all
        // three must survive the MCP path.
        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "count", Map.of(
                                "type", "integer",
                                "description", "how many rows to fetch",
                                "minimum", 1,
                                "maximum", 100
                        ),
                        "mode", Map.of(
                                "type", "string",
                                "description", "lookup strategy",
                                "enum", List.of("exact", "fuzzy", "prefix")
                        )
                ),
                "required", List.of("count", "mode")
        );

        return AgentToolDefinition.builder()
                .name("query_rows")
                .description("Fetch rows by count with a lookup mode")
                .category(ToolCategory.UTILITY)
                .inputSchema(inputSchema)
                .requiresAuth(false)
                .build();
    }

    @Test
    @DisplayName("MCP format embeds the inputSchema verbatim - integer type survives")
    void integerTypeSurvives() {
        DefaultAgentToolRegistry registry = new DefaultAgentToolRegistry();
        registry.register(richTool());

        List<Map<String, Object>> mcp = registry.getToolsInMcpFormat();
        assertThat(mcp).hasSize(1);

        Map<String, Object> tool = mcp.get(0);
        assertThat(tool).containsKeys("name", "description", "inputSchema");
        assertThat(tool.get("name")).isEqualTo("query_rows");

        @SuppressWarnings("unchecked")
        Map<String, Object> inputSchema = (Map<String, Object>) tool.get("inputSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) inputSchema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> count = (Map<String, Object>) properties.get("count");

        // The slim minimiser would coerce this to "string". MCP MUST NOT.
        assertThat(count.get("type"))
                .as("integer type must survive the MCP path, not be coerced to string")
                .isEqualTo("integer");
    }

    @Test
    @DisplayName("descriptions are preserved (not stripped as the slim path would)")
    void descriptionsSurvive() {
        DefaultAgentToolRegistry registry = new DefaultAgentToolRegistry();
        registry.register(richTool());

        Map<String, Object> tool = registry.getToolsInMcpFormat().get(0);
        assertThat(tool.get("description"))
                .as("tool-level description")
                .isEqualTo("Fetch rows by count with a lookup mode");

        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>)
                ((Map<String, Object>) tool.get("inputSchema")).get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> count = (Map<String, Object>) props.get("count");
        @SuppressWarnings("unchecked")
        Map<String, Object> mode = (Map<String, Object>) props.get("mode");

        assertThat(count.get("description")).isEqualTo("how many rows to fetch");
        assertThat(mode.get("description")).isEqualTo("lookup strategy");
    }

    @Test
    @DisplayName("enum values survive on the MCP payload (not stripped as the slim path would)")
    void enumValuesSurvive() {
        DefaultAgentToolRegistry registry = new DefaultAgentToolRegistry();
        registry.register(richTool());

        Map<String, Object> tool = registry.getToolsInMcpFormat().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>)
                ((Map<String, Object>) tool.get("inputSchema")).get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> mode = (Map<String, Object>) props.get("mode");

        assertThat(mode.get("enum"))
                .as("enum discriminators must not be stripped")
                .isEqualTo(List.of("exact", "fuzzy", "prefix"));
    }

    @Test
    @DisplayName("numeric bounds on parameters survive (minimum/maximum retained)")
    void numericBoundsSurvive() {
        DefaultAgentToolRegistry registry = new DefaultAgentToolRegistry();
        registry.register(richTool());

        Map<String, Object> tool = registry.getToolsInMcpFormat().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> count = (Map<String, Object>)
                ((Map<String, Object>) ((Map<String, Object>) tool.get("inputSchema"))
                        .get("properties"))
                        .get("count");

        assertThat(count.get("minimum")).isEqualTo(1);
        assertThat(count.get("maximum")).isEqualTo(100);
    }

    @Test
    @DisplayName("tool with null inputSchema degrades to a well-formed MCP default, not a slim surrogate")
    void nullInputSchemaDefault() {
        // A legit case: a tool with no inputs at all. The MCP contract still
        // requires {type:object, properties:{}}. Assert we emit that, not
        // some slim-marker that coerces types to string.
        AgentToolDefinition noInputs = AgentToolDefinition.builder()
                .name("ping")
                .description("heartbeat")
                .category(ToolCategory.UTILITY)
                .inputSchema(null)
                .requiresAuth(false)
                .build();
        DefaultAgentToolRegistry registry = new DefaultAgentToolRegistry();
        registry.register(noInputs);

        Map<String, Object> tool = registry.getToolsInMcpFormat().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) tool.get("inputSchema");

        assertThat(schema.get("type")).isEqualTo("object");
        assertThat(schema.get("properties")).isEqualTo(Map.of());
    }

    @Test
    @DisplayName("description field is always present on MCP payload (MCP spec requires it)")
    void descriptionAlwaysPresent() {
        DefaultAgentToolRegistry registry = new DefaultAgentToolRegistry();
        registry.register(richTool());

        Map<String, Object> tool = registry.getToolsInMcpFormat().get(0);
        assertThat(tool).containsKey("description");
        assertThat(tool.get("description")).isNotNull();
        assertThat((String) tool.get("description")).isNotBlank();
    }
}
