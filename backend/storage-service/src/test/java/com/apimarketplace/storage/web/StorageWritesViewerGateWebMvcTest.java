package com.apimarketplace.storage.web;

import com.apimarketplace.storage.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression (MockMvc: the role comes from the bound request header) for the sibling
 * storage writes a VIEWER could still reach: /api/storage upload, PUT and DELETE files,
 * DELETE user files, and /storage/v1 documents, rag/upsert and upload.
 */
@DisplayName("storage-service siblings bind X-Organization-Role and refuse VIEWER")
class StorageWritesViewerGateWebMvcTest {

    private StorageService storageService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        storageService = mock(StorageService.class);
        StorageController storage = new StorageController();
        ReflectionTestUtils.setField(storage, "storageService", storageService);
        StorageV1Controller v1 = new StorageV1Controller();
        ReflectionTestUtils.setField(v1, "storageService", storageService);
        mockMvc = MockMvcBuilders.standaloneSetup(storage, v1).build();
    }

    private static <B extends MockHttpServletRequestBuilder> B as(B b, String role) {
        b.header("X-User-ID", "58").header("X-Organization-ID", "org-s").header("X-Organization-Role", role);
        return b;
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "a.txt", "text/plain", "hi".getBytes());
    }

    @Test
    @DisplayName("VIEWER: every storage write is 403 and nothing reaches the storage service")
    void viewerRefused() throws Exception {
        mockMvc.perform(as(multipart("/api/storage/upload").file(file()), "VIEWER")).andExpect(status().isForbidden());
        mockMvc.perform(as(put("/api/storage/files/5").param("description", "d"), "VIEWER"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(delete("/api/storage/files/5"), "VIEWER")).andExpect(status().isForbidden());
        mockMvc.perform(as(delete("/api/storage/user/58/files"), "VIEWER")).andExpect(status().isForbidden());
        mockMvc.perform(as(post("/storage/v1/documents"), "VIEWER").contentType(MediaType.APPLICATION_JSON)
                .content("{\"collection\":\"c\"}")).andExpect(status().isForbidden());
        mockMvc.perform(as(post("/storage/v1/rag/upsert"), "VIEWER").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"x\"}")).andExpect(status().isForbidden());
        mockMvc.perform(as(multipart("/storage/v1/upload").file(file()), "VIEWER")).andExpect(status().isForbidden());
        verifyNoInteractions(storageService);
    }

    @Test
    @DisplayName("MEMBER: a file delete goes past the gate to the storage service")
    void memberProceeds() throws Exception {
        mockMvc.perform(as(delete("/api/storage/files/5"), "MEMBER"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isNotEqualTo(403));
    }
}
