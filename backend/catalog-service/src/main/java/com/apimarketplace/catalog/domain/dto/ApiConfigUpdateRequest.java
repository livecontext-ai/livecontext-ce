package com.apimarketplace.catalog.domain.dto;

import jakarta.validation.constraints.Size;

/**
 * DTO for updating API configuration
 */
public record ApiConfigUpdateRequest(
    @Size(max = 255, message = "Base URL must not exceed 255 characters")
    String baseUrl,
    
    @Size(max = 255, message = "Health check endpoint must not exceed 255 characters")
    String healthcheckEndpoint,
    
    @Size(max = 50, message = "Visibility must not exceed 50 characters")
    String visibility,
    
    @Size(max = 50, message = "Auth type must not exceed 50 characters")
    String authType,
    
    @Size(max = 100, message = "Auth header name must not exceed 100 characters")
    String authHeaderName,
    
    @Size(max = 500, message = "Auth header value must not exceed 500 characters")
    String authHeaderValue
) {}
