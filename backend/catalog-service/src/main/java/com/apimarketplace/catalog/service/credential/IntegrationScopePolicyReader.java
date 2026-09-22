package com.apimarketplace.catalog.service.credential;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads an integration's OAuth scope policy out of its catalog credential template.
 *
 * <p>The template row is {@code catalog.credentials}, keyed by
 * {@code credential_name} - the same value an API carries as
 * {@code platform_credential_name} and the same one the agent-facing tool info calls
 * {@code integrationName}. Its {@code metadata} holds {@code oauth2Config}, written
 * by the seed importer and by the signed catalog bundle.
 *
 * <p>Every failure answers {@link IntegrationScopePolicy#unknown()}: an unreadable
 * template must make the capability go quiet, never make it assert something. See the
 * class note on {@code IntegrationScopePolicy} for why silence is the safe direction
 * here specifically.
 */
@Slf4j
@Service
public class IntegrationScopePolicyReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;
    private final boolean ownClientIsTheOnlyClient;

    public IntegrationScopePolicyReader(JdbcTemplate jdbcTemplate,
                                        @Value("${auth.mode:}") String authMode) {
        this.jdbcTemplate = jdbcTemplate;
        // Keyed on auth.mode=embedded, exactly like OAuth2Service.isCeEmbeddedMode, and
        // deliberately NOT on "is this self-hosted": a self-hosted ENTERPRISE install
        // runs Keycloak and DOES have a platform-shared OAuth app, so it belongs on the
        // managed side of this line. Absent (every microservice deployment) reads as
        // false, which is the managed-cloud answer.
        this.ownClientIsTheOnlyClient = "embedded".equalsIgnoreCase(
                authMode == null ? "" : authMode.trim());
    }

    /** True when this install has no platform-shared OAuth app at all. */
    public boolean ownClientIsTheOnlyClient() {
        return ownClientIsTheOnlyClient;
    }

    /**
     * The scope policy declared for {@code integrationName}, or
     * {@link IntegrationScopePolicy#unknown()} when the template declares none, names
     * no row, or cannot be read.
     */
    public IntegrationScopePolicy forIntegration(String integrationName) {
        if (integrationName == null || integrationName.isBlank()) {
            return IntegrationScopePolicy.unknown();
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT metadata::text AS metadata FROM catalog.credentials "
                            + "WHERE credential_name = ? ORDER BY variant",
                    integrationName.trim());
            for (Map<String, Object> row : rows) {
                IntegrationScopePolicy policy = parse(row.get("metadata"));
                if (policy.declared()) {
                    return policy;
                }
            }
            return IntegrationScopePolicy.unknown();
        } catch (Exception e) {
            log.debug("Scope policy unavailable for integration {}: {}", integrationName, e.getMessage());
            return IntegrationScopePolicy.unknown();
        }
    }

    /**
     * Pulls {@code oauth2Config.scopes} / {@code oauth2Config.byokOnlyScopes} out of a
     * template's metadata, tolerating the legacy {@code metadata.value = "<json>"}
     * wrapping alongside the direct object shape - the catalog wire path leaves one
     * and the DB holds the other, and both reach readers of this column.
     */
    private IntegrationScopePolicy parse(Object rawMetadata) {
        if (rawMetadata == null) {
            return IntegrationScopePolicy.unknown();
        }
        try {
            JsonNode metadata = MAPPER.readTree(String.valueOf(rawMetadata));
            if (metadata.hasNonNull("value") && metadata.get("value").isTextual()) {
                metadata = MAPPER.readTree(metadata.get("value").asText());
            }
            JsonNode oauth2Config = metadata.path("oauth2Config");
            if (!oauth2Config.isObject()) {
                return IntegrationScopePolicy.unknown();
            }
            List<String> platform = stringList(oauth2Config.path("scopes"));
            List<String> byokOnly = stringList(oauth2Config.path("byokOnlyScopes"));
            if (platform.isEmpty() && byokOnly.isEmpty()) {
                // An oauth2Config that declares no scope at all says nothing about what a
                // standard connection can grant, so it is not a policy - answering
                // "declared" here would make every required scope look restricted.
                return IntegrationScopePolicy.unknown();
            }
            return IntegrationScopePolicy.declared(platform, byokOnly, ownClientIsTheOnlyClient);
        } catch (Exception e) {
            log.debug("Unparseable credential-template metadata: {}", e.getMessage());
            return IntegrationScopePolicy.unknown();
        }
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(entry -> {
            if (entry != null && entry.isTextual() && !entry.asText().isBlank()) {
                values.add(entry.asText().trim());
            }
        });
        return values;
    }
}
