package com.apimarketplace.catalog.service.monetization;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiToolMonetizationEntity;
import com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest;
import com.apimarketplace.catalog.domain.dto.MonetizationResponse;
import com.apimarketplace.catalog.repository.ApiToolMonetizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MonetizationService.
 *
 * MonetizationService handles all monetization-related operations including
 * FREEMIUM and PAID pricing models for API tools.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MonetizationService")
class MonetizationServiceTest {

    @Mock
    private ApiToolMonetizationRepository monetizationRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private MonetizationService service;

    @BeforeEach
    void setUp() {
        service = new MonetizationService(monetizationRepository, jdbcTemplate);
    }

    // ========================================================================
    // deleteMonetizationsForTool tests
    // ========================================================================

    @Nested
    @DisplayName("deleteMonetizationsForTool()")
    class DeleteMonetizationsForToolTests {

        @Test
        @DisplayName("should delete all monetizations for a tool")
        void shouldDeleteAllMonetizationsForTool() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            ApiToolMonetizationEntity monetization1 = new ApiToolMonetizationEntity();
            monetization1.setId(UUID.randomUUID());
            monetization1.setApiToolId(toolId);
            monetization1.setMonetizationType("FREEMIUM");

            ApiToolMonetizationEntity monetization2 = new ApiToolMonetizationEntity();
            monetization2.setId(UUID.randomUUID());
            monetization2.setApiToolId(toolId);
            monetization2.setMonetizationType("PAID");

            when(monetizationRepository.findByApiToolId(toolId)).thenReturn(List.of(monetization1, monetization2));

            // Act
            service.deleteMonetizationsForTool(toolId);

            // Assert
            verify(monetizationRepository).delete(monetization1);
            verify(monetizationRepository).delete(monetization2);
        }

        @Test
        @DisplayName("should handle empty monetization list")
        void shouldHandleEmptyMonetizationList() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            when(monetizationRepository.findByApiToolId(toolId)).thenReturn(Collections.emptyList());

            // Act
            service.deleteMonetizationsForTool(toolId);

            // Assert
            verify(monetizationRepository, never()).delete(any(ApiToolMonetizationEntity.class));
        }
    }

    // ========================================================================
    // getToolMonetization tests
    // ========================================================================

    @Nested
    @DisplayName("getToolMonetization()")
    class GetToolMonetizationTests {

        @Test
        @DisplayName("should return monetization data for tool")
        void shouldReturnMonetizationDataForTool() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            UUID monetizationId = UUID.randomUUID();

            MonetizationResponse mockResponse = new MonetizationResponse(
                    monetizationId, toolId, "FREEMIUM", null,
                    100, "hour", 1000, "per-user",
                    1, BigDecimal.ZERO, 1, null,
                    null, null, null,
                    System.currentTimeMillis(), System.currentTimeMillis()
            );

            when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(toolId)))
                    .thenReturn(List.of(mockResponse));

            // Act
            List<MonetizationResponse> result = service.getToolMonetization(toolId);

            // Assert
            assertEquals(1, result.size());
            assertEquals(monetizationId, result.get(0).id());
            assertEquals("FREEMIUM", result.get(0).monetizationType());
        }

        @Test
        @DisplayName("should return empty list when no monetization data")
        void shouldReturnEmptyListWhenNoMonetizationData() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(toolId)))
                    .thenReturn(Collections.emptyList());

            // Act
            List<MonetizationResponse> result = service.getToolMonetization(toolId);

            // Assert
            assertTrue(result.isEmpty());
        }
    }

    // ========================================================================
    // updatePricingModelsForTools tests
    // ========================================================================

    @Nested
    @DisplayName("updatePricingModelsForTools()")
    class UpdatePricingModelsForToolsTests {

        @Test
        @DisplayName("should throw when no pricing models selected")
        void shouldThrowWhenNoPricingModelsSelected() {
            // Arrange
            ApiToolEntity tool = createTestTool();
            ApiConfigurationRequest.PricingModelsUpdateRequest request =
                    new ApiConfigurationRequest.PricingModelsUpdateRequest("api-1", List.of());

            Function<ApiToolEntity, String> nameProvider = t -> "test_tool";

            // Act & Assert
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.updatePricingModelsForTools(List.of(tool), request, nameProvider));

            assertTrue(exception.getMessage().contains("At least one pricing model must be selected"));
        }

        @Test
        @DisplayName("should throw when pricing models is null")
        void shouldThrowWhenPricingModelsIsNull() {
            // Arrange
            ApiToolEntity tool = createTestTool();
            ApiConfigurationRequest.PricingModelsUpdateRequest request =
                    new ApiConfigurationRequest.PricingModelsUpdateRequest("api-1", null);

            Function<ApiToolEntity, String> nameProvider = t -> "test_tool";

            // Act & Assert
            assertThrows(IllegalArgumentException.class,
                    () -> service.updatePricingModelsForTools(List.of(tool), request, nameProvider));
        }

        @Test
        @DisplayName("should create FREEMIUM monetization for new tool")
        void shouldCreateFreemiumMonetizationForNewTool() {
            // Arrange
            ApiToolEntity tool = createTestTool();
            ApiConfigurationRequest.PricingModelsUpdateRequest request =
                    new ApiConfigurationRequest.PricingModelsUpdateRequest("api-1", List.of("FREEMIUM"));

            when(monetizationRepository.findByApiToolId(tool.getId())).thenReturn(Collections.emptyList());

            Function<ApiToolEntity, String> nameProvider = t -> "test_tool";

            // Act
            service.updatePricingModelsForTools(List.of(tool), request, nameProvider);

            // Assert
            ArgumentCaptor<ApiToolMonetizationEntity> captor = ArgumentCaptor.forClass(ApiToolMonetizationEntity.class);
            verify(monetizationRepository).save(captor.capture());

            ApiToolMonetizationEntity saved = captor.getValue();
            assertEquals("FREEMIUM", saved.getMonetizationType());
            assertEquals(tool.getId(), saved.getApiToolId());
            assertEquals(1000, saved.getFreeRequests());
        }

        @Test
        @DisplayName("should create PAID monetization plans for new tool")
        void shouldCreatePaidMonetizationPlansForNewTool() {
            // Arrange
            ApiToolEntity tool = createTestTool();
            ApiConfigurationRequest.PricingModelsUpdateRequest request =
                    new ApiConfigurationRequest.PricingModelsUpdateRequest("api-1", List.of("PAID"));

            when(monetizationRepository.findByApiToolId(tool.getId())).thenReturn(Collections.emptyList());

            Function<ApiToolEntity, String> nameProvider = t -> "test_tool";

            // Act
            service.updatePricingModelsForTools(List.of(tool), request, nameProvider);

            // Assert
            // PAID creates 4 plans: basic, pro, ultra, mega
            verify(monetizationRepository, times(4)).save(any(ApiToolMonetizationEntity.class));
        }

        @Test
        @DisplayName("should skip existing monetization types")
        void shouldSkipExistingMonetizationTypes() {
            // Arrange
            ApiToolEntity tool = createTestTool();
            ApiToolMonetizationEntity existingFreemium = new ApiToolMonetizationEntity();
            existingFreemium.setApiToolId(tool.getId());
            existingFreemium.setMonetizationType("FREEMIUM");

            ApiConfigurationRequest.PricingModelsUpdateRequest request =
                    new ApiConfigurationRequest.PricingModelsUpdateRequest("api-1", List.of("FREEMIUM"));

            when(monetizationRepository.findByApiToolId(tool.getId())).thenReturn(List.of(existingFreemium));

            Function<ApiToolEntity, String> nameProvider = t -> "test_tool";

            // Act
            service.updatePricingModelsForTools(List.of(tool), request, nameProvider);

            // Assert
            verify(monetizationRepository, never()).save(any(ApiToolMonetizationEntity.class));
        }

        @Test
        @DisplayName("should remove deselected monetization types")
        void shouldRemoveDeselectedMonetizationTypes() {
            // Arrange
            ApiToolEntity tool = createTestTool();
            ApiToolMonetizationEntity existingFreemium = new ApiToolMonetizationEntity();
            existingFreemium.setId(UUID.randomUUID());
            existingFreemium.setApiToolId(tool.getId());
            existingFreemium.setMonetizationType("FREEMIUM");

            ApiToolMonetizationEntity existingPaid = new ApiToolMonetizationEntity();
            existingPaid.setId(UUID.randomUUID());
            existingPaid.setApiToolId(tool.getId());
            existingPaid.setMonetizationType("PAID");

            // Only FREEMIUM selected - PAID should be removed
            ApiConfigurationRequest.PricingModelsUpdateRequest request =
                    new ApiConfigurationRequest.PricingModelsUpdateRequest("api-1", List.of("FREEMIUM"));

            when(monetizationRepository.findByApiToolId(tool.getId()))
                    .thenReturn(List.of(existingFreemium, existingPaid));

            Function<ApiToolEntity, String> nameProvider = t -> "test_tool";

            // Act
            service.updatePricingModelsForTools(List.of(tool), request, nameProvider);

            // Assert
            verify(monetizationRepository).deleteById(existingPaid.getId());
            verify(monetizationRepository, never()).deleteById(existingFreemium.getId());
        }
    }

    // ========================================================================
    // updateToolFreemiumConfig tests
    // ========================================================================

    @Nested
    @DisplayName("updateToolFreemiumConfig()")
    class UpdateToolFreemiumConfigTests {

        @Test
        @DisplayName("should create new FREEMIUM when none exists")
        void shouldCreateNewFreemiumWhenNoneExists() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            ApiConfigurationRequest.ToolFreemiumConfigDto config =
                    new ApiConfigurationRequest.ToolFreemiumConfigDto(500, "per-day", 100, "minute", 2, 5);

            when(monetizationRepository.findByApiToolId(toolId)).thenReturn(Collections.emptyList());

            // Act
            service.updateToolFreemiumConfig(toolId, config);

            // Assert
            ArgumentCaptor<ApiToolMonetizationEntity> captor = ArgumentCaptor.forClass(ApiToolMonetizationEntity.class);
            verify(monetizationRepository).save(captor.capture());

            ApiToolMonetizationEntity saved = captor.getValue();
            assertEquals("FREEMIUM", saved.getMonetizationType());
            assertEquals(500, saved.getFreeRequests());
            assertEquals("per-day", saved.getFreeRequestsType());
            assertEquals(100, saved.getRateLimitRequests());
            assertEquals("minute", saved.getRateLimitPeriod());
        }

        @Test
        @DisplayName("should update existing FREEMIUM configuration")
        void shouldUpdateExistingFreemiumConfiguration() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            ApiToolMonetizationEntity existing = new ApiToolMonetizationEntity();
            existing.setId(UUID.randomUUID());
            existing.setApiToolId(toolId);
            existing.setMonetizationType("FREEMIUM");
            existing.setFreeRequests(100);

            ApiConfigurationRequest.ToolFreemiumConfigDto config =
                    new ApiConfigurationRequest.ToolFreemiumConfigDto(500, null, null, null, null, null);

            when(monetizationRepository.findByApiToolId(toolId)).thenReturn(List.of(existing));

            // Act
            service.updateToolFreemiumConfig(toolId, config);

            // Assert
            ArgumentCaptor<ApiToolMonetizationEntity> captor = ArgumentCaptor.forClass(ApiToolMonetizationEntity.class);
            verify(monetizationRepository).save(captor.capture());

            ApiToolMonetizationEntity saved = captor.getValue();
            assertEquals(500, saved.getFreeRequests());
            assertNotNull(saved.getUpdatedAt());
        }

        @Test
        @DisplayName("should handle partial updates")
        void shouldHandlePartialUpdates() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            ApiToolMonetizationEntity existing = new ApiToolMonetizationEntity();
            existing.setApiToolId(toolId);
            existing.setMonetizationType("FREEMIUM");
            existing.setFreeRequests(100);
            existing.setRateLimitRequests(50);

            // Only update freeRequests
            ApiConfigurationRequest.ToolFreemiumConfigDto config =
                    new ApiConfigurationRequest.ToolFreemiumConfigDto(200, null, null, null, null, null);

            when(monetizationRepository.findByApiToolId(toolId)).thenReturn(List.of(existing));

            // Act
            service.updateToolFreemiumConfig(toolId, config);

            // Assert
            ArgumentCaptor<ApiToolMonetizationEntity> captor = ArgumentCaptor.forClass(ApiToolMonetizationEntity.class);
            verify(monetizationRepository).save(captor.capture());

            ApiToolMonetizationEntity saved = captor.getValue();
            assertEquals(200, saved.getFreeRequests());
            assertEquals(50, saved.getRateLimitRequests()); // Unchanged
        }
    }

    // ========================================================================
    // batchUpdateToolsFreemiumConfig tests
    // ========================================================================

    @Nested
    @DisplayName("batchUpdateToolsFreemiumConfig()")
    class BatchUpdateToolsFreemiumConfigTests {

        @Test
        @DisplayName("should update multiple tools")
        void shouldUpdateMultipleTools() {
            // Arrange
            UUID toolId1 = UUID.randomUUID();
            UUID toolId2 = UUID.randomUUID();

            ApiConfigurationRequest.ToolFreemiumConfigDto config1 =
                    new ApiConfigurationRequest.ToolFreemiumConfigDto(500, "per-user", null, null, null, null);
            ApiConfigurationRequest.ToolFreemiumConfigDto config2 =
                    new ApiConfigurationRequest.ToolFreemiumConfigDto(1000, "per-user", null, null, null, null);

            Map<UUID, ApiConfigurationRequest.ToolFreemiumConfigDto> toolsConfig = new HashMap<>();
            toolsConfig.put(toolId1, config1);
            toolsConfig.put(toolId2, config2);

            when(monetizationRepository.findByApiToolId(any())).thenReturn(Collections.emptyList());

            // Act
            service.batchUpdateToolsFreemiumConfig(toolsConfig);

            // Assert
            verify(monetizationRepository, times(2)).save(any(ApiToolMonetizationEntity.class));
        }

        @Test
        @DisplayName("should continue on error for individual tool")
        void shouldContinueOnErrorForIndividualTool() {
            // Arrange
            UUID toolId1 = UUID.randomUUID();
            UUID toolId2 = UUID.randomUUID();

            ApiConfigurationRequest.ToolFreemiumConfigDto config =
                    new ApiConfigurationRequest.ToolFreemiumConfigDto(500, "per-user", null, null, null, null);

            Map<UUID, ApiConfigurationRequest.ToolFreemiumConfigDto> toolsConfig = new LinkedHashMap<>();
            toolsConfig.put(toolId1, config);
            toolsConfig.put(toolId2, config);

            when(monetizationRepository.findByApiToolId(toolId1)).thenThrow(new RuntimeException("DB error"));
            when(monetizationRepository.findByApiToolId(toolId2)).thenReturn(Collections.emptyList());

            // Act
            service.batchUpdateToolsFreemiumConfig(toolsConfig);

            // Assert - second tool should still be saved
            verify(monetizationRepository).save(any(ApiToolMonetizationEntity.class));
        }
    }

    // ========================================================================
    // updateToolPaidConfig tests
    // ========================================================================

    @Nested
    @DisplayName("updateToolPaidConfig()")
    class UpdateToolPaidConfigTests {

        @Test
        @DisplayName("should create new PAID when none exists")
        void shouldCreateNewPaidWhenNoneExists() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            ApiConfigurationRequest.ToolPaidConfigDto config =
                    new ApiConfigurationRequest.ToolPaidConfigDto("PRO", 5000,
                            BigDecimal.valueOf(9.99), BigDecimal.valueOf(0.01), true, 100, "minute");

            when(monetizationRepository.findByApiToolId(toolId)).thenReturn(Collections.emptyList());

            // Act
            service.updateToolPaidConfig(toolId, config);

            // Assert
            ArgumentCaptor<ApiToolMonetizationEntity> captor = ArgumentCaptor.forClass(ApiToolMonetizationEntity.class);
            verify(monetizationRepository).save(captor.capture());

            ApiToolMonetizationEntity saved = captor.getValue();
            assertEquals("PAID", saved.getMonetizationType());
            assertEquals("PRO", saved.getPlanName());
            assertEquals(5000, saved.getQuota());
            assertEquals(BigDecimal.valueOf(9.99), saved.getPrice());
        }

        @Test
        @DisplayName("should update existing PAID configuration")
        void shouldUpdateExistingPaidConfiguration() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            ApiToolMonetizationEntity existing = new ApiToolMonetizationEntity();
            existing.setApiToolId(toolId);
            existing.setMonetizationType("PAID");
            existing.setPlanName("BASIC");
            existing.setQuota(1000);

            ApiConfigurationRequest.ToolPaidConfigDto config =
                    new ApiConfigurationRequest.ToolPaidConfigDto(null, 2000, null, null, null, null, null);

            when(monetizationRepository.findByApiToolId(toolId)).thenReturn(List.of(existing));

            // Act
            service.updateToolPaidConfig(toolId, config);

            // Assert
            ArgumentCaptor<ApiToolMonetizationEntity> captor = ArgumentCaptor.forClass(ApiToolMonetizationEntity.class);
            verify(monetizationRepository).save(captor.capture());

            ApiToolMonetizationEntity saved = captor.getValue();
            assertEquals(2000, saved.getQuota());
            assertEquals("BASIC", saved.getPlanName()); // Unchanged
        }
    }

    // ========================================================================
    // batchUpdateToolsPaidConfig tests
    // ========================================================================

    @Nested
    @DisplayName("batchUpdateToolsPaidConfig()")
    class BatchUpdateToolsPaidConfigTests {

        @Test
        @DisplayName("should update multiple tools")
        void shouldUpdateMultipleTools() {
            // Arrange
            UUID toolId1 = UUID.randomUUID();
            UUID toolId2 = UUID.randomUUID();

            ApiConfigurationRequest.ToolPaidConfigDto config =
                    new ApiConfigurationRequest.ToolPaidConfigDto("PRO", 5000, null, null, null, null, null);

            Map<UUID, ApiConfigurationRequest.ToolPaidConfigDto> toolsConfig = new HashMap<>();
            toolsConfig.put(toolId1, config);
            toolsConfig.put(toolId2, config);

            when(monetizationRepository.findByApiToolId(any())).thenReturn(Collections.emptyList());

            // Act
            service.batchUpdateToolsPaidConfig(toolsConfig);

            // Assert
            verify(monetizationRepository, times(2)).save(any(ApiToolMonetizationEntity.class));
        }

        @Test
        @DisplayName("should delete PAID when all config values are null")
        void shouldDeletePaidWhenAllConfigValuesAreNull() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            ApiToolMonetizationEntity existing = new ApiToolMonetizationEntity();
            existing.setId(UUID.randomUUID());
            existing.setApiToolId(toolId);
            existing.setMonetizationType("PAID");

            ApiConfigurationRequest.ToolPaidConfigDto config =
                    new ApiConfigurationRequest.ToolPaidConfigDto(null, null, null, null, null, null, null);

            Map<UUID, ApiConfigurationRequest.ToolPaidConfigDto> toolsConfig = Map.of(toolId, config);

            when(monetizationRepository.findByApiToolId(toolId)).thenReturn(List.of(existing));

            // Act
            service.batchUpdateToolsPaidConfig(toolsConfig);

            // Assert
            verify(monetizationRepository).delete(existing);
            verify(monetizationRepository, never()).save(any(ApiToolMonetizationEntity.class));
        }

        @Test
        @DisplayName("should continue on error for individual tool")
        void shouldContinueOnErrorForIndividualTool() {
            // Arrange
            UUID toolId1 = UUID.randomUUID();
            UUID toolId2 = UUID.randomUUID();

            ApiConfigurationRequest.ToolPaidConfigDto config =
                    new ApiConfigurationRequest.ToolPaidConfigDto("PRO", 5000, null, null, null, null, null);

            Map<UUID, ApiConfigurationRequest.ToolPaidConfigDto> toolsConfig = new LinkedHashMap<>();
            toolsConfig.put(toolId1, config);
            toolsConfig.put(toolId2, config);

            when(monetizationRepository.findByApiToolId(toolId1)).thenThrow(new RuntimeException("DB error"));
            when(monetizationRepository.findByApiToolId(toolId2)).thenReturn(Collections.emptyList());

            // Act
            service.batchUpdateToolsPaidConfig(toolsConfig);

            // Assert - second tool should still be saved
            verify(monetizationRepository).save(any(ApiToolMonetizationEntity.class));
        }
    }

    // ========================================================================
    // updatePaidPlans tests
    // ========================================================================

    @Nested
    @DisplayName("updatePaidPlans()")
    class UpdatePaidPlansTests {

        @Test
        @DisplayName("should create plan configuration when selected")
        void shouldCreatePlanConfigurationWhenSelected() {
            // Arrange
            ApiToolEntity tool = createTestTool();
            String toolName = "test_tool";

            Map<String, Boolean> selectedPlans = new HashMap<>();
            selectedPlans.put("selectedBasic", true);

            Map<String, List<String>> planTools = new HashMap<>();
            planTools.put("selectedBasic", List.of(toolName));

            ApiConfigurationRequest.PaidPlansUpdateRequest request =
                    new ApiConfigurationRequest.PaidPlansUpdateRequest(selectedPlans, planTools);

            when(monetizationRepository.findByApiToolIdAndMonetizationType(tool.getId(), "PAID"))
                    .thenReturn(Collections.emptyList());

            Function<ApiToolEntity, String> nameProvider = t -> toolName;

            // Act
            service.updatePaidPlans(List.of(tool), request, nameProvider);

            // Assert
            ArgumentCaptor<ApiToolMonetizationEntity> captor = ArgumentCaptor.forClass(ApiToolMonetizationEntity.class);
            verify(monetizationRepository).save(captor.capture());

            ApiToolMonetizationEntity saved = captor.getValue();
            assertEquals("PAID", saved.getMonetizationType());
            assertEquals("BASIC", saved.getPlanName());
        }

        @Test
        @DisplayName("should remove plan when deselected")
        void shouldRemovePlanWhenDeselected() {
            // Arrange
            ApiToolEntity tool = createTestTool();
            String toolName = "test_tool";

            ApiToolMonetizationEntity existingBasic = new ApiToolMonetizationEntity();
            existingBasic.setId(UUID.randomUUID());
            existingBasic.setApiToolId(tool.getId());
            existingBasic.setMonetizationType("PAID");
            existingBasic.setPlanName("BASIC");

            Map<String, Boolean> selectedPlans = new HashMap<>();
            selectedPlans.put("selectedBasic", false);

            Map<String, List<String>> planTools = new HashMap<>();

            ApiConfigurationRequest.PaidPlansUpdateRequest request =
                    new ApiConfigurationRequest.PaidPlansUpdateRequest(selectedPlans, planTools);

            when(monetizationRepository.findByApiToolIdAndMonetizationType(tool.getId(), "PAID"))
                    .thenReturn(List.of(existingBasic));

            Function<ApiToolEntity, String> nameProvider = t -> toolName;

            // Act
            service.updatePaidPlans(List.of(tool), request, nameProvider);

            // Assert
            verify(monetizationRepository).delete(existingBasic);
        }

        @Test
        @DisplayName("should use fallback plan key format")
        void shouldUseFallbackPlanKeyFormat() {
            // Arrange
            ApiToolEntity tool = createTestTool();
            String toolName = "test_tool";

            Map<String, Boolean> selectedPlans = new HashMap<>();
            selectedPlans.put("pro", true); // Using simple format instead of "selectedPro"

            Map<String, List<String>> planTools = new HashMap<>();
            planTools.put("pro", List.of(toolName));

            ApiConfigurationRequest.PaidPlansUpdateRequest request =
                    new ApiConfigurationRequest.PaidPlansUpdateRequest(selectedPlans, planTools);

            when(monetizationRepository.findByApiToolIdAndMonetizationType(tool.getId(), "PAID"))
                    .thenReturn(Collections.emptyList());

            Function<ApiToolEntity, String> nameProvider = t -> toolName;

            // Act
            service.updatePaidPlans(List.of(tool), request, nameProvider);

            // Assert
            ArgumentCaptor<ApiToolMonetizationEntity> captor = ArgumentCaptor.forClass(ApiToolMonetizationEntity.class);
            verify(monetizationRepository).save(captor.capture());

            assertEquals("PRO", captor.getValue().getPlanName());
        }
    }

    // ========================================================================
    // convertMonetizationToMapList tests
    // ========================================================================

    @Nested
    @DisplayName("convertMonetizationToMapList()")
    class ConvertMonetizationToMapListTests {

        @Test
        @DisplayName("should convert monetization list to map format")
        void shouldConvertMonetizationListToMapFormat() {
            // Arrange
            UUID id = UUID.randomUUID();
            UUID toolId = UUID.randomUUID();
            Long createdAt = System.currentTimeMillis();
            Long updatedAt = System.currentTimeMillis();

            MonetizationResponse monetization = new MonetizationResponse(
                    id, toolId, "FREEMIUM", null,
                    100, "hour", 1000, "per-user",
                    1, BigDecimal.ZERO, 1, null,
                    null, null, null,
                    createdAt, updatedAt
            );

            // Act
            List<Map<String, Object>> result = service.convertMonetizationToMapList(List.of(monetization));

            // Assert
            assertEquals(1, result.size());
            Map<String, Object> map = result.get(0);
            assertEquals(id.toString(), map.get("id"));
            assertEquals(toolId.toString(), map.get("apiToolId"));
            assertEquals("FREEMIUM", map.get("monetizationType"));
            assertEquals(100, map.get("rateLimitRequests"));
            assertEquals("hour", map.get("rateLimitPeriod"));
        }

        @Test
        @DisplayName("should return empty list for null input")
        void shouldReturnEmptyListForNullInput() {
            // Act
            List<Map<String, Object>> result = service.convertMonetizationToMapList(null);

            // Assert
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty list for empty input")
        void shouldReturnEmptyListForEmptyInput() {
            // Act
            List<Map<String, Object>> result = service.convertMonetizationToMapList(Collections.emptyList());

            // Assert
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should convert multiple monetizations")
        void shouldConvertMultipleMonetizations() {
            // Arrange
            MonetizationResponse freemium = new MonetizationResponse(
                    UUID.randomUUID(), UUID.randomUUID(), "FREEMIUM", null,
                    100, "hour", 1000, "per-user",
                    1, BigDecimal.ZERO, 1, null,
                    null, null, null,
                    System.currentTimeMillis(), System.currentTimeMillis()
            );

            MonetizationResponse paid = new MonetizationResponse(
                    UUID.randomUUID(), UUID.randomUUID(), "PAID", "PRO",
                    500, "minute", null, null,
                    null, null, null, 10000,
                    BigDecimal.valueOf(9.99), BigDecimal.valueOf(0.01), true,
                    System.currentTimeMillis(), System.currentTimeMillis()
            );

            // Act
            List<Map<String, Object>> result = service.convertMonetizationToMapList(List.of(freemium, paid));

            // Assert
            assertEquals(2, result.size());
            assertEquals("FREEMIUM", result.get(0).get("monetizationType"));
            assertEquals("PAID", result.get(1).get("monetizationType"));
            assertEquals("PRO", result.get(1).get("planName"));
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private ApiToolEntity createTestTool() {
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(UUID.randomUUID());
        tool.setToolNameId("test_tool");
        tool.setDescription("Test tool description");
        return tool;
    }
}
