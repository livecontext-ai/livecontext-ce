package com.apimarketplace.catalog.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * DTO for creating an API
 */
public record ApiCreateRequest(
    @NotBlank(message = "API name is required")
    @Size(max = 100, message = "API name must not exceed 100 characters")
    String apiName,
    
    @Size(max = 50000, message = "Description must not exceed 50000 characters")
    String description,
    
    @NotBlank(message = "Base URL is required")
    @Size(max = 255, message = "Base URL must not exceed 255 characters")
    String baseUrl,
    
    @NotNull(message = "Category ID is required")
    UUID categoryId,
    
    @NotNull(message = "Subcategory ID is required")
    UUID subcategoryId,
    
    List<ToolCreateRequest> tools
) {
    
    /**
     * DTO for creating a tool within an API
     */
    public record ToolCreateRequest(
        @NotBlank(message = "Tool name is required")
        @Size(max = 100, message = "Tool name must not exceed 100 characters")
        String name,
        
        @NotBlank(message = "Tool description is required")
        @Size(max = 250, message = "Tool description must not exceed 250 characters")
        String description,
        
        @NotBlank(message = "Endpoint is required")
        @Size(max = 255, message = "Endpoint must not exceed 255 characters")
        String endpoint,
        
        @NotBlank(message = "Method is required")
        @Size(max = 10, message = "Method must not exceed 10 characters")
        String method,
        
        List<ParameterCreateRequest> parameters
    ) {}
    
    /**
     * DTO for creating a tool parameter
     */
    public record ParameterCreateRequest(
        @NotBlank(message = "Parameter name is required")
        @Size(max = 50, message = "Parameter name must not exceed 50 characters")
        String name,
        
        @NotBlank(message = "Parameter type is required")
        @Size(max = 20, message = "Parameter type must not exceed 20 characters")
        String type,
        
        @Size(max = 200, message = "Parameter description must not exceed 200 characters")
        String description,
        
        @NotNull(message = "Required flag is required")
        Boolean required,
        
        @Size(max = 100, message = "Default value must not exceed 100 characters")
        String defaultValue
    ) {}
}
