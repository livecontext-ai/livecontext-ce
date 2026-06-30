package com.apimarketplace.catalog.web;

import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.PlatformCredentialStatusDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.util.PGobject;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * REST Controller for credential templates management
 */
@RestController
@RequestMapping("/api/catalog/credentials")
@RequiredArgsConstructor
@Slf4j
public class CredentialTemplateController {

    private final JdbcTemplate jdbcTemplate;
    private final CredentialClient credentialClient;
    // A local ObjectMapper dodges the need to thread a bean through the
    // controller's sole constructor (kept for @RequiredArgsConstructor so the
    // existing unit tests don't need to pass an extra argument). JSONB parsing
    // here is stateless and doesn't benefit from sharing configuration with
    // the default app mapper.
    private static final ObjectMapper VARIANTS_MAPPER = new ObjectMapper();

    /**
     * Native credential templates consumed by <b>core workflow nodes</b> and therefore NOT
     * backed by any {@code catalog.apis} row: {@code smtp} (send_email) and {@code imap}
     * (email_inbox). The user-facing list ({@link #getCredentialTemplates} with
     * {@code includeInactive=false}) only shows templates backed by an active catalog API, so
     * without this allow-list these native templates - which ARE configurable and used by core
     * nodes - would be hidden from "Available integrations". Mirrors the core nodes that read a
     * native credential; extend this set when a new core node introduces one.
     *
     * <p>LLM provider keys ({@code llm_*}) are intentionally absent: they are configured on the
     * dedicated AI Providers settings page, so surfacing them here would duplicate that flow.
     */
    private static final List<String> NATIVE_CORE_CREDENTIAL_NAMES = List.of("smtp", "imap");

    /**
     * Ensures the {@code variants} column returned by {@link #getCredentialTemplates} and
     * {@link #getCredentialTemplate} is a proper JSON array instead of a PGobject wrapper.
     *
     * <p>Spring's raw {@code JdbcTemplate.queryForList} hands back {@link PGobject} for any
     * jsonb column, which Jackson then serializes as {@code {"type":"jsonb","value":"..."}}.
     * That shape breaks the frontend, which expects {@code variants} to be an array it can
     * iterate. We parse the underlying JSON string once per row and replace the value with
     * the parsed list, so the HTTP response carries a clean array. Missing or unparseable
     * values are normalized to an empty list - the caller treats "no variants" as a
     * legitimate state (single-variant APIs fall back to the row's own {@code variant}).
     */
    private void unwrapVariants(Map<String, Object> row) {
        Object raw = row.get("variants");
        if (raw instanceof PGobject pg) {
            String json = pg.getValue();
            if (json == null || json.isBlank()) {
                row.put("variants", List.of());
                return;
            }
            try {
                row.put("variants", VARIANTS_MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {}));
            } catch (Exception e) {
                log.warn("Failed to parse variants jsonb for credential row; returning empty list", e);
                row.put("variants", List.of());
            }
        }
    }

    /**
     * Build the set of {@code (integrationName, variant)} keys the platform admin has
     * explicitly disabled via {@code auth.platform_credentials.is_enabled = false}.
     *
     * <p>Semantics: a variant is hidden from regular users when an admin flips its
     * {@code is_enabled} flag off <b>AND</b> has actually configured the row (any secret).
     * Variants with no platform_credentials row at all are considered enabled - many
     * user-provided auth types (basic_auth, api_key) never need a platform-level secret,
     * so their absence from this table is the default "available" state, not a disabled
     * state.
     *
     * <p><b>Defense-in-depth against phantom placeholder rows:</b> {@code
     * PlatformCredentialRepository.setEnabledForVariant} synthesizes a NULL-secrets
     * placeholder when the admin flips a per-variant toggle on an integration that has
     * never been saved (multi-variant pre-disable workflow). On single-variant catalog
     * APIs (e.g. Salesforce), this placeholder used to silently drop the entire
     * integration from {@code /app/settings/credentials} because its only variant
     * landed in this disabled-set. The gate on {@code dto.isConfigured()} now treats
     * unconfigured-disabled rows as equivalent to "no row at all" - the admin's intent
     * only takes user-visible effect once they've actually saved a secret, matching the
     * row-absence "default available" semantics above. Prod incident May 2026: 81 such
     * phantom rows silently hid OAuth2 integrations from end users.
     *
     * <p>Cross-service call: catalog-service cannot read {@code auth.*} directly, so we
     * route through {@link CredentialClient#listPlatformCredentials}. A failure returns
     * an empty set - we fail-open (show everything) rather than hiding the whole catalog
     * if auth-service is briefly unreachable.
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
            log.warn("Failed to fetch platform credential status for variant filtering; falling back to 'show all': {}",
                    e.getMessage());
            return Set.of();
        }
    }

    /**
     * Drop any entry from the row's {@code variants[]} whose {@code (credential_name, variant)}
     * pair is in {@code disabledKeys}. Called after {@link #unwrapVariants}. Mutates the
     * row in place. Returns the number of variants left - callers use this to decide
     * whether to drop the row entirely (if every variant has been admin-disabled, there
     * is nothing for the user to pick).
     */
    @SuppressWarnings("unchecked")
    private int filterDisabledVariants(Map<String, Object> row, Set<String> disabledKeys) {
        if (disabledKeys.isEmpty()) {
            // Nothing is disabled - short-circuit without touching the row. Return a
            // positive sentinel so callers don't mistake "no filtering applied" for
            // "every variant was filtered" and drop the row to 404.
            return 1;
        }
        String credentialName = Objects.toString(row.get("credential_name"), "");
        Object rawVariants = row.get("variants");
        if (!(rawVariants instanceof List<?>)) {
            return 0;
        }
        List<Map<String, Object>> variants = (List<Map<String, Object>>) rawVariants;
        List<Map<String, Object>> kept = new ArrayList<>(variants.size());
        for (Map<String, Object> v : variants) {
            String variantName = Objects.toString(v.get("variant"), "");
            if (!disabledKeys.contains(credentialName + "::" + variantName)) {
                kept.add(v);
            }
        }
        row.put("variants", kept);
        return kept.size();
    }
    
    /**
     * Get all credential templates with pagination.
     *
     * <p>Users only see templates whose corresponding API is {@code is_active = true}. When an
     * admin disables an API via the platform-credentials admin page, the credential template
     * must disappear from the regular user's settings page - this JOIN enforces that.
     *
     * <p>The 1:1 mapping is guaranteed by the api-migration importer
     * ({@code catalog.apis.platform_credential_name = catalog.credentials.credential_name}).
     * Orphan credentials (without a matching API) are hidden as well, which is the desired
     * behavior: a template with no backing API is unusable.
     *
     * <p>Multi-variant APIs (e.g. Alchemy has both {@code api_key} and {@code bearer_token})
     * are deduplicated by {@code credential_name}: exactly one row per integration is returned,
     * with a {@code variants} array listing every available {@code (variant, auth_type)} pair.
     * The frontend uses this array to render a "N auth methods" chip on the Available list and
     * to skip the variant picker when only one exists.
     *
     * GET /api/catalog/credentials?page=1&pageSize=10&includeInactive=false
     */
    @GetMapping
    public ResponseEntity<?> getCredentialTemplates(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "includeInactive", defaultValue = "false") boolean includeInactive
    ) {
        // Exact lookup by credential_name - returns single template, no pagination needed
        if (name != null && !name.trim().isEmpty()) {
            try {
                String sql = """
                    SELECT c.id, c.credential_name, c.display_name, c.description, c.credential_type,
                           c.auth_type, c.variant, c.test_endpoint, c.documentation_url, c.icon_url, c.icon_slug,
                           c.properties, c.extends_, c.metadata, c.created_at, c.updated_at,
                           (SELECT jsonb_agg(jsonb_build_object('variant', c2.variant, 'auth_type', c2.auth_type)
                                             ORDER BY c2.variant ASC)
                            FROM catalog.credentials c2
                            WHERE c2.credential_name = c.credential_name) AS variants
                    FROM catalog.credentials c
                    WHERE c.credential_name = ?
                    ORDER BY c.variant ASC
                    LIMIT 1
                    """;
                List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, name.trim());
                if (results.isEmpty()) {
                    return ResponseEntity.notFound().build();
                }
                Map<String, Object> row = results.get(0);
                unwrapVariants(row);
                // Hide admin-disabled variants. If the integration has a single variant
                // and the admin disabled it, the whole credential becomes unusable -
                // treat it as 404 so the wizard stops at "template not found" instead
                // of rendering an empty picker. Admin flow (includeInactive=true) skips
                // the filter so the platform-credentials admin page can still address
                // disabled variants when toggling them back on.
                Set<String> disabled = includeInactive ? Set.of() : fetchDisabledVariantKeys();
                int remaining = filterDisabledVariants(row, disabled);
                if (remaining == 0) {
                    return ResponseEntity.notFound().build();
                }
                return ResponseEntity.ok(row);
            } catch (Exception e) {
                log.error("Error fetching credential template by name: {}", name, e);
                return ResponseEntity.status(500).body(Map.of("error", "Failed to fetch credential template", "message", e.getMessage()));
            }
        }

        try {
            int offset = (page - 1) * pageSize;

            // Admins may want to list everything (e.g., for the platform-credentials admin
            // page itself). Regular users always get the filtered list.
            //
            // Visibility for a regular user = backed by an active catalog API OR a native
            // core-node credential (see NATIVE_CORE_CREDENTIAL_NAMES). The names are
            // compile-time constants - inlining them as SQL string literals carries no
            // injection risk and keeps the search filter's positional '?' ordering intact.
            String nativeInList = "'" + String.join("','", NATIVE_CORE_CREDENTIAL_NAMES) + "'";
            // Regular users never see bundle-deprecated rows (V331): neither a
            // deprecated template itself, nor a template whose only backing API
            // was soft-deleted by an API-catalog bundle apply. Admins
            // (includeInactive=true) keep seeing everything.
            String activeFilter = includeInactive
                    ? ""
                    : " AND c.deprecated_at IS NULL "
                            + "AND (EXISTS (SELECT 1 FROM catalog.apis a "
                            + "WHERE a.platform_credential_name = c.credential_name "
                            + "AND a.is_active = true AND a.deprecated_at IS NULL) "
                            + "OR c.credential_name IN (" + nativeInList + ")) ";

            String searchFilter = "";
            if (search != null && !search.trim().isEmpty()) {
                searchFilter = " AND (c.credential_name ILIKE ? OR c.display_name ILIKE ? "
                        + "OR c.description ILIKE ? OR c.icon_slug ILIKE ?) ";
            }

            String whereClause = " WHERE 1=1 " + activeFilter + searchFilter;

            // Count DISTINCT credential_name to align with the deduped list below - otherwise
            // multi-variant APIs inflate totalItems and pagination math breaks.
            String countSql = "SELECT COUNT(DISTINCT c.credential_name) FROM catalog.credentials c " + whereClause;
            int totalItems;

            if (search != null && !search.trim().isEmpty()) {
                String searchPattern = "%" + search + "%";
                totalItems = jdbcTemplate.queryForObject(countSql, Integer.class,
                        searchPattern, searchPattern, searchPattern, searchPattern);
            } else {
                totalItems = jdbcTemplate.queryForObject(countSql, Integer.class);
            }

            // DISTINCT ON (credential_name) keeps one canonical row per integration. The
            // variants[] subquery lists every (variant, auth_type) pair so the frontend can
            // render a "N auth methods" chip and skip the wizard picker when only one exists.
            String sql = """
                SELECT DISTINCT ON (c.credential_name)
                       c.id, c.credential_name, c.display_name, c.description, c.credential_type,
                       c.auth_type, c.variant, c.test_endpoint, c.documentation_url, c.icon_url, c.icon_slug,
                       c.properties, c.extends_, c.metadata, c.created_at, c.updated_at,
                       (SELECT a.source FROM catalog.apis a WHERE a.platform_credential_name = c.credential_name LIMIT 1) AS source,
                       (SELECT COALESCE(jsonb_agg(jsonb_build_object('variant', c2.variant, 'auth_type', c2.auth_type)
                                                  ORDER BY c2.variant ASC), '[]'::jsonb)
                        FROM catalog.credentials c2
                        WHERE c2.credential_name = c.credential_name) AS variants
                FROM catalog.credentials c
                """ + whereClause + """
                ORDER BY c.credential_name ASC, c.variant ASC
                LIMIT ? OFFSET ?
                """;

            List<Map<String, Object>> credentials;
            if (search != null && !search.trim().isEmpty()) {
                String searchPattern = "%" + search + "%";
                credentials = jdbcTemplate.queryForList(sql, searchPattern, searchPattern,
                        searchPattern, searchPattern, pageSize, offset);
            } else {
                credentials = jdbcTemplate.queryForList(sql, pageSize, offset);
            }

            // DISTINCT ON forces ORDER BY credential_name for correctness, but the UI expects
            // alphabetical display_name. Re-sort immutably in memory - page size is capped (≤1000).
            credentials = credentials.stream()
                    .sorted((a, b) -> {
                        String da = java.util.Objects.toString(a.get("display_name"), "");
                        String db = java.util.Objects.toString(b.get("display_name"), "");
                        return da.compareToIgnoreCase(db);
                    })
                    .toList();

            credentials.forEach(this::unwrapVariants);

            // Apply admin-disabled-variant filter uniformly across the page. Rows whose
            // every variant has been disabled drop out entirely - same user-facing effect
            // as the pre-multivariant behaviour where toggling the single OAuth2 entry
            // removed the credential from the list. Note: totalItems above reflects the
            // pre-filter count, so the last page may be shorter than pageSize when a few
            // rows collapse; acceptable given admin disables are rare.
            //
            // includeInactive=true is the admin flow (platform-credentials admin page
            // needs to see *everything* so disabled variants can be toggled back on).
            // Bypass the auth-service round-trip entirely in that case - matches the
            // existing behaviour where the API-level `is_active` filter is also skipped.
            final Set<String> disabled = includeInactive ? Set.of() : fetchDisabledVariantKeys();
            if (!disabled.isEmpty()) {
                List<Map<String, Object>> filtered = new ArrayList<>(credentials.size());
                for (Map<String, Object> row : credentials) {
                    if (filterDisabledVariants(row, disabled) > 0) {
                        filtered.add(row);
                    }
                }
                credentials = filtered;
            }

            int totalPages = (int) Math.ceil((double) totalItems / pageSize);
            boolean hasNext = page < totalPages;
            boolean hasPrevious = page > 1;
            
            Map<String, Object> response = new HashMap<>();
            response.put("credentials", credentials);
            response.put("page", page);
            response.put("pageSize", pageSize);
            response.put("totalItems", totalItems);
            response.put("totalPages", totalPages);
            response.put("hasNext", hasNext);
            response.put("hasPrevious", hasPrevious);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error fetching credential templates", e);
            return ResponseEntity.status(500).body(Map.of("error", "Failed to fetch credential templates", "message", e.getMessage()));
        }
    }
    
    /**
     * Resolve one concrete credential template by the stable runtime key used by user
     * credentials. The key can be either {@code credential_name} or {@code icon_slug};
     * the latter is important for old OAuth credentials created before an API rename
     * normalized {@code credential_name} differently from the stored integration slug.
     *
     * GET /api/catalog/credentials/resolve?key=twitter&variant=oauth2&includeInactive=true
     */
    @GetMapping("/resolve")
    public ResponseEntity<?> resolveCredentialTemplate(
            @RequestParam("key") String key,
            @RequestParam(value = "variant", defaultValue = "oauth2") String variant,
            @RequestParam(value = "includeInactive", defaultValue = "false") boolean includeInactive
    ) {
        String trimmedKey = key == null ? "" : key.trim();
        String trimmedVariant = variant == null || variant.isBlank() ? "oauth2" : variant.trim();
        if (trimmedKey.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        try {
            String activeFilter = includeInactive
                    ? ""
                    : " AND EXISTS (SELECT 1 FROM catalog.apis a "
                            + "WHERE a.platform_credential_name = c.credential_name "
                            + "AND a.is_active = true) ";
            String sql = """
                SELECT c.id, c.credential_name, c.display_name, c.description, c.credential_type, c.auth_type,
                       c.variant, c.test_endpoint, c.documentation_url, c.icon_url, c.icon_slug,
                       c.properties, c.extends_, c.metadata, c.created_at, c.updated_at,
                       (SELECT COALESCE(jsonb_agg(jsonb_build_object('variant', c2.variant, 'auth_type', c2.auth_type)
                                                  ORDER BY c2.variant ASC), '[]'::jsonb)
                        FROM catalog.credentials c2
                        WHERE c2.credential_name = c.credential_name) AS variants
                FROM catalog.credentials c
                WHERE (c.credential_name = ? OR c.icon_slug = ?)
                  AND c.variant = ?
                """ + activeFilter + """
                ORDER BY CASE WHEN c.credential_name = ? THEN 0 ELSE 1 END
                LIMIT 1
                """;

            List<Map<String, Object>> results = jdbcTemplate.queryForList(
                    sql, trimmedKey, trimmedKey, trimmedVariant, trimmedKey);
            if (results.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            Map<String, Object> row = results.get(0);
            unwrapVariants(row);
            Set<String> disabled = includeInactive ? Set.of() : fetchDisabledVariantKeys();
            String credentialName = Objects.toString(row.get("credential_name"), "");
            String variantName = Objects.toString(row.get("variant"), "");
            if (disabled.contains(credentialName + "::" + variantName)) {
                return ResponseEntity.notFound().build();
            }
            filterDisabledVariants(row, disabled);
            return ResponseEntity.ok(row);
        } catch (Exception e) {
            log.error("Error resolving credential template by key: {}", trimmedKey, e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to resolve credential template",
                    "message", e.getMessage()));
        }
    }

    /**
     * Get all variants for a credential_name. When an API exposes multiple auth methods
     * (e.g. Gmail: OAuth2 + API_Key), one row per variant lives in {@code catalog.credentials}
     * and shares the same {@code credential_name}. The wizard uses this endpoint to render
     * variant tabs so the user can pick which method to configure.
     *
     * <p>Single-variant APIs return a one-element array - callers can detect "no choice"
     * and skip the picker. An unknown {@code credentialName} returns an empty array (not 404),
     * because the wizard may call this opportunistically before it knows if variants exist.
     *
     * GET /api/catalog/credentials/{credentialName}/variants
     */
    @GetMapping("/{credentialName}/variants")
    public ResponseEntity<?> getCredentialVariants(@PathVariable String credentialName) {
        try {
            String sql = """
                SELECT c.id, c.credential_name, c.display_name, c.description, c.credential_type,
                       c.auth_type, c.variant, c.test_endpoint, c.documentation_url, c.icon_url, c.icon_slug,
                       c.properties, c.extends_, c.metadata, c.created_at, c.updated_at
                FROM catalog.credentials c
                WHERE c.credential_name = ?
                ORDER BY c.variant ASC
                """;
            List<Map<String, Object>> variants = jdbcTemplate.queryForList(sql, credentialName);
            // Drop admin-disabled variants so the wizard's tab strip only shows what the
            // user can actually pick. Keeps this endpoint consistent with the list and
            // by-id endpoints - all three apply the same (integration, variant) gate.
            Set<String> disabled = fetchDisabledVariantKeys();
            if (!disabled.isEmpty()) {
                variants = variants.stream()
                        .filter(v -> {
                            String cname = Objects.toString(v.get("credential_name"), "");
                            String vname = Objects.toString(v.get("variant"), "");
                            return !disabled.contains(cname + "::" + vname);
                        })
                        .toList();
            }
            return ResponseEntity.ok(variants);
        } catch (Exception e) {
            log.error("Error fetching credential variants for name: {}", credentialName, e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to fetch credential variants",
                    "message", e.getMessage()));
        }
    }

    /**
     * Get a specific credential template by ID.
     *
     * <p>The path variable is a UUID. Non-UUID values (e.g. a credential_name or a stray
     * {@code template-<slug>} probe) resolve as 404 rather than 500 - a malformed path
     * segment semantically means "no such resource", and returning 500 spams the logs
     * with stacktraces for what is, from the server's point of view, a normal miss.
     *
     * GET /api/catalog/credentials/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getCredentialTemplate(@PathVariable String id) {
        java.util.UUID uuid;
        try {
            uuid = java.util.UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }

        try {
            String sql = """
                SELECT c.id, c.credential_name, c.display_name, c.description, c.credential_type, c.auth_type,
                       c.variant, c.test_endpoint, c.documentation_url, c.icon_url, c.icon_slug,
                       c.properties, c.extends_, c.metadata, c.created_at, c.updated_at,
                       (SELECT COALESCE(jsonb_agg(jsonb_build_object('variant', c2.variant, 'auth_type', c2.auth_type)
                                                  ORDER BY c2.variant ASC), '[]'::jsonb)
                        FROM catalog.credentials c2
                        WHERE c2.credential_name = c.credential_name) AS variants
                FROM catalog.credentials c
                WHERE c.id = ?
                """;

            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, uuid);

            if (results.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            Map<String, Object> row = results.get(0);
            unwrapVariants(row);
            // Same gate as the list/by-name endpoints: strip admin-disabled variants,
            // 404 if the requested row no longer has any usable variant left.
            Set<String> disabled = fetchDisabledVariantKeys();
            if (filterDisabledVariants(row, disabled) == 0) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(row);
        } catch (Exception e) {
            log.error("Error fetching credential template", e);
            return ResponseEntity.status(500).body(Map.of("error", "Failed to fetch credential template", "message", e.getMessage()));
        }
    }
}

