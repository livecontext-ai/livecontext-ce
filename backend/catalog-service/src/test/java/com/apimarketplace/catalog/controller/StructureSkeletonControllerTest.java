package com.apimarketplace.catalog.controller;

import com.apimarketplace.catalog.repository.ToolResponseRepository;
import com.apimarketplace.catalog.service.StructureSkeletonService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StructureSkeletonController")
class StructureSkeletonControllerTest {

    @Mock
    private StructureSkeletonService service;

    private StructureSkeletonController controller;

    @BeforeEach
    void setUp() {
        controller = new StructureSkeletonController(service);
    }

    @Nested
    @DisplayName("GET /{responseId}/root")
    class GetRootStructureTests {

        @Test
        @DisplayName("should return root structure")
        void returnsRootStructure() {
            UUID responseId = UUID.randomUUID();
            ToolResponseRepository.StructureNode node = mock(ToolResponseRepository.StructureNode.class);
            when(node.getKey()).thenReturn("data");

            when(service.getRootStructure(responseId)).thenReturn(List.of(node));

            ResponseEntity<List<ToolResponseRepository.StructureNode>> response =
                    controller.getRootStructure(responseId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(1);
            assertThat(response.getBody().get(0).getKey()).isEqualTo("data");

            verify(service).getRootStructure(responseId);
        }

        @Test
        @DisplayName("should return empty list when no structure")
        void returnsEmptyListWhenNoStructure() {
            UUID responseId = UUID.randomUUID();

            when(service.getRootStructure(responseId)).thenReturn(Collections.emptyList());

            ResponseEntity<List<ToolResponseRepository.StructureNode>> response =
                    controller.getRootStructure(responseId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEmpty();
        }
    }

    @Nested
    @DisplayName("GET /{responseId}/path")
    class GetPathStructureTests {

        @Test
        @DisplayName("should return path structure with path parameter")
        void returnsPathStructureWithPath() {
            UUID responseId = UUID.randomUUID();
            List<String> path = List.of("props", "users", "items");
            ToolResponseRepository.StructureNode node = mock(ToolResponseRepository.StructureNode.class);

            when(service.getPathStructure(eq(responseId), any(String[].class)))
                    .thenReturn(List.of(node));

            ResponseEntity<List<ToolResponseRepository.StructureNode>> response =
                    controller.getPathStructure(responseId, path);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(1);

            verify(service).getPathStructure(eq(responseId), any(String[].class));
        }

        @Test
        @DisplayName("should fall back to root structure when path is null")
        void fallsBackToRootWhenPathNull() {
            UUID responseId = UUID.randomUUID();

            when(service.getRootStructure(responseId)).thenReturn(Collections.emptyList());

            ResponseEntity<List<ToolResponseRepository.StructureNode>> response =
                    controller.getPathStructure(responseId, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(service).getRootStructure(responseId);
            verify(service, never()).getPathStructure(any(), any());
        }

        @Test
        @DisplayName("should fall back to root structure when path is empty")
        void fallsBackToRootWhenPathEmpty() {
            UUID responseId = UUID.randomUUID();

            when(service.getRootStructure(responseId)).thenReturn(Collections.emptyList());

            ResponseEntity<List<ToolResponseRepository.StructureNode>> response =
                    controller.getPathStructure(responseId, Collections.emptyList());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(service).getRootStructure(responseId);
        }
    }

    @Nested
    @DisplayName("GET /tool/{toolId}/skeleton")
    class GetSkeletonByToolIdTests {

        @Test
        @DisplayName("should return skeleton when found")
        void returnsSkeletonWhenFound() {
            UUID toolId = UUID.randomUUID();
            Map<String, Object> result = new HashMap<>();
            result.put("skeleton", Map.of("type", "object"));
            result.put("paths", List.of("$.data", "$.data.items"));

            when(service.getSkeletonByToolId(toolId)).thenReturn(result);

            ResponseEntity<Map<String, Object>> response = controller.getSkeletonByToolId(toolId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsKey("skeleton");
        }

        @Test
        @DisplayName("returns 200 with skeleton:null + hint when no ToolResponse exists yet (cold-start)")
        void returnsGraceful200OnColdStart() {
            // Regression: previously this returned 404, which the agent's
            // "Get response schema" action surfaced as TOOL_050 EXECUTION_FAILED.
            // Cold-start must hand back a typed "no schema yet" so the agent
            // can still fetch inputSchema and execute the tool to seed it.
            UUID toolId = UUID.randomUUID();
            Map<String, Object> result = new HashMap<>();
            result.put("error", "No response found for this tool");

            when(service.getSkeletonByToolId(toolId)).thenReturn(result);

            ResponseEntity<Map<String, Object>> response = controller.getSkeletonByToolId(toolId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody())
                    .containsEntry("toolId", toolId.toString())
                    .containsEntry("skeleton", null)
                    .containsEntry("paths", List.of())
                    .containsKey("hint");
        }

        @Test
        @DisplayName("returns 200 with skeleton:null + hint when row exists but stored skeleton is null (cold-start branch b)")
        void returnsGracefulColdStartWhenRowExistsButSkeletonIsNull() {
            // Branch (b) of the converged "no schema" check: a tool_responses row exists
            // (so the service emits {toolId, responseId, skeleton:null, paths:[]}) but
            // never had a non-empty execution. Same agent contract as branch (a): hand
            // back the cold-start payload with the actionable hint, not a row that the
            // agent will mistake for "schema exists, just empty."
            UUID toolId = UUID.randomUUID();
            Map<String, Object> result = new HashMap<>();
            result.put("toolId", toolId.toString());
            result.put("responseId", UUID.randomUUID().toString());
            result.put("skeleton", null);
            result.put("paths", List.of());

            when(service.getSkeletonByToolId(toolId)).thenReturn(result);

            ResponseEntity<Map<String, Object>> response = controller.getSkeletonByToolId(toolId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody())
                    .containsEntry("toolId", toolId.toString())
                    .containsEntry("skeleton", null)
                    .containsEntry("paths", List.of())
                    .containsKey("hint");
            // The hint is the agent's only signal that the cold-start applies - must be present.
            assertThat(response.getBody().get("hint")).asString().isNotBlank();
        }

        @Test
        @DisplayName("should return 200 when result has both error and skeleton")
        void returns200WhenResultHasBothErrorAndSkeleton() {
            UUID toolId = UUID.randomUUID();
            Map<String, Object> result = new HashMap<>();
            result.put("error", "Some warning");
            result.put("skeleton", Map.of("type", "object"));

            when(service.getSkeletonByToolId(toolId)).thenReturn(result);

            ResponseEntity<Map<String, Object>> response = controller.getSkeletonByToolId(toolId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("POST /migrate")
    class TriggerMigrationTests {

        @Test
        @DisplayName("should trigger migration with specified batch size")
        void triggersMigrationWithBatchSize() {
            when(service.runMigrationBatch(50)).thenReturn(50);

            ResponseEntity<String> response = controller.triggerMigration(50);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("50");

            verify(service).runMigrationBatch(50);
        }

        @Test
        @DisplayName("should use default batch size when not specified")
        void usesDefaultBatchSize() {
            when(service.runMigrationBatch(100)).thenReturn(75);

            ResponseEntity<String> response = controller.triggerMigration(100);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("75");
        }
    }

    @Nested
    @DisplayName("POST /{responseId}/regenerate")
    class RegenerateSkeletonTests {

        @Test
        @DisplayName("should regenerate skeleton successfully")
        void regeneratesSkeletonSuccessfully() {
            UUID responseId = UUID.randomUUID();

            doNothing().when(service).generateAndSaveSkeleton(responseId);

            ResponseEntity<Void> response = controller.regenerateSkeleton(responseId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(service).generateAndSaveSkeleton(responseId);
        }
    }
}
