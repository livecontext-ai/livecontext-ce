package com.apimarketplace.storage.web;

import com.apimarketplace.storage.domain.StoredFile;
import com.apimarketplace.storage.service.StorageService;
import com.apimarketplace.storage.web.dto.DocumentRequest;
import com.apimarketplace.storage.web.dto.DocumentResponse;
import com.apimarketplace.storage.web.dto.RagUpsertRequest;
import com.apimarketplace.storage.web.dto.RagUpsertResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("StorageV1Controller Tests")
@ExtendWith(MockitoExtension.class)
class StorageV1ControllerTest {

    @Mock
    private StorageService storageService;

    @InjectMocks
    private StorageV1Controller storageV1Controller;

    private static final String USER_ID_HEADER = "user-123";
    private static final String ORG_ID_HEADER = "org-456";

    @Nested
    @DisplayName("POST /storage/v1/documents")
    class CreateDocumentTests {

        @Test
        @DisplayName("should return 200 OK with document response on success")
        void shouldReturn200OnSuccess() {
            DocumentRequest request = DocumentRequest.builder()
                .collection("test-collection")
                .document(Map.of("title", "Test Doc"))
                .build();

            ResponseEntity<DocumentResponse> response =
                storageV1Controller.createDocument(request, USER_ID_HEADER, ORG_ID_HEADER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            DocumentResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getId()).startsWith("doc-");
            assertThat(body.getCollection()).isEqualTo("test-collection");
            assertThat(body.getDocument()).containsEntry("title", "Test Doc");
            assertThat(body.getCreatedAt()).isGreaterThan(0);
            assertThat(body.getUpdatedAt()).isGreaterThan(0);
            assertThat(body.getError()).isNull();
        }

        @Test
        @DisplayName("should handle request with null userId and orgId headers")
        void shouldHandleNullHeaders() {
            DocumentRequest request = DocumentRequest.builder()
                .collection("default")
                .document(Map.of("content", "data"))
                .build();

            ResponseEntity<DocumentResponse> response =
                storageV1Controller.createDocument(request, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError()).isNull();
        }
    }

    @Nested
    @DisplayName("GET /storage/v1/documents/{id}")
    class GetDocumentTests {

        @Test
        @DisplayName("should return 200 OK with simulated document")
        void shouldReturn200WithDocument() {
            String documentId = "doc-12345";

            ResponseEntity<DocumentResponse> response =
                storageV1Controller.getDocument(documentId, USER_ID_HEADER, ORG_ID_HEADER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            DocumentResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getId()).isEqualTo(documentId);
            assertThat(body.getCollection()).isEqualTo("default");
            assertThat(body.getDocument()).isNotNull();
            assertThat(body.getDocument()).containsKey("content");
        }

        @Test
        @DisplayName("should return document with correct timestamps")
        void shouldReturnDocumentWithTimestamps() {
            long beforeCall = System.currentTimeMillis();

            ResponseEntity<DocumentResponse> response =
                storageV1Controller.getDocument("doc-1", USER_ID_HEADER, ORG_ID_HEADER);

            long afterCall = System.currentTimeMillis();

            DocumentResponse body = response.getBody();
            assertThat(body).isNotNull();
            // createdAt is 1 day before, updatedAt is 1 hour before
            assertThat(body.getCreatedAt()).isLessThan(afterCall);
            assertThat(body.getUpdatedAt()).isLessThan(afterCall);
        }
    }

    @Nested
    @DisplayName("POST /storage/v1/rag/upsert")
    class UpsertRagDocumentTests {

        @Test
        @DisplayName("should return 200 OK with indexed status")
        void shouldReturn200WithIndexedStatus() {
            RagUpsertRequest request = RagUpsertRequest.builder()
                .id("rag-doc-1")
                .text("Sample text for RAG indexing")
                .collection("rag-collection")
                .meta(Map.of("source", "test"))
                .build();

            ResponseEntity<RagUpsertResponse> response =
                storageV1Controller.upsertRagDocument(request, USER_ID_HEADER, ORG_ID_HEADER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            RagUpsertResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getId()).isEqualTo("rag-doc-1");
            assertThat(body.getStatus()).isEqualTo("indexed");
            assertThat(body.getVectorCount()).isEqualTo(1);
            assertThat(body.getIndexedAt()).isGreaterThan(0);
            assertThat(body.getError()).isNull();
        }

        @Test
        @DisplayName("should handle request with null headers")
        void shouldHandleNullHeaders() {
            RagUpsertRequest request = RagUpsertRequest.builder()
                .id("rag-doc-2")
                .text("Text")
                .build();

            ResponseEntity<RagUpsertResponse> response =
                storageV1Controller.upsertRagDocument(request, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getStatus()).isEqualTo("indexed");
        }
    }

    @Nested
    @DisplayName("GET /storage/v1/health")
    class HealthTests {

        @Test
        @DisplayName("should return 200 OK with UP status")
        void shouldReturn200WithUpStatus() {
            ResponseEntity<Map<String, Object>> response = storageV1Controller.health();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("status")).isEqualTo("UP");
            assertThat(body.get("service")).isEqualTo("storage-v1");
            assertThat(body.get("timestamp")).isNotNull();
        }
    }

    @Nested
    @DisplayName("POST /storage/v1/upload")
    class UploadFileTests {

        @Test
        @DisplayName("should return 400 when X-User-Id header is null")
        void shouldReturn400WhenUserIdNull() {
            MultipartFile file = mock(MultipartFile.class);

            ResponseEntity<Map<String, Object>> response =
                storageV1Controller.uploadFile(file, null, "00000000-0000-0000-0000-000000000001", "description");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsKey("error");
            assertThat(response.getBody().get("error")).isEqualTo("X-User-Id header is required");
        }

        @Test
        @DisplayName("should return 200 OK with file info on successful upload")
        void shouldReturn200OnSuccessfulUpload() throws IOException {
            MultipartFile file = mock(MultipartFile.class);
            StoredFile storedFile = new StoredFile(123L, "unique.txt", "test.txt", "text/plain", 512L, "/uploads/123/unique.txt");
            storedFile.setId(1L);
            when(storageService.storeFile(eq(file), eq(123L), anyString(), eq("desc"))).thenReturn(storedFile);

            ResponseEntity<Map<String, Object>> response =
                storageV1Controller.uploadFile(file, "123", "00000000-0000-0000-0000-000000000001", "desc");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("id")).isEqualTo(1L);
            assertThat(body.get("filename")).isEqualTo("test.txt");
            assertThat(body.get("size")).isEqualTo(512L);
            assertThat(body.get("contentType")).isEqualTo("text/plain");
        }

        @Test
        @DisplayName("should return 400 when IOException occurs during upload")
        void shouldReturn400WhenIOException() throws IOException {
            MultipartFile file = mock(MultipartFile.class);
            when(storageService.storeFile(eq(file), eq(123L), anyString(), isNull()))
                .thenThrow(new IOException("Disk full"));

            ResponseEntity<Map<String, Object>> response =
                storageV1Controller.uploadFile(file, "123", "00000000-0000-0000-0000-000000000001", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsKey("error");
        }

        @Test
        @DisplayName("should return 400 when X-User-Id is not a valid number")
        void shouldReturn400WhenUserIdNotNumber() {
            MultipartFile file = mock(MultipartFile.class);

            ResponseEntity<Map<String, Object>> response =
                storageV1Controller.uploadFile(file, "not-a-number", "00000000-0000-0000-0000-000000000001", "desc");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsKey("error");
            assertThat((String) response.getBody().get("error")).contains("nombre valide");
        }
    }
}
