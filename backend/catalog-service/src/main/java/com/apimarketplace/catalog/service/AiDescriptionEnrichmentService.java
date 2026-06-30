package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.service.LexicalIndexSyncService.EnrichedSynthesisData;
import com.apimarketplace.catalog.service.SynthesisQualityValidator.SynthesisData;
import com.apimarketplace.catalog.service.SynthesisQualityValidator.ValidationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service pour enrichir les descriptions des outils avec l'IA
 * Optimized for RRF search with enriched synthesis data
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiDescriptionEnrichmentService {

    private final ApiToolRepository apiToolRepository;
    private final LexicalIndexSyncService lexicalIndexSyncService;
    private final ToolDescriptionGeneratorService toolDescriptionGeneratorService;
    private final DeepInfraDescriptionService deepInfraDescriptionService;
    private final SynthesisQualityValidator synthesisQualityValidator;
    private final com.apimarketplace.catalog.repository.ApiToolParameterRepository apiToolParameterRepository;
    private final com.apimarketplace.catalog.repository.ApiRepository apiRepository;
    private final com.apimarketplace.catalog.repository.ApiCategoryRepository apiCategoryRepository;
    private final com.apimarketplace.catalog.repository.ApiSubcategoryRepository apiSubcategoryRepository;
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
     * Enrichit la description d'un outil specifique avec l'IA
     * Uses enriched synthesis format optimized for RRF search
     */
    @Transactional
    public String enrichToolDescription(UUID toolId) {
        try {
            log.info("Enriching description for tool: {}", toolId);

            // Recuperer l'outil
            ApiToolEntity tool = apiToolRepository.findById(toolId)
                                                  .orElseThrow(() -> new RuntimeException("Tool not found: " + toolId));

            // Generer une description enrichie
            String enrichedDescription = toolDescriptionGeneratorService.generateEnrichedDescription(toolId);

            // Generer la synthese enrichie via l'IA
            String aiSynthesis;
            try {
                aiSynthesis = deepInfraDescriptionService.generateAISummaryAndAction(
                        toolId, enrichedDescription);

                // Verifier que c'est du JSON valide
                if (isValidJson(aiSynthesis)) {
                    log.info("Received valid AI synthesis for tool: {}", toolId);
                } else {
                    log.error("DeepInfra returned invalid JSON for tool: {}", toolId);
                    throw new RuntimeException("DeepInfra returned invalid JSON structure for tool " + toolId);
                }

            } catch (Exception e) {
                log.error("Failed to generate AI synthesis for tool {}: {}", toolId, e.getMessage(), e);
                throw new RuntimeException("AI synthesis generation failed for tool " + toolId + ": " + e.getMessage(), e);
            }

            // Parser le JSON de l'IA
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode aiNode = mapper.readTree(aiSynthesis);

            // Construire le provider et resource
            String provider = buildProvider(tool);
            String resource = extractResourceFromToolName(getToolName(tool));

            // Extraire les champs enrichis de l'IA
            String action = getStringOrDefault(aiNode, "action", "list");
            String summary = getStringOrDefault(aiNode, "summary", "API endpoint");

            // Extraire les nouveaux champs enrichis (arrays)
            List<String> keywordsPrimary = getStringListOrEmpty(aiNode, "keywords_primary");
            List<String> keywordsSynonyms = getStringListOrEmpty(aiNode, "keywords_synonyms");
            List<String> keywordsParams = getStringListOrEmpty(aiNode, "keywords_params");
            List<String> useCases = getStringListOrEmpty(aiNode, "use_cases");

            // Legacy keywords field (for backward compatibility)
            String legacyKeywords = aiNode.has("keywords") ? aiNode.get("keywords").asText() : "";

            // Recuperer les parametres depuis la base
            java.util.List<com.apimarketplace.catalog.domain.ApiToolParameterEntity> parameters =
                apiToolParameterRepository.findByApiToolId(tool.getId());

            java.util.List<String> paramsRequired = parameters.stream()
                .filter(com.apimarketplace.catalog.domain.ApiToolParameterEntity::getIsRequired)
                .map(com.apimarketplace.catalog.domain.ApiToolParameterEntity::getName)
                .collect(java.util.stream.Collectors.toList());

            java.util.List<String> paramsOptional = parameters.stream()
                .filter(param -> !param.getIsRequired())
                .map(com.apimarketplace.catalog.domain.ApiToolParameterEntity::getName)
                .collect(java.util.stream.Collectors.toList());

            java.util.List<String> paramExamples = parameters.stream()
                .filter(param -> param.getExampleValue() != null && !param.getExampleValue().trim().isEmpty())
                .map(com.apimarketplace.catalog.domain.ApiToolParameterEntity::getExampleValue)
                .collect(java.util.stream.Collectors.toList());

            // Validate synthesis quality
            SynthesisData synthesisData = new SynthesisData(
                action, summary, resource,
                keywordsPrimary, keywordsSynonyms, keywordsParams, useCases
            );
            ValidationResult validationResult = synthesisQualityValidator.validate(synthesisData);

            if (!validationResult.isValid()) {
                log.warn("Synthesis validation failed for tool {}: Errors: {}", toolId, validationResult.errors());
            }
            if (!validationResult.warnings().isEmpty()) {
                log.info("Synthesis validation warnings for tool {}: {}", toolId, validationResult.warnings());
            }
            log.info("Synthesis quality for tool {}: {}", toolId, validationResult.getSummary());

            // Build enriched synthesis data
            EnrichedSynthesisData enrichedData = EnrichedSynthesisData.builder()
                .toolName(getToolName(tool))
                .provider(provider)
                .resource(resource)
                .action(action)
                .endpoint(tool.getEndpoint())
                .paramsRequired(paramsRequired)
                .paramsOptional(paramsOptional)
                .paramExamples(paramExamples)
                .summary(summary)
                .summaryExtended(summary) // Use same summary as extended for now
                .keywords(legacyKeywords)
                .keywordsPrimary(keywordsPrimary)
                .keywordsSynonyms(keywordsSynonyms)
                .keywordsParams(keywordsParams)
                .useCases(useCases)
                .build();

            // Sync to lexical index with enriched data
            try {
                lexicalIndexSyncService.syncApiToolEnriched(toolId, enrichedData);
                log.info("Successfully synced tool {} with enriched lexical search index", toolId);
            } catch (Exception e) {
                log.error("Failed to sync tool {} with lexical index: {}", toolId, e.getMessage(), e);
                throw new RuntimeException("Failed to sync tool with lexical index: " + e.getMessage(), e);
            }

            // Construire le JSON final pour le retour (compatibilite)
            String finalJsonDescription = buildFinalJsonDescriptionEnriched(tool, aiNode, enrichedData);
            log.info("Successfully processed tool: {} (Quality Score: {})", toolId, validationResult.qualityScore());
            return finalJsonDescription;

        } catch (Exception e) {
            log.error("Error enriching description for tool {}: {}", toolId, e.getMessage(), e);
            throw new RuntimeException("Failed to enrich tool description: " + e.getMessage(), e);
        }
    }

    /**
     * Get string value from JSON node with default
     */
    private String getStringOrDefault(com.fasterxml.jackson.databind.JsonNode node, String field, String defaultValue) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : defaultValue;
    }

    /**
     * Get string list from JSON node
     */
    private List<String> getStringListOrEmpty(com.fasterxml.jackson.databind.JsonNode node, String field) {
        List<String> result = new ArrayList<>();
        if (node.has(field) && node.get(field).isArray()) {
            node.get(field).forEach(item -> {
                if (!item.isNull()) {
                    result.add(item.asText());
                }
            });
        }
        return result;
    }

    /**
     * Build final JSON description with enriched data
     */
    private String buildFinalJsonDescriptionEnriched(ApiToolEntity tool,
                                                      com.fasterxml.jackson.databind.JsonNode aiNode,
                                                      EnrichedSynthesisData data) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Map<String, Object> finalJson = new java.util.LinkedHashMap<>();

            finalJson.put("provider", data.provider());
            finalJson.put("resource", data.resource());
            finalJson.put("action", data.action());
            finalJson.put("endpoint", data.endpoint());
            finalJson.put("params_required", data.paramsRequired());
            finalJson.put("params_optional", data.paramsOptional());
            finalJson.put("param_examples", data.paramExamples());
            finalJson.put("summary", data.summary());
            finalJson.put("keywords", data.keywords());
            finalJson.put("keywords_primary", data.keywordsPrimary());
            finalJson.put("keywords_synonyms", data.keywordsSynonyms());
            finalJson.put("keywords_params", data.keywordsParams());
            finalJson.put("use_cases", data.useCases());

            return mapper.writeValueAsString(finalJson);

        } catch (Exception e) {
            log.error("Error building enriched JSON description: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to build enriched JSON description", e);
        }
    }

    /**
     * Enrichit les descriptions de tous les outils
     */
    @Transactional
    public void enrichAllToolDescriptions() {
        try {
            log.info("Starting enrichment of all tool descriptions");

            List<ApiToolEntity> tools = apiToolRepository.findByIsActiveTrue();
            int successCount = 0;
            int errorCount = 0;

            for (ApiToolEntity tool : tools) {
                try {
                    // Verifier si l'outil a deja une description enrichie
                    if (hasEnrichedDescription(tool.getId())) {
                        log.info("Tool {} ({}) already has enriched description, skipping", tool.getId(), getToolName(tool));
                        successCount++; // Compter comme succes car pas d'erreur
                        continue;
                    }
                    
                    enrichToolDescription(tool.getId());
                    successCount++;
                    log.info("Successfully enriched tool {}: {}", tool.getId(), getToolName(tool));
                } catch (Exception e) {
                    log.error("Failed to enrich tool {} ({}): {}", tool.getId(), getToolName(tool), e.getMessage());
                    log.debug("Full error details for tool {}: ", tool.getId(), e);
                    errorCount++;
                    // Continue avec le prochain outil au lieu d'arreter tout le processus
                }
            }

            log.info("Enrichment completed. Success: {}, Errors: {}", successCount, errorCount);

        } catch (Exception e) {
            log.error("Error during bulk enrichment: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to enrich all tool descriptions: " + e.getMessage(), e);
        }
    }

    /**
     * Recupere la description enrichie d'un outil depuis lexical_search_index
     */
    public String getEnrichedDescription(UUID toolId) {
        try {
            // Verifier que l'outil existe
            if (!apiToolRepository.existsById(toolId)) {
                throw new RuntimeException("Tool not found: " + toolId);
            }

            // Recuperer les donnees depuis lexical_search_index
            String sql = """
                SELECT 
                    provider,
                    resource,
                    action,
                    endpoint,
                    params_required,
                    params_optional,
                    param_examples,
                    summary
                FROM catalog.lexical_search_index
                WHERE api_tool_id = ?
                """;

            var result = lexicalIndexSyncService.getJdbcTemplate().queryForMap(sql, toolId);
            
            // Construire le JSON a partir des donnees lexicales
            return buildJsonFromLexicalData(result);

        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new RuntimeException("No enriched description found for tool: " + toolId);
        } catch (Exception e) {
            log.error("Error getting enriched description for tool {}: {}", toolId, e.getMessage(), e);
            throw new RuntimeException("Failed to get enriched description for tool: " + toolId, e);
        }
    }

    /**
     * Verifie si un outil a une description enrichie dans lexical_search_index
     */
    public boolean hasEnrichedDescription(UUID toolId) {
        try {
            // Verifier que l'outil existe
            if (!apiToolRepository.existsById(toolId)) {
                return false;
            }

            // Verifier si des donnees existent dans lexical_search_index
            String sql = "SELECT COUNT(*) FROM catalog.lexical_search_index WHERE api_tool_id = ?";
            Integer count = lexicalIndexSyncService.getJdbcTemplate().queryForObject(sql, Integer.class, toolId);
            
            return count != null && count > 0;

        } catch (Exception e) {
            log.debug("Error checking enriched description for tool {}: {}", toolId, e.getMessage());
            return false;
        }
    }

    /**
     * Construit le JSON a partir des donnees lexicales
     */
    private String buildJsonFromLexicalData(java.util.Map<String, Object> lexicalData) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Map<String, Object> jsonData = new java.util.LinkedHashMap<>();
            
            // Champs de base
            jsonData.put("provider", lexicalData.get("provider"));
            jsonData.put("resource", lexicalData.get("resource"));
            jsonData.put("action", lexicalData.get("action"));
            jsonData.put("endpoint", lexicalData.get("endpoint"));
            jsonData.put("summary", lexicalData.get("summary"));
            
            // Arrays - conversion depuis PostgreSQL arrays
            jsonData.put("params_required", convertArrayFromDb(lexicalData.get("params_required")));
            jsonData.put("params_optional", convertArrayFromDb(lexicalData.get("params_optional")));
            jsonData.put("param_examples", convertArrayFromDb(lexicalData.get("param_examples")));
            
            return mapper.writeValueAsString(jsonData);
            
        } catch (Exception e) {
            log.error("Error building JSON from lexical data: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to build JSON from lexical data", e);
        }
    }

    /**
     * Convertit un array PostgreSQL en List<String>
     */
    private java.util.List<String> convertArrayFromDb(Object dbArray) {
        if (dbArray == null) {
            return new java.util.ArrayList<>();
        }
        
        if (dbArray instanceof String[]) {
            return java.util.Arrays.asList((String[]) dbArray);
        } else if (dbArray instanceof java.util.List) {
            @SuppressWarnings("unchecked")
            java.util.List<String> list = (java.util.List<String>) dbArray;
            return list;
        } else {
            log.warn("Unexpected array type: {}", dbArray.getClass());
            return new java.util.ArrayList<>();
        }
    }

    /**
     * Valide qu'une chaîne est du JSON valide
     */
    private boolean isValidJson(String jsonString) {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            return false;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.readTree(jsonString);
            return true;
        } catch (Exception e) {
            log.debug("JSON validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Construit le JSON final en combinant les donnees de la base avec le summary/action de l'IA
     */
    private String buildFinalJsonDescription(ApiToolEntity tool, String aiSummaryAndAction) {
        try {
            // Parser le JSON de l'IA pour extraire action et summary
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode aiNode = mapper.readTree(aiSummaryAndAction);
            
            String action = aiNode.has("action") ? aiNode.get("action").asText() : "list";
            String keywords = aiNode.has("keywords") ? aiNode.get("keywords").asText() : "";
            String summary = aiNode.has("summary") ? aiNode.get("summary").asText() : "API endpoint";

            // Construire le provider : apiCategory.name + / + subcategory.name
            String provider = buildProvider(tool);
            
            // Extraire le resource du nom de l'outil
            String resource = extractResourceFromToolName(getToolName(tool));
            
            // Recuperer les parametres depuis la base (comme dans ToolDescriptionGeneratorService)
            java.util.List<com.apimarketplace.catalog.domain.ApiToolParameterEntity> parameters = 
                apiToolParameterRepository.findByApiToolId(tool.getId());
                
            java.util.List<String> paramsRequired = parameters.stream()
                .filter(com.apimarketplace.catalog.domain.ApiToolParameterEntity::getIsRequired)
                .map(com.apimarketplace.catalog.domain.ApiToolParameterEntity::getName)
                .collect(java.util.stream.Collectors.toList());
                
            java.util.List<String> paramsOptional = parameters.stream()
                .filter(param -> !param.getIsRequired())
                .map(com.apimarketplace.catalog.domain.ApiToolParameterEntity::getName)
                .collect(java.util.stream.Collectors.toList());
            
            // Construire les exemples depuis la base
            java.util.List<String> paramExamples = parameters.stream()
                .filter(param -> param.getExampleValue() != null && !param.getExampleValue().trim().isEmpty())
                .map(com.apimarketplace.catalog.domain.ApiToolParameterEntity::getExampleValue)
                .collect(java.util.stream.Collectors.toList());

            // Construire le JSON final
            java.util.Map<String, Object> finalJson = new java.util.LinkedHashMap<>();
            finalJson.put("provider", provider);
            finalJson.put("resource", resource);
            finalJson.put("action", action);
            finalJson.put("endpoint", tool.getEndpoint());
            finalJson.put("params_required", paramsRequired);
            finalJson.put("params_optional", paramsOptional);
            finalJson.put("param_examples", paramExamples);
            finalJson.put("summary", summary);
            finalJson.put("keywords", keywords);

            return mapper.writeValueAsString(finalJson);

        } catch (Exception e) {
            log.error("Error building final JSON description: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to build final JSON description", e);
        }
    }

    private String buildProvider(ApiToolEntity tool) {
        try {
            // Recuperer les categories depuis la base (comme dans ToolDescriptionGeneratorService)
            com.apimarketplace.catalog.domain.ApiEntity api = apiRepository
                .findById(tool.getApiId()).orElse(null);
            
            if (api == null) return "unknown/unknown";
            
            com.apimarketplace.catalog.domain.ApiCategoryEntity apiCategory = apiCategoryRepository
                .findById(api.getCategoryId()).orElse(null);
            com.apimarketplace.catalog.domain.ApiSubcategoryEntity apiSubcategory = apiSubcategoryRepository
                .findById(api.getSubcategoryId()).orElse(null);
                
            String categoryName = apiCategory != null ? apiCategory.getName() : "unknown";
            String subcategoryName = apiSubcategory != null ? apiSubcategory.getName() : "unknown";
            
            return categoryName.toLowerCase() + "/" + subcategoryName.toLowerCase();
            
        } catch (Exception e) {
            log.warn("Error building provider: {}", e.getMessage());
            return "unknown/unknown";
        }
    }

    private String extractResourceFromToolName(String toolName) {
        if (toolName == null) return "unknown";
        
        // Exemple: list_user_stories_by... → user_stories
        // Supprimer les prefixes comme list_, get_, create_, etc.
        String cleaned = toolName.replaceFirst("^(list|get|create|update|delete|fetch|retrieve)_", "");
        
        // Supprimer le suffixe apres "by" s'il existe
        if (cleaned.contains("_by_")) {
            cleaned = cleaned.substring(0, cleaned.indexOf("_by_"));
        } else if (cleaned.contains("_by")) {
            cleaned = cleaned.substring(0, cleaned.indexOf("_by"));
        }
        
        return cleaned.toLowerCase();
    }
}
