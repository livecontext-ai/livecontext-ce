package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest;
import com.apimarketplace.catalog.domain.dto.ApiCreateRequest;
import com.apimarketplace.catalog.domain.dto.ApiResponse;
import com.apimarketplace.catalog.domain.dto.ApiBasicInfoUpdateRequest;
import com.apimarketplace.catalog.domain.dto.ApiConfigUpdateRequest;
import com.apimarketplace.catalog.dto.ToolUpdateDto;
import com.apimarketplace.catalog.service.ApiService;
import com.apimarketplace.catalog.service.AuthorizationService;
import com.apimarketplace.catalog.util.SlugUtils;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST Controller for API management and configuration
 */
@RestController
@RequestMapping("/api/apis")
@RequiredArgsConstructor
@Slf4j
public class ApiController {

    private final ApiService apiService;
    private final AuthorizationService authorizationService;
    
    /**
     * Process complete API configuration from frontend (DTO version)
     */
    @PostMapping("/configuration/process")
    public ResponseEntity<Map<String, Object>> processApiConfiguration(
            @Valid @RequestBody ApiConfigurationRequest request,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            // Extract user ID from either X-User-Id header or Authorization header
            String finalUserId = userId;
            if (finalUserId == null && authHeader != null && authHeader.startsWith("Bearer ")) {
                // For now, use a default user ID when Bearer token is present
                // In production, you would decode and validate the JWT token
                finalUserId = "authenticated-user";
            }

            ApiResponse response = apiService.processApiConfiguration(request, finalUserId);
            
            // Wrapper la reponse pour inclure success et data
            Map<String, Object> wrappedResponse = new HashMap<>();
            wrappedResponse.put("success", true);
            wrappedResponse.put("data", response);
            wrappedResponse.put("message", "API configuration processed successfully");
            
            return ResponseEntity.status(HttpStatus.CREATED).body(wrappedResponse);
        } catch (Exception e) {
            // Check if it's a unique constraint error
            String errorMessage = e.getMessage();
            if (errorMessage != null && errorMessage.contains("An API with this name already exists")) {
                errorMessage = "An API with this name already exists for this developer. Please choose a different name.";
            } else {
                errorMessage = "Error processing request: " + e.getMessage();
            }
            
            // Wrapper la reponse d'erreur pour inclure success et error
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", errorMessage);
            errorResponse.put("data", null);
            
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        }
    }
    
    /**
     * Get all APIs (for admin/debugging only)
     */
    @GetMapping
    public ResponseEntity<List<ApiResponse>> getAllApis(
            @RequestParam(value = "createdBy", required = false) String createdBy) {
        try {
            List<ApiResponse> responses;
            if (createdBy != null && !createdBy.isBlank()) {
                responses = apiService.getApisByCreator(createdBy);
            } else {
                responses = apiService.getAllApis();
            }
            return ResponseEntity.ok(responses);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get all APIs with complete aggregated information (tools, parameters, monetization, responses, credentials)
     * Direct endpoint for system view - bypasses gateway
     * Supports pagination with page and size parameters
     * Supports filtering by API name with name parameter
     */
    @GetMapping("/system/all")
    public ResponseEntity<Map<String, Object>> getAllApisWithCompleteInfo(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "name", required = false) String nameFilter) {
        try {
            log.info("Fetching all APIs with complete aggregated information - page: {}, size: {}, nameFilter: {}", 
                    page, size, nameFilter);
            List<ApiResponse> allApis = apiService.getAllApis(nameFilter);
            
            // Calculate pagination
            int total = allApis.size();
            int totalPages = (int) Math.ceil((double) total / size);
            int start = page * size;
            int end = Math.min(start + size, total);
            
            // Get paginated APIs
            List<ApiResponse> paginatedApis = allApis.subList(start, end);
            
            List<Map<String, Object>> result = new ArrayList<>();
            
            for (ApiResponse api : paginatedApis) {
                Map<String, Object> apiMap = new HashMap<>();
                apiMap.put("id", api.id().toString());
                apiMap.put("apiName", api.apiName());
                apiMap.put("apiSlug", api.apiSlug());
                apiMap.put("description", api.description());
                apiMap.put("baseUrl", api.baseUrl());
                apiMap.put("healthcheckEndpoint", api.healthcheckEndpoint());
                apiMap.put("categoryId", api.categoryId().toString());
                apiMap.put("categoryName", api.categoryName());
                apiMap.put("subcategoryId", api.subcategoryId().toString());
                apiMap.put("subcategoryName", api.subcategoryName());
                apiMap.put("visibility", api.visibility());
                apiMap.put("pricingModel", api.pricingModel());
                apiMap.put("authType", api.authType());
                apiMap.put("authHeaderName", api.authHeaderName());
                apiMap.put("status", api.status());
                apiMap.put("isPublic", api.isPublic());
                apiMap.put("isActive", api.isActive());
                apiMap.put("isLocal", api.isLocal());
                apiMap.put("createdBy", api.createdBy());
                apiMap.put("createdAt", api.createdAt());
                apiMap.put("updatedAt", api.updatedAt());
                
                // Get tools with all their details
                List<Map<String, Object>> toolsList = new ArrayList<>();
                if (api.tools() != null) {
                    for (ApiResponse.ToolResponse tool : api.tools()) {
                        Map<String, Object> toolMap = new HashMap<>();
                        toolMap.put("id", tool.id().toString());
                        toolMap.put("name", tool.name());
                        toolMap.put("description", tool.description());
                        toolMap.put("endpoint", tool.endpoint());
                        toolMap.put("method", tool.method());
                        toolMap.put("protocol", tool.protocol());
                        toolMap.put("status", tool.status());
                        toolMap.put("isActive", tool.isActive());
                        toolMap.put("createdAt", tool.createdAt());
                        toolMap.put("updatedAt", tool.updatedAt());
                        toolMap.put("runtimeMetadata", tool.runtimeMetadata());
                        toolMap.put("sqlConfig", tool.sqlConfig());
                        toolMap.put("amqpConfig", tool.amqpConfig());
                        toolMap.put("kafkaConfig", tool.kafkaConfig());
                        toolMap.put("mqttConfig", tool.mqttConfig());
                        toolMap.put("redisConfig", tool.redisConfig());
                        toolMap.put("toolCategories", tool.toolCategories());
                        
                        // Parameters
                        toolMap.put("parameters", tool.parameters());
                        
                        // Monetization
                        toolMap.put("monetization", tool.monetization());
                        
                        // Get tool responses
                        List<Map<String, Object>> responses = apiService.getToolResponses(tool.id());
                        toolMap.put("responses", responses);
                        
                        // Get tool credentials
                        List<Map<String, Object>> credentials = apiService.getToolCredentials(tool.id());
                        toolMap.put("credentials", credentials);
                        
                        toolsList.add(toolMap);
                    }
                }
                apiMap.put("tools", toolsList);
                
                result.add(apiMap);
            }
            
            // Build pagination response
            Map<String, Object> response = new HashMap<>();
            response.put("content", result);
            response.put("totalElements", total);
            response.put("totalPages", totalPages);
            response.put("page", page);
            response.put("size", size);
            response.put("first", page == 0);
            response.put("last", page >= totalPages - 1);
            response.put("numberOfElements", result.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error fetching all APIs with complete info: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get current user's APIs (similar to /billing/me)
     */
    @GetMapping("/me")
    public ResponseEntity<List<ApiResponse>> getMyApis(HttpServletRequest request) {
        try {
            // Extract user ID from gateway headers (same as /billing/me)
            String userIdHeader = request.getHeader("X-User-ID");
            if (userIdHeader == null) {
                return ResponseEntity.status(401).body(List.of());
            }

            List<ApiResponse> responses = apiService.getApisByCreator(userIdHeader);
            return ResponseEntity.ok(responses);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Check API name uniqueness for developer
     */
    @GetMapping("/check-name")
    public ResponseEntity<Map<String, Object>> checkApiNameUniquenes(
            @RequestParam String name,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            // Verifier l'unicite du nom
            boolean nameIsUnique;
            if (userId != null) {
                nameIsUnique = apiService.isApiNameUniqueForUser(name, userId);
            } else {
                nameIsUnique = apiService.isApiNameUniqueGlobally(name);
            }

            // Generer et verifier l'unicite du slug
            String generatedSlug = SlugUtils.generateSlug(name);
            boolean slugIsUnique = apiService.isApiSlugUnique(generatedSlug);

            Map<String, Object> response = new HashMap<>();
            boolean overallIsUnique = nameIsUnique && slugIsUnique;

            response.put("isUnique", overallIsUnique);
            response.put("nameIsUnique", nameIsUnique);
            response.put("slugIsUnique", slugIsUnique);
            response.put("generatedSlug", generatedSlug);

            if (!overallIsUnique) {
                List<String> conflicts = new ArrayList<>();
                if (!nameIsUnique) {
                    conflicts.add("API name already exists");
                }
                if (!slugIsUnique) {
                    conflicts.add("Generated slug '" + generatedSlug + "' already exists");
                }
                response.put("conflicts", conflicts);
                response.put("message", "Conflicts detected: " + String.join(", ", conflicts));
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("isUnique", true); // In case of error, consider as unique
            response.put("nameIsUnique", true);
            response.put("slugIsUnique", true);
            return ResponseEntity.ok(response);
        }
    }
    
    /**
     * Update API
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse> updateApi(@PathVariable UUID id,
                                               @Valid @RequestBody ApiCreateRequest request,
                                               @RequestHeader("X-User-ID") String userId) {
        // Verify user owns this API before allowing modification
        authorizationService.verifyApiOwnership(userId, id);

        try {
            ApiResponse response = apiService.updateApi(id, request, userId);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            if (e.getMessage().contains("not found")) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }
    
    /**
     * Update API basic information (for auto-save)
     */
    @PutMapping("/{id}/basic-info")
    public ResponseEntity<ApiResponse> updateApiBasicInfo(@PathVariable UUID id,
                                                         @Valid @RequestBody ApiBasicInfoUpdateRequest request,
                                                         @RequestHeader("X-User-ID") String userId) {
        // Verify user owns this API before allowing modification
        authorizationService.verifyApiOwnership(userId, id);

        try {
            ApiResponse response = apiService.updateApiBasicInfo(id, request, userId);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            if (e.getMessage().contains("not found")) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }
    
    /**
     * Update API configuration (for auto-save)
     */
    @PutMapping("/{id}/config")
    public ResponseEntity<ApiResponse> updateApiConfig(@PathVariable UUID id,
                                                      @Valid @RequestBody ApiConfigUpdateRequest request,
                                                      @RequestHeader("X-User-ID") String userId) {
        // Verify user owns this API before allowing modification
        authorizationService.verifyApiOwnership(userId, id);

        try {
            ApiResponse response = apiService.updateApiConfig(id, request, userId);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            if (e.getMessage().contains("not found")) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }
    
    /**
     * Update tool
     */
    @PutMapping("/{apiId}/tools/{toolId}")
    public ResponseEntity<ApiResponse> updateTool(@PathVariable UUID apiId,
                                                 @PathVariable UUID toolId,
                                                 @RequestBody ToolUpdateDto toolUpdateDto,
                                                 @RequestHeader("X-User-ID") String userId) {
        // Verify user owns this API before allowing tool modification
        authorizationService.verifyApiOwnership(userId, apiId);

        try {
            ApiResponse response = apiService.updateTool(apiId, toolId, toolUpdateDto, userId);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            if (e.getMessage().contains("not found")) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }
    
    /**
     * Delete API
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteApi(@PathVariable UUID id,
                                         @RequestHeader("X-User-ID") String userId) {
        // Verify user owns this API before allowing deletion
        authorizationService.verifyApiOwnership(userId, id);

        try {
            apiService.deleteApi(id);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            if (e.getMessage().contains("not found")) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Update pricing models for an API
     */
    @PutMapping("/{id}/pricing-models")
    public ResponseEntity<ApiResponse> updatePricingModels(@PathVariable UUID id,
                                                          @Valid @RequestBody ApiConfigurationRequest.PricingModelsUpdateRequest request,
                                                          @RequestHeader(value = "X-User-ID", required = false) String userId,
                                                          @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            // Extract user ID from either X-User-Id header or Authorization header
            String finalUserId = userId;
            if (finalUserId == null && authHeader != null && authHeader.startsWith("Bearer ")) {
                // For now, use a default user ID when Bearer token is present
                // In production, you would decode and validate the JWT token
                finalUserId = "authenticated-user";
            }

            ApiResponse response = apiService.updatePricingModels(id, request, finalUserId);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            if (e.getMessage().contains("not found")) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(createErrorResponse("Error updating pricing models: " + e.getMessage()));
        }
    }
    
    /**
     * Update PAID plans selection for an API
     */
    @PutMapping("/{id}/paid-plans")
    public ResponseEntity<ApiResponse> updatePaidPlans(@PathVariable UUID id,
                                                      @Valid @RequestBody ApiConfigurationRequest.PaidPlansUpdateRequest request,
                                                      @RequestHeader(value = "X-User-ID", required = false) String userId,
                                                      @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            // Extract user ID from either X-User-Id header or Authorization header
            String finalUserId = userId;
            if (finalUserId == null && authHeader != null && authHeader.startsWith("Bearer ")) {
                finalUserId = "authenticated-user";
            }

            ApiResponse response = apiService.updatePaidPlans(id, request, finalUserId);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            if (e.getMessage().contains("not found")) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(createErrorResponse("Error updating PAID plans: " + e.getMessage()));
        }
    }
    
    /**
     * Create error response
     */
    private ApiResponse createErrorResponse(String message) {
        return new ApiResponse(
            null, // id
            null, // apiName
            null, // apiSlug
            message, // description (using as error message)
            null, // baseUrl
            null, // categoryId
            null, // categoryName
            null, // subcategoryId
            null, // subcategoryName
            false, // isActive
            false, // isLocal
            null, // createdAt
            null, // updatedAt
            null, // createdBy
            null, // tools
            null, // healthcheckEndpoint
            null, // visibility
            false, // isPublic
            null, // authType
            null, // authHeaderName
            null, // authHeaderValue
            null, // pricingModel
            "error", // status
            null // platformCredentialMissing
        );
    }
    
    /**
     * Get monetization state for all tools by user
     */
    @GetMapping("/monetization/state")
    public ResponseEntity<Map<String, Object>> getMonetizationState(HttpServletRequest request) {
        try {
            // Extract user ID from gateway headers
            String userIdHeader = request.getHeader("X-User-ID");
            if (userIdHeader == null) {
                return ResponseEntity.status(401).body(Map.of("error", "Utilisateur non authentifie"));
            }

            Map<String, Object> response = apiService.getMonetizationStateByUser(userIdHeader);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Erreur lors de la recuperation de l'etat de monetisation: " + e.getMessage()));
        }
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> healthCheck() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "api-management"));
    }
    
    /**
     * Handle validation errors
     */
    /**
     * Update FREEMIUM configuration for a specific tool by api_tool_id
     */
    @PutMapping("/{apiId}/tools/{apiToolId}/freemium-config")
    public ResponseEntity<ApiResponse> updateToolFreemiumConfig(
            @PathVariable UUID apiId,
            @PathVariable UUID apiToolId,
            @Valid @RequestBody ApiConfigurationRequest.ToolFreemiumConfigUpdateRequest request,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        
        try {
            log.info("Updating FREEMIUM config for tool {} in API {}", apiToolId, apiId);
            
            ApiResponse response = apiService.updateToolFreemiumConfig(apiId, apiToolId, request, userId);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error updating FREEMIUM config for tool {} in API {}: {}", apiToolId, apiId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Error updating FREEMIUM configuration: " + e.getMessage()));
        }
    }

    /**
     * Batch update FREEMIUM configuration for multiple tools by api_tool_id
     */
    @PutMapping("/{apiId}/tools/freemium-config/batch")
    public ResponseEntity<ApiResponse> batchUpdateToolsFreemiumConfig(
            @PathVariable UUID apiId,
            @Valid @RequestBody ApiConfigurationRequest.BatchToolFreemiumConfigUpdateRequest request,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        
        try {
            log.info("Batch updating FREEMIUM config for {} tools in API {}", request.toolsConfig().size(), apiId);
            
            ApiResponse response = apiService.batchUpdateToolsFreemiumConfig(apiId, request, userId);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error batch updating FREEMIUM config for API {}: {}", apiId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Error batch updating FREEMIUM configuration: " + e.getMessage()));
        }
    }

    /**
     * Update PAID configuration for a specific tool by api_tool_id
     */
    @PutMapping("/{apiId}/tools/{apiToolId}/paid-config")
    public ResponseEntity<ApiResponse> updateToolPaidConfig(
            @PathVariable UUID apiId,
            @PathVariable UUID apiToolId,
            @Valid @RequestBody ApiConfigurationRequest.ToolPaidConfigUpdateRequest request,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        
        try {
            log.info("Updating PAID config for tool {} in API {}", apiToolId, apiId);
            
            ApiResponse response = apiService.updateToolPaidConfig(apiId, apiToolId, request, userId);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error updating PAID config for tool {} in API {}: {}", apiToolId, apiId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Error updating PAID configuration: " + e.getMessage()));
        }
    }

    /**
     * Batch update PAID configuration for multiple tools by api_tool_id
     */
    @PutMapping("/{apiId}/tools/paid-config/batch")
    public ResponseEntity<ApiResponse> batchUpdateToolsPaidConfig(
            @PathVariable UUID apiId,
            @Valid @RequestBody ApiConfigurationRequest.BatchToolPaidConfigUpdateRequest request,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        
        try {
            log.info("Batch updating PAID config for {} tools in API {}", request.toolsConfig().size(), apiId);
            
            ApiResponse response = apiService.batchUpdateToolsPaidConfig(apiId, request, userId);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error batch updating PAID config for API {}: {}", apiId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Error batch updating PAID configuration: " + e.getMessage()));
        }
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new java.util.HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
    }
}
