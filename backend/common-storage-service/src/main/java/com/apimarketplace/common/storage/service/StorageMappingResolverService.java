package com.apimarketplace.common.storage.service;

import com.apimarketplace.common.mapping.SimpleMappingEngine;
import com.apimarketplace.common.mapping.SimpleMappingService;
import com.apimarketplace.common.mapping.StrictMappingEngine;
import com.apimarketplace.common.storage.config.StorageMappingConfig;
import com.apimarketplace.common.storage.dto.MappingResolutionResult;
import com.apimarketplace.common.storage.dto.MappingSpec;
import com.apimarketplace.common.storage.service.api.MappingOperations;
import com.apimarketplace.common.storage.service.mapping.MappingSpecConverter;
import com.apimarketplace.common.storage.service.mapping.MappingSpecNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;

/**
 * Service de resolution de mapping pour les donnees storage.
 * Respecte les principes SOLID:
 * - SRP: Delegue la normalisation et la conversion aux services specialises
 * - OCP: Extensible via l'interface MappingOperations
 * - DIP: Depend des abstractions (interfaces et configurations injectees)
 */
@Service
public class StorageMappingResolverService implements MappingOperations {

    private static final Logger logger = LoggerFactory.getLogger(StorageMappingResolverService.class);
    private static final int PREVIEW_LIMIT = 200;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final StorageMappingConfig config;
    private final SimpleMappingService simpleMappingService;
    private final MappingSpecNormalizer normalizer;
    private final MappingSpecConverter converter;

    public StorageMappingResolverService(ObjectMapper objectMapper,
                                        StorageMappingConfig config,
                                        SimpleMappingService simpleMappingService,
                                        MappingSpecNormalizer normalizer,
                                        MappingSpecConverter converter,
                                        WebClient.Builder webClientBuilder) {
        this.objectMapper = objectMapper;
        this.config = config;
        this.simpleMappingService = simpleMappingService;
        this.normalizer = normalizer;
        this.converter = converter;
        String catalogBaseUrl = config.getCatalogBaseUrl();
        this.webClient = webClientBuilder
            .baseUrl(catalogBaseUrl)
            .build();
        // Observabilite : l'URL catalog effective au demarrage. Un localhost:8081 ici sur un deploiement
        // multi-pod (k3s) = la regression "Erreur recuperation MappingSpec / Connection refused".
        logger.info("StorageMappingResolverService catalog base URL resolved to: {} (enabled={})",
            catalogBaseUrl, config.isEnabled());
    }

    @Override
    public boolean isEnabled() {
        return config.isEnabled();
    }

    @Override
    @Cacheable(value = "mappingSpecs", key = "#toolId.toString()")
    public MappingSpec getMappingSpec(UUID toolId) {
        if (!isEnabled()) {
            logger.debug("Mapping resolver desactive");
            return null;
        }

        logger.debug("Recuperation MappingSpec pour toolId: {}", toolId);

        try {
            String responseJson = fetchMappingFromCatalog(toolId);
            if (responseJson == null || responseJson.isBlank()) {
                logger.warn("Reponse vide du catalog-service pour toolId: {}", toolId);
                return null;
            }

            return parseMappingResponse(responseJson, toolId);

        } catch (Exception e) {
            logger.error("Erreur recuperation MappingSpec pour toolId: {} - {}",
                toolId, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public MappingResolutionResult resolve(UUID toolId, String jsonData) {
        if (!isEnabled()) {
            logger.debug("Mapping resolver desactive");
            return null;
        }

        if (toolId == null || jsonData == null || jsonData.isBlank()) {
            logger.debug("Parametres invalides: toolId={}, jsonData null={}",
                toolId, jsonData == null);
            return null;
        }

        logger.debug("Resolution mapping pour toolId: {}", toolId);

        try {
            // 1. Recuperer le spec (depuis le cache si disponible)
            MappingSpec spec = getMappingSpec(toolId);
            if (spec == null) {
                return MappingResolutionResult.failure(
                    "No mapping found for tool " + toolId + ". Please create a mapping first.");
            }

            // 2. Normaliser le spec
            MappingSpec normalizedSpec = normalizer.normalizeComplete(spec);

            // 3. Appliquer le mapping des items
            StrictMappingEngine.StrictMappingSpec strictSpec = converter.toStrictSpec(normalizedSpec);
            SimpleMappingEngine.MappingOutcome outcome = simpleMappingService.applyMapping(jsonData, strictSpec);

            // 4. Resoudre les globals
            Map<String, Object> globals = resolveGlobals(jsonData, normalizedSpec);

            // 5. Construire le resultat
            Map<String, Object> preview = buildPreview(outcome, globals);

            logger.info("Mapping resolu pour toolId: {}, itemCount: {}", toolId, outcome.itemCount);

            return MappingResolutionResult.builder()
                .success(true)
                .preview(preview)
                .itemCount(outcome.itemCount)
                .unresolvedFields(outcome.unresolvedFields != null ? outcome.unresolvedFields : List.of())
                .build();

        } catch (Exception e) {
            logger.error("Erreur resolution mapping pour toolId: {} - {}",
                toolId, e.getMessage(), e);
            return MappingResolutionResult.failure("Resolve error: " + e.getMessage());
        }
    }

    // ========== Methodes privees ==========

    private String fetchMappingFromCatalog(UUID toolId) {
        return webClient.get()
            .uri(uriBuilder -> uriBuilder
                .path("/api/tool-responses/mapping/{apiId}/{toolId}")
                .build("default", toolId.toString()))
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
            .block();
    }

    @SuppressWarnings("unchecked")
    private MappingSpec parseMappingResponse(String responseJson, UUID toolId) throws Exception {
        Map<String, Object> response = objectMapper.readValue(responseJson,
            objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));

        boolean success = Boolean.TRUE.equals(response.get("success"));
        if (!success) {
            String error = (String) response.get("error");
            logger.warn("Echec recuperation mapping spec pour toolId: {}, erreur: {}", toolId, error);
            return null;
        }

        Map<String, Object> specMap = (Map<String, Object>) response.get("spec");
        if (specMap == null) {
            logger.warn("Spec vide dans reponse pour toolId: {}", toolId);
            return null;
        }

        MappingSpec spec = converter.fromMap(specMap);
        logger.info("MappingSpec recupere et mis en cache pour toolId: {}", toolId);

        return spec;
    }

    private Map<String, Object> resolveGlobals(String jsonData, MappingSpec spec) {
        Map<String, Object> globals = new LinkedHashMap<>();

        if (spec.getGlobals() == null || spec.getGlobals().isEmpty()) {
            return globals;
        }

        try {
            StrictMappingEngine.StrictMappingSpec globalsSpec = converter.toGlobalsStrictSpec(spec);
            if (globalsSpec != null) {
                SimpleMappingEngine.MappingOutcome outcome = simpleMappingService.applyMapping(jsonData, globalsSpec);
                if (outcome.items != null && !outcome.items.isEmpty()) {
                    globals.putAll(outcome.items.get(0));
                }
            }
        } catch (Exception e) {
            logger.warn("Erreur mapping globals: {}", e.getMessage());
        }

        return globals;
    }

    private Map<String, Object> buildPreview(SimpleMappingEngine.MappingOutcome outcome,
                                             Map<String, Object> globals) {
        List<Map<String, Object>> items = outcome.items != null ? outcome.items : List.of();
        int limit = Math.min(items.size(), PREVIEW_LIMIT);

        List<Map<String, Object>> fields = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            Map<String, Object> row = new LinkedHashMap<>(items.get(i));
            // Deduplication des listes
            row.replaceAll((k, v) -> {
                if (v instanceof List<?>) {
                    return new ArrayList<>(new LinkedHashSet<>((List<?>) v));
                }
                return v;
            });
            fields.add(row);
        }

        Map<String, Object> wrapper = new LinkedHashMap<>(globals);
        wrapper.put("fields", fields);

        return wrapper;
    }
}
