package com.apimarketplace.catalog.seed;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.dto.ApiResponse;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.service.ApiService;
import com.apimarketplace.catalog.service.LexicalIndexSyncService;
import com.apimarketplace.catalog.service.StructureSkeletonService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "catalog.seed.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class CatalogSeedService {

    private final CatalogSeedConfig config;
    private final CatalogSeedStateRepository seedStateRepository;
    private final ApiService apiService;
    private final ApiRepository apiRepository;
    private final ApiToolRepository apiToolRepository;
    private final OpenApiTransformer transformer;
    private final CatalogSeedCredentialService credentialService;
    private final LexicalIndexSyncService lexicalIndexSyncService;
    private final ObjectMapper objectMapper;
    private final CatalogDataBootstrapService dataBootstrapService;
    private final StructureSkeletonService structureSkeletonService;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Thread.ofVirtual().name("catalog-seed-import").start(() -> {
            try {
                // First: load SQL dump if catalog is empty (bulk import of 500+ APIs)
                dataBootstrapService.bootstrapIfNeeded();

                // Then: process YAML-based seeds (can override/augment)
                SeedImportResult result = importAll();
                log.info("Catalog seed import complete: imported={}, skipped={}, failed={}, userModified={}",
                        result.imported(), result.skipped(), result.failed(), result.userModified());
                if (!result.errors().isEmpty()) {
                    result.errors().forEach(err -> log.warn("Seed import error: {}", err));
                }

                // Generate structure_skeleton for any tool_responses missing it
                int skeletonCount = structureSkeletonService.runMigrationBatch(1000);
                if (skeletonCount > 0) {
                    log.info("Generated structure_skeleton for {} tool responses", skeletonCount);
                }
            } catch (Exception e) {
                log.error("Catalog seed import failed: {}", e.getMessage(), e);
            }
        });
    }

    public SeedImportResult importAll() {
        Path seedDir = Paths.get(config.getPath());
        Path manifestPath = seedDir.resolve("manifest.json");

        if (!Files.exists(manifestPath)) {
            log.info("No catalog seed manifest found at {}. Skipping seed import.", manifestPath);
            return new SeedImportResult(0, 0, 0, 0, List.of());
        }

        SeedManifest manifest;
        try {
            manifest = objectMapper.readValue(manifestPath.toFile(), SeedManifest.class);
        } catch (IOException e) {
            log.error("Failed to read seed manifest: {}", e.getMessage());
            return new SeedImportResult(0, 0, 0, 0, List.of("Failed to read manifest: " + e.getMessage()));
        }

        if (manifest.getSpecs() == null || manifest.getSpecs().isEmpty()) {
            log.info("Seed manifest is empty. Nothing to import.");
            return new SeedImportResult(0, 0, 0, 0, List.of());
        }

        int imported = 0;
        int skipped = 0;
        int failed = 0;
        int userModified = 0;
        List<String> errors = new ArrayList<>();

        for (SeedManifest.SeedSpec spec : manifest.getSpecs()) {
            try {
                ImportAction action = importSpec(seedDir, spec);
                switch (action) {
                    case IMPORTED -> imported++;
                    case SKIPPED_UNCHANGED -> skipped++;
                    case SKIPPED_USER_MODIFIED -> userModified++;
                }
            } catch (Exception e) {
                failed++;
                String msg = String.format("Failed to import seed '%s': %s", spec.getId(), e.getMessage());
                errors.add(msg);
                log.error(msg, e);
            }
        }

        return new SeedImportResult(imported, skipped, failed, userModified, errors);
    }

    private ImportAction importSpec(Path seedDir, SeedManifest.SeedSpec spec) throws IOException {
        Path specFile = seedDir.resolve(spec.getFile());
        if (!Files.exists(specFile)) {
            throw new IOException("Spec file not found: " + specFile);
        }

        // Compute checksum
        byte[] fileBytes = Files.readAllBytes(specFile);
        String checksum = sha256(fileBytes);

        // Check existing seed state
        Optional<CatalogSeedStateEntity> existingState = seedStateRepository.findBySeedId(spec.getId());

        if (existingState.isPresent()) {
            CatalogSeedStateEntity state = existingState.get();

            // Check if user has modified the API since last import
            if (!state.isUserModified()) {
                Optional<ApiEntity> existingApi = apiRepository.findById(state.getApiId());
                if (existingApi.isPresent() && existingApi.get().getUpdatedAt() != null
                        && existingApi.get().getUpdatedAt() > state.getLastImportedAt()) {
                    state.setUserModified(true);
                    seedStateRepository.save(state);
                    log.info("Seed '{}' marked as user-modified (API updated since last import)", spec.getId());
                }
            }

            if (state.isUserModified()) {
                log.info("Skipping seed '{}': user has modified the imported API", spec.getId());
                return ImportAction.SKIPPED_USER_MODIFIED;
            }

            if (checksum.equals(state.getFileChecksum())) {
                log.debug("Skipping seed '{}': file unchanged (checksum match)", spec.getId());
                return ImportAction.SKIPPED_UNCHANGED;
            }

            // Changed: delete old API and re-import
            log.info("Seed '{}' has changed, deleting old API {} and re-importing", spec.getId(), state.getApiId());
            try {
                apiService.deleteApi(state.getApiId());
            } catch (Exception e) {
                log.warn("Could not delete old API {} for seed '{}': {}", state.getApiId(), spec.getId(), e.getMessage());
            }
            seedStateRepository.delete(state);
        }

        // Parse OpenAPI spec
        OpenAPI openApi = parseOpenApi(specFile);

        // Transform to submission JsonNode
        JsonNode submissionData = transformer.transform(openApi, spec);

        // Import through existing pipeline
        ApiResponse response = apiService.processApiSubmission(submissionData, config.getOwnerId());

        UUID apiId = response.id();

        // Post-import: link credentials
        credentialService.linkCredentials(apiId, spec);

        // Post-import: sync lexical search index
        syncLexicalIndex(apiId, openApi, spec);

        // Create/update seed state
        CatalogSeedStateEntity seedState = new CatalogSeedStateEntity();
        seedState.setSeedId(spec.getId());
        seedState.setApiId(apiId);
        seedState.setFileChecksum(checksum);
        seedState.setUserModified(false);
        seedState.setLastImportedAt(System.currentTimeMillis());
        seedState.setSpecVersion(spec.getVersion());
        seedStateRepository.save(seedState);

        log.info("Successfully imported seed '{}' as API '{}' (id={})", spec.getId(), response.apiName(), apiId);
        return ImportAction.IMPORTED;
    }

    private void syncLexicalIndex(UUID apiId, OpenAPI openApi, SeedManifest.SeedSpec spec) {
        List<ApiToolEntity> tools = apiToolRepository.findByApiId(apiId);
        String apiTitle = openApi.getInfo() != null ? openApi.getInfo().getTitle() : spec.getId();

        for (ApiToolEntity tool : tools) {
            try {
                // Find matching operation from OpenAPI for richer metadata
                String summary = tool.getDescription() != null ? tool.getDescription() : "";
                String endpoint = tool.getEndpoint() != null ? tool.getEndpoint() : "";
                String method = tool.getMethod() != null ? tool.getMethod() : "";

                List<String> paramsRequired = new ArrayList<>();
                List<String> paramsOptional = new ArrayList<>();
                extractParamsFromOpenApi(openApi, endpoint, method, paramsRequired, paramsOptional);

                String keywords = String.join(", ", apiTitle, spec.getCategory(),
                        spec.getSubcategory() != null ? spec.getSubcategory() : "",
                        endpoint, method).replaceAll(",\\s*,", ",");

                LexicalIndexSyncService.SyncData syncData = LexicalIndexSyncService.SyncData.builder()
                        .toolName(tool.getToolSlug())
                        .provider(apiTitle)
                        .resource(spec.getCategory())
                        .action(method)
                        .endpoint(endpoint)
                        .paramsRequired(paramsRequired)
                        .paramsOptional(paramsOptional)
                        .summary(summary)
                        .keywords(keywords)
                        .category(spec.getCategory())
                        .subcategory(spec.getSubcategory())
                        .build();

                lexicalIndexSyncService.sync(tool.getId(), syncData);
            } catch (Exception e) {
                log.warn("Failed to sync lexical index for tool {}: {}", tool.getId(), e.getMessage());
            }
        }
    }

    private void extractParamsFromOpenApi(OpenAPI openApi, String endpoint, String method,
                                          List<String> paramsRequired, List<String> paramsOptional) {
        if (openApi.getPaths() == null) return;
        PathItem pathItem = openApi.getPaths().get(endpoint);
        if (pathItem == null) return;

        Operation operation = switch (method.toUpperCase()) {
            case "GET" -> pathItem.getGet();
            case "POST" -> pathItem.getPost();
            case "PUT" -> pathItem.getPut();
            case "DELETE" -> pathItem.getDelete();
            case "PATCH" -> pathItem.getPatch();
            default -> null;
        };
        if (operation == null || operation.getParameters() == null) return;

        for (var param : operation.getParameters()) {
            if (param.getRequired() != null && param.getRequired()) {
                paramsRequired.add(param.getName());
            } else {
                paramsOptional.add(param.getName());
            }
        }
    }

    private OpenAPI parseOpenApi(Path specFile) {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setFlatten(true);

        SwaggerParseResult result = new OpenAPIV3Parser().readLocation(
                specFile.toAbsolutePath().toString(), null, options);

        if (result.getOpenAPI() == null) {
            String errors = result.getMessages() != null ? String.join("; ", result.getMessages()) : "unknown error";
            throw new RuntimeException("Failed to parse OpenAPI spec: " + errors);
        }

        if (result.getMessages() != null && !result.getMessages().isEmpty()) {
            log.warn("OpenAPI parser warnings for {}: {}", specFile.getFileName(), result.getMessages());
        }

        return result.getOpenAPI();
    }

    private String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private enum ImportAction {
        IMPORTED,
        SKIPPED_UNCHANGED,
        SKIPPED_USER_MODIFIED
    }
}
