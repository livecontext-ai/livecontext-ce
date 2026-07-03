package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.agent.client.queue.AgentExecutionRequestMessage;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.Agent;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.async.PendingAgent;
import com.apimarketplace.orchestrator.execution.v2.async.PendingAgentRegistry;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.services.agent.AgentConfigResolver;
import com.apimarketplace.orchestrator.services.agent.AgentRuntimeOverrides;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AgentNode}'s async queue execution path
 * (activated via {@code scaling.agent.queue.enabled=true}).
 *
 * <p>The async path:
 * <ol>
 *   <li>Generates a correlationId</li>
 *   <li>Registers a {@link PendingAgent} entry on the {@link PendingAgentRegistry}</li>
 *   <li>Yields with {@link NodeExecutionResult#asyncRunning} - visible status stays RUNNING</li>
 *   <li>The engine event service enqueues the {@code queueMessage} attached to the result output</li>
 * </ol>
 *
 * <p>Completion is delivered later by {@code AgentAsyncCompletionService}, which calls back
 * into the same sync persistence pipeline used by inline execution.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentNode Async Queue Path")
class AgentNodeAsyncTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private AgentClient mockAgentClient;

    @Mock
    private PendingAgentRegistry mockPendingAgentRegistry;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("user_input", "Analyze this data");

        context = ExecutionContext.create(
            "run-async-1",
            "workflow-run-async-1",
            "tenant-async-1",
            "item-0",
            0,
            triggerData,
            mockPlan
        );
    }

    private Agent createAgent(String type) {
        return new Agent(
            "agent-config-1",
            type,
            "Test Agent",
            null,
            null,
            "openai",
            "gpt-4o",
            "You are a test agent",
            "Analyze this",
            0.7,
            4096,
            10,
            5,
            List.of(),
            null,
            Map.of(),
            List.of(),
            null,
            List.of(),
            null,
            null
        );
    }

    private ServiceRegistry buildServiceRegistry() {
        return ServiceRegistry.builder()
            .agentClient(mockAgentClient)
            .pendingAgentRegistry(mockPendingAgentRegistry)
            .build();
    }

    private AgentNode createAsyncNode(String type) {
        Agent agent = createAgent(type);
        AgentNode node = new AgentNode("agent:test_node", agent);
        node.acceptServices(buildServiceRegistry());
        node.setAsyncQueueEnabled(true);
        return node;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Async path activation
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Async path activation")
    class AsyncPathActivation {

        @Test
        @DisplayName("Should yield asyncRunning when async queue enabled for agent type")
        void shouldYieldAsyncRunningForAgent() {
            AgentNode node = createAsyncNode("agent");

            NodeExecutionResult result = node.execute(context);

            assertThat(result.status()).isEqualTo(NodeStatus.RUNNING);
            assertThat(result.isAsyncRunning()).isTrue();
            assertThat(result.metadata())
                .containsEntry(NodeExecutionResult.ASYNC_RUNNING_MARKER, Boolean.TRUE)
                .containsEntry(NodeExecutionResult.ASYNC_AGENT_TYPE, "agent");
            assertThat(result.metadata().get(NodeExecutionResult.ASYNC_CORRELATION_ID))
                .isNotNull();
            assertThat(result.output()).containsKey("correlationId");
            assertThat(result.output()).containsEntry("async", true);
            assertThat(result.output()).containsEntry("agentType", "agent");
        }

        @Test
        @DisplayName("Should yield asyncRunning when async queue enabled for classify type")
        void shouldYieldAsyncRunningForClassify() {
            AgentNode node = createAsyncNode("classify");

            NodeExecutionResult result = node.execute(context);

            assertThat(result.isAsyncRunning()).isTrue();
            assertThat(result.metadata()).containsEntry(NodeExecutionResult.ASYNC_AGENT_TYPE, "classify");
            assertThat(result.output()).containsEntry("agentType", "classify");
        }

        @Test
        @DisplayName("Should yield asyncRunning when async queue enabled for guardrail type")
        void shouldYieldAsyncRunningForGuardrail() {
            AgentNode node = createAsyncNode("guardrail");

            NodeExecutionResult result = node.execute(context);

            assertThat(result.isAsyncRunning()).isTrue();
            assertThat(result.metadata()).containsEntry(NodeExecutionResult.ASYNC_AGENT_TYPE, "guardrail");
            assertThat(result.output()).containsEntry("agentType", "guardrail");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Pending entry registration
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Pending entry registration")
    class PendingEntryRegistration {

        @Test
        @DisplayName("Should register PendingAgent with correlationId, run/node/tenant context")
        void shouldRegisterPendingAgent() {
            AgentNode node = createAsyncNode("agent");

            NodeExecutionResult result = node.execute(context);

            ArgumentCaptor<PendingAgent> captor = ArgumentCaptor.forClass(PendingAgent.class);
            verify(mockPendingAgentRegistry).register(captor.capture());

            PendingAgent pending = captor.getValue();
            assertThat(pending.correlationId())
                .isEqualTo(result.output().get("correlationId"));
            assertThat(pending.runId()).isEqualTo("run-async-1");
            assertThat(pending.nodeId()).isEqualTo("agent:test_node");
            assertThat(pending.nodeLabel()).isEqualTo("Test Agent");
            assertThat(pending.tenantId()).isEqualTo("tenant-async-1");
            assertThat(pending.agentType()).isEqualTo("agent");
            assertThat(pending.itemId()).isEqualTo("item-0");
            assertThat(pending.itemIndex()).isEqualTo(0);
            assertThat(pending.splitItemData()).isNull();  // not in split context
            assertThat(pending.startedAt()).isNotNull();
        }

        @Test
        @DisplayName("Should generate unique correlationId per execution")
        void shouldGenerateUniqueCorrelationId() {
            AgentNode node = createAsyncNode("agent");

            NodeExecutionResult result1 = node.execute(context);
            NodeExecutionResult result2 = node.execute(context);

            String corr1 = (String) result1.output().get("correlationId");
            String corr2 = (String) result2.output().get("correlationId");

            assertThat(corr1).isNotEqualTo(corr2);
        }

        @Test
        @DisplayName("Snapshots resolvedSystemPrompt + resolvedUserPrompt that match the inline buildAgentRequest output")
        void snapshotsMatchInlineSystemAndUserPrompts() {
            // Pinning test: AgentAsyncCompletionService.enrichAgentShape now relies on the
            // PendingAgent snapshot to populate the Agent Performance metric view's SYSTEM
            // and USER messages. The snapshot is built by a private helper inside
            // AgentNode (resolveEffectiveSystemPromptForObservability +
            // resolveEffectiveUserPrompt) that hand-mirrors what buildAgentRequest sends to
            // the LLM on the inline path.
            //
            // If buildAgentRequest is later changed (extra suffix, different fallback,
            // module ordering tweak) without updating the helper, the metric view would
            // silently desync from what the LLM actually saw. This test pins the two
            // outputs together by exercising the SAME agent through the SYNC path AND the
            // ASYNC path on the same context, then asserting that:
            //   AgentExecutionRequestDto.systemPrompt() == PendingAgent.resolvedSystemPrompt()
            //   AgentExecutionRequestDto.prompt()       == PendingAgent.resolvedUserPrompt()
            //
            // Run the SYNC path first to capture what the inline LLM call would receive.
            Agent syncAgent = createAgent("agent");
            AgentNode syncNode = new AgentNode("agent:pin_sync", syncAgent);
            syncNode.acceptServices(ServiceRegistry.builder()
                .agentClient(mockAgentClient)
                .build());
            // asyncQueueEnabled defaults to false → inline path
            when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
                .thenReturn(createSyncSuccessResponse());
            syncNode.execute(context);
            ArgumentCaptor<AgentExecutionRequestDto> reqCaptor =
                ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
            verify(mockAgentClient).executeAgent(reqCaptor.capture());
            AgentExecutionRequestDto inlineRequest = reqCaptor.getValue();

            // Now run the ASYNC path on the same context - captures the snapshot.
            Agent asyncAgent = createAgent("agent");
            AgentNode asyncNode = new AgentNode("agent:pin_async", asyncAgent);
            asyncNode.acceptServices(buildServiceRegistry());
            asyncNode.setAsyncQueueEnabled(true);
            asyncNode.execute(context);

            ArgumentCaptor<PendingAgent> pendingCaptor = ArgumentCaptor.forClass(PendingAgent.class);
            verify(mockPendingAgentRegistry).register(pendingCaptor.capture());
            PendingAgent pending = pendingCaptor.getValue();

            // The snapshot must equal what the inline path sent to the LLM. If this
            // assertion ever fails, buildAgentRequest and the observability helper have
            // drifted apart - fix both together so the metric view stays accurate.
            assertThat(pending.resolvedSystemPrompt())
                .as("Async snapshot systemPrompt must equal what the inline path sends to the LLM")
                .isEqualTo(inlineRequest.systemPrompt());
            assertThat(pending.resolvedUserPrompt())
                .as("Async snapshot userPrompt must equal what the inline path sends to the LLM")
                .isEqualTo(inlineRequest.prompt());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Sync path preserved
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Sync path when async disabled")
    class SyncPathPreserved {

        @Test
        @DisplayName("Should NOT yield when async queue is disabled (default)")
        void shouldNotYieldWhenAsyncDisabled() {
            Agent agent = createAgent("agent");
            AgentNode node = new AgentNode("agent:sync_node", agent);
            node.acceptServices(ServiceRegistry.builder()
                .agentClient(mockAgentClient)
                .build());
            // asyncQueueEnabled is false by default

            AgentExecutionResponseDto response = createSyncSuccessResponse();

            when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
                .thenReturn(response);

            NodeExecutionResult result = node.execute(context);

            // Should proceed synchronously
            assertThat(result.isAsyncRunning()).isFalse();
            verify(mockAgentClient).executeAgent(any(AgentExecutionRequestDto.class));
            verifyNoInteractions(mockPendingAgentRegistry);
        }

        @Test
        @DisplayName("Should NOT yield when pendingAgentRegistry is null even if flag is true")
        void shouldNotYieldWhenRegistryNull() {
            Agent agent = createAgent("agent");
            AgentNode node = new AgentNode("agent:no_registry", agent);
            node.acceptServices(ServiceRegistry.builder()
                .agentClient(mockAgentClient)
                .build());
            node.setAsyncQueueEnabled(true); // Flag enabled but no registry

            AgentExecutionResponseDto response = createSyncSuccessResponse();

            when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
                .thenReturn(response);

            NodeExecutionResult result = node.execute(context);

            assertThat(result.isAsyncRunning()).isFalse();
            verify(mockAgentClient).executeAgent(any(AgentExecutionRequestDto.class));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Queue message
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Queue message output")
    class QueueMessageOutput {

        @Test
        @DisplayName("Should include provider and model in output")
        void shouldIncludeProviderAndModelInOutput() {
            AgentNode node = createAsyncNode("agent");

            NodeExecutionResult result = node.execute(context);

            assertThat(result.output()).containsEntry("provider", "openai");
            assertThat(result.output()).containsEntry("model", "gpt-4o");
        }

        @Test
        @DisplayName("Should include queueMessage in output for queue producer")
        void shouldIncludeQueueMessageInOutput() {
            AgentNode node = createAsyncNode("agent");
            ExecutionContext orgContext = context.withOrganization("org-async-1", "ADMIN");

            NodeExecutionResult result = node.execute(orgContext);

            assertThat(result.output()).containsKey("queueMessage");
            assertThat(result.output().get("queueMessage")).isInstanceOf(AgentExecutionRequestMessage.class);

            AgentExecutionRequestMessage queueMessage =
                (AgentExecutionRequestMessage) result.output().get("queueMessage");
            assertThat(queueMessage.correlationId()).isEqualTo(result.output().get("correlationId"));
            assertThat(queueMessage.runId()).isEqualTo("run-async-1");
            assertThat(queueMessage.nodeId()).isEqualTo("agent:test_node");
            assertThat(queueMessage.tenantId()).isEqualTo("tenant-async-1");
            assertThat(queueMessage.agentType()).isEqualTo("agent");
            assertThat(queueMessage.provider()).isEqualTo("openai");
            assertThat(queueMessage.model()).isEqualTo("gpt-4o");
            assertThat(queueMessage.requestPayload())
                .containsEntry("organizationId", "org-async-1")
                .containsEntry("organizationRole", "ADMIN")
                .containsEntry("source", "WORKFLOW");
            @SuppressWarnings("unchecked")
            Map<String, Object> credentials = (Map<String, Object>) queueMessage.requestPayload().get("credentials");
            assertThat(credentials)
                .containsEntry("__orgId__", "org-async-1")
                .containsEntry("__orgRole__", "ADMIN");
        }

        @Test
        @DisplayName("Async queue carries the per-agent inactivity window as __inactivityTimeoutSeconds__ (V372 - the DEFAULT prod path, multi-pod producer)")
        void asyncQueueCarriesInactivityCredential() {
            AgentNode node = createAsyncNode("agent");
            node.setRuntimeOverrides(new AgentRuntimeOverrides(null, null, null, null, 300));

            NodeExecutionResult result = node.execute(context.withOrganization("org-async-1", "ADMIN"));

            AgentExecutionRequestMessage queueMessage =
                (AgentExecutionRequestMessage) result.output().get("queueMessage");
            @SuppressWarnings("unchecked")
            Map<String, Object> credentials = (Map<String, Object>) queueMessage.requestPayload().get("credentials");
            // The override MUST ride INSIDE the queue message so whichever worker pod dequeues
            // resolves the SAME window (multi-pod correctness, the property SplitContextManager
            // lacked). The sync path is pinned by AgentNodeRuntimeOverridesTest; this pins the
            // async/queue put-site (AgentNode.executeAgentAsyncQueue), which had no coverage.
            assertThat(credentials).containsEntry("__inactivityTimeoutSeconds__", 300);
        }

        @Test
        @DisplayName("Async queue carries inactivityTimeout=0 (disabled) VERBATIM - 0 must not be dropped as falsy")
        void asyncQueueCarriesZeroInactivityVerbatim() {
            AgentNode node = createAsyncNode("agent");
            node.setRuntimeOverrides(new AgentRuntimeOverrides(null, null, null, null, 0));

            NodeExecutionResult result = node.execute(context.withOrganization("org-async-1", "ADMIN"));

            AgentExecutionRequestMessage queueMessage =
                (AgentExecutionRequestMessage) result.output().get("queueMessage");
            @SuppressWarnings("unchecked")
            Map<String, Object> credentials = (Map<String, Object>) queueMessage.requestPayload().get("credentials");
            // 0 = watchdog disabled; a falsy-drop here would silently re-enable the 5-min
            // default on whichever pod dequeues the run.
            assertThat(credentials).containsEntry("__inactivityTimeoutSeconds__", 0);
        }

        @Test
        @DisplayName("Async queue carries the contract boundary windows 10 and 7200 unchanged (no clamping in the producer)")
        void asyncQueueCarriesBoundaryWindowsUnchanged() {
            for (int boundary : new int[] {10, 7200}) {
                AgentNode node = createAsyncNode("agent");
                node.setRuntimeOverrides(new AgentRuntimeOverrides(null, null, null, null, boundary));

                NodeExecutionResult result = node.execute(context.withOrganization("org-async-1", "ADMIN"));

                AgentExecutionRequestMessage queueMessage =
                    (AgentExecutionRequestMessage) result.output().get("queueMessage");
                @SuppressWarnings("unchecked")
                Map<String, Object> credentials = (Map<String, Object>) queueMessage.requestPayload().get("credentials");
                assertThat(credentials)
                    .as("boundary window %s must ride unchanged in the queue payload", boundary)
                    .containsEntry("__inactivityTimeoutSeconds__", boundary);
            }
        }

        @Test
        @DisplayName("Async queue omits __inactivityTimeoutSeconds__ when there is no per-agent override (platform 5-min default applies)")
        void asyncQueueOmitsInactivityCredentialWhenNull() {
            AgentNode node = createAsyncNode("agent");
            node.setRuntimeOverrides(new AgentRuntimeOverrides(null, null, null, null, null));

            NodeExecutionResult result = node.execute(context.withOrganization("org-async-1", "ADMIN"));

            AgentExecutionRequestMessage queueMessage =
                (AgentExecutionRequestMessage) result.output().get("queueMessage");
            @SuppressWarnings("unchecked")
            Map<String, Object> credentials = (Map<String, Object>) queueMessage.requestPayload().get("credentials");
            assertThat(credentials).doesNotContainKey("__inactivityTimeoutSeconds__");
        }

        @Test
        @DisplayName("Async queue: an UNKNOWN grant ('bogus') → allowedWorkflowIds=[] in the QUEUE payload (deny-by-default on the async path too)")
        void asyncQueueUnknownGrantFailsClosed() {
            // The async worker-queue path builds the SAME credentials (applyToolsConfigCredentials →
            // passAllowedIds) as the inline path. Pin the deny-by-default fail-closed invariant on the
            // queue payload itself, not just the inline AgentExecutionRequestDto.
            Agent agent = new Agent(
                "agent-id", "agent", "Async Grant Agent", "agent-config-bogus",
                null, "openai", "gpt-4o", "system",
                "Process", 0.7, 4096, 10, 5,
                List.of(), null, Map.of(), List.of(), null, List.of(), null, null);

            AgentConfigResolver resolver = mock(AgentConfigResolver.class);
            Map<String, Object> toolsConfig = new HashMap<>();
            toolsConfig.put("workflows", List.of("wf-stale")); // a stale list behind a junk grant
            toolsConfig.put("workflowsGrant", "bogus");
            // Match any (entityId, tenant, org) so the bogus config is GUARANTEED to be loaded
            // (a non-matching stub would return null → [] trivially = false positive).
            when(resolver.getToolsConfig(any(), any(), any())).thenReturn(toolsConfig);

            AgentNode node = new AgentNode("agent:async_grant", agent);
            node.acceptServices(ServiceRegistry.builder()
                .agentClient(mockAgentClient)
                .pendingAgentRegistry(mockPendingAgentRegistry)
                .agentConfigResolver(resolver)
                .build());
            node.setAsyncQueueEnabled(true);

            NodeExecutionResult result = node.execute(context.withOrganization("org-async-1", "ADMIN"));

            AgentExecutionRequestMessage queueMessage =
                (AgentExecutionRequestMessage) result.output().get("queueMessage");
            @SuppressWarnings("unchecked")
            Map<String, Object> credentials = (Map<String, Object>) queueMessage.requestPayload().get("credentials");
            // Deny: [] in the QUEUE payload - NOT the stale ['wf-stale'], NOT omitted (unrestricted).
            assertThat(credentials).containsEntry("allowedWorkflowIds", List.of());
        }

        @Test
        @DisplayName("Async queue: enabledModules on the QUEUE payload is scoped by toolsConfig.mode - mode=none drops catalog (guards the async over-billing fix the e2e caught)")
        void asyncQueueScopesEnabledModulesByToolsConfig() {
            // The async worker-queue path builds a SEPARATE flat payload; it once dropped
            // enabledModules entirely → the worker billed the UNFILTERED full core tool set on
            // every iteration regardless of mode (caught only by a live e2e). Pin the SCHEMA
            // scoping on the queue payload itself with a NON-NULL toolsConfig - the sibling
            // mirror test below is a null==null tautology (its registry has no agentConfigResolver).
            Agent agent = new Agent(
                "agent-id", "agent", "Async Scope Agent", "agent-config-scope",
                null, "openai", "gpt-4o", "system",
                "Process", 0.7, 4096, 10, 5,
                List.of(), null, Map.of(), List.of(), null, List.of(), null, null);

            AgentConfigResolver resolver = mock(AgentConfigResolver.class);
            Map<String, Object> toolsConfig = new HashMap<>();
            toolsConfig.put("mode", "none"); // catalog/MCP blocked, internal modules kept
            when(resolver.getToolsConfig(any(), any(), any())).thenReturn(toolsConfig);

            AgentNode node = new AgentNode("agent:async_scope", agent);
            node.acceptServices(ServiceRegistry.builder()
                .agentClient(mockAgentClient)
                .pendingAgentRegistry(mockPendingAgentRegistry)
                .agentConfigResolver(resolver)
                .build());
            node.setAsyncQueueEnabled(true);

            NodeExecutionResult result = node.execute(context.withOrganization("org-async-1", "ADMIN"));

            AgentExecutionRequestMessage queueMessage =
                (AgentExecutionRequestMessage) result.output().get("queueMessage");
            @SuppressWarnings("unchecked")
            List<String> enabledModules = (List<String>) queueMessage.requestPayload().get("enabledModules");
            // mode=none → internal modules kept, catalog dropped. Removing AgentNode's async
            // requestPayload.put("enabledModules", …) makes this null → reddens this test.
            assertThat(enabledModules)
                .as("async queue payload must scope core tool schemas by mode (catalog dropped for mode=none)")
                .isNotNull()
                .contains("table")
                .doesNotContain("catalog");
        }

        @Test
        @DisplayName("Async queue: a mode=off agent puts an EMPTY enabledModules on the payload (0 tools - the no-tools judge), present-but-empty, never null/omitted")
        void asyncQueueModeOffPutsEmptyEnabledModules() {
            Agent agent = new Agent(
                "agent-id", "agent", "Async Off Agent", "agent-config-off",
                null, "openai", "gpt-4o", "system",
                "Process", 0.7, 4096, 10, 5,
                List.of(), null, Map.of(), List.of(), null, List.of(), null, null);

            AgentConfigResolver resolver = mock(AgentConfigResolver.class);
            Map<String, Object> toolsConfig = new HashMap<>();
            toolsConfig.put("mode", "off"); // NO tools at all
            when(resolver.getToolsConfig(any(), any(), any())).thenReturn(toolsConfig);

            AgentNode node = new AgentNode("agent:async_off", agent);
            node.acceptServices(ServiceRegistry.builder()
                .agentClient(mockAgentClient)
                .pendingAgentRegistry(mockPendingAgentRegistry)
                .agentConfigResolver(resolver)
                .build());
            node.setAsyncQueueEnabled(true);

            NodeExecutionResult result = node.execute(context.withOrganization("org-async-1", "ADMIN"));

            AgentExecutionRequestMessage queueMessage =
                (AgentExecutionRequestMessage) result.output().get("queueMessage");
            @SuppressWarnings("unchecked")
            List<String> enabledModules = (List<String>) queueMessage.requestPayload().get("enabledModules");
            // mode=off → empty module set. Must be PRESENT-but-empty (the worker then advertises 0
            // tools); a null/omitted enabledModules would make CliAgentService treat it as
            // unrestricted (all tools) on the bridge - the exact over-billing this guards against.
            assertThat(enabledModules)
                .as("mode=off → present-but-empty enabledModules on the queue payload (0 tools)")
                .isNotNull()
                .isEmpty();
        }

        @Test
        @DisplayName("Regression - async workflow agent payload mirrors inline credentials, variables, prompts, and execution id")
        void asyncWorkflowAgentPayloadMirrorsInlineExecutionEnvelope() {
            Agent agent = new Agent(
                "agent-id", "agent", "Envelope Agent", "agent-config-1",
                null, "openai", "gpt-4o", "custom system",
                "Process {{user_input}}", 0.7, 4096, 10, 5,
                List.of("search_invoices"), null,
                Map.of("credentials", Map.of("apiKey", "secret-from-agent")),
                List.of(), null, List.of(), null, null);
            ExecutionContext envelopeContext = context
                .withOrganization("org-envelope", "ADMIN")
                .withStepOutput("mcp:lookup", Map.of("output", Map.of("invoiceId", "inv-42")));

            AgentNode syncNode = new AgentNode("agent:sync_envelope", agent);
            syncNode.acceptServices(ServiceRegistry.builder()
                .agentClient(mockAgentClient)
                .build());
            when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
                .thenReturn(createSyncSuccessResponse());
            syncNode.execute(envelopeContext);
            ArgumentCaptor<AgentExecutionRequestDto> syncCaptor =
                ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
            verify(mockAgentClient).executeAgent(syncCaptor.capture());
            AgentExecutionRequestDto inlineRequest = syncCaptor.getValue();

            AgentNode asyncNode = new AgentNode("agent:async_envelope", agent);
            asyncNode.acceptServices(buildServiceRegistry());
            asyncNode.setAsyncQueueEnabled(true);
            NodeExecutionResult result = asyncNode.execute(envelopeContext);

            AgentExecutionRequestMessage queueMessage =
                (AgentExecutionRequestMessage) result.output().get("queueMessage");
            Map<String, Object> payload = queueMessage.requestPayload();

            assertThat(payload)
                .containsEntry("source", "WORKFLOW")
                .containsEntry("prompt", inlineRequest.prompt())
                .containsEntry("systemPrompt", inlineRequest.systemPrompt())
                .containsEntry("autoDiscoverTools", inlineRequest.autoDiscoverTools())
                .containsEntry("maxTools", inlineRequest.maxTools());
            // The async queue payload MUST carry the same enabledModules the inline DTO does -
            // otherwise the worker deserializes enabledModules=null and falls back to the
            // UNFILTERED full core tool set, billing every schema regardless of toolsConfig.mode
            // (the over-billing the live mock e2e CE-WF-TOOLSCOPE-001 caught on the async path).
            assertThat(payload.get("enabledModules"))
                .as("async payload must mirror the inline request's enabledModules")
                .isEqualTo(inlineRequest.enabledModules());
            assertThat(payload.get("executionId")).isInstanceOf(String.class);
            assertThat((String) payload.get("executionId")).isNotBlank();

            @SuppressWarnings("unchecked")
            Map<String, Object> credentials = (Map<String, Object>) payload.get("credentials");
            assertThat(credentials)
                .containsEntry("apiKey", "secret-from-agent")
                .containsEntry("__agentId__", "agent-config-1")
                .containsEntry("__workflowRunId__", "run-async-1")
                .containsEntry("__orgId__", "org-envelope")
                .containsEntry("__orgRole__", "ADMIN")
                .containsEntry("__executionId__", payload.get("executionId"));

            @SuppressWarnings("unchecked")
            Map<String, Object> variables = (Map<String, Object>) payload.get("variables");
            assertThat(variables).isEqualTo(inlineRequest.variables());
            assertThat(variables)
                .containsEntry("trigger", Map.of("user_input", "Analyze this data"))
                .containsEntry("__requestedTools", List.of("search_invoices"));
            assertThat(variables).containsKey("mcp:lookup");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Split context - async still applies, splitItemData is snapshotted
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Split context handling")
    class SplitContextHandling {

        @Test
        @DisplayName("Should yield asyncRunning AND snapshot split context for restoration")
        void shouldSnapshotSplitContext() {
            AgentNode node = createAsyncNode("agent");

            // Mimic SplitAwareNodeExecutor - current_split_id, items, index injected as global data
            ExecutionContext splitContext = context
                .withGlobalData("current_split_id", "core:split_messages:0")
                .withGlobalData("items", List.of("a", "b", "c"))
                .withGlobalData("index", 1);

            NodeExecutionResult result = node.execute(splitContext);

            // Async path is still taken - completion service handles split restoration on the result side
            assertThat(result.isAsyncRunning()).isTrue();
            verify(mockAgentClient, never()).executeAgent(any(AgentExecutionRequestDto.class));

            // The PendingAgent entry should carry the split snapshot
            ArgumentCaptor<PendingAgent> captor = ArgumentCaptor.forClass(PendingAgent.class);
            verify(mockPendingAgentRegistry).register(captor.capture());
            PendingAgent pending = captor.getValue();

            assertThat(pending.splitItemData()).isNotNull();
            // splitNodeId is the BASE node id (not the scoped form) - needed for SplitContextManager lookup
            assertThat(pending.splitItemData())
                .containsEntry("splitNodeId", "core:split_messages")
                .containsEntry("itemIndex", 1)
                // workflowItemIndex is parsed from the scoped key suffix ("core:split_messages:0" -> 0)
                // so AgentAsyncCompletionService can re-inject the sealed batch into SplitContext.
                .containsEntry("workflowItemIndex", 0);
            assertThat(pending.splitItemData().get("items")).isEqualTo(List.of("a", "b", "c"));
        }

        @Test
        @DisplayName("Should leave splitItemData null when NOT in split context")
        void shouldNotSnapshotWhenNotInSplit() {
            AgentNode node = createAsyncNode("agent");

            node.execute(context);

            ArgumentCaptor<PendingAgent> captor = ArgumentCaptor.forClass(PendingAgent.class);
            verify(mockPendingAgentRegistry).register(captor.capture());
            assertThat(captor.getValue().splitItemData()).isNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Conversation persistence at enqueue - regression for the "Smart Assistant
    // never writes in its conversation" prod incident (2026-05-01). Without this
    // wiring, executeAgentAsyncQueue yields BEFORE saveAssistantResponse runs and
    // the agent's conversation row stays empty no matter how many times the
    // workflow fires.
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Conversation persistence at enqueue")
    class ConversationAtEnqueue {

        @org.mockito.Mock
        private com.apimarketplace.orchestrator.services.agent.AgentConversationManager mockConversationManager;

        private Agent agentWithConfigId(String agentType) {
            // The 4th positional arg of Agent is agentConfigId (UUID of the AgentEntity).
            // The default helper createAgent uses null there - we set it explicitly so
            // ensureConversation receives the value the inline path would forward.
            return new Agent(
                "agent-id", agentType, "Smart Assistant", "agent-config-1",
                null, "openai", "gpt-4o", "system", "Analyze this",
                0.7, 4096, 10, 5,
                List.of(), null, Map.of(),
                List.of(), null,
                List.of(), null, null);
        }

        private AgentNode buildNodeWithConversation(String agentType) {
            org.mockito.MockitoAnnotations.openMocks(this);
            Agent agent = agentWithConfigId(agentType);
            AgentNode node = new AgentNode("agent:smart_assistant", agent);
            node.acceptServices(com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry.builder()
                .agentClient(mockAgentClient)
                .pendingAgentRegistry(mockPendingAgentRegistry)
                .agentConversationManager(mockConversationManager)
                .build());
            node.setAsyncQueueEnabled(true);
            return node;
        }

        @Test
        @DisplayName("Regression (org bleed) - agent conversation is created with the run OWNER org, never the ambient thread org")
        void ensureConversationReceivesRunOwnerOrgNotAmbient() {
            AgentNode node = buildNodeWithConversation("agent");
            when(mockConversationManager.ensureConversation(
                any(), any(), any(), any())).thenReturn("conv-owner-org");
            when(mockConversationManager.startExecution(
                any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new com.apimarketplace.orchestrator.services.agent.AgentConversationManager.StreamSession(
                    "conv-owner-org", "stream-owner"));

            // The run is owned by org "org-owner-7". Before the fix, ensureConversation was called
            // org-less (3-arg) and the conversation row was stamped from whatever org happened to be
            // ambient on the worker thread - a cross-tenant bleed. It must now receive the resolved
            // run OWNER org so the conversation row can never be stamped from another tenant.
            node.execute(context.withOrganization("org-owner-7", "ADMIN"));

            verify(mockConversationManager).ensureConversation(
                org.mockito.ArgumentMatchers.eq("agent-config-1"),
                org.mockito.ArgumentMatchers.eq("tenant-async-1"),
                org.mockito.ArgumentMatchers.eq("Smart Assistant"),
                org.mockito.ArgumentMatchers.eq("org-owner-7"));
        }

        @Test
        @DisplayName("Regression (org bleed) - with no context org, the conversation org falls back to the run record, never ambient")
        void ensureConversationFallsBackToRunRecordOrgNotAmbient() {
            org.mockito.MockitoAnnotations.openMocks(this);
            com.apimarketplace.orchestrator.repository.WorkflowRunRepository mockRunRepo =
                mock(com.apimarketplace.orchestrator.repository.WorkflowRunRepository.class);
            com.apimarketplace.orchestrator.domain.WorkflowRunEntity run =
                mock(com.apimarketplace.orchestrator.domain.WorkflowRunEntity.class);
            when(run.getOrgId()).thenReturn("org-from-run-record");
            java.util.UUID runUuid = java.util.UUID.fromString("11111111-1111-1111-1111-111111111111");
            when(mockRunRepo.findById(runUuid)).thenReturn(java.util.Optional.of(run));

            Agent agent = agentWithConfigId("agent");
            AgentNode node = new AgentNode("agent:smart_assistant", agent);
            node.acceptServices(com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry.builder()
                .agentClient(mockAgentClient)
                .pendingAgentRegistry(mockPendingAgentRegistry)
                .agentConversationManager(mockConversationManager)
                .workflowRunRepository(mockRunRepo)
                .build());
            node.setAsyncQueueEnabled(true);

            when(mockConversationManager.ensureConversation(any(), any(), any(), any()))
                .thenReturn("conv-fallback");
            when(mockConversationManager.startExecution(
                any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new com.apimarketplace.orchestrator.services.agent.AgentConversationManager.StreamSession(
                    "conv-fallback", "stream-fb"));

            // Context carries NO org (no withOrganization), but the workflowRunId IS a real UUID,
            // so resolveOrgId must recover the OWNER org from the WorkflowRunEntity rather than
            // leaving the conversation to be stamped from the ambient thread context.
            ExecutionContext noOrgCtx = ExecutionContext.create(
                "run-fb", runUuid.toString(), "tenant-async-1", "item-0", 0,
                Map.of("user_input", "Analyze"), mockPlan);

            node.execute(noOrgCtx);

            verify(mockConversationManager).ensureConversation(
                org.mockito.ArgumentMatchers.eq("agent-config-1"),
                org.mockito.ArgumentMatchers.eq("tenant-async-1"),
                org.mockito.ArgumentMatchers.eq("Smart Assistant"),
                org.mockito.ArgumentMatchers.eq("org-from-run-record"));
        }

        @Test
        @DisplayName("Regression - agent type: ensures the conversation, saves the user prompt, and starts a stream BEFORE yielding")
        void savesUserPromptAndStartsStreamForAgent() {
            AgentNode node = buildNodeWithConversation("agent");
            when(mockConversationManager.ensureConversation(
                any(), any(), any(), any())).thenReturn("conv-7730cebb");
            when(mockConversationManager.startExecution(
                any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new com.apimarketplace.orchestrator.services.agent.AgentConversationManager.StreamSession(
                    "conv-7730cebb", "stream-xyz"));

            NodeExecutionResult result = node.execute(context);

            assertThat(result.isAsyncRunning()).isTrue();
            // The conversation must be resolved at enqueue time (not at delivery) so the
            // panel shows the user prompt immediately, just like the inline path.
            verify(mockConversationManager).ensureConversation(
                org.mockito.ArgumentMatchers.eq("agent-config-1"),
                org.mockito.ArgumentMatchers.eq("tenant-async-1"),
                org.mockito.ArgumentMatchers.eq("Smart Assistant"),
                any());
            // skipUserPrompt is false here because no chat-trigger conversationId is in
            // triggerData - the helper must persist the resolved prompt.
            verify(mockConversationManager).startExecution(
                org.mockito.ArgumentMatchers.eq("conv-7730cebb"),
                any(),
                org.mockito.ArgumentMatchers.eq("tenant-async-1"),
                any(),
                org.mockito.ArgumentMatchers.eq("gpt-4o"),
                org.mockito.ArgumentMatchers.eq(false));

            // PendingAgent must carry conversationId/streamId/executionId/model so the
            // delivery path can call saveAssistantResponse against the same conversation.
            ArgumentCaptor<PendingAgent> captor = ArgumentCaptor.forClass(PendingAgent.class);
            verify(mockPendingAgentRegistry).register(captor.capture());
            PendingAgent pending = captor.getValue();
            assertThat(pending.conversationId()).isEqualTo("conv-7730cebb");
            assertThat(pending.streamId()).isEqualTo("stream-xyz");
            assertThat(pending.executionId()).isNotBlank();
            assertThat(pending.model()).isEqualTo("gpt-4o");
        }

        @Test
        @DisplayName("Regression - async agent payload carries conversationId so the bridge streams tool events live to the conversation panel (prod 'stuck in thinking' fix)")
        void asyncAgentPayloadCarriesConversationIdForBridge() {
            // Root cause of the prod report: with scaling.agent.queue.enabled=true (the
            // default), a workflow agent runs through executeAgentAsyncQueue, whose payload
            // dropped conversationId. The bridge (RedisPublisher) ignores streamingFormat and
            // publishes tool_call/tool_result/content to ws:conversation:{conversationId} ONLY
            // when conversationId is present - so the node's conversation panel (its bottom
            // button) sat in "thinking" with no live tool cards until the final DB reload. The
            // inline path (executeAgentRemotely) already forwards it; the async payload now must
            // too, so async bridge runs stream live exactly like inline runs.
            AgentNode node = buildNodeWithConversation("agent");
            when(mockConversationManager.ensureConversation(
                any(), any(), any(), any())).thenReturn("conv-7730cebb");
            when(mockConversationManager.startExecution(
                any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new com.apimarketplace.orchestrator.services.agent.AgentConversationManager.StreamSession(
                    "conv-7730cebb", "stream-xyz"));

            NodeExecutionResult result = node.execute(context);

            com.apimarketplace.agent.client.queue.AgentExecutionRequestMessage queueMessage =
                (com.apimarketplace.agent.client.queue.AgentExecutionRequestMessage) result.output().get("queueMessage");
            assertThat(queueMessage.requestPayload())
                .as("async queue payload must forward conversationId so the worker streams live to the conversation panel")
                .containsEntry("conversationId", "conv-7730cebb")
                // Agents WITH a conversation stream in "conversation" format, mirroring the
                // inline path: the direct-API worker then instantiates the conversation
                // callback (live transcript on ws:conversation + snapshot buffers) instead of
                // the workflow-format one whose onChunk/onThinking are no-ops. The run view
                // does NOT regress: the worker TEES the workflow-envelope callback alongside
                // (TeeStreamingCallback keyed on nodeId/workflowRunId), so both surfaces
                // stream. Pin the pairing so an accidental flip back to the silent-panel
                // "workflow" pin reddens here.
                .containsEntry("streamingFormat", "conversation")
                .containsEntry("nodeId", "agent:smart_assistant")
                .containsEntry("workflowRunId", "run-async-1");

            // Cross-service contract: the worker serializes requestPayload to JSON
            // (AgentQueueProducer) and deserializes it into AgentExecutionRequestDto
            // (AgentRemoteExecutionService.executeByType). Prove the map key "conversationId"
            // actually lands on the DTO field the dispatch reads back - a rename on
            // either side would silently re-open the "stuck in thinking" gap.
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
                String json = mapper.writeValueAsString(queueMessage.requestPayload());
                AgentExecutionRequestDto roundTripped =
                    mapper.readValue(json, AgentExecutionRequestDto.class);
                assertThat(roundTripped.conversationId())
                    .as("conversationId must survive the queue->worker JSON round-trip onto the DTO")
                    .isEqualTo("conv-7730cebb");
                assertThat(roundTripped.streamingFormat()).isEqualTo("conversation");
                assertThat(roundTripped.nodeId())
                    .as("nodeId must survive the round-trip - the worker's tee keys the workflow-envelope callback on it")
                    .isEqualTo("agent:smart_assistant");
                assertThat(roundTripped.workflowRunId()).isEqualTo("run-async-1");
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new AssertionError("requestPayload must round-trip to AgentExecutionRequestDto", e);
            }
        }

        @Test
        @DisplayName("agent WITHOUT a conversation keeps the workflow streaming format (no conversation channel to feed)")
        void asyncAgentWithoutConversationKeepsWorkflowFormat() {
            // When no conversation manager is wired (or conversation resolution fails),
            // there is no ws:conversation channel to stream to - the payload must fall
            // back to the workflow format so the run view still gets its tool envelopes.
            AgentNode node = buildNodeWithConversation("agent");
            when(mockConversationManager.ensureConversation(any(), any(), any(), any())).thenReturn(null);

            NodeExecutionResult result = node.execute(context);

            com.apimarketplace.agent.client.queue.AgentExecutionRequestMessage queueMessage =
                (com.apimarketplace.agent.client.queue.AgentExecutionRequestMessage) result.output().get("queueMessage");
            assertThat(queueMessage.requestPayload())
                .containsEntry("streamingFormat", "workflow")
                .doesNotContainKey("conversationId");
        }

        @Test
        @DisplayName("classify type: no conversationId on the async payload (routing nodes have no user-facing conversation)")
        void classifyAsyncPayloadHasNoConversationId() {
            // Mirror of the fix's guard: conversationId is resolved only for agent type, so a
            // classify routing node must NOT carry it - else the bridge would open a stray
            // conversation stream for a node that has no user-facing conversation.
            AgentNode node = buildNodeWithConversation("classify");

            NodeExecutionResult result = node.execute(context);

            com.apimarketplace.agent.client.queue.AgentExecutionRequestMessage queueMessage =
                (com.apimarketplace.agent.client.queue.AgentExecutionRequestMessage) result.output().get("queueMessage");
            assertThat(queueMessage.requestPayload())
                .as("classify routing nodes write no conversation, so conversationId must be absent")
                .doesNotContainKey("conversationId");
        }

        @Test
        @DisplayName("classify type: skips conversation save (no user-facing conversation for routing nodes)")
        void skipsConversationForClassify() {
            AgentNode node = buildNodeWithConversation("classify");

            node.execute(context);

            verifyNoInteractions(mockConversationManager);
            ArgumentCaptor<PendingAgent> captor = ArgumentCaptor.forClass(PendingAgent.class);
            verify(mockPendingAgentRegistry).register(captor.capture());
            assertThat(captor.getValue().conversationId()).isNull();
            assertThat(captor.getValue().streamId()).isNull();
        }

        @Test
        @DisplayName("guardrail type: also skips conversation save")
        void skipsConversationForGuardrail() {
            AgentNode node = buildNodeWithConversation("guardrail");

            node.execute(context);

            verifyNoInteractions(mockConversationManager);
        }

        @Test
        @DisplayName("Chat trigger conversationId is honored - uses workflow conversation, skipUserPrompt=true")
        void usesChatTriggerConversation() {
            AgentNode node = buildNodeWithConversation("agent");
            when(mockConversationManager.startExecution(
                any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new com.apimarketplace.orchestrator.services.agent.AgentConversationManager.StreamSession(
                    "conv-from-chat", "stream-zzz"));

            // Inject a chat trigger conversationId into triggerData
            Map<String, Object> chatTriggerData = new HashMap<>();
            chatTriggerData.put("conversationId", "conv-from-chat");
            ExecutionContext chatContext = ExecutionContext.create(
                "run-async-1", "workflow-run-async-1", "tenant-async-1", "item-0", 0,
                chatTriggerData, mockPlan);

            node.execute(chatContext);

            // ensureConversation must NOT be called when the trigger already supplies one -
            // matches the inline path's contract (AgentNode.executeAgent line 867).
            verify(mockConversationManager, never()).ensureConversation(any(), any(), any(), any());
            verify(mockConversationManager).startExecution(
                org.mockito.ArgumentMatchers.eq("conv-from-chat"),
                any(),
                any(),
                any(),
                any(),
                org.mockito.ArgumentMatchers.eq(true)); // skipUserPrompt=true: frontend already saved it
        }

        @Test
        @DisplayName("Prompt fallback - when agentConfig.prompt() is null, falls back to inputData['prompt'] (matches inline buildAgentRequest)")
        void promptFallbackUsesInputDataWhenAgentPromptBlank() {
            org.mockito.MockitoAnnotations.openMocks(this);
            // Agent with NO prompt configured but params containing 'prompt' - prepareInput
            // pulls params into inputData, which is the same path the inline buildAgentRequest
            // fallback (AgentNode.java:1438) uses to recover the prompt.
            Agent agent = new Agent(
                "agent-id", "agent", "Smart Assistant", "agent-config-1",
                null, "openai", "gpt-4o", "system", null, // null prompt
                0.7, 4096, 10, 5,
                List.of(), null,
                Map.of("prompt", "Tell me about Paris"),
                List.of(), null,
                List.of(), null, null);
            AgentNode node = new AgentNode("agent:smart_assistant", agent);
            node.acceptServices(com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry.builder()
                .agentClient(mockAgentClient)
                .pendingAgentRegistry(mockPendingAgentRegistry)
                .agentConversationManager(mockConversationManager)
                .build());
            node.setAsyncQueueEnabled(true);

            when(mockConversationManager.ensureConversation(
                any(), any(), any(), any())).thenReturn("conv-x");
            when(mockConversationManager.startExecution(
                any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new com.apimarketplace.orchestrator.services.agent.AgentConversationManager.StreamSession(
                    "conv-x", "stream-x"));

            node.execute(context);

            // The user prompt forwarded to startExecution must be the inputData value,
            // NOT null/blank - otherwise startExecution would silently skip the save
            // (AgentConversationManager.startExecution#113 drops blank prompts) and
            // the conversation would only ever contain the assistant reply.
            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockConversationManager).startExecution(
                org.mockito.ArgumentMatchers.eq("conv-x"),
                promptCaptor.capture(),
                any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(false));
            assertThat(promptCaptor.getValue()).isEqualTo("Tell me about Paris");
        }

        @Test
        @DisplayName("conversationManager unavailable: degrades cleanly while preserving execution id for worker observability")
        void degradesWhenConversationManagerNull() {
            // Build a node without conversationManager wired
            Agent agent = createAgent("agent");
            AgentNode node = new AgentNode("agent:smart_assistant", agent);
            node.acceptServices(com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry.builder()
                .agentClient(mockAgentClient)
                .pendingAgentRegistry(mockPendingAgentRegistry)
                .build());
            node.setAsyncQueueEnabled(true);

            NodeExecutionResult result = node.execute(context);

            assertThat(result.isAsyncRunning()).isTrue();
            ArgumentCaptor<PendingAgent> captor = ArgumentCaptor.forClass(PendingAgent.class);
            verify(mockPendingAgentRegistry).register(captor.capture());
            assertThat(captor.getValue().conversationId()).isNull();
            assertThat(captor.getValue().streamId()).isNull();
            assertThat(captor.getValue().executionId()).isNotBlank();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private AgentExecutionResponseDto createSyncSuccessResponse() {
        return new AgentExecutionResponseDto(
            true,                    // success
            "Agent response content", // finalResponse
            "Agent response content", // content
            List.of(),               // toolResults
            1,                       // iterations
            Map.of(),                // totalUsage
            null,                    // error
            500L,                    // durationMs
            "openai",                // provider
            "gpt-4o",               // model
            List.of(),               // conversationHistory
            "COMPLETED",             // stopReason
            Map.of(),                // metrics
            List.of(),               // usagePerIteration
            List.of(),               // iterationDurations
            List.of(),               // finishReasonsPerIteration
            null,                    // thinkingSections
            null                     // orderedEntries
        , null);
    }
}
