package com.apimarketplace.publication.service.resource;

import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.datasource.client.dto.DataSourceTypeDto;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType;
import com.apimarketplace.publication.service.resource.ResourcePublicationStrategy.ResourceMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("InterfaceResourceStrategy")
class InterfaceResourceStrategyTest {

    private static final String OWNER = "publisher-1";
    private static final String OWNER_ORG = "org-publisher-1";
    private static final String ACQUIRER = "acquirer-1";
    private static final String ACQUIRER_ORG = "org-acquirer-1";

    private InterfaceClient interfaceClient;
    private DataSourceClient dataSourceClient;
    private DataSourceFileCloneService fileCloneService;
    private InterfaceResourceStrategy strategy;

    @BeforeEach
    void setUp() {
        interfaceClient = mock(InterfaceClient.class);
        dataSourceClient = mock(DataSourceClient.class);
        fileCloneService = mock(DataSourceFileCloneService.class);
        strategy = new InterfaceResourceStrategy(
                interfaceClient, dataSourceClient, fileCloneService, new ObjectMapper());
    }

    @Test
    @DisplayName("Reports PublicationType.INTERFACE and DisplayMode.INTERFACE")
    void reportsTypeAndDisplayMode() {
        assertThat(strategy.getPublicationType()).isEqualTo(PublicationType.INTERFACE);
        assertThat(strategy.getDisplayMode()).isEqualTo(DisplayMode.INTERFACE);
    }

    @Test
    @DisplayName("fetchOwnedResource returns name and description")
    void fetchOwnedResourceReturnsMetadata() {
        UUID id = UUID.randomUUID();
        InterfaceDto iface = iface(id, OWNER, "Login Page", "Landing");
        when(interfaceClient.getInterface(id, OWNER)).thenReturn(iface);

        ResourceMetadata meta = strategy.fetchOwnedResource(id.toString(), OWNER);

        assertThat(meta.name()).isEqualTo("Login Page");
        assertThat(meta.description()).isEqualTo("Landing");
    }

    @Test
    @DisplayName("fetchOwnedResource rejects interfaces owned by another tenant")
    void fetchOwnedResourceRejectsWrongTenant() {
        UUID id = UUID.randomUUID();
        InterfaceDto foreign = iface(id, "someone-else", "X", null);
        when(interfaceClient.getInterface(id, OWNER)).thenReturn(foreign);

        assertThatThrownBy(() -> strategy.fetchOwnedResource(id.toString(), OWNER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    @DisplayName("fetchOwnedResource throws when id is not a valid UUID")
    void fetchOwnedResourceRejectsInvalidId() {
        assertThatThrownBy(() -> strategy.fetchOwnedResource("not-a-uuid", OWNER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid interface id");
    }

    @Test
    @DisplayName("buildSnapshot captures html/css/js/format/interfaceType and sanitized data")
    void buildSnapshotCapturesFields() {
        UUID id = UUID.randomUUID();
        InterfaceDto iface = iface(id, OWNER, "Page", "Desc");
        iface.setHtmlTemplate("<div/>");
        iface.setCssTemplate(".a{}");
        iface.setJsTemplate("console.log(1)");
        iface.setInterfaceType("CUSTOM");
        iface.setFormat("vertical");
        iface.setData(new LinkedHashMap<>(Map.of("title", "Hello")));
        when(interfaceClient.getInterface(id, OWNER)).thenReturn(iface);

        Map<String, Object> snapshot = strategy.buildSnapshot(id.toString(), OWNER);

        assertThat(snapshot)
                .containsEntry("name", "Page")
                .containsEntry("description", "Desc")
                .containsEntry("htmlTemplate", "<div/>")
                .containsEntry("cssTemplate", ".a{}")
                .containsEntry("jsTemplate", "console.log(1)")
                // The shape travels with the templates: the clone must keep the published frame.
                .containsEntry("format", "vertical")
                .containsEntry("interfaceType", "CUSTOM");
        assertThat(snapshot.get("data")).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("buildSnapshot strips workflow-bound entries from action_mapping, keeps __continue and navigate:*")
    void buildSnapshotSanitizesActionMapping() {
        UUID id = UUID.randomUUID();
        InterfaceDto iface = iface(id, OWNER, "Page", "Desc");

        Map<String, Object> actionMapping = new LinkedHashMap<>();
        actionMapping.put("submit", UUID.randomUUID().toString());   // external workflow ref → strip
        actionMapping.put("next", "__continue");                     // keep
        actionMapping.put("go-home", "navigate:home");               // keep
        actionMapping.put("legacy", "some-other-value");             // strip
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("action_mapping", actionMapping);
        data.put("title", "Hello");
        iface.setData(data);
        when(interfaceClient.getInterface(id, OWNER)).thenReturn(iface);

        Map<String, Object> snapshot = strategy.buildSnapshot(id.toString(), OWNER);

        @SuppressWarnings("unchecked")
        Map<String, Object> snapshotData = (Map<String, Object>) snapshot.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> sanitized = (Map<String, Object>) snapshotData.get("action_mapping");
        assertThat(sanitized)
                .containsEntry("next", "__continue")
                .containsEntry("go-home", "navigate:home")
                .doesNotContainKey("submit")
                .doesNotContainKey("legacy");
        assertThat(snapshotData).containsEntry("title", "Hello");
    }

    @Test
    @DisplayName("buildSnapshot omits data entry when interface data is null")
    void buildSnapshotOmitsNullData() {
        UUID id = UUID.randomUUID();
        InterfaceDto iface = iface(id, OWNER, "Page", "Desc");
        iface.setData(null);
        when(interfaceClient.getInterface(id, OWNER)).thenReturn(iface);

        Map<String, Object> snapshot = strategy.buildSnapshot(id.toString(), OWNER);

        assertThat(snapshot).doesNotContainKey("data");
    }

    @Test
    @DisplayName("cloneFromSnapshot forwards snapshot fields + sourcePublicationId to InterfaceClient")
    void cloneForwardsFieldsAndSourcePublicationId() {
        UUID newId = UUID.randomUUID();
        UUID publicationId = UUID.randomUUID();
        when(interfaceClient.createFromSnapshot(any(), eq(ACQUIRER))).thenAnswer(inv -> {
            InterfaceDto created = new InterfaceDto();
            created.setId(newId);
            return created;
        });

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", "Page");
        snapshot.put("htmlTemplate", "<div/>");
        snapshot.put("interfaceType", "CUSTOM");

        String result = strategy.cloneFromSnapshot(snapshot, ACQUIRER, publicationId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(interfaceClient).createFromSnapshot(captor.capture(), eq(ACQUIRER));
        Map<String, Object> req = captor.getValue();

        assertThat(req)
                .containsEntry("name", "Page")
                .containsEntry("htmlTemplate", "<div/>")
                .containsEntry("interfaceType", "CUSTOM")
                .containsEntry("sourcePublicationId", publicationId.toString());
        assertThat(result).isEqualTo(newId.toString());
    }

    @Test
    @DisplayName("cloneFromSnapshot forwards organization scope to InterfaceClient")
    void cloneForwardsOrganizationScope() {
        UUID newId = UUID.randomUUID();
        UUID publicationId = UUID.randomUUID();
        when(interfaceClient.createFromSnapshot(any(), eq(ACQUIRER))).thenAnswer(inv -> {
            InterfaceDto created = new InterfaceDto();
            created.setId(newId);
            return created;
        });

        String result = strategy.cloneFromSnapshot(
                Map.of("name", "Org Page"),
                ACQUIRER,
                publicationId,
                ACQUIRER_ORG);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(interfaceClient).createFromSnapshot(captor.capture(), eq(ACQUIRER));
        assertThat(captor.getValue())
                .containsEntry("sourcePublicationId", publicationId.toString())
                .containsEntry("organizationId", ACQUIRER_ORG);
        assertThat(result).isEqualTo(newId.toString());
    }

    @Test
    @DisplayName("cloneFromSnapshot throws when InterfaceClient returns null")
    void cloneThrowsWhenCreateFails() {
        when(interfaceClient.createFromSnapshot(any(), anyString())).thenReturn(null);

        assertThatThrownBy(() -> strategy.cloneFromSnapshot(
                Map.of("name", "X"), ACQUIRER, UUID.randomUUID()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to clone interface");
    }

    @Test
    @DisplayName("buildSnapshot(org) resolves the interface through the org-scoped client overload (review path)")
    void buildSnapshotWithOrgUsesOrgScopedLookup() {
        UUID id = UUID.randomUUID();
        InterfaceDto iface = iface(id, OWNER, "Org page", "d");
        iface.setHtmlTemplate("<div/>");
        when(interfaceClient.getInterface(id, OWNER, OWNER_ORG)).thenReturn(iface);

        Map<String, Object> snapshot = strategy.buildSnapshot(id.toString(), OWNER, OWNER_ORG);

        assertThat(snapshot).containsEntry("name", "Org page");
        verify(interfaceClient).getInterface(id, OWNER, OWNER_ORG);
        // must NOT fall back to the personal (2-arg) overload for an org-owned interface
        verify(interfaceClient, never()).getInterface(id, OWNER);
    }

    @Test
    @DisplayName("buildSnapshot(blank org) keeps the personal 2-arg overload (publish path unchanged)")
    void buildSnapshotWithBlankOrgUsesPersonalLookup() {
        UUID id = UUID.randomUUID();
        InterfaceDto iface = iface(id, OWNER, "Personal page", "d");
        when(interfaceClient.getInterface(id, OWNER)).thenReturn(iface);

        Map<String, Object> snapshot = strategy.buildSnapshot(id.toString(), OWNER, "  ");

        assertThat(snapshot).containsEntry("name", "Personal page");
        verify(interfaceClient).getInterface(id, OWNER);
        verify(interfaceClient, never()).getInterface(eq(id), eq(OWNER), any());
    }

    @Test
    @DisplayName("buildSnapshot(org) snapshots the embedded table through org-scoped datasource lookups")
    void buildSnapshotWithOrgScopesEmbeddedTableLookup() {
        UUID id = UUID.randomUUID();
        long dsId = 99L;
        InterfaceDto iface = iface(id, OWNER, "Org page", "d");
        iface.setDataSourceId(dsId);
        when(interfaceClient.getInterface(id, OWNER, OWNER_ORG)).thenReturn(iface);

        DataSourceDto embedded = new DataSourceDto(
                dsId, OWNER, "Embedded", "d",
                DataSourceTypeDto.INLINE, Map.of(),
                null, null, null, null,
                List.of(), null, null, null, null, null);
        when(dataSourceClient.findByIdAndTenantId(dsId, OWNER, OWNER_ORG)).thenReturn(embedded);
        when(dataSourceClient.getAllItems(dsId, OWNER, OWNER_ORG)).thenReturn(List.of());

        Map<String, Object> snapshot = strategy.buildSnapshot(id.toString(), OWNER, OWNER_ORG);

        assertThat(snapshot).containsKey("embeddedTable");
        verify(dataSourceClient).findByIdAndTenantId(dsId, OWNER, OWNER_ORG);
        verify(dataSourceClient).getAllItems(dsId, OWNER, OWNER_ORG);
        verify(dataSourceClient, never()).findByIdAndTenantId(dsId, OWNER);
    }

    private InterfaceDto iface(UUID id, String tenant, String name, String desc) {
        InterfaceDto dto = new InterfaceDto();
        dto.setId(id);
        dto.setTenantId(tenant);
        dto.setName(name);
        dto.setDescription(desc);
        return dto;
    }
}
