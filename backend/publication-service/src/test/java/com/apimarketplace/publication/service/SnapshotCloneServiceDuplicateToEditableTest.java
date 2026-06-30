package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceCreateRequest;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.service.resource.DataSourceFileCloneService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Decouple-to-editable-workflow engine ({@link SnapshotCloneService#duplicateToEditableWorkflow})
 * + a CHARACTERIZATION lock on the real acquire path.
 *
 * <p>The same core ({@code cloneFromSnapshotInternal}) now backs two paths via two ids
 * threaded SEPARATELY:
 * <ul>
 *   <li>{@code sourcePublicationId} (NULLABLE) - the marketplace source tag stamped on
 *       the created workflow rows + datasource snapshots. {@code null} for a decoupled
 *       editable duplicate; OMITTED from every create request when null.</li>
 *   <li>{@code fileNamespaceId} (NEVER null) - backs the {@code _publications/{id}/}
 *       file-namespace allowlist + datasource/agent file re-upload. Threading it
 *       separately is what lets the source tag be null without the file namespace
 *       degrading to {@code _publications/null/} and silently dropping every cloned file.</li>
 * </ul>
 *
 * <p>The characterization test pins that the REAL acquire path
 * ({@code cloneFromSnapshot}, where fileNamespaceId == sourcePublicationId == publicationId)
 * is unchanged by the refactor: APPLICATION root, source-tagged rows/datasources, files
 * under the publication namespace, and NO provenance metadata.
 */
@DisplayName("SnapshotCloneService - duplicateToEditableWorkflow + real-acquire characterization")
class SnapshotCloneServiceDuplicateToEditableTest {

    private static final String TENANT = "acquirer";
    private static final String ORG = "org-1";
    private static final UUID PUBLICATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    // A SYNTHETIC file namespace distinct from any publication id - proves the allowlist
    // tracks fileNamespaceId, not sourcePublicationId (which is null on the duplicate path).
    private static final UUID FILE_NS = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String APP_WORKFLOW_ID = "33333333-3333-3333-3333-333333333333";
    private static final String SUB_ID = "44444444-4444-4444-4444-444444444444";

    private OrchestratorInternalClient orchestratorClient;
    private AgentClient agentClient;
    private InterfaceClient interfaceClient;
    private DataSourceClient dataSourceClient;
    private SnapshotCloneService service;

    @BeforeEach
    void setUp() {
        orchestratorClient = mock(OrchestratorInternalClient.class);
        agentClient = mock(AgentClient.class);
        interfaceClient = mock(InterfaceClient.class);
        dataSourceClient = mock(DataSourceClient.class);
        service = new SnapshotCloneService(
                orchestratorClient,
                agentClient,
                interfaceClient,
                dataSourceClient,
                mock(StorageBreakdownService.class),
                new ObjectMapper(),
                mock(DataSourceFileCloneService.class));
        when(orchestratorClient.createApplicationWorkflow(any(), anyString()))
                .thenReturn(Map.of("id", UUID.randomUUID().toString()));
    }

    // ------------------------------------------------------------------
    // Plan builders
    // ------------------------------------------------------------------

    /** A plan with a datasource, an agent, and an interface carrying a FileRef under {@code _publications/{ns}/}. */
    private static Map<String, Object> richPlan(UUID fileNamespace) {
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("dataSourceId", "42");
        table.put("_snapshot_ds_name", "Customers");
        table.put("_snapshot_ds_sourceType", "INLINE");

        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("agentConfigId", "agent-old");
        agent.put("_snapshot_agent_name", "Helper");

        Map<String, Object> fileRef = new LinkedHashMap<>();
        fileRef.put("_type", "file");
        fileRef.put("path", "_publications/" + fileNamespace + "/img.png");
        fileRef.put("name", "img.png");
        fileRef.put("mimeType", "image/png");
        Map<String, Object> ifaceData = new LinkedHashMap<>();
        ifaceData.put("hero", fileRef);

        Map<String, Object> iface = new LinkedHashMap<>();
        iface.put("id", "iface-old");
        iface.put("_snapshot_htmlTemplate", "<html></html>");
        iface.put("_snapshot_name", "Home");
        iface.put("_snapshot_data", ifaceData);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("tables", new ArrayList<>(List.of(table)));
        plan.put("agents", new ArrayList<>(List.of(agent)));
        plan.put("interfaces", new ArrayList<>(List.of(iface)));
        return plan;
    }

    private void wireResourceMocks() {
        DataSourceDto clonedDs = mock(DataSourceDto.class);
        when(clonedDs.id()).thenReturn(99L);
        when(dataSourceClient.createFromSnapshot(any(), anyString())).thenReturn(clonedDs);

        InterfaceDto savedIface = mock(InterfaceDto.class);
        when(savedIface.getId()).thenReturn(UUID.fromString("55555555-5555-5555-5555-555555555555"));
        when(interfaceClient.createInterface(any(InterfaceCreateRequest.class), anyString())).thenReturn(savedIface);

        when(agentClient.cloneFromSnapshot(any()))
                .thenReturn(Map.of("agentId", UUID.randomUUID().toString()));
        when(orchestratorClient.copyFile(any(), any()))
                .thenReturn(Map.of("newPath", TENANT + "/copied/img.png", "newId", UUID.randomUUID().toString()));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> capturedCreateRequests() {
        ArgumentCaptor<Map<String, Object>> captor =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(orchestratorClient, atLeastOnce()).createApplicationWorkflow(captor.capture(), anyString());
        return captor.getAllValues();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedDatasourceSnapshot() {
        ArgumentCaptor<Map<String, Object>> captor =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(dataSourceClient).createFromSnapshot(captor.capture(), anyString());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedAgentCloneRequest() {
        ArgumentCaptor<Map<String, Object>> captor =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(agentClient).cloneFromSnapshot(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedCopyFileRequest() {
        ArgumentCaptor<Map<String, Object>> captor =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(orchestratorClient).copyFile(captor.capture(), any());
        return captor.getValue();
    }

    // ------------------------------------------------------------------
    // Characterization: real acquire path is unchanged
    // ------------------------------------------------------------------

    @Test
    @DisplayName("CHARACTERIZATION: cloneFromSnapshot (real acquire) -> APPLICATION root, source-tagged rows + datasource, files under the publication namespace, NO provenance metadata")
    void realAcquirePath_unchanged_application_sourceTagged_noMetadata() {
        wireResourceMocks();

        service.cloneFromSnapshot(richPlan(PUBLICATION_ID), TENANT, PUBLICATION_ID, "App", "desc", null, ORG);

        Map<String, Object> root = capturedCreateRequests().get(0);
        assertThat(root.get("workflowType")).isEqualTo("APPLICATION");
        assertThat(root.get("sourcePublicationId")).isEqualTo(PUBLICATION_ID.toString());
        // The real acquire path never stamps provenance metadata.
        assertThat(root).doesNotContainKey("metadata");

        // Datasource snapshot is stamped with the source publication.
        assertThat(capturedDatasourceSnapshot().get("sourcePublicationId")).isEqualTo(PUBLICATION_ID.toString());
        // Agent file namespace is the publication id.
        assertThat(capturedAgentCloneRequest().get("publicationId")).isEqualTo(PUBLICATION_ID.toString());
        // The interface FileRef is copied because its path is under _publications/{publicationId}/.
        assertThat(capturedCopyFileRequest().get("sourcePath").toString())
                .startsWith("_publications/" + PUBLICATION_ID + "/");
    }

    // ------------------------------------------------------------------
    // Duplicate path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("duplicateToEditableWorkflow -> root is a decoupled WORKFLOW: NO sourcePublicationId, lineage in metadata.duplicatedFromApplicationId")
    void duplicate_rootIsDecoupledWorkflowWithLineageMetadata() {
        wireResourceMocks();

        service.duplicateToEditableWorkflow(richPlan(FILE_NS), TENANT, ORG, "App", "desc", null,
                FILE_NS, APP_WORKFLOW_ID);

        Map<String, Object> root = capturedCreateRequests().get(0);
        assertThat(root.get("workflowType")).isEqualTo("WORKFLOW");
        // Decoupled: the source tag is omitted entirely (not "null") so the row is exempt
        // from the V268 partial unique index + every APPLICATION lookup.
        assertThat(root).doesNotContainKey("sourcePublicationId");
        // Lineage travels in metadata, NEVER in source_publication_id.
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) root.get("metadata");
        assertThat(metadata).containsEntry("duplicatedFromApplicationId", APP_WORKFLOW_ID);
    }

    @Test
    @DisplayName("duplicateToEditableWorkflow -> the cloned datasource snapshot OMITS sourcePublicationId (decoupled)")
    void duplicate_datasourceSnapshotOmitsSourcePublicationId() {
        wireResourceMocks();

        service.duplicateToEditableWorkflow(richPlan(FILE_NS), TENANT, ORG, "App", "desc", null,
                FILE_NS, APP_WORKFLOW_ID);

        assertThat(capturedDatasourceSnapshot()).doesNotContainKey("sourcePublicationId");
    }

    @Test
    @DisplayName("duplicateToEditableWorkflow -> files resolve via the SYNTHETIC fileNamespaceId even though sourcePublicationId is null (no file is dropped)")
    void duplicate_filesUseFileNamespaceIdNotNullSource() {
        wireResourceMocks();

        service.duplicateToEditableWorkflow(richPlan(FILE_NS), TENANT, ORG, "App", "desc", null,
                FILE_NS, APP_WORKFLOW_ID);

        // The allowlist is _publications/{fileNamespaceId}/ - the FileRef under that namespace is copied.
        assertThat(capturedCopyFileRequest().get("sourcePath").toString())
                .startsWith("_publications/" + FILE_NS + "/");
        // The agent's file namespace is the same synthetic id (never null).
        assertThat(capturedAgentCloneRequest().get("publicationId")).isEqualTo(FILE_NS.toString());
    }

    @Test
    @DisplayName("duplicateToEditableWorkflow -> a FileRef OUTSIDE the fileNamespaceId allowlist is left untouched (defense in depth)")
    void duplicate_fileRefOutsideNamespaceIsNotCopied() {
        wireResourceMocks();
        // FileRef lives under a DIFFERENT namespace than the one we pass -> must be skipped.
        Map<String, Object> plan = richPlan(PUBLICATION_ID); // path under _publications/{PUBLICATION_ID}/

        service.duplicateToEditableWorkflow(plan, TENANT, ORG, "App", "desc", null,
                FILE_NS /* mismatching namespace */, APP_WORKFLOW_ID);

        // No copy happened - the path was not under _publications/{FILE_NS}/.
        org.mockito.Mockito.verify(orchestratorClient, org.mockito.Mockito.never()).copyFile(any(), any());
    }

    @Test
    @DisplayName("duplicateToEditableWorkflow -> sub-workflows are WORKFLOW rows with NO sourcePublicationId either")
    void duplicate_subWorkflowsAreDecoupledWorkflows() {
        Map<String, Object> subSnapshot = new LinkedHashMap<>();
        subSnapshot.put("name", "Sub");
        subSnapshot.put("plan", new LinkedHashMap<>(Map.of("triggers", List.of(), "cores", List.of())));
        Map<String, Object> subWorkflows = new LinkedHashMap<>();
        subWorkflows.put(SUB_ID, subSnapshot);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", new ArrayList<>());
        plan.put("cores", new ArrayList<>());
        plan.put("_snapshot_subworkflows", subWorkflows);

        service.duplicateToEditableWorkflow(plan, TENANT, ORG, "App", "desc", null, FILE_NS, APP_WORKFLOW_ID);

        List<Map<String, Object>> requests = capturedCreateRequests();
        assertThat(requests).hasSize(2);
        assertThat(requests).allSatisfy(r -> {
            assertThat(r.get("workflowType")).isEqualTo("WORKFLOW");
            assertThat(r).doesNotContainKey("sourcePublicationId");
        });
    }

    @Test
    @DisplayName("duplicateToEditableWorkflow -> a null fileNamespaceId is rejected (it would degrade the file namespace to _publications/null/)")
    void duplicate_nullFileNamespaceRejected() {
        assertThatThrownBy(() -> service.duplicateToEditableWorkflow(
                richPlan(FILE_NS), TENANT, ORG, "App", "desc", null, null, APP_WORKFLOW_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fileNamespaceId");
    }
}
