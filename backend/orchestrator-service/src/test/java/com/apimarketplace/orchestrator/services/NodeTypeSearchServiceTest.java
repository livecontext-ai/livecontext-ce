package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.domain.NodeTypeDocumentationEntity;
import com.apimarketplace.orchestrator.repository.NodeTypeDocumentationRepository;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link NodeTypeSearchService}.
 * Tests enabled/disabled filtering and toggle functionality.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NodeTypeSearchService")
class NodeTypeSearchServiceTest {

    @Mock
    private NodeTypeDocumentationRepository repository;

    private NodeTypeSearchService service;

    @BeforeEach
    void setUp() {
        service = new NodeTypeSearchService(repository);
    }

    private NodeTypeDocumentationEntity createEntity(String type, String label, String category, boolean enabled) {
        NodeTypeDocumentationEntity entity = new NodeTypeDocumentationEntity();
        entity.setType(type);
        entity.setLabel(label);
        entity.setCategory(category);
        entity.setDescription("Description for " + label);
        entity.setEnabled(enabled);
        entity.setVariablePrefix(category.equals("trigger") ? "trigger" : "core");
        return entity;
    }

    @Nested
    @DisplayName("getAllTypes (agent-facing, enabled only)")
    class GetAllTypes {

        @Test
        @DisplayName("should return only enabled types")
        void shouldReturnOnlyEnabled() {
            NodeTypeDocumentationEntity enabled1 = createEntity("decision", "Decision", "control_flow", true);
            NodeTypeDocumentationEntity enabled2 = createEntity("split", "Split", "control_flow", true);

            when(repository.findByEnabledTrueOrderByCategoryAscLabelAsc())
                    .thenReturn(List.of(enabled1, enabled2));

            List<Map<String, Object>> result = service.getAllTypes();

            assertEquals(2, result.size());
            assertEquals("decision", result.get(0).get("type"));
            assertEquals("split", result.get(1).get("type"));
            // Verify we called the enabled-only method
            verify(repository).findByEnabledTrueOrderByCategoryAscLabelAsc();
            verify(repository, never()).findAllByOrderByCategoryAscLabelAsc();
        }
    }

    @Nested
    @DisplayName("getAllGroupedByCategory (agent-facing, enabled only)")
    class GetAllGroupedByCategory {

        @Test
        @DisplayName("should group only enabled types by category")
        void shouldGroupEnabledByCategory() {
            NodeTypeDocumentationEntity trigger = createEntity("webhook", "Webhook", "trigger", true);
            NodeTypeDocumentationEntity control = createEntity("decision", "Decision", "control_flow", true);

            when(repository.findByEnabledTrueOrderByCategoryAscLabelAsc())
                    .thenReturn(List.of(control, trigger));

            Map<String, List<Map<String, Object>>> result = service.getAllGroupedByCategory();

            assertEquals(2, result.size());
            assertTrue(result.containsKey("trigger"));
            assertTrue(result.containsKey("control_flow"));
        }
    }

    @Nested
    @DisplayName("search (agent-facing, enabled only)")
    class Search {

        @Test
        @DisplayName("should use enabled-only queries for search")
        void shouldUseEnabledQueries() {
            NodeTypeDocumentationEntity entity = createEntity("decision", "Decision", "control_flow", true);

            when(repository.searchByQueryEnabled(eq("decision"), eq(5)))
                    .thenReturn(List.of(entity));

            List<Map<String, Object>> result = service.search("decision", null);

            assertEquals(1, result.size());
            assertEquals("decision", result.get(0).get("type"));
            verify(repository).searchByQueryEnabled(eq("decision"), eq(5));
        }

        @Test
        @DisplayName("should fallback to pattern search with enabled filter")
        void shouldFallbackToPatternEnabled() {
            NodeTypeDocumentationEntity entity = createEntity("decision", "Decision", "control_flow", true);

            when(repository.searchByQueryEnabled(anyString(), anyInt()))
                    .thenReturn(List.of());
            when(repository.searchByPatternEnabled(anyString(), anyInt()))
                    .thenReturn(List.of(entity));

            List<Map<String, Object>> result = service.search("dec", null);

            assertEquals(1, result.size());
            verify(repository).searchByPatternEnabled(anyString(), anyInt());
        }

        @Test
        @DisplayName("should return all enabled types for empty query")
        void shouldReturnAllForEmptyQuery() {
            NodeTypeDocumentationEntity entity = createEntity("decision", "Decision", "control_flow", true);

            when(repository.findByEnabledTrueOrderByCategoryAscLabelAsc())
                    .thenReturn(List.of(entity));

            List<Map<String, Object>> result = service.search("", null);

            assertEquals(1, result.size());
        }
    }

    @Nested
    @DisplayName("isNodeTypeEnabled")
    class IsNodeTypeEnabled {

        @Test
        @DisplayName("should return true for enabled type")
        void shouldReturnTrueForEnabled() {
            NodeTypeDocumentationEntity entity = createEntity("decision", "Decision", "control_flow", true);
            when(repository.findByType("decision")).thenReturn(Optional.of(entity));

            assertTrue(service.isNodeTypeEnabled("decision"));
        }

        @Test
        @DisplayName("should return false for disabled type")
        void shouldReturnFalseForDisabled() {
            NodeTypeDocumentationEntity entity = createEntity("xml", "XML", "action", false);
            when(repository.findByType("xml")).thenReturn(Optional.of(entity));

            assertFalse(service.isNodeTypeEnabled("xml"));
        }

        @Test
        @DisplayName("should return true for unknown type (not in DB)")
        void shouldReturnTrueForUnknown() {
            when(repository.findByType("unknown_type")).thenReturn(Optional.empty());

            assertTrue(service.isNodeTypeEnabled("unknown_type"));
        }
    }

    @Nested
    @DisplayName("toggleEnabled")
    class ToggleEnabled {

        @Test
        @DisplayName("should disable an enabled type")
        void shouldDisableType() {
            NodeTypeDocumentationEntity entity = createEntity("xml", "XML", "action", true);
            when(repository.findByType("xml")).thenReturn(Optional.of(entity));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            NodeTypeDocumentationEntity result = service.toggleEnabled("xml", false);

            assertFalse(result.isEnabled());
            verify(repository).save(entity);
        }

        @Test
        @DisplayName("should enable a disabled type")
        void shouldEnableType() {
            NodeTypeDocumentationEntity entity = createEntity("xml", "XML", "action", false);
            when(repository.findByType("xml")).thenReturn(Optional.of(entity));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            NodeTypeDocumentationEntity result = service.toggleEnabled("xml", true);

            assertTrue(result.isEnabled());
            verify(repository).save(entity);
        }

        @Test
        @DisplayName("should throw for unknown type")
        void shouldThrowForUnknownType() {
            when(repository.findByType("nonexistent")).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class,
                    () -> service.toggleEnabled("nonexistent", false));
        }
    }

    @Nested
    @DisplayName("getAllTypesIncludingDisabled (admin)")
    class GetAllTypesIncludingDisabled {

        @Test
        @DisplayName("should return all types including disabled")
        void shouldReturnAllIncludingDisabled() {
            NodeTypeDocumentationEntity enabled = createEntity("decision", "Decision", "control_flow", true);
            NodeTypeDocumentationEntity disabled = createEntity("xml", "XML", "action", false);

            when(repository.findAllByOrderByCategoryAscLabelAsc())
                    .thenReturn(List.of(disabled, enabled));

            List<NodeTypeDocumentationEntity> result = service.getAllTypesIncludingDisabled();

            assertEquals(2, result.size());
            verify(repository).findAllByOrderByCategoryAscLabelAsc();
        }
    }

    @Nested
    @DisplayName("toMap includes enabled field")
    class ToMapIncludesEnabled {

        @Test
        @DisplayName("toMap should include enabled=true for enabled entity")
        void shouldIncludeEnabledTrue() {
            NodeTypeDocumentationEntity entity = createEntity("decision", "Decision", "control_flow", true);

            Map<String, Object> map = entity.toMap();

            assertEquals(true, map.get("enabled"));
        }

        @Test
        @DisplayName("toMap should include enabled=false for disabled entity")
        void shouldIncludeEnabledFalse() {
            NodeTypeDocumentationEntity entity = createEntity("xml", "XML", "action", false);

            Map<String, Object> map = entity.toMap();

            assertEquals(false, map.get("enabled"));
        }
    }
}
