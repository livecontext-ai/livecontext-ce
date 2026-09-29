package com.apimarketplace.orchestrator.tools;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.storage.service.StorageExplorerService;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.ApplicationLifecycleService;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowPinService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.orchestrator.services.agent.ConversationEventPublisher;
import com.apimarketplace.orchestrator.tools.application.ApplicationCrudModule;
import com.apimarketplace.orchestrator.tools.application.ApplicationExecuteModule;
import com.apimarketplace.orchestrator.tools.application.ApplicationShowcaseResolver;
import com.apimarketplace.orchestrator.tools.common.RunStopToolHandler;
import com.apimarketplace.orchestrator.tools.files.FilesToolsProvider;
import com.apimarketplace.orchestrator.tools.workflow.WorkflowCrudModule;
import com.apimarketplace.orchestrator.tools.workflow.builder.AgentWorkflowFireService;
import com.apimarketplace.common.storage.url.PublicFileUrlBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Regression for the tool-path VIEWER gap on the orchestrator tools other than the
 * builder entry point: a VIEWER driving an agent could delete / pin / stop through the
 * workflow CRUD module, uninstall or execute an application, and create a folder, none of
 * which the REST surface allows them. The workflow delete also passed no role to
 * {@code deleteWorkflow}, so {@code OrgAccessGuard.canWrite} could never recognise a VIEWER.
 */
@DisplayName("Orchestrator tools refuse writes from the workspace VIEWER role")
class OrgViewerToolWriteGateTest {

    private static final String TENANT = "tenant-v";
    private static final String ORG = "org-v";
    private static final UUID WORKFLOW_ID = UUID.fromString("0e3b8f5a-6c1d-4f7e-8a9b-2c3d4e5f6a7b");

    private static ToolExecutionContext ctx(String role) {
        return new ToolExecutionContext(TENANT, Map.of(), Map.of(), Set.of(), null, null, ORG, role);
    }

    private static void assertDenied(ToolExecutionResult result) {
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        assertThat(result.error()).contains("VIEWER");
    }

    @Nested
    @DisplayName("workflow CRUD module")
    class WorkflowCrud {

        private WorkflowManagementService workflowService;
        private WorkflowRepository workflowRepository;
        private WorkflowPinService pinService;
        private RunStopToolHandler runStopToolHandler;
        private WorkflowCrudModule module;

        @BeforeEach
        void setUp() {
            workflowService = mock(WorkflowManagementService.class);
            workflowRepository = mock(WorkflowRepository.class);
            pinService = mock(WorkflowPinService.class);
            runStopToolHandler = mock(RunStopToolHandler.class);
            WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
            module = new WorkflowCrudModule(workflowService, runRepository,
                    mock(AgentWorkflowFireService.class), mock(WorkflowPlanVersionService.class), pinService,
                    mock(com.apimarketplace.publication.client.PublicationClient.class),
                    mock(com.apimarketplace.credential.client.CredentialClient.class), workflowRepository,
                    new ApplicationShowcaseResolver(runRepository),
                    mock(com.apimarketplace.orchestrator.tools.utility.AgentCancellationProbe.class),
                    runStopToolHandler,
                    mock(com.apimarketplace.orchestrator.services.resume.StepRerunService.class),
                    mock(com.apimarketplace.orchestrator.services.resume.AutoRestartExecutionService.class));
        }

        @Test
        @DisplayName("delete by a VIEWER is denied before the workflow is even looked up")
        void viewerDeleteDenied() {
            assertDenied(module.execute("delete", Map.of("workflow_id", WORKFLOW_ID.toString()), TENANT, ctx("VIEWER"))
                    .orElseThrow());
            verifyNoInteractions(workflowService, workflowRepository);
        }

        @Test
        @DisplayName("pin and stop_run by a VIEWER are denied")
        void viewerPinAndStopDenied() {
            assertDenied(module.execute("pin", Map.of("workflow_id", WORKFLOW_ID.toString(), "version", 2),
                    TENANT, ctx("VIEWER")).orElseThrow());
            assertDenied(module.execute("stop_run", Map.of("run_id", "run_1"), TENANT, ctx("VIEWER")).orElseThrow());
            verifyNoInteractions(pinService, runStopToolHandler);
        }

        @Test
        @DisplayName("delete by a MEMBER forwards the role to deleteWorkflow so the guard can apply it")
        void memberDeleteForwardsRole() {
            WorkflowEntity wf = mock(WorkflowEntity.class);
            when(wf.getTenantId()).thenReturn("owner-1");
            when(wf.getOrganizationId()).thenReturn(ORG);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(workflowService.deleteWorkflow(WORKFLOW_ID, TENANT, "MEMBER")).thenReturn(true);

            ToolExecutionResult result = module.execute("delete", Map.of("workflow_id", WORKFLOW_ID.toString()),
                    TENANT, ctx("MEMBER")).orElseThrow();

            assertThat(result.success()).isTrue();
            verify(workflowService).deleteWorkflow(WORKFLOW_ID, TENANT, "MEMBER");
            verify(workflowService, never()).deleteWorkflow(WORKFLOW_ID, TENANT);
        }
    }

    @Nested
    @DisplayName("application tool")
    class Application {

        @Test
        @DisplayName("uninstall by a VIEWER is denied and nothing is deleted")
        void viewerUninstallDenied() {
            WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
            WorkflowManagementService workflowService = mock(WorkflowManagementService.class);
            WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
            ApplicationCrudModule module = new ApplicationCrudModule(
                    mock(com.apimarketplace.publication.client.PublicationClient.class), workflowRepository,
                    runRepository, mock(com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository.class),
                    mock(WorkflowPlanVersionService.class), mock(AgentWorkflowFireService.class),
                    mock(com.apimarketplace.credential.client.CredentialClient.class),
                    new ApplicationShowcaseResolver(runRepository), new ApplicationLifecycleService(workflowRepository));
            module.setWorkflowManagementService(workflowService);

            assertDenied(module.execute("uninstall", Map.of("application_id", UUID.randomUUID().toString()),
                    TENANT, ctx("VIEWER")).orElseThrow());
            verify(workflowService, never()).deleteWorkflow(any(), any(), any());
            verifyNoInteractions(workflowRepository);
        }

        @Test
        @DisplayName("execute by a VIEWER is denied and nothing is fired")
        void viewerExecuteDenied() {
            WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
            AgentWorkflowFireService fireService = mock(AgentWorkflowFireService.class);
            ApplicationExecuteModule module = new ApplicationExecuteModule(workflowRepository, fireService,
                    mock(ConversationEventPublisher.class), new ApplicationLifecycleService(workflowRepository),
                    mock(WorkflowRunRepository.class), mock(RunStopToolHandler.class));

            assertDenied(module.execute("execute", Map.of("application_id", UUID.randomUUID().toString()),
                    TENANT, ctx("VIEWER")).orElseThrow());
            verifyNoInteractions(fireService, workflowRepository);
        }
    }

    @Nested
    @DisplayName("download_file / store_file (no read/write category: direct role check)")
    class StoreAndDownload {

        @Test
        @DisplayName("store_file and download_file by a VIEWER are denied and nothing is stored or fetched")
        void viewerStoreAndDownloadDenied() {
            com.apimarketplace.orchestrator.services.file.FileStorageService storage =
                    mock(com.apimarketplace.orchestrator.services.file.FileStorageService.class);
            com.apimarketplace.orchestrator.services.file.FileDownloader downloader =
                    mock(com.apimarketplace.orchestrator.services.file.FileDownloader.class);
            com.apimarketplace.orchestrator.tools.file.FileToolsProvider provider =
                    new com.apimarketplace.orchestrator.tools.file.FileToolsProvider(storage, downloader,
                            mock(com.apimarketplace.orchestrator.utils.file.MimeTypeRegistry.class));

            assertDenied(provider.execute("store_file", Map.of("content", "x", "filename", "a.txt"), ctx("VIEWER")));
            assertDenied(provider.execute("download_file", Map.of("url", "https://example.com/a.pdf"), ctx("VIEWER")));
            verifyNoInteractions(storage, downloader);
        }
    }

    @Nested
    @DisplayName("files tool")
    class Files {

        @Test
        @DisplayName("create_folder by a VIEWER is denied (it had no role check at all)")
        void viewerCreateFolderDenied() {
            StorageExplorerService explorer = mock(StorageExplorerService.class);
            FilesToolsProvider provider = new FilesToolsProvider(explorer, mock(StorageService.class),
                    new PublicFileUrlBuilder("https://app.example"), mock(OrgAccessGuard.class),
                    mock(WorkflowRepository.class));

            assertDenied(provider.execute("files", Map.of("action", "create_folder", "name", "x"), ctx("VIEWER")));
            verifyNoInteractions(explorer);
        }
    }
}
