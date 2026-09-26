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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
        // Run reports resolve their plan through the version service (ids only, never the run's lazy workflow).
        lenient().when(planVersionService.resolvePlanForRun(any(), any(), any()))
                .thenReturn(new com.apimarketplace.orchestrator.services.WorkflowPlanVersionService.RunPlan(null, null));
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
    @DisplayName("search - CE-exclusive annotation is edition-aware")
    class CeExclusiveAnnotation {

        private Map<String, Object> searchPageWithCeExclusiveApp() {
            Map<String, Object> pub = new HashMap<>();
            pub.put("id", APP_PUB_ID.toString());
            pub.put("title", "RAG App");
            pub.put("ceExclusive", true);
            pub.put("ceExclusiveFeatures", java.util.List.of("CLI_AGENT"));
            return Map.of("content", java.util.List.of(pub), "totalElements", 1);
        }

        /** The envelope names its items list per Spec; find it rather than hardcode the key. */
        @SuppressWarnings("unchecked")
        private Map<String, Object> firstItem(ToolExecutionResult result) {
            Map<String, Object> data = (Map<String, Object>) result.data();
            return data.values().stream()
                    .filter(java.util.List.class::isInstance)
                    .map(v -> (java.util.List<Map<String, Object>>) v)
                    .filter(list -> !list.isEmpty())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no items in envelope: " + data))
                    .get(0);
        }

        private void setEdition(String value) {
            org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
            env.setProperty("app.edition", value);
            org.springframework.test.util.ReflectionTestUtils.setField(module, "appEditionProvider",
                    new com.apimarketplace.common.web.AppEditionProvider(env));
        }

        @Test
        @DisplayName("managed cloud: the app is annotated so the agent warns BEFORE a refused acquire")
        void annotatedOnManagedCloud() {
            setEdition("cloud");
            when(publicationClient.getMarketplacePublications(anyInt(), anyInt()))
                    .thenReturn(searchPageWithCeExclusiveApp());

            ToolExecutionResult result = module.execute("search", Map.of(), TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(firstItem(result))
                    .containsEntry("ce_exclusive", true)
                    .containsEntry("ce_exclusive_features", java.util.List.of("CLI_AGENT"));
        }

        @Test
        @DisplayName("self-hosted: the SAME app is NOT annotated - this deployment can install it")
        void notAnnotatedOnSelfHosted() {
            // A self-hosted install browses the same (proxied) cloud catalogue.
            // Annotating there would make its agent decline an install that works.
            setEdition("ce");
            when(publicationClient.getMarketplacePublications(anyInt(), anyInt()))
                    .thenReturn(searchPageWithCeExclusiveApp());

            ToolExecutionResult result = module.execute("search", Map.of(), TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(firstItem(result))
                    .doesNotContainKey("ce_exclusive")
                    .doesNotContainKey("ce_exclusive_features");
        }

        @Test
        @DisplayName("a normal app is never annotated, on either edition")
        void normalAppNeverAnnotated() {
            setEdition("cloud");
            Map<String, Object> pub = new HashMap<>();
            pub.put("id", APP_PUB_ID.toString());
            pub.put("title", "Plain App");
            when(publicationClient.getMarketplacePublications(anyInt(), anyInt()))
                    .thenReturn(Map.of("content", java.util.List.of(pub), "totalElements", 1));

            ToolExecutionResult result = module.execute("search", Map.of(), TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(firstItem(result)).doesNotContainKey("ce_exclusive");
        }
    }

    @Nested
    @DisplayName("search - the studio axis reaches the agent")
    class StudioAxisAnnotation {

        /** Same envelope-shape reader as the CE-exclusive suite above. */
        @SuppressWarnings("unchecked")
        private Map<String, Object> firstItem(ToolExecutionResult result) {
            Map<String, Object> data = (Map<String, Object>) result.data();
            return data.values().stream()
                    .filter(java.util.List.class::isInstance)
                    .map(v -> (java.util.List<Map<String, Object>>) v)
                    .filter(list -> !list.isEmpty())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no items in envelope: " + data))
                    .get(0);
        }

        private void stubSearchWith(Object studioValue) {
            Map<String, Object> pub = new HashMap<>();
            pub.put("id", APP_PUB_ID.toString());
            pub.put("title", "Clip Studio");
            if (studioValue != null) pub.put("studio", studioValue);
            when(publicationClient.getMarketplacePublications(anyInt(), anyInt()))
                    .thenReturn(Map.of("content", java.util.List.of(pub), "totalElements", 1));
        }

        @Test
        @DisplayName("a studio app says so, so the agent can verify what it published")
        void studioAppIsAnnotated() {
            // The agent can SET this axis on create. A value it can write and never read back is one
            // it cannot check, so it would have to trust that the publish did what it asked.
            stubSearchWith(true);

            ToolExecutionResult result = module.execute("search", Map.of(), TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(firstItem(result)).containsEntry("studio", true);
        }

        @Test
        @DisplayName("an ordinary app carries no key at all - absent IS the answer")
        void ordinaryAppIsNotAnnotated() {
            // Deliberately absent rather than false, the same economy ce_exclusive follows: a key
            // per item costs a line in every page of a 50-app listing to say nothing.
            stubSearchWith(false);

            assertThat(firstItem(module.execute("search", Map.of(), TENANT_ID, contextWithOrg()).orElseThrow()))
                    .doesNotContainKey("studio");
        }

        @Test
        @DisplayName("an app from a build that predates the axis is treated as ordinary, not as unknown")
        void missingKeyIsNotAnnotated() {
            // A self-hosted install proxies a cloud catalogue that may be a different version. A
            // missing key must read as "not a studio app", never as a truthy object.
            stubSearchWith(null);

            assertThat(firstItem(module.execute("search", Map.of(), TENANT_ID, contextWithOrg()).orElseThrow()))
                    .doesNotContainKey("studio");
        }
    }

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
        @DisplayName("Reports the acquired clone id from the response's workflowId key (reading only 'id' returned null on every acquire)")
        void acquireReportsTheClonedWorkflowId() {
            // The publication service answers { workflowId, title, resources } - this is the
            // real response shape, not the historical fixture that only carried "id".
            Map<String, Object> stub = new HashMap<>();
            stub.put("workflowId", "wf-9");
            stub.put("title", "My Cloned App");
            stub.put("resources", Map.of("interfaces", 2, "tables", 1));
            when(publicationClient.acquirePublication(eq(APP_PUB_ID), eq(TENANT_ID), eq(CALLER_ORG_ID)))
                    .thenReturn(stub);

            ToolExecutionResult result = module.execute("acquire",
                    Map.of("application_id", APP_PUB_ID.toString()),
                    TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat(data).containsEntry("workflowId", "wf-9");
            // What else the install put in the workspace, so the agent can say so.
            assertThat(data).containsEntry("installed_resources", Map.of("interfaces", 2, "tables", 1));
        }

        @Test
        @DisplayName("Falls back to an 'id'-keyed response so an older/alternate acquire shape still yields a workflow id")
        void acquireFallsBackToTheIdKey() {
            Map<String, Object> stub = new HashMap<>();
            stub.put("id", "wf-legacy");
            stub.put("title", "My Cloned App");
            when(publicationClient.acquirePublication(eq(APP_PUB_ID), eq(TENANT_ID), eq(CALLER_ORG_ID)))
                    .thenReturn(stub);

            ToolExecutionResult result = module.execute("acquire",
                    Map.of("application_id", APP_PUB_ID.toString()),
                    TENANT_ID, contextWithOrg()).orElseThrow();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat(data).containsEntry("workflowId", "wf-legacy");
        }

        @Test
        @DisplayName("An install that created nothing beyond the application omits the resource summary entirely")
        void acquireOmitsAnEmptyResourceSummary() {
            Map<String, Object> stub = new HashMap<>();
            stub.put("workflowId", "wf-9");
            stub.put("title", "My Cloned App");
            stub.put("resources", Map.of());
            when(publicationClient.acquirePublication(eq(APP_PUB_ID), eq(TENANT_ID), eq(CALLER_ORG_ID)))
                    .thenReturn(stub);

            ToolExecutionResult result = module.execute("acquire",
                    Map.of("application_id", APP_PUB_ID.toString()),
                    TENANT_ID, contextWithOrg()).orElseThrow();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat(data).doesNotContainKey("installed_resources");
        }

        @Test
        @DisplayName("A CE-exclusive refusal is reported as PERMISSION_DENIED with the real reason, not a generic failure")
        void ceExclusiveRefusalIsPermissionDenied() {
            // The deployment cannot run the app: terminal for the agent. A generic
            // EXECUTION_FAILED would read as transient and get retried, and the
            // user would never learn the app needs a self-hosted install.
            when(publicationClient.acquirePublication(eq(APP_PUB_ID), eq(TENANT_ID), eq(CALLER_ORG_ID)))
                    .thenThrow(new com.apimarketplace.publication.client.CeExclusiveAcquisitionException(
                            "This app is Community Edition exclusive: it uses features that only run "
                                    + "on a self-hosted install.", java.util.List.of("CLI_AGENT")));

            ToolExecutionResult result = module.execute("acquire",
                    Map.of("application_id", APP_PUB_ID.toString()),
                    TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            assertThat(result.error()).contains("self-hosted");
        }

        @Test
        @DisplayName("A PLAN refusal is PERMISSION_DENIED too, but tells the agent an upgrade lifts it")
        void planRefusalNamesThePlanAndIsNotTerminal() {
            // Same error code as the CE case above and deliberately a different message: the CE
            // refusal is documented to the agent as terminal, so reporting this one through it
            // would make the agent tell the user to give up on an install one upgrade away.
            when(publicationClient.acquirePublication(eq(APP_PUB_ID), eq(TENANT_ID), eq(CALLER_ORG_ID)))
                    .thenThrow(new com.apimarketplace.publication.client.PublicationPlanUpgradeException(
                            "This app uses vector search (embedding columns), which is available "
                                    + "from the PRO plan.", "PRO", java.util.List.of("VECTOR_SEARCH")));

            ToolExecutionResult result = module.execute("acquire",
                    Map.of("application_id", APP_PUB_ID.toString()),
                    TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            assertThat(result.error())
                    .contains("PRO")
                    // The agent cannot buy a plan; it must hand the decision back.
                    .contains("cannot change the plan yourself")
                    .doesNotContain("self-hosted");
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

        /** A published-app map as the publisher listing returns it. */
        private Map<String, Object> publishedApp(String title, List<String> nodeTypes) {
            Map<String, Object> app = new HashMap<>();
            app.put("id", UUID.randomUUID().toString());
            app.put("title", title);
            if (nodeTypes != null) app.put("nodeTypes", nodeTypes);
            return app;
        }

        private ToolExecutionContext noOrgContext() {
            return new ToolExecutionContext(TENANT_ID, Map.of(), Map.of(), Set.of(), null, null, null, null);
        }

        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> appsOf(ToolExecutionResult result) {
            return (List<Map<String, Object>>) ((Map<String, Object>) result.data()).get("applications");
        }

        @Test
        @DisplayName("node_types narrows the publisher listing to the apps that use that node")
        void myNodeTypesFiltersPublisherList() {
            // The no-org branch reads publication MAPS, not workflow rows: it can only
            // filter if the publisher listing actually carries nodeTypes. It did not,
            // and this filter silently answered "you have 0 applications".
            when(publicationClient.getPublicationsByPublisher(TENANT_ID)).thenReturn(List.of(
                    publishedApp("Gmail Tool", List.of("mcp:gmail", "core:loop")),
                    publishedApp("Slack Tool", List.of("mcp:slack"))));
            lenient().when(credentialClient.getConfiguredIntegrations(TENANT_ID)).thenReturn(Set.of());

            ToolExecutionResult result = module.execute(
                    "my", Map.of("node_types", List.of("mcp:gmail")), TENANT_ID, noOrgContext())
                    .orElseThrow();

            assertThat(result.success()).isTrue();
            assertThat(appsOf(result)).extracting(a -> a.get("title")).containsExactly("Gmail Tool");
        }

        @Test
        @DisplayName("every listed application reports its own node_types, so the agent need not guess a token")
        void myItemsCarryTheirNodeTypes() {
            when(publicationClient.getPublicationsByPublisher(TENANT_ID)).thenReturn(List.of(
                    publishedApp("Gmail Tool", List.of("mcp:gmail", "core:loop"))));
            lenient().when(credentialClient.getConfiguredIntegrations(TENANT_ID)).thenReturn(Set.of());

            ToolExecutionResult result = module.execute("my", Map.of(), TENANT_ID, noOrgContext())
                    .orElseThrow();

            assertThat((List<String>) appsOf(result).get(0).get("node_types"))
                    .containsExactly("mcp:gmail", "core:loop");
        }

        @Test
        @DisplayName("an application published before node types existed is dropped by the filter, not by an error")
        void myAppWithoutNodeTypesIsFilteredOut() {
            when(publicationClient.getPublicationsByPublisher(TENANT_ID)).thenReturn(List.of(
                    publishedApp("Legacy Tool", null)));
            lenient().when(credentialClient.getConfiguredIntegrations(TENANT_ID)).thenReturn(Set.of());

            ToolExecutionResult result = module.execute(
                    "my", Map.of("node_types", List.of("mcp:gmail")), TENANT_ID, noOrgContext())
                    .orElseThrow();

            assertThat(result.success()).isTrue();
            assertThat(appsOf(result)).isEmpty();
        }

        @Test
        @DisplayName("an empty node_types list leaves the listing alone")
        void myEmptyNodeTypesIsNoFilter() {
            when(publicationClient.getPublicationsByPublisher(TENANT_ID)).thenReturn(List.of(
                    publishedApp("Gmail Tool", List.of("mcp:gmail")),
                    publishedApp("Slack Tool", List.of("mcp:slack"))));
            lenient().when(credentialClient.getConfiguredIntegrations(TENANT_ID)).thenReturn(Set.of());

            ToolExecutionResult result = module.execute(
                    "my", Map.of("node_types", List.of()), TENANT_ID, noOrgContext()).orElseThrow();

            assertThat(appsOf(result)).hasSize(2);
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

        /** An acquired clone reporting the given node types, plus its publication. */
        private UUID stubAcquired(String title, List<String> nodeTypes) {
            UUID pubId = UUID.randomUUID();
            WorkflowEntity clone = mock(WorkflowEntity.class);
            lenient().when(clone.getSourcePublicationId()).thenReturn(pubId);
            lenient().when(clone.getNodeTypes()).thenReturn(nodeTypes);
            Map<String, Object> pub = new HashMap<>();
            pub.put("id", pubId.toString());
            pub.put("title", title);
            // The publication carries a DIFFERENT token set on purpose: the source was
            // re-published after this clone was acquired. Without a conflicting value
            // here the override under test would pass by doing nothing.
            pub.put("nodeTypes", List.of("mcp:republished_since"));
            lenient().when(publicationClient.getPublicationById(pubId)).thenReturn(pub);
            acquiredClones.add(clone);
            return pubId;
        }

        private final List<WorkflowEntity> acquiredClones = new ArrayList<>();

        private ToolExecutionResult listMyInOrg(Map<String, Object> params) {
            when(workflowRepository.findAcquiredByOrganizationId(
                    CALLER_ORG_ID, WorkflowEntity.WorkflowType.APPLICATION))
                    .thenReturn(List.copyOf(acquiredClones));
            lenient().when(credentialClient.getConfiguredIntegrations(TENANT_ID)).thenReturn(Set.of());
            return module.execute("my", params, TENANT_ID, contextWithOrg()).orElseThrow();
        }

        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> orgApps(ToolExecutionResult result) {
            return (List<Map<String, Object>>) ((Map<String, Object>) result.data()).get("applications");
        }

        @Test
        @DisplayName("org scope: node_types keeps only the apps whose acquired plan uses that node")
        void orgNodeTypesFiltersAcquiredApps() {
            stubAcquired("Gmail App", List.of("mcp:gmail", "core:loop"));
            stubAcquired("Slack App", List.of("mcp:slack"));

            ToolExecutionResult result = listMyInOrg(Map.of("node_types", List.of("mcp:gmail")));

            assertThat(orgApps(result)).extracting(a -> a.get("title")).containsExactly("Gmail App");
        }

        @Test
        @DisplayName("org scope: the node_types an item reports are the ones the filter matched on")
        void orgNodeTypesEchoMatchesTheFilterSource() {
            // The filter reads the acquired CLONE's plan while the item itself is the
            // publication, whose stored tokens can differ (a clone is frozen at acquire
            // time, the publication can be re-published since). Reporting one while
            // filtering on the other breaks the loop the help promises: the agent reads
            // a token off an item and gets that very item filtered away.
            stubAcquired("Gmail App", List.of("mcp:gmail"));

            ToolExecutionResult result = listMyInOrg(Map.of());

            assertThat((List<String>) orgApps(result).get(0).get("node_types"))
                    .as("the CLONE's tokens, not the publication's - the publication says "
                      + "mcp:republished_since here, and reporting that would hand the agent "
                      + "a token this branch does not filter on")
                    .containsExactly("mcp:gmail");
        }

        @Test
        @DisplayName("org scope: total counts the filtered set, so paging is not computed on the unfiltered one")
        void orgNodeTypesFiltersBeforePagination() {
            stubAcquired("Gmail App", List.of("mcp:gmail"));
            stubAcquired("Slack App", List.of("mcp:slack"));
            stubAcquired("Notion App", List.of("mcp:notion"));

            ToolExecutionResult result = listMyInOrg(Map.of("node_types", List.of("mcp:gmail")));

            assertThat(((Map<String, Object>) result.data()).get("total")).isEqualTo(1L);
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

        /** Everything a successful create needs stubbed, minus the parameters under test. */
        private ArgumentCaptor<Map<String, Object>> createAndCapture(Map<String, Object> params) {
            WorkflowEntity wf = stubWorkflowWithInterface();
            when(wf.getName()).thenReturn("Clip Studio");
            when(wf.getDescription()).thenReturn("desc");
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.COMPLETED);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getRunIdPublic()).thenReturn("run-public-1");
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDescPageable(eq(workflowId), any()))
                    .thenReturn(new PageImpl<>(List.of(run)));
            when(publicationClient.publishWorkflow(any(), eq(TENANT_ID), eq(CALLER_ORG_ID)))
                    .thenReturn(Map.of("id", createdPubId.toString()));

            module.execute("create", params, TENANT_ID, createCtxMutableCreds()).orElseThrow();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(publicationClient).publishWorkflow(captor.capture(), eq(TENANT_ID), eq(CALLER_ORG_ID));
            return captor;
        }

        @Test
        @DisplayName("studio=true is forwarded, or an agent can ask for the shelf and never reach it")
        void studioTrueIsForwarded() {
            // The agent can name this axis and reads it back on get/search/my. Without the
            // forwarding it would ask for the Studio shelf, get a 200, and read back an application
            // that is not on it - with nothing failing anywhere to say why.
            Map<String, Object> params = new HashMap<>();
            params.put("workflow_id", workflowId.toString());
            params.put("studio", true);

            assertThat(createAndCapture(params).getValue()).containsEntry("studio", true);
        }

        @Test
        @DisplayName("studio=false is forwarded too - unticking is an instruction, not a silence")
        void studioFalseIsForwarded() {
            Map<String, Object> params = new HashMap<>();
            params.put("workflow_id", workflowId.toString());
            params.put("studio", false);

            assertThat(createAndCapture(params).getValue()).containsEntry("studio", false);
        }

        @Test
        @DisplayName("An omitted axis is NOT put in the publish Map, so a re-publish leaves the shelf alone")
        void omittedStudioIsNotForwarded() {
            // Absent has to stay absent the whole way down: publication-service reads a missing key
            // as "no opinion". Sending a default false here would take an application off the
            // studio shelf every time an agent re-published it without mentioning the axis.
            assertThat(createAndCapture(new HashMap<>(Map.of("workflow_id", workflowId.toString()))).getValue())
                    .doesNotContainKey("studio");
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

    // ==================== pruned plan version + epoch_count (application surface) ====================

    @Nested
    @DisplayName("run reads: pruned plan version and epoch_count")
    class RunReadsTests {

        private WorkflowEntity lazyWorkflow;
        private WorkflowRunEntity run;
        private final UUID workflowId = UUID.randomUUID();

        @BeforeEach
        void prunedRun() {
            lazyWorkflow = mock(WorkflowEntity.class);
            lenient().when(lazyWorkflow.getId()).thenReturn(workflowId);
            lenient().when(lazyWorkflow.getPlan()).thenThrow(new org.hibernate.LazyInitializationException("no session"));
            run = new WorkflowRunEntity();
            run.setRunIdPublic("run-pruned");
            run.setTenantId(TENANT_ID);
            run.setOrganizationId(CALLER_ORG_ID);
            run.setStatus(RunStatus.COMPLETED);
            run.setPlanVersion(2);
            run.setWorkflow(lazyWorkflow);
            lenient().when(workflowRunRepository.findByRunIdPublic("run-pruned")).thenReturn(Optional.of(run));
            lenient().when(planVersionService.resolvePlanForRun(workflowId, 2, TENANT_ID))
                    .thenReturn(new com.apimarketplace.orchestrator.services.WorkflowPlanVersionService.RunPlan(null, 2));
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> dataOf(Optional<ToolExecutionResult> result) {
            assertThat(result.get().success()).as(String.valueOf(result.get().error())).isTrue();
            return (Map<String, Object>) result.get().data();
        }

        @Test
        @DisplayName("regression: get_run on a pruned version answers with plan_note (kept through the application hint rewrite)")
        void getRunPruned() {
            when(agentWorkflowFireService.buildRunMacroReport(eq(run), any(), eq(TENANT_ID)))
                    .thenReturn(Map.of("run_id", "run-pruned", "NEXT", "workflow(action='get_run', run_id='run-pruned', epoch=1)"));

            Map<String, Object> data = dataOf(module.execute("get_run",
                    Map.of("run_id", "run-pruned"), TENANT_ID, contextWithOrg()));

            assertThat((String) data.get("plan_note")).contains("Plan version 2");
            assertThat((String) data.get("NEXT")).startsWith("application(action='get_run'");
            verify(lazyWorkflow, never()).getPlan();
        }

        @Test
        @DisplayName("regression: get_run epoch=N on a pruned version")
        void getRunEpochPruned() {
            when(agentWorkflowFireService.buildEpochDetailReport(eq(run), any(), eq(1), eq(TENANT_ID)))
                    .thenReturn(Map.of("epoch", 1));

            Map<String, Object> data = dataOf(module.execute("get_run",
                    Map.of("run_id", "run-pruned", "epoch", 1), TENANT_ID, contextWithOrg()));

            assertThat(data).containsEntry("epoch", 1).containsKey("plan_note");
            verify(lazyWorkflow, never()).getPlan();
        }

        @Test
        @DisplayName("regression: get_node_output on a pruned version")
        void nodeOutputPruned() {
            when(agentWorkflowFireService.buildNodeOutputReport(eq(run), any(), eq(1), eq("core:x"), eq(TENANT_ID),
                    any(), any(), any(), any(), any(), any())).thenReturn(Map.of("node_id", "core:x"));

            Map<String, Object> data = dataOf(module.execute("get_node_output",
                    Map.of("run_id", "run-pruned", "epoch", 1, "node_id", "core:x"), TENANT_ID, contextWithOrg()));

            assertThat(data).containsEntry("node_id", "core:x").containsKey("plan_note");
            verify(lazyWorkflow, never()).getPlan();
        }

        private void stubAcquiredApp() {
            WorkflowEntity clone = mock(WorkflowEntity.class);
            when(clone.getId()).thenReturn(workflowId);
            when(workflowRepository.findByOrganizationIdAndSourcePublicationIdAndWorkflowType(
                    CALLER_ORG_ID, APP_PUB_ID, WorkflowEntity.WorkflowType.APPLICATION))
                    .thenReturn(Optional.of(clone));
        }

        private com.apimarketplace.orchestrator.repository.WorkflowRunSummaryProjection summary(String runId) {
            var p = mock(com.apimarketplace.orchestrator.repository.WorkflowRunSummaryProjection.class);
            when(p.getRunIdPublic()).thenReturn(runId);
            return p;
        }

        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> runsOf(Optional<ToolExecutionResult> result) {
            return (List<Map<String, Object>>) dataOf(result).get("runs");
        }

        @Test
        @DisplayName("runs: epoch_count per run, 0 for a run that never fired")
        void runsCarryEpochCount() {
            stubAcquiredApp();
            var s1 = summary("run-1");
            var s2 = summary("run-2");
            when(workflowRunRepository.findRunSummariesByWorkflowId(eq(workflowId), any()))
                    .thenReturn(new PageImpl<>(List.of(s1, s2), org.springframework.data.domain.PageRequest.of(0, 20), 2));
            when(agentWorkflowFireService.countEpochsByRunIds(List.of("run-1", "run-2"))).thenReturn(Map.of("run-1", 9L));

            List<Map<String, Object>> runs = runsOf(module.execute("runs",
                    Map.of("application_id", APP_PUB_ID.toString()), TENANT_ID, contextWithOrg()));

            assertThat(runs.get(0)).containsEntry("epoch_count", 9L);
            assertThat(runs.get(1)).containsEntry("epoch_count", 0L);
        }

        @Test
        @DisplayName("runs: epoch counts unavailable -> field omitted, never a false 0")
        void runsOmitEpochCountWhenUnavailable() {
            stubAcquiredApp();
            var s1 = summary("run-1");
            when(workflowRunRepository.findRunSummariesByWorkflowId(eq(workflowId), any()))
                    .thenReturn(new PageImpl<>(List.of(s1), org.springframework.data.domain.PageRequest.of(0, 20), 1));
            when(agentWorkflowFireService.countEpochsByRunIds(any())).thenReturn(null);

            List<Map<String, Object>> runs = runsOf(module.execute("runs",
                    Map.of("application_id", APP_PUB_ID.toString()), TENANT_ID, contextWithOrg()));

            assertThat(runs.get(0)).containsEntry("run_id", "run-1").doesNotContainKey("epoch_count");
        }
    }
}
