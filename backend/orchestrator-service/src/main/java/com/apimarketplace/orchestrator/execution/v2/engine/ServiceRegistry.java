package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.execution.v2.async.PendingAgentRegistry;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.orchestrator.services.StepOutputService;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.agent.AgentConfigResolver;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.agent.AgentConversationManager;
import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.orchestrator.services.agent.ConversationEventPublisher;
import com.apimarketplace.orchestrator.services.InterfaceRenderService;
import com.apimarketplace.orchestrator.services.file.FileDownloader;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.apimarketplace.orchestrator.services.interfaces.InterfaceScreenshotService;
import com.apimarketplace.orchestrator.services.interfaces.ToolsGateway;
import com.apimarketplace.orchestrator.execution.v2.WorkflowExecutionServiceV2;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.orchestrator.utils.file.MimeTypeRegistry;
import com.apimarketplace.orchestrator.services.code.CodeExecutor;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.orchestrator.services.credit.CreditBudgetService;
import com.apimarketplace.orchestrator.tools.websearch.BrowserAgentModule;
import com.apimarketplace.orchestrator.webhook.WebhookResponseRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;

/**
 * Registry holding all services that can be injected into execution nodes.
 * This enables polymorphic service injection without instanceof checks.
 *
 * <p>Nodes implement {@link com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode#acceptServices(ServiceRegistry)}
 * and pull the services they need from this registry.
 */
public class ServiceRegistry {

    private final ToolsGateway toolsGateway;
    private final V2TemplateAdapter templateAdapter;
    private final FileStorageService fileStorageService;
    private final FileDownloader fileDownloader;
    private final MimeTypeRegistry mimeTypeRegistry;
    private final WorkflowEventPublisher eventPublisher;
    private final RestTemplate restTemplate;
    private final SplitContextManager splitContextManager;
    private final UnifiedSignalService signalService;
    private final PendingAgentRegistry pendingAgentRegistry;
    private final Clock clock;
    private final ConversationClient conversationServiceClient;
    private final AgentConfigResolver agentConfigResolver;
    private final AgentClient agentClient;
    private final ConversationEventPublisher conversationEventPublisher;
    private final AgentConversationManager agentConversationManager;
    private final WorkflowRunRepository workflowRunRepository;
    private final WebhookResponseRegistry webhookResponseRegistry;
    private final CodeExecutor codeExecutor;
    private final CredentialClient credentialClient;
    private final CreditBudgetService creditBudgetService;
    private final WorkflowRepository workflowRepository;
    private final WorkflowExecutionService workflowExecutionService;
    private final WorkflowExecutionServiceV2 workflowExecutionServiceV2;
    private final ReusableTriggerService reusableTriggerService;
    private final StepOutputService stepOutputService;
    private final WorkflowStepDataRepository workflowStepDataRepository;
    private final com.apimarketplace.orchestrator.services.triggers.TriggerUserResolver triggerUserResolver;
    private final ObjectMapper objectMapper;
    private final BrowserAgentModule browserAgentModule;
    private final com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher workflowRedisPublisher;
    private final InterfaceScreenshotService interfaceScreenshotService;
    private final InterfaceRenderService interfaceRenderService;
    private final com.apimarketplace.orchestrator.tools.websearch.CloudBrowserAgentRelayClient cloudBrowserAgentRelayClient;
    private final com.apimarketplace.agent.cloud.CloudLlmRuntimeAccess cloudLlmRuntimeAccess;

    private ServiceRegistry(Builder builder) {
        this.toolsGateway = builder.toolsGateway;
        this.templateAdapter = builder.templateAdapter;
        this.fileStorageService = builder.fileStorageService;
        this.fileDownloader = builder.fileDownloader;
        this.mimeTypeRegistry = builder.mimeTypeRegistry;
        this.eventPublisher = builder.eventPublisher;
        this.restTemplate = builder.restTemplate;
        this.splitContextManager = builder.splitContextManager;
        this.signalService = builder.signalService;
        this.pendingAgentRegistry = builder.pendingAgentRegistry;
        this.clock = builder.clock;
        this.conversationServiceClient = builder.conversationServiceClient;
        this.agentConfigResolver = builder.agentConfigResolver;
        this.agentClient = builder.agentClient;
        this.conversationEventPublisher = builder.conversationEventPublisher;
        this.agentConversationManager = builder.agentConversationManager;
        this.workflowRunRepository = builder.workflowRunRepository;
        this.webhookResponseRegistry = builder.webhookResponseRegistry;
        this.codeExecutor = builder.codeExecutor;
        this.credentialClient = builder.credentialClient;
        this.creditBudgetService = builder.creditBudgetService;
        this.workflowRepository = builder.workflowRepository;
        this.workflowExecutionService = builder.workflowExecutionService;
        this.workflowExecutionServiceV2 = builder.workflowExecutionServiceV2;
        this.reusableTriggerService = builder.reusableTriggerService;
        this.stepOutputService = builder.stepOutputService;
        this.workflowStepDataRepository = builder.workflowStepDataRepository;
        this.triggerUserResolver = builder.triggerUserResolver;
        this.objectMapper = builder.objectMapper;
        this.browserAgentModule = builder.browserAgentModule;
        this.workflowRedisPublisher = builder.workflowRedisPublisher;
        this.interfaceScreenshotService = builder.interfaceScreenshotService;
        this.interfaceRenderService = builder.interfaceRenderService;
        this.cloudBrowserAgentRelayClient = builder.cloudBrowserAgentRelayClient;
        this.cloudLlmRuntimeAccess = builder.cloudLlmRuntimeAccess;
    }

    public com.apimarketplace.orchestrator.services.triggers.TriggerUserResolver getTriggerUserResolver() {
        return triggerUserResolver;
    }

    // Getters for each service - nodes pull what they need

    public ToolsGateway getToolsGateway() {
        return toolsGateway;
    }

    public V2TemplateAdapter getTemplateAdapter() {
        return templateAdapter;
    }

    public FileStorageService getFileStorageService() {
        return fileStorageService;
    }

    public FileDownloader getFileDownloader() {
        return fileDownloader;
    }

    public MimeTypeRegistry getMimeTypeRegistry() {
        return mimeTypeRegistry;
    }

    public WorkflowEventPublisher getEventPublisher() {
        return eventPublisher;
    }

    public RestTemplate getRestTemplate() {
        return restTemplate;
    }

    public SplitContextManager getSplitContextManager() {
        return splitContextManager;
    }

    public UnifiedSignalService getSignalService() {
        return signalService;
    }

    public PendingAgentRegistry getPendingAgentRegistry() {
        return pendingAgentRegistry;
    }

    public Clock getClock() {
        return clock;
    }

    public ConversationClient getConversationClient() {
        return conversationServiceClient;
    }

    public AgentConfigResolver getAgentConfigResolver() {
        return agentConfigResolver;
    }

    public AgentClient getAgentClient() {
        return agentClient;
    }

    public ConversationEventPublisher getConversationEventPublisher() {
        return conversationEventPublisher;
    }

    public AgentConversationManager getAgentConversationManager() {
        return agentConversationManager;
    }

    public WorkflowRunRepository getWorkflowRunRepository() {
        return workflowRunRepository;
    }

    public WebhookResponseRegistry getWebhookResponseRegistry() {
        return webhookResponseRegistry;
    }

    public CredentialClient getCredentialClient() {
        return credentialClient;
    }

    public CodeExecutor getCodeExecutor() {
        return codeExecutor;
    }

    public CreditBudgetService getCreditBudgetService() {
        return creditBudgetService;
    }

    public WorkflowRepository getWorkflowRepository() {
        return workflowRepository;
    }

    public WorkflowExecutionService getWorkflowExecutionService() {
        return workflowExecutionService;
    }

    public WorkflowExecutionServiceV2 getWorkflowExecutionServiceV2() {
        return workflowExecutionServiceV2;
    }

    public ReusableTriggerService getReusableTriggerService() {
        return reusableTriggerService;
    }

    public StepOutputService getStepOutputService() {
        return stepOutputService;
    }

    public WorkflowStepDataRepository getWorkflowStepDataRepository() {
        return workflowStepDataRepository;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    public BrowserAgentModule getBrowserAgentModule() {
        return browserAgentModule;
    }

    /**
     * CE→cloud browser-agent relay client. Present only in a CE deployment
     * ({@code websearch.enabled=false}); null in cloud where the local
     * {@link BrowserAgentModule} runs. Lets {@link com.apimarketplace.orchestrator.execution.v2.nodes.BrowserAgentNode}
     * relay a browse when the local module is absent but the install is cloud-linked.
     */
    public com.apimarketplace.orchestrator.tools.websearch.CloudBrowserAgentRelayClient getCloudBrowserAgentRelayClient() {
        return cloudBrowserAgentRelayClient;
    }

    /**
     * Resolves the effective CE LLM source + cloud-link credentials for a tenant.
     * Present only where the CE cloud-link wiring exists (marketplace.mode=remote);
     * null otherwise. Used with {@link #getCloudBrowserAgentRelayClient()} to gate the
     * browser-agent relay per tenant at runtime.
     */
    public com.apimarketplace.agent.cloud.CloudLlmRuntimeAccess getCloudLlmRuntimeAccess() {
        return cloudLlmRuntimeAccess;
    }

    /** F2.2 - used by SubWorkflowNode to register parent→child run links so a parent
     *  cancel cascades down to in-flight sub-runs. May be {@code null} in unit tests. */
    public com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher getWorkflowRedisPublisher() {
        return workflowRedisPublisher;
    }

    /** Best-effort PNG capture of a rendered interface. May be null in tests / when the screenshot
     *  sidecar is not configured - nodes must null-guard and treat absence as "no capture". */
    public InterfaceScreenshotService getInterfaceScreenshotService() {
        return interfaceScreenshotService;
    }

    /** Resolves the rendered HTML/CSS/JS templates for an interface - consumed by InterfaceNode
     *  when the {@code exposeRenderedSource} toggle is on. May be null in unit tests; nodes must
     *  null-guard and treat absence as "no exposure". */
    public InterfaceRenderService getInterfaceRenderService() {
        return interfaceRenderService;
    }

    // Builder pattern for construction

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private ToolsGateway toolsGateway;
        private V2TemplateAdapter templateAdapter;
        private FileStorageService fileStorageService;
        private FileDownloader fileDownloader;
        private MimeTypeRegistry mimeTypeRegistry;
        private WorkflowEventPublisher eventPublisher;
        private RestTemplate restTemplate;
        private SplitContextManager splitContextManager;
        private UnifiedSignalService signalService;
        private PendingAgentRegistry pendingAgentRegistry;
        private Clock clock;
        private ConversationClient conversationServiceClient;
        private AgentConfigResolver agentConfigResolver;
        private AgentClient agentClient;
        private ConversationEventPublisher conversationEventPublisher;
        private AgentConversationManager agentConversationManager;
        private WorkflowRunRepository workflowRunRepository;
        private WebhookResponseRegistry webhookResponseRegistry;
        private CodeExecutor codeExecutor;
        private CredentialClient credentialClient;
        private CreditBudgetService creditBudgetService;
        private WorkflowRepository workflowRepository;
        private WorkflowExecutionService workflowExecutionService;
        private WorkflowExecutionServiceV2 workflowExecutionServiceV2;
        private ReusableTriggerService reusableTriggerService;
        private StepOutputService stepOutputService;
        private WorkflowStepDataRepository workflowStepDataRepository;
        private com.apimarketplace.orchestrator.services.triggers.TriggerUserResolver triggerUserResolver;
        private ObjectMapper objectMapper;
        private BrowserAgentModule browserAgentModule;
        private com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher workflowRedisPublisher;
        private InterfaceScreenshotService interfaceScreenshotService;
        private InterfaceRenderService interfaceRenderService;
        private com.apimarketplace.orchestrator.tools.websearch.CloudBrowserAgentRelayClient cloudBrowserAgentRelayClient;
        private com.apimarketplace.agent.cloud.CloudLlmRuntimeAccess cloudLlmRuntimeAccess;

        public Builder toolsGateway(ToolsGateway toolsGateway) {
            this.toolsGateway = toolsGateway;
            return this;
        }

        public Builder templateAdapter(V2TemplateAdapter templateAdapter) {
            this.templateAdapter = templateAdapter;
            return this;
        }

        public Builder fileStorageService(FileStorageService service) {
            this.fileStorageService = service;
            return this;
        }

        public Builder fileDownloader(FileDownloader downloader) {
            this.fileDownloader = downloader;
            return this;
        }

        public Builder mimeTypeRegistry(MimeTypeRegistry registry) {
            this.mimeTypeRegistry = registry;
            return this;
        }

        public Builder eventPublisher(WorkflowEventPublisher publisher) {
            this.eventPublisher = publisher;
            return this;
        }

        public Builder restTemplate(RestTemplate restTemplate) {
            this.restTemplate = restTemplate;
            return this;
        }

        public Builder splitContextManager(SplitContextManager manager) {
            this.splitContextManager = manager;
            return this;
        }

        public Builder signalService(UnifiedSignalService service) {
            this.signalService = service;
            return this;
        }

        public Builder pendingAgentRegistry(PendingAgentRegistry registry) {
            this.pendingAgentRegistry = registry;
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public Builder conversationServiceClient(ConversationClient client) {
            this.conversationServiceClient = client;
            return this;
        }

        public Builder agentConfigResolver(AgentConfigResolver resolver) {
            this.agentConfigResolver = resolver;
            return this;
        }

        public Builder agentClient(AgentClient client) {
            this.agentClient = client;
            return this;
        }

        public Builder conversationEventPublisher(ConversationEventPublisher publisher) {
            this.conversationEventPublisher = publisher;
            return this;
        }

        public Builder agentConversationManager(AgentConversationManager manager) {
            this.agentConversationManager = manager;
            return this;
        }

        public Builder workflowRunRepository(WorkflowRunRepository repository) {
            this.workflowRunRepository = repository;
            return this;
        }

        public Builder webhookResponseRegistry(WebhookResponseRegistry registry) {
            this.webhookResponseRegistry = registry;
            return this;
        }

        public Builder codeExecutor(CodeExecutor client) {
            this.codeExecutor = client;
            return this;
        }

        public Builder credentialClient(CredentialClient client) {
            this.credentialClient = client;
            return this;
        }

        public Builder creditBudgetService(CreditBudgetService service) {
            this.creditBudgetService = service;
            return this;
        }

        public Builder workflowRepository(WorkflowRepository repository) {
            this.workflowRepository = repository;
            return this;
        }

        public Builder workflowExecutionService(WorkflowExecutionService service) {
            this.workflowExecutionService = service;
            return this;
        }

        public Builder workflowExecutionServiceV2(WorkflowExecutionServiceV2 service) {
            this.workflowExecutionServiceV2 = service;
            return this;
        }

        public Builder reusableTriggerService(ReusableTriggerService service) {
            this.reusableTriggerService = service;
            return this;
        }

        public Builder stepOutputService(StepOutputService service) {
            this.stepOutputService = service;
            return this;
        }

        public Builder workflowStepDataRepository(WorkflowStepDataRepository repository) {
            this.workflowStepDataRepository = repository;
            return this;
        }

        public Builder objectMapper(ObjectMapper mapper) {
            this.objectMapper = mapper;
            return this;
        }

        public Builder triggerUserResolver(com.apimarketplace.orchestrator.services.triggers.TriggerUserResolver resolver) {
            this.triggerUserResolver = resolver;
            return this;
        }

        public Builder browserAgentModule(BrowserAgentModule module) {
            this.browserAgentModule = module;
            return this;
        }

        public Builder workflowRedisPublisher(
                com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher publisher) {
            this.workflowRedisPublisher = publisher;
            return this;
        }

        public Builder interfaceScreenshotService(InterfaceScreenshotService service) {
            this.interfaceScreenshotService = service;
            return this;
        }

        public Builder interfaceRenderService(InterfaceRenderService service) {
            this.interfaceRenderService = service;
            return this;
        }

        public Builder cloudBrowserAgentRelayClient(
                com.apimarketplace.orchestrator.tools.websearch.CloudBrowserAgentRelayClient client) {
            this.cloudBrowserAgentRelayClient = client;
            return this;
        }

        public Builder cloudLlmRuntimeAccess(
                com.apimarketplace.agent.cloud.CloudLlmRuntimeAccess runtimeAccess) {
            this.cloudLlmRuntimeAccess = runtimeAccess;
            return this;
        }

        public ServiceRegistry build() {
            return new ServiceRegistry(this);
        }
    }
}
