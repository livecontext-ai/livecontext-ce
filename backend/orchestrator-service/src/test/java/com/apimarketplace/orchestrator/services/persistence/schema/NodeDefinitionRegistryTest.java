package com.apimarketplace.orchestrator.services.persistence.schema;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.orchestrator.execution.v2.nodes.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NodeDefinitionRegistry")
class NodeDefinitionRegistryTest {

    private NodeDefinitionRegistry registry;

    private static final List<NodeSpec> ALL_SPECS = List.of(
        new ExitNodeSpec(), new SortNodeSpec(), new MergeNodeSpec(),
        new RemoveDuplicatesNodeSpec(), new FilterNodeSpec(),
        new DecisionNodeSpec(), new SwitchNodeSpec(), new ForkNodeSpec(),
        new SplitNodeSpec(), new LoopNodeSpec(), new WaitNodeSpec(),
        new FindNodeSpec(), new AgentNodeSpec(), new BrowserAgentNodeSpec(),
        new GuardrailNodeSpec(), new ClassifyNodeSpec(), new HttpRequestNodeSpec(),
        new RespondToWebhookNodeSpec(), new ManualTriggerNodeSpec(),
        new ChatTriggerNodeSpec(), new ErrorTriggerNodeSpec(), new WebhookTriggerNodeSpec(),
        new ScheduleTriggerNodeSpec(), new GetRowsNodeSpec(),
        new InsertRowNodeSpec(), new UpdateRowNodeSpec(), new DeleteRowNodeSpec(),
        new CreateColumnNodeSpec(), new SendEmailNodeSpec(), new InterfaceNodeSpec(),
        new SubWorkflowNodeSpec(), new ResponseNodeSpec(),
        new CompressionNodeSpec(), new DateTimeNodeSpec(), new CryptoJwtNodeSpec(),
        new XmlNodeSpec(), new RssNodeSpec(), new CodeNodeSpec(), new DatabaseNodeSpec(),
        new CompareDatasetsNodeSpec(), new ExtractFromFileNodeSpec(),
        new ConvertToFileNodeSpec(), new ApprovalNodeSpec(),
        new OptionNodeSpec(), new LimitNodeSpec(), new SummarizeNodeSpec(),
        new FormTriggerNodeSpec(), new TableTriggerNodeSpec(),
        new WorkflowTriggerNodeSpec(), new DownloadFileNodeSpec(),
        new TransformNodeSpec(), new AggregateNodeSpec(), new SetNodeSpec(),
        new HtmlExtractNodeSpec(), new TaskNodeSpec(), new StopOnErrorNodeSpec(),
        new SshNodeSpec(), new SftpNodeSpec()
    );

    @BeforeEach
    void setUp() {
        registry = new NodeDefinitionRegistry(ALL_SPECS);
        registry.init();
    }

    @Nested
    @DisplayName("Auto-discovery")
    class AutoDiscovery {

        @Test
        @DisplayName("Should discover all 58 NodeSpec implementations")
        void shouldDiscoverAll() {
            assertEquals(58, registry.getAll().size());
        }

        @Test
        @DisplayName("Should register each expected node type")
        void shouldRegisterAllTypes() {
            String[] expectedTypes = {
                "EXIT", "SORT", "MERGE", "REMOVE_DUPLICATES", "FILTER",
                "DECISION", "SWITCH", "FORK", "SPLIT", "LOOP", "WAIT",
                "FIND", "AGENT", "BROWSER_AGENT", "GUARDRAIL", "CLASSIFY", "HTTP_REQUEST",
                "RESPOND_TO_WEBHOOK", "MANUAL_TRIGGER", "CHAT_TRIGGER", "ERROR_TRIGGER",
                "WEBHOOK_TRIGGER", "SCHEDULE_TRIGGER", "GET_ROWS",
                "INSERT_ROW", "UPDATE_ROW", "DELETE_ROW", "CREATE_COLUMN",
                "SEND_EMAIL", "INTERFACE", "SUB_WORKFLOW", "RESPONSE", "COMPRESSION",
                "DATE_TIME", "CRYPTO_JWT", "XML", "RSS", "CODE", "DATABASE",
                "COMPARE_DATASETS", "EXTRACT_FROM_FILE", "CONVERT_TO_FILE",
                "APPROVAL", "OPTION", "LIMIT", "SUMMARIZE",
                "FORM_TRIGGER", "TABLE_TRIGGER", "WORKFLOW_TRIGGER",
                "DOWNLOAD_FILE", "TRANSFORM", "AGGREGATE", "SET",
                "HTML_EXTRACT", "TASK", "STOP_ON_ERROR", "SSH", "SFTP"
            };
            for (String type : expectedTypes) {
                assertTrue(registry.has(type), "Should have: " + type);
            }
        }
    }

    @Nested
    @DisplayName("get()")
    class GetTests {

        @Test
        void shouldFindByExactType() {
            Optional<NodeDefinition> def = registry.get("EXIT");
            assertTrue(def.isPresent());
            assertEquals("EXIT", def.get().nodeType());
            assertEquals("Exit", def.get().label());
        }

        @Test
        void shouldFindCaseInsensitive() {
            assertTrue(registry.get("exit").isPresent());
            assertTrue(registry.get("Sort").isPresent());
            assertTrue(registry.get("decision").isPresent());
        }

        @Test
        void shouldReturnEmptyForUnknown() {
            assertTrue(registry.get("UNKNOWN").isEmpty());
        }

        @Test
        void shouldReturnEmptyForNull() {
            assertTrue(registry.get(null).isEmpty());
        }
    }

    @Nested
    @DisplayName("getSpec()")
    class GetSpecTests {

        @Test
        void shouldReturnSpecByType() {
            Optional<NodeSpec> spec = registry.getSpec("TRANSFORM");
            assertTrue(spec.isPresent());
            assertNotNull(spec.get().customTransform(new java.util.HashMap<>()));
        }

        @Test
        void shouldReturnNullCustomTransformForSimpleNode() {
            Optional<NodeSpec> spec = registry.getSpec("EXIT");
            assertTrue(spec.isPresent());
            assertNull(spec.get().customTransform(new java.util.HashMap<>()));
        }
    }

    @Nested
    @DisplayName("has()")
    class HasTests {

        @Test
        void shouldReturnTrueForRegistered() {
            assertTrue(registry.has("EXIT"));
            assertTrue(registry.has("AGENT"));
            assertTrue(registry.has("FORM_TRIGGER"));
        }

        @Test
        void shouldReturnFalseForNull() {
            assertFalse(registry.has(null));
        }
    }

    @Nested
    @DisplayName("getByCategory()")
    class GetByCategoryTests {

        @Test
        void shouldReturnCoreNodes() {
            List<NodeDefinition> core = registry.getByCategory("core");
            assertTrue(core.size() > 10, "Should have many core nodes");
        }

        @Test
        void shouldReturnAgentNodes() {
            List<NodeDefinition> agents = registry.getByCategory("agent");
            assertEquals(4, agents.size()); // Agent, Browser Agent, Guardrail, Classify
        }

        @Test
        void shouldReturnTriggerNodes() {
            List<NodeDefinition> triggers = registry.getByCategory("trigger");
            assertTrue(triggers.size() >= 6); // Manual, Chat, Webhook, Schedule, Form, Table, Workflow
        }

        @Test
        void shouldReturnTableNodes() {
            List<NodeDefinition> tables = registry.getByCategory("table");
            assertTrue(tables.size() >= 5); // GetRows, InsertRow, UpdateRow, DeleteRow, CreateColumn, Find
        }

        @Test
        void shouldReturnEmptyForNonExistent() {
            assertTrue(registry.getByCategory("nonexistent").isEmpty());
        }

        @Test
        void shouldReturnEmptyForNull() {
            assertTrue(registry.getByCategory(null).isEmpty());
        }
    }

    @Nested
    @DisplayName("Definition content validation")
    class DefinitionContent {

        @Test
        void stopShouldBeTerminal() {
            NodeDefinition stop = registry.get("EXIT").orElseThrow();
            assertTrue(stop.terminal());
            assertFalse(stop.branching());
        }

        @Test
        void decisionShouldBeBranching() {
            NodeDefinition decision = registry.get("DECISION").orElseThrow();
            assertTrue(decision.branching());
        }

        @Test
        void mergeShouldHaveAliases() {
            NodeDefinition merge = registry.get("MERGE").orElseThrow();
            var field = merge.outputs().stream()
                .filter(f -> "merged_branches".equals(f.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("merged_branches output not found"));
            assertEquals("merged_branches", field.key());
            assertEquals(List.of("received_branches", "merged_data"), field.aliases());
        }

        @Test
        void outputsToDocMapShouldWork() {
            NodeDefinition sort = registry.get("SORT").orElseThrow();
            var docMap = sort.outputsToDocMap();
            assertEquals(2, docMap.size());
            assertTrue(docMap.containsKey("sorted_items"));
            assertTrue(docMap.containsKey("count"));
        }

        @Test
        @DisplayName("SPLIT declares current_item/current_index (runtime, body-only) for discoverability, kept conditional")
        void splitShouldDeclareRuntimeItemContextFields() {
            NodeDefinition split = registry.get("SPLIT").orElseThrow();
            var keys = split.outputs().stream().map(f -> f.key()).toList();
            // Runtime per-item context, declared so it shows in the output schema (agent + builder)
            assertTrue(keys.contains("current_item"), "SPLIT must declare current_item for discoverability");
            assertTrue(keys.contains("current_index"), "SPLIT must declare current_index for discoverability");
            // Persisted aggregate fields stay
            assertTrue(keys.containsAll(List.of("items", "item_count", "split_id", "spawn_reason", "terminated")),
                "SPLIT must keep its persisted aggregate fields");
            // current_item is CONDITIONAL (no default) so GenericOutputSchemaMapper excludes it from the
            // split's PERSISTED output (it is a body-node runtime variable, not the split's stored data).
            var currentItem = split.outputs().stream()
                .filter(f -> "current_item".equals(f.key())).findFirst().orElseThrow();
            assertTrue(currentItem.isConditional(),
                "current_item must be conditional (runtime-only, excluded from the split's persisted output)");
        }
    }

    @Nested
    @DisplayName("registerDynamic - typed-execution refactor")
    class DynamicRegistration {

        @Test
        void shouldRegisterMcpNodeAtRuntime() {
            NodeDefinition mcp = NodeDefinition.builder()
                .nodeType("MCP:firecrawl:scrape_url")
                .label("scrape_url")
                .category("mcp")
                .variablePrefix("mcp")
                .description("Scrape a URL")
                .outputs(List.of(
                    com.apimarketplace.agent.domain.OutputFieldDef.builder()
                        .key("markdown").type("string").description("Page content").build()
                ))
                .build();

            registry.registerDynamic(mcp);

            var fetched = registry.get("MCP:firecrawl:scrape_url");
            assertTrue(fetched.isPresent());
            assertEquals("mcp", fetched.get().category());
            assertEquals(1, fetched.get().outputs().size());
            assertEquals("markdown", fetched.get().outputs().get(0).key());
        }

        @Test
        void registerDynamicShouldOverwriteSameKey() {
            NodeDefinition v1 = NodeDefinition.builder()
                .nodeType("MCP:test:tool").label("v1").category("mcp").variablePrefix("mcp")
                .description("v1").build();
            NodeDefinition v2 = NodeDefinition.builder()
                .nodeType("MCP:test:tool").label("v2").category("mcp").variablePrefix("mcp")
                .description("v2").build();
            registry.registerDynamic(v1);
            registry.registerDynamic(v2);
            assertEquals("v2", registry.get("MCP:test:tool").orElseThrow().label());
        }

        @Test
        void mcpRegistrationsShouldAppearInGetByCategory() {
            NodeDefinition mcp = NodeDefinition.builder()
                .nodeType("MCP:slack:send").label("send").category("mcp").variablePrefix("mcp")
                .description("Send message").build();
            registry.registerDynamic(mcp);
            var mcps = registry.getByCategory("mcp");
            assertTrue(mcps.stream().anyMatch(d -> "MCP:slack:send".equals(d.nodeType())));
        }

        @Test
        void registerDynamicShouldRejectNullDefinition() {
            assertThrows(IllegalArgumentException.class, () -> registry.registerDynamic(null));
        }
    }
}
