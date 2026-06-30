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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Acquire-clone remap of a FORM interface's {@code dataSourceId} binding.
 *
 * <p>A FORM-typed interface binds to a backing datasource by numeric PK
 * (snapshotted at publish as {@code _snapshot_dataSourceId}). At runtime
 * {@code InterfaceRenderService} resolves the form's table by this id. When the
 * application is acquired, the datasource is cloned with a NEW id - so the
 * cloned interface MUST adopt that new id, otherwise its form binds the SOURCE
 * tenant's datasource and the tenant-scoped lookup fails at render time.
 *
 * <p>The fix clones datasources BEFORE interfaces and passes the
 * old→new {@code dsMapping} into {@code cloneInterfacesForTenant}. This test
 * drives the public {@link SnapshotCloneService#cloneFromSnapshot} and asserts
 * the created interface carries the CLONED datasource id, not the publisher's.
 */
@DisplayName("SnapshotCloneService - FORM interface dataSourceId remap")
class SnapshotCloneServiceInterfaceDataSourceRemapTest {

    private static final String TENANT = "acquirer";
    private static final UUID PUBLICATION_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final long OLD_DS = 42L;
    private static final long NEW_DS = 99L;

    private OrchestratorInternalClient orchestratorClient;
    private InterfaceClient interfaceClient;
    private DataSourceClient dataSourceClient;
    private SnapshotCloneService service;

    @BeforeEach
    void setUp() {
        orchestratorClient = mock(OrchestratorInternalClient.class);
        interfaceClient = mock(InterfaceClient.class);
        dataSourceClient = mock(DataSourceClient.class);
        service = new SnapshotCloneService(
                orchestratorClient,
                mock(AgentClient.class),
                interfaceClient,
                dataSourceClient,
                mock(StorageBreakdownService.class),
                new ObjectMapper(),
                mock(DataSourceFileCloneService.class));
    }

    @Test
    @DisplayName("cloneFromSnapshot remaps a FORM interface's dataSourceId to the cloned datasource id")
    @SuppressWarnings("unchecked")
    void cloneFromSnapshot_remapsFormInterfaceDataSourceId() {
        // tables[] node that owns the datasource (so it is cloned → dsMapping has it)
        Map<String, Object> tableNode = new LinkedHashMap<>();
        tableNode.put("dataSourceId", String.valueOf(OLD_DS));
        tableNode.put("_snapshot_ds_name", "Customers");
        tableNode.put("_snapshot_ds_sourceType", "INLINE");

        // FORM interface bound to that same datasource by numeric id
        Map<String, Object> ifaceNode = new LinkedHashMap<>();
        ifaceNode.put("id", "iface-old");
        ifaceNode.put("_snapshot_htmlTemplate", "<form></form>");
        ifaceNode.put("_snapshot_name", "Signup Form");
        ifaceNode.put("_snapshot_targetTable", "Customers");
        ifaceNode.put("_snapshot_dataSourceId", OLD_DS);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("tables", new ArrayList<>(List.of(tableNode)));
        plan.put("interfaces", new ArrayList<>(List.of(ifaceNode)));

        // Datasource clone returns the NEW id.
        DataSourceDto clonedDs = mock(DataSourceDto.class);
        when(clonedDs.id()).thenReturn(NEW_DS);
        when(dataSourceClient.createFromSnapshot(any(), anyString())).thenReturn(clonedDs);

        // Interface clone returns a fresh interface id; capture the request to inspect dataSourceId.
        InterfaceDto savedIface = mock(InterfaceDto.class);
        when(savedIface.getId()).thenReturn(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        when(interfaceClient.createInterface(any(InterfaceCreateRequest.class), anyString())).thenReturn(savedIface);

        when(orchestratorClient.createApplicationWorkflow(any(), anyString()))
                .thenReturn(Map.of("id", "cloned-workflow-id"));

        service.cloneFromSnapshot(plan, TENANT, PUBLICATION_ID, "My Form App", "desc", null);

        ArgumentCaptor<InterfaceCreateRequest> reqCaptor = ArgumentCaptor.forClass(InterfaceCreateRequest.class);
        verify(interfaceClient).createInterface(reqCaptor.capture(), anyString());

        // The cloned interface binds the CLONED datasource - pre-fix it kept OLD_DS (publisher's).
        assertThat(reqCaptor.getValue().getDataSourceId()).isEqualTo(NEW_DS);
        // Table name is stable across clone (no id), so it travels as-is.
        assertThat(reqCaptor.getValue().getTargetTable()).isEqualTo("Customers");
    }

    @Test
    @DisplayName("Unmapped FORM datasource (not part of the publication) keeps the old id - fail-soft, no NPE")
    @SuppressWarnings("unchecked")
    void cloneFromSnapshot_unmappedFormDataSource_keepsOldId() {
        // FORM interface bound to a datasource that has NO table node → not cloned → not in dsMapping.
        Map<String, Object> ifaceNode = new LinkedHashMap<>();
        ifaceNode.put("id", "iface-old");
        ifaceNode.put("_snapshot_htmlTemplate", "<form></form>");
        ifaceNode.put("_snapshot_name", "Orphan Form");
        ifaceNode.put("_snapshot_dataSourceId", OLD_DS);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("interfaces", new ArrayList<>(List.of(ifaceNode)));

        InterfaceDto savedIface = mock(InterfaceDto.class);
        when(savedIface.getId()).thenReturn(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"));
        when(interfaceClient.createInterface(any(InterfaceCreateRequest.class), anyString())).thenReturn(savedIface);
        when(orchestratorClient.createApplicationWorkflow(any(), anyString()))
                .thenReturn(Map.of("id", "cloned-workflow-id"));

        service.cloneFromSnapshot(plan, TENANT, PUBLICATION_ID, "My Form App", "desc", null);

        ArgumentCaptor<InterfaceCreateRequest> reqCaptor = ArgumentCaptor.forClass(InterfaceCreateRequest.class);
        verify(interfaceClient).createInterface(reqCaptor.capture(), anyString());
        assertThat(reqCaptor.getValue().getDataSourceId()).isEqualTo(OLD_DS);
    }

    @Test
    @DisplayName("Sub-workflow path: a nested FORM interface's dataSourceId is remapped to the nested cloned datasource")
    @SuppressWarnings("unchecked")
    void cloneFromSnapshot_remapsNestedFormInterfaceDataSourceId() {
        Map<String, Object> subTable = new LinkedHashMap<>();
        subTable.put("dataSourceId", String.valueOf(OLD_DS));
        subTable.put("_snapshot_ds_name", "Customers");
        subTable.put("_snapshot_ds_sourceType", "INLINE");

        Map<String, Object> subIface = new LinkedHashMap<>();
        subIface.put("id", "sub-iface-old");
        subIface.put("_snapshot_htmlTemplate", "<form></form>");
        subIface.put("_snapshot_name", "Nested Form");
        subIface.put("_snapshot_dataSourceId", OLD_DS);

        Map<String, Object> subPlan = new LinkedHashMap<>();
        subPlan.put("tables", new ArrayList<>(List.of(subTable)));
        subPlan.put("interfaces", new ArrayList<>(List.of(subIface)));

        Map<String, Object> subSnapshot = new LinkedHashMap<>();
        subSnapshot.put("name", "Sub WF");
        subSnapshot.put("plan", subPlan);
        Map<String, Object> subWorkflows = new LinkedHashMap<>();
        subWorkflows.put("old-sub-workflow-id", subSnapshot);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("_snapshot_subworkflows", subWorkflows);

        DataSourceDto clonedDs = mock(DataSourceDto.class);
        when(clonedDs.id()).thenReturn(NEW_DS);
        when(dataSourceClient.createFromSnapshot(any(), anyString())).thenReturn(clonedDs);
        InterfaceDto savedIface = mock(InterfaceDto.class);
        when(savedIface.getId()).thenReturn(UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"));
        when(interfaceClient.createInterface(any(InterfaceCreateRequest.class), anyString())).thenReturn(savedIface);
        when(orchestratorClient.createApplicationWorkflow(any(), anyString()))
                .thenReturn(Map.of("id", "any-workflow-id"));

        service.cloneFromSnapshot(plan, TENANT, PUBLICATION_ID, "My App", "desc", null);

        // The nested FORM interface (cloned inside the sub-workflow) binds the nested cloned datasource.
        ArgumentCaptor<InterfaceCreateRequest> reqCaptor = ArgumentCaptor.forClass(InterfaceCreateRequest.class);
        verify(interfaceClient).createInterface(reqCaptor.capture(), anyString());
        assertThat(reqCaptor.getValue().getDataSourceId()).isEqualTo(NEW_DS);
    }
}
