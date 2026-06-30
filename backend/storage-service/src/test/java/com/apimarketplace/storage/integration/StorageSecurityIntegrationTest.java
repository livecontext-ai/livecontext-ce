package com.apimarketplace.storage.integration;

import com.apimarketplace.storage.domain.StoredFile;
import com.apimarketplace.storage.repository.StoredFileRepository;
import com.apimarketplace.storage.web.dto.DocumentRequest;
import com.apimarketplace.storage.web.dto.RagUpsertRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Security integration tests for the Storage Service.
 *
 * <p>Authentication is enforced by the GatewayAuthenticationFilter (HMAC-based),
 * not Spring Security. Tests verify that authenticated requests reach endpoints correctly.</p>
 */
@IntegrationTest
@AutoConfigureMockMvc
@DisplayName("Storage Security Integration Tests")
class StorageSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StoredFileRepository storedFileRepository;

    @Autowired
    private com.apimarketplace.storage.service.StorageService storageService;

    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    Path tempDir;

    private static final Long USER_ID = 42L;

    @BeforeEach
    void setUp() {
        storedFileRepository.deleteAll();
        ReflectionTestUtils.setField(storageService, "localStoragePath", tempDir.toString());
    }

    // ========== Authenticated Access Tests ==========

    @Nested
    @DisplayName("Authenticated requests should be allowed")
    class AuthenticatedAccessTests {

        @Test
        @WithMockUser
        @DisplayName("should allow authenticated file upload")
        void shouldAllowAuthenticatedUpload() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "auth-test.txt", "text/plain", "Authenticated content".getBytes()
            );

            mockMvc.perform(multipart("/api/storage/upload")
                            .file(file)
                            .with(csrf())
                            .header("X-User-ID", String.valueOf(USER_ID))
                            .header("X-Organization-ID", "org-" + USER_ID))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser
        @DisplayName("should allow authenticated get user files")
        void shouldAllowAuthenticatedGetUserFiles() throws Exception {
            mockMvc.perform(get("/api/storage/user/{userId}/files", USER_ID)
                            .header("X-User-ID", String.valueOf(USER_ID)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser
        @DisplayName("should allow authenticated get public files")
        void shouldAllowAuthenticatedGetPublicFiles() throws Exception {
            mockMvc.perform(get("/api/storage/public/files"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser
        @DisplayName("should allow authenticated health check")
        void shouldAllowAuthenticatedHealthCheck() throws Exception {
            mockMvc.perform(get("/api/storage/health"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser
        @DisplayName("should allow authenticated storage usage request")
        void shouldAllowAuthenticatedGetStorageUsage() throws Exception {
            mockMvc.perform(get("/api/storage/user/{userId}/usage", USER_ID)
                            .header("X-User-ID", String.valueOf(USER_ID)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser
        @DisplayName("should allow authenticated V1 document creation")
        void shouldAllowAuthenticatedV1DocumentCreation() throws Exception {
            DocumentRequest request = DocumentRequest.builder()
                    .collection("test")
                    .document(Map.of("key", "value"))
                    .build();

            mockMvc.perform(post("/storage/v1/documents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(csrf()))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser
        @DisplayName("should allow authenticated V1 document retrieval")
        void shouldAllowAuthenticatedV1DocumentRetrieval() throws Exception {
            mockMvc.perform(get("/storage/v1/documents/doc-123"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser
        @DisplayName("should allow authenticated V1 RAG upsert")
        void shouldAllowAuthenticatedV1RagUpsert() throws Exception {
            RagUpsertRequest request = RagUpsertRequest.builder()
                    .id("rag-1")
                    .text("Text")
                    .build();

            mockMvc.perform(post("/storage/v1/rag/upsert")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(csrf()))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser
        @DisplayName("should allow authenticated V1 health check")
        void shouldAllowAuthenticatedV1HealthCheck() throws Exception {
            mockMvc.perform(get("/storage/v1/health"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("should allow access with ADMIN role")
        void shouldAllowAccessWithAdminRole() throws Exception {
            mockMvc.perform(get("/api/storage/health"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(username = "testuser", roles = {"USER"})
        @DisplayName("should allow access with USER role")
        void shouldAllowAccessWithUserRole() throws Exception {
            mockMvc.perform(get("/api/storage/public/files"))
                    .andExpect(status().isOk());
        }
    }
}
