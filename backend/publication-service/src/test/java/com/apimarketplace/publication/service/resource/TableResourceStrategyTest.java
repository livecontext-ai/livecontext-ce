package com.apimarketplace.publication.service.resource;

import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.ColumnMappingSpecDto;
import com.apimarketplace.datasource.client.dto.ColumnStructureDto;
import com.apimarketplace.datasource.client.dto.ColumnTypeDto;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.datasource.client.dto.DataSourceItemDto;
import com.apimarketplace.datasource.client.dto.DataSourceTypeDto;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType;
import com.apimarketplace.publication.service.resource.ResourcePublicationStrategy.ResourceMetadata;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("TableResourceStrategy")
class TableResourceStrategyTest {

    private static final String OWNER = "publisher-1";
    private static final String OWNER_ORG = "org-publisher-1";
    private static final String ACQUIRER = "acquirer-1";
    private static final String ACQUIRER_ORG = "org-acquirer-1";

    private DataSourceClient dataSourceClient;
    private DataSourceFileCloneService fileCloneService;
    private ObjectMapper objectMapper;
    private TableResourceStrategy strategy;

    @BeforeEach
    void setUp() {
        dataSourceClient = mock(DataSourceClient.class);
        fileCloneService = mock(DataSourceFileCloneService.class);
        objectMapper = new ObjectMapper();
        strategy = new TableResourceStrategy(dataSourceClient, fileCloneService, objectMapper);
    }

    @Test
    @DisplayName("Reports PublicationType.TABLE and DisplayMode.TABLE")
    void reportsTypeAndDisplayMode() {
        assertThat(strategy.getPublicationType()).isEqualTo(PublicationType.TABLE);
        assertThat(strategy.getDisplayMode()).isEqualTo(DisplayMode.TABLE);
    }

    @Test
    @DisplayName("fetchOwnedResource returns name and description")
    void fetchOwnedResourceReturnsMetadata() {
        DataSourceDto ds = tableDto(42L, OWNER, "Contacts", "CRM list");
        when(dataSourceClient.findByIdAndTenantId(42L, OWNER)).thenReturn(ds);

        ResourceMetadata meta = strategy.fetchOwnedResource("42", OWNER);

        assertThat(meta.name()).isEqualTo("Contacts");
        assertThat(meta.description()).isEqualTo("CRM list");
    }

    @Test
    @DisplayName("fetchOwnedResource rejects tables owned by another tenant")
    void fetchOwnedResourceRejectsWrongTenant() {
        DataSourceDto foreign = tableDto(42L, "someone-else", "X", null);
        when(dataSourceClient.findByIdAndTenantId(42L, OWNER)).thenReturn(foreign);

        assertThatThrownBy(() -> strategy.fetchOwnedResource("42", OWNER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    @DisplayName("fetchOwnedResource throws when id is not numeric")
    void fetchOwnedResourceRejectsInvalidId() {
        assertThatThrownBy(() -> strategy.fetchOwnedResource("not-a-number", OWNER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid table id");
    }

    @Test
    @DisplayName("buildSnapshot captures metadata, schema and every row")
    void buildSnapshotCapturesFieldsAndRows() {
        DataSourceDto ds = tableDto(42L, OWNER, "Contacts", "CRM");
        when(dataSourceClient.findByIdAndTenantId(42L, OWNER)).thenReturn(ds);

        DataSourceItemDto row1 = new DataSourceItemDto(1L, 42L, OWNER,
                Map.of("name", "Alice"), 0, null);
        DataSourceItemDto row2 = new DataSourceItemDto(2L, 42L, OWNER,
                Map.of("name", "Bob"), 1, null);
        when(dataSourceClient.getAllItems(42L, OWNER)).thenReturn(List.of(row1, row2));

        Map<String, Object> snapshot = strategy.buildSnapshot("42", OWNER);

        assertThat(snapshot)
                .containsEntry("name", "Contacts")
                .containsEntry("description", "CRM")
                .containsEntry("sourceType", DataSourceTypeDto.INLINE.name());
        assertThat(snapshot.get("columnOrder")).isEqualTo(List.of());
        assertThat(snapshot.get("mappingSpec")).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) snapshot.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("priority", 0);
        assertThat(items.get(1)).containsEntry("priority", 1);
    }

    @Test
    @DisplayName("buildSnapshot defaults sourceType to INLINE when null")
    void buildSnapshotDefaultsSourceType() {
        DataSourceDto ds = new DataSourceDto(42L, OWNER, "N", "D",
                null, Map.of(), null, null, null, null,
                List.of(), Map.of(), null, null, null, null);
        when(dataSourceClient.findByIdAndTenantId(42L, OWNER)).thenReturn(ds);
        when(dataSourceClient.getAllItems(42L, OWNER)).thenReturn(List.of());

        Map<String, Object> snapshot = strategy.buildSnapshot("42", OWNER);

        assertThat(snapshot).containsEntry("sourceType", DataSourceTypeDto.INLINE.name());
    }

    @Test
    @DisplayName("cloneFromSnapshot calls file clone, creates datasource, bulk-inserts rows")
    void cloneOrchestratesEverything() {
        UUID publicationId = UUID.randomUUID();

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", "Contacts");
        snapshot.put("description", "CRM");
        snapshot.put("sourceType", "FILE");
        Map<String, Object> sourceConfig = new LinkedHashMap<>();
        sourceConfig.put("file_path", "bucket/key.csv");
        snapshot.put("sourceConfig", sourceConfig);
        snapshot.put("columnOrder", List.of(Map.of("key", "name")));
        snapshot.put("mappingSpec", Map.of("name",
                Map.of("path", "name", "type", "TEXT", "structure", "SCALAR",
                        "children", Map.of(), "display", Map.of())));
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(new LinkedHashMap<>(Map.of("data", Map.of("name", "Alice"), "priority", 0)));
        snapshot.put("items", items);

        DataSourceDto created = tableDto(999L, ACQUIRER, "Contacts", "CRM");
        when(dataSourceClient.createFromSnapshot(any(), eq(ACQUIRER))).thenReturn(created);
        when(dataSourceClient.bulkInsertItems(eq(999L), anyList(), eq(ACQUIRER))).thenReturn(1);

        String result = strategy.cloneFromSnapshot(snapshot, ACQUIRER, publicationId, ACQUIRER_ORG);

        // The acquirer's org MUST be forwarded so the re-uploaded file's storage row is
        // org-scoped (and thus gets an id the acquirer can resolve by-id).
        verify(fileCloneService).rewriteFilePaths(
                eq("FILE"), any(), any(), any(), eq(ACQUIRER), eq(publicationId.toString()), eq(ACQUIRER_ORG));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> createCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dataSourceClient).createFromSnapshot(createCaptor.capture(), eq(ACQUIRER));
        Map<String, Object> createRequest = createCaptor.getValue();
        assertThat(createRequest)
                .containsEntry("name", "Contacts")
                .containsEntry("description", "CRM")
                .containsEntry("sourceType", "FILE")
                .containsEntry("sourcePublicationId", publicationId.toString());

        verify(dataSourceClient).bulkInsertItems(eq(999L), anyList(), eq(ACQUIRER));
        assertThat(result).isEqualTo("999");
    }

    @Test
    @DisplayName("cloneFromSnapshot skips bulkInsertItems when there are no rows")
    void cloneSkipsBulkInsertWhenEmpty() {
        UUID publicationId = UUID.randomUUID();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", "Empty");
        snapshot.put("sourceType", "INLINE");
        snapshot.put("items", List.of());

        DataSourceDto created = tableDto(77L, ACQUIRER, "Empty", null);
        when(dataSourceClient.createFromSnapshot(any(), eq(ACQUIRER))).thenReturn(created);

        strategy.cloneFromSnapshot(snapshot, ACQUIRER, publicationId);

        verify(dataSourceClient, never()).bulkInsertItems(any(), anyList(), anyString());
    }

    @Test
    @DisplayName("cloneFromSnapshot forwards organization scope to DataSourceClient")
    void cloneForwardsOrganizationScope() {
        UUID publicationId = UUID.randomUUID();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", "Org Table");
        snapshot.put("sourceType", "INLINE");
        snapshot.put("items", List.of());

        DataSourceDto created = tableDto(88L, ACQUIRER, "Org Table", null);
        when(dataSourceClient.createFromSnapshot(any(), eq(ACQUIRER))).thenReturn(created);

        strategy.cloneFromSnapshot(snapshot, ACQUIRER, publicationId, ACQUIRER_ORG);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(dataSourceClient).createFromSnapshot(captor.capture(), eq(ACQUIRER));
        assertThat(captor.getValue())
                .containsEntry("sourcePublicationId", publicationId.toString())
                .containsEntry("organizationId", ACQUIRER_ORG);
    }

    @Test
    @DisplayName("cloneFromSnapshot throws when createFromSnapshot returns null or a no-id DTO")
    void cloneThrowsWhenCreateFails() {
        when(dataSourceClient.createFromSnapshot(any(), anyString())).thenReturn(null);

        assertThatThrownBy(() -> strategy.cloneFromSnapshot(
                Map.of("name", "X", "sourceType", "INLINE", "items", List.of()),
                ACQUIRER, UUID.randomUUID()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to clone datasource");
    }

    @Test
    @DisplayName("buildSnapshot(org) resolves the table through org-scoped lookups (review path)")
    void buildSnapshotWithOrgUsesOrgScopedLookup() {
        DataSourceDto ds = tableDto(42L, OWNER, "Org table", "d");
        when(dataSourceClient.findByIdAndTenantId(42L, OWNER, OWNER_ORG)).thenReturn(ds);
        when(dataSourceClient.getAllItems(42L, OWNER, OWNER_ORG)).thenReturn(List.of());

        Map<String, Object> snapshot = strategy.buildSnapshot("42", OWNER, OWNER_ORG);

        assertThat(snapshot).containsEntry("name", "Org table");
        verify(dataSourceClient).findByIdAndTenantId(42L, OWNER, OWNER_ORG);
        verify(dataSourceClient).getAllItems(42L, OWNER, OWNER_ORG);
        // must NOT fall back to the personal (2-arg) lookup for an org-owned table
        verify(dataSourceClient, never()).findByIdAndTenantId(42L, OWNER);
        verify(dataSourceClient, never()).getAllItems(42L, OWNER);
    }

    @Test
    @DisplayName("buildSnapshot(blank org) keeps the personal 2-arg lookups (publish path unchanged)")
    void buildSnapshotWithBlankOrgUsesPersonalLookup() {
        DataSourceDto ds = tableDto(42L, OWNER, "Personal table", "d");
        when(dataSourceClient.findByIdAndTenantId(42L, OWNER)).thenReturn(ds);
        when(dataSourceClient.getAllItems(42L, OWNER)).thenReturn(List.of());

        Map<String, Object> snapshot = strategy.buildSnapshot("42", OWNER, "  ");

        assertThat(snapshot).containsEntry("name", "Personal table");
        verify(dataSourceClient).findByIdAndTenantId(42L, OWNER);
        verify(dataSourceClient, never()).findByIdAndTenantId(eq(42L), eq(OWNER), any());
    }

    private DataSourceDto tableDto(Long id, String tenant, String name, String description) {
        ColumnMappingSpecDto spec = new ColumnMappingSpecDto(
                "", ColumnTypeDto.TEXT, ColumnStructureDto.SCALAR, Map.of(), Map.of());
        return new DataSourceDto(
                id, tenant, name, description,
                DataSourceTypeDto.INLINE, Map.of(),
                null, null, null, null,
                List.of(),
                Map.of("col", spec),
                null, null, null, null);
    }
}
