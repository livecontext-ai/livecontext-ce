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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@link com.apimarketplace.storage.web.StorageV1Controller}.
 *
 * <p>Tests the V1 API endpoints exposed via Gateway with JWT authentication.
 * Uses a full Spring application context with H2 in-memory database.</p>
 */
@IntegrationTest
@AutoConfigureMockMvc
@DisplayName("StorageV1Controller Integration Tests")
class StorageV1ControllerIntegrationTest {

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

    private static final String USER_ID_HEADER = "123";
    private static final String ORG_ID_HEADER = "org-456";

    @BeforeEach
    void setUp() {
        storedFileRepository.deleteAll();
        ReflectionTestUtils.setField(storageService, "localStoragePath", tempDir.toString());
    }

    // ========== Document Creation Tests ==========

    @Nested
    @DisplayName("POST /storage/v1/documents")
    class CreateDocumentTests {

        @Test
        @WithMockUser
        @DisplayName("should create a document and return response with generated id")
        void shouldCreateDocumentAndReturnResponse() throws Exception {
            DocumentRequest request = DocumentRequest.builder()
                    .collection("test-collection")
                    .document(Map.of("title", "Test Document", "body", "Document content"))
                    .metadata(Map.of("source", "integration-test"))
                    .build();

            mockMvc.perform(post("/storage/v1/documents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .header("X-User-ID", USER_ID_HEADER)
                            .header("X-Organization-ID", ORG_ID_HEADER)
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(startsWith("doc-")))
                    .andExpect(jsonPath("$.collection").value("test-collection"))
                    .andExpect(jsonPath("$.document.title").value("Test Document"))
                    .andExpect(jsonPath("$.document.body").value("Document content"))
                    .andExpect(jsonPath("$.createdAt").isNumber())
                    .andExpect(jsonPath("$.updatedAt").isNumber())
                    .andExpect(jsonPath("$.error").doesNotExist());
        }

        @Test
        @WithMockUser
        @DisplayName("should create document without optional headers")
        void shouldCreateDocumentWithoutOptionalHeaders() throws Exception {
            DocumentRequest request = DocumentRequest.builder()
                    .collection("minimal-collection")
                    .document(Map.of("data", "value"))
                    .build();

            mockMvc.perform(post("/storage/v1/documents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(startsWith("doc-")))
                    .andExpect(jsonPath("$.collection").value("minimal-collection"));
        }

        @Test
        @WithMockUser
        @DisplayName("should create document with empty document map")
        void shouldCreateDocumentWithEmptyDocumentMap() throws Exception {
            DocumentRequest request = DocumentRequest.builder()
                    .collection("empty-docs")
                    .document(Map.of())
                    .build();

            mockMvc.perform(post("/storage/v1/documents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").isNotEmpty());
        }
    }

    // ========== Document Retrieval Tests ==========

    @Nested
    @DisplayName("GET /storage/v1/documents/{id}")
    class GetDocumentTests {

        @Test
        @WithMockUser
        @DisplayName("should return simulated document by id")
        void shouldReturnSimulatedDocumentById() throws Exception {
            String documentId = "doc-12345";

            mockMvc.perform(get("/storage/v1/documents/{id}", documentId)
                            .header("X-User-ID", USER_ID_HEADER)
                            .header("X-Organization-ID", ORG_ID_HEADER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(documentId))
                    .andExpect(jsonPath("$.collection").value("default"))
                    .andExpect(jsonPath("$.document").isNotEmpty())
                    .andExpect(jsonPath("$.document.content").isNotEmpty())
                    .andExpect(jsonPath("$.createdAt").isNumber())
                    .andExpect(jsonPath("$.updatedAt").isNumber());
        }

        @Test
        @WithMockUser
        @DisplayName("should return document without optional headers")
        void shouldReturnDocumentWithoutHeaders() throws Exception {
            mockMvc.perform(get("/storage/v1/documents/{id}", "doc-abc"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("doc-abc"));
        }

        @Test
        @WithMockUser
        @DisplayName("should return document with content containing document id reference")
        void shouldReturnDocumentWithIdReference() throws Exception {
            String documentId = "doc-unique-test";

            mockMvc.perform(get("/storage/v1/documents/{id}", documentId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.document.content", containsString(documentId)));
        }
    }

    // ========== RAG Upsert Tests ==========

    @Nested
    @DisplayName("POST /storage/v1/rag/upsert")
    class RagUpsertTests {

        @Test
        @WithMockUser
        @DisplayName("should index document for RAG and return indexed status")
        void shouldIndexDocumentAndReturnIndexedStatus() throws Exception {
            RagUpsertRequest request = RagUpsertRequest.builder()
                    .id("rag-integration-1")
                    .text("This is sample text for vector embedding in RAG system.")
                    .collection("rag-test-collection")
                    .meta(Map.of("source", "integration-test", "language", "en"))
                    .build();

            mockMvc.perform(post("/storage/v1/rag/upsert")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .header("X-User-ID", USER_ID_HEADER)
                            .header("X-Organization-ID", ORG_ID_HEADER)
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("rag-integration-1"))
                    .andExpect(jsonPath("$.status").value("indexed"))
                    .andExpect(jsonPath("$.vectorCount").value(1))
                    .andExpect(jsonPath("$.indexedAt").isNumber())
                    .andExpect(jsonPath("$.error").doesNotExist());
        }

        @Test
        @WithMockUser
        @DisplayName("should index document with minimal fields")
        void shouldIndexDocumentWithMinimalFields() throws Exception {
            RagUpsertRequest request = RagUpsertRequest.builder()
                    .id("rag-minimal")
                    .text("Minimal text")
                    .build();

            mockMvc.perform(post("/storage/v1/rag/upsert")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("rag-minimal"))
                    .andExpect(jsonPath("$.status").value("indexed"));
        }
    }

    // ========== V1 Upload Tests ==========

    @Nested
    @DisplayName("POST /storage/v1/upload")
    class V1UploadTests {

        @Test
        @WithMockUser
        @DisplayName("should upload file via V1 endpoint with X-User-Id header")
        void shouldUploadFileViaV1Endpoint() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "v1-upload.txt", "text/plain",
                    "V1 upload content".getBytes()
            );

            mockMvc.perform(multipart("/storage/v1/upload")
                            .file(file)
                            .header("X-User-ID", USER_ID_HEADER)
                            .header("X-Organization-ID", USER_ID_HEADER)
                            .param("description", "V1 upload test")
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").isNumber())
                    .andExpect(jsonPath("$.filename").value("v1-upload.txt"))
                    .andExpect(jsonPath("$.size").value(17))
                    .andExpect(jsonPath("$.contentType").value("text/plain"))
                    .andExpect(jsonPath("$.uploadedAt").isNotEmpty());

            // Verify database persistence
            Long userId = Long.parseLong(USER_ID_HEADER);
            assertThat(storedFileRepository.findByUserId(userId)).hasSize(1);
        }

        @Test
        @WithMockUser
        @DisplayName("should return 400 when X-User-Id header is missing")
        void shouldReturn400WhenUserIdHeaderMissing() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "no-user.txt", "text/plain",
                    "Content".getBytes()
            );

            mockMvc.perform(multipart("/storage/v1/upload")
                            .file(file)
                            .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("X-User-Id header is required"));
        }

        @Test
        @WithMockUser
        @DisplayName("should return 400 when X-User-Id is not a valid number")
        void shouldReturn400WhenUserIdIsNotNumeric() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "bad-user.txt", "text/plain",
                    "Content".getBytes()
            );

            mockMvc.perform(multipart("/storage/v1/upload")
                            .file(file)
                            .header("X-User-ID", "not-a-number")
                            .header("X-Organization-ID", USER_ID_HEADER)
                            .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", containsString("nombre valide")));
        }
    }

    // ========== V1 Health Tests ==========

    @Nested
    @DisplayName("GET /storage/v1/health")
    class V1HealthTests {

        @Test
        @WithMockUser
        @DisplayName("should return UP status with timestamp")
        void shouldReturnUpStatusWithTimestamp() throws Exception {
            mockMvc.perform(get("/storage/v1/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.service").value("storage-v1"))
                    .andExpect(jsonPath("$.timestamp").isNumber());
        }
    }
}
