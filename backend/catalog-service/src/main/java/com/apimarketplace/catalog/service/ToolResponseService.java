package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ResponseFormat;
import com.apimarketplace.catalog.domain.ToolResponseEntity;
import com.apimarketplace.catalog.dto.ToolResponseDto;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.repository.ToolResponseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Service pour gerer les reponses des outils
 */
@Service
@Transactional
public class ToolResponseService {

    private static final Logger logger = LoggerFactory.getLogger(ToolResponseService.class);

    @Autowired
    private ObjectMapper objectMapper;
    
    @Autowired
    private ToolResponseRepository toolResponseRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private ApiToolRepository apiToolRepository;
    
    @Autowired
    private com.apimarketplace.catalog.util.JsonSkeletonGenerator jsonSkeletonGenerator;
    
    /**
     * Recupere toutes les reponses pour un outil donne
     */
    @Transactional(readOnly = true)
    public List<ToolResponseDto> getResponsesByToolId(UUID toolId) {
        logger.info("=== TOOL RESPONSE SERVICE - GET RESPONSES BY TOOL ID ===");
        logger.info("Recuperation des reponses pour l'outil: {}", toolId);
        logger.info("Tool ID type: {}, toString: {}", toolId.getClass().getSimpleName(), toolId);
        
        List<ToolResponseEntity> responses = toolResponseRepository.findByToolId(toolId);
        logger.info("Repository returned {} responses for toolId: {}", responses.size(), toolId);
        
        if (responses.isEmpty()) {
            logger.warn("No tool responses found for toolId: {}", toolId);
            // Log some available tool responses for debugging
            try {
                List<ToolResponseEntity> allResponses = toolResponseRepository.findAll();
                logger.warn("Total tool responses in database: {}", allResponses.size());
                if (!allResponses.isEmpty()) {
                    logger.warn("Sample tool response IDs and tool IDs:");
                    allResponses.stream().limit(5).forEach(r -> 
                        logger.warn("Response ID: {}, Tool ID: {}, Name: {}", 
                            r.getId(), r.getToolId(), r.getName()));
                }
            } catch (Exception e) {
                logger.warn("Could not list available tool responses: {}", e.getMessage());
            }
        } else {
            logger.info("Found {} responses for toolId: {}", responses.size(), toolId);
            responses.forEach(r -> logger.info("Response: ID={}, Name={}, ToolId={}, HasExampleJsonb={}", 
                r.getId(), r.getName(), r.getToolId(), r.getExampleJsonb() != null));
        }
        
        List<ToolResponseDto> result = responses.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
        
        logger.info("Converted to {} DTOs", result.size());
        return result;
    }
    
    /**
     * Recupere une reponse specifique par ID
     */
    @Transactional(readOnly = true)
    public Optional<ToolResponseDto> getResponseById(java.util.UUID responseId) {
        logger.info("Recuperation de la reponse: {}", responseId);

        return toolResponseRepository.findById(responseId)
                .map(this::convertToDto);
    }
    
    /**
     * Recupere la reponse par defaut pour un outil (by UUID)
     */
    @Transactional(readOnly = true)
    public Optional<ToolResponseDto> getDefaultResponseByToolId(UUID toolId) {
        logger.info("Recuperation de la reponse par defaut pour l'outil: {}", toolId);

        return toolResponseRepository.findByToolIdAndIsDefaultTrue(toolId)
                .map(this::convertToDto);
    }

    /**
     * Recupere la reponse par defaut pour un outil (by slug: apiSlug/toolSlug)
     * Used by MockToolsGateway in orchestrator-service.
     */
    @Transactional(readOnly = true)
    public Optional<ToolResponseDto> getDefaultResponseByToolSlug(String fullSlug) {
        logger.info("Recuperation de la reponse par defaut pour l'outil par slug: {}", fullSlug);

        // fullSlug format: "apiSlug/toolSlug" e.g. "gmail/gmail-list-messages"
        // Extract just the toolSlug part
        String toolSlug = fullSlug;
        if (fullSlug.contains("/")) {
            toolSlug = fullSlug.substring(fullSlug.lastIndexOf("/") + 1);
        }

        // Find tool by slug
        Optional<ApiToolEntity> tool = apiToolRepository.findByToolSlug(toolSlug);
        if (tool.isEmpty()) {
            logger.warn("Tool not found for slug: {}", toolSlug);
            return Optional.empty();
        }

        UUID toolId = tool.get().getId();
        logger.info("Found tool with ID {} for slug {}", toolId, toolSlug);

        return toolResponseRepository.findByToolIdAndIsDefaultTrue(toolId)
                .map(this::convertToDto);
    }


    /**
     * Detecte si une chaîne est du JSON valide
     */
    private boolean isValidJson(String content) {
        if (content == null || content.trim().isEmpty()) {
            return false;
        }

        String trimmedContent = content.trim();
        try {
            objectMapper.readTree(trimmedContent);
            return true;
        } catch (Exception e) {
            logger.debug("Le contenu n'est pas du JSON valide: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Cree une nouvelle reponse ou retourne l'existante si elle existe deja.
     * Gere les race conditions lors d'imports paralleles.
     */
    public ToolResponseDto createResponse(ToolResponseDto responseDto, String createdBy) {
        // Gerer le cas où createdBy est null
        if (createdBy == null) {
            logger.info("Aucun utilisateur specifie, createdBy sera null");
        } else {
            logger.info("Utilisateur specifie: {}", createdBy);
        }
        logger.info("Creation d'une nouvelle reponse pour l'outil: {}", responseDto.getToolId());

        // Utiliser le nom fourni ou laisser null
        String name = responseDto.getName();

        // Check if response already exists (handles parallel imports)
        if (name != null && responseDto.getToolId() != null) {
            Optional<ToolResponseEntity> existing = toolResponseRepository.findByToolIdAndName(
                    responseDto.getToolId(), name);
            if (existing.isPresent()) {
                logger.info("Tool response already exists for tool {} with name '{}', reusing existing",
                        responseDto.getToolId(), name);
                return convertToDto(existing.get());
            }
        }

        logger.info("Verification de l'existence de l'outil...");

        String description = responseDto.getDescription();

        String schema = responseDto.getSchema();
        
        // Determiner automatiquement le format si non fourni
        ResponseFormat format = responseDto.getResponseFormat();
        logger.info("Format initial: {}", format);
        if (format == null) {
            format = determineResponseFormat(responseDto.getExample());
            logger.info("Format determine automatiquement: {}", format);
        }

        // Definir par defaut comme reponse par defaut si c'est la premiere
        Boolean isDefault = responseDto.getIsDefault();
        if (isDefault == null) {
            isDefault = true; // Par defaut, c'est la reponse par defaut
        }
        
        // Si c'est la reponse par defaut, desactiver les autres reponses par defaut
        if (isDefault) {
            setOtherResponsesAsNonDefault(responseDto.getToolId());
        }

        // LOG AVANT CReATION DE L'ENTITe
        logger.info("=== CReATION DE L'ENTITe ===");
        logger.info("Parametres pour ToolResponseEntity:");
        logger.info("  toolId: {}", responseDto.getToolId());
        logger.info("  name: {}", name);
        logger.info("  description: {}", description);
        logger.info("  schema: {}", schema);
        logger.info("  example: {}", responseDto.getExample());
        logger.info("  format: {}", format);
        logger.info("  statusCode: {}", responseDto.getStatusCode());
        logger.info("  isDefault: {}", isDefault);
        logger.info("  isActive: {}", responseDto.getIsActive() != null ? responseDto.getIsActive() : true);
        logger.info("===========================");

        ToolResponseEntity response = new ToolResponseEntity(
                responseDto.getToolId(),
                name,
                description,
                schema,
                responseDto.getExample(),
                responseDto.getExampleJsonb(),
                format,
                responseDto.getStatusCode(),
                isDefault,
                responseDto.getIsActive() != null ? responseDto.getIsActive() : true
        );

        logger.info("Entite creee avec succes, tentative de sauvegarde avec SQL native...");

        // Generer un UUID si l'entite n'en a pas
        if (response.getId() == null) {
            java.util.UUID newId = java.util.UUID.randomUUID();
            response.setId(newId);
            logger.info("UUID genere pour la nouvelle entite: {}", newId);
        }

        // Determiner où stocker l'exemple selon son type
        String exampleContent = response.getExample();
        boolean isJsonContent = isValidJson(exampleContent);

        String exampleText = null;
        String exampleJsonb = null;
        String structureSkeleton = null;

        if (isJsonContent) {
            // Si c'est du JSON, on le stocke dans example_jsonb
            exampleJsonb = exampleContent;
            try {
                JsonNode root = objectMapper.readTree(exampleJsonb);
                JsonNode skeleton = jsonSkeletonGenerator.generateSkeleton(root);
                // Utiliser ObjectMapper pour convertir en JSON string plutôt que toString()
                structureSkeleton = objectMapper.writeValueAsString(skeleton);
            } catch (Exception e) {
                logger.warn("Echec de la generation du squelette pour la reponse: {}", e.getMessage(), e);
                // On continue sans skeleton plutôt que de bloquer la création
            }
        } else {
            // Sinon, on le stocke dans example (text)
            exampleText = exampleContent;
        }

        // Utiliser une requete SQL native pour eviter les problemes de conversion JSONB de Hibernate
        // Utiliser NULL pour le champ qui n'est pas utilise
        String sql = "INSERT INTO catalog.tool_responses " +
                     "(id, tool_id, name, description, schema, example, example_jsonb, structure_skeleton, format, status_code, is_default, is_active, created_at, updated_at, created_by) " +
                     "VALUES (?, ?, ?, ?, " + (schema != null ? "?::jsonb" : "NULL") + ", ?, " + (isJsonContent ? "?::jsonb" : "NULL") + ", " + (structureSkeleton != null ? "?::jsonb" : "NULL") + ", ?, ?, ?, ?, ?, ?, ?)" +
                     " ON CONFLICT (tool_id, name) DO NOTHING";

        // Executer la requete SQL native avec gestion des race conditions
        final String finalExampleText = exampleText;
        final String finalExampleJsonb = exampleJsonb;
        final String finalStructureSkeleton = structureSkeleton;
        final String finalSchema = schema;

        try {
            int rowsInserted = 0;
            if (isJsonContent) {
                // Si JSON, passer tous les parametres avec exampleJsonb
                if (finalSchema != null) {
                    rowsInserted = jdbcTemplate.update(sql,
                        response.getId(),
                        response.getToolId(),
                        response.getName(),
                        response.getDescription(),
                        finalSchema,
                        finalExampleText,  // example (text) - sera null
                        finalExampleJsonb,  // example_jsonb (jsonb) - contient le JSON
                        finalStructureSkeleton, // Squelette genere
                        response.getResponseFormat() != null ? response.getResponseFormat().name().toLowerCase() : "json",
                        response.getStatusCode(),
                        response.getIsDefault(),
                        response.getIsActive(),
                        java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()),
                        java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()),
                        createdBy
                    );
                } else {
                    // Schema null - need different SQL without schema placeholder
                    String sqlNoSchema = "INSERT INTO catalog.tool_responses " +
                             "(id, tool_id, name, description, schema, example, example_jsonb, structure_skeleton, format, status_code, is_default, is_active, created_at, updated_at, created_by) " +
                             "VALUES (?, ?, ?, ?, NULL, ?, ?::jsonb, " + (finalStructureSkeleton != null ? "?::jsonb" : "NULL") + ", ?, ?, ?, ?, ?, ?, ?)" +
                             " ON CONFLICT (tool_id, name) DO NOTHING";
                    if (finalStructureSkeleton != null) {
                        rowsInserted = jdbcTemplate.update(sqlNoSchema,
                            response.getId(),
                            response.getToolId(),
                            response.getName(),
                            response.getDescription(),
                            finalExampleText,
                            finalExampleJsonb,
                            finalStructureSkeleton,
                            response.getResponseFormat() != null ? response.getResponseFormat().name().toLowerCase() : "json",
                            response.getStatusCode(),
                            response.getIsDefault(),
                            response.getIsActive(),
                            java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()),
                            java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()),
                            createdBy
                        );
                    } else {
                        rowsInserted = jdbcTemplate.update(sqlNoSchema,
                            response.getId(),
                            response.getToolId(),
                            response.getName(),
                            response.getDescription(),
                            finalExampleText,
                            finalExampleJsonb,
                            response.getResponseFormat() != null ? response.getResponseFormat().name().toLowerCase() : "json",
                            response.getStatusCode(),
                            response.getIsDefault(),
                            response.getIsActive(),
                            java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()),
                            java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()),
                            createdBy
                        );
                    }
                }
            } else {
                // Si non-JSON, passer les parametres sans exampleJsonb (qui sera NULL)
                String sqlWithoutJsonb = "INSERT INTO catalog.tool_responses " +
                         "(id, tool_id, name, description, schema, example, example_jsonb, structure_skeleton, format, status_code, is_default, is_active, created_at, updated_at, created_by) " +
                         "VALUES (?, ?, ?, ?, " + (finalSchema != null ? "?::jsonb" : "NULL") + ", ?, NULL, NULL, ?, ?, ?, ?, ?, ?, ?)" +
                         " ON CONFLICT (tool_id, name) DO NOTHING";


                if (finalSchema != null) {
                    rowsInserted = jdbcTemplate.update(sqlWithoutJsonb,
                        response.getId(),
                        response.getToolId(),
                        response.getName(),
                        response.getDescription(),
                        finalSchema,
                        finalExampleText,  // example (text) - contient le contenu non-JSON
                        response.getResponseFormat() != null ? response.getResponseFormat().name().toLowerCase() : "json",
                        response.getStatusCode(),
                        response.getIsDefault(),
                        response.getIsActive(),
                        java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()),
                        java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()),
                        createdBy
                    );
                } else {
                    // Schema null
                    rowsInserted = jdbcTemplate.update(sqlWithoutJsonb,
                        response.getId(),
                        response.getToolId(),
                        response.getName(),
                        response.getDescription(),
                        finalExampleText,  // example (text) - contient le contenu non-JSON
                        response.getResponseFormat() != null ? response.getResponseFormat().name().toLowerCase() : "json",
                        response.getStatusCode(),
                        response.getIsDefault(),
                        response.getIsActive(),
                        java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()),
                        java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()),
                        createdBy
                    );
                }
            }

            // If no rows inserted due to conflict, fetch and return existing
            if (rowsInserted == 0 && response.getName() != null) {
                logger.info("Tool response conflict for tool {} with name '{}', fetching existing",
                        response.getToolId(), response.getName());
                Optional<ToolResponseEntity> existing = toolResponseRepository.findByToolIdAndName(
                        response.getToolId(), response.getName());
                if (existing.isPresent()) {
                    return convertToDto(existing.get());
                }
            }
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // Race condition - another thread created it, fetch the existing one
            logger.info("Duplicate key for tool response, fetching existing for tool {} with name '{}'",
                    response.getToolId(), response.getName());
            if (response.getName() != null) {
                Optional<ToolResponseEntity> existing = toolResponseRepository.findByToolIdAndName(
                        response.getToolId(), response.getName());
                if (existing.isPresent()) {
                    return convertToDto(existing.get());
                }
            }
            throw e; // Re-throw if we couldn't find the existing record
        }

        return convertToDto(response);
    }
    
    /**
     * Met a jour une reponse existante
     */
    public ToolResponseDto updateResponse(java.util.UUID responseId, ToolResponseDto responseDto, String updatedBy) {
        logger.info("Mise a jour de la reponse: {}", responseId);

        ToolResponseEntity existingResponse = toolResponseRepository.findById(responseId)
                .orElseThrow(() -> new IllegalArgumentException("Reponse non trouvee avec l'ID: " + responseId));
        
        // Utiliser le nom fourni ou laisser null
        String finalName = responseDto.getName();
        
        // Verifier l'unicite du nom si le nom a change (seulement si le nom n'est pas null)
        if (finalName != null && !finalName.equals(existingResponse.getName()) && 
            toolResponseRepository.existsByToolIdAndName(existingResponse.getToolId(), finalName)) {
            throw new IllegalArgumentException("Une reponse avec ce nom existe deja pour cet outil: " + finalName);
        }
        
        // Si c'est la reponse par defaut, desactiver les autres reponses par defaut
        if (Boolean.TRUE.equals(responseDto.getIsDefault())) {
            setOtherResponsesAsNonDefault(existingResponse.getToolId());
        }
        
        // Utiliser le nom final genere precedemment
        String name = finalName;

        String description = responseDto.getDescription();

        String schema = responseDto.getSchema();
        
        // Determiner automatiquement le format si non fourni
        ResponseFormat format = responseDto.getResponseFormat();
        if (format == null) {
            format = determineResponseFormat(responseDto.getExample());
        }
        
        // Definir par defaut comme reponse par defaut si c'est la premiere
        Boolean isDefault = responseDto.getIsDefault();
        if (isDefault == null) {
            isDefault = true; // Par defaut, c'est la reponse par defaut
        }
        
        // Determiner où stocker l'exemple selon son type (meme logique que createResponse)
        String exampleContent = responseDto.getExample();
        boolean isJsonContent = isValidJson(exampleContent);

        String exampleText = null;
        String exampleJsonb = null;
        String structureSkeleton = null;

        if (isJsonContent) {
            // Si c'est du JSON, on le stocke dans example_jsonb
            exampleJsonb = exampleContent;
            try {
                JsonNode root = objectMapper.readTree(exampleJsonb);
                JsonNode skeleton = jsonSkeletonGenerator.generateSkeleton(root);
                // Utiliser ObjectMapper pour convertir en JSON string plutôt que toString()
                structureSkeleton = objectMapper.writeValueAsString(skeleton);
            } catch (Exception e) {
                logger.warn("Echec de la generation du squelette lors de l'update: {}", e.getMessage(), e);
                // On continue sans skeleton plutôt que de bloquer la mise à jour
            }
        } else {
            // Sinon, on le stocke dans example (text)
            exampleText = exampleContent;
        }

        // Utiliser une requete SQL native pour eviter les problemes de conversion JSONB de Hibernate
        String sql = "UPDATE catalog.tool_responses SET " +
                     "name = ?, description = ?, schema = " + (schema != null ? "?::jsonb" : "NULL") + 
                     ", example = ?, example_jsonb = " + (isJsonContent ? "?::jsonb" : "NULL") + 
                     ", structure_skeleton = " + (structureSkeleton != null ? "?::jsonb" : "NULL") +
                     ", format = ?, status_code = ?, is_default = ?, is_active = ?, updated_at = ?, created_by = ? " +
                     "WHERE id = ?";

        // Executer la requete SQL native
        if (isJsonContent) {
            // Si JSON, passer tous les parametres avec exampleJsonb
            if (schema != null) {
                jdbcTemplate.update(sql,
                    name,
                    description,
                    schema,
                    exampleText,  // example (text) - sera null
                    exampleJsonb,  // example_jsonb (jsonb) - contient le JSON
                    structureSkeleton,
                    format != null ? format.name().toLowerCase() : "json",
                    responseDto.getStatusCode(),
                    isDefault,
                    responseDto.getIsActive() != null ? responseDto.getIsActive() : true,
                    java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()),
                    updatedBy,
                    responseId
                );
            } else {
                // Schema null
                jdbcTemplate.update(sql,
                    name,
                    description,
                    exampleText,  // example (text) - sera null
                    exampleJsonb,  // example_jsonb (jsonb) - contient le JSON
                    structureSkeleton,
                    format != null ? format.name().toLowerCase() : "json",
                    responseDto.getStatusCode(),
                    isDefault,
                    responseDto.getIsActive() != null ? responseDto.getIsActive() : true,
                    java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()),
                    updatedBy,
                    responseId
                );
            }
        } else {
            // Si non-JSON, passer les parametres sans exampleJsonb (qui sera NULL)
            String sqlWithoutJsonb = "UPDATE catalog.tool_responses SET " +
                     "name = ?, description = ?, schema = " + (schema != null ? "?::jsonb" : "NULL") + 
                     ", example = ?, example_jsonb = NULL, structure_skeleton = NULL, " +
                     "format = ?, status_code = ?, is_default = ?, is_active = ?, updated_at = ?, created_by = ? " +
                     "WHERE id = ?";

            if (schema != null) {
                jdbcTemplate.update(sqlWithoutJsonb,
                    name,
                    description,
                    schema,
                    exampleText,  // example (text) - contient le contenu non-JSON
                    format != null ? format.name().toLowerCase() : "json",
                    responseDto.getStatusCode(),
                    isDefault,
                    responseDto.getIsActive() != null ? responseDto.getIsActive() : true,
                    java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()),
                    updatedBy,
                    responseId
                );
            } else {
                // Schema null
                jdbcTemplate.update(sqlWithoutJsonb,
                    name,
                    description,
                    exampleText,  // example (text) - contient le contenu non-JSON
                    format != null ? format.name().toLowerCase() : "json",
                    responseDto.getStatusCode(),
                    isDefault,
                    responseDto.getIsActive() != null ? responseDto.getIsActive() : true,
                    java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()),
                    updatedBy,
                    responseId
                );
            }
        }

        logger.info("Reponse mise a jour avec succes: {}", responseId);
        
        // Mettre a jour les items si le format est JSON et exampleJsonb existe
        /*
        if (format == ResponseFormat.JSON && exampleJsonb != null) {
            // Supprimer les anciens items
            toolResponseItemRepository.deleteByToolResponseId(responseId);
            // Creer les nouveaux items
            createItemsFromExampleJsonb(responseId, exampleJsonb);
        } else if (format != ResponseFormat.JSON) {
            // Si le format n'est plus JSON, supprimer les items existants
            toolResponseItemRepository.deleteByToolResponseId(responseId);
        }
        */
        
        // Retourner l'entite mise a jour en la recuperant depuis la base
        return toolResponseRepository.findById(responseId)
                .map(this::convertToDto)
                .orElseThrow(() -> new IllegalArgumentException("Reponse non trouvee apres mise a jour: " + responseId));
    }
    
    /**
     * Supprime une reponse
     */
    public void deleteResponse(java.util.UUID responseId) {
        logger.info("Suppression de la reponse: {}", responseId);

        if (!toolResponseRepository.existsById(responseId)) {
            throw new IllegalArgumentException("Reponse non trouvee avec l'ID: " + responseId);
        }

        toolResponseRepository.deleteById(responseId);
        logger.info("Reponse supprimee avec succes: {}", responseId);
    }
    
    /**
     * Supprime toutes les reponses d'un outil
     */
    public void deleteResponsesByToolId(UUID toolId) {
        logger.info("Suppression de toutes les reponses pour l'outil: {}", toolId);
        
        toolResponseRepository.deleteByToolId(toolId);
        logger.info("Toutes les reponses supprimees pour l'outil: {}", toolId);
    }
    
    /**
     * Definit une reponse comme reponse par defaut
     */
    public ToolResponseDto setAsDefaultResponse(java.util.UUID responseId, String updatedBy) {
        logger.info("Definition de la reponse comme reponse par defaut: {}", responseId);

        ToolResponseEntity response = toolResponseRepository.findById(responseId)
                .orElseThrow(() -> new IllegalArgumentException("Reponse non trouvee avec l'ID: " + responseId));
        
        // Desactiver les autres reponses par defaut
        setOtherResponsesAsNonDefault(response.getToolId());
        
        // Activer cette reponse comme reponse par defaut
        response.setIsDefault(true);
        response.setUpdatedAt(LocalDateTime.now());
        
        ToolResponseEntity savedResponse = toolResponseRepository.save(response);
        logger.info("Reponse definie comme reponse par defaut: {}", savedResponse.getId());
        
        return convertToDto(savedResponse);
    }
    
    /**
     * Desactive toutes les autres reponses par defaut pour un outil
     */
    private void setOtherResponsesAsNonDefault(UUID toolId) {
        List<ToolResponseEntity> defaultResponses = toolResponseRepository.findByToolIdAndIsDefault(toolId, true);
        for (ToolResponseEntity response : defaultResponses) {
            response.setIsDefault(false);
            response.setUpdatedAt(LocalDateTime.now());
        }
        toolResponseRepository.saveAll(defaultResponses);
    }
    
    /**
     * Determine automatiquement le format de reponse base sur le contenu de l'exemple
     */
    private ResponseFormat determineResponseFormat(String example) {
        if (example == null || example.trim().isEmpty()) {
            return ResponseFormat.JSON; // Valeur par defaut
        }

        String trimmedExample = example.trim();

        // Detecter le format HTML
        if (trimmedExample.contains("<html") || trimmedExample.contains("<HTML") ||
            trimmedExample.contains("<body") || trimmedExample.contains("<BODY")) {
            return ResponseFormat.HTML;
        }

        // Detecter le format CSV (contient des virgules et des sauts de ligne)
        if (trimmedExample.contains(",") && trimmedExample.contains("\n")) {
            // Verifier si cela ressemble a du CSV (pas de crochets JSON)
            if (!trimmedExample.contains("{") && !trimmedExample.contains("[")) {
                return ResponseFormat.CSV;
            }
        }

        // Detecter le format XML
        if (trimmedExample.contains("<?xml") || trimmedExample.contains("<xml") ||
            (trimmedExample.contains("<") && trimmedExample.contains(">"))) {
            // Verifier que ce n'est pas du HTML (pas de balises HTML communes)
            if (!trimmedExample.contains("<html") && !trimmedExample.contains("<body") &&
                !trimmedExample.contains("<div") && !trimmedExample.contains("<span")) {
                return ResponseFormat.XML;
            }
        }

        // Detecter le format TEXT (contient principalement du texte)
        if (!trimmedExample.contains("{") && !trimmedExample.contains("[") &&
            !trimmedExample.contains("<") && !trimmedExample.contains(",")) {
            return ResponseFormat.TEXT;
        }

        // Par defaut, considerer comme JSON si contient des accolades ou crochets
        if (trimmedExample.contains("{") || trimmedExample.contains("[")) {
            return ResponseFormat.JSON;
        }

        // Si rien ne correspond, utiliser BINARY
        return ResponseFormat.BINARY;
    }

    /**
     * Cree automatiquement les items depuis example_jsonb
     * Uniquement si le format est JSON
     * Si example_jsonb est un tableau, cree un item par element
     * Si example_jsonb est un objet, cree un seul item
     */
    /*
    private void createItemsFromExampleJsonb(UUID toolResponseId, String exampleJsonb) {
        if (exampleJsonb == null || exampleJsonb.trim().isEmpty()) {
            return;
        }
        
        try {
            JsonNode jsonNode = objectMapper.readTree(exampleJsonb);
            LocalDateTime now = LocalDateTime.now();
            
            if (jsonNode.isArray()) {
                // Si c'est un tableau, creer un item par element
                int priority = 0;
                for (JsonNode itemNode : jsonNode) {
                    String itemData = objectMapper.writeValueAsString(itemNode);
                    ToolResponseItemEntity item = new ToolResponseItemEntity(toolResponseId, itemData, priority);
                    item.setCreatedAt(now);
                    item.setUpdatedAt(now);
                    
                    // Utiliser SQL natif pour inserer avec JSONB
                    String sql = "INSERT INTO catalog.tool_response_items " +
                                 "(id, tool_response_id, data, priority, created_at, updated_at) " +
                                 "VALUES (?, ?, ?::jsonb, ?, ?, ?)";
                    
                    UUID itemId = UUID.randomUUID();
                    jdbcTemplate.update(sql,
                        itemId,
                        toolResponseId,
                        itemData,
                        priority,
                        java.sql.Timestamp.valueOf(now),
                        java.sql.Timestamp.valueOf(now)
                    );
                    
                    priority++;
                }
                logger.info("Cree {} items pour la reponse {}", jsonNode.size(), toolResponseId);
            } else if (jsonNode.isObject()) {
                // Si c'est un objet, creer un seul item avec l'objet complet
                String itemData = objectMapper.writeValueAsString(jsonNode);
                ToolResponseItemEntity item = new ToolResponseItemEntity(toolResponseId, itemData, 0);
                item.setCreatedAt(now);
                item.setUpdatedAt(now);
                
                // Utiliser SQL natif pour inserer avec JSONB
                String sql = "INSERT INTO catalog.tool_response_items " +
                             "(id, tool_response_id, data, priority, created_at, updated_at) " +
                             "VALUES (?, ?, ?::jsonb, ?, ?, ?)";
                
                UUID itemId = UUID.randomUUID();
                jdbcTemplate.update(sql,
                    itemId,
                    toolResponseId,
                    itemData,
                    0,
                    java.sql.Timestamp.valueOf(now),
                    java.sql.Timestamp.valueOf(now)
                );
                
                logger.info("Cree 1 item pour la reponse {}", toolResponseId);
            }
        } catch (Exception e) {
            logger.error("Erreur lors de la creation des items pour la reponse {}: {}", toolResponseId, e.getMessage(), e);
            // Ne pas faire echouer la creation de la reponse si les items ne peuvent pas etre crees
        }
    }
    */
    
    /**
     * Auto-save skeleton from a live execution result.
     * Stores ONLY the structure_skeleton (no example_jsonb duplication).
     * The full result already lives in orchestrator storage + conversation.
     *
     * @param toolId The tool UUID
     * @param resultData The execution result data (used to generate skeleton only)
     * @param httpStatusCode HTTP status code from execution
     */
    public void autoSaveFromExecution(UUID toolId, Object resultData, Integer httpStatusCode) {
        if (toolId == null || resultData == null) return;

        try {
            // Check existing responses
            List<ToolResponseEntity> existing = toolResponseRepository.findByToolId(toolId);

            if (!existing.isEmpty()) {
                // Backfill or REPLACE the skeleton when it is missing or trivially empty.
                // Trivially-empty case (Apify regression): when the first live run returns
                // {} or [], the generated skeleton is {"_t":"obj","props":{}} - useless,
                // but non-null. The previous code skipped on `getStructureSkeleton() != null`,
                // so the empty skeleton stuck forever and every later run with a richer
                // payload was silently ignored. We now treat trivially-empty as "still
                // missing" and let the next non-trivial run overwrite it.
                for (ToolResponseEntity resp : existing) {
                    boolean existingIsUseful = resp.getStructureSkeleton() != null
                            && !isStoredSkeletonTrivial(resp.getStructureSkeleton());
                    if (existingIsUseful) continue;

                    // Generate skeleton from existing example_jsonb OR from live result
                    String source = resp.getExampleJsonb();
                    if (source == null || source.trim().isEmpty()) {
                        source = objectMapper.writeValueAsString(resultData);
                    }
                    if (source == null || source.trim().isEmpty() || "null".equals(source.trim())) continue;

                    try {
                        JsonNode root = objectMapper.readTree(source);
                        JsonNode skeleton = jsonSkeletonGenerator.generateSkeleton(root);
                        if (jsonSkeletonGenerator.isTriviallyEmptySkeleton(skeleton)) {
                            // Don't overwrite with an equally-useless skeleton - wait for a richer run.
                            // Symmetric INFO log with the cold-start skip below: if both legs go quiet
                            // at debug, debugging the next regression takes a log-level flip in prod.
                            logger.info("Backfill SKIPPED for response {} (tool {}): newly generated skeleton " +
                                    "is trivially empty - keeping the existing empty skeleton until a richer run arrives", resp.getId(), toolId);
                            continue;
                        }
                        String skeletonStr = objectMapper.writeValueAsString(skeleton);
                        jdbcTemplate.update(
                            "UPDATE catalog.tool_responses SET structure_skeleton = ?::jsonb, updated_at = ? WHERE id = ?",
                            skeletonStr,
                            java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()),
                            resp.getId()
                        );
                        logger.debug("Auto-generated skeleton for existing response {}", resp.getId());
                    } catch (Exception e) {
                        logger.debug("Failed to auto-generate skeleton for response {}: {}", resp.getId(), e.getMessage());
                    }
                }
                return;
            }

            // No response exists - create a lightweight entry with skeleton only (no example_jsonb duplication)
            String jsonResult = objectMapper.writeValueAsString(resultData);
            if (jsonResult == null || jsonResult.trim().isEmpty() || "null".equals(jsonResult.trim())) return;

            String structureSkeleton = null;
            try {
                JsonNode root = objectMapper.readTree(jsonResult);
                JsonNode skeleton = jsonSkeletonGenerator.generateSkeleton(root);
                if (jsonSkeletonGenerator.isTriviallyEmptySkeleton(skeleton)) {
                    // Cold-start with an empty/uninformative payload - don't insert a
                    // tool_responses row that would lock us out of learning the real
                    // shape on the next execution (per-tool first-write-wins cache).
                    // INFO level: the skip is the only signal upstream that the live
                    // call returned an empty payload. Hidden-at-debug masked the
                    // Apify regression for weeks.
                    logger.info("Auto-save SKIPPED for tool {}: trivially-empty skeleton from live result " +
                            "(payload was {{}} or [] - next non-empty execution will seed the schema)", toolId);
                    return;
                }
                structureSkeleton = objectMapper.writeValueAsString(skeleton);
            } catch (Exception e) {
                logger.debug("Failed to generate skeleton from execution result: {}", e.getMessage());
                return; // No point creating a response without skeleton
            }

            UUID responseId = UUID.randomUUID();
            // Store skeleton only - example_jsonb = NULL to avoid data duplication
            String sql = "INSERT INTO catalog.tool_responses " +
                "(id, tool_id, name, description, example, example_jsonb, structure_skeleton, format, status_code, is_default, is_active, created_at, updated_at, created_by) " +
                "VALUES (?, ?, ?, ?, NULL, NULL, ?::jsonb, 'json', ?, true, true, ?, ?, 'system-auto')" +
                " ON CONFLICT DO NOTHING";

            java.sql.Timestamp now = java.sql.Timestamp.valueOf(java.time.LocalDateTime.now());
            jdbcTemplate.update(sql, responseId, toolId, "Auto-generated", "Skeleton from live execution",
                structureSkeleton, httpStatusCode != null ? httpStatusCode : 200, now, now);

            logger.info("Auto-saved skeleton for tool {} from live execution", toolId);
        } catch (Exception e) {
            logger.debug("Failed to auto-save skeleton for {}: {}", toolId, e.getMessage());
        }
    }

    /**
     * Parse a stored skeleton string and check whether it is trivially empty.
     * Errors during parse are treated as "not trivial" - we only suppress the
     * skeleton when we can prove it's useless, never on parse failure.
     */
    private boolean isStoredSkeletonTrivial(String skeletonJson) {
        if (skeletonJson == null || skeletonJson.isBlank()) return true;
        try {
            return jsonSkeletonGenerator.isTriviallyEmptySkeleton(objectMapper.readTree(skeletonJson));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Convertit une entite en DTO
     */
    private ToolResponseDto convertToDto(ToolResponseEntity entity) {
        return new ToolResponseDto(
                entity.getId(),
                entity.getToolId(),
                entity.getName(),
                entity.getDescription(),
                entity.getSchema(),
                entity.getExample(),
                entity.getExampleJsonb(),
                entity.getStructureSkeleton(),
                entity.getResponseFormat(),
                entity.getStatusCode(),
                entity.getIsDefault(),
                entity.getIsActive(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getCreatedBy() // createdBy
        );
    }
}
