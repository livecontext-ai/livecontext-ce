package com.apimarketplace.orchestrator.tools.application;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.publication.client.PublicationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ApplicationCrudModule}.
 *
 * <p>Post-V261 regression coverage - pins (a) the {@code acquire} path
 * forwards {@code context.orgId} to {@link PublicationClient#acquirePublication}
 * (round-2 audit fix: pre-fix the cloned workflow landed in the acquirer's
 * personal scope, not their active workspace), and (b) the {@code get} /
 * {@code acquire} actions reject malformed application_id, invalid actions,
 * and surface {@code RESOURCE_NOT_FOUND} consistently.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApplicationCrudModule - post-V261 strict-org acquire + get")
class ApplicationCrudModuleTest {

    @Mock private PublicationClient publicationClient;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowPlanVersionRepository planVersionRepository;
    @Mock private com.apimarketplace.orchestrator.services.WorkflowPlanVersionService planVersionService;
    @Mock private com.apimarketplace.orchestrator.tools.workflow.builder.AgentWorkflowFireService agentWorkflowFireService;
    @Mock private CredentialClient credentialClient;
    @Mock private com.apimarketplace.orchestrator.services.WorkflowManagementService workflowManagementService;

    private ApplicationCrudModule module;

    private static final String TENANT_ID = "tenant-456";
    private static final String CALLER_ORG_ID = "org-zzz-yyy";
    private static final UUID APP_PUB_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        module = new ApplicationCrudModule(
                publicationClient, workflowRepository, workflowRunRepository,
                planVersionRepository, planVersionService, agentWorkflowFireService,
                credentialClient, new ApplicationShowcaseResolver(workflowRunRepository),
                new com.apimarketplace.orchestrator.services.ApplicationLifecycleService(workflowRepository));
        module.setWorkflowManagementService(workflowManagementService);
    }

    // ------------------------------------------------------------------
    // canHandle
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("canHandle")
    class CanHandle {

        @Test
        @DisplayName("Handles the documented CRUD + run-inspection actions")
        void canHandleDocumentedActions() {
            assertThat(module.canHandle("search")).isTrue();
            assertThat(module.canHandle("my")).isTrue();
            assertThat(module.canHandle("get")).isTrue();
            assertThat(module.canHandle("acquire")).isTrue();
            assertThat(module.canHandle("visualize")).isTrue();
            assertThat(module.canHandle("create")).isTrue();
            assertThat(module.canHandle("runs")).isTrue();
            assertThat(module.canHandle("get_run")).isTrue();
            assertThat(module.canHandle("get_node_output")).isTrue();
            assertThat(module.canHandle("uninstall")).isTrue();
        }

        @Test
        @DisplayName("Does not handle execute (delegated to ApplicationExecuteModule) or unknowns")
        void rejectsExecuteAndUnknownActions() {
            assertThat(module.canHandle("execute")).isFalse();
            assertThat(module.canHandle("delete")).isFalse();
            assertThat(module.canHandle("")).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // acquire - org propagation regression (round-2)
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("acquire - propagates org context (round-2 regression)")
    class AcquireOrgPropagation {

        @Test
        @DisplayName("Forwards context.orgId to PublicationClient.acquirePublication - pre-fix the clone landed in personal scope")
        void acquireForwardsOrgIdToPublicationClient() {
            Map<String, Object> stub = new HashMap<>();
            stub.put("id", "wf-9");
            stub.put("title", "My Cloned App");
            when(publicationClient.acquirePublication(eq(APP_PUB_ID), eq(TENANT_ID), eq(CALLER_ORG_ID)))
                    .thenReturn(stub);

            ToolExecutionResult result = module.execute("acquire",
                    Map.of("application_id", APP_PUB_ID.toString()),
                    TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isTrue();
            // Regression pin: the orgId MUST be the 3rd argument to acquirePublication.
            // Pre-fix the call was acquirePublication(id, tenantId, null) and the clone
            // dropped into personal scope where the caller couldn't see it from the
            // org workspace switcher.
            verify(publicationClient).acquirePublication(
                    eq(APP_PUB_ID), eq(TENANT_ID), eq(CALLER_ORG_ID));
        }

        @Test
        @DisplayName("Forwards null orgId when context has no org (personal-scope acquire still works)")
        void acquireWithoutOrgIdStillCallsPublicationClient() {
            ToolExecutionContext noOrgCtx = new ToolExecutionContext(
                    TENANT_ID, Map.of(), Map.of(), Set.of(),
                    null, null, null, null);
            Map<String, Object> stub = new HashMap<>();
            stub.put("id", "wf-personal");
            stub.put("title", "App");
            when(publicationClient.acquirePublication(eq(APP_PUB_ID), eq(TENANT_ID), eq(null)))
                    .thenReturn(stub);

            ToolExecutionResult result = module.execute("acquire",
                    Map.of("application_id", APP_PUB_ID.toString()),
                    TENANT_ID, noOrgCtx).orElseThrow();

            assertThat(result.success()).isTrue();
            verify(publicationClient).acquirePublication(eq(APP_PUB_ID), eq(TENANT_ID), eq(null));
        }

        @Test
        @DisplayName("acquire emits an application-ONLY marker - never visualizes the cloned workflow")
        void acquireMarkerVisualizesOnlyTheApplicationNotTheWorkflow() {
            // Regression: acquire used to emit "[visualize:workflow:<id>]\n[visualize:application:<id>]",
            // surfacing a workflow card the user never asked for (and that may still be settling
            // post-clone). The card must show ONLY the application, like create/execute/visualize.
            Map<String, Object> stub = new HashMap<>();
            stub.put("id", "wf-9");
            stub.put("title", "My Cloned App");
            when(publicationClient.acquirePublication(eq(APP_PUB_ID), eq(TENANT_ID), eq(CALLER_ORG_ID)))
                    .thenReturn(stub);

            ToolExecutionResult result = module.execute("acquire",
                    Map.of("application_id", APP_PUB_ID.toString()),
                    TENANT_ID, contextWithOrg()).orElseThrow();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            String marker = (String) data.get("marker");
            assertThat(marker).isEqualTo("[visualize:application:" + APP_PUB_ID + "]");
            assertThat(marker).doesNotContain("visualize:workflow");
        }
    }

    // ------------------------------------------------------------------
    // acquire - input validation
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("acquire - input validation")
    class AcquireInputValidation {

        @Test
        @DisplayName("Missing application_id returns MISSING_PARAMETER and never hits publicationClient")
        void missingApplicationIdReturnsMissing() {
            ToolExecutionResult result = module.execute("acquire", Map.of(),
                    TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            verify(publicationClient, never()).acquirePublication(any(), any(), any());
        }

        @Test
        @DisplayName("Not-in-allowed-list returns PERMISSION_DENIED before any HTTP call")
        void allowedListBlocksAcquire() {
            Map<String, Object> credentials = new HashMap<>();
            credentials.put("allowedApplicationIds", List.of("some-other-id"));
            ToolExecutionContext ctx = new ToolExecutionContext(
                    TENANT_ID, credentials, Map.of(), Set.of(),
                    null, null, CALLER_ORG_ID, null);

            ToolExecutionResult result = module.execute("acquire",
                    Map.of("application_id", APP_PUB_ID.toString()),
                    TENANT_ID, ctx).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            verify(publicationClient, never()).acquirePublication(any(), any(), any());
        }

        @Test
        @DisplayName("Namespaced allowedApplicationIds blocks acquire before any HTTP call")
        void namespacedAllowedListBlocksAcquire() {
            Map<String, Object> credentials = new HashMap<>();
            credentials.put("__allowedApplicationIds__", List.of("some-other-id"));
            ToolExecutionContext ctx = new ToolExecutionContext(
                    TENANT_ID, credentials, Map.of(), Set.of(),
                    null, null, CALLER_ORG_ID, null);

            ToolExecutionResult result = module.execute("acquire",
                    Map.of("application_id", APP_PUB_ID.toString()),
                    TENANT_ID, ctx).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            verify(publicationClient, never()).acquirePublication(any(), any(), any());
        }
    }

    // ------------------------------------------------------------------
    // my - lists the workspace's apps (acquired + published), not just published
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("my - lists the workspace's apps, not just published")
    class MyListsWorkspaceApps {

        @Test
        @DisplayName("query filters acquired apps by the workflow name BEFORE resolving publications (no fan-out for excluded apps)")
        void myQueryFiltersAcquiredByWorkflowName() {
            UUID invoicePub = UUID.randomUUID();
            UUID weatherPub = UUID.randomUUID();
            WorkflowEntity invoiceClone = mock(WorkflowEntity.class);
            when(invoiceClone.getName()).thenReturn("Invoice Sync");
            when(invoiceClone.getSourcePublicationId()).thenReturn(invoicePub);
            WorkflowEntity weatherClone = mock(WorkflowEntity.class);
            when(weatherClone.getName()).thenReturn("Weather Bot");
            // Weather's publication id IS resolvable: pre-change (no filter) both pubIds are
            // collected -> total=2 and getPublicationById(weatherPub) IS called, so the
            // assertions below fail. Post-change the query filter drops Weather at the
            // WorkflowEntity level, so getSourcePublicationId is never reached (lenient).
            lenient().when(weatherClone.getSourcePublicationId()).thenReturn(weatherPub);
            when(workflowRepository.findAcquiredByOrganizationId(CALLER_ORG_ID, WorkflowEntity.WorkflowType.APPLICATION))
                    .thenReturn(List.of(invoiceClone, weatherClone));
            Map<String, Object> pub = new HashMap<>();
            pub.put("id", invoicePub.toString());
            pub.put("title", "Invoice Sync");
            when(publicationClient.getPublicationById(invoicePub)).thenReturn(pub);
            lenient().when(credentialClient.getConfiguredIntegrations(TENANT_ID)).thenReturn(Set.of());

            ToolExecutionResult result = module.execute("my", Map.of("query", "invoice"),
                    TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> apps = (List<Map<String, Object>>) data.get("applications");
            assertThat(apps).hasSize(1);
            // total is computed from the FILTERED pubId set (1), not the raw acquired set (2).
            assertThat(data.get("total")).isEqualTo(1L);
            assertThat(apps.get(0).get("id")).isEqualTo(invoicePub.toString());
            // The excluded (Weather) app's publication is never fetched - the filter runs
            // on the workflow entity BEFORE the getPublicationById fan-out.
            verify(publicationClient).getPublicationById(invoicePub);
            verify(publicationClient, never()).getPublicationById(weatherPub);
        }

        @Test
        @DisplayName("query filters the publisher list by title/description when there is no org scope")
        void myQueryFiltersPublisherList() {
            ToolExecutionContext noOrgCtx = new ToolExecutionContext(
                    TENANT_ID, Map.of(), Map.of(), Set.of(), null, null, null, null);
            Map<String, Object> invoiceApp = new HashMap<>();
            invoiceApp.put("id", UUID.randomUUID().toString());
            invoiceApp.put("title", "Invoice Tool");
            Map<String, Object> weatherApp = new HashMap<>();
            weatherApp.put("id", UUID.randomUUID().toString());
            weatherApp.put("title", "Weather");
            weatherApp.put("description", "forecasts");
            when(publicationClient.getPublicationsByPublisher(TENANT_ID))
                    .thenReturn(List.of(invoiceApp, weatherApp));
            lenient().when(credentialClient.getConfiguredIntegrations(TENANT_ID)).thenReturn(Set.of());

            ToolExecutionResult result = module.execute("my", Map.of("query", "invoice"),
                    TENANT_ID, noOrgCtx).orElseThrow();

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat(data.get("total")).isEqualTo(1L);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> apps = (List<Map<String, Object>>) data.get("applications");
            assertThat(apps).hasSize(1);
        }

        @Test
        @DisplayName("Lists the org's ACQUIRED apps - pre-fix a consumer who published nothing was told 'you have no applications'")
        void myListsAcquiredAppsNotJustPublished() {
            // contact@ prod report: user acquired ~10 apps, published none. Pre-fix
            // executeMy called getPublicationsByPublisher (empty for a consumer), so
            // the agent answered "tu n'as aucune application" despite a full workspace.
            UUID acquiredPubId = UUID.randomUUID();
            WorkflowEntity clone = mock(WorkflowEntity.class);
            when(clone.getSourcePublicationId()).thenReturn(acquiredPubId);
            when(workflowRepository.findAcquiredByOrganizationId(CALLER_ORG_ID, WorkflowEntity.WorkflowType.APPLICATION))
                    .thenReturn(List.of(clone));
            Map<String, Object> pub = new HashMap<>();
            pub.put("id", acquiredPubId.toString());
            pub.put("title", "Acquired App");
            when(publicationClient.getPublicationById(acquiredPubId)).thenReturn(pub);
            lenient().when(credentialClient.getConfiguredIntegrations(TENANT_ID)).thenReturn(Set.of());

            ToolExecutionResult result = module.execute("my", Map.of(),
                    TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> apps = (List<Map<String, Object>>) data.get("applications");
            assertThat(apps).hasSize(1);
            assertThat(apps.get(0).get("id")).isEqualTo(acquiredPubId.toString());
            // Disambiguation (fix 2026-06-05): `application_id` is emitted alongside
            // `id` with the same value, so the agent copies the field whose name
            // matches the get/execute parameter instead of grabbing `workflowId`.
            assertThat(apps.get(0).get("application_id")).isEqualTo(acquiredPubId.toString());
            // Regression pin: the workspace path is used, never the publisher-only list.
            verify(workflowRepository).findAcquiredByOrganizationId(CALLER_ORG_ID, WorkflowEntity.WorkflowType.APPLICATION);
            verify(publicationClient, never()).getPublicationsByPublisher(any());
        }

        @Test
        @DisplayName("Falls back to the publisher list when there is no workspace (org) scope")
        void myFallsBackToPublisherWhenNoOrg() {
            ToolExecutionContext noOrgCtx = new ToolExecutionContext(
                    TENANT_ID, Map.of(), Map.of(), Set.of(), null, null, null, null);
            Map<String, Object> pub = new HashMap<>();
            pub.put("id", UUID.randomUUID().toString());
            pub.put("title", "Published App");
            when(publicationClient.getPublicationsByPublisher(TENANT_ID))
                    .thenReturn(List.of(pub));
            lenient().when(credentialClient.getConfiguredIntegrations(TENANT_ID)).thenReturn(Set.of());

            ToolExecutionResult result = module.execute("my", Map.of(),
                    TENANT_ID, noOrgCtx).orElseThrow();

            assertThat(result.success()).isTrue();
            verify(publicationClient).getPublicationsByPublisher(TENANT_ID);
            verify(workflowRepository, never()).findAcquiredByOrganizationId(any(), any());
        }

        @Test
        @DisplayName("ACQUIRED app: workflowId is reconciled to the LOCAL clone (the publisher's id is out of scope -> 404s)")
        void acquiredAppReconcilesWorkflowIdToLocalClone() {
            UUID publisherWorkflowId = UUID.randomUUID();
            UUID localCloneId = UUID.randomUUID();
            Map<String, Object> pub = new HashMap<>();
            pub.put("id", APP_PUB_ID.toString());
            pub.put("title", "Acquired App");
            pub.put("workflowId", publisherWorkflowId.toString()); // publisher's source workflow
            when(publicationClient.getPublicationById(APP_PUB_ID)).thenReturn(pub);

            WorkflowEntity clone = mock(WorkflowEntity.class);
            lenient().when(clone.getId()).thenReturn(localCloneId);
            lenient().when(clone.getPlan()).thenReturn(null);
            when(workflowRepository.findByOrganizationIdAndSourcePublicationIdAndWorkflowType(
                    CALLER_ORG_ID, APP_PUB_ID, WorkflowEntity.WorkflowType.APPLICATION))
                    .thenReturn(Optional.of(clone));
            // The publisher's workflow exists but belongs to ANOTHER tenant/org -> out of scope.
            WorkflowEntity publisherWf = mock(WorkflowEntity.class);
            lenient().when(publisherWf.getTenantId()).thenReturn("other-tenant");
            lenient().when(publisherWf.getOrganizationId()).thenReturn("other-org");
            lenient().when(workflowRepository.findById(publisherWorkflowId)).thenReturn(Optional.of(publisherWf));
            lenient().when(credentialClient.getConfiguredIntegrations(TENANT_ID)).thenReturn(Set.of());

            AtomicReference<ToolExecutionResult> ref = new AtomicReference<>();
            TenantResolver.runWithOrgScope(CALLER_ORG_ID, () -> ref.set(module.execute("get",
                    Map.of("application_id", APP_PUB_ID.toString()),
                    TENANT_ID, contextWithOrg()).orElseThrow()));

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) ref.get().data();
            assertThat(data.get("owned_by_me")).isEqualTo(true);
            // Reconciled to the loadable LOCAL clone, never the publisher's original.
            assertThat(data.get("workflowId")).isEqualTo(localCloneId.toString());
            assertThat(data.get("workflowId")).isNotEqualTo(publisherWorkflowId.toString());
        }

        @Test
        @DisplayName("PUBLISHED app: workflowId stays the publisher's own source workflow (no regression)")
        void publishedAppKeepsPublisherSourceWorkflowId() {
            UUID sourceWorkflowId = UUID.randomUUID();
            UUID localCloneId = UUID.randomUUID();
            Map<String, Object> pub = new HashMap<>();
            pub.put("id", APP_PUB_ID.toString());
            pub.put("title", "My Published App");
            pub.put("workflowId", sourceWorkflowId.toString());
            when(publicationClient.getPublicationById(APP_PUB_ID)).thenReturn(pub);

            WorkflowEntity clone = mock(WorkflowEntity.class);
            lenient().when(clone.getId()).thenReturn(localCloneId);
            lenient().when(clone.getPlan()).thenReturn(null);
            when(workflowRepository.findByOrganizationIdAndSourcePublicationIdAndWorkflowType(
                    CALLER_ORG_ID, APP_PUB_ID, WorkflowEntity.WorkflowType.APPLICATION))
                    .thenReturn(Optional.of(clone));
            // The publisher's source workflow is in the CALLER's own scope -> loadable -> kept.
            WorkflowEntity sourceWf = mock(WorkflowEntity.class);
            lenient().when(sourceWf.getTenantId()).thenReturn(TENANT_ID);
            lenient().when(sourceWf.getOrganizationId()).thenReturn(CALLER_ORG_ID);
            lenient().when(workflowRepository.findById(sourceWorkflowId)).thenReturn(Optional.of(sourceWf));
            lenient().when(credentialClient.getConfiguredIntegrations(TENANT_ID)).thenReturn(Set.of());

            AtomicReference<ToolExecutionResult> ref = new AtomicReference<>();
            TenantResolver.runWithOrgScope(CALLER_ORG_ID, () -> ref.set(module.execute("get",
                    Map.of("application_id", APP_PUB_ID.toString()),
                    TENANT_ID, contextWithOrg()).orElseThrow()));

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) ref.get().data();
            assertThat(data.get("owned_by_me")).isEqualTo(true);
            // No override: the publisher edits their own source workflow.
            assertThat(data.get("workflowId")).isEqualTo(sourceWorkflowId.toString());
            assertThat(data.get("workflowId")).isNotEqualTo(localCloneId.toString());
        }
    }

    // ------------------------------------------------------------------
    // get - basic flow
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("get - basic flow")
    class GetFlow {

        @Test
        @DisplayName("Missing application_id returns MISSING_PARAMETER")
        void getMissingApplicationId() {
            ToolExecutionResult result = module.execute("get", Map.of(),
                    TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }

        @Test
        @DisplayName("Malformed application_id (non-UUID) returns INVALID_PARAMETER_VALUE")
        void getMalformedApplicationId() {
            ToolExecutionResult result = module.execute("get",
                    Map.of("application_id", "definitely-not-a-uuid"),
                    TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
        }

        @Test
        @DisplayName("Application not found returns RESOURCE_NOT_FOUND")
        void getApplicationNotFound() {
            when(publicationClient.getPublicationById(eq(APP_PUB_ID))).thenReturn(null);

            ToolExecutionResult result = module.execute("get",
                    Map.of("application_id", APP_PUB_ID.toString()),
                    TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
        }

        @Test
        @DisplayName("Passing a workflowId where application_id was expected → error echoes the correct application_id")
        void getWithWorkflowIdReturnsDisambiguationHint() {
            // Regression (prod 2026-06-05): my/search items carry both `id`
            // (application_id) and `workflowId` adjacent; the agent copied
            // workflowId into application_id and get dead-ended on a generic 404.
            // Now get detects that the UUID is a workflow with a sourcePublicationId
            // and hands back the real application_id to retry with.
            UUID workflowId = APP_PUB_ID; // the UUID the agent wrongly passed
            UUID realAppId = UUID.randomUUID();
            when(publicationClient.getPublicationById(eq(workflowId))).thenReturn(null);
            WorkflowEntity wf = mock(WorkflowEntity.class);
            when(wf.getSourcePublicationId()).thenReturn(realAppId);
            when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(wf));

            ToolExecutionResult result = module.execute("get",
                    Map.of("application_id", workflowId.toString()),
                    TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
            assertThat(result.error())
                    .contains("workflowId")
                    .contains(realAppId.toString());
        }
    }

    @Nested
    @DisplayName("rewriteWorkflowToApplication (get_node_output NEXT pointers on the application surface)")
    class RewriteNextPointers {

        @Test
        @DisplayName("rewrites the report's own tool-call pointers (incl. nested expand NEXT) but preserves node-output data")
        void rewritesPointersNotData() {
            Map<String, Object> outputField = new HashMap<>();
            outputField.put("NEXT", "workflow(action='get_node_output', run_id='r', field='output.x', offset=131072)");
            Map<String, Object> bigStub = new HashMap<>();
            bigStub.put("truncated", true);
            bigStub.put("NEXT", "workflow(action='get_node_output', run_id='r', field='output.body', offset=0)");
            Map<String, Object> nodeOutput = new HashMap<>();
            nodeOutput.put("body", bigStub);
            nodeOutput.put("note", "To retry, call workflow(retry) yourself");  // genuine output data - no action=
            Map<String, Object> result = new HashMap<>();
            result.put("NEXT", "workflow(action='get_run', run_id='r')");
            result.put("output_field", outputField);
            result.put("output", nodeOutput);

            ApplicationCrudModule.rewriteWorkflowToApplication(result);

            assertThat((String) result.get("NEXT")).startsWith("application(action=");
            assertThat((String) outputField.get("NEXT")).startsWith("application(action=");
            assertThat((String) bigStub.get("NEXT")).startsWith("application(action=");
            // genuine node-output data that merely mentions workflow( in prose is NOT mutated
            assertThat((String) nodeOutput.get("note")).isEqualTo("To retry, call workflow(retry) yourself");
        }
    }

    // ------------------------------------------------------------------
    // create - showcase run + epoch resolution (same contract as the share
    // wizard; agent goes through the same publish pipe with epoch forwarded)
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("create - showcase run + epoch (share-parity)")
    class CreateShowcaseRunAndEpoch {

        private final UUID workflowId = UUID.randomUUID();
        private final UUID ifaceId = UUID.randomUUID();
        private final UUID createdPubId = UUID.randomUUID();

        @Test
        @DisplayName("No completed automatic run → WORKFLOW_INVALID with a 'run it first' hint, never publishes (regression: the runless app that dead-ended on 'No run available')")
        void zeroCompletedRunReturnsWorkflowInvalidWithRunFirstHint() {
            stubWorkflowWithInterface();
            // Latest-runs query returns nothing publishable.
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDescPageable(eq(workflowId), any()))
                    .thenReturn(new PageImpl<>(List.of()));

            ToolExecutionResult result = module.execute("create",
                    Map.of("workflow_id", workflowId.toString()),
                    TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.WORKFLOW_INVALID);
            assertThat(result.error()).contains("workflow(action='execute'");
            verify(publicationClient, never()).publishWorkflow(any(), any(), any());
        }

        @Test
        @DisplayName("No run_id → picks the latest COMPLETED automatic run, forwards the chosen epoch through the publish Map, emits a 4-field marker")
        void defaultsToLatestRunAndForwardsEpoch() {
            WorkflowEntity wf = stubWorkflowWithInterface();
            when(wf.getName()).thenReturn("Search WF");
            when(wf.getDescription()).thenReturn("desc");

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.COMPLETED);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getRunIdPublic()).thenReturn("run-public-1");
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDescPageable(eq(workflowId), any()))
                    .thenReturn(new PageImpl<>(List.of(run)));
            when(publicationClient.publishWorkflow(any(), eq(TENANT_ID), eq(CALLER_ORG_ID)))
                    .thenReturn(Map.of("id", createdPubId.toString()));

            Map<String, Object> params = new HashMap<>();
            params.put("workflow_id", workflowId.toString());
            params.put("epoch", 3);

            ToolExecutionResult result = module.execute("create", params,
                    TENANT_ID, createCtxMutableCreds()).orElseThrow();

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(publicationClient).publishWorkflow(captor.capture(), eq(TENANT_ID), eq(CALLER_ORG_ID));
            assertThat(captor.getValue().get("showcaseRunId")).isEqualTo("run-public-1");
            // Epoch flows through the SAME publish Map the UI wizard uses.
            assertThat(captor.getValue().get("showcaseEpoch")).isEqualTo(3);
            assertThat(captor.getValue().get("displayMode")).isEqualTo("APPLICATION");
            // 4-field marker pins the chat card/panel to the showcase run.
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat((String) data.get("marker"))
                    .isEqualTo("[visualize:application:" + createdPubId + ":run-public-1]");
            assertThat(data.get("showcaseRunId")).isEqualTo("run-public-1");
            assertThat(data.get("showcaseEpoch")).isEqualTo(3);
        }

        @Test
        @DisplayName("Omitted epoch is NOT put in the publish Map (render then defaults to the latest epoch)")
        void omittedEpochIsNotForwarded() {
            WorkflowEntity wf = stubWorkflowWithInterface();
            when(wf.getName()).thenReturn("Search WF");
            when(wf.getDescription()).thenReturn("desc");
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.COMPLETED);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getRunIdPublic()).thenReturn("run-public-1");
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDescPageable(eq(workflowId), any()))
                    .thenReturn(new PageImpl<>(List.of(run)));
            when(publicationClient.publishWorkflow(any(), eq(TENANT_ID), eq(CALLER_ORG_ID)))
                    .thenReturn(Map.of("id", createdPubId.toString()));

            module.execute("create", Map.of("workflow_id", workflowId.toString()),
                    TENANT_ID, createCtxMutableCreds()).orElseThrow();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(publicationClient).publishWorkflow(captor.capture(), eq(TENANT_ID), eq(CALLER_ORG_ID));
            assertThat(captor.getValue()).doesNotContainKey("showcaseEpoch");
        }

        @Test
        @DisplayName("Pinned run_id that is not a run of this workflow → RESOURCE_NOT_FOUND, never publishes")
        void pinnedRunNotOfThisWorkflowReturnsNotFound() {
            stubWorkflowWithInterface();
            when(workflowRunRepository.findByRunIdPublic("rid")).thenReturn(Optional.empty());

            Map<String, Object> params = new HashMap<>();
            params.put("workflow_id", workflowId.toString());
            params.put("run_id", "rid");

            ToolExecutionResult result = module.execute("create", params,
                    TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
            verify(publicationClient, never()).publishWorkflow(any(), any(), any());
        }

        @Test
        @DisplayName("Pinned step-by-step run → WORKFLOW_INVALID, never publishes")
        void pinnedStepByStepRunReturnsWorkflowInvalid() {
            WorkflowEntity wf = stubWorkflowWithInterface();
            when(wf.getId()).thenReturn(workflowId);
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getWorkflow()).thenReturn(wf);
            when(run.isStepByStepMode()).thenReturn(true);
            when(workflowRunRepository.findByRunIdPublic("rid")).thenReturn(Optional.of(run));

            Map<String, Object> params = new HashMap<>();
            params.put("workflow_id", workflowId.toString());
            params.put("run_id", "rid");

            ToolExecutionResult result = module.execute("create", params,
                    TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.WORKFLOW_INVALID);
            verify(publicationClient, never()).publishWorkflow(any(), any(), any());
        }

        @Test
        @DisplayName("Pinned run that has not COMPLETED → WORKFLOW_INVALID, never publishes")
        void pinnedNonCompletedRunReturnsWorkflowInvalid() {
            WorkflowEntity wf = stubWorkflowWithInterface();
            when(wf.getId()).thenReturn(workflowId);
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getWorkflow()).thenReturn(wf);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(workflowRunRepository.findByRunIdPublic("rid")).thenReturn(Optional.of(run));

            Map<String, Object> params = new HashMap<>();
            params.put("workflow_id", workflowId.toString());
            params.put("run_id", "rid");

            ToolExecutionResult result = module.execute("create", params,
                    TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.WORKFLOW_INVALID);
            verify(publicationClient, never()).publishWorkflow(any(), any(), any());
        }

        @Test
        @DisplayName("Default selection SKIPS showcase-snapshot clones (source='showcase') and picks the latest REAL completed automatic run")
        void defaultSkipsShowcaseSnapshotRunsAndPicksRealRun() {
            WorkflowEntity wf = stubWorkflowWithInterface();
            when(wf.getName()).thenReturn("Search WF");
            when(wf.getDescription()).thenReturn("desc");

            // Latest (first) is a showcase clone → must be skipped; the real run wins.
            WorkflowRunEntity clone = mock(WorkflowRunEntity.class);
            when(clone.getStatus()).thenReturn(RunStatus.COMPLETED);
            when(clone.isStepByStepMode()).thenReturn(false);
            when(clone.getSource()).thenReturn("showcase");
            WorkflowRunEntity real = mock(WorkflowRunEntity.class);
            when(real.getStatus()).thenReturn(RunStatus.COMPLETED);
            when(real.isStepByStepMode()).thenReturn(false);
            when(real.getRunIdPublic()).thenReturn("run-real");
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDescPageable(eq(workflowId), any()))
                    .thenReturn(new PageImpl<>(List.of(clone, real)));
            when(publicationClient.publishWorkflow(any(), eq(TENANT_ID), eq(CALLER_ORG_ID)))
                    .thenReturn(Map.of("id", createdPubId.toString()));

            ToolExecutionResult result = module.execute("create",
                    Map.of("workflow_id", workflowId.toString()),
                    TENANT_ID, createCtxMutableCreds()).orElseThrow();

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(publicationClient).publishWorkflow(captor.capture(), eq(TENANT_ID), eq(CALLER_ORG_ID));
            assertThat(captor.getValue().get("showcaseRunId")).isEqualTo("run-real");
        }

        @Test
        @DisplayName("Pinned run that is a showcase snapshot (source='showcase') → WORKFLOW_INVALID, never publishes")
        void pinnedShowcaseSnapshotRunRejected() {
            WorkflowEntity wf = stubWorkflowWithInterface();
            when(wf.getId()).thenReturn(workflowId);
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getWorkflow()).thenReturn(wf);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getStatus()).thenReturn(RunStatus.COMPLETED);
            when(run.getSource()).thenReturn("showcase");
            when(workflowRunRepository.findByRunIdPublic("rid")).thenReturn(Optional.of(run));

            Map<String, Object> params = new HashMap<>();
            params.put("workflow_id", workflowId.toString());
            params.put("run_id", "rid");

            ToolExecutionResult result = module.execute("create", params,
                    TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.WORKFLOW_INVALID);
            verify(publicationClient, never()).publishWorkflow(any(), any(), any());
        }

        @Test
        @DisplayName("Default ACCEPTS a WAITING_TRIGGER run (reusable-trigger app post-fire idle) - mirrors the publish pipeline, not just COMPLETED (regression: reusable apps were wrongly told to 'run it first')")
        void defaultAcceptsWaitingTriggerRun() {
            WorkflowEntity wf = stubWorkflowWithInterface();
            when(wf.getName()).thenReturn("Reusable App");
            when(wf.getDescription()).thenReturn("desc");
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.WAITING_TRIGGER);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getRunIdPublic()).thenReturn("run-reusable");
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDescPageable(eq(workflowId), any()))
                    .thenReturn(new PageImpl<>(List.of(run)));
            when(publicationClient.publishWorkflow(any(), eq(TENANT_ID), eq(CALLER_ORG_ID)))
                    .thenReturn(Map.of("id", createdPubId.toString()));

            ToolExecutionResult result = module.execute("create",
                    Map.of("workflow_id", workflowId.toString()),
                    TENANT_ID, createCtxMutableCreds()).orElseThrow();

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(publicationClient).publishWorkflow(captor.capture(), eq(TENANT_ID), eq(CALLER_ORG_ID));
            assertThat(captor.getValue().get("showcaseRunId")).isEqualTo("run-reusable");
        }

        @Test
        @DisplayName("Pinned WAITING_TRIGGER run is accepted (reusable-trigger run is showcaseable)")
        void pinnedWaitingTriggerRunAccepted() {
            WorkflowEntity wf = stubWorkflowWithInterface();
            when(wf.getName()).thenReturn("Reusable App");
            when(wf.getDescription()).thenReturn("desc");
            when(wf.getId()).thenReturn(workflowId);
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getWorkflow()).thenReturn(wf);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getStatus()).thenReturn(RunStatus.WAITING_TRIGGER);
            when(run.getSource()).thenReturn(null);
            when(run.getRunIdPublic()).thenReturn("run-reusable");
            when(workflowRunRepository.findByRunIdPublic("run-reusable")).thenReturn(Optional.of(run));
            when(publicationClient.publishWorkflow(any(), eq(TENANT_ID), eq(CALLER_ORG_ID)))
                    .thenReturn(Map.of("id", createdPubId.toString()));

            Map<String, Object> params = new HashMap<>();
            params.put("workflow_id", workflowId.toString());
            params.put("run_id", "run-reusable");

            ToolExecutionResult result = module.execute("create", params,
                    TENANT_ID, createCtxMutableCreds()).orElseThrow();

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(publicationClient).publishWorkflow(captor.capture(), eq(TENANT_ID), eq(CALLER_ORG_ID));
            assertThat(captor.getValue().get("showcaseRunId")).isEqualTo("run-reusable");
        }

        // -- shared setup --

        private WorkflowEntity stubWorkflowWithInterface() {
            WorkflowEntity wf = mock(WorkflowEntity.class);
            when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(wf));
            when(wf.getTenantId()).thenReturn(TENANT_ID);
            when(wf.getOrganizationId()).thenReturn(CALLER_ORG_ID);
            when(wf.getPlan()).thenReturn(planDataWithInterface());
            // getMaxVersion is read before the interface check on every create.
            lenient().when(planVersionRepository.getMaxVersion(workflowId)).thenReturn(Optional.of(1));
            return wf;
        }

        private Map<String, Object> planDataWithInterface() {
            Map<String, Object> iface = new HashMap<>();
            iface.put("id", ifaceId.toString());
            iface.put("label", "Search Page");
            iface.put("isEntryInterface", true);
            iface.put("actionMapping", Map.of("#search", "trigger:search_input:submit"));
            Map<String, Object> plan = new HashMap<>();
            plan.put("interfaces", List.of(iface));
            return plan;
        }

        private ToolExecutionContext createCtxMutableCreds() {
            // Mutable credentials map so ToolAccessControl.grantCreatedResource
            // (called after publish) has a writable surface.
            return new ToolExecutionContext(
                    TENANT_ID, new HashMap<>(), Map.of(), Set.of(),
                    null, null, CALLER_ORG_ID, null);
        }
    }

    // ------------------------------------------------------------------
    // uninstall - remove an acquired app's local clone (F12)
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("uninstall - remove an acquired app's local clone")
    class Uninstall {

        private final UUID cloneId = UUID.randomUUID();

        private void stubClone() {
            WorkflowEntity clone = mock(WorkflowEntity.class);
            lenient().when(clone.getId()).thenReturn(cloneId);
            when(workflowRepository.findByOrganizationIdAndSourcePublicationIdAndWorkflowType(
                    CALLER_ORG_ID, APP_PUB_ID, WorkflowEntity.WorkflowType.APPLICATION))
                    .thenReturn(Optional.of(clone));
        }

        @Test
        @DisplayName("Missing application_id returns MISSING_PARAMETER and never deletes")
        void missingApplicationId() {
            ToolExecutionResult result = module.execute("uninstall", Map.of(),
                    TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            verify(workflowManagementService, never()).deleteWorkflow(any(), any());
        }

        @Test
        @DisplayName("Not-in-allowed-list returns PERMISSION_DENIED before any lookup or delete")
        void notInAllowedList() {
            Map<String, Object> credentials = new HashMap<>();
            credentials.put("allowedApplicationIds", List.of("some-other-id"));
            ToolExecutionContext ctx = new ToolExecutionContext(
                    TENANT_ID, credentials, Map.of(), Set.of(), null, null, CALLER_ORG_ID, null);

            ToolExecutionResult result = module.execute("uninstall",
                    Map.of("application_id", APP_PUB_ID.toString()), TENANT_ID, ctx).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            verify(workflowManagementService, never()).deleteWorkflow(any(), any());
        }

        @Test
        @DisplayName("No local clone returns RESOURCE_NOT_FOUND (idempotent) and never deletes")
        void noCloneIsIdempotentNotFound() {
            when(workflowRepository.findByOrganizationIdAndSourcePublicationIdAndWorkflowType(
                    CALLER_ORG_ID, APP_PUB_ID, WorkflowEntity.WorkflowType.APPLICATION))
                    .thenReturn(Optional.empty());

            ToolExecutionResult result = module.execute("uninstall",
                    Map.of("application_id", APP_PUB_ID.toString()), TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
            verify(workflowManagementService, never()).deleteWorkflow(any(), any());
        }

        @Test
        @DisplayName("Deletes the local clone via the cascade delete and returns the removed workflow id")
        void deletesCloneAndReportsId() {
            stubClone();
            when(workflowManagementService.deleteWorkflow(cloneId, TENANT_ID)).thenReturn(true);

            ToolExecutionResult result = module.execute("uninstall",
                    Map.of("application_id", APP_PUB_ID.toString()), TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat(data.get("status")).isEqualTo("OK");
            assertThat(data.get("application_id")).isEqualTo(APP_PUB_ID.toString());
            assertThat(data.get("removed_workflow_id")).isEqualTo(cloneId.toString());
            verify(workflowManagementService).deleteWorkflow(cloneId, TENANT_ID);
        }

        @Test
        @DisplayName("A failed cascade delete (returns false) surfaces EXECUTION_FAILED")
        void deleteFalseFails() {
            stubClone();
            when(workflowManagementService.deleteWorkflow(cloneId, TENANT_ID)).thenReturn(false);

            ToolExecutionResult result = module.execute("uninstall",
                    Map.of("application_id", APP_PUB_ID.toString()), TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private ToolExecutionContext contextWithOrg() {
        return new ToolExecutionContext(
                TENANT_ID, Map.of(), Map.of(), Set.of(),
                null, null, CALLER_ORG_ID, null);
    }
}
