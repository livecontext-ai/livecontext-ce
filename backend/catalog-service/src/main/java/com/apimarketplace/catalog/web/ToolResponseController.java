package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.dto.ToolResponseDto;
import com.apimarketplace.catalog.mapping.dsl.MappingSpec;
import com.apimarketplace.catalog.mapping.service.MappingRegistry;
import com.apimarketplace.catalog.mapping.service.MappingResolverService;
import com.apimarketplace.catalog.service.ToolResponseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Controller REST pour les reponses des outils.
 *
 * <p>DELETE endpoints are admin-only (mutations to global tool-mapping rows
 * affect every tenant - a wipe breaks Gmail/Slack/GCS/etc integrations
 * platform-wide). Caller MUST send {@code X-Internal-Admin-Token} matching
 * the {@code catalog.admin-token} property. Audit 2026-05-16: prior
 * implementation had no auth at all on these DELETE endpoints; gateway
 * routed them publicly so any authenticated user could wipe the catalog.
 */
@RestController
@RequestMapping("/api/tool-responses")
@RequiredArgsConstructor
@Slf4j
public class ToolResponseController {

    @org.springframework.beans.factory.annotation.Value("${catalog.admin-token:}")
    private String catalogAdminToken;

    /**
     * Constant-time string check for the admin token. Returns false on null or
     * mismatch. Blank token configured = endpoint is locked down (return false
     * to deny by default).
     */
    private boolean isAdminCaller(String headerToken) {
        if (catalogAdminToken == null || catalogAdminToken.isBlank()) return false;
        if (headerToken == null || headerToken.isBlank()) return false;
        // length-independent constant-time compare
        return java.security.MessageDigest.isEqual(
                catalogAdminToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                headerToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private final ToolResponseService toolResponseService;
    private final MappingResolverService mappingResolverService;
    private final MappingRegistry mappingRegistry;

    /**
     * Recupere toutes les reponses pour un outil donne
     */
    @GetMapping("/tool/{toolId}")
    public ResponseEntity<List<ToolResponseDto>> getResponsesByTool(@PathVariable UUID toolId) {
        try {
            log.info("Recuperation des reponses pour l'outil: {}", toolId);
            List<ToolResponseDto> responses = toolResponseService.getResponsesByToolId(toolId);
            log.info("Service returned {} responses", responses.size());
            return ResponseEntity.ok(responses);
        } catch (Exception e) {
            log.error("Erreur lors de la recuperation des reponses pour l'outil {}: {}", toolId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Recupere une reponse specifique par ID
     */
    @GetMapping("/{responseId}")
    public ResponseEntity<ToolResponseDto> getResponseById(@PathVariable java.util.UUID responseId) {
        try {
            log.info("Recuperation de la reponse: {}", responseId);
            Optional<ToolResponseDto> response = toolResponseService.getResponseById(responseId);
            return response.map(ResponseEntity::ok)
                           .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("Erreur lors de la recuperation de la reponse {}: {}", responseId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Recupere la reponse par defaut pour un outil (by UUID)
     */
    @GetMapping("/tool/{toolId}/default")
    public ResponseEntity<ToolResponseDto> getDefaultResponseByTool(@PathVariable UUID toolId) {
        try {
            log.info("Recuperation de la reponse par defaut pour l'outil: {}", toolId);
            Optional<ToolResponseDto> response = toolResponseService.getDefaultResponseByToolId(toolId);
            return response.map(ResponseEntity::ok)
                           .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("Erreur lors de la recuperation de la reponse par defaut pour l'outil {}: {}", toolId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Recupere la reponse par defaut pour un outil (by slug: apiSlug/toolSlug)
     * Used by MockToolsGateway in orchestrator-service.
     */
    @GetMapping("/tool/{apiSlug}/{toolSlug}/default")
    public ResponseEntity<ToolResponseDto> getDefaultResponseByToolSlug(
            @PathVariable String apiSlug,
            @PathVariable String toolSlug) {
        try {
            String fullSlug = apiSlug + "/" + toolSlug;
            log.info("Recuperation de la reponse par defaut pour l'outil par slug: {}", fullSlug);
            Optional<ToolResponseDto> response = toolResponseService.getDefaultResponseByToolSlug(fullSlug);
            return response.map(ResponseEntity::ok)
                           .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("Erreur lors de la recuperation de la reponse par defaut pour le slug {}/{}: {}", apiSlug, toolSlug, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Cree une nouvelle reponse
     */
    @PostMapping
    public ResponseEntity<ToolResponseDto> createResponse(@Valid @RequestBody ToolResponseDto responseDto,
                                                          @RequestHeader(value = "X-User-ID", required = false) String userId,
                                                          @RequestHeader(value = "X-Internal-Admin-Token", required = false) String adminToken) {
        // Catalog rows are global (every tenant sees them) - admin-only. Audit 2026-05-16 round-2.
        if (!isAdminCaller(adminToken)) {
            log.warn("Refused unauthorized POST /api/tool-responses - admin-token mismatch");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            log.info("Creation d'une nouvelle reponse pour l'outil: {}", responseDto.getToolId());

            // Si userId est vide ou null, passer null au service
            String finalUserId = (userId == null || userId.trim().isEmpty()) ? null : userId;
            log.info("UserId final: {}", finalUserId);

            ToolResponseDto createdResponse = toolResponseService.createResponse(responseDto, finalUserId);
            return ResponseEntity.status(HttpStatus.CREATED).body(createdResponse);
        } catch (IllegalArgumentException e) {
            log.warn("Erreur de validation lors de la creation de la reponse: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Erreur lors de la creation de la reponse: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Met a jour une reponse existante
     */
    @PutMapping("/{responseId}")
    public ResponseEntity<ToolResponseDto> updateResponse(@PathVariable java.util.UUID responseId,
                                                          @Valid @RequestBody ToolResponseDto responseDto,
                                                          @RequestHeader(value = "X-User-ID", defaultValue = "system") String userId,
                                                          @RequestHeader(value = "X-Internal-Admin-Token", required = false) String adminToken) {
        if (!isAdminCaller(adminToken)) {
            log.warn("Refused unauthorized PUT /api/tool-responses/{} - admin-token mismatch", responseId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            log.info("Mise a jour de la reponse: {}", responseId);
            ToolResponseDto updatedResponse = toolResponseService.updateResponse(responseId, responseDto, userId);
            return ResponseEntity.ok(updatedResponse);
        } catch (IllegalArgumentException e) {
            log.warn("Erreur de validation lors de la mise a jour de la reponse: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Erreur lors de la mise a jour de la reponse: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Supprime une reponse
     */
    @DeleteMapping("/{responseId}")
    public ResponseEntity<Void> deleteResponse(
            @PathVariable java.util.UUID responseId,
            @RequestHeader(value = "X-Internal-Admin-Token", required = false) String adminToken) {
        if (!isAdminCaller(adminToken)) {
            log.warn("Refused unauthorized DELETE /api/tool-responses/{} - admin-token mismatch", responseId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            log.info("Suppression de la reponse: {}", responseId);
            toolResponseService.deleteResponse(responseId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.warn("Reponse non trouvee lors de la suppression: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Erreur lors de la suppression de la reponse: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Supprime toutes les reponses d'un outil
     */
    @DeleteMapping("/tool/{toolId}")
    public ResponseEntity<Void> deleteResponsesByTool(
            @PathVariable UUID toolId,
            @RequestHeader(value = "X-Internal-Admin-Token", required = false) String adminToken) {
        if (!isAdminCaller(adminToken)) {
            log.warn("Refused unauthorized DELETE /api/tool-responses/tool/{} - admin-token mismatch", toolId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            log.info("Suppression de toutes les reponses pour l'outil: {}", toolId);
            toolResponseService.deleteResponsesByToolId(toolId);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("Erreur lors de la suppression des reponses pour l'outil {}: {}", toolId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Definit une reponse comme reponse par defaut
     */
    @PutMapping("/{responseId}/set-default")
    public ResponseEntity<ToolResponseDto> setAsDefaultResponse(@PathVariable java.util.UUID responseId,
                                                                @RequestHeader(value = "X-User-ID", defaultValue = "system") String userId,
                                                                @RequestHeader(value = "X-Internal-Admin-Token", required = false) String adminToken) {
        // set-default flips which response is served to ALL tenants for the tool.
        // Admin-only. Audit 2026-05-16 round-2.
        if (!isAdminCaller(adminToken)) {
            log.warn("Refused unauthorized PUT /api/tool-responses/{}/set-default - admin-token mismatch", responseId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            log.info("Definition de la reponse comme reponse par defaut: {}", responseId);
            ToolResponseDto updatedResponse = toolResponseService.setAsDefaultResponse(responseId, userId);
            return ResponseEntity.ok(updatedResponse);
        } catch (IllegalArgumentException e) {
            log.warn("Reponse non trouvee lors de la definition comme reponse par defaut: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Erreur lors de la definition de la reponse comme reponse par defaut: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Resolve mapping for tool response data
     */
    @PostMapping("/mapping/resolve")
    public ResponseEntity<MappingResolverService.MappingResolutionResult> resolveMapping(
            @RequestBody MappingRequest request,
            @RequestHeader(value = "X-User-ID", required = false) String userId) {

        try {
            if (request == null || request.getToolId() == null) {
                log.error("Invalid request: request={}, toolId={}", request != null, request != null ? request.getToolId() : "null");
                return ResponseEntity.badRequest()
                                     .body(null);
            }
            byte[] input = request.getContent() != null ? request.getContent().getBytes() : new byte[0];
            MappingResolverService.MappingResolutionResult result = mappingResolverService.resolve(request.getToolId(), input);

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error resolving mapping: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body(null);
        }
    }

    /**
     * Create a new mapping for tool response data
     */
    @PostMapping("/mapping/create")
    public ResponseEntity<MappingResolutionResponse> createMapping(
            @RequestBody CreateMappingRequest request,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Internal-Admin-Token", required = false) String adminToken) {
        // Mapping rows are global (every tenant uses them for tool result parsing).
        // Admin-only. Audit 2026-05-16 round-2.
        if (!isAdminCaller(adminToken)) {
            log.warn("Refused unauthorized POST /api/tool-responses/mapping/create - admin-token mismatch");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            log.info("Creating mapping for tool: {} with content length: {}",
                        request.getToolId(), request.getContent() != null ? request.getContent().length() : 0);

            // Get content as bytes
            byte[] input = request.getContent().getBytes();
            if (input.length == 0) {
                log.warn("Empty content provided for mapping creation");
                return ResponseEntity.badRequest()
                                     .body(new MappingResolutionResponse(false, "Empty content provided", null, null, null, null, 0, null, false));
            }

            // Use MappingResolverService.resolve() like in /mapping/resolve
            MappingResolverService.MappingResolutionResult result = mappingResolverService.create(request.getToolId(), input, request.getMappingSpec(), userId);

            log.info("Mapping creation result - Success: {}, Error: {}", result.isSuccess(), result.getError());

            if (result.isSuccess()) {
                log.info("Mapping created successfully for tool: {}", request.getToolId());
                // Convert Map preview to List for compatibility
                List<?> previewList = result.getPreview() != null ? 
                    List.of(result.getPreview()) : Collections.emptyList();
                
                MappingResolutionResponse response = new MappingResolutionResponse(
                        true,
                        null,
                        result.getSourceFormat(),
                        null, // MappingResolverService doesn't return mappingVersion
                        result.getSpec(),
                        previewList,
                        result.getItemCount(),
                        result.getUnresolvedFields(),
                        result.isFromCache()
                );

                return ResponseEntity.ok(response);
            } else {
                log.error("Mapping creation failed for tool {}: {}", request.getToolId(), result.getError());
                return ResponseEntity.badRequest()
                                     .body(new MappingResolutionResponse(false, result.getError(), null, null, null, null, 0, null, false));
            }

        } catch (Exception e) {
            log.error("Error creating mapping for tool {}: {}", request.getToolId(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body(new MappingResolutionResponse(false, "Internal server error: " + e.getMessage(), null, null, null, null, 0, null, false));
        }
    }

    /**
     * Check mapping status for a specific API and tool
     */
    @GetMapping("/mapping/status/{apiId}/{toolId}")
    public ResponseEntity<MappingStatusResponse> getMappingStatus(
            @PathVariable String apiId,
            @PathVariable String toolId,
            @RequestHeader(value = "X-User-ID", required = false) String userId) {

        try {
            log.info("Checking mapping status for API: {} and tool: {}", apiId, toolId);

            // Check if mappings exist for this tool
            boolean hasMapping = mappingResolverService.hasMappingForTool(toolId);

            MappingStatusResponse response = new MappingStatusResponse(hasMapping, null);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error checking mapping status for API {} and tool {}: {}", apiId, toolId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body(new MappingStatusResponse(false, "Error checking mapping status: " + e.getMessage()));
        }
    }

    /**
     * Get existing mapping for a tool
     */
    @GetMapping("/mapping/{apiId}/{toolId}")
    public ResponseEntity<Map<String, Object>> getMapping(
            @PathVariable String apiId,
            @PathVariable String toolId,
            @RequestParam(value = "toolName", required = false) String toolName,
            @RequestHeader(value = "X-User-ID", required = false) String userId) {

        try {
            log.info("Getting existing mapping for API: {} and tool: {}", apiId, toolId);

            UUID toolUuid;
            try {
                toolUuid = UUID.fromString(toolId);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid tool ID format: {}", toolId);
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("error", "Invalid tool ID format");
                return ResponseEntity.ok(response);
            }

            // Check if mappings exist for this tool
            boolean hasMapping = mappingResolverService.hasMappingForTool(toolId);

            if (!hasMapping) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("error", "No mapping found for this tool");
                return ResponseEntity.ok(response);
            }

            // Try to get the latest mapping version
            var mappingDef = mappingRegistry.findLatestMappingVersionByToolId(toolUuid);

            if (mappingDef.isPresent()) {
                // Return a success response indicating mapping exists
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("fromCache", true);
                // Optionally add spec if available
                if (mappingDef.get().getParsedSpec() != null) {
                    response.put("spec", mappingDef.get().getParsedSpec());
                }
                return ResponseEntity.ok(response);
            } else {
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("error", "No mapping definition found");
                return ResponseEntity.ok(response);
            }

        } catch (Exception e) {
            log.error("Error getting mapping for API {} and tool {}: {}", apiId, toolId, e.getMessage(), e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", "Error getting mapping: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Response class for mapping resolution
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    public static class MappingResolutionResponse {
        private boolean success;
        private String error;
        private com.apimarketplace.catalog.mapping.SourceFormat sourceFormat;
        private com.apimarketplace.catalog.mapping.entity.MappingVersionEntity mappingVersion;
        private com.apimarketplace.catalog.mapping.dsl.MappingSpec spec;
        private List<?> preview;
        private int itemCount;
        private List<String> unresolvedFields;
        private boolean fromCache;

        public MappingResolutionResponse() {
        }

        public MappingResolutionResponse(boolean success, String error,
                                         com.apimarketplace.catalog.mapping.SourceFormat sourceFormat,
                                         com.apimarketplace.catalog.mapping.entity.MappingVersionEntity mappingVersion,
                                         com.apimarketplace.catalog.mapping.dsl.MappingSpec spec,
                                         List<?> preview, int itemCount,
                                         List<String> unresolvedFields, boolean fromCache) {
            this.success = success;
            this.error = error;
            this.sourceFormat = sourceFormat;
            this.mappingVersion = mappingVersion;
            this.spec = spec;
            this.preview = preview;
            this.itemCount = itemCount;
            this.unresolvedFields = unresolvedFields;
            this.fromCache = fromCache;
        }

        // Getters and setters
        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

        public com.apimarketplace.catalog.mapping.SourceFormat getSourceFormat() {
            return sourceFormat;
        }

        public void setSourceFormat(com.apimarketplace.catalog.mapping.SourceFormat sourceFormat) {
            this.sourceFormat = sourceFormat;
        }

        public com.apimarketplace.catalog.mapping.entity.MappingVersionEntity getMappingVersion() {
            return mappingVersion;
        }

        public void setMappingVersion(com.apimarketplace.catalog.mapping.entity.MappingVersionEntity mappingVersion) {
            this.mappingVersion = mappingVersion;
        }

        public com.apimarketplace.catalog.mapping.dsl.MappingSpec getSpec() {
            return spec;
        }

        public void setSpec(com.apimarketplace.catalog.mapping.dsl.MappingSpec spec) {
            this.spec = spec;
        }

        public List<?> getPreview() {
            return preview;
        }

        public void setPreview(List<Object> preview) {
            this.preview = preview;
        }

        public int getItemCount() {
            return itemCount;
        }

        public void setItemCount(int itemCount) {
            this.itemCount = itemCount;
        }

        public List<String> getUnresolvedFields() {
            return unresolvedFields;
        }

        public void setUnresolvedFields(List<String> unresolvedFields) {
            this.unresolvedFields = unresolvedFields;
        }

        public boolean isFromCache() {
            return fromCache;
        }

        public void setFromCache(boolean fromCache) {
            this.fromCache = fromCache;
        }
    }

    /**
     * Request DTO for mapping resolution
     */
    public static class MappingRequest {
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = UUIDDeserializer.class)
        private java.util.UUID toolId;
        private String content;
        private String contentType;
        private MappingSpec mappingSpec;

        // Constructors
        public MappingRequest() {
        }

        public MappingRequest(java.util.UUID toolId, String content, String contentType) {
            this.toolId = toolId;
            this.content = content;
            this.contentType = contentType;
        }

        // Getters and setters
        public java.util.UUID getToolId() {
            return toolId;
        }

        public void setToolId(java.util.UUID toolId) {
            this.toolId = toolId;
        }


        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getContentType() {
            return contentType;
        }

        public void setContentType(String contentType) {
            this.contentType = contentType;
        }

        public MappingSpec getMappingSpec() {
            return mappingSpec;
        }

        public void setMappingSpec(MappingSpec mappingSpec) {
            this.mappingSpec = mappingSpec;
        }
    }

    /**
     * Request DTO for mapping creation
     */
    public static class CreateMappingRequest {
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = UUIDDeserializer.class)
        private java.util.UUID toolId;
        private String content;
        private String contentType;
        private MappingSpec mappingSpec;

        // Constructors
        public CreateMappingRequest() {
        }

        public CreateMappingRequest(java.util.UUID toolId, String content, String contentType, MappingSpec mappingSpec) {
            this.toolId = toolId;
            this.content = content;
            this.contentType = contentType;
            this.mappingSpec = mappingSpec;
        }

        // Getters and setters
        public java.util.UUID getToolId() {
            return toolId;
        }

        public void setToolId(java.util.UUID toolId) {
            this.toolId = toolId;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getContentType() {
            return contentType;
        }

        public void setContentType(String contentType) {
            this.contentType = contentType;
        }

        public MappingSpec getMappingSpec() {
            return mappingSpec;
        }

        public void setMappingSpec(MappingSpec mappingSpec) {
            this.mappingSpec = mappingSpec;
        }
    }

    /**
     * Response DTO for mapping status
     */
    public static class MappingStatusResponse {
        private boolean hasMapping;
        private String error;

        // Constructors
        public MappingStatusResponse() {
        }

        public MappingStatusResponse(boolean hasMapping, String error) {
            this.hasMapping = hasMapping;
            this.error = error;
        }

        // Getters and setters
        public boolean isHasMapping() {
            return hasMapping;
        }

        public void setHasMapping(boolean hasMapping) {
            this.hasMapping = hasMapping;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }
    }
}
