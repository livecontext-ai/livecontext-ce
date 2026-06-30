package com.apimarketplace.catalog.service.submission;

import com.apimarketplace.catalog.domain.*;
import com.apimarketplace.catalog.dto.ToolResponseDto;
import com.apimarketplace.catalog.repository.*;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.common.web.UrlSafetyValidator;
import com.apimarketplace.catalog.service.ProtocolConfigService;
import com.apimarketplace.catalog.service.ToolCategoryService;
import com.apimarketplace.catalog.service.ToolResponseService;
import com.apimarketplace.catalog.util.IconSlugNormalizer;
import com.apimarketplace.catalog.util.SlugUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApiSubmissionOrchestrator {

    private final ApiRepository apiRepository;
    private final ApiToolRepository apiToolRepository;
    private final ApiCategoryRepository categoryRepository;
    private final ApiSubcategoryRepository subcategoryRepository;
    private final ApiToolMonetizationRepository monetizationRepository;
    private final ToolCategoryRepository toolCategoryRepository;
    private final ToolNameRepository toolNameRepository;
    private final ToolCategoryService toolCategoryService;
    private final ToolResponseService toolResponseService;
    private final ProtocolConfigService protocolConfigService;
    private final CredentialEncryptionService encryptionService;
    private final JdbcTemplate jdbcTemplate;
    // Used when the caller (catalog-service-import) supplies a pre-assigned UUID
    // for catalog.apis.id / catalog.api_tools.id. JdbcAggregateTemplate.insert()
    // forces an INSERT even when @Id is non-null, which Spring Data JDBC's
    // CrudRepository.save() refuses to do (save() routes non-null IDs to UPDATE).
    // See scripts/api-migrations/SCHEMA.md "Stable UUIDs" for rationale.
    private final JdbcAggregateTemplate jdbcAggregateTemplate;
    private final ApiSlugService apiSlugService;
    private final ToolMonetizationService toolMonetizationService;
    private final ToolParameterService toolParameterService;
    private final ToolNameSubcategoryUpdater toolNameSubcategoryUpdater;
    private final ObjectMapper objectMapper;

    @Transactional
    public ApiEntity process(ApiSubmissionCommand command) {
        try {
            JsonNode submissionData = command.payload();
            ApiEntity api = createApiFromSubmission(submissionData, command.userId());
            // Stable UUID path: if the submission carried an apiId, the entity already
            // has a non-null id. CrudRepository.save() would route that to UPDATE
            // (finding nothing) - we need JdbcAggregateTemplate.insert() to force an
            // INSERT that writes the pre-assigned primary key.
            ApiEntity savedApi = (api.getId() != null)
                    ? jdbcAggregateTemplate.insert(api)
                    : apiRepository.save(api);
            log.info("API saved with ID: {}", savedApi.getId());

            for (JsonNode toolData : command.tools()) {
                ApiToolEntity tool = createToolFromData(toolData, savedApi, submissionData);
                log.info("About to save tool for API {}: name='{}', method={}, endpoint='{}', toolNameId={}", savedApi.getId(), toolData.path("name").asText(), tool.getMethod(), tool.getEndpoint(), tool.getToolNameId());
                // Same stable-UUID branch as the API row: pre-assigned id → insert(),
                // fall back to save() for the legacy frontend create-API path.
                ApiToolEntity savedTool = (tool.getId() != null)
                        ? jdbcAggregateTemplate.insert(tool)
                        : apiToolRepository.save(tool);
                
                // Generate and set tool slug after saving
                generateAndSetToolSlug(savedTool, savedApi.getApiSlug());

                // Persist protocol-specific configuration
                protocolConfigService.persistProtocolConfig(savedTool, toolData);

                String toolDisplayName = resolveToolName(savedTool);
                log.info("Tool saved - ID: {}, Name: {}", savedTool.getId(), toolDisplayName);

                List<ApiToolMonetizationEntity> monetizations = toolMonetizationService.createMonetizationFromApiData(submissionData, toolData, savedTool);
                for (ApiToolMonetizationEntity monetization : monetizations) {
                    monetizationRepository.save(monetization);
                    log.info("Monetization configuration saved for tool {} - Type: {} - Plan: {}",
                            toolDisplayName,
                            monetization.getMonetizationType(),
                            monetization.getPlanName());
                }

                toolParameterService.saveParameters(savedTool, toolData, toolDisplayName);
                persistToolResponseIfPresent(savedTool, toolData, command.userId());
            }

            log.info("API '{}' saved with {} tools", savedApi.getApiName(), command.tools().size());
            return savedApi;

        } catch (DuplicateKeyException e) {
            log.error("Unique constraint error during submission processing: {}", e.getMessage(), e);
            throw new RuntimeException("An API with this name already exists for this developer. Please choose a different name.", e);
        } catch (Exception e) {
            log.error("Error processing submission: {}", e.getMessage(), e);
            throw new RuntimeException("Processing error: " + e.getMessage(), e);
        }
    }

    private void persistToolResponseIfPresent(ApiToolEntity tool, JsonNode toolData, String createdBy) {
        JsonNode response = toolData.path("response");
        boolean hasResponseBlock = !response.isMissingNode() && !response.isNull();

        if (hasResponseBlock) {
            log.debug("Persisting response for tool {} with raw payload {}", tool.getId(), response);
            JsonNode exampleNode = response.get("example");
            JsonNode exampleJsonbNode = response.hasNonNull("exampleJsonb") ? response.get("exampleJsonb") : response.get("example_jsonb");
            String exampleJsonb = null;
            if (exampleJsonbNode != null && !exampleJsonbNode.isNull() && !exampleJsonbNode.isMissingNode()) {
                exampleJsonb = exampleJsonbNode.toString();
            } else if (exampleNode != null && !exampleNode.isNull() && !exampleNode.isMissingNode()) {
                if (exampleNode.isContainerNode()) {
                    exampleJsonb = exampleNode.toString();
                }
            }
            String example = null;
            if (exampleNode != null && !exampleNode.isNull() && !exampleNode.isMissingNode()) {
                if (exampleNode.isContainerNode()) {
                    example = exampleNode.toString();
                } else {
                    example = exampleNode.asText();
                }
            }
            if (example == null || example.isBlank()) {
                example = exampleJsonb != null && !exampleJsonb.isBlank() ? exampleJsonb : "{}";
            }
            boolean hasRealPayload = (exampleJsonb != null && !exampleJsonb.isBlank())
                    || (exampleNode != null && !exampleNode.isNull() && !exampleNode.isMissingNode() && exampleNode.isContainerNode())
                    || (response.hasNonNull("schema") && !response.get("schema").isNull() && !response.get("schema").isMissingNode());
            if (!hasRealPayload) {
                log.debug("Response block present but empty for tool {} - falling through to outputSchema", tool.getId());
                // Fall through to outputSchema-based generation below
            } else {
                ResponseFormat format = mapResponseFormat(response.path("type").asText(null), response.path("format").asText(null));
                Integer statusCode = response.path("statusCode").isInt() ? response.path("statusCode").asInt() : 200;
                JsonNode schemaNode = response.get("schema");
                String schema = schemaNode != null && !schemaNode.isNull() && !schemaNode.isMissingNode() ? schemaNode.toString() : null;
                String name = response.path("name").asText(tool.getToolNameId() != null ? tool.getToolNameId() : "default_response");
                String description = response.path("description").asText("");
                Boolean isDefault = response.has("isDefault") ? response.path("isDefault").asBoolean() : true;
                Boolean isActive = response.has("isActive") ? response.path("isActive").asBoolean() : true;

                ToolResponseDto dto = new ToolResponseDto();
                dto.setToolId(tool.getId());
                dto.setName(name);
                dto.setDescription(description);
                dto.setSchema(schema);
                dto.setExample(example);
                dto.setExampleJsonb(exampleJsonb);
                dto.setResponseFormat(format);
                dto.setStatusCode(statusCode);
                dto.setIsDefault(isDefault);
                dto.setIsActive(isActive);
                toolResponseService.createResponse(dto, createdBy);
                log.info("Persisted response for tool {} ({}) with status {}", tool.getId(), name, statusCode);
                return;
            }
        }

        // Fallback: generate a synthetic example from outputSchema when no response block exists,
        // or when the response block was present but had no real payload (no example/schema).
        JsonNode outputSchema = toolData.path("outputSchema");
        if (!outputSchema.isArray() || outputSchema.isEmpty()) {
            log.debug("No response block or outputSchema for tool {}", tool.getId());
            return;
        }

        try {
            ObjectNode syntheticExample = outputSchemaToSyntheticJson(outputSchema);
            String exampleJson = objectMapper.writeValueAsString(syntheticExample);

            ToolResponseDto dto = new ToolResponseDto();
            dto.setToolId(tool.getId());
            dto.setName("default_response");
            dto.setDescription("Auto-generated from outputSchema");
            dto.setExample(exampleJson);
            dto.setExampleJsonb(exampleJson);
            dto.setIsDefault(true);
            dto.setIsActive(true);
            dto.setStatusCode(200);
            toolResponseService.createResponse(dto, createdBy);
            log.info("Persisted outputSchema-based response for tool {} ({})", tool.getId(),
                    toolData.path("name").asText("?"));
        } catch (Exception e) {
            log.warn("Failed to generate response from outputSchema for tool {}: {}",
                    tool.getId(), e.getMessage());
        }
    }

    /**
     * Convert an outputSchema array [{key, type, description, children?}] into a synthetic JSON example.
     * Each field gets a placeholder value based on its declared type.
     * Used to generate tool_responses + skeleton for custom APIs that have no response.example.
     */
    ObjectNode outputSchemaToSyntheticJson(JsonNode outputSchemaArray) {
        ObjectNode root = objectMapper.createObjectNode();
        for (JsonNode field : outputSchemaArray) {
            String key = field.path("key").asText("");
            if (key.isBlank()) continue;
            String type = field.path("type").asText("string").toLowerCase();
            addFieldToNode(root, key, type, field.path("children"));
        }
        return root;
    }

    private void addFieldToNode(ObjectNode parent, String key, String type, JsonNode children) {
        switch (type) {
            case "object" -> {
                ObjectNode obj = objectMapper.createObjectNode();
                if (children.isArray()) {
                    for (JsonNode child : children) {
                        String ck = child.path("key").asText("");
                        if (ck.isBlank()) continue;
                        addFieldToNode(obj, ck, child.path("type").asText("string").toLowerCase(), child.path("children"));
                    }
                }
                parent.set(key, obj);
            }
            case "array" -> {
                ArrayNode arr = objectMapper.createArrayNode();
                if (children.isArray() && !children.isEmpty()) {
                    ObjectNode item = objectMapper.createObjectNode();
                    for (JsonNode child : children) {
                        String ck = child.path("key").asText("");
                        if (ck.isBlank()) continue;
                        addFieldToNode(item, ck, child.path("type").asText("string").toLowerCase(), child.path("children"));
                    }
                    arr.add(item);
                } else {
                    arr.add("example_string");
                }
                parent.set(key, arr);
            }
            case "number", "integer" -> parent.put(key, 0);
            case "boolean" -> parent.put(key, false);
            case "datetime" -> parent.put(key, "2024-01-01T00:00:00Z");
            case "fileref" -> parent.put(key, "file://example.dat");
            default -> parent.put(key, "string");
        }
    }

    private ResponseFormat mapResponseFormat(String type, String explicitFormat) {
        String candidate = explicitFormat;
        if (candidate == null || candidate.isBlank()) {
            candidate = type;
        }
        if (candidate == null || candidate.isBlank()) {
            return ResponseFormat.JSON;
        }
        String normalized = candidate.toLowerCase();
        if (normalized.contains("json")) {
            return ResponseFormat.JSON;
        }
        if (normalized.contains("html")) {
            return ResponseFormat.HTML;
        }
        if (normalized.contains("xml")) {
            return ResponseFormat.XML;
        }
        if (normalized.contains("csv")) {
            return ResponseFormat.CSV;
        }
        if (normalized.contains("text")) {
            return ResponseFormat.TEXT;
        }
        if (normalized.contains("binary") || normalized.contains("octet")) {
            return ResponseFormat.BINARY;
        }
        try {
            return ResponseFormat.valueOf(normalized.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ResponseFormat.JSON;
        }
    }

    private ApiEntity createApiFromSubmission(JsonNode data, String userId) {
        ApiEntity api = new ApiEntity();

        // Stable UUID path (catalog-service-import): if the payload carries an
        // apiId, use it verbatim for catalog.apis.id so that re-imports after a
        // TRUNCATE reproduce the same primary key. The JSON migration files own
        // these UUIDs - see scripts/api-migrations/SCHEMA.md "Stable UUIDs".
        // Legacy frontend create-API flow leaves apiId null → fall back to the
        // gen_random_uuid() Postgres default via save().
        String providedApiId = data.path("apiId").asText(null);
        if (providedApiId != null && !providedApiId.isBlank()) {
            try {
                api.setId(UUID.fromString(providedApiId));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Submission 'apiId' must be a valid UUID, got '" + providedApiId + "'", e);
            }
        }

        // Basic information
        String apiName = data.path("apiName").asText("Unnamed API");
        api.setApiName(apiName);
        api.setDescription(data.path("apiDescription").asText("No description provided"));
        api.setIsLocal(data.path("isLocal").asBoolean(false));
        String categoryDescriptionValue = data.path("categoryDescription").asText("");
        String subcategoryDescriptionValue = data.path("subcategoryDescription").asText("");
        String subcategoryIconUrl = data.path("subcategoryIconUrl").asText("");

        // Use provided apiSlug if available, otherwise generate automatically
        String uniqueSlug;
        String providedSlug = data.path("apiSlug").asText(null);
        if (providedSlug != null && !providedSlug.isBlank()) {
            uniqueSlug = providedSlug;
            log.info("Using provided API slug: {} for API: {}", uniqueSlug, apiName);
        } else {
            String baseSlug = SlugUtils.generateSlug(apiName);
            uniqueSlug = apiSlugService.generateUniqueSlug(baseSlug, userId);
            log.info("Generated API slug: {} for API: {}", uniqueSlug, apiName);
        }
        api.setApiSlug(uniqueSlug);

        // Management of categories and subcategories - Priority to IDs
        String categoryIdStr = data.path("categoryId").asText();
        String subcategoryIdStr = data.path("subcategoryId").asText();
        String selectedCategory = data.path("selectedCategory").asText();
        String selectedSubcategory = data.path("selectedSubcategory").asText();
        Boolean isCustomCategory = data.path("isCustomCategory").asBoolean(false);
        Boolean isCustomSubcategory = data.path("isCustomSubcategory").asBoolean(false);

        // Check if names are provided directly (fallback) - kept for compatibility
        // String categoryName = data.path("categoryName").asText();
        // String subcategoryName = data.path("subcategoryName").asText();

        // Category management with creation if necessary
        UUID finalCategoryId = null;
        
        // 1. Absolute priority: ID provided directly
        if (!categoryIdStr.isEmpty()) {
            try {
                UUID categoryId = UUID.fromString(categoryIdStr);
                if (categoryRepository.existsById(categoryId)) {
                    finalCategoryId = categoryId;
                    log.info("✅ Using provided category UUID: {}", categoryIdStr);
                } else {
                    log.warn("⚠️ Provided category UUID does not exist: {}, fallback to name", categoryIdStr);
                    // Fallback to name
                    if (!selectedCategory.isEmpty()) {
                        UUID existingCategoryId = findCategoryIdByName(selectedCategory);
                        if (existingCategoryId != null) {
                            finalCategoryId = existingCategoryId;
                        } else {
                        finalCategoryId = createCategory(selectedCategory, categoryDescriptionValue);
                    }
                } else {
                    finalCategoryId = createCategory("Custom Category", fallbackDescription(categoryDescriptionValue, "Custom Category"));
                }
                }
            } catch (IllegalArgumentException e) {
                log.warn("⚠️ Invalid category UUID: {}, fallback to name", categoryIdStr);
                // Fallback to name
                if (!selectedCategory.isEmpty()) {
                    UUID existingCategoryId = findCategoryIdByName(selectedCategory);
                    if (existingCategoryId != null) {
                        finalCategoryId = existingCategoryId;
                    } else {
                        finalCategoryId = createCategory(selectedCategory, categoryDescriptionValue);
                    }
                } else {
                    finalCategoryId = createCategory("Custom Category", fallbackDescription(categoryDescriptionValue, "Custom Category"));
                }
            }
        }
        // 2. Fallback: Custom category
        else if (isCustomCategory) {
            String categoryNameToCreate = !selectedCategory.isEmpty() ? selectedCategory : "Custom Category";
            
            log.info("Processing custom category: {}", categoryNameToCreate);
            
            // Check if it already exists
            UUID existingCategoryId = findCategoryIdByName(categoryNameToCreate);
            if (existingCategoryId != null) {
                finalCategoryId = existingCategoryId;
                log.info("Existing custom category found: {}", categoryNameToCreate);
            } else {
                finalCategoryId = createCategory(categoryNameToCreate, categoryDescriptionValue);
                log.info("Custom category created: {}", categoryNameToCreate);
            }
        }
        // 3. Fallback: Search by name
        else if (!selectedCategory.isEmpty()) {
            UUID existingCategoryId = findCategoryIdByName(selectedCategory);
            if (existingCategoryId != null) {
                finalCategoryId = existingCategoryId;
                log.info("✅ Category found by name: {}", selectedCategory);
            } else {
                finalCategoryId = createCategory(selectedCategory, categoryDescriptionValue);
                log.info("✅ Category created by name: {}", selectedCategory);
            }
        }
        // 4. Fallback: Default category
        else {
            UUID otherCategoryId = findCategoryIdByName("Other");
            if (otherCategoryId != null) {
                finalCategoryId = otherCategoryId;
                log.info("✅ Using default category: Other");
            } else {
                finalCategoryId = createCategory("Other", fallbackDescription(categoryDescriptionValue, "Other"));
                log.info("✅ Default category created: Other");
            }
        }
        api.setCategoryId(finalCategoryId);

        // Management of subcategories with priority to IDs
        if (!subcategoryIdStr.isEmpty()) {
            try {
                UUID subcategoryId = UUID.fromString(subcategoryIdStr);
                if (subcategoryRepository.existsById(subcategoryId)) {
                    api.setSubcategoryId(subcategoryId);
                    log.info("✅ Using provided subcategory UUID: {}", subcategoryIdStr);
                } else {
                    log.warn("⚠️ Provided subcategory UUID does not exist: {}, fallback to name", subcategoryIdStr);
                    // Fallback to name
                    if (!selectedSubcategory.isEmpty()) {
                        UUID existingSubcategoryId = findSubcategoryIdByName(selectedSubcategory, finalCategoryId);
                        if (existingSubcategoryId != null) {
                            updateSubcategoryIcon(existingSubcategoryId, subcategoryIconUrl);
                            api.setSubcategoryId(existingSubcategoryId);
                        } else {
                            UUID newSubcategoryId = createSubcategory(selectedSubcategory, finalCategoryId, subcategoryDescriptionValue, subcategoryIconUrl);
                            api.setSubcategoryId(newSubcategoryId);
                        }
                    } else {
                        UUID newSubcategoryId = createSubcategory("Custom Subcategory", finalCategoryId, fallbackDescription(subcategoryDescriptionValue, "Custom Subcategory"), subcategoryIconUrl);
                        api.setSubcategoryId(newSubcategoryId);
                    }
                }
            } catch (IllegalArgumentException e) {
                log.warn("⚠️ Invalid subcategory UUID: {}, fallback to name", subcategoryIdStr);
                // Fallback to name
                if (!selectedSubcategory.isEmpty()) {
                    UUID existingSubcategoryId = findSubcategoryIdByName(selectedSubcategory, finalCategoryId);
                    if (existingSubcategoryId != null) {
                        updateSubcategoryIcon(existingSubcategoryId, subcategoryIconUrl);
                        api.setSubcategoryId(existingSubcategoryId);
                    } else {
                        UUID newSubcategoryId = createSubcategory(selectedSubcategory, finalCategoryId, subcategoryDescriptionValue, subcategoryIconUrl);
                        api.setSubcategoryId(newSubcategoryId);
                    }
                } else {
                    UUID newSubcategoryId = createSubcategory("Custom Subcategory", finalCategoryId, fallbackDescription(subcategoryDescriptionValue, "Custom Subcategory"), subcategoryIconUrl);
                    api.setSubcategoryId(newSubcategoryId);
                }
            }
        }
        // Fallback: Custom subcategory
        else if (isCustomSubcategory) {
            String subcategoryNameToCreate = !selectedSubcategory.isEmpty() ? selectedSubcategory : "Custom Subcategory";
            
            log.info("Processing custom subcategory: {}", subcategoryNameToCreate);
            
            // Check if it already exists
            UUID existingSubcategoryId = findSubcategoryIdByName(subcategoryNameToCreate, finalCategoryId);
            if (existingSubcategoryId != null) {
                updateSubcategoryIcon(existingSubcategoryId, subcategoryIconUrl);
                api.setSubcategoryId(existingSubcategoryId);
                log.info("Existing custom subcategory found: {}", subcategoryNameToCreate);
            } else {
                UUID newSubcategoryId = createSubcategory(subcategoryNameToCreate, finalCategoryId, subcategoryDescriptionValue, subcategoryIconUrl);
                api.setSubcategoryId(newSubcategoryId);
                log.info("Custom subcategory created: {}", subcategoryNameToCreate);
            }
        }
        // Fallback: Search by name
        else if (!selectedSubcategory.isEmpty()) {
            UUID existingSubcategoryId = findSubcategoryIdByName(selectedSubcategory, finalCategoryId);
            if (existingSubcategoryId != null) {
                updateSubcategoryIcon(existingSubcategoryId, subcategoryIconUrl);
                api.setSubcategoryId(existingSubcategoryId);
                log.info("✅ Subcategory found by name: {}", selectedSubcategory);
            } else {
                UUID newSubcategoryId = createSubcategory(selectedSubcategory, finalCategoryId, subcategoryDescriptionValue, subcategoryIconUrl);
                api.setSubcategoryId(newSubcategoryId);
                log.info("✅ Subcategory created by name: {}", selectedSubcategory);
            }
        }
        // Fallback: Default subcategory
        else {
            List<ApiSubcategoryEntity> subcategories = subcategoryRepository.findByCategoryId(finalCategoryId);
            if (!subcategories.isEmpty()) {
                subcategories.sort((a, b) -> Integer.compare(a.getSortOrder(), b.getSortOrder()));
                api.setSubcategoryId(subcategories.get(0).getId());
                log.info("✅ Using first available subcategory");
            } else {
                // Create default subcategory if none exists
                UUID defaultSubcategoryId = createSubcategory("Default", finalCategoryId, fallbackDescription(subcategoryDescriptionValue, "Default"), subcategoryIconUrl);
                api.setSubcategoryId(defaultSubcategoryId);
                log.info("✅ Default subcategory created for category {}", finalCategoryId);
            }
        }

        // API Configuration
        JsonNode apiConfig = data.get("apiConfig");
        if (apiConfig != null) {
            String baseUrl = apiConfig.path("baseUrl").asText();
            if (baseUrl.isEmpty()) {
                baseUrl = "https://api.example.com";
            }
            // SSRF format check at registration; full DNS+IP check at execution (HttpExecutionService)
            String source = data.path("source").asText("");
            if ("custom".equals(source)) {
                UrlSafetyValidator.validateUrlFormat(baseUrl);
            } else {
                UrlSafetyValidator.validateUrl(baseUrl);
            }
            api.setBaseUrl(baseUrl);

            // Healthcheck endpoint configuration
            String healthcheckEndpoint = apiConfig.path("healthcheckEndpoint").asText();
            if (!healthcheckEndpoint.isEmpty()) {
                api.setHealthcheckEndpoint(healthcheckEndpoint);
            }

            // Visibility configuration
            String visibility = apiConfig.path("visibility").asText();
            if (!visibility.isEmpty()) {
                api.setVisibility(visibility);
                // Map visibility to isPublic
                api.setIsPublic("public".equals(visibility));
            } else {
                api.setVisibility("public"); // Default value
                api.setIsPublic(true);
            }

            // Authorization configuration
            if (apiConfig.has("authorization")) {
                JsonNode auth = apiConfig.get("authorization");
                api.setAuthType(auth.path("type").asText("none"));
                api.setAuthHeaderName(auth.path("headerName").asText());
                String headerValue = auth.path("headerValue").asText();
                api.setAuthHeaderValue(
                        headerValue != null && !headerValue.isBlank()
                                ? encryptionService.encrypt(headerValue) : headerValue);
            } else {
                api.setAuthType("none");
            }
        } else {
            // Check if baseUrl is provided directly in data
            String baseUrl = data.path("baseUrl").asText();
            if (baseUrl.isEmpty()) {
                baseUrl = "https://api.example.com";
            }
            String sourceAlt = data.path("source").asText("");
            if ("custom".equals(sourceAlt)) {
                UrlSafetyValidator.validateUrlFormat(baseUrl);
            } else {
                UrlSafetyValidator.validateUrl(baseUrl);
            }
            api.setBaseUrl(baseUrl);
            api.setAuthType("none");
        }

        // Metadonnees par defaut
        api.setCreatedBy(userId != null ? userId : "developer-" + UUID.randomUUID());
        api.setStatus(ApiEntity.ApiStatus.REVIEWING.name()); // Definir le statut par defaut
        
        // Definir le modele de tarification depuis la monetisation
        JsonNode monetization = data.get("monetization");
        if (monetization != null && monetization.has("pricing")) {
            String pricingModel = monetization.path("pricing").asText("freemium");
            api.setPricingModel(pricingModel.toUpperCase());
        } else {
            api.setPricingModel("FREEMIUM");
        }

        // credentialMode is no longer persisted: agentic paths fall back
        // user→platform by default, and workflow nodes carry their own
        // explicit toggle on the Step. Legacy JSONs may still declare the
        // field (`"credentialMode": "both"`); the importer reads it via
        // raw JsonNode below the API row and silently ignores the value.

        // Set platform credential name. Single source of truth: iconSlug - the
        // catalog mirrors it onto `apis.platform_credential_name` and onto every
        // `credentials.credential_name` so the OAuth runtime resolver picks up
        // user creds via a single key. The importer always supplies it; the
        // auto-derive fallback below is for non-importer callers (UI form,
        // third-party submissions). It prefers an explicit iconSlug field when
        // present, falling back to apiName-normalize otherwise.
        String platformCredentialName = data.path("platformCredentialName").asText(null);
        if (platformCredentialName == null || platformCredentialName.isBlank()) {
            String fallbackIconSlug = data.path("iconSlug").asText(null);
            if (fallbackIconSlug != null && !fallbackIconSlug.isBlank()) {
                platformCredentialName = com.apimarketplace.catalog.util.IconSlugNormalizer.normalize(fallbackIconSlug);
            } else {
                platformCredentialName = com.apimarketplace.catalog.util.IconSlugNormalizer.normalizeForKey(api.getApiName());
            }
        }
        if (platformCredentialName != null && !platformCredentialName.isBlank()) {
            api.setPlatformCredentialName(platformCredentialName);
            log.info("API platform credential set: {}", platformCredentialName);
        }

        // Set icon_slug using the centralized IconSlugNormalizer (single source of truth)
        String explicitIconSlug = data.path("iconSlug").asText(null);
        String derivedIconSlug = IconSlugNormalizer.deriveIconSlug(uniqueSlug, explicitIconSlug);
        api.setIconSlug(derivedIconSlug);
        log.info("API icon_slug set to '{}' (explicit: {}, apiSlug: {})", derivedIconSlug, explicitIconSlug, uniqueSlug);

        // Set source (defaults to "import" via entity default)
        String source = data.path("source").asText(null);
        if (source != null && !source.isBlank()) {
            api.setSource(source);
        }

        String organizationId = data.path("organizationId").asText(null);
        if ((organizationId == null || organizationId.isBlank()) && "custom".equals(api.getSource())) {
            organizationId = TenantResolver.currentRequestOrganizationId();
        }
        if (organizationId != null && !organizationId.isBlank() && "custom".equals(api.getSource())) {
            api.setOrganizationId(organizationId);
        }

        // Set icon URL if provided
        String iconUrl = data.path("iconUrl").asText(null);
        if (iconUrl != null && !iconUrl.isBlank()) {
            api.setIconUrl(iconUrl);
        }

        // V83: API-level metadata - used by api-migrations import path.
        // For custom API registration, postProcessApiMetadata() in CustomApiRegistrationService
        // handles these fields separately (converter does not forward them here).
        String apiVersion = data.path("apiVersion").asText(null);
        if (apiVersion != null && !apiVersion.isBlank()) {
            api.setApiVersion(apiVersion);
        }
        String documentation = data.path("documentation").asText(null);
        if (documentation != null && !documentation.isBlank()) {
            api.setDocumentation(documentation);
        }
        JsonNode rateLimitsNode = data.path("rateLimits");
        if (rateLimitsNode != null && !rateLimitsNode.isMissingNode() && !rateLimitsNode.isNull() && rateLimitsNode.isObject()) {
            api.setRateLimits(rateLimitsNode.toString());
        }

        long currentTime = System.currentTimeMillis();
        api.setIsActive(true);
        api.setCreatedAt(currentTime);
        api.setUpdatedAt(currentTime);

        return api;
    }

    /**
     * Creates an MCP tool from JSON data
     */
    private ApiToolEntity createToolFromData(JsonNode toolData, ApiEntity api, JsonNode submissionData) {
        ApiToolEntity tool = new ApiToolEntity();

        // Stable UUID path: use the tool 'id' supplied by the importer (from the
        // migration JSON file) verbatim as catalog.api_tools.id. When present,
        // process() routes the save through JdbcAggregateTemplate.insert() so the
        // pre-assigned primary key survives into the DB. Legacy frontend flows
        // leave this null → Postgres gen_random_uuid() default.
        String providedToolId = toolData.path("id").asText(null);
        if (providedToolId != null && !providedToolId.isBlank()) {
            try {
                tool.setId(UUID.fromString(providedToolId));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Tool 'id' must be a valid UUID, got '" + providedToolId
                                + "' (tool name: '" + toolData.path("name").asText("?") + "')",
                        e);
            }
        }
        tool.setApiId(api.getId());

        // Basic information
        // tool.setName() removed - name is now stored in tool_names table
        tool.setDescription(toolData.path("description").asText());
        String protocol = toolData.path("protocol").asText("HTTP");
        tool.setProtocol(protocol.isBlank() ? "HTTP" : protocol);

        // HTTP Configuration
        String methodStr = toolData.path("method").asText("GET");
        tool.setMethod(methodStr);
        tool.setEndpoint(toolData.path("endpoint").asText());
        JsonNode defaultHeadersNode = toolData.path("defaultHeaders");
        if (defaultHeadersNode != null && !defaultHeadersNode.isMissingNode() && !defaultHeadersNode.isNull() && defaultHeadersNode.size() > 0) {
            tool.setDefaultHeaders(defaultHeadersNode.toString());
        } else {
            tool.setDefaultHeaders("{}");
        }
        JsonNode runtimeMetadataNode = toolData.path("runtimeMetadata");
        if (runtimeMetadataNode != null && !runtimeMetadataNode.isMissingNode() && !runtimeMetadataNode.isNull()) {
            tool.setRuntimeMetadata(runtimeMetadataNode.toString());
        } else {
            tool.setRuntimeMetadata(null);
        }

        // Typed-execution refactor (V52): persist execution_spec, output_schema, execution_mode.
        // Source of truth for ToolExecutionOrchestrator routing AND for the dynamic
        // CatalogToolNodeSpec registration that exposes MCPs in /api/node-definitions.
        JsonNode executionSpecNode = toolData.path("executionSpec");
        if (executionSpecNode != null && !executionSpecNode.isMissingNode() && !executionSpecNode.isNull()) {
            tool.setExecutionSpec(executionSpecNode.toString());
        } else {
            tool.setExecutionSpec(null);
        }
        JsonNode outputSchemaNode = toolData.path("outputSchema");
        if (outputSchemaNode != null && !outputSchemaNode.isMissingNode() && !outputSchemaNode.isNull()) {
            // V52 hardening: validate that outputSchema is a JSON array (OutputFieldDef[]).
            // A malformed schema would otherwise reach the runtime OutputProjector and
            // silently passthrough - better to fail at submission with an actionable error.
            if (!outputSchemaNode.isArray()) {
                throw new IllegalArgumentException(
                        "tool.outputSchema must be a JSON array of OutputFieldDef entries (got " +
                                outputSchemaNode.getNodeType() + " for tool '" +
                                toolData.path("name").asText("?") + "')");
            }
            tool.setOutputSchema(outputSchemaNode.toString());
        } else {
            tool.setOutputSchema(null);
        }

        // V166: per-endpoint OAuth scope requirements. Always write (null when absent
        // or empty) so a re-import after a JSON edit clears stale data on the row.
        JsonNode requiredScopesNode = toolData.path("requiredScopes");
        if (requiredScopesNode != null && !requiredScopesNode.isMissingNode() && !requiredScopesNode.isNull()
                && requiredScopesNode.isArray() && requiredScopesNode.size() > 0) {
            List<String> scopes = new ArrayList<>();
            for (JsonNode s : requiredScopesNode) {
                if (!s.isTextual() || s.asText().isBlank()) {
                    throw new IllegalArgumentException(
                            "tool.requiredScopes must be an array of non-blank strings (tool '" +
                                    toolData.path("name").asText("?") + "')");
                }
                scopes.add(s.asText());
            }
            tool.setRequiredScopes(scopes);
        } else {
            tool.setRequiredScopes(null);
        }

        // V52 hardening: derive execution_mode from execution_spec.mode as the single source
        // of truth. The flat 'executionMode' key is only used as a fallback for back-compat
        // with payloads that don't carry the full executionSpec yet (handoff in progress).
        String executionMode;
        if (executionSpecNode != null && !executionSpecNode.isMissingNode() && !executionSpecNode.isNull()) {
            executionMode = executionSpecNode.path("mode").asText("");
            if (executionMode.isBlank()) {
                // executionSpec present but no mode field - fallback to flat key, then to "sync".
                executionMode = toolData.path("executionMode").asText("sync");
            }
        } else {
            executionMode = toolData.path("executionMode").asText("sync");
        }
        if (executionMode.isBlank()) {
            executionMode = "sync";
        }
        // Validate against the V145 CHECK constraint enum so we surface a 400 instead of a
        // SQL exception when something is wrong upstream. Aligns with HttpExecutionService
        // and validate_apis.py.
        if (!"sync".equals(executionMode)
                && !"async_poll".equals(executionMode)
                && !"upload".equals(executionMode)
                && !"streaming".equals(executionMode)) {
            throw new IllegalArgumentException(
                    "tool.execution.mode must be one of [sync, async_poll, upload, streaming] " +
                            "(got '" + executionMode + "' for tool '" + toolData.path("name").asText("?") + "')");
        }
        tool.setExecutionMode(executionMode);

        // V145 - bodyType-specific contract validation. The runtime encoders rely on
        // these sub-blocks, so reject at submission rather than failing silently at
        // execute time. Aligns with the same checks in validate_apis.py.
        if (executionSpecNode != null && executionSpecNode.isObject()) {
            JsonNode requestNode = executionSpecNode.path("request");
            String bodyType = requestNode.path("bodyType").asText("json");
            String toolName = toolData.path("name").asText("?");
            if ("graphql".equals(bodyType)) {
                JsonNode graphqlNode = requestNode.path("graphql");
                String query = graphqlNode.path("query").asText("");
                if (query.isBlank()) {
                    throw new IllegalArgumentException(
                            "tool.execution.request.graphql.query is required when bodyType=graphql " +
                                    "(tool '" + toolName + "')");
                }
            } else if ("multipart".equals(bodyType)) {
                JsonNode multipartFields = requestNode.path("multipartFields");
                if (!multipartFields.isArray() || multipartFields.size() == 0) {
                    throw new IllegalArgumentException(
                            "tool.execution.request.multipartFields must be a non-empty array " +
                                    "when bodyType=multipart (tool '" + toolName + "')");
                }
            }
        }

        // V83: pagination config (JSONB)
        JsonNode paginationNode = toolData.path("pagination");
        if (paginationNode != null && !paginationNode.isMissingNode() && !paginationNode.isNull() && paginationNode.isObject()) {
            tool.setPagination(paginationNode.toString());
        }
        // V83: next hint (text)
        String nextHint = toolData.path("nextHint").asText(null);
        if (nextHint != null && !nextHint.isBlank()) {
            tool.setNextHint(nextHint);
        }

        // Manage custom categories and tool names with subcategory_id
        processCustomToolData(toolData, tool, api.getSubcategoryId());

        // Default status
        tool.setStatus(ApiEntity.ApiStatus.REVIEWING.name()); // Set default status
        tool.setTestStatus("PENDING");
        tool.setIsActive(true);

        // Timestamps
        long currentTime = System.currentTimeMillis();
        tool.setCreatedAt(currentTime);
        tool.setUpdatedAt(currentTime);

        return tool;
    }

    /**
     * Traite les categories et noms d'outils personnalises
     */
    private void processCustomToolData(JsonNode toolData, ApiToolEntity tool, UUID subcategoryId) {
        boolean isCustomCategory = toolData.path("isCustomCategory").asBoolean(false);
        boolean isCustomToolName = toolData.path("isCustomToolName").asBoolean(false);

        String toolName = toolData.path("name").asText();
        String toolCategoryName = toolData.path("toolCategory").asText();

        // Debug logs
        log.info("Processing tool: {}", toolName);
        log.info("isCustomCategory: {}, isCustomToolName: {}", isCustomCategory, isCustomToolName);
        log.info("toolNameId from data: '{}'", toolData.path("toolNameId").asText());
        log.info("subcategoryId from API: {}", subcategoryId);

        // Determine tool category
        String categoryId = null;
        String toolCategoryDescription = toolData.path("toolCategoryDescription").asText("");
        String toolCategoryIconUrl = toolData.path("toolCategoryIconUrl").asText("");
        if (isCustomCategory && toolCategoryName != null && !toolCategoryName.isEmpty()) {
            categoryId = createOrFindToolCategory(toolCategoryName, toolCategoryDescription, toolCategoryIconUrl);
        } else if (toolCategoryName != null && !toolCategoryName.isEmpty()) {
            // Search category by name
            categoryId = createOrFindToolCategory(toolCategoryName, toolCategoryDescription, toolCategoryIconUrl);
        }
        log.info("Tool category resolved to id: {} for tool '{}'", categoryId, toolName);

        // Process tool name
        if (isCustomToolName && toolName != null && !toolName.isEmpty()) {
            // Create or find a ToolNameEntity
            log.info("Creating/searching for tool name: {}", toolName);
            String toolNameId = createOrFindToolName(toolName, tool.getDescription(), categoryId, subcategoryId);
            if (toolNameId != null) {
                tool.setToolNameId(toolNameId);
                log.info("ToolNameId set (custom): {}", toolNameId);
            }
        } else {
            // Use provided ID or search by name
            String toolNameId = toolData.path("toolNameId").asText();
            if (toolNameId != null && !toolNameId.isEmpty()) {
                // Verify that tool name exists in database
                if (validateToolNameExists(toolNameId)) {
                    // Update existing tool_name with new subcategory_id (independent tx to avoid deadlocks)
                    toolNameSubcategoryUpdater.updateSubcategory(toolNameId, subcategoryId);
                    tool.setToolNameId(toolNameId);
                    log.info("ToolNameId set from data and subcategory updated: {}", toolNameId);
                } else {
                    log.warn("Provided ToolNameId does not exist: {}, creating new tool name", toolNameId);
                    // Create new tool name with previously determined category
                    String foundToolNameId = createOrFindToolName(toolName, tool.getDescription(), categoryId, subcategoryId);
                    if (foundToolNameId != null) {
                        tool.setToolNameId(foundToolNameId);
                        log.info("New ToolNameId created (fallback from missing id): {}", foundToolNameId);
                    }
                }
            } else if (toolName != null && !toolName.isEmpty() && categoryId != null) {
                // Search by name if no ID provided
                String foundToolNameId = createOrFindToolName(toolName, tool.getDescription(), categoryId, subcategoryId);
                if (foundToolNameId != null) {
                    tool.setToolNameId(foundToolNameId);
                    log.info("ToolNameId found by name: {}", foundToolNameId);
                }
            }
        }
        log.info("ToolNameId resolved for tool '{}': {}", toolName, tool.getToolNameId());
    }

    /**
     * Creates or finds a tool category.
     * Handles race conditions by catching duplicate key exceptions and fetching existing record.
     */
    private String createOrFindToolCategory(String categoryName, String description, String iconUrl) {
        String categorySlug = SlugUtils.generateSlug(categoryName);

        // First, try to find existing category by slug or name
        try {
            Optional<ToolCategoryEntity> existingCategory = toolCategoryService.getToolCategoryBySlug(categorySlug);
            if (existingCategory.isPresent()) {
                log.debug("Tool category found by slug: {} with ID: {}", categoryName, existingCategory.get().getId());
                updateToolCategoryIcon(existingCategory.get().getId(), iconUrl);
                return existingCategory.get().getId().toString();
            }
            // Also check by name in case slug differs
            existingCategory = toolCategoryService.getToolCategoryByName(categoryName);
            if (existingCategory.isPresent()) {
                log.debug("Tool category found by name: {} with ID: {}", categoryName, existingCategory.get().getId());
                updateToolCategoryIcon(existingCategory.get().getId(), iconUrl);
                return existingCategory.get().getId().toString();
            }
        } catch (Exception e) {
            log.debug("Error checking existing tool category: {}", e.getMessage());
        }

        // Try to insert with ON CONFLICT to handle parallel race conditions gracefully
        UUID categoryId = UUID.randomUUID();
        long currentTime = System.currentTimeMillis();

        try {
            String insertSql = """
                INSERT INTO tool_categories (id, name, description, icon, color, sort_order, slug, icon_url, is_active, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (name) DO NOTHING
                """;
            int rowsInserted = jdbcTemplate.update(insertSql,
                categoryId,
                categoryName,
                resolveDescription(description, categoryName),
                "🔧",
                "#6B7280",
                999,
                categorySlug,
                iconUrl,
                true,
                currentTime,
                currentTime
            );

            if (rowsInserted > 0) {
                log.info("New tool category created: {} with ID: {}", categoryName, categoryId);
                return categoryId.toString();
            }

            // Conflict occurred - fetch existing
            log.debug("Tool category '{}' already exists (ON CONFLICT), fetching existing", categoryName);
            Optional<ToolCategoryEntity> conflictCategory = toolCategoryService.getToolCategoryBySlug(categorySlug);
            if (conflictCategory.isEmpty()) {
                conflictCategory = toolCategoryService.getToolCategoryByName(categoryName);
            }
            if (conflictCategory.isPresent()) {
                updateToolCategoryIcon(conflictCategory.get().getId(), iconUrl);
                return conflictCategory.get().getId().toString();
            }

        } catch (Exception e) {
            log.error("Error creating tool category: {}", categoryName, e);
        }

        log.warn("Could not find or create tool category: {}", categoryName);
        return null;
    }

    /**
     * Creates or finds a tool name.
     * Note: method and endpoint_pattern are no longer stored in ToolNameEntity - they're in ApiToolEntity only.
     * The DB columns still exist but are set to defaults ("GET", "/") for backwards compatibility.
     *
     * Handles race conditions in parallel imports by using ON CONFLICT and fetching existing records.
     */
    private String createOrFindToolName(String toolName, String description, String categoryId, UUID subcategoryId) {
        try {
            if (categoryId == null) {
                log.warn("CategoryId is null, cannot create/search tool name: {}", toolName);
                return null;
            }

            UUID categoryUuid = UUID.fromString(categoryId);

            // First search if tool name exists with same tool_category_id (unique constraint is name + tool_category_id)
            List<ToolNameEntity> candidates = toolCategoryService.findToolNamesByName(toolName);
            if (!candidates.isEmpty()) {
                // Check by tool_category_id (matches the DB unique constraint)
                Optional<ToolNameEntity> exactMatch = candidates.stream()
                        .filter(t -> categoryUuid.equals(t.getToolCategoryId()))
                        .findFirst();
                if (exactMatch.isPresent()) {
                    ToolNameEntity existing = exactMatch.get();
                    // Update subcategory_id if different (independent tx to avoid deadlocks)
                    if (subcategoryId != null && !subcategoryId.equals(existing.getSubcategoryId())) {
                        toolNameSubcategoryUpdater.updateSubcategory(existing.getId().toString(), subcategoryId);
                    }
                    log.info("Tool name reused: {} with ID: {}", toolName, existing.getId());
                    return existing.getId().toString();
                }
            }

            // Try to create new tool name with ON CONFLICT to handle race conditions
            UUID toolNameId = UUID.randomUUID();
            long currentTime = System.currentTimeMillis();
            String baseSlug = SlugUtils.generateSlug(toolName);

            // Ensure slug uniqueness: uk_tool_names_slug is a global unique constraint.
            // Same tool name in different categories would have the same slug, so append
            // a category-based suffix when the base slug already exists.
            String slug = baseSlug;
            try {
                Integer slugCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tool_names WHERE slug = ?", Integer.class, baseSlug);
                if (slugCount != null && slugCount > 0) {
                    String categorySlug = SlugUtils.generateSlug(
                        toolCategoryService.getToolCategoryById(categoryUuid)
                            .map(ToolCategoryEntity::getName).orElse(categoryUuid.toString().substring(0, 8)));
                    slug = baseSlug + "-" + categorySlug;
                    // If that also exists, add a short UUID suffix
                    Integer slug2Count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM tool_names WHERE slug = ?", Integer.class, slug);
                    if (slug2Count != null && slug2Count > 0) {
                        slug = baseSlug + "-" + toolNameId.toString().substring(0, 8);
                    }
                }
            } catch (Exception slugEx) {
                log.debug("Slug uniqueness check failed, using base slug: {}", slugEx.getMessage());
            }

            String insertSql = """
                INSERT INTO tool_names (id, name, description, tool_category_id, subcategory_id, method, endpoint_pattern, slug, is_active, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (name, tool_category_id) DO NOTHING
                """;
            int rowsInserted = jdbcTemplate.update(insertSql,
                toolNameId,
                toolName,
                description != null ? description : "Tool created automatically",
                categoryUuid,
                subcategoryId,
                "GET",  // deprecated - method is now in ApiToolEntity
                "/",    // deprecated - endpoint is now in ApiToolEntity
                slug,
                true,
                currentTime,
                currentTime
            );

            if (rowsInserted > 0) {
                log.info("New tool name created: {} with ID: {} and subcategory_id: {}", toolName, toolNameId, subcategoryId);
                return toolNameId.toString();
            }

            // Conflict occurred - another thread created it, fetch the existing one
            log.debug("Tool name conflict for '{}', fetching existing record", toolName);
            List<ToolNameEntity> refreshedCandidates = toolCategoryService.findToolNamesByName(toolName);
            Optional<ToolNameEntity> conflictMatch = refreshedCandidates.stream()
                    .filter(t -> categoryUuid.equals(t.getToolCategoryId()))
                    .findFirst();
            if (conflictMatch.isPresent()) {
                ToolNameEntity existing = conflictMatch.get();
                // Update subcategory_id if needed (independent tx to avoid deadlocks)
                if (subcategoryId != null && !subcategoryId.equals(existing.getSubcategoryId())) {
                    toolNameSubcategoryUpdater.updateSubcategory(existing.getId().toString(), subcategoryId);
                }
                log.info("Tool name found after conflict: {} with ID: {}", toolName, existing.getId());
                return existing.getId().toString();
            }

            log.warn("Could not find or create tool name: {}", toolName);
            return null;

        } catch (Exception e) {
            log.error("Error creating/searching tool name: {}", toolName, e);
            return null;
        }
    }


    /**
     * Checks if a tool category exists in the database
     */
    private boolean validateToolCategoryExists(String categoryId) {
        try {
            UUID categoryUuid = UUID.fromString(categoryId);
            Optional<ToolCategoryEntity> category = toolCategoryService.getToolCategoryById(categoryUuid);
            return category.isPresent();
        } catch (Exception e) {
            log.warn("Error validating tool category: {}", categoryId, e);
            return false;
        }
    }

    /**
     * Checks if a tool name exists in the database.
     * Returns false if toolNameId is not a valid UUID (e.g., when it's a slug like "baichuan-chat-completions")
     */
    private boolean validateToolNameExists(String toolNameId) {
        if (toolNameId == null || toolNameId.isBlank()) {
            return false;
        }
        // Check if it's a valid UUID format before attempting parse
        if (!isValidUuid(toolNameId)) {
            log.debug("toolNameId '{}' is not a valid UUID, will create new tool name", toolNameId);
            return false;
        }
        try {
            UUID toolNameUuid = UUID.fromString(toolNameId);
            Optional<ToolNameEntity> toolName = toolCategoryService.getToolNameById(toolNameUuid);
            return toolName.isPresent();
        } catch (Exception e) {
            log.debug("Tool name not found for id: {}", toolNameId);
            return false;
        }
    }

    /**
     * Check if string is a valid UUID format
     */
    private boolean isValidUuid(String str) {
        if (str == null || str.length() != 36) {
            return false;
        }
        try {
            UUID.fromString(str);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Creates a custom tool category (legacy method - to be removed)
     */
    private ToolCategoryEntity createCustomToolCategory(String categoryName) {
        try {
            // Convert category name to slug and search by slug
            String categorySlug = SlugUtils.generateSlug(categoryName);
            Optional<ToolCategoryEntity> existingCategory = toolCategoryRepository.findBySlug(categorySlug);
            if (existingCategory.isPresent()) {
                log.info("Category '{}' already exists, using existing ID", categoryName);
                return existingCategory.get();
            }

            ToolCategoryEntity category = new ToolCategoryEntity();
            // Don't set ID - let Spring Data JDBC handle it
            // category.setId(UUID.randomUUID());
            category.setName(categoryName);
            category.setDescription("Custom category: " + categoryName);
            category.setIcon("custom");
            category.setColor("#FF6B6B");
            category.setSortOrder(999);
            category.setIsActive(true);

            // Generate unique slug
            List<String> existingSlugs = StreamSupport.stream(toolCategoryRepository.findAll().spliterator(), false)
                    .map(ToolCategoryEntity::getSlug)
                    .filter(slug -> slug != null && !slug.isEmpty())
                    .collect(java.util.stream.Collectors.toList());
            String slug = SlugUtils.generateToolCategorySlug(categoryName, existingSlugs);
            category.setSlug(slug);

            long currentTime = System.currentTimeMillis();
            category.setCreatedAt(currentTime);
            category.setUpdatedAt(currentTime);

            return toolCategoryRepository.save(category);
        } catch (Exception e) {
            log.error("Error creating custom category '{}': {}", categoryName, e.getMessage());
            return null;
        }
    }

    /**
     * Creates a custom tool name.
     * Note: method and endpoint are no longer stored in ToolNameEntity - they're in ApiToolEntity only.
     */
    private ToolNameEntity createCustomToolName(String toolName, String description, String categoryId) {
        try {
            // Convert tool name to slug and search by slug
            String toolSlug = SlugUtils.generateSlug(toolName);
            Optional<ToolNameEntity> existingToolName = toolNameRepository.findBySlugAndToolCategoryId(toolSlug, UUID.fromString(categoryId));
            if (existingToolName.isPresent()) {
                log.info("Tool name '{}' already exists in category, using existing ID", toolName);
                return existingToolName.get();
            }

            ToolNameEntity toolNameEntity = new ToolNameEntity();

            // Don't set ID - let Spring Data JDBC handle it
            // toolNameEntity.setId(UUID.randomUUID());
            toolNameEntity.setName(toolName);
            toolNameEntity.setDescription(description != null ? description : "Custom tool: " + toolName);
            toolNameEntity.setToolCategoryId(UUID.fromString(categoryId));
            toolNameEntity.setIsActive(true);
            // method and endpointPattern removed - now stored only in ApiToolEntity

            // Generate unique slug using prefix query instead of full table scan
            String baseSlug = SlugUtils.generateSlug(toolName);
            List<String> matchingSlugs = toolNameRepository.findSlugsByPrefix(baseSlug);
            String slug = SlugUtils.generateToolNameSlug(toolName, matchingSlugs);
            toolNameEntity.setSlug(slug);

            long currentTime = System.currentTimeMillis();
            toolNameEntity.setCreatedAt(currentTime);
            toolNameEntity.setUpdatedAt(currentTime);

            return toolNameRepository.save(toolNameEntity);
        } catch (Exception e) {
            log.error("Error creating custom tool name '{}': {}", toolName, e.getMessage());
            return null;
        }
    }

    /**
     * Creates monetization configurations from API JSON data
     */



    private UUID findCategoryIdByName(String categoryName) {
        try {
            String categorySlug = SlugUtils.generateSlug(categoryName);
            return categoryRepository.findBySlug(categorySlug)
                    .map(ApiCategoryEntity::getId)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("Error searching for category '{}': {}", categoryName, e.getMessage());
            return null;
        }
    }

    private UUID findSubcategoryIdByName(String subcategoryName, UUID categoryId) {
        String subcategorySlug = SlugUtils.generateSlug(subcategoryName);
        Optional<ApiSubcategoryEntity> subcategory = subcategoryRepository.findBySlugAndCategoryId(subcategorySlug, categoryId);
        return subcategory.map(ApiSubcategoryEntity::getId).orElse(null);
    }

    private UUID createCategory(String categoryName, String description) {
        log.info("Searching/creating category: {}", categoryName);
        try {
            String categorySlug = SlugUtils.generateSlug(categoryName);
            Optional<ApiCategoryEntity> existingCategory = categoryRepository.findBySlug(categorySlug);
            if (existingCategory.isPresent()) {
                UUID existingId = existingCategory.get().getId();
                updateCategoryDescription(existingId, description, categoryName);
                log.info("Existing category found by slug: {} (ID: {})", categoryName, existingId);
                return existingId;
            }
            Optional<ApiCategoryEntity> existingByName = categoryRepository.findByName(categoryName);
            if (existingByName.isPresent()) {
                UUID existingId = existingByName.get().getId();
                updateCategoryDescription(existingId, description, categoryName);
                log.info("Existing category found by name: {} (ID: {})", categoryName, existingId);
                return existingId;
            }
        } catch (Exception e) {
            log.warn("Error searching for category '{}': {}", categoryName, e.getMessage());
        }

        // Use INSERT ON CONFLICT to handle race conditions in parallel imports
        UUID newCategoryId = UUID.randomUUID();
        String categorySlug = SlugUtils.generateSlug(categoryName);
        long currentTime = System.currentTimeMillis();

        String insertSql = """
            INSERT INTO api_categories (id, name, description, icon, color, sort_order, slug, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (name) DO NOTHING
            """;
        int rowsInserted = jdbcTemplate.update(insertSql,
                newCategoryId,
                categoryName,
                resolveDescription(description, categoryName),
                "📁",
                "#3B82F6",
                999,
                categorySlug,
                currentTime,
                currentTime);

        if (rowsInserted > 0) {
            log.info("Category created successfully: {} (ID: {})", categoryName, newCategoryId);
            return newCategoryId;
        }

        // If conflict, fetch the existing one
        Optional<ApiCategoryEntity> conflictCategory = categoryRepository.findBySlug(categorySlug);
        if (conflictCategory.isEmpty()) {
            conflictCategory = categoryRepository.findByName(categoryName);
        }
        if (conflictCategory.isPresent()) {
            UUID existingId = conflictCategory.get().getId();
            log.info("Category found after conflict: {} (ID: {})", categoryName, existingId);
            return existingId;
        }

        log.warn("Could not find or create category: {}", categoryName);
        return null;
    }

    private void updateCategoryDescription(UUID categoryId, String description, String fallbackName) {
        if (categoryId == null) {
            return;
        }
        String resolved = resolveDescription(description, fallbackName);
        if (resolved == null || resolved.isBlank()) {
            return;
        }
        jdbcTemplate.update("UPDATE api_categories SET description = ?, updated_at = ? WHERE id = ?",
                resolved,
                System.currentTimeMillis(),
                categoryId);
    }

    private UUID createSubcategory(String subcategoryName, UUID categoryId, String description, String iconUrl) {
        if (categoryId == null) {
            log.error("CategoryId is null, cannot create subcategory: {}", subcategoryName);
            throw new IllegalArgumentException("CategoryId cannot be null to create a subcategory");
        }

        log.info("Searching/creating subcategory: {} for category {}", subcategoryName, categoryId);
        ApiCategoryEntity categoryEntity = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new IllegalArgumentException("Category not found for ID " + categoryId));
        String categorySlug = categoryEntity.getSlug();
        if (categorySlug == null || categorySlug.isBlank()) {
            categorySlug = SlugUtils.generateSlug(categoryEntity.getName());
        }
        String legacySubSlug = SlugUtils.generateSlug(subcategoryName);
        String requestedSlug = buildCategoryAwareSubcategorySlug(categorySlug, legacySubSlug);
        String resolvedSlug = requestedSlug;
        try {
            Optional<ApiSubcategoryEntity> existingSubcategory = subcategoryRepository.findBySlugAndCategoryId(requestedSlug, categoryId);
            if (existingSubcategory.isEmpty() && !legacySubSlug.equals(requestedSlug)) {
                existingSubcategory = subcategoryRepository.findBySlugAndCategoryId(legacySubSlug, categoryId);
            }
            if (existingSubcategory.isPresent()) {
                ApiSubcategoryEntity entity = existingSubcategory.get();
                updateSubcategoryMetadata(entity, subcategoryName, description, iconUrl);
                log.info("Existing subcategory found by slug: {} (ID: {})", subcategoryName, entity.getId());
                return entity.getId();
            }

            Optional<ApiSubcategoryEntity> existingByName = subcategoryRepository.findByNameAndCategoryId(subcategoryName, categoryId);
            if (existingByName.isPresent()) {
                ApiSubcategoryEntity entity = existingByName.get();
                updateSubcategoryMetadata(entity, subcategoryName, description, iconUrl);
                log.info("Existing subcategory found by name: {} (ID: {})", subcategoryName, entity.getId());
                return entity.getId();
            }

            resolvedSlug = generateUniqueSubcategorySlug(requestedSlug, categoryId);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Error searching for subcategory '{}': {}", subcategoryName, e.getMessage());
        }

        // Use INSERT ON CONFLICT to handle race conditions in parallel imports
        UUID newSubcategoryId = UUID.randomUUID();
        long currentTime = System.currentTimeMillis();

        String insertSql = """
            INSERT INTO api_subcategories (id, category_id, name, description, icon, color, sort_order, slug, icon_url, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (slug) DO NOTHING
            """;
        int rowsInserted = jdbcTemplate.update(insertSql,
                newSubcategoryId,
                categoryId,
                subcategoryName,
                resolveDescription(description, subcategoryName),
                "🔧",
                "#999999",
                999,
                resolvedSlug,
                iconUrl,
                currentTime,
                currentTime);

        if (rowsInserted > 0) {
            log.info("Subcategory created successfully: {} (ID: {})", subcategoryName, newSubcategoryId);
            return newSubcategoryId;
        }

        // If conflict, fetch the existing one
        Optional<ApiSubcategoryEntity> conflictSubcategory = subcategoryRepository.findBySlug(resolvedSlug);
        if (conflictSubcategory.isEmpty()) {
            conflictSubcategory = subcategoryRepository.findByNameAndCategoryId(subcategoryName, categoryId);
        }
        if (conflictSubcategory.isPresent()) {
            UUID existingId = conflictSubcategory.get().getId();
            log.info("Subcategory found after conflict: {} (ID: {})", subcategoryName, existingId);
            return existingId;
        }

        log.warn("Could not find or create subcategory: {}", subcategoryName);
        return null;
    }

    private String buildCategoryAwareSubcategorySlug(String categorySlug, String subSlug) {
        if (categorySlug == null || categorySlug.isBlank()) {
            return subSlug;
        }
        if (subSlug == null || subSlug.isBlank()) {
            return categorySlug;
        }
        return categorySlug + "-" + subSlug;
    }

    private String generateUniqueSubcategorySlug(String desiredSlug, UUID categoryId) {
        String candidate = desiredSlug;
        int attempt = 1;
        while (true) {
            Optional<ApiSubcategoryEntity> existing = subcategoryRepository.findBySlug(candidate);
            if (existing.isEmpty()) {
                return candidate;
            }
            if (existing.get().getCategoryId().equals(categoryId)) {
                return candidate;
            }
            candidate = desiredSlug + "-" + attempt;
            attempt += 1;
        }
    }

    private void updateSubcategoryMetadata(ApiSubcategoryEntity subcategory,
                                           String fallbackName,
                                           String description,
                                           String iconUrl) {
        if (subcategory == null) {
            return;
        }
        List<String> updates = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        String resolvedDescription = resolveDescription(description, fallbackName);
        if (resolvedDescription != null && !resolvedDescription.isBlank()) {
            updates.add("description = ?");
            params.add(resolvedDescription);
        }
        if (iconUrl != null && !iconUrl.isBlank()) {
            updates.add("icon_url = ?");
            params.add(iconUrl);
        }
        updates.add("updated_at = ?");
        params.add(System.currentTimeMillis());
        params.add(subcategory.getId());

        String sql = "UPDATE api_subcategories SET " + String.join(", ", updates) + " WHERE id = ?";
        jdbcTemplate.update(sql, params.toArray());
    }

    private String resolveToolName(ApiToolEntity tool) {
        if (tool.getToolNameId() == null || tool.getToolNameId().trim().isEmpty()) {
            return "Unknown Tool";
        }
        return toolCategoryService.getToolNameByToolNameId(tool.getToolNameId())
                .map(ToolNameEntity::getName)
                .orElse("Unknown Tool");
    }

    private String resolveDescription(String provided, String fallbackName) {
        if (provided != null && !provided.isBlank()) {
            return provided;
        }
        return "Tags: " + fallbackName;
    }

    private String fallbackDescription(String provided, String fallbackName) {
        if (provided != null && !provided.isBlank()) {
            return provided;
        }
        return fallbackName;
    }

    private void updateSubcategoryIcon(UUID subcategoryId, String iconUrl) {
        if (subcategoryId == null || iconUrl == null || iconUrl.isBlank()) {
            return;
        }
        jdbcTemplate.update("UPDATE api_subcategories SET icon_url = ? WHERE id = ?", iconUrl, subcategoryId);
    }

    private void updateToolCategoryIcon(UUID categoryId, String iconUrl) {
        if (categoryId == null || iconUrl == null || iconUrl.isBlank()) {
            return;
        }
        jdbcTemplate.update("UPDATE tool_categories SET icon_url = ? WHERE id = ?", iconUrl, categoryId);
    }

    /**
     * Map derived icon slug to known SVG icon names.
     * Handles cases where the API name doesn't match the available SVG file.
     */

    /**
     * Génère et définit le slug d'un tool
     */
    private void generateAndSetToolSlug(ApiToolEntity tool, String apiSlug) {
        if (tool.getToolSlug() != null && !tool.getToolSlug().trim().isEmpty()) {
            return; // Slug already exists
        }
        
        if (apiSlug == null || apiSlug.trim().isEmpty()) {
            apiSlug = "api";
        }
        
        // Get tool name from tool_names table
        String toolName = "unknown-tool";
        if (tool.getToolNameId() != null) {
            try {
                UUID toolNameUuid = UUID.fromString(tool.getToolNameId());
                Optional<ToolNameEntity> toolNameOpt = toolNameRepository.findById(toolNameUuid);
                if (toolNameOpt.isPresent()) {
                    toolName = toolNameOpt.get().getName();
                }
            } catch (Exception e) {
                log.warn("Error fetching tool name for slug generation: {}", e.getMessage());
            }
        }
        
        // Generate slug: apiSlug-toolNameSlug
        String toolNameSlug = SlugUtils.generateSlug(toolName);
        String baseSlug = apiSlug + "-" + toolNameSlug;
        String uniqueSlug = ensureUniqueToolSlug(tool.getApiId(), baseSlug, tool.getId());
        
        tool.setToolSlug(uniqueSlug);
        apiToolRepository.save(tool);
        log.info("Generated and saved tool slug: {} for tool: {} (ID: {})", uniqueSlug, toolName, tool.getId());
    }

    /**
     * Assure l'unicité du slug du tool en ajoutant un suffixe numérique si nécessaire
     */
    private String ensureUniqueToolSlug(UUID apiId, String baseSlug, UUID toolId) {
        // Récupérer tous les slugs existants pour cette API
        String sql = "SELECT tool_slug FROM api_tools WHERE api_id = ? AND tool_slug IS NOT NULL AND tool_slug != '' AND id != ?";
        List<String> existingSlugs = jdbcTemplate.queryForList(sql, String.class, apiId, toolId);
        
        // Si le slug n'existe pas déjà, on le retourne tel quel
        if (!existingSlugs.contains(baseSlug)) {
            return baseSlug;
        }
        
        // Sinon, on ajoute un suffixe numérique
        int counter = 1;
        String uniqueSlug = baseSlug + "-" + counter;
        
        while (existingSlugs.contains(uniqueSlug)) {
            counter++;
            uniqueSlug = baseSlug + "-" + counter;
        }
        
        return uniqueSlug;
    }
}
