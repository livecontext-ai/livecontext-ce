package com.apimarketplace.catalog.domain.dto;

import jakarta.validation.constraints.Size;

/**
 * DTO for updating API basic information
 * Note: apiName is not modifiable via this endpoint
 */
public record ApiBasicInfoUpdateRequest(
    @Size(max = 50000, message = "Description must not exceed 50000 characters")
    String description,
    
    @Size(max = 100, message = "Category must not exceed 100 characters")
    String category,
    
    @Size(max = 100, message = "Subcategory must not exceed 100 characters")
    String subcategory
) {}
