package com.apimarketplace.publication.service.resource;

import com.apimarketplace.datasource.client.dto.ColumnMappingSpecDto;
import com.apimarketplace.datasource.client.dto.ColumnStructureDto;
import com.apimarketplace.datasource.client.dto.ColumnTypeDto;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DataSourceFileCloneService")
class DataSourceFileCloneServiceTest {

    private static final String TENANT = "acquirer";
    private static final String SCOPE = "publication-xyz";
    private static final String ORG = "acquirer-org-uuid";

    private OrchestratorInternalClient orchestratorClient;
    private DataSourceFileCloneService service;

    @BeforeEach
    void setUp() {
        orchestratorClient = mock(OrchestratorInternalClient.class);
        when(orchestratorClient.copyFile(any(), any())).thenAnswer(inv -> {
            Map<String, Object> req = inv.getArgument(0);
            return Map.of("newPath", "copied/" + req.get("sourcePath"));
        });
        service = new DataSourceFileCloneService(orchestratorClient);
    }

    @Test
    @DisplayName("Rewrites sourceConfig.file_path when sourceType is FILE")
    void rewritesSourceConfigFilePath() {
        Map<String, Object> sourceConfig = new LinkedHashMap<>();
        sourceConfig.put("file_path", "bucket/key.csv");
        sourceConfig.put("file_type", "csv");

        service.rewriteFilePaths("FILE", sourceConfig, List.of(), Map.of(), TENANT, SCOPE, ORG);

        assertThat(sourceConfig.get("file_path")).isEqualTo("copied/bucket/key.csv");
    }

    @Test
    @DisplayName("Skips sourceConfig rewrite when sourceType is INLINE")
    void skipsSourceConfigWhenInline() {
        Map<String, Object> sourceConfig = new LinkedHashMap<>();
        sourceConfig.put("file_path", "bucket/key.csv");

        service.rewriteFilePaths("INLINE", sourceConfig, List.of(), Map.of(), TENANT, SCOPE, ORG);

        assertThat(sourceConfig.get("file_path")).isEqualTo("bucket/key.csv");
        verify(orchestratorClient, never()).copyFile(any(), any());
    }

    @Test
    @DisplayName("Leaves external http(s) URLs untouched")
    void leavesHttpUrlsUntouched() {
        Map<String, Object> sourceConfig = new LinkedHashMap<>();
        sourceConfig.put("file_path", "https://cdn.example.com/x.csv");

        service.rewriteFilePaths("FILE", sourceConfig, List.of(), Map.of(), TENANT, SCOPE, ORG);

        assertThat(sourceConfig.get("file_path")).isEqualTo("https://cdn.example.com/x.csv");
        verify(orchestratorClient, never()).copyFile(any(), any());
    }

    @Test
    @DisplayName("Rewrites string-valued FILE column in row data")
    void rewritesStringFileColumnInRow() {
        Map<String, ColumnMappingSpecDto> spec = Map.of(
                "attachment", columnSpec(ColumnTypeDto.FILE),
                "name", columnSpec(ColumnTypeDto.TEXT));

        Map<String, Object> row = new HashMap<>();
        row.put("data", new HashMap<>(Map.of("name", "Alice", "attachment", "bucket/a.pdf")));
        List<Map<String, Object>> items = new ArrayList<>(List.of(row));

        service.rewriteFilePaths("INLINE", Map.of(), items, spec, TENANT, SCOPE, ORG);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) row.get("data");
        assertThat(data.get("attachment")).isEqualTo("copied/bucket/a.pdf");
        assertThat(data.get("name")).isEqualTo("Alice");
    }

    @Test
    @DisplayName("Rewrites map-shaped IMAGE column preserving name and mimeType")
    void rewritesMapImageColumn() {
        Map<String, ColumnMappingSpecDto> spec = Map.of("photo", columnSpec(ColumnTypeDto.IMAGE));

        Map<String, Object> photo = new LinkedHashMap<>();
        photo.put("path", "bucket/photo.png");
        photo.put("name", "photo.png");
        photo.put("mimeType", "image/png");

        Map<String, Object> row = new HashMap<>();
        row.put("data", new HashMap<>(Map.of("photo", photo)));
        List<Map<String, Object>> items = new ArrayList<>(List.of(row));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

        service.rewriteFilePaths("INLINE", Map.of(), items, spec, TENANT, SCOPE, ORG);

        verify(orchestratorClient).copyFile(captor.capture(), any());
        assertThat(captor.getValue()).containsEntry("sourceTenantId", "bucket");
        assertThat(captor.getValue()).containsEntry("fileName", "photo.png");
        assertThat(captor.getValue()).containsEntry("mimeType", "image/png");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) row.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> rewritten = (Map<String, Object>) data.get("photo");
        assertThat(rewritten.get("path")).isEqualTo("copied/bucket/photo.png");
        assertThat(rewritten.get("name")).isEqualTo("photo.png");
    }

    @Test
    @DisplayName("Map-shaped FILE column adopts the new storage-row id so the by-id URL resolves cross-tenant")
    void mapFileColumnAdoptsNewStorageId() {
        // The opaque by-id file URL is built from `id`. The clone is a NEW storage row in the
        // acquirer's tenant, so the cell MUST carry the new id, not the publisher's.
        doReturn(Map.of("newPath", "copied/bucket/photo.png", "newId", "new-id-777"))
                .when(orchestratorClient).copyFile(any(), any());

        Map<String, ColumnMappingSpecDto> spec = Map.of("photo", columnSpec(ColumnTypeDto.IMAGE));
        Map<String, Object> photo = new LinkedHashMap<>();
        photo.put("path", "bucket/photo.png");
        photo.put("name", "photo.png");
        photo.put("id", "stale-source-id-1");

        Map<String, Object> row = new HashMap<>();
        row.put("data", new HashMap<>(Map.of("photo", photo)));
        List<Map<String, Object>> items = new ArrayList<>(List.of(row));

        service.rewriteFilePaths("INLINE", Map.of(), items, spec, TENANT, SCOPE, ORG);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) row.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> rewritten = (Map<String, Object>) data.get("photo");
        assertThat(rewritten.get("path")).isEqualTo("copied/bucket/photo.png");
        assertThat(rewritten.get("id")).isEqualTo("new-id-777");
        // The acquirer's org MUST be forwarded so the storage index row is org-scoped (yields the id).
        ArgumentCaptor<String> orgCaptor = ArgumentCaptor.forClass(String.class);
        verify(orchestratorClient).copyFile(any(), orgCaptor.capture());
        assertThat(orgCaptor.getValue()).isEqualTo(ORG);
    }

    @Test
    @DisplayName("Map-shaped FILE column drops a stale source id when copyFile returns no newId")
    void mapFileColumnDropsStaleIdWhenNoNewId() {
        // @BeforeEach answerer returns newPath only (no newId) - a leftover source id would
        // 403/404 cross-tenant, so it must be removed rather than kept.
        Map<String, ColumnMappingSpecDto> spec = Map.of("photo", columnSpec(ColumnTypeDto.IMAGE));
        Map<String, Object> photo = new LinkedHashMap<>();
        photo.put("path", "bucket/photo.png");
        photo.put("name", "photo.png");
        photo.put("id", "stale-source-id-1");

        Map<String, Object> row = new HashMap<>();
        row.put("data", new HashMap<>(Map.of("photo", photo)));
        List<Map<String, Object>> items = new ArrayList<>(List.of(row));

        service.rewriteFilePaths("INLINE", Map.of(), items, spec, TENANT, SCOPE, ORG);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) row.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> rewritten = (Map<String, Object>) data.get("photo");
        assertThat(rewritten.get("path")).isEqualTo("copied/bucket/photo.png");
        assertThat(rewritten).doesNotContainKey("id");
    }

    @Test
    @DisplayName("Ignores TEXT/NUMBER columns even if they contain path-like strings")
    void ignoresNonFileColumns() {
        Map<String, ColumnMappingSpecDto> spec = Map.of(
                "label", columnSpec(ColumnTypeDto.TEXT),
                "score", columnSpec(ColumnTypeDto.NUMBER));

        Map<String, Object> row = new HashMap<>();
        row.put("data", new HashMap<>(Map.of("label", "bucket/looks-like-path", "score", 42)));
        List<Map<String, Object>> items = List.of(row);

        service.rewriteFilePaths("INLINE", Map.of(), items, spec, TENANT, SCOPE, ORG);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) row.get("data");
        assertThat(data.get("label")).isEqualTo("bucket/looks-like-path");
        verify(orchestratorClient, never()).copyFile(any(), any());
    }

    @Test
    @DisplayName("Keeps original path when copyFile returns null (best-effort)")
    void keepsOriginalPathOnCopyFailure() {
        // doReturn bypasses the @BeforeEach thenAnswer stub cleanly (re-stubbing with
        // when().thenReturn() against the same matcher still routes through the answerer).
        doReturn(null).when(orchestratorClient).copyFile(any(), any());

        Map<String, Object> sourceConfig = new LinkedHashMap<>();
        sourceConfig.put("file_path", "bucket/key.csv");

        service.rewriteFilePaths("FILE", sourceConfig, List.of(), Map.of(), TENANT, SCOPE, ORG);

        assertThat(sourceConfig.get("file_path")).isEqualTo("bucket/key.csv");
    }

    @Test
    @DisplayName("Rewrites every element when value is a list of file paths")
    void rewritesListOfFilePaths() {
        Map<String, ColumnMappingSpecDto> spec = Map.of("attachments", columnSpec(ColumnTypeDto.FILE));

        Map<String, Object> row = new HashMap<>();
        row.put("data", new HashMap<>(Map.of("attachments",
                new ArrayList<>(List.of("bucket/a.pdf", "bucket/b.pdf")))));
        List<Map<String, Object>> items = List.of(row);

        service.rewriteFilePaths("INLINE", Map.of(), items, spec, TENANT, SCOPE, ORG);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) row.get("data");
        @SuppressWarnings("unchecked")
        List<String> rewritten = (List<String>) data.get("attachments");
        assertThat(rewritten).containsExactly("copied/bucket/a.pdf", "copied/bucket/b.pdf");
    }

    @Test
    @DisplayName("Noop when tenantId or scopeId is null")
    void noopWhenScopeMissing() {
        Map<String, Object> sourceConfig = new LinkedHashMap<>();
        sourceConfig.put("file_path", "bucket/key.csv");

        service.rewriteFilePaths("FILE", sourceConfig, List.of(), Map.of(), null, SCOPE, ORG);
        service.rewriteFilePaths("FILE", sourceConfig, List.of(), Map.of(), TENANT, null, ORG);

        assertThat(sourceConfig.get("file_path")).isEqualTo("bucket/key.csv");
        verify(orchestratorClient, never()).copyFile(any(), any());
    }

    private ColumnMappingSpecDto columnSpec(ColumnTypeDto type) {
        return new ColumnMappingSpecDto("", type, ColumnStructureDto.SCALAR, Map.of(), Map.of());
    }
}
