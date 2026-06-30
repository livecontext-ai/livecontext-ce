package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiToolParameterEntity;
import com.apimarketplace.catalog.domain.ToolNameEntity;
import com.apimarketplace.catalog.dto.*;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.repository.ToolNameRepository;
import com.apimarketplace.catalog.util.SlugUtils;
import com.apimarketplace.catalog.util.AllowedValuesParser;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.PlatformCredentialStatusDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service optimisé pour l'extraction des données API/Tool pour le workflow inspector
 * Utilise des requêtes SQL optimisées avec jointures pour éviter les N+1 queries
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowInspectorService {

    private final ApiRepository apiRepository;
    private final ApiToolRepository apiToolRepository;
    private final ApiToolParameterRepository apiToolParameterRepository;
    private final ToolNameRepository toolNameRepository;
    private final JdbcTemplate jdbcTemplate;
    private final CredentialClient credentialClient;

    /**
     * Canonical UUID shape. A workflow plan may reference a tool either by its
     * {@code apiSlug/toolSlug} (the last segment matches {@code api_tools.tool_slug})
     * OR by its {@code api_tools.id} UUID - both are legitimate references the
     * orchestrator resolves at execution time, and the single-tool
     * {@link #getToolDetailBySlug} endpoint already accepts either. Used by
     * {@link #getToolsBatch} to route each identifier to the right column.
     */
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * Build the set of {@code integrationName::variant} keys an admin has explicitly
     * disabled via {@code auth.platform_credentials.is_enabled = false} AND for which
     * a secret has actually been saved. Mirror of the helper in
     * {@code CredentialTemplateController} - keep the two in sync. Fail-open:
     * a failure to reach auth-service returns an empty set, so the inspector keeps
     * showing every variant rather than blanking the whole node's credential picker.
     *
     * <p>The {@code dto.isConfigured()} gate filters out phantom placeholder rows
     * synthesized by {@code PlatformCredentialRepository.setEnabledForVariant} when an
     * admin toggles a variant off before saving secrets - same defense-in-depth as the
     * sibling helper in {@code CredentialTemplateController}. Without this gate, the
     * May 2026 Salesforce-style incident would reproduce on the workflow-builder
     * inspector side (81 OAuth2 integrations silently hidden from node credential pickers).
     */
    private Set<String> fetchDisabledVariantKeys() {
        try {
            List<PlatformCredentialStatusDto> all = credentialClient.listPlatformCredentials();
            Set<String> keys = new HashSet<>();
            for (PlatformCredentialStatusDto dto : all) {
                if (Boolean.FALSE.equals(dto.getEnabled())
                        && dto.getName() != null
                        && dto.getVariant() != null
                        && dto.isConfigured()) {
                    keys.add(dto.getName() + "::" + dto.getVariant());
                }
            }
            return keys;
        } catch (Exception e) {
            log.warn("Failed to fetch platform credential status for variant filtering in inspector; falling back to 'show all': {}",
                    e.getMessage());
            return Set.of();
        }
    }

    /**
     * Drop rows whose {@code (credential_name, variant)} pair has been admin-disabled,
     * then collapse the remaining rows to one entry per {@code credential_name}. The
     * inspector panel shows one configure button per integration - the wizard itself
     * handles per-variant selection via {@code /credentials/{name}/variants}, which
     * already filters disabled variants. Preserves input order.
     */
    private List<Map<String, Object>> filterAndDedupe(List<Map<String, Object>> rows, Set<String> disabledKeys) {
        if (rows.isEmpty()) {
            return rows;
        }
        Map<String, Map<String, Object>> byCredentialName = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String credentialName = Objects.toString(row.get("credential_name"), "");
            String variant = Objects.toString(row.get("variant"), "");
            if (!disabledKeys.isEmpty() && disabledKeys.contains(credentialName + "::" + variant)) {
                continue;
            }
            byCredentialName.putIfAbsent(credentialName, row);
        }
        return new ArrayList<>(byCredentialName.values());
    }

    /**
     * Récupère toutes les APIs avec leurs tools (uniquement les champs nécessaires)
     * Utilise des requêtes groupées pour éviter les N+1 queries
     */
    public List<WorkflowApiDTO> getAllApisForWorkflow(String nameFilter) {
        log.info("Fetching APIs for workflow inspector with filter: {}", nameFilter);

        // Récupérer les APIs avec leur nombre de tools et l'icon_slug directement depuis apis
        String apiSql = """
            SELECT
                a.api_slug,
                a.api_name,
                a.description,
                COUNT(at.id) as tools_count,
                COALESCE(a.icon_slug, 'mcp') as icon_slug,
                a.icon_url
            FROM apis a
            LEFT JOIN api_tools at ON a.id = at.api_id AND at.is_active = true
            WHERE a.is_active = true
            """;

        List<Object> apiParams = new ArrayList<>();
        if (nameFilter != null && !nameFilter.trim().isEmpty()) {
            apiSql += " AND LOWER(a.api_name) LIKE ?";
            apiParams.add("%" + nameFilter.toLowerCase() + "%");
        }

        apiSql += " GROUP BY a.api_slug, a.api_name, a.description, a.icon_slug, a.icon_url ORDER BY a.api_name";

        List<Map<String, Object>> apiRows = jdbcTemplate.queryForList(apiSql, apiParams.toArray());
        log.info("Total APIs found: {}", apiRows.size());

        // Construire les DTOs (sans charger les tools)
        return apiRows.stream()
            .map(row -> new WorkflowApiDTO(
                (String) row.get("api_slug"),
                (String) row.get("api_name"),
                (String) row.get("description"),
                ((Number) row.get("tools_count")).intValue(),
                (String) row.get("icon_slug"),
                (String) row.get("icon_url")
            ))
            .collect(Collectors.toList());
    }

    /**
     * Récupère les tools d'une API spécifique par son slug (uniquement les champs nécessaires)
     */
    public List<WorkflowToolDTO> getToolsForApi(String apiSlug) {
        log.info("Fetching tools for API slug: {}", apiSlug);
        
        // Récupérer l'API par son slug
        Optional<ApiEntity> apiOpt = apiRepository.findByApiSlug(apiSlug);
        if (apiOpt.isEmpty()) {
            log.warn("API not found for slug: {}", apiSlug);
            return new ArrayList<>();
        }
        
        UUID apiId = apiOpt.get().getId();
        List<ApiToolEntity> tools = apiToolRepository.findByApiIdAndIsActiveTrue(apiId);
        
        // Récupérer tous les tool_name_id uniques
        Set<String> toolNameIds = tools.stream()
            .map(ApiToolEntity::getToolNameId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        // Charger tous les tool names en une seule fois
        Map<String, ToolNameEntity> toolNameMap = new HashMap<>();
        for (String toolNameId : toolNameIds) {
            try {
                UUID toolNameUuid = UUID.fromString(toolNameId);
                toolNameRepository.findById(toolNameUuid)
                    .ifPresent(tn -> toolNameMap.put(toolNameId, tn));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid tool name ID format: {}", toolNameId);
            }
        }

        // Récupérer le slug de l'API (utiliser celui de la base de données, ou le paramètre si null)
        ApiEntity api = apiOpt.get();
        String actualApiSlug = api.getApiSlug();
        if (actualApiSlug == null || actualApiSlug.trim().isEmpty()) {
            log.warn("API slug is null or empty for API ID: {}, using provided slug: {}", apiId, apiSlug);
            actualApiSlug = apiSlug != null && !apiSlug.trim().isEmpty() ? apiSlug : "api";
        }

        // Récupérer l'iconSlug de l'API depuis sa subcategory
        String iconSlug = getApiIconSlug(actualApiSlug).orElse(null);
        String iconUrl = api.getIconUrl();

        // Générer les slugs manquants pour les tools
        List<WorkflowToolDTO> result = new ArrayList<>();
        for (ApiToolEntity tool : tools) {
            // Récupérer le nom du tool depuis tool_names
            String toolName = "Unknown Tool";
            if (tool.getToolNameId() != null) {
                ToolNameEntity toolNameEntity = toolNameMap.get(tool.getToolNameId());
                if (toolNameEntity != null) {
                    toolName = toolNameEntity.getName();
                }
            }

            // Générer le slug du tool : apiSlug-toolNameSlug
            String toolSlug = tool.getToolSlug();
            if (toolSlug == null || toolSlug.trim().isEmpty()) {
                // Générer le slug à partir du nom du tool
                String toolNameSlug = SlugUtils.generateSlug(toolName);
                toolSlug = actualApiSlug + "-" + toolNameSlug;
                
                // Vérifier l'unicité du slug pour cette API
                toolSlug = ensureUniqueToolSlug(apiId, toolSlug, tool.getId());
                
                // Sauvegarder le slug généré
                tool.setToolSlug(toolSlug);
                apiToolRepository.save(tool);
                log.info("Generated and saved tool slug: {} for tool: {} (ID: {})", toolSlug, toolName, tool.getId());
            }

            // V166: surface per-endpoint OAuth scope requirements + unique-per-API
            // integration name so the frontend lock-badge can warn before the user
            // even picks the tool.
            List<String> required = tool.getRequiredScopes();
            String integrationName = api.getPlatformCredentialName();

            result.add(new WorkflowToolDTO(
                toolSlug,
                toolName,
                tool.getDescription(),
                tool.getMethod(),
                actualApiSlug,
                iconSlug,
                iconUrl,
                tool.getId() == null ? null : tool.getId().toString(),
                (required == null || required.isEmpty()) ? null : required,
                (integrationName == null || integrationName.isBlank()) ? null : integrationName
            ));
        }

        return result;
    }

    /**
     * Batch-resolve {@link WorkflowToolDTO} light records for a list of {@code api_tools.id}
     * UUIDs. Single SQL IN query; missing UUIDs are silently omitted from the result (callers
     * can detect missing entries by size or by lookup). Used by the agent-builder backend to
     * normalise UUID tool references to {@code apiSlug:toolSlug} format before persistence,
     * and by the agent-fleet frontend to resolve legacy UUID entries for display.
     *
     * <p>Returns the same shape as {@link #getToolBySlug} (no params/credentials/responses)
     * since the two consumers only need (apiSlug, toolSlug, iconSlug) for display and
     * normalisation.
     */
    @Transactional(readOnly = true)
    public List<WorkflowToolDTO> resolveToolsByIds(List<UUID> toolIds) {
        if (toolIds == null || toolIds.isEmpty()) {
            return List.of();
        }
        List<UUID> uniqueIds = toolIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (uniqueIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(uniqueIds.size(), "?"));
        String sql = String.format("""
            SELECT at.id, at.tool_slug, at.description, at.method, at.tool_name_id,
                   tn.name as tool_name, a.api_slug, COALESCE(a.icon_slug, 'mcp') as icon_slug, a.icon_url
            FROM api_tools at
            LEFT JOIN tool_names tn ON at.tool_name_id = tn.id::text
            JOIN apis a ON at.api_id = a.id
            WHERE at.id IN (%s) AND at.is_active = true AND a.is_active = true
            """, placeholders);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, uniqueIds.toArray());
        List<WorkflowToolDTO> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String toolName = (String) row.get("tool_name");
            if (toolName == null) toolName = "Unknown Tool";
            Object rawId = row.get("id");
            out.add(new WorkflowToolDTO(
                (String) row.get("tool_slug"),
                toolName,
                (String) row.get("description"),
                (String) row.get("method"),
                (String) row.get("api_slug"),
                (String) row.get("icon_slug"),
                (String) row.get("icon_url"),
                rawId == null ? null : rawId.toString(),
                null,  // V166: requiredScopes - not needed for batch ID resolution (display path)
                null   // V166: integrationName - not needed for batch ID resolution (display path)
            ));
        }
        log.info("Resolved {}/{} tool UUIDs to light DTOs", out.size(), uniqueIds.size());
        return out;
    }

    /**
     * Récupère un tool spécifique par son slug avec ses paramètres (uniquement les champs nécessaires)
     */
    public Optional<WorkflowToolDTO> getToolBySlug(String toolSlug) {
        log.info("Fetching tool by slug: {}", toolSlug);
        
        // Requête SQL optimisée pour récupérer le tool par son slug avec l'API et l'iconSlug
        String sql = """
            SELECT at.id, at.api_id, at.description, at.method, at.tool_name_id,
                   tn.name as tool_name, a.api_slug, COALESCE(a.icon_slug, 'mcp') as icon_slug, a.icon_url
            FROM api_tools at
            LEFT JOIN tool_names tn ON at.tool_name_id = tn.id::text
            JOIN apis a ON at.api_id = a.id
            WHERE at.tool_slug = ? AND at.is_active = true AND a.is_active = true
            LIMIT 1
            """;

        List<Map<String, Object>> toolRows = jdbcTemplate.queryForList(sql, toolSlug);

        if (toolRows.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Object> toolRow = toolRows.get(0);

        // Récupérer le nom du tool
        String toolName = (String) toolRow.get("tool_name");
        if (toolName == null) {
            toolName = "Unknown Tool";
        }

        String apiSlug = (String) toolRow.get("api_slug");
        String iconSlug = (String) toolRow.get("icon_slug");
        String iconUrl = (String) toolRow.get("icon_url");

        Object rawId = toolRow.get("id");
        String toolId = rawId == null ? null : rawId.toString();

        return Optional.of(new WorkflowToolDTO(
            toolSlug,
            toolName,
            (String) toolRow.get("description"),
            (String) toolRow.get("method"),
            apiSlug,
            iconSlug,
            iconUrl,
            toolId,
            null,  // V166: requiredScopes - single-tool lookup; getTool*Details paths carry the data instead
            null   // V166: integrationName - same rationale
        ));
    }

    /**
     * Récupère les détails complets d'un tool par son slug (paramètres, réponses, credentials)
     * Accepte aussi les UUIDs si le tool n'a pas de slug
     */
    public Optional<WorkflowToolDetailDTO> getToolDetailBySlug(String toolSlug) {
        log.info("Fetching tool details by slug: {}", toolSlug);
        
        // Requête SQL optimisée pour récupérer le tool par son slug ou par ID (si toolSlug est un UUID)
        String sql;
        Object queryParam;
        
        // Si toolSlug ressemble à un UUID, chercher par ID, sinon par slug
        boolean isUuid = toolSlug.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        
        if (isUuid) {
            // Chercher par ID
            sql = """
                SELECT at.id, at.api_id, at.description, at.method, at.tool_name_id, at.tool_slug,
                       at.required_scopes, a.platform_credential_name,
                       tn.name as tool_name, a.api_slug, COALESCE(a.icon_slug, 'mcp') as icon_slug, a.icon_url
                FROM api_tools at
                LEFT JOIN tool_names tn ON at.tool_name_id = tn.id::text
                JOIN apis a ON at.api_id = a.id
                WHERE at.id = ?::uuid AND at.is_active = true AND a.is_active = true
                LIMIT 1
                """;
            queryParam = toolSlug;
        } else {
            // Chercher par slug
            sql = """
                SELECT at.id, at.api_id, at.description, at.method, at.tool_name_id, at.tool_slug,
                       at.required_scopes, a.platform_credential_name,
                       tn.name as tool_name, a.api_slug, COALESCE(a.icon_slug, 'mcp') as icon_slug, a.icon_url
                FROM api_tools at
                LEFT JOIN tool_names tn ON at.tool_name_id = tn.id::text
                JOIN apis a ON at.api_id = a.id
                WHERE at.tool_slug = ? AND at.is_active = true AND a.is_active = true
                LIMIT 1
                """;
            queryParam = toolSlug;
        }
        
        List<Map<String, Object>> toolRows = jdbcTemplate.queryForList(sql, queryParam);
        
        if (toolRows.isEmpty()) {
            return Optional.empty();
        }
        
        Map<String, Object> toolRow = toolRows.get(0);
        UUID toolId = (UUID) toolRow.get("id");
        
        // Utiliser le slug réel du tool depuis la base de données (peut être différent si on a cherché par UUID)
        String actualToolSlug = (String) toolRow.get("tool_slug");
        if (actualToolSlug == null || actualToolSlug.trim().isEmpty()) {
            // Si le tool n'a toujours pas de slug, générer un slug et le sauvegarder
            // Récupérer l'API pour obtenir son slug
            UUID apiId = (UUID) toolRow.get("api_id");
            Optional<ApiEntity> apiOpt = apiRepository.findById(apiId);
            if (apiOpt.isPresent()) {
                String apiSlug = apiOpt.get().getApiSlug();
                if (apiSlug == null || apiSlug.trim().isEmpty()) {
                    apiSlug = "api";
                }
                
                // Récupérer le nom du tool
                String toolName = (String) toolRow.get("tool_name");
                if (toolName == null) {
                    toolName = "Unknown Tool";
                }
                
                // Générer le slug
                String toolNameSlug = SlugUtils.generateSlug(toolName);
                actualToolSlug = apiSlug + "-" + toolNameSlug;
                actualToolSlug = ensureUniqueToolSlug(apiId, actualToolSlug, toolId);
                
                // Sauvegarder le slug
                String updateSql = "UPDATE api_tools SET tool_slug = ? WHERE id = ?";
                jdbcTemplate.update(updateSql, actualToolSlug, toolId);
                log.info("Generated and saved tool slug: {} for tool ID: {}", actualToolSlug, toolId);
            } else {
                // Fallback si l'API n'existe pas
                actualToolSlug = toolSlug;
            }
        }
        
        // Récupérer le nom du tool
        String toolName = (String) toolRow.get("tool_name");
        if (toolName == null) {
            toolName = "Unknown Tool";
        }

        // Récupérer apiSlug et iconSlug
        String apiSlug = (String) toolRow.get("api_slug");
        String iconSlug = (String) toolRow.get("icon_slug");

        // Récupérer les paramètres en une seule requête avec parameter_type
        String paramsSql = """
            SELECT name, description, data_type, is_required, parameter_type, default_value, allowed_values, extras
            FROM api_tool_parameters
            WHERE api_tool_id = ?
            ORDER BY parameter_type, name
            """;

        List<Map<String, Object>> paramRows = jdbcTemplate.queryForList(paramsSql, toolId);
        List<WorkflowParameterDTO> paramDTOs = paramRows.stream()
            .map(row -> new WorkflowParameterDTO(
                (String) row.get("name"),
                (String) row.get("description"),
                (String) row.get("data_type"),
                (Boolean) row.get("is_required"),
                (String) row.get("parameter_type"),
                (String) row.get("default_value"),
                AllowedValuesParser.parse(row.get("allowed_values")),
                parseExtras(row.get("extras"))
            ))
            .collect(Collectors.toList());

        String responsesSql = """
            SELECT id, name, description, schema, example, example_jsonb, format, status_code, is_default
            FROM tool_responses
            WHERE tool_id = ? AND is_active = true
            ORDER BY is_default DESC, created_at DESC
            """;
        
        List<Map<String, Object>> responseRows = jdbcTemplate.queryForList(responsesSql, toolId);
        List<WorkflowToolResponseDTO> responseDTOs = responseRows.stream()
            .map(row -> new WorkflowToolResponseDTO(
                (UUID) row.get("id"),
                convertToString(row.get("name")),
                convertToString(row.get("description")),
                convertToString(row.get("schema")),
                convertToString(row.get("example")),
                convertJsonbToString(row.get("example_jsonb")),
                convertToString(row.get("format")),
                row.get("status_code") != null ? ((Number) row.get("status_code")).intValue() : null,
                (Boolean) row.get("is_default")
            ))
            .collect(Collectors.toList());

        // Récupérer les credentials en une seule requête.
        // `tc.variant` is required so we can filter rows whose variant has been
        // admin-disabled (auth.platform_credentials.is_enabled = false). Ordered by
        // (credential_name, variant) so filterAndDedupe picks the first enabled variant
        // deterministically (alphabetical - basic_auth before bearer_token).
        String credentialsSql = """
            SELECT tc.credential_name, tc.variant, tc.is_required, tc.usage, tc.condition, tc.metadata,
                   c.display_name, c.description, c.credential_type, c.auth_type,
                   c.test_endpoint, c.documentation_url, c.icon_url, c.properties, c.extends_
            FROM catalog.tool_credentials tc
            LEFT JOIN catalog.credentials c ON tc.credential_id = c.id
            WHERE tc.api_tool_id = ?
            ORDER BY tc.credential_name, tc.variant
            """;

        List<Map<String, Object>> credentialRows = jdbcTemplate.queryForList(credentialsSql, toolId);
        List<Map<String, Object>> visibleRows = filterAndDedupe(credentialRows, fetchDisabledVariantKeys());
        List<WorkflowToolCredentialDTO> credentialDTOs = visibleRows.stream()
            .map(row -> new WorkflowToolCredentialDTO(
                convertToString(row.get("credential_name")),
                (Boolean) row.get("is_required"),
                convertToString(row.get("usage")),
                convertJsonbToString(row.get("condition")),
                convertJsonbToString(row.get("metadata")),
                convertToString(row.get("display_name")),
                convertToString(row.get("description")),
                convertToString(row.get("credential_type")),
                convertToString(row.get("auth_type")),
                convertToString(row.get("test_endpoint")),
                convertToString(row.get("documentation_url")),
                convertToString(row.get("icon_url")),
                convertJsonbToString(row.get("properties")),
                convertToString(row.get("extends_"))
            ))
            .collect(Collectors.toList());

        String iconUrl = (String) toolRow.get("icon_url");

        // V166: parse required_scopes JSONB and platform_credential_name for the
        // frontend MissingScopesBanner. Both nullable on the column / API.
        List<String> requiredScopes = parseRequiredScopesJsonb(toolRow.get("required_scopes"));
        String integrationName = (String) toolRow.get("platform_credential_name");

        return Optional.of(new WorkflowToolDetailDTO(
            actualToolSlug,
            toolName,
            (String) toolRow.get("description"),
            (String) toolRow.get("method"),
            apiSlug,
            iconSlug,
            iconUrl,
            paramDTOs,
            responseDTOs,
            credentialDTOs,
            toolId == null ? null : toolId.toString(),
            requiredScopes,
            integrationName
        ));
    }

    /**
     * V166: parse a JSONB cell value (PGobject or String) into a List<String>.
     * Returns null on any parse error or empty list (banner short-circuits on null).
     */
    @SuppressWarnings("unchecked")
    private List<String> parseRequiredScopesJsonb(Object cell) {
        if (cell == null) return null;
        try {
            String json;
            if (cell instanceof org.postgresql.util.PGobject pg) {
                json = pg.getValue();
            } else {
                json = cell.toString();
            }
            if (json == null || json.isBlank()) return null;
            List<String> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            return (parsed == null || parsed.isEmpty()) ? null : parsed;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Convertit un objet en String, gérant les cas null et PGobject
     */
    private String convertToString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        return value.toString();
    }

    /**
     * Convertit un JSONB (PGobject) en String
     */
    private String convertJsonbToString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        // Pour les PGobject (JSONB), on récupère la valeur string
        return value.toString();
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

    /**
     * Récupère une API spécifique par son slug (uniquement les champs nécessaires)
     */
    public Optional<WorkflowApiDTO> getApiBySlug(String apiSlug) {
        log.info("Fetching API by slug: {}", apiSlug);

        String sql = """
            SELECT
                a.api_slug,
                a.api_name,
                a.description,
                COUNT(at.id) as tools_count,
                COALESCE(a.icon_slug, 'mcp') as icon_slug,
                a.icon_url
            FROM apis a
            LEFT JOIN api_tools at ON a.id = at.api_id
            WHERE a.api_slug = ?
            GROUP BY a.api_slug, a.api_name, a.description, a.icon_slug, a.icon_url
            """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, apiSlug);

        if (rows.isEmpty()) {
            log.warn("API not found for slug: {}", apiSlug);
            return Optional.empty();
        }

        Map<String, Object> row = rows.get(0);
        return Optional.of(new WorkflowApiDTO(
            (String) row.get("api_slug"),
            (String) row.get("api_name"),
            (String) row.get("description"),
            ((Number) row.get("tools_count")).intValue(),
            (String) row.get("icon_slug"),
            (String) row.get("icon_url")
        ));
    }

    /**
     * Récupère uniquement l'iconSlug d'une API par son slug (requête optimisée)
     */
    public Optional<String> getApiIconSlug(String apiSlug) {
        log.info("Fetching iconSlug for API: {}", apiSlug);

        String sql = "SELECT COALESCE(icon_slug, 'mcp') FROM apis WHERE api_slug = ?";

        List<String> results = jdbcTemplate.queryForList(sql, String.class, apiSlug);
        return results.isEmpty() ? Optional.empty() : Optional.ofNullable(results.get(0));
    }

    /**
     * Récupère l'iconSlug d'une API via le slug d'un de ses tools (requête optimisée)
     */
    public Optional<String> getToolIconSlug(String toolSlug) {
        log.info("Fetching iconSlug for tool: {}", toolSlug);

        String sql = """
            SELECT COALESCE(a.icon_slug, 'mcp')
            FROM api_tools at
            JOIN apis a ON at.api_id = a.id
            WHERE at.tool_slug = ?
            """;

        List<String> results = jdbcTemplate.queryForList(sql, String.class, toolSlug);
        return results.isEmpty() ? Optional.empty() : Optional.ofNullable(results.get(0));
    }

    /**
     * Batch fetch multiple tools by their identifiers.
     * Optimized for workflow import - reduces N+1 queries to a single batch.
     *
     * <p>Each identifier may be a {@code tool_slug} (the {@code apiSlug/toolSlug}
     * reference's last segment) OR an {@code api_tools.id} UUID - both forms appear
     * in workflow plans and the result is keyed by whichever the caller passed
     * (slug-keyed always; additionally UUID-keyed when the tool was requested by id).
     *
     * @param toolSlugs List of tool identifiers (tool_slug or api_tools.id UUID)
     * @return Map of identifier -> WorkflowToolDetailDTO
     */
    @Transactional(readOnly = true)
    public Map<String, WorkflowToolDetailDTO> getToolsBatch(List<String> toolSlugs) {
        if (toolSlugs == null || toolSlugs.isEmpty()) {
            return new HashMap<>();
        }
        log.info("Batch fetching {} tools", toolSlugs.size());

        // Deduplicate and filter empty identifiers. Each entry may be a tool_slug
        // (the "apiSlug/toolSlug" reference's last segment) OR an api_tools.id UUID.
        // Both forms appear in workflow plans - the orchestrator resolves either at
        // execution, and getToolDetailBySlug already accepts either. The batch path
        // historically matched tool_slug ONLY, so a UUID-referenced node resolved to
        // NOTHING: it loaded with no params/credentials in the builder AND was
        // silently dropped from the acquired-application "missing credentials" setup
        // wizard (a serpapi node referencing api_tools.id never surfaced).
        List<String> uniqueIds = toolSlugs.stream()
            .filter(s -> s != null && !s.trim().isEmpty())
            .map(String::trim)
            .distinct()
            .collect(Collectors.toList());

        if (uniqueIds.isEmpty()) {
            return new HashMap<>();
        }

        // Route each identifier to the column it can match: UUID → api_tools.id,
        // everything else → api_tools.tool_slug. requestedUuidKeys (lowercased)
        // lets us additionally key the result by the UUID the caller asked with, so
        // a UUID-referencing caller can look the tool up by the id it sent.
        List<String> slugIds = new ArrayList<>();
        List<UUID> uuidIds = new ArrayList<>();
        Set<String> requestedUuidKeys = new HashSet<>();
        for (String id : uniqueIds) {
            if (UUID_PATTERN.matcher(id).matches()) {
                try {
                    uuidIds.add(UUID.fromString(id));
                    requestedUuidKeys.add(id.toLowerCase(Locale.ROOT));
                    continue;
                } catch (IllegalArgumentException ignored) {
                    // Not actually a UUID despite matching the shape - fall through to slug.
                }
            }
            slugIds.add(id);
        }

        Map<String, WorkflowToolDetailDTO> result = new HashMap<>();

        // Fetch tools in batch using SQL IN clause(s). Build the predicate from
        // whichever identifier kinds are present so we never emit an empty IN ().
        List<Object> queryParams = new ArrayList<>();
        List<String> whereParts = new ArrayList<>();
        if (!slugIds.isEmpty()) {
            whereParts.add("at.tool_slug IN (" + String.join(",", Collections.nCopies(slugIds.size(), "?")) + ")");
            queryParams.addAll(slugIds);
        }
        if (!uuidIds.isEmpty()) {
            whereParts.add("at.id IN (" + String.join(",", Collections.nCopies(uuidIds.size(), "?")) + ")");
            queryParams.addAll(uuidIds);
        }
        String toolsSql = """
            SELECT at.id, at.api_id, at.description, at.method, at.tool_name_id, at.tool_slug,
                   at.required_scopes, a.platform_credential_name,
                   tn.name as tool_name, a.api_slug, COALESCE(a.icon_slug, 'mcp') as icon_slug, a.icon_url
            FROM api_tools at
            LEFT JOIN tool_names tn ON at.tool_name_id = tn.id::text
            JOIN apis a ON at.api_id = a.id
            WHERE\s""" + String.join(" OR ", whereParts);

        List<Map<String, Object>> toolRows = jdbcTemplate.queryForList(toolsSql, queryParams.toArray());
        log.info("Found {} tools in batch", toolRows.size());

        if (toolRows.isEmpty()) {
            return result;
        }

        // Collect all tool IDs for parameter batch fetch
        List<UUID> toolIds = toolRows.stream()
            .map(row -> (UUID) row.get("id"))
            .collect(Collectors.toList());

        // Batch fetch parameters for all tools
        Map<UUID, List<WorkflowParameterDTO>> paramsByToolId = batchFetchParameters(toolIds);

        // Batch fetch responses for all tools
        Map<UUID, List<WorkflowToolResponseDTO>> responsesByToolId = batchFetchResponses(toolIds);

        // Batch fetch credentials for all tools
        Map<UUID, List<WorkflowToolCredentialDTO>> credentialsByToolId = batchFetchCredentials(toolIds);

        // Build result DTOs
        for (Map<String, Object> toolRow : toolRows) {
            UUID toolId = (UUID) toolRow.get("id");
            String toolSlug = (String) toolRow.get("tool_slug");
            String toolName = (String) toolRow.get("tool_name");
            if (toolName == null) {
                toolName = "Unknown Tool";
            }

            WorkflowToolDetailDTO dto = new WorkflowToolDetailDTO(
                toolSlug,
                toolName,
                (String) toolRow.get("description"),
                (String) toolRow.get("method"),
                (String) toolRow.get("api_slug"),
                (String) toolRow.get("icon_slug"),
                (String) toolRow.get("icon_url"),
                paramsByToolId.getOrDefault(toolId, new ArrayList<>()),
                responsesByToolId.getOrDefault(toolId, new ArrayList<>()),
                credentialsByToolId.getOrDefault(toolId, new ArrayList<>()),
                toolId == null ? null : toolId.toString(),
                parseRequiredScopesJsonb(toolRow.get("required_scopes")),
                (String) toolRow.get("platform_credential_name")
            );

            // Key by tool_slug (back-compat: slug-referencing callers look up by slug).
            if (toolSlug != null && !toolSlug.isBlank()) {
                result.put(toolSlug, dto);
            }
            // Also key by the UUID the caller asked with, so a node that references
            // this tool by api_tools.id resolves it under the id it sent. Only added
            // when the id was actually requested, so the response shape is unchanged
            // for the common slug-only batch.
            if (toolId != null && requestedUuidKeys.contains(toolId.toString().toLowerCase(Locale.ROOT))) {
                result.put(toolId.toString(), dto);
            }
        }

        log.info("Batch fetch complete: returned {} entries", result.size());
        return result;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper EXTRAS_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Parses the JSONB {@code extras} column (PGobject / String) into a JsonNode for the inspector
     * DTO so the builder can read e.g. {@code extras.picker}. Returns {@code null} for an
     * absent/blank/empty/invalid value so the field stays null for params without extras.
     */
    private static com.fasterxml.jackson.databind.JsonNode parseExtras(Object value) {
        if (value == null) {
            return null;
        }
        try {
            String json = value.toString();
            if (json == null || json.isBlank()) {
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode node = EXTRAS_MAPPER.readTree(json);
            return (node != null && node.isObject() && node.size() > 0) ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Batch fetch parameters for multiple tools
     */
    private Map<UUID, List<WorkflowParameterDTO>> batchFetchParameters(List<UUID> toolIds) {
        if (toolIds.isEmpty()) {
            return new HashMap<>();
        }

        String placeholders = String.join(",", Collections.nCopies(toolIds.size(), "?::uuid"));
        String sql = String.format("""
            SELECT api_tool_id, name, description, data_type, is_required, parameter_type, default_value, allowed_values, extras
            FROM api_tool_parameters
            WHERE api_tool_id IN (%s)
            ORDER BY parameter_type, name
            """, placeholders);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, toolIds.toArray());

        Map<UUID, List<WorkflowParameterDTO>> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            UUID toolId = (UUID) row.get("api_tool_id");
            WorkflowParameterDTO param = new WorkflowParameterDTO(
                (String) row.get("name"),
                (String) row.get("description"),
                (String) row.get("data_type"),
                (Boolean) row.get("is_required"),
                (String) row.get("parameter_type"),
                (String) row.get("default_value"),
                AllowedValuesParser.parse(row.get("allowed_values")),
                parseExtras(row.get("extras"))
            );
            result.computeIfAbsent(toolId, k -> new ArrayList<>()).add(param);
        }

        return result;
    }

    /**
     * Batch fetch responses for multiple tools
     */
    private Map<UUID, List<WorkflowToolResponseDTO>> batchFetchResponses(List<UUID> toolIds) {
        if (toolIds.isEmpty()) {
            return new HashMap<>();
        }

        String placeholders = String.join(",", Collections.nCopies(toolIds.size(), "?::uuid"));
        String sql = String.format("""
            SELECT tool_id, id, name, description, schema, example, example_jsonb, format, status_code, is_default
            FROM tool_responses
            WHERE tool_id IN (%s)
            ORDER BY is_default DESC, created_at DESC
            """, placeholders);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, toolIds.toArray());

        Map<UUID, List<WorkflowToolResponseDTO>> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            UUID toolId = (UUID) row.get("tool_id");
            WorkflowToolResponseDTO response = new WorkflowToolResponseDTO(
                (UUID) row.get("id"),
                convertToString(row.get("name")),
                convertToString(row.get("description")),
                convertToString(row.get("schema")),
                convertToString(row.get("example")),
                convertJsonbToString(row.get("example_jsonb")),
                convertToString(row.get("format")),
                row.get("status_code") != null ? ((Number) row.get("status_code")).intValue() : null,
                (Boolean) row.get("is_default")
            );
            result.computeIfAbsent(toolId, k -> new ArrayList<>()).add(response);
        }

        return result;
    }

    /**
     * Batch fetch credentials for multiple tools
     */
    private Map<UUID, List<WorkflowToolCredentialDTO>> batchFetchCredentials(List<UUID> toolIds) {
        if (toolIds.isEmpty()) {
            return new HashMap<>();
        }

        String placeholders = String.join(",", Collections.nCopies(toolIds.size(), "?::uuid"));
        // catalog.* is qualified deliberately: an unqualified `credentials` resolves to
        // auth/orchestrator.credentials (bigint id) under the monolith's multi-schema
        // search_path, producing `operator does not exist: uuid = bigint` - a 500 that
        // silently kills ALL tool-credential resolution (so missing-credential surfacing
        // shows nothing). Keep every catalog table schema-qualified here.
        String sql = String.format("""
            SELECT tc.api_tool_id, tc.credential_name, tc.variant, tc.is_required, tc.usage, tc.condition, tc.metadata,
                   c.display_name, c.description, c.credential_type, c.auth_type,
                   c.test_endpoint, c.documentation_url, c.icon_url, c.properties, c.extends_
            FROM catalog.tool_credentials tc
            LEFT JOIN catalog.credentials c ON tc.credential_id = c.id
            WHERE tc.api_tool_id IN (%s)
            ORDER BY tc.credential_name, tc.variant
            """, placeholders);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, toolIds.toArray());
        Map<UUID, List<WorkflowToolCredentialDTO>> result = new HashMap<>();
        if (rows.isEmpty()) {
            // No credentials for any tool - skip the auth-service round trip entirely.
            return result;
        }

        // Group rows by tool, then filter+dedupe once per tool so a disabled variant
        // on tool A does not leak across to tool B. One auth-service round trip total
        // (fetchDisabledVariantKeys is called once and reused for every group).
        Map<UUID, List<Map<String, Object>>> rowsByTool = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            UUID toolId = (UUID) row.get("api_tool_id");
            rowsByTool.computeIfAbsent(toolId, k -> new ArrayList<>()).add(row);
        }
        Set<String> disabledKeys = fetchDisabledVariantKeys();
        // result was declared earlier so the empty-rows short-circuit can return it
        for (Map.Entry<UUID, List<Map<String, Object>>> entry : rowsByTool.entrySet()) {
            List<Map<String, Object>> visible = filterAndDedupe(entry.getValue(), disabledKeys);
            List<WorkflowToolCredentialDTO> dtos = visible.stream()
                .map(row -> new WorkflowToolCredentialDTO(
                    convertToString(row.get("credential_name")),
                    (Boolean) row.get("is_required"),
                    convertToString(row.get("usage")),
                    convertJsonbToString(row.get("condition")),
                    convertJsonbToString(row.get("metadata")),
                    convertToString(row.get("display_name")),
                    convertToString(row.get("description")),
                    convertToString(row.get("credential_type")),
                    convertToString(row.get("auth_type")),
                    convertToString(row.get("test_endpoint")),
                    convertToString(row.get("documentation_url")),
                    convertToString(row.get("icon_url")),
                    convertJsonbToString(row.get("properties")),
                    convertToString(row.get("extends_"))
                ))
                .collect(Collectors.toList());
            result.put(entry.getKey(), dtos);
        }

        return result;
    }
}
