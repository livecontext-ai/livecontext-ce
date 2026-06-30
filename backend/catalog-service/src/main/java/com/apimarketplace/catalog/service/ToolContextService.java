package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiToolParameterEntity;
import com.apimarketplace.catalog.domain.ToolNameEntity;
import com.apimarketplace.catalog.domain.ToolCategoryEntity;
import com.apimarketplace.catalog.domain.ApiSubcategoryEntity;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.repository.ToolNameRepository;
import com.apimarketplace.catalog.repository.ToolCategoryRepository;
import com.apimarketplace.catalog.repository.ApiSubcategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service for loading tool context data for mapping generation
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ToolContextService {

    private final ApiRepository apiRepository;
    private final ApiToolRepository apiToolRepository;
    private final ToolNameRepository toolNameRepository;
    private final ToolCategoryRepository toolCategoryRepository;
    private final ApiSubcategoryRepository apiSubcategoryRepository;
    private final ApiToolParameterRepository apiToolParameterRepository;
    private final ToolCategoryService toolCategoryService;
    
    /**
     * Get tool name from tool_names table via tool_name_id
     */
    private String getToolName(ApiToolEntity tool) {
        if (tool.getToolNameId() == null || tool.getToolNameId().trim().isEmpty()) {
            return "Unknown Tool";
        }
        
        return toolCategoryService.getToolNameByToolNameId(tool.getToolNameId())
                .map(tn -> tn.getName())
                .orElse("Unknown Tool");
    }
    
    /**
     * Tool context data container
     */
    public static class ToolContext {
        private String toolId;
        private String apiId;
        private String toolName;
        private String toolNameId; // UUID of the tool_name entry
        private String toolDescription;
        private String toolCategoryName;
        private String toolSubCategoryName;
        private String httpMethod;
        private String endpoint;
        private String toolDescriptionFull;
        private String iconSlug; // Icon slug for UI display
        private Set<String> allowedParameterNames; // Parameter names defined for this tool
        private List<ParamMeta> parameters; // Ordered name+description list for cursor heuristic resolution
        // Typed-execution refactor (V52): raw JSON strings, parsed on demand by OutputProjector / ToolExecutionOrchestrator.
        private String executionSpecJson;
        private String outputSchemaJson;
        private String executionMode;

        /** Slim parameter metadata used by the response shaper's cursor/size resolver. */
        public record ParamMeta(String name, String description) {}
        
        // Getters and Setters
        public String getToolId() {
            return toolId;
        }
        
        public void setToolId(String toolId) {
            this.toolId = toolId;
        }
        
        public String getToolName() {
            return toolName;
        }

        public void setToolName(String toolName) {
            this.toolName = toolName;
        }

        public String getToolNameId() {
            return toolNameId;
        }

        public void setToolNameId(String toolNameId) {
            this.toolNameId = toolNameId;
        }

        public String getToolDescription() {
            return toolDescription;
        }
        
        public void setToolDescription(String toolDescription) {
            this.toolDescription = toolDescription;
        }
        
        public String getToolCategoryName() {
            return toolCategoryName;
        }
        
        public void setToolCategoryName(String toolCategoryName) {
            this.toolCategoryName = toolCategoryName;
        }
        
        public String getToolSubCategoryName() {
            return toolSubCategoryName;
        }
        
        public void setToolSubCategoryName(String toolSubCategoryName) {
            this.toolSubCategoryName = toolSubCategoryName;
        }
        
        public String getHttpMethod() {
            return httpMethod;
        }
        
        public void setHttpMethod(String httpMethod) {
            this.httpMethod = httpMethod;
        }
        
        public String getEndpoint() {
            return endpoint;
        }
        
        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }
        
        public String getToolDescriptionFull() {
            return toolDescriptionFull;
        }
        
        public void setToolDescriptionFull(String toolDescriptionFull) {
            this.toolDescriptionFull = toolDescriptionFull;
        }
        
        public String getApiId() {
            return apiId;
        }
        
        public void setApiId(String apiId) {
            this.apiId = apiId;
        }
        
        public Set<String> getAllowedParameterNames() {
            return allowedParameterNames;
        }

        public void setAllowedParameterNames(Set<String> allowedParameterNames) {
            this.allowedParameterNames = allowedParameterNames;
        }

        public List<ParamMeta> getParameters() {
            return parameters;
        }

        public void setParameters(List<ParamMeta> parameters) {
            this.parameters = parameters;
        }

        public String getIconSlug() {
            return iconSlug;
        }

        public void setIconSlug(String iconSlug) {
            this.iconSlug = iconSlug;
        }

        public String getExecutionSpecJson() {
            return executionSpecJson;
        }

        public void setExecutionSpecJson(String executionSpecJson) {
            this.executionSpecJson = executionSpecJson;
        }

        public String getOutputSchemaJson() {
            return outputSchemaJson;
        }

        public void setOutputSchemaJson(String outputSchemaJson) {
            this.outputSchemaJson = outputSchemaJson;
        }

        public String getExecutionMode() {
            return executionMode;
        }

        public void setExecutionMode(String executionMode) {
            this.executionMode = executionMode;
        }
    }
    
    /**
     * Load tool context data by tool ID or slug
     * 
     * @param toolIdOrSlug The tool ID (UUID as string) or tool slug (e.g., "api-slug/tool-slug" or "tool-slug")
     * @return ToolContext containing all relevant tool information
     */
    public Optional<ToolContext> loadToolContext(String toolIdOrSlug) {
        try {
            Optional<ApiToolEntity> apiToolOpt;
            
            // Check if it's a UUID or a slug
            boolean isUuid = toolIdOrSlug.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
            
            if (isUuid) {
                // Load by UUID
                UUID toolUuid = UUID.fromString(toolIdOrSlug);
                apiToolOpt = apiToolRepository.findById(toolUuid);
            } else {
                // Load by slug - handle both "api-slug/tool-slug" and "tool-slug" formats
                String toolSlug = toolIdOrSlug;
                if (toolSlug.contains("/")) {
                    // Format: "api-slug/tool-slug" - extract just the tool slug part
                    toolSlug = toolSlug.substring(toolSlug.lastIndexOf("/") + 1);
                }
                apiToolOpt = apiToolRepository.findByToolSlug(toolSlug);
            }
            
            if (!apiToolOpt.isPresent()) {
                log.warn("Tool not found with ID or slug: {}", toolIdOrSlug);
                return Optional.empty();
            }
            
            ApiToolEntity apiTool = apiToolOpt.get();
            ToolContext context = new ToolContext();
            
            // Set basic tool information
            context.setToolId(apiTool.getId().toString());
            context.setApiId(apiTool.getApiId() != null ? apiTool.getApiId().toString() : null);
            context.setToolName(getToolName(apiTool));
            context.setToolNameId(apiTool.getToolNameId()); // Store tool_name_id for hint lookup
            context.setToolDescription(apiTool.getDescription());
            context.setHttpMethod(apiTool.getMethod());
            context.setEndpoint(apiTool.getEndpoint());
            context.setToolDescriptionFull(apiTool.getDescription());
            context.setExecutionSpecJson(apiTool.getExecutionSpec());
            context.setOutputSchemaJson(apiTool.getOutputSchema());
            context.setExecutionMode(apiTool.getExecutionMode());

            // Load iconSlug from API entity
            if (apiTool.getApiId() != null) {
                apiRepository.findById(apiTool.getApiId()).ifPresentOrElse(
                    api -> {
                        String iconSlug = api.getIconSlug();
                        context.setIconSlug(iconSlug != null ? iconSlug : "mcp");
                    },
                    () -> context.setIconSlug("mcp")
                );
            }
            
            // Charger les paramètres définis pour ce tool
            List<ApiToolParameterEntity> toolParameters = apiToolParameterRepository.findByApiToolId(apiTool.getId());
            Set<String> allowedParamNames = toolParameters.stream()
                    .map(ApiToolParameterEntity::getName)
                    .collect(Collectors.toSet());
            context.setAllowedParameterNames(allowedParamNames);
            // Slim list for the response shaper's cursor/size heuristic. Description is
            // best-effort (often empty on legacy APIs) - name-only blocklist is the
            // primary defense.
            context.setParameters(toolParameters.stream()
                    .map(p -> new ToolContext.ParamMeta(p.getName(), p.getDescription()))
                    .collect(Collectors.toList()));
            
            // Load tool name entity if toolNameId is available
            if (apiTool.getToolNameId() != null && !apiTool.getToolNameId().trim().isEmpty()) {
                try {
                    UUID toolNameUuid = UUID.fromString(apiTool.getToolNameId());
                    Optional<ToolNameEntity> toolNameOpt = toolNameRepository.findById(toolNameUuid);
                    
                    if (toolNameOpt.isPresent()) {
                        ToolNameEntity toolName = toolNameOpt.get();
                        
                        // Load tool category
                        Optional<ToolCategoryEntity> categoryOpt = toolCategoryRepository.findById(toolName.getToolCategoryId());
                        if (categoryOpt.isPresent()) {
                            context.setToolCategoryName(categoryOpt.get().getName());
                        }
                        
                        // Load subcategory if available
                        if (toolName.getSubcategoryId() != null) {
                            Optional<ApiSubcategoryEntity> subcategoryOpt = apiSubcategoryRepository.findById(toolName.getSubcategoryId());
                            if (subcategoryOpt.isPresent()) {
                                context.setToolSubCategoryName(subcategoryOpt.get().getName());
                            }
                        }
                        
                        // Enrich with tool name data (name + description only)
                        // DO NOT override method/endpoint - ApiToolEntity has the correct implementation-specific values
                        if (toolName.getName() != null && !toolName.getName().trim().isEmpty()) {
                            context.setToolName(toolName.getName());
                        }
                        if (toolName.getDescription() != null && !toolName.getDescription().trim().isEmpty()) {
                            context.setToolDescription(toolName.getDescription());
                            context.setToolDescriptionFull(toolName.getDescription());
                        }
                        // method and endpoint come from ApiToolEntity (implementation-specific)
                    }
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid toolNameId format: {}", apiTool.getToolNameId());
                }
            }
            
            log.info("Loaded tool context for tool ID/slug {}: name={}, category={}, method={}, endpoint={}", 
                       toolIdOrSlug, context.getToolName(), context.getToolCategoryName(), 
                       context.getHttpMethod(), context.getEndpoint());
            
            return Optional.of(context);
            
        } catch (IllegalArgumentException e) {
            log.error("Invalid tool ID format: {}", toolIdOrSlug);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error loading tool context for ID/slug {}: {}", toolIdOrSlug, e.getMessage(), e);
            return Optional.empty();
        }
    }
    
    // REMOVED: loadToolContextByToolNameId() - method/endpoint are now only in ApiToolEntity
    // Use loadToolContext(toolId) instead which loads the complete context
}
