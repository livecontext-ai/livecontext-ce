package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ApiSubcategoryEntity;
import com.apimarketplace.catalog.domain.ToolNameEntity;
import com.apimarketplace.catalog.domain.ToolSignalEntity;
import com.apimarketplace.catalog.repository.ApiSubcategoryRepository;
import com.apimarketplace.catalog.repository.ToolNameRepository;
import com.apimarketplace.catalog.repository.ToolSignalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SignalsService.
 *
 * SignalsService generates and updates signals for tool names,
 * deriving action, resource, and method from tool naming conventions.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SignalsService")
class SignalsServiceTest {

    @Mock
    private ToolNameRepository toolNameRepository;

    @Mock
    private ToolSignalRepository toolSignalRepository;

    @Mock
    private ApiSubcategoryRepository apiSubcategoryRepository;

    private SignalsService signalsService;

    @BeforeEach
    void setUp() {
        signalsService = new SignalsService(toolNameRepository, toolSignalRepository, apiSubcategoryRepository);
    }

    // ========================================================================
    // generateOrUpdateSignals tests
    // ========================================================================

    @Nested
    @DisplayName("generateOrUpdateSignals")
    class GenerateOrUpdateSignalsTests {

        @Test
        @DisplayName("should create new signal for tool name")
        void shouldCreateNewSignalForToolName() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            ToolNameEntity toolName = createToolName(toolId, "get_users");
            when(toolSignalRepository.findByToolId(toolId)).thenReturn(Optional.empty());

            // Act
            signalsService.generateOrUpdateSignals(toolName);

            // Assert
            ArgumentCaptor<ToolSignalEntity> captor = ArgumentCaptor.forClass(ToolSignalEntity.class);
            verify(toolSignalRepository).save(captor.capture());

            ToolSignalEntity saved = captor.getValue();
            assertEquals(toolId, saved.getToolId());
            assertEquals("get", saved.getAction());
            assertEquals("users", saved.getResource());
            assertEquals("GET", saved.getMethod());
        }

        @Test
        @DisplayName("should update existing signal")
        void shouldUpdateExistingSignal() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            ToolNameEntity toolName = createToolName(toolId, "create_order");

            ToolSignalEntity existing = new ToolSignalEntity();
            existing.setToolId(toolId);
            when(toolSignalRepository.findByToolId(toolId)).thenReturn(Optional.of(existing));

            // Act
            signalsService.generateOrUpdateSignals(toolName);

            // Assert
            verify(toolSignalRepository).save(existing);
            assertEquals("create", existing.getAction());
            assertEquals("order", existing.getResource());
            assertEquals("POST", existing.getMethod());
        }

        @Test
        @DisplayName("should set provider from subcategory")
        void shouldSetProviderFromSubcategory() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            UUID subcategoryId = UUID.randomUUID();
            ToolNameEntity toolName = createToolName(toolId, "get_profile");
            toolName.setSubcategoryId(subcategoryId);

            ApiSubcategoryEntity subcategory = new ApiSubcategoryEntity();
            subcategory.setId(subcategoryId);
            subcategory.setName("Instagram");

            when(toolSignalRepository.findByToolId(toolId)).thenReturn(Optional.empty());
            when(apiSubcategoryRepository.findById(subcategoryId)).thenReturn(Optional.of(subcategory));

            // Act
            signalsService.generateOrUpdateSignals(toolName);

            // Assert
            ArgumentCaptor<ToolSignalEntity> captor = ArgumentCaptor.forClass(ToolSignalEntity.class);
            verify(toolSignalRepository).save(captor.capture());
            assertEquals("Instagram", captor.getValue().getProvider());
        }
    }

    // ========================================================================
    // Action derivation tests
    // ========================================================================

    @Nested
    @DisplayName("Action derivation")
    class ActionDerivationTests {

        @Test
        @DisplayName("should derive 'get' action")
        void shouldDeriveGetAction() {
            assertActionDerived("get_users", "get");
            assertActionDerived("get_profile_by_id", "get");
        }

        @Test
        @DisplayName("should derive 'list' action")
        void shouldDeriveListAction() {
            assertActionDerived("list_orders", "list");
            assertActionDerived("list_all_items", "list");
        }

        @Test
        @DisplayName("should derive 'create' action")
        void shouldDeriveCreateAction() {
            assertActionDerived("create_user", "create");
            assertActionDerived("create_new_order", "create");
        }

        @Test
        @DisplayName("should derive 'delete' action")
        void shouldDeriveDeleteAction() {
            assertActionDerived("delete_item", "delete");
            assertActionDerived("delete_user_by_id", "delete");
        }

        @Test
        @DisplayName("should derive 'update' action")
        void shouldDeriveUpdateAction() {
            assertActionDerived("update_profile", "update");
            assertActionDerived("update_order_status", "update");
        }

        private void assertActionDerived(String toolName, String expectedAction) {
            UUID toolId = UUID.randomUUID();
            ToolNameEntity tool = createToolName(toolId, toolName);
            when(toolSignalRepository.findByToolId(toolId)).thenReturn(Optional.empty());

            signalsService.generateOrUpdateSignals(tool);

            ArgumentCaptor<ToolSignalEntity> captor = ArgumentCaptor.forClass(ToolSignalEntity.class);
            verify(toolSignalRepository).save(captor.capture());
            assertEquals(expectedAction, captor.getValue().getAction());

            reset(toolSignalRepository);
        }
    }

    // ========================================================================
    // Method derivation tests
    // ========================================================================

    @Nested
    @DisplayName("Method derivation")
    class MethodDerivationTests {

        @Test
        @DisplayName("should derive GET method for get/list/query actions")
        void shouldDeriveGetMethod() {
            assertMethodDerived("get_users", "GET");
            assertMethodDerived("list_items", "GET");
            assertMethodDerived("query_results", "GET");
        }

        @Test
        @DisplayName("should derive POST method for create/send actions")
        void shouldDerivePostMethod() {
            assertMethodDerived("create_order", "POST");
            assertMethodDerived("send_message", "POST");
        }

        @Test
        @DisplayName("should derive PUT method for update action")
        void shouldDerivePutMethod() {
            assertMethodDerived("update_profile", "PUT");
        }

        @Test
        @DisplayName("should derive DELETE method for delete action")
        void shouldDeriveDeleteMethod() {
            assertMethodDerived("delete_item", "DELETE");
        }

        private void assertMethodDerived(String toolName, String expectedMethod) {
            UUID toolId = UUID.randomUUID();
            ToolNameEntity tool = createToolName(toolId, toolName);
            when(toolSignalRepository.findByToolId(toolId)).thenReturn(Optional.empty());

            signalsService.generateOrUpdateSignals(tool);

            ArgumentCaptor<ToolSignalEntity> captor = ArgumentCaptor.forClass(ToolSignalEntity.class);
            verify(toolSignalRepository).save(captor.capture());
            assertEquals(expectedMethod, captor.getValue().getMethod());

            reset(toolSignalRepository);
        }
    }

    // ========================================================================
    // generateAllSignals tests
    // ========================================================================

    @Nested
    @DisplayName("generateAllSignals")
    class GenerateAllSignalsTests {

        @Test
        @DisplayName("should process all tool names")
        void shouldProcessAllToolNames() {
            // Arrange
            ToolNameEntity tool1 = createToolName(UUID.randomUUID(), "get_users");
            ToolNameEntity tool2 = createToolName(UUID.randomUUID(), "create_order");
            when(toolNameRepository.findByIsActiveTrueOrderByNameAsc()).thenReturn(List.of(tool1, tool2));
            when(toolSignalRepository.findByToolId(any())).thenReturn(Optional.empty());

            // Act
            signalsService.generateAllSignals();

            // Assert
            verify(toolSignalRepository, times(2)).save(any(ToolSignalEntity.class));
        }

        @Test
        @DisplayName("should handle empty tool list")
        void shouldHandleEmptyToolList() {
            // Arrange
            when(toolNameRepository.findByIsActiveTrueOrderByNameAsc()).thenReturn(List.of());

            // Act
            signalsService.generateAllSignals();

            // Assert
            verify(toolSignalRepository, never()).save(any());
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private ToolNameEntity createToolName(UUID id, String name) {
        ToolNameEntity toolName = new ToolNameEntity();
        toolName.setId(id);
        toolName.setName(name);
        toolName.setIsActive(true);
        toolName.setRequiresUserCredentials(false);
        toolName.setRunScope("provider");
        return toolName;
    }
}
