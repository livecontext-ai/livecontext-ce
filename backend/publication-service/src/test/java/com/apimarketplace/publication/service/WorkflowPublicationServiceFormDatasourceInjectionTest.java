package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.PublicationReviewRepository;
import com.apimarketplace.publication.repository.PublicationSnapshotVersionRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Publish-time injection of FORM-interface datasources.
 *
 * <p>A FORM interface binds to a backing datasource by numeric id. If that
 * datasource has no table node in the workflow plan (common - a form app may
 * not have a CRUD node), it would never be snapshotted and therefore never
 * cloned for the acquirer, so the acquired form has no table at all. The fix
 * makes {@code enrichPlanWithInterfaceData} inject a placeholder
 * {@code tables[]} node for such datasources (mirroring
 * {@code injectAgentReferencedResources}) so the subsequent
 * {@code enrichPlanWithDatasourceData} snapshots them and the clone copies them.
 */
@DisplayName("publish enrichment: FORM-referenced datasources are injected for cloning")
class WorkflowPublicationServiceFormDatasourceInjectionTest {

    private static final String TENANT = "1";

    private InterfaceClient interfaceClient;
    private WorkflowPublicationService service;

    @BeforeEach
    void setUp() {
        interfaceClient = mock(InterfaceClient.class);
        service = new WorkflowPublicationService(
                mock(WorkflowPublicationRepository.class),
                mock(PublicationSnapshotVersionRepository.class),
                mock(PublicationReceiptRepository.class),
                mock(PublicationReviewRepository.class),
                mock(OrchestratorInternalClient.class),
                mock(AgentClient.class),
                interfaceClient,
                mock(DataSourceClient.class),
                mock(StorageBreakdownService.class),
                new ObjectMapper(),
                mock(SnapshotCloneService.class),
                mock(EntitlementGuard.class),
                mock(AuthClient.class));
    }

    private InterfaceDto formInterface(UUID id, Long dataSourceId) {
        InterfaceDto dto = mock(InterfaceDto.class);
        when(dto.getId()).thenReturn(id);
        when(dto.getTenantId()).thenReturn(TENANT);
        when(dto.getDataSourceId()).thenReturn(dataSourceId);
        return dto;
    }

    @Test
    @DisplayName("FORM datasource with no table node is injected as a placeholder table (so it gets cloned)")
    @SuppressWarnings("unchecked")
    void injectsFormDatasourceMissingFromTables() {
        UUID ifaceId = UUID.randomUUID();
        Map<String, Object> ifaceNode = new HashMap<>();
        ifaceNode.put("id", ifaceId.toString());

        Map<String, Object> plan = new HashMap<>();
        plan.put("interfaces", new ArrayList<>(List.of(ifaceNode)));
        // no tables[]

        InterfaceDto dto = formInterface(ifaceId, 42L);
        when(interfaceClient.getInterfacesByIds(any(), eq(TENANT), isNull()))
                .thenReturn(List.of(dto));

        service.enrichPlanWithInterfaceData(plan, TENANT, null, null);

        List<Map<String, Object>> tables = (List<Map<String, Object>>) plan.get("tables");
        assertThat(tables).isNotNull();
        assertThat(tables).anySatisfy(t -> {
            assertThat(t.get("dataSourceId")).isEqualTo("42");
            assertThat(t.get("_injected_by_interface")).isEqualTo(true);
        });
        // The interface node still carries the FORM binding snapshot.
        assertThat(ifaceNode.get("_snapshot_dataSourceId")).isEqualTo(42L);
    }

    @Test
    @DisplayName("FORM datasource already present in tables[] is NOT duplicated")
    @SuppressWarnings("unchecked")
    void doesNotDuplicateExistingDatasourceTable() {
        UUID ifaceId = UUID.randomUUID();
        Map<String, Object> ifaceNode = new HashMap<>();
        ifaceNode.put("id", ifaceId.toString());
        Map<String, Object> existingTable = new HashMap<>();
        existingTable.put("dataSourceId", "42");

        Map<String, Object> plan = new HashMap<>();
        plan.put("interfaces", new ArrayList<>(List.of(ifaceNode)));
        plan.put("tables", new ArrayList<>(List.of(existingTable)));

        InterfaceDto dto = formInterface(ifaceId, 42L);
        when(interfaceClient.getInterfacesByIds(any(), eq(TENANT), isNull()))
                .thenReturn(List.of(dto));

        service.enrichPlanWithInterfaceData(plan, TENANT, null, null);

        List<Map<String, Object>> tables = (List<Map<String, Object>>) plan.get("tables");
        assertThat(tables).hasSize(1);
        assertThat(tables.get(0)).doesNotContainKey("_injected_by_interface");
    }

    @Test
    @DisplayName("Non-FORM interface (null dataSourceId) injects no table node")
    @SuppressWarnings("unchecked")
    void noInjectionWhenInterfaceHasNoDatasource() {
        UUID ifaceId = UUID.randomUUID();
        Map<String, Object> ifaceNode = new HashMap<>();
        ifaceNode.put("id", ifaceId.toString());

        Map<String, Object> plan = new HashMap<>();
        plan.put("interfaces", new ArrayList<>(List.of(ifaceNode)));

        InterfaceDto dto = formInterface(ifaceId, null);
        when(interfaceClient.getInterfacesByIds(any(), eq(TENANT), isNull()))
                .thenReturn(List.of(dto));

        service.enrichPlanWithInterfaceData(plan, TENANT, null, null);

        Object tables = plan.get("tables");
        assertThat(tables == null || ((List<?>) tables).isEmpty()).isTrue();
    }
}
