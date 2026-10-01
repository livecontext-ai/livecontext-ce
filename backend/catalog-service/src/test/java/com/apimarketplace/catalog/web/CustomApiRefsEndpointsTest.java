package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.config.GlobalExceptionHandler;
import com.apimarketplace.catalog.dto.CustomApiRefDTO;
import com.apimarketplace.catalog.service.WorkflowInspectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two faces of the custom-API lookup: the public one the publish surfaces call to
 * warn before submitting, and the internal one publication-service uses to actually gate
 * the publish. They must answer the same shape, or the warning and the refusal disagree.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Custom-API reference lookup endpoints")
class CustomApiRefsEndpointsTest {

    @Mock private WorkflowInspectorService workflowInspectorService;

    private MockMvc publicMvc;
    private MockMvc internalMvc;

    private static final String BODY = "{\"toolSlugs\":[\"my-api/do-thing\"]}";
    private static final String CALLER = "tenant-1";

    @BeforeEach
    void setUp() {
        publicMvc = MockMvcBuilders
                .standaloneSetup(new WorkflowInspectorController(workflowInspectorService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        internalMvc = MockMvcBuilders
                .standaloneSetup(new InternalWorkflowInspectorController(workflowInspectorService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private void stubOneCustomApi() {
        when(workflowInspectorService.findCustomApiRefs(
                List.of("my-api/do-thing"), CALLER, null))
                .thenReturn(List.of(new CustomApiRefDTO("my-api", "My API", List.of("my-api/do-thing"))));
    }

    private void stubOneCustomApiInScope() {
        when(workflowInspectorService.findCustomApiRefsInScope(
                List.of("my-api/do-thing"), CALLER, null))
                .thenReturn(List.of(new CustomApiRefDTO("my-api", "My API", List.of("my-api/do-thing"))));
    }

    @Nested
    @DisplayName("POST /api/workflow-inspector/custom-apis (public - used by the publish modals)")
    class PublicEndpoint {

        @Test
        @DisplayName("returns the caller's own custom APIs the identifiers resolve to")
        void returnsCustomApis() throws Exception {
            stubOneCustomApiInScope();

            publicMvc.perform(post("/api/workflow-inspector/custom-apis")
                            .header("X-User-ID", CALLER)
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customApis[0].apiSlug").value("my-api"))
                    .andExpect(jsonPath("$.customApis[0].apiName").value("My API"))
                    .andExpect(jsonPath("$.customApis[0].toolIdentifiers[0]").value("my-api/do-thing"));
        }

        @Test
        @DisplayName("the lookup is SCOPED to the caller - the unscoped gate variant is never used here")
        void neverUsesTheUnscopedLookup() throws Exception {
            stubOneCustomApiInScope();

            publicMvc.perform(post("/api/workflow-inspector/custom-apis")
                            .header("X-User-ID", CALLER)
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isOk());

            verify(workflowInspectorService, never()).findCustomApiRefs(any(), any(), any());
        }

        @Test
        @DisplayName("without a caller identity it answers an empty list instead of an unscoped one")
        void missingCallerShortCircuits() throws Exception {
            publicMvc.perform(post("/api/workflow-inspector/custom-apis")
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customApis").isEmpty());

            verify(workflowInspectorService, never()).findCustomApiRefsInScope(any(), any(), any());
            verify(workflowInspectorService, never()).findCustomApiRefs(any(), any(), any());
        }

        @Test
        @DisplayName("an empty identifier list answers an empty list without touching the service")
        void emptyRequestShortCircuits() throws Exception {
            publicMvc.perform(post("/api/workflow-inspector/custom-apis")
                            .header("X-User-ID", CALLER)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"toolSlugs\":[]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customApis").isEmpty());

            verify(workflowInspectorService, never()).findCustomApiRefsInScope(any(), any(), any());
        }

        @Test
        @DisplayName("a null identifier list answers an empty list (never a 500)")
        void nullRequestShortCircuits() throws Exception {
            publicMvc.perform(post("/api/workflow-inspector/custom-apis")
                            .header("X-User-ID", CALLER)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"toolSlugs\":null}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customApis").isEmpty());
        }

        @Test
        @DisplayName("a lookup failure is a 500 - the publish surface then falls back to the backend gate")
        void serviceFailureIs500() throws Exception {
            when(workflowInspectorService.findCustomApiRefsInScope(any(), any(), any()))
                    .thenThrow(new RuntimeException("DB error"));

            publicMvc.perform(post("/api/workflow-inspector/custom-apis")
                            .header("X-User-ID", CALLER)
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("POST /api/internal/catalog/workflow-inspector/custom-apis (internal - the publish gate)")
    class InternalEndpoint {

        @Test
        @DisplayName("answers the same shape as the public endpoint, using the GATE lookup")
        void returnsCustomApis() throws Exception {
            stubOneCustomApi();

            internalMvc.perform(post("/api/internal/catalog/workflow-inspector/custom-apis")
                            .header("X-User-ID", CALLER)
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customApis[0].apiSlug").value("my-api"))
                    .andExpect(jsonPath("$.customApis[0].apiName").value("My API"))
                    .andExpect(jsonPath("$.customApis[0].toolIdentifiers[0]").value("my-api/do-thing"));

            // The gate is NOT owner-restricted: an acquired plan can use its publisher's API.
            verify(workflowInspectorService, never()).findCustomApiRefsInScope(any(), any(), any());
        }

        @Test
        @DisplayName("forwards the publishing workspace so the caller's own APIs match on prefix")
        void forwardsTheOrganizationScope() throws Exception {
            when(workflowInspectorService.findCustomApiRefs(
                    List.of("my-api/do-thing"), CALLER, "org-7")).thenReturn(List.of());

            internalMvc.perform(post("/api/internal/catalog/workflow-inspector/custom-apis")
                            .header("X-User-ID", CALLER)
                            .header("X-Organization-ID", "org-7")
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isOk());

            verify(workflowInspectorService).findCustomApiRefs(List.of("my-api/do-thing"), CALLER, "org-7");
        }

        @Test
        @DisplayName("still answers without a publisher header (the exact-row matches remain)")
        void worksWithoutPublisherHeader() throws Exception {
            when(workflowInspectorService.findCustomApiRefs(List.of("my-api/do-thing"), null, null))
                    .thenReturn(List.of(new CustomApiRefDTO("my-api", "My API", List.of("my-api/do-thing"))));

            internalMvc.perform(post("/api/internal/catalog/workflow-inspector/custom-apis")
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customApis[0].apiSlug").value("my-api"));
        }

        @Test
        @DisplayName("an empty identifier list answers an empty list without touching the service")
        void emptyRequestShortCircuits() throws Exception {
            internalMvc.perform(post("/api/internal/catalog/workflow-inspector/custom-apis")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"toolSlugs\":[]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customApis").isEmpty());

            verify(workflowInspectorService, never()).findCustomApiRefs(any(), any(), any());
        }
    }
}
