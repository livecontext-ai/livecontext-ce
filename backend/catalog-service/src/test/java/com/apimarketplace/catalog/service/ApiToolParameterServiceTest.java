package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.dto.ApiResponse;
import com.apimarketplace.catalog.dto.ToolUpdateDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ApiToolParameterService.
 *
 * ApiToolParameterService manages CRUD operations for tool parameters.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApiToolParameterService")
class ApiToolParameterServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ApiToolParameterService parameterService;

    @BeforeEach
    void setUp() {
        parameterService = new ApiToolParameterService(jdbcTemplate);
    }

    // ========================================================================
    // getToolParameters tests
    // ========================================================================

    @Nested
    @DisplayName("getToolParameters")
    class GetToolParametersTests {

        @Test
        @DisplayName("should return parameters for tool")
        void shouldReturnParametersForTool() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            ApiResponse.ParameterResponse param = new ApiResponse.ParameterResponse(
                    UUID.randomUUID(), "userId", "string", "User ID", true, null, "123", "path", null
            );

            when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(toolId)))
                    .thenReturn(List.of(param));

            // Act
            List<ApiResponse.ParameterResponse> result = parameterService.getToolParameters(toolId);

            // Assert
            assertEquals(1, result.size());
            assertEquals("userId", result.get(0).name());
        }

        @Test
        @DisplayName("should return empty list when no parameters")
        void shouldReturnEmptyListWhenNoParameters() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(toolId)))
                    .thenReturn(Collections.emptyList());

            // Act
            List<ApiResponse.ParameterResponse> result = parameterService.getToolParameters(toolId);

            // Assert
            assertTrue(result.isEmpty());
        }
    }

    // ========================================================================
    // updateParametersFromDto tests
    // ========================================================================

    @Nested
    @DisplayName("updateParametersFromDto")
    class UpdateParametersFromDtoTests {

        @Test
        @DisplayName("should update path parameters from DTO")
        void shouldUpdatePathParametersFromDto() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            ToolUpdateDto dto = new ToolUpdateDto();
            dto.setPathParameters(List.of(createParamMap("id", "userId", "string")));

            when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), any(), any()))
                    .thenReturn(Collections.emptyList());

            // Act
            parameterService.updateParametersFromDto(toolId, dto);

            // Assert
            verify(jdbcTemplate).update(contains("INSERT"), any(Object[].class));
        }

        @Test
        @DisplayName("should update query parameters from DTO")
        void shouldUpdateQueryParametersFromDto() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            ToolUpdateDto dto = new ToolUpdateDto();
            dto.setQueryParameters(List.of(createParamMap("id", "limit", "integer")));

            when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), any(), any()))
                    .thenReturn(Collections.emptyList());

            // Act
            parameterService.updateParametersFromDto(toolId, dto);

            // Assert
            verify(jdbcTemplate).update(contains("INSERT"), any(Object[].class));
        }

        @Test
        @DisplayName("should update header parameters from DTO")
        void shouldUpdateHeaderParametersFromDto() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            ToolUpdateDto dto = new ToolUpdateDto();
            dto.setHeaders(List.of(createParamMap("id", "Authorization", "string")));

            when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), any(), any()))
                    .thenReturn(Collections.emptyList());

            // Act
            parameterService.updateParametersFromDto(toolId, dto);

            // Assert
            verify(jdbcTemplate).update(contains("INSERT"), any(Object[].class));
        }

        @Test
        @DisplayName("should skip null parameter lists in DTO")
        void shouldSkipNullParameterListsInDto() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            ToolUpdateDto dto = new ToolUpdateDto();
            // All parameter lists are null

            // Act
            parameterService.updateParametersFromDto(toolId, dto);

            // Assert
            verify(jdbcTemplate, never()).queryForList(anyString(), eq(UUID.class), any(), any());
        }
    }

    // ========================================================================
    // updateParametersByType tests
    // ========================================================================

    @Nested
    @DisplayName("updateParametersByType")
    class UpdateParametersByTypeTests {

        @Test
        @DisplayName("should insert new parameter with temp id")
        void shouldInsertNewParameterWithTempId() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            Map<String, Object> param = createParamMap("temp-123", "newParam", "string");

            when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), any(), any()))
                    .thenReturn(Collections.emptyList());

            // Act
            parameterService.updateParametersByType(toolId, List.of(param), "path");

            // Assert
            verify(jdbcTemplate).update(contains("INSERT"), any(Object[].class));
        }

        @Test
        @DisplayName("should update existing parameter")
        void shouldUpdateExistingParameter() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            UUID existingParamId = UUID.randomUUID();
            Map<String, Object> param = createParamMap(existingParamId.toString(), "updatedParam", "string");

            when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), any(), any()))
                    .thenReturn(List.of(existingParamId));

            // Act
            parameterService.updateParametersByType(toolId, List.of(param), "path");

            // Assert
            verify(jdbcTemplate).update(contains("UPDATE"), any(Object[].class));
        }

        @Test
        @DisplayName("should delete unused parameters")
        void shouldDeleteUnusedParameters() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            UUID existingParamId = UUID.randomUUID();

            when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), any(), any()))
                    .thenReturn(List.of(existingParamId));

            // Empty list means delete all existing
            List<Map<String, Object>> emptyParams = Collections.emptyList();

            // Act
            parameterService.updateParametersByType(toolId, emptyParams, "path");

            // Assert
            verify(jdbcTemplate).update(contains("DELETE"), eq(existingParamId));
        }

        @Test
        @DisplayName("should insert parameter with invalid UUID")
        void shouldInsertParameterWithInvalidUuid() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            Map<String, Object> param = createParamMap("not-a-uuid", "newParam", "string");

            when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), any(), any()))
                    .thenReturn(Collections.emptyList());

            // Act
            parameterService.updateParametersByType(toolId, List.of(param), "query");

            // Assert
            verify(jdbcTemplate).update(contains("INSERT"), any(Object[].class));
        }
    }

    // ========================================================================
    // deleteAllParameters tests
    // ========================================================================

    @Nested
    @DisplayName("deleteAllParameters")
    class DeleteAllParametersTests {

        @Test
        @DisplayName("should delete all parameters for tool")
        void shouldDeleteAllParametersForTool() {
            // Arrange
            UUID toolId = UUID.randomUUID();

            // Act
            parameterService.deleteAllParameters(toolId);

            // Assert
            verify(jdbcTemplate).update(contains("DELETE"), eq(toolId));
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private Map<String, Object> createParamMap(String id, String name, String type) {
        Map<String, Object> param = new HashMap<>();
        param.put("id", id);
        param.put("name", name);
        param.put("type", type);
        param.put("description", "Test description");
        param.put("required", true);
        param.put("example", "example-value");
        return param;
    }
}
