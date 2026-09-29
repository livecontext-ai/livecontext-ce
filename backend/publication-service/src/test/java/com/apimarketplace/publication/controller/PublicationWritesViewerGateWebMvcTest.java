package com.apimarketplace.publication.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc proof that publication-service writes bind {@code X-Organization-Role} and refuse
 * a VIEWER: share-link create / delete / regenerate, remote-marketplace acquire (clones into
 * the workspace), and the three screening steps (pre-publish scan, decisions, AI replacement
 * image).
 */
@DisplayName("publication-service writes bind X-Organization-Role and refuse VIEWER")
class PublicationWritesViewerGateWebMvcTest {

    private final List<Object> deps = new ArrayList<>();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.standaloneSetup(
                build(SharedLinkController.class),
                build(RemoteMarketplaceController.class),
                build(PublicationScreeningController.class)).build();
    }

    private <T> T build(Class<T> type) throws Exception {
        Constructor<?> ctor = Arrays.stream(type.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == String.class) {
                args[i] = "";
            } else if (types[i].isPrimitive()) {
                args[i] = types[i] == boolean.class ? (Object) false : (Object) 0;
            } else {
                args[i] = mock(types[i]);
                deps.add(args[i]);
            }
        }
        return type.cast(ctor.newInstance(args));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String role) {
        return b.header("X-User-ID", "u-1").header("X-Organization-ID", "org-p")
                .header("X-Organization-Role", role).contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    @DisplayName("VIEWER: every publication-side write is 403 and reaches no service")
    void viewerRefused() throws Exception {
        String link = "7d3c0f4e-2b1a-4e6f-9c8d-5a4b3c2d1e0f";
        mockMvc.perform(as(post("/api/publications/shared-links"), "VIEWER")
                .content("{\"resourceType\":\"APPLICATION\",\"resourceToken\":\"t\"}")).andExpect(status().isForbidden());
        mockMvc.perform(as(delete("/api/publications/shared-links/" + link), "VIEWER")).andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/publications/shared-links/" + link + "/regenerate-token"), "VIEWER"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/publications/remote/" + link + "/acquire"), "VIEWER"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/publications/pre-publish-scan"), "VIEWER")
                .content("{\"interfaceId\":\"" + link + "\"}")).andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/publications/screening-decisions"), "VIEWER")
                .content("{\"publicationId\":\"p\"}")).andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/publications/screening/generate-replacement"), "VIEWER")
                .content("{\"prompt\":\"x\",\"interfaceId\":\"i\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(deps.toArray());
    }

    @Test
    @DisplayName("MEMBER: the pre-publish scan is not refused by the role gate (the interface lookup runs)")
    void memberScanProceeds() throws Exception {
        mockMvc.perform(as(post("/api/publications/pre-publish-scan"), "MEMBER")
                        .content("{\"interfaceId\":\"7d3c0f4e-2b1a-4e6f-9c8d-5a4b3c2d1e0f\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("MEMBER: a share-link delete is not refused by the role gate")
    void memberProceeds() throws Exception {
        mockMvc.perform(as(delete("/api/publications/shared-links/7d3c0f4e-2b1a-4e6f-9c8d-5a4b3c2d1e0f"), "MEMBER"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isNotEqualTo(403));
    }
}
