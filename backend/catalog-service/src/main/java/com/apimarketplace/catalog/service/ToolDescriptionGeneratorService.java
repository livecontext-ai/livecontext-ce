package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.*;
import com.apimarketplace.catalog.mapping.entity.MappingDefinitionEntity;
import com.apimarketplace.catalog.repository.*;
import com.apimarketplace.catalog.mapping.repository.MappingDefinitionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ToolDescriptionGeneratorService {

    private final ToolNameRepository toolNameRepository;
    private final ApiToolRepository apiToolRepository;
    private final ApiCategoryRepository apiCategoryRepository;
    private final ApiSubcategoryRepository apiSubcategoryRepository;
    private final ToolCategoryRepository toolCategoryRepository;
    private final ApiRepository apiRepository;
    private final ApiToolParameterRepository apiToolParameterRepository;
    private final MappingDefinitionRepository mappingDefinitionRepository;
    private final JdbcTemplate jdbcTemplate;
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
     * Genere une description enrichie pour un tool a partir de son ID
     */
    public String generateEnrichedDescription(UUID toolId) {
        try {
            log.info("Generating enriched description for tool ID: {}", toolId);

            // 1. Recuperer les informations de base du tool
            ApiToolEntity apiTool = apiToolRepository.findById(toolId)
                                                     .orElseThrow(() -> new RuntimeException("Tool not found: " + toolId));

            // 2. Recuperer l'API associee
            ApiEntity api = apiRepository.findById(apiTool.getApiId())
                                         .orElseThrow(() -> new RuntimeException("API not found: " + apiTool.getApiId()));

            // 3. Recuperer les categories API
            ApiCategoryEntity apiCategory = apiCategoryRepository.findById(api.getCategoryId())
                                                                 .orElseThrow(() -> new RuntimeException("API Category not found: " + api.getCategoryId()));

            ApiSubcategoryEntity apiSubcategory = apiSubcategoryRepository.findById(api.getSubcategoryId())
                                                                          .orElseThrow(() -> new RuntimeException("API Subcategory not found: " + api.getSubcategoryId()));

            // 4. Recuperer les informations tool_name si disponible
            ToolNameEntity toolName = null;
            ToolCategoryEntity toolCategory = null;
            if (apiTool.getToolNameId() != null) {
                try {
                    UUID toolNameId = UUID.fromString(apiTool.getToolNameId());
                    toolName = toolNameRepository.findById(toolNameId).orElse(null);
                    if (toolName != null) {
                        toolCategory = toolCategoryRepository.findById(toolName.getToolCategoryId()).orElse(null);
                    }
                } catch (Exception e) {
                    log.warn("Could not retrieve tool_name information: {}", e.getMessage());
                }
            }

            // 5. Recuperer les parametres du tool
            List<ApiToolParameterEntity> parameters = apiToolParameterRepository.findByApiToolId(toolId);
            List<ApiToolParameterEntity> requiredParams = parameters.stream()
                                                                    .filter(ApiToolParameterEntity::getIsRequired)
                                                                    .collect(Collectors.toList());
            List<ApiToolParameterEntity> optionalParams = parameters.stream()
                                                                    .filter(p -> !p.getIsRequired())
                                                                    .collect(Collectors.toList());

            // 6. Recuperer le mapping definition pour connaître les outputs
            String mappingInfo = "";
            try {
                Optional<MappingDefinitionEntity> mappingOpt = mappingDefinitionRepository.findLatestByToolId(toolId);
                if (mappingOpt.isPresent()) {
                    mappingInfo = extractMappingOutputs(mappingOpt.get());
                }
            } catch (Exception e) {
                log.warn("Could not retrieve mapping definition: {}", e.getMessage());
            }

            // 7. Construire la description enrichie
            return buildEnrichedDescription(
                    apiTool, api, apiCategory, apiSubcategory,
                    toolName, toolCategory, requiredParams, optionalParams, mappingInfo
                                           );

        } catch (Exception e) {
            log.error("Error generating enriched description for tool {}: {}", toolId, e.getMessage(), e);
            throw new RuntimeException("Failed to generate enriched description: " + e.getMessage(), e);
        }
    }

    private String buildEnrichedDescription(
            ApiToolEntity apiTool,
            ApiEntity api,
            ApiCategoryEntity apiCategory,
            ApiSubcategoryEntity apiSubcategory,
            ToolNameEntity toolName,
            ToolCategoryEntity toolCategory,
            List<ApiToolParameterEntity> requiredParams,
            List<ApiToolParameterEntity> optionalParams,
            String mappingInfo) {

        // Limites de caracteres pour eviter un prompt trop long
        final int MAX_DESCRIPTION_LENGTH = 200;
        final int MAX_API_DESCRIPTION_LENGTH = 150;
        final int MAX_CATEGORY_DESC_LENGTH = 100;
        final int MAX_TOOL_DESC_LENGTH = 150;
        final int MAX_PARAM_DESC_LENGTH = 80;
        final int MAX_EXAMPLE_LENGTH = 30;
        final int MAX_MAPPING_INFO_LENGTH = 300;
        final int MAX_REQUIRED_PARAMS = 8;
        final int MAX_OPTIONAL_PARAMS = 6;

        StringBuilder description = new StringBuilder();

        // Informations de base (avec gestion null)
        description.append("[Tool Information]\n");
        description.append("Tool: ").append(truncateTextWithPlaceholder(getToolName(apiTool), 100, "Unknown")).append("\n");
        description.append("Description: ").append(truncateTextWithPlaceholder(apiTool.getDescription(), MAX_DESCRIPTION_LENGTH, "No description")).append("\n");
        description.append("Method: ").append(truncateTextWithPlaceholder(apiTool.getMethod(), 10, "GET")).append("\n");
        description.append("Endpoint: ").append(truncateTextWithPlaceholder(apiTool.getEndpoint(), 100, "/unknown")).append("\n\n");

        // Informations API (avec gestion null)
        description.append("[API Information]\n");
        description.append("API: ").append(truncateTextWithPlaceholder(api.getApiName(), 80, "Unknown API")).append("\n");
        description.append("API Description: ").append(truncateTextWithPlaceholder(api.getDescription(), MAX_API_DESCRIPTION_LENGTH, "No API description")).append("\n");
        
        // Categorie API
        String categoryName = apiCategory != null ? apiCategory.getName() : "Unknown";
        String categoryDesc = apiCategory != null ? truncateText(apiCategory.getDescription(), MAX_CATEGORY_DESC_LENGTH) : "";
        description.append("API Category: ").append(truncateText(categoryName, 50));
        if (!categoryDesc.isEmpty()) {
            description.append(" - ").append(categoryDesc);
        }
        description.append("\n");
        
        // Sous-categorie API
        String subcategoryName = apiSubcategory != null ? apiSubcategory.getName() : "Unknown";
        String subcategoryDesc = apiSubcategory != null ? truncateText(apiSubcategory.getDescription(), MAX_CATEGORY_DESC_LENGTH) : "";
        description.append("API Subcategory: ").append(truncateText(subcategoryName, 50));
        if (!subcategoryDesc.isEmpty()) {
            description.append(" - ").append(subcategoryDesc);
        }
        description.append("\n\n");

        // Informations tool_name si disponibles (avec gestion null)
        if (toolName != null) {
            description.append("[Tool Details]\n");
            description.append("Tool Name: ").append(truncateTextWithPlaceholder(toolName.getName(), 80, "Unknown Tool")).append("\n");
            
            String toolDesc = truncateText(toolName.getDescription(), MAX_TOOL_DESC_LENGTH);
            if (!toolDesc.isEmpty()) {
                description.append("Tool Name Description: ").append(toolDesc).append("\n");
            }
            
            if (toolCategory != null) {
                String toolCatName = truncateTextWithPlaceholder(toolCategory.getName(), 50, "Unknown Category");
                String toolCatDesc = truncateText(toolCategory.getDescription(), MAX_CATEGORY_DESC_LENGTH);
                description.append("Tool Category: ").append(toolCatName);
                if (!toolCatDesc.isEmpty()) {
                    description.append(" - ").append(toolCatDesc);
                }
                description.append("\n");
            }
            description.append("\n");
        }

        // Deduplication des parametres : si un parametre est a la fois requis et optionnel, 
        // on le garde SEULEMENT dans les requis
        Set<String> requiredParamNames = requiredParams.stream()
            .map(ApiToolParameterEntity::getName)
            .collect(Collectors.toSet());
        
        List<ApiToolParameterEntity> deduplicatedOptionalParams = optionalParams.stream()
            .filter(param -> !requiredParamNames.contains(param.getName()))
            .collect(Collectors.toList());
        
        log.info("Parameter deduplication for tool {}: {} required, {} optional (after dedup: {})", 
                apiTool.getId(), requiredParams.size(), optionalParams.size(), deduplicatedOptionalParams.size());
        
        if (deduplicatedOptionalParams.size() < optionalParams.size()) {
            Set<String> duplicatedParams = optionalParams.stream()
                .map(ApiToolParameterEntity::getName)
                .filter(requiredParamNames::contains)
                .collect(Collectors.toSet());
            log.warn("Removed duplicated parameters from optional list: {}", duplicatedParams);
        }

        // Parametres requis (limites en nombre et taille)
        List<ApiToolParameterEntity> limitedRequiredParams = limitParameters(requiredParams, MAX_REQUIRED_PARAMS);
        if (!limitedRequiredParams.isEmpty()) {
            description.append("[Required Parameters]\n");
            for (ApiToolParameterEntity param : limitedRequiredParams) {
                String paramName = truncateTextWithPlaceholder(param.getName(), 50, "unknown_param");
                String dataType = truncateTextWithPlaceholder(param.getDataType(), 20, "string");
                
                description.append("- ").append(paramName).append(" (").append(dataType).append(")");
                
                String paramDesc = truncateText(param.getDescription(), MAX_PARAM_DESC_LENGTH);
                if (!paramDesc.isEmpty()) {
                    description.append(": ").append(paramDesc);
                }
                
                String example = truncateText(param.getExampleValue(), MAX_EXAMPLE_LENGTH);
                if (!example.isEmpty()) {
                    description.append(" [Example: ").append(example).append("]");
                }
                description.append("\n");
            }
            
            // Indiquer s'il y a plus de parametres
            if (requiredParams.size() > MAX_REQUIRED_PARAMS) {
                description.append("... and ").append(requiredParams.size() - MAX_REQUIRED_PARAMS).append(" more required parameters\n");
            }
            description.append("\n");
        }

        // Parametres optionnels (dedupliques et limites en nombre et taille)
        List<ApiToolParameterEntity> limitedOptionalParams = limitParameters(deduplicatedOptionalParams, MAX_OPTIONAL_PARAMS);
        if (!limitedOptionalParams.isEmpty()) {
            description.append("[Optional Parameters]\n");
            for (ApiToolParameterEntity param : limitedOptionalParams) {
                String paramName = truncateTextWithPlaceholder(param.getName(), 50, "unknown_param");
                String dataType = truncateTextWithPlaceholder(param.getDataType(), 20, "string");
                
                description.append("- ").append(paramName).append(" (").append(dataType).append(")");
                
                String paramDesc = truncateText(param.getDescription(), MAX_PARAM_DESC_LENGTH);
                if (!paramDesc.isEmpty()) {
                    description.append(": ").append(paramDesc);
                }
                
                String example = truncateText(param.getExampleValue(), MAX_EXAMPLE_LENGTH);
                if (!example.isEmpty()) {
                    description.append(" [Example: ").append(example).append("]");
                }
                description.append("\n");
            }
            
            // Indiquer s'il y a plus de parametres
            if (deduplicatedOptionalParams.size() > MAX_OPTIONAL_PARAMS) {
                description.append("... and ").append(deduplicatedOptionalParams.size() - MAX_OPTIONAL_PARAMS).append(" more optional parameters\n");
            }
            description.append("\n");
        }

        String result = description.toString();
        
        // Log de la taille du prompt genere avec estimation des tokens
        int estimatedTokens = estimateTokenCount(result);
        log.info("Generated enriched description: {} characters, {} lines, ~{} tokens", 
                result.length(), result.split("\n").length, estimatedTokens);
        log.debug("Prompt preview: {}", result.substring(0, Math.min(300, result.length())));
        
        // Avertissement si le prompt est tres long
        if (estimatedTokens > 2000) {
            log.warn("Generated prompt is quite long (~{} tokens). Consider further optimization.", estimatedTokens);
        }
        
        return result;
    }

    private String extractMappingOutputs(MappingDefinitionEntity mapping) {
        try {
            StringBuilder output = new StringBuilder();

            // Essayer de recuperer des informations sur les versions si disponibles
            try {
                String versionSql = """
                        SELECT 
                            mv.version,
                            mv.spec,
                            mv.is_latest
                        FROM catalog.mapping_versions mv 
                        JOIN catalog.mapping_definitions md ON mv.mapping_definition_id = md.id
                        WHERE md.tool_id = ? AND mv.is_latest = true
                        """;

                Map<String, Object> versionData = jdbcTemplate.queryForMap(versionSql, mapping.getToolId());

                // Afficher seulement les noms des proprietes avec leur type
                Object specObj = versionData.get("spec");
                if (specObj != null) {
                    String spec = specObj.toString();
                    if (!spec.isEmpty() && !spec.equals("null")) {
                        try {
                            ObjectMapper mapper = new ObjectMapper();
                            JsonNode specNode = mapper.readTree(spec);
                            if (specNode.has("fields")) {
                                JsonNode fields = specNode.get("fields");

                                // Separer les champs requis et optionnels avec leurs types
                                java.util.List<String> requiredFields = new java.util.ArrayList<>();
                                java.util.List<String> optionalFields = new java.util.ArrayList<>();

                                fields.fieldNames().forEachRemaining(fieldName -> {
                                    JsonNode field = fields.get(fieldName);
                                    String fieldType = field.has("to") ? field.get("to").asText() : "unknown";
                                    String fieldInfo = fieldName + " (" + fieldType + ")";

                                    if (field.has("required") && field.get("required").asBoolean()) {
                                        requiredFields.add(fieldInfo);
                                    } else {
                                        optionalFields.add(fieldInfo);
                                    }
                                });

                                // Afficher d'abord les champs requis
                                if (!requiredFields.isEmpty()) {
                                    output.append("Required Fields: ").append(String.join(", ", requiredFields)).append("\n");
                                }

                                // Afficher les champs optionnels
                                if (!optionalFields.isEmpty()) {
                                    output.append("Optional Fields: ").append(String.join(", ", optionalFields)).append("\n");
                                }
                            } else {
                                output.append("Mapping Fields: No fields structure found\n");
                            }
                        } catch (Exception parseException) {
                            output.append("Mapping Fields: Unable to parse spec\n");
                        }
                    } else {
                        output.append("Mapping Fields: No spec available\n");
                    }
                } else {
                    output.append("Mapping Fields: No spec available\n");
                }
            } catch (Exception e) {
                log.debug("Could not retrieve version information: {}", e.getMessage());
                output.append("Mapping Fields: Unable to retrieve spec information\n");
            }

            return output.toString();
        } catch (Exception e) {
            log.warn("Could not extract mapping outputs: {}", e.getMessage());
            return "Mapping information not available";
        }
    }

    /**
     * Tronque une chaîne de caracteres avec gestion des null
     * @param text Le texte a tronquer (peut etre null)
     * @param maxLength Longueur maximale
     * @return Le texte tronque ou chaîne vide si null
     */
    private String truncateText(String text, int maxLength) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        
        String trimmed = text.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        
        // Tronquer et ajouter "..." si tronque
        return trimmed.substring(0, maxLength - 3) + "...";
    }

    /**
     * Tronque une chaîne avec gestion null et retour de placeholder si vide
     * @param text Le texte a tronquer
     * @param maxLength Longueur maximale
     * @param placeholder Texte de remplacement si null/vide
     * @return Le texte tronque ou le placeholder
     */
    private String truncateTextWithPlaceholder(String text, int maxLength, String placeholder) {
        String truncated = truncateText(text, maxLength);
        return truncated.isEmpty() ? placeholder : truncated;
    }

    /**
     * Limite le nombre de parametres pour eviter un prompt trop long
     * @param params Liste des parametres
     * @param maxCount Nombre maximum de parametres a inclure
     * @return Liste tronquee
     */
    private List<ApiToolParameterEntity> limitParameters(List<ApiToolParameterEntity> params, int maxCount) {
        if (params == null || params.isEmpty()) {
            return new ArrayList<>();
        }
        
        if (params.size() <= maxCount) {
            return params;
        }
        
        // Prendre les premiers maxCount parametres
        return params.stream().limit(maxCount).collect(Collectors.toList());
    }

    /**
     * Estime le nombre de tokens approximatif d'un texte
     * Utilise une heuristique simple : ~4 caracteres par token pour l'anglais
     * @param text Le texte a analyser
     * @return Estimation du nombre de tokens
     */
    private int estimateTokenCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        
        // Heuristique simple : 
        // - ~4 caracteres par token pour l'anglais
        // - Ajuster pour la ponctuation et les espaces
        // - Les mots techniques peuvent etre plus longs
        
        String[] words = text.split("\\s+");
        int wordCount = words.length;
        int charCount = text.length();
        
        // Estimation basee sur les caracteres avec ajustement pour les mots
        int estimatedByChars = charCount / 4;
        
        // Estimation basee sur les mots (en moyenne 1.3 token par mot)
        int estimatedByWords = (int) (wordCount * 1.3);
        
        // Prendre la moyenne des deux estimations
        return (estimatedByChars + estimatedByWords) / 2;
    }
}
