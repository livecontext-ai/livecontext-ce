package com.apimarketplace.catalog.mapping.web;

import com.apimarketplace.catalog.mapping.generator.MappingGenerationException;
import com.apimarketplace.catalog.mapping.generator.StrictMappingConstraints;
import com.apimarketplace.catalog.mapping.generator.DeepInfraStrictMappingGenerator;
import com.apimarketplace.catalog.mapping.service.MappingGeneratorService;
import com.apimarketplace.catalog.service.ToolContextService;
import com.apimarketplace.catalog.service.ToolResponseService;
import com.apimarketplace.catalog.dto.ToolResponseDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;


/**
 * REST controller for mapping generation endpoints.
 *
 * This controller provides HTTP endpoints for generating strict JSONPath mappings
 * from sample JSON data using AI models.
 */
@RestController
@RequestMapping("/api/mapping")
@ConditionalOnProperty(name = "ai.mapping.enabled", havingValue = "true")
public class MappingGeneratorController {
    
    private static final Logger logger = LoggerFactory.getLogger(MappingGeneratorController.class);
    
    private final MappingGeneratorService mappingGeneratorService;
    private final ObjectMapper objectMapper;
    private final ToolContextService toolContextService;
    private final DeepInfraStrictMappingGenerator deepInfraGenerator;
    
    @Autowired
    private ToolResponseService toolResponseService;
    
    @Autowired
    public MappingGeneratorController(MappingGeneratorService mappingGeneratorService, 
                                    ObjectMapper objectMapper,
                                    ToolContextService toolContextService,
                                    @Autowired(required = false) @Qualifier("deepInfraStrictMappingGenerator") DeepInfraStrictMappingGenerator deepInfraGenerator) {
        this.mappingGeneratorService = mappingGeneratorService;
        this.objectMapper = objectMapper;
        this.toolContextService = toolContextService;
        this.deepInfraGenerator = deepInfraGenerator;
    }
    
    /**
     * Generates a strict mapping specification from sample JSON data.
     * 
     * POST /api/mapping/generate
     * 
     * Request body:
     * {
     *   "sample": {...},
     *   "tool_id": "uuid-string",
     *   "constraints": {
     *     "items_path": "...",
     *     "max_fallbacks": 4,
     *     "modelProvider": "deepinfra"
     *   }
     * }
     * 
     * @param request The mapping generation request
     * @return The generated mapping specification
     */
    @PostMapping("/generate")
    public ResponseEntity<?> generateMapping(@RequestBody MappingGenerationRequest request) {
        try {
            logger.info("Received mapping generation request with tool_id: {}", request.getToolId());
            
            // Validate request
            if (request.getSample() == null || request.getSample().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("Sample JSON is required"));
            }
            
            String sampleData = request.getSample();
            
            // Load tool context if tool_id is provided
            ToolContextService.ToolContext toolContext = null;
            if (request.getToolId() != null && !request.getToolId().trim().isEmpty()) {
                var contextOpt = toolContextService.loadToolContext(request.getToolId());
                if (contextOpt.isPresent()) {
                    toolContext = contextOpt.get();
                    logger.info("Loaded tool context: name={}, category={}, method={}, endpoint={}", 
                               toolContext.getToolName(), toolContext.getToolCategoryName(),
                               toolContext.getHttpMethod(), toolContext.getEndpoint());
                } else {
                    logger.warn("Tool context not found for tool_id: {}", request.getToolId());
                    return ResponseEntity.badRequest()
                            .body(new ErrorResponse("Tool not found with ID: " + request.getToolId()));
                }
            }
            
            // Check file size
            long fileSizeBytes = sampleData.getBytes().length;
            long maxFileSize = 1048576; // 1MB default
            
            if (fileSizeBytes > maxFileSize) {
                logger.warn("Large file detected: {} bytes (max: {} bytes)", fileSizeBytes, maxFileSize);
                // Continue processing but log the warning
            }
            
            // Parse constraints
            StrictMappingConstraints constraints = parseConstraints(request.getConstraints());
            
            // Generate mapping with tool context
            String mapping;
            if (toolContext != null) {
                mapping = mappingGeneratorService.generateStrictMappingWithContext(
                    sampleData, 
                    constraints, 
                    toolContext.getToolName(),
                    toolContext.getToolCategoryName(),
                    toolContext.getToolSubCategoryName(),
                    toolContext.getHttpMethod(),
                    toolContext.getEndpoint(),
                    toolContext.getToolDescriptionFull()
                );
            } else {
                mapping = mappingGeneratorService.generateStrictMapping(sampleData, constraints);
            }
            
            // Parse and return as JSON object
            JsonNode mappingNode = objectMapper.readTree(mapping);
            
            return ResponseEntity.ok(mappingNode);
            
        } catch (MappingGenerationException e) {
            logger.error("Mapping generation failed: {}", e.getMessage());
            // Return the AI response directly in the error for debugging
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("AI Mapping generation failed: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected error during mapping generation: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Internal server error: " + e.getMessage()));
        }
    }
    
    /**
     * Generates a strict mapping specification from sample JSON data using tool_id.
     * 
     * POST /api/mapping/generate/{tool_id}
     * 
     * Request body:
     * {
     *   "sample": {...},
     *   "constraints": {
     *     "items_path": "...",
     *     "max_fallbacks": 4,
     *     "modelProvider": "deepinfra"
     *   }
     * }
     * 
     * @param toolId The tool ID from the URL path
     * @param request The mapping generation request
     * @return The generated mapping specification
     */
    @PostMapping("/generate/{tool_id}")
    public ResponseEntity<?> generateMappingWithToolId(@PathVariable("tool_id") String toolId, 
                                                      @RequestBody MappingGenerationRequest request) {
        try {
            logger.info("Received mapping generation request with tool_id: {}", toolId);
            
            // Load tool context using tool_id
            ToolContextService.ToolContext toolContext = null;
            String sampleData = null;
            
            if (toolId != null && !toolId.trim().isEmpty()) {
                var contextOpt = toolContextService.loadToolContext(toolId);
                if (contextOpt.isPresent()) {
                    toolContext = contextOpt.get();
                    logger.info("Loaded tool context: name={}, category={}, method={}, endpoint={}", 
                               toolContext.getToolName(), toolContext.getToolCategoryName(),
                               toolContext.getHttpMethod(), toolContext.getEndpoint());
                    
                    // Recuperer la reponse de l'outil pour generer le mapping
                    try {
                        // Here, you need to implement the logic to retrieve the actual tool response
                        // Par exemple, faire un appel a l'API de l'outil ou recuperer des donnees d'exemple
                        sampleData = getToolResponseData(toolId);
                        
                        if (sampleData == null || sampleData.trim().isEmpty()) {
                            return ResponseEntity.badRequest()
                                    .body(new ErrorResponse("No response data available for tool: " + toolId));
                        }
                        
                        // Validation JSON
                        try {
                            objectMapper.readTree(sampleData);
                            logger.info("JSON validation successful for tool: {}", toolId);
                        } catch (JsonProcessingException e) {
                            logger.error("Invalid JSON format for tool {}: {}", toolId, e.getMessage());
                            return ResponseEntity.badRequest()
                                    .body(new ErrorResponse("Invalid JSON format in tool response: " + e.getMessage()));
                        }
                        
                    } catch (Exception e) {
                        logger.error("Error retrieving tool response for {}: {}", toolId, e.getMessage());
                        return ResponseEntity.badRequest()
                                .body(new ErrorResponse("Failed to retrieve tool response: " + e.getMessage()));
                    }
                } else {
                    logger.warn("Tool context not found for tool_id: {}", toolId);
                    return ResponseEntity.badRequest()
                            .body(new ErrorResponse("Tool not found with ID: " + toolId));
                }
            } else {
                // Fallback: use the request sample if provided
                if (sampleData != null && !sampleData.trim().isEmpty()) {
                    sampleData = sampleData;
                    
                    // Validation JSON
                    try {
                        objectMapper.readTree(sampleData);
                        logger.info("JSON validation successful for provided sample");
                    } catch (JsonProcessingException e) {
                        logger.error("Invalid JSON format in provided sample: {}", e.getMessage());
                        return ResponseEntity.badRequest()
                                .body(new ErrorResponse("Invalid JSON format in provided sample: " + e.getMessage()));
                    }
                } else {
                    return ResponseEntity.badRequest()
                            .body(new ErrorResponse("Sample JSON is required"));
                }
            }
            
            // Check file size
            long fileSizeBytes = sampleData.getBytes().length;
            long maxFileSize = 1048576; // 1MB default
            
            if (fileSizeBytes > maxFileSize) {
                logger.warn("Large file detected: {} bytes (max: {} bytes)", fileSizeBytes, maxFileSize);
                // Continue processing but log the warning
            }
            
            // Parse constraints
            StrictMappingConstraints constraints = parseConstraints(request.getConstraints());
            
            // Generate mapping with tool context
            String mapping;
            if (toolContext != null) {
                mapping = mappingGeneratorService.generateStrictMappingWithContext(
                    sampleData, 
                    constraints, 
                    toolContext.getToolName(),
                    toolContext.getToolCategoryName(),
                    toolContext.getToolSubCategoryName(),
                    toolContext.getHttpMethod(),
                    toolContext.getEndpoint(),
                    toolContext.getToolDescriptionFull()
                );
            } else {
                mapping = mappingGeneratorService.generateStrictMapping(sampleData, constraints);
            }
            
            // Parse and return as JSON object
            JsonNode mappingNode = objectMapper.readTree(mapping);
            
            // Add tool_id to the response payload for frontend
            if (mappingNode.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) mappingNode)
                    .put("tool_id", toolId);
            }
            
            return ResponseEntity.ok(mappingNode);
            
        } catch (MappingGenerationException e) {
            logger.error("Mapping generation failed: {}", e.getMessage());
            // Return the AI response directly in the error for debugging
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("AI Mapping generation failed: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected error during mapping generation: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Internal server error: " + e.getMessage()));
        }
    }
    
    /**
     * Generates a mapping with default constraints.
     * 
     * POST /api/mapping/generate-simple
     * 
     * Request body:
     * {
     *   "sample": {...}
     * }
     * 
     * @param request The simple mapping generation request
     * @return The generated mapping specification
     */
    @PostMapping("/generate-simple")
    public ResponseEntity<?> generateSimpleMapping(@RequestBody SimpleMappingRequest request) {
        try {
            logger.info("Received simple mapping generation request");
            
            // Validate request
            if (request.getSample() == null || request.getSample().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("Sample JSON is required"));
            }
            
            String sampleData = request.getSample();
            
            // Generate mapping with default constraints
            String mapping = mappingGeneratorService.generateStrictMapping(sampleData);
            
            // Parse and return as JSON object
            JsonNode mappingNode = objectMapper.readTree(mapping);
            
            return ResponseEntity.ok(mappingNode);
            
        } catch (MappingGenerationException e) {
            logger.error("Simple mapping generation failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Mapping generation failed: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected error during simple mapping generation: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Internal server error: " + e.getMessage()));
        }
    }
    
    /**
     * Generates a strict mapping specification using external DeepInfra AI.
     * 
     * POST /api/mapping/generate-external/{tool_id}
     * 
     * Request body:
     * {
     *   "sample": {...},
     *   "constraints": {
     *     "items_path": "...",
     *     "max_fallbacks": 4
     *   }
     * }
     * 
     * @param toolId The tool ID from the URL path
     * @param request The mapping generation request
     * @return The generated mapping specification using DeepInfra
     */
    @PostMapping("/generate-external/{tool_id}")
    public ResponseEntity<?> generateMappingExternal(@PathVariable("tool_id") String toolId, 
                                                   @RequestBody MappingGenerationRequest request) {
        try {
            logger.info("Received external mapping generation request with tool_id: {} using DeepInfra", toolId);
            
            // Validate request
            if (request.getSample() == null || request.getSample().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("Sample JSON is required"));
            }
            
            String sampleData = request.getSample();
            
            // Check if DeepInfra generator is available
            if (deepInfraGenerator == null) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(new ErrorResponse("DeepInfra AI service is not configured. Please configure DeepInfra provider."));
            }
            
            if (!deepInfraGenerator.isAvailable()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(new ErrorResponse("DeepInfra AI service is not available. Please check your API token and configuration."));
            }
            
            // Load tool context using tool_id
            ToolContextService.ToolContext toolContext = null;
            if (toolId != null && !toolId.trim().isEmpty()) {
                var contextOpt = toolContextService.loadToolContext(toolId);
                if (contextOpt.isPresent()) {
                    toolContext = contextOpt.get();
                    logger.info("Loaded tool context for external generation: name={}, category={}, method={}, endpoint={}", 
                               toolContext.getToolName(), toolContext.getToolCategoryName(),
                               toolContext.getHttpMethod(), toolContext.getEndpoint());
                } else {
                    logger.warn("Tool context not found for tool_id: {}", toolId);
                    return ResponseEntity.badRequest()
                            .body(new ErrorResponse("Tool not found with ID: " + toolId));
                }
            }
            
            // Check file size
            long fileSizeBytes = sampleData.getBytes().length;
            long maxFileSize = 1048576; // 1MB default
            
            if (fileSizeBytes > maxFileSize) {
                logger.warn("Large file detected for external generation: {} bytes (max: {} bytes)", fileSizeBytes, maxFileSize);
                // Continue processing but log the warning
            }
            
            // Parse constraints
            StrictMappingConstraints constraints = parseConstraints(request.getConstraints());
            
            // Generate mapping using DeepInfra directly
            String mapping;
            if (toolContext != null) {
                mapping = deepInfraGenerator.generateStrictMappingWithContext(
                    sampleData, 
                    constraints, 
                    toolContext.getToolName(),
                    toolContext.getToolCategoryName(),
                    toolContext.getToolSubCategoryName(),
                    toolContext.getHttpMethod(),
                    toolContext.getEndpoint(),
                    toolContext.getToolDescriptionFull()
                );
            } else {
                mapping = deepInfraGenerator.generateStrictMapping(sampleData, constraints);
            }
            
            // Parse and return as JSON object
            JsonNode mappingNode = objectMapper.readTree(mapping);
            
            // Add metadata to the response
            if (mappingNode.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) mappingNode)
                    .put("tool_id", toolId)
                    .put("generator_type", "deepinfra")
                    .put("generated_at", System.currentTimeMillis());
            }
            
            logger.info("Successfully generated mapping using DeepInfra for tool_id: {}", toolId);
            return ResponseEntity.ok(mappingNode);
            
        } catch (MappingGenerationException e) {
            logger.error("External mapping generation failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("DeepInfra mapping generation failed: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected error during external mapping generation: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Internal server error: " + e.getMessage()));
        }
    }
    
    /**
     * Generates a simple mapping using external DeepInfra AI without tool context.
     * 
     * POST /api/mapping/generate-external-simple
     * 
     * Request body:
     * {
     *   "sample": {...}
     * }
     * 
     * @param request The simple mapping generation request
     * @return The generated mapping specification using DeepInfra
     */
    @PostMapping("/generate-external-simple")
    public ResponseEntity<?> generateSimpleMappingExternal(@RequestBody SimpleMappingRequest request) {
        try {
            logger.info("Received simple external mapping generation request using DeepInfra");
            
            // Validate request
            if (request.getSample() == null || request.getSample().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("Sample JSON is required"));
            }
            
            String sampleData = request.getSample();
            
            // Check if DeepInfra generator is available
            if (deepInfraGenerator == null) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(new ErrorResponse("DeepInfra AI service is not configured. Please configure DeepInfra provider."));
            }
            
            if (!deepInfraGenerator.isAvailable()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(new ErrorResponse("DeepInfra AI service is not available. Please check your API token and configuration."));
            }
            
            // Generate mapping using DeepInfra with default constraints
            StrictMappingConstraints defaultConstraints = new StrictMappingConstraints();
            String mapping = deepInfraGenerator.generateStrictMapping(sampleData, defaultConstraints);
            
            // Parse and return as JSON object
            JsonNode mappingNode = objectMapper.readTree(mapping);
            
            // Add metadata to the response
            if (mappingNode.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) mappingNode)
                    .put("generator_type", "deepinfra")
                    .put("generated_at", System.currentTimeMillis());
            }
            
            logger.info("Successfully generated simple mapping using DeepInfra");
            return ResponseEntity.ok(mappingNode);
            
        } catch (MappingGenerationException e) {
            logger.error("Simple external mapping generation failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("DeepInfra mapping generation failed: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected error during simple external mapping generation: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Internal server error: " + e.getMessage()));
        }
    }
    
    /**
     * Checks if the mapping generator is available.
     * 
     * GET /api/mapping/status
     * 
     * @return The generator availability status
     */
    @GetMapping("/status")
    public ResponseEntity<?> getGeneratorStatus() {
        try {
            boolean available = mappingGeneratorService.isGeneratorAvailable();
            return ResponseEntity.ok(new StatusResponse(available));
        } catch (Exception e) {
            logger.error("Failed to check generator status: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Failed to check generator status: " + e.getMessage()));
        }
    }
    
    /**
     * Checks if the external DeepInfra generator is available.
     * 
     * GET /api/mapping/status-external
     * 
     * @return The external generator availability status
     */
    @GetMapping("/status-external")
    public ResponseEntity<?> getExternalGeneratorStatus() {
        try {
            if (deepInfraGenerator == null) {
                return ResponseEntity.ok(new StatusResponse(false, "DeepInfra generator not configured"));
            }
            boolean available = deepInfraGenerator.isAvailable();
            return ResponseEntity.ok(new StatusResponse(available));
        } catch (Exception e) {
            logger.error("Failed to check external generator status: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Failed to check external generator status: " + e.getMessage()));
        }
    }
    
    /**
     * Validates file size and provides processing information.
     * 
     * POST /api/mapping/validate-file
     * 
     * @param request The file validation request
     * @return File validation information
     */
    @PostMapping("/validate-file")
    public ResponseEntity<?> validateFile(@RequestBody FileValidationRequest request) {
        try {
            if (request.getSample() == null || request.getSample().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("Sample JSON is required"));
            }
            
            String sampleData = request.getSample();
            long fileSizeBytes = sampleData.getBytes().length;
            long maxFileSize = 1048576; // 1MB default
            boolean isLargeFile = fileSizeBytes > maxFileSize;
            
            FileValidationResponse response = new FileValidationResponse();
            response.setFileSizeBytes(fileSizeBytes);
            response.setMaxFileSizeBytes(maxFileSize);
            response.setIsLargeFile(isLargeFile);
            response.setWillBeProcessed(true); // We can handle large files
            response.setProcessingMethod(isLargeFile ? "intelligent-sampling" : "full-processing");
            
            if (isLargeFile) {
                response.setMessage("Large file detected. Will use intelligent sampling to preserve structure.");
            } else {
                response.setMessage("File size is within limits. Full processing will be used.");
            }
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Failed to validate file: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Failed to validate file: " + e.getMessage()));
        }
    }
    
    /**
     * Parses constraints from the request.
     */
    private StrictMappingConstraints parseConstraints(JsonNode constraintsNode) {
        StrictMappingConstraints constraints = new StrictMappingConstraints();
        
        if (constraintsNode != null) {
            if (constraintsNode.has("items_path")) {
                constraints.setItemsPath(constraintsNode.get("items_path").asText());
            }
            if (constraintsNode.has("max_fallbacks")) {
                constraints.setMaxFallbacks(constraintsNode.get("max_fallbacks").asInt());
            }
            if (constraintsNode.has("prefer_relative")) {
                constraints.setPreferRelative(constraintsNode.get("prefer_relative").asBoolean());
            }
            if (constraintsNode.has("model_provider")) {
                constraints.setModelProvider(constraintsNode.get("model_provider").asText());
            }
            if (constraintsNode.has("model_name")) {
                constraints.setModelName(constraintsNode.get("model_name").asText());
            }
            if (constraintsNode.has("timeout_ms")) {
                constraints.setTimeoutMs(constraintsNode.get("timeout_ms").asInt());
            }
        }
        
        return constraints;
    }
    
    /**
     * Request DTO for mapping generation.
     */
    public static class MappingGenerationRequest {
        private String sample;
        private JsonNode constraints;
        private String toolId;
        
        public String getSample() {
            return sample;
        }
        
        public void setSample(String sample) {
            this.sample = sample;
        }
        
        public JsonNode getConstraints() {
            return constraints;
        }
        
        public void setConstraints(JsonNode constraints) {
            this.constraints = constraints;
        }
        
        public String getToolId() {
            return toolId;
        }
        
        public void setToolId(String toolId) {
            this.toolId = toolId;
        }
    }
    
    /**
     * Request DTO for simple mapping generation.
     */
    public static class SimpleMappingRequest {
        private String sample;
        
        public String getSample() {
            return sample;
        }
        
        public void setSample(String sample) {
            this.sample = sample;
        }
    }
    
    /**
     * Response DTO for errors.
     */
    public static class ErrorResponse {
        private String error;
        
        public ErrorResponse(String error) {
            this.error = error;
        }
        
        public String getError() {
            return error;
        }
        
        public void setError(String error) {
            this.error = error;
        }
    }
    
    /**
     * Response DTO for status.
     */
    public static class StatusResponse {
        private boolean available;
        private String message;
        
        public StatusResponse(boolean available) {
            this.available = available;
        }
        
        public StatusResponse(boolean available, String message) {
            this.available = available;
            this.message = message;
        }
        
        public boolean isAvailable() {
            return available;
        }
        
        public String getMessage() {
            return message;
        }
        
        public void setAvailable(boolean available) {
            this.available = available;
        }
    }
    
    /**
     * Request DTO for file validation.
     */
    public static class FileValidationRequest {
        private String sample;
        
        public String getSample() {
            return sample;
        }
        
        public void setSample(String sample) {
            this.sample = sample;
        }
    }
    
    /**
     * Response DTO for file validation.
     */
    public static class FileValidationResponse {
        private long fileSizeBytes;
        private long maxFileSizeBytes;
        private boolean isLargeFile;
        private boolean willBeProcessed;
        private String processingMethod;
        private String message;
        
        // Getters and Setters
        public long getFileSizeBytes() {
            return fileSizeBytes;
        }
        
        public void setFileSizeBytes(long fileSizeBytes) {
            this.fileSizeBytes = fileSizeBytes;
        }
        
        public long getMaxFileSizeBytes() {
            return maxFileSizeBytes;
        }
        
        public void setMaxFileSizeBytes(long maxFileSizeBytes) {
            this.maxFileSizeBytes = maxFileSizeBytes;
        }
        
        public boolean isIsLargeFile() {
            return isLargeFile;
        }
        
        public void setIsLargeFile(boolean isLargeFile) {
            this.isLargeFile = isLargeFile;
        }
        
        public boolean isWillBeProcessed() {
            return willBeProcessed;
        }
        
        public void setWillBeProcessed(boolean willBeProcessed) {
            this.willBeProcessed = willBeProcessed;
        }
        
        public String getProcessingMethod() {
            return processingMethod;
        }
        
        public void setProcessingMethod(String processingMethod) {
            this.processingMethod = processingMethod;
        }
        
        public String getMessage() {
            return message;
        }
        
        public void setMessage(String message) {
            this.message = message;
        }
    }
    
    /**
     * Gets the latest default mapping specification for a tool.
     * 
     * GET /api/mapping/latest/{tool_id}
     * 
     * @param toolId The tool ID
     * @return The latest mapping specification
     */
    @GetMapping("/latest/{tool_id}")
    public ResponseEntity<?> getLatestMappingSpec(@PathVariable("tool_id") String toolId) {
        try {
            logger.info("Getting latest mapping spec for tool_id: {}", toolId);
            
            // Load tool context using tool_id
            ToolContextService.ToolContext toolContext = null;
            if (toolId != null && !toolId.trim().isEmpty()) {
                try {
                    Optional<ToolContextService.ToolContext> toolContextOpt = toolContextService.loadToolContext(toolId);
                    if (toolContextOpt.isPresent()) {
                        toolContext = toolContextOpt.get();
                        logger.info("Loaded tool context for tool_id: {}, tool_name: {}", toolId, toolContext.getToolName());
                    } else {
                        logger.warn("No tool context found for tool_id: {}", toolId);
                    }
                } catch (Exception e) {
                    logger.warn("Could not load tool context for tool_id: {}, error: {}", toolId, e.getMessage());
                }
            }
            
            // Create a default mapping specification based on the tool context
            Map<String, Object> defaultMappingSpec = new HashMap<>();
            
            // Source configuration
            Map<String, Object> source = new HashMap<>();
            source.put("format", "json");
            source.put("items_path", "$.data[*]"); // Default path for most APIs
            source.put("root_alternatives", Arrays.asList(
                "$.data[*]",
                "$.results[*]",
                "$.items[*]",
                "$[*]"
            ));
            source.put("root_match", new HashMap<>());
            defaultMappingSpec.put("source", source);
            
            // Fields configuration - basic structure
            Map<String, Object> fields = new HashMap<>();
            
            // Add common fields based on tool context if available
            if (toolContext != null && toolContext.getToolName() != null) {
                String toolName = toolContext.getToolName().toLowerCase();
                
                // Instagram-specific fields
                if (toolName.contains("instagram") || toolName.contains("reel")) {
                    fields.put("story_id", createFieldConfig(Arrays.asList("@.pk", "@.id"), "string", true));
                    fields.put("media_id", createFieldConfig(Arrays.asList("@.id", "@.media_id"), "string", true));
                    fields.put("caption", createFieldConfig(Arrays.asList("@.caption.text", "@.caption"), "string", false));
                    fields.put("timestamp", createFieldConfig(Arrays.asList("@.taken_at", "@.timestamp"), "number", true));
                    fields.put("media_type", createFieldConfig(Arrays.asList("@.media_type", "@.type"), "number", true));
                    fields.put("image_url", createFieldConfig(Arrays.asList("@.image_versions2.candidates[0].url", "@.image_url"), "string", false));
                }
                // Twitter/X-specific fields
                else if (toolName.contains("twitter") || toolName.contains("tweet")) {
                    fields.put("tweet_id", createFieldConfig(Arrays.asList("@.id", "@.tweet_id"), "string", true));
                    fields.put("text", createFieldConfig(Arrays.asList("@.text", "@.content"), "string", true));
                    fields.put("author_id", createFieldConfig(Arrays.asList("@.author_id", "@.user.id"), "string", true));
                    fields.put("created_at", createFieldConfig(Arrays.asList("@.created_at", "@.timestamp"), "string", true));
                    fields.put("retweet_count", createFieldConfig(Arrays.asList("@.public_metrics.retweet_count", "@.retweet_count"), "number", false));
                }
                // Generic API fields
                else {
                    fields.put("id", createFieldConfig(Arrays.asList("@.id", "@.pk"), "string", true));
                    fields.put("title", createFieldConfig(Arrays.asList("@.title", "@.name", "@.label"), "string", false));
                    fields.put("description", createFieldConfig(Arrays.asList("@.description", "@.summary", "@.content"), "string", false));
                    fields.put("created_at", createFieldConfig(Arrays.asList("@.created_at", "@.timestamp", "@.date"), "string", false));
                    fields.put("updated_at", createFieldConfig(Arrays.asList("@.updated_at", "@.modified_at"), "string", false));
                }
            } else {
                // Default generic fields
                fields.put("id", createFieldConfig(Arrays.asList("@.id", "@.pk"), "string", true));
                fields.put("title", createFieldConfig(Arrays.asList("@.title", "@.name", "@.label"), "string", false));
                fields.put("description", createFieldConfig(Arrays.asList("@.description", "@.summary", "@.content"), "string", false));
                fields.put("created_at", createFieldConfig(Arrays.asList("@.created_at", "@.timestamp", "@.date"), "string", false));
            }
            
            defaultMappingSpec.put("fields", fields);
            
            // Add metadata
            Map<String, Object> meta = new HashMap<>();
            meta.put("version", "1.0");
            meta.put("generated_at", System.currentTimeMillis());
            meta.put("tool_id", toolId);
            meta.put("tool_name", toolContext != null ? toolContext.getToolName() : "Unknown");
            meta.put("is_default", true);
            defaultMappingSpec.put("meta", meta);
            
            logger.info("Successfully generated default mapping spec for tool_id: {}", toolId);
            return ResponseEntity.ok(defaultMappingSpec);
            
        } catch (Exception e) {
            logger.error("Error getting latest mapping spec for tool_id {}: {}", toolId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Failed to get latest mapping spec: " + e.getMessage()));
        }
    }
    
    /**
     * Helper method to create field configuration
     */
    private Map<String, Object> createFieldConfig(java.util.List<String> candidates, String type, boolean required) {
        Map<String, Object> field = new HashMap<>();
        field.put("candidates", candidates);
        field.put("to", type);
        field.put("required", required);
        return field;
    }
    
    /**
     * Recupere les donnees de reponse d'un outil pour generer le mapping
     * Utilise le ToolResponseService pour recuperer les VRAIES donnees de l'outil
     */
    private String getToolResponseData(String toolId) {
        try {
            logger.info("Retrieving REAL tool response data for tool: {}", toolId);
            
            // Convertir le toolId en UUID
            UUID toolUuid;
            try {
                toolUuid = UUID.fromString(toolId);
            } catch (IllegalArgumentException e) {
                logger.error("Invalid tool ID format: {}", toolId);
                throw new IllegalArgumentException("Invalid tool ID format: " + toolId);
            }
            
            // Recuperer les reponses de l'outil via le service
            List<ToolResponseDto> responses = toolResponseService.getResponsesByToolId(toolUuid);
            
            if (responses == null || responses.isEmpty()) {
                logger.warn("No tool responses found for tool: {}", toolId);
                throw new RuntimeException("No tool responses found for tool: " + toolId + ". Please ensure the tool has been tested and has response data.");
            }
            
            // Prendre la premiere reponse comme echantillon
            ToolResponseDto firstResponse = responses.get(0);
            logger.info("Using response data from response ID: {} for tool: {}", firstResponse.getId(), toolId);
            
            // Recuperer le JSON de la reponse
            String responseData = firstResponse.getExampleJsonb();
            if (responseData == null || responseData.trim().isEmpty()) {
                logger.warn("No JSON data found in response for tool: {}", toolId);
                throw new RuntimeException("No JSON data found in tool response for tool: " + toolId + ". Please ensure the tool response contains valid JSON data.");
            }
            
            // Valider que c'est du JSON valide
            try {
                objectMapper.readTree(responseData);
                logger.info("Successfully retrieved and validated JSON response data for tool: {}", toolId);
                return responseData;
            } catch (JsonProcessingException e) {
                logger.error("Invalid JSON format in tool response for tool {}: {}", toolId, e.getMessage());
                throw new RuntimeException("Invalid JSON format in tool response for tool: " + toolId + ". Error: " + e.getMessage());
            }
            
        } catch (IllegalArgumentException e) {
            // Re-lancer les erreurs d'argument telles quelles
            throw e;
        } catch (RuntimeException e) {
            // Re-lancer les erreurs metier telles quelles
            throw e;
        } catch (Exception e) {
            logger.error("Unexpected error retrieving tool response data for {}: {}", toolId, e.getMessage(), e);
            throw new RuntimeException("Failed to retrieve tool response data: " + e.getMessage());
        }
    }
}
