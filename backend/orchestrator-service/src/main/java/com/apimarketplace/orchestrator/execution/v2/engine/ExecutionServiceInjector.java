package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.execution.v2.async.PendingAgentRegistry;
import com.apimarketplace.orchestrator.execution.v2.nodes.AgentNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.services.StepOutputService;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.execution.v2.WorkflowExecutionServiceV2;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.orchestrator.services.agent.AgentConfigResolver;
import com.apimarketplace.orchestrator.services.agent.AgentConversationManager;
import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.orchestrator.services.agent.ConversationEventPublisher;
import com.apimarketplace.orchestrator.services.file.FileDownloader;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.apimarketplace.orchestrator.services.InterfaceRenderService;
import com.apimarketplace.orchestrator.services.interfaces.InterfaceScreenshotService;
import com.apimarketplace.orchestrator.services.interfaces.ToolsGateway;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.utils.file.MimeTypeRegistry;
import com.apimarketplace.orchestrator.services.code.CodeExecutor;
import com.apimarketplace.orchestrator.services.credit.CreditBudgetService;
import com.apimarketplace.orchestrator.tools.websearch.BrowserAgentModule;
import com.apimarketplace.orchestrator.webhook.WebhookResponseRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Injects execution services into nodes.
 * Only toolsGateway and templateAdapter are needed - event emission,
 * persistence, and metrics are handled by V2ExecutionEventService.
 * AgentNodes also get AgentExecutionService.
 */
@Component
public class ExecutionServiceInjector {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionServiceInjector.class);

    private final V2TemplateAdapter templateAdapter;

    @Value("${orchestrator.mock.enabled:true}")
    private boolean mockEnabled;

    @Value("${scaling.agent.queue.enabled:false}")
    private boolean agentQueueEnabled;

    @Autowired(required = false)
    @Qualifier("mockToolsGateway")
    private ToolsGateway mockToolsGateway;

    @Autowired(required = false)
    @Qualifier("catalogToolsGateway")
    private ToolsGateway catalogToolsGateway;

    @Autowired(required = false)
    private FileStorageService fileStorageService;

    @Autowired(required = false)
    private FileDownloader fileDownloader;

    @Autowired(required = false)
    private MimeTypeRegistry mimeTypeRegistry;

    @Autowired(required = false)
    private WorkflowEventPublisher eventPublisher;

    @Autowired(required = false)
    private RestTemplate restTemplate;

    @Autowired(required = false)
    private SplitContextManager splitContextManager;

    @Autowired(required = false)
    private UnifiedSignalService unifiedSignalService;

    @Autowired(required = false)
    private PendingAgentRegistry pendingAgentRegistry;

    @Autowired(required = false)
    private java.time.Clock clock;

    @Autowired(required = false)
    private ConversationClient conversationServiceClient;

    @Autowired(required = false)
    private AgentConfigResolver agentConfigResolver;

    @Autowired(required = false)
    private AgentClient agentClient;

    @Autowired(required = false)
    private ConversationEventPublisher conversationEventPublisher;

    @Autowired(required = false)
    private AgentConversationManager agentConversationManager;

    @Autowired(required = false)
    private WorkflowRunRepository workflowRunRepository;

    @Autowired(required = false)
    private WebhookResponseRegistry webhookResponseRegistry;

    @Autowired(required = false)
    private CodeExecutor codeExecutor;

    @Autowired(required = false)
    private com.apimarketplace.credential.client.CredentialClient credentialClient;

    @Autowired(required = false)
    private CreditBudgetService creditBudgetService;

    @Autowired(required = false)
    private WorkflowRepository workflowRepository;

    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private WorkflowExecutionService workflowExecutionService;

    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private WorkflowExecutionServiceV2 workflowExecutionServiceV2;

    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private ReusableTriggerService reusableTriggerService;

    @Autowired(required = false)
    private StepOutputService stepOutputService;

    @Autowired(required = false)
    private WorkflowStepDataRepository workflowStepDataRepository;

    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.triggers.TriggerUserResolver triggerUserResolver;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    /**
     * Browser-agent runner module - bean is gated on {@code websearch.enabled=true}
     * (see {@link BrowserAgentModule}). Optional so non-websearch profiles still build
     * a complete registry.
     */
    @Autowired(required = false)
    private BrowserAgentModule browserAgentModule;

    /** F2.2 - used by SubWorkflowNode to register parent→child run links so a
     *  parent cancel cascades to in-flight sub-runs. Optional in unit tests. */
    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher workflowRedisPublisher;

    /** Best-effort PNG capture of a rendered interface. Optional: when the renderer sidecar
     *  is not configured ({@code services.screenshot-renderer-url} blank), capture is skipped
     *  and the node continues without a {@code screenshot} output. */
    @Autowired(required = false)
    private InterfaceScreenshotService interfaceScreenshotService;

    /** Resolves the rendered HTML/CSS/JS for an interface - consumed by InterfaceNode when the
     *  {@code exposeRenderedSource} toggle is on. Optional in unit tests; absent → InterfaceNode
     *  treats the toggle as no-op and emits no {@code rendered_*} fields. */
    @Autowired(required = false)
    private InterfaceRenderService interfaceRenderService;

    public ExecutionServiceInjector(V2TemplateAdapter templateAdapter) {
        this.templateAdapter = templateAdapter;
    }

    /**
     * Injects execution services into all nodes in the map.
     * Uses polymorphic service injection via ExecutionNode.acceptServices() interface method.
     *
     * @param nodeMap The map of all nodes to inject services into
     */
    public void injectServices(Map<String, ExecutionNode> nodeMap) {
        ServiceRegistry registry = buildServiceRegistry();
        logger.info("🔧 Using tools gateway: mockEnabled={}, gateway={}",
            mockEnabled, registry.getToolsGateway() != null
                ? registry.getToolsGateway().getClass().getSimpleName() : "null");
        logger.info("🤖 AgentClient available: {}", agentClient != null);

        // Polymorphic service injection - each node pulls the services it needs
        // acceptServices is now in ExecutionNode interface with default no-op
        for (ExecutionNode node : nodeMap.values()) {
            node.acceptServices(registry);
            if (agentQueueEnabled && node instanceof AgentNode agentNode) {
                agentNode.setAsyncQueueEnabled(true);
            }
            logger.debug("🔧 Injected services into node: {}", node.getNodeId());
        }
    }

    /**
     * Builds the ServiceRegistry with all available services.
     */
    private ServiceRegistry buildServiceRegistry() {
        return ServiceRegistry.builder()
            .toolsGateway(resolveActiveToolsGateway())
            .templateAdapter(templateAdapter)
            .fileStorageService(fileStorageService)
            .fileDownloader(fileDownloader)
            .mimeTypeRegistry(mimeTypeRegistry)
            .eventPublisher(eventPublisher)
            .restTemplate(restTemplate)
            .splitContextManager(splitContextManager)
            .signalService(unifiedSignalService)
            .pendingAgentRegistry(pendingAgentRegistry)
            .clock(clock)
            .conversationServiceClient(conversationServiceClient)
            .agentConfigResolver(agentConfigResolver)
            .agentClient(agentClient)
            .conversationEventPublisher(conversationEventPublisher)
            .agentConversationManager(agentConversationManager)
            .workflowRunRepository(workflowRunRepository)
            .webhookResponseRegistry(webhookResponseRegistry)
            .codeExecutor(codeExecutor)
            .credentialClient(credentialClient)
            .creditBudgetService(creditBudgetService)
            .workflowRepository(workflowRepository)
            .workflowExecutionService(workflowExecutionService)
            .workflowExecutionServiceV2(workflowExecutionServiceV2)
            .reusableTriggerService(reusableTriggerService)
            .stepOutputService(stepOutputService)
            .workflowStepDataRepository(workflowStepDataRepository)
            .triggerUserResolver(triggerUserResolver)
            .objectMapper(objectMapper)
            .browserAgentModule(browserAgentModule)
            .workflowRedisPublisher(workflowRedisPublisher)
            .interfaceScreenshotService(interfaceScreenshotService)
            .interfaceRenderService(interfaceRenderService)
            .build();
    }

    /**
     * Selects the active gateway based on configuration.
     */
    private ToolsGateway resolveActiveToolsGateway() {
        if (!mockEnabled && catalogToolsGateway != null) {
            return catalogToolsGateway;
        }
        if (mockEnabled && mockToolsGateway != null) {
            return mockToolsGateway;
        }
        if (mockEnabled && mockToolsGateway == null) {
            return null;
        }
        if (catalogToolsGateway != null) {
            return catalogToolsGateway;
        }
        if (mockToolsGateway != null) {
            return mockToolsGateway;
        }
        return null;
    }
}
