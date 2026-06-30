package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ToolResponseEntity;
import com.apimarketplace.catalog.repository.ToolResponseRepository;
import com.apimarketplace.catalog.util.JsonSkeletonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StructureSkeletonService.
 *
 * StructureSkeletonService handles JSON skeleton generation and navigation for tool responses.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StructureSkeletonService")
class StructureSkeletonServiceTest {

    @Mock
    private ToolResponseRepository repository;

    @Mock
    private JsonSkeletonGenerator skeletonGenerator;

    private ObjectMapper objectMapper;

    private StructureSkeletonService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new StructureSkeletonService(repository, skeletonGenerator, objectMapper);
    }

    // ========================================================================
    // getRootStructure tests
    // ========================================================================

    @Nested
    @DisplayName("getRootStructure()")
    class GetRootStructureTests {

        @Test
        @DisplayName("should delegate to repository")
        void shouldDelegateToRepository() {
            // Arrange
            UUID responseId = UUID.randomUUID();
            List<ToolResponseRepository.StructureNode> expected = List.of(
                createStructureNode("id", "string", false),
                createStructureNode("data", "obj", true)
            );
            when(repository.getStructureRoot(responseId)).thenReturn(expected);

            // Act
            List<ToolResponseRepository.StructureNode> result = service.getRootStructure(responseId);

            // Assert
            assertEquals(expected, result);
            verify(repository).getStructureRoot(responseId);
        }
    }

    // ========================================================================
    // getPathStructure tests
    // ========================================================================

    @Nested
    @DisplayName("getPathStructure()")
    class GetPathStructureTests {

        @Test
        @DisplayName("should return root structure when path is null")
        void shouldReturnRootStructureWhenPathIsNull() {
            // Arrange
            UUID responseId = UUID.randomUUID();
            List<ToolResponseRepository.StructureNode> expected = List.of(
                createStructureNode("root", "obj", true)
            );
            when(repository.getStructureRoot(responseId)).thenReturn(expected);

            // Act
            List<ToolResponseRepository.StructureNode> result = service.getPathStructure(responseId, null);

            // Assert
            assertEquals(expected, result);
            verify(repository).getStructureRoot(responseId);
        }

        @Test
        @DisplayName("should return root structure when path is empty")
        void shouldReturnRootStructureWhenPathIsEmpty() {
            // Arrange
            UUID responseId = UUID.randomUUID();
            List<ToolResponseRepository.StructureNode> expected = List.of(
                createStructureNode("root", "obj", true)
            );
            when(repository.getStructureRoot(responseId)).thenReturn(expected);

            // Act
            List<ToolResponseRepository.StructureNode> result = service.getPathStructure(responseId, new String[]{});

            // Assert
            assertEquals(expected, result);
        }

        @Test
        @DisplayName("should navigate object path and return children")
        void shouldNavigateObjectPathAndReturnChildren() throws Exception {
            // Arrange
            UUID responseId = UUID.randomUUID();
            String skeleton = "{\"_t\":\"obj\",\"props\":{\"user\":{\"_t\":\"obj\",\"props\":{\"name\":\"string\",\"age\":\"number\"}}}}";

            ToolResponseEntity response = new ToolResponseEntity();
            response.setId(responseId);
            response.setStructureSkeleton(skeleton);
            when(repository.findById(responseId)).thenReturn(Optional.of(response));

            // Act
            List<ToolResponseRepository.StructureNode> result = service.getPathStructure(responseId, new String[]{"user"});

            // Assert
            assertEquals(2, result.size());
            assertTrue(result.stream().anyMatch(n -> "name".equals(n.getKey())));
            assertTrue(result.stream().anyMatch(n -> "age".equals(n.getKey())));
        }

        @Test
        @DisplayName("should navigate array path and return item children")
        void shouldNavigateArrayPathAndReturnItemChildren() throws Exception {
            // Arrange
            UUID responseId = UUID.randomUUID();
            String skeleton = "{\"_t\":\"obj\",\"props\":{\"items\":{\"_t\":\"arr\",\"items\":{\"_t\":\"obj\",\"props\":{\"id\":\"string\",\"value\":\"number\"}}}}}";

            ToolResponseEntity response = new ToolResponseEntity();
            response.setId(responseId);
            response.setStructureSkeleton(skeleton);
            when(repository.findById(responseId)).thenReturn(Optional.of(response));

            // Act
            List<ToolResponseRepository.StructureNode> result = service.getPathStructure(responseId, new String[]{"items"});

            // Assert
            assertEquals(2, result.size());
            assertTrue(result.stream().anyMatch(n -> "id".equals(n.getKey())));
            assertTrue(result.stream().anyMatch(n -> "value".equals(n.getKey())));
        }

        @Test
        @DisplayName("should return empty list when response not found")
        void shouldReturnEmptyListWhenResponseNotFound() {
            // Arrange
            UUID responseId = UUID.randomUUID();
            when(repository.findById(responseId)).thenReturn(Optional.empty());

            // Act
            List<ToolResponseRepository.StructureNode> result = service.getPathStructure(responseId, new String[]{"path"});

            // Assert
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty list when skeleton is null")
        void shouldReturnEmptyListWhenSkeletonIsNull() {
            // Arrange
            UUID responseId = UUID.randomUUID();
            ToolResponseEntity response = new ToolResponseEntity();
            response.setId(responseId);
            response.setStructureSkeleton(null);
            when(repository.findById(responseId)).thenReturn(Optional.of(response));

            // Act
            List<ToolResponseRepository.StructureNode> result = service.getPathStructure(responseId, new String[]{"path"});

            // Assert
            assertTrue(result.isEmpty());
        }
    }

    // ========================================================================
    // generateAndSaveSkeleton tests
    // ========================================================================

    @Nested
    @DisplayName("generateAndSaveSkeleton()")
    class GenerateAndSaveSkeletonTests {

        @Test
        @DisplayName("should generate and save skeleton for valid response")
        void shouldGenerateAndSaveSkeletonForValidResponse() throws Exception {
            // Arrange
            UUID responseId = UUID.randomUUID();
            String jsonContent = "{\"id\":1,\"name\":\"test\"}";
            JsonNode root = objectMapper.readTree(jsonContent);
            JsonNode skeleton = objectMapper.readTree("{\"_t\":\"obj\",\"props\":{\"id\":\"number\",\"name\":\"string\"}}");

            ToolResponseEntity response = new ToolResponseEntity();
            response.setId(responseId);
            response.setExampleJsonb(jsonContent);
            response.setExample("example");

            when(repository.findById(responseId)).thenReturn(Optional.of(response));
            when(skeletonGenerator.generateSkeleton(any(JsonNode.class))).thenReturn(skeleton);
            when(repository.save(any(ToolResponseEntity.class))).thenReturn(response);

            // Act
            service.generateAndSaveSkeleton(responseId);

            // Assert
            verify(skeletonGenerator).generateSkeleton(any(JsonNode.class));
            verify(repository).save(response);
            assertNotNull(response.getStructureSkeleton());
        }

        @Test
        @DisplayName("should set example from exampleJsonb when example is null")
        void shouldSetExampleFromExampleJsonbWhenExampleIsNull() throws Exception {
            // Arrange
            UUID responseId = UUID.randomUUID();
            String jsonContent = "{\"id\":1}";
            JsonNode skeleton = objectMapper.readTree("{\"_t\":\"obj\"}");

            ToolResponseEntity response = new ToolResponseEntity();
            response.setId(responseId);
            response.setExampleJsonb(jsonContent);
            response.setExample(null);

            when(repository.findById(responseId)).thenReturn(Optional.of(response));
            when(skeletonGenerator.generateSkeleton(any(JsonNode.class))).thenReturn(skeleton);
            when(repository.save(any(ToolResponseEntity.class))).thenReturn(response);

            // Act
            service.generateAndSaveSkeleton(responseId);

            // Assert
            assertEquals(jsonContent, response.getExample());
        }

        @Test
        @DisplayName("should do nothing when response not found")
        void shouldDoNothingWhenResponseNotFound() {
            // Arrange
            UUID responseId = UUID.randomUUID();
            when(repository.findById(responseId)).thenReturn(Optional.empty());

            // Act
            service.generateAndSaveSkeleton(responseId);

            // Assert
            verify(skeletonGenerator, never()).generateSkeleton(any());
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("should do nothing when exampleJsonb is empty")
        void shouldDoNothingWhenExampleJsonbIsEmpty() {
            // Arrange
            UUID responseId = UUID.randomUUID();
            ToolResponseEntity response = new ToolResponseEntity();
            response.setId(responseId);
            response.setExampleJsonb("   ");

            when(repository.findById(responseId)).thenReturn(Optional.of(response));

            // Act
            service.generateAndSaveSkeleton(responseId);

            // Assert
            verify(skeletonGenerator, never()).generateSkeleton(any());
            verify(repository, never()).save(any());
        }
    }

    // ========================================================================
    // getSkeletonByToolId tests
    // ========================================================================

    @Nested
    @DisplayName("getSkeletonByToolId()")
    class GetSkeletonByToolIdTests {

        @Test
        @DisplayName("should return skeleton for existing default response")
        void shouldReturnSkeletonForExistingDefaultResponse() throws Exception {
            // Arrange
            UUID toolId = UUID.randomUUID();
            UUID responseId = UUID.randomUUID();
            String skeletonStr = "{\"_t\":\"obj\",\"props\":{\"name\":\"string\"}}";

            ToolResponseEntity response = new ToolResponseEntity();
            response.setId(responseId);
            response.setToolId(toolId);
            response.setStructureSkeleton(skeletonStr);

            when(repository.findByToolIdAndIsDefaultTrue(toolId)).thenReturn(Optional.of(response));

            // Act
            Map<String, Object> result = service.getSkeletonByToolId(toolId);

            // Assert
            assertEquals(toolId.toString(), result.get("toolId"));
            assertEquals(responseId.toString(), result.get("responseId"));
            assertNotNull(result.get("skeleton"));
            assertNotNull(result.get("paths"));
        }

        @Test
        @DisplayName("should return error when no response found")
        void shouldReturnErrorWhenNoResponseFound() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            when(repository.findByToolIdAndIsDefaultTrue(toolId)).thenReturn(Optional.empty());
            when(repository.findByToolId(toolId)).thenReturn(List.of());

            // Act
            Map<String, Object> result = service.getSkeletonByToolId(toolId);

            // Assert
            assertEquals(toolId.toString(), result.get("toolId"));
            assertTrue(result.containsKey("error"));
        }

        @Test
        @DisplayName("should return null skeleton when skeleton is empty")
        void shouldReturnNullSkeletonWhenSkeletonIsEmpty() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            UUID responseId = UUID.randomUUID();

            ToolResponseEntity response = new ToolResponseEntity();
            response.setId(responseId);
            response.setToolId(toolId);
            response.setStructureSkeleton("");

            when(repository.findByToolIdAndIsDefaultTrue(toolId)).thenReturn(Optional.of(response));

            // Act
            Map<String, Object> result = service.getSkeletonByToolId(toolId);

            // Assert
            assertNull(result.get("skeleton"));
            assertEquals(List.of(), result.get("paths"));
        }
    }

    // ========================================================================
    // runMigrationBatch tests
    // ========================================================================

    @Nested
    @DisplayName("runMigrationBatch()")
    class RunMigrationBatchTests {

        @Test
        @DisplayName("should process batch and return count")
        void shouldProcessBatchAndReturnCount() throws Exception {
            // Arrange
            int batchSize = 10;
            String jsonContent = "{\"id\":1}";
            JsonNode skeleton = objectMapper.readTree("{\"_t\":\"obj\"}");

            ToolResponseEntity response1 = new ToolResponseEntity();
            response1.setId(UUID.randomUUID());
            response1.setExampleJsonb(jsonContent);

            ToolResponseEntity response2 = new ToolResponseEntity();
            response2.setId(UUID.randomUUID());
            response2.setExampleJsonb(jsonContent);

            when(repository.findResponsesWithoutSkeleton(batchSize)).thenReturn(List.of(response1, response2));
            when(skeletonGenerator.generateSkeleton(any(JsonNode.class))).thenReturn(skeleton);
            when(repository.updateStructureSkeleton(any(UUID.class), anyString())).thenReturn(1);

            // Act
            int count = service.runMigrationBatch(batchSize);

            // Assert
            assertEquals(2, count);
            verify(repository, times(2)).updateStructureSkeleton(any(UUID.class), anyString());
        }

        @Test
        @DisplayName("should skip responses with empty exampleJsonb")
        void shouldSkipResponsesWithEmptyExampleJsonb() {
            // Arrange
            ToolResponseEntity response = new ToolResponseEntity();
            response.setId(UUID.randomUUID());
            response.setExampleJsonb("  ");

            when(repository.findResponsesWithoutSkeleton(anyInt())).thenReturn(List.of(response));

            // Act
            int count = service.runMigrationBatch(10);

            // Assert
            assertEquals(0, count);
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private ToolResponseRepository.StructureNode createStructureNode(String key, String type, boolean hasChildren) {
        return new ToolResponseRepository.StructureNode() {
            @Override
            public String getKey() { return key; }
            @Override
            public String getType() { return type; }
            @Override
            public Boolean getHasChildren() { return hasChildren; }
        };
    }
}
