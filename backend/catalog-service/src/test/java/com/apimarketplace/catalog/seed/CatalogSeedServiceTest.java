package com.apimarketplace.catalog.seed;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.dto.ApiResponse;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.service.ApiService;
import com.apimarketplace.catalog.service.LexicalIndexSyncService;
import com.apimarketplace.catalog.service.StructureSkeletonService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogSeedService")
class CatalogSeedServiceTest {

    @Mock private CatalogSeedStateRepository seedStateRepository;
    @Mock private ApiService apiService;
    @Mock private ApiRepository apiRepository;
    @Mock private ApiToolRepository apiToolRepository;
    @Mock private OpenApiTransformer transformer;
    @Mock private CatalogSeedCredentialService credentialService;
    @Mock private LexicalIndexSyncService lexicalIndexSyncService;
    @Mock private CatalogDataBootstrapService dataBootstrapService;
    @Mock private StructureSkeletonService structureSkeletonService;

    private CatalogSeedConfig config;
    private ObjectMapper objectMapper;
    private CatalogSeedService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        config = new CatalogSeedConfig();
        config.setPath(tempDir.toString());
        config.setOwnerId("SYSTEM");
        objectMapper = new ObjectMapper();
        service = new CatalogSeedService(
                config, seedStateRepository, apiService, apiRepository,
                apiToolRepository, transformer, credentialService,
                lexicalIndexSyncService, objectMapper, dataBootstrapService,
                structureSkeletonService
        );
    }

    private void writeManifest(String content) throws IOException {
        Files.writeString(tempDir.resolve("manifest.json"), content);
    }

    private void writeSpecFile(String filename, String content) throws IOException {
        Files.writeString(tempDir.resolve(filename), content);
    }

    private ApiResponse mockApiResponse(UUID id, String name) {
        return new ApiResponse(id, name, "slug", "desc", "https://api.test.com",
                null, null, null, null, true, false, System.currentTimeMillis(),
                System.currentTimeMillis(), "SYSTEM", List.of(),
                null, "public", true, null, null, null, "FREE", "REVIEWING", null);
    }

    @Nested
    @DisplayName("importAll")
    class ImportAll {

        @Test
        @DisplayName("should return empty result when no manifest exists")
        void shouldReturnEmptyWhenNoManifest() {
            SeedImportResult result = service.importAll();

            assertEquals(0, result.imported());
            assertEquals(0, result.skipped());
            assertEquals(0, result.failed());
            verifyNoInteractions(apiService);
        }

        @Test
        @DisplayName("should return empty result when manifest has no specs")
        void shouldReturnEmptyWhenNoSpecs() throws IOException {
            writeManifest("{\"version\": \"1.0\", \"specs\": []}");

            SeedImportResult result = service.importAll();

            assertEquals(0, result.imported());
            verifyNoInteractions(apiService);
        }

        @Test
        @DisplayName("should import a new spec successfully")
        void shouldImportNewSpec() throws IOException {
            writeManifest("""
                    {"version": "1.0", "specs": [
                        {"id": "weather", "file": "weather.yaml", "category": "Weather", "subcategory": "Forecast"}
                    ]}""");
            writeSpecFile("weather.yaml", MINIMAL_OPENAPI_SPEC);

            when(seedStateRepository.findBySeedId("weather")).thenReturn(Optional.empty());
            UUID apiId = UUID.randomUUID();
            when(transformer.transform(any(), any())).thenReturn(objectMapper.createObjectNode());
            when(apiService.processApiSubmission(any(JsonNode.class), eq("SYSTEM")))
                    .thenReturn(mockApiResponse(apiId, "Weather API"));
            when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of());

            SeedImportResult result = service.importAll();

            assertEquals(1, result.imported());
            assertEquals(0, result.failed());
            verify(apiService).processApiSubmission(any(), eq("SYSTEM"));
            verify(credentialService).linkCredentials(eq(apiId), any());
            verify(seedStateRepository).save(any(CatalogSeedStateEntity.class));
        }

        @Test
        @DisplayName("should skip unchanged spec")
        void shouldSkipUnchangedSpec() throws Exception {
            writeManifest("""
                    {"version": "1.0", "specs": [
                        {"id": "weather", "file": "weather.yaml", "category": "Weather"}
                    ]}""");
            String specContent = MINIMAL_OPENAPI_SPEC;
            writeSpecFile("weather.yaml", specContent);

            // Compute checksum of spec content
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(specContent.getBytes());
            String checksum = java.util.HexFormat.of().formatHex(hash);

            CatalogSeedStateEntity existing = new CatalogSeedStateEntity();
            existing.setSeedId("weather");
            existing.setApiId(UUID.randomUUID());
            existing.setFileChecksum(checksum);
            existing.setUserModified(false);
            existing.setLastImportedAt(System.currentTimeMillis());
            when(seedStateRepository.findBySeedId("weather")).thenReturn(Optional.of(existing));

            SeedImportResult result = service.importAll();

            assertEquals(0, result.imported());
            assertEquals(1, result.skipped());
            verifyNoInteractions(apiService);
        }

        @Test
        @DisplayName("should skip user-modified spec")
        void shouldSkipUserModifiedSpec() throws IOException {
            writeManifest("""
                    {"version": "1.0", "specs": [
                        {"id": "weather", "file": "weather.yaml", "category": "Weather"}
                    ]}""");
            writeSpecFile("weather.yaml", MINIMAL_OPENAPI_SPEC);

            CatalogSeedStateEntity existing = new CatalogSeedStateEntity();
            existing.setSeedId("weather");
            existing.setApiId(UUID.randomUUID());
            existing.setFileChecksum("different-checksum");
            existing.setUserModified(true);
            existing.setLastImportedAt(System.currentTimeMillis());
            when(seedStateRepository.findBySeedId("weather")).thenReturn(Optional.of(existing));

            SeedImportResult result = service.importAll();

            assertEquals(0, result.imported());
            assertEquals(0, result.skipped());
            assertEquals(1, result.userModified());
            verifyNoInteractions(apiService);
        }

        @Test
        @DisplayName("should re-import changed spec after deleting old API")
        void shouldReimportChangedSpec() throws IOException {
            writeManifest("""
                    {"version": "1.0", "specs": [
                        {"id": "weather", "file": "weather.yaml", "category": "Weather"}
                    ]}""");
            writeSpecFile("weather.yaml", MINIMAL_OPENAPI_SPEC);

            UUID oldApiId = UUID.randomUUID();
            CatalogSeedStateEntity existing = new CatalogSeedStateEntity();
            existing.setSeedId("weather");
            existing.setApiId(oldApiId);
            existing.setFileChecksum("old-checksum");
            existing.setUserModified(false);
            existing.setLastImportedAt(System.currentTimeMillis());
            when(seedStateRepository.findBySeedId("weather")).thenReturn(Optional.of(existing));

            UUID newApiId = UUID.randomUUID();
            when(transformer.transform(any(), any())).thenReturn(objectMapper.createObjectNode());
            when(apiService.processApiSubmission(any(JsonNode.class), eq("SYSTEM")))
                    .thenReturn(mockApiResponse(newApiId, "Weather API"));
            when(apiToolRepository.findByApiId(newApiId)).thenReturn(List.of());

            SeedImportResult result = service.importAll();

            assertEquals(1, result.imported());
            verify(apiService).deleteApi(oldApiId);
            verify(seedStateRepository).delete(existing);
            verify(apiService).processApiSubmission(any(), eq("SYSTEM"));
        }

        @Test
        @DisplayName("should detect user modification via updatedAt")
        void shouldDetectUserModification() throws IOException {
            writeManifest("""
                    {"version": "1.0", "specs": [
                        {"id": "weather", "file": "weather.yaml", "category": "Weather"}
                    ]}""");
            writeSpecFile("weather.yaml", MINIMAL_OPENAPI_SPEC);

            UUID apiId = UUID.randomUUID();
            long importTime = System.currentTimeMillis() - 60000;
            CatalogSeedStateEntity existing = new CatalogSeedStateEntity();
            existing.setSeedId("weather");
            existing.setApiId(apiId);
            existing.setFileChecksum("different-checksum");
            existing.setUserModified(false);
            existing.setLastImportedAt(importTime);
            when(seedStateRepository.findBySeedId("weather")).thenReturn(Optional.of(existing));

            // API was updated after import → user modified
            ApiEntity apiEntity = new ApiEntity();
            apiEntity.setUpdatedAt(importTime + 30000);
            when(apiRepository.findById(apiId)).thenReturn(Optional.of(apiEntity));

            SeedImportResult result = service.importAll();

            assertEquals(1, result.userModified());
            verify(seedStateRepository).save(argThat(entity ->
                    entity.isUserModified()
            ));
            verifyNoInteractions(apiService);
        }

        @Test
        @DisplayName("should handle import failure without aborting others")
        void shouldHandleFailureGracefully() throws IOException {
            writeManifest("""
                    {"version": "1.0", "specs": [
                        {"id": "bad", "file": "missing.yaml", "category": "Cat"},
                        {"id": "good", "file": "good.yaml", "category": "Cat"}
                    ]}""");
            writeSpecFile("good.yaml", MINIMAL_OPENAPI_SPEC);

            // "bad" will fail because missing.yaml doesn't exist - no stubbing needed for it
            when(seedStateRepository.findBySeedId("good")).thenReturn(Optional.empty());
            UUID apiId = UUID.randomUUID();
            lenient().when(transformer.transform(any(), any())).thenReturn(objectMapper.createObjectNode());
            when(apiService.processApiSubmission(any(JsonNode.class), eq("SYSTEM")))
                    .thenReturn(mockApiResponse(apiId, "Good API"));
            when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of());

            SeedImportResult result = service.importAll();

            assertEquals(1, result.imported());
            assertEquals(1, result.failed());
            assertEquals(1, result.errors().size());
        }

        @Test
        @DisplayName("should handle malformed manifest")
        void shouldHandleMalformedManifest() throws IOException {
            writeManifest("not valid json {{{");

            SeedImportResult result = service.importAll();

            assertEquals(0, result.imported());
            assertEquals(1, result.errors().size());
        }
    }

    private static final String MINIMAL_OPENAPI_SPEC = """
            openapi: "3.0.3"
            info:
              title: Test API
              description: A test API
              version: "1.0"
            servers:
              - url: https://api.test.com
            paths:
              /test:
                get:
                  operationId: getTest
                  summary: Test endpoint
                  responses:
                    "200":
                      description: OK
            """;
}
