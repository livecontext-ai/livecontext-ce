package com.apimarketplace.common.storage.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration pour l'integration avec le catalog-service pour le mapping.
 *
 * <p>L'URL du catalog est resolue avec la precedence suivante (cf. {@link #getCatalogBaseUrl()}) :
 * <ol>
 *   <li>{@code storage.mapping.catalog-base-url} - override explicite (retro-compat docker-compose/systemd) ;</li>
 *   <li>{@code services.catalog-url} - convention plateforme standard (le helm injecte {@code SERVICES_CATALOG_URL}
 *       = DNS de service in-cluster ; le profil CE le pointe sur le monolithe) ;</li>
 *   <li>{@code http://localhost:8081} - repli pour un run dev single-host.</li>
 * </ol>
 *
 * <p>Pourquoi : ce bean est embarque par tous les services qui possedent le schema {@code storage}
 * (orchestrator, datasource, agent, ...). Avant ce fix, {@code catalogBaseUrl} etait code en dur sur
 * {@code localhost:8081} et n'etait relie a aucune variable d'env - correct en mono-hote (systemd) mais
 * casse des la migration k3s (chaque service = son pod : {@code localhost:8081} ne pointe sur rien →
 * {@code Connection refused}). On herite desormais de {@code services.catalog-url}, deja injecte partout.
 */
@Configuration
@ConfigurationProperties(prefix = "storage.mapping")
public class StorageMappingConfig {

    /** Repli pour un run dev single-host quand aucune URL plateforme n'est fournie. */
    static final String DEV_FALLBACK_CATALOG_BASE_URL = "http://localhost:8081";

    /** Override explicite via {@code storage.mapping.catalog-base-url}. Null/blank ⇒ on herite de {@link #defaultCatalogBaseUrl}. */
    private String catalogBaseUrl;
    private boolean enabled = true;
    private int timeoutSeconds = 30;

    /**
     * URL catalog plateforme utilisee quand aucun override explicite n'est pose.
     * Liee a la convention standard {@code services.catalog-url} (cf. javadoc de la classe).
     */
    private final String defaultCatalogBaseUrl;

    // Design : on garde le binding @ConfigurationProperties (prefix storage.mapping) pour l'override
    // explicite via le setter, ET on injecte le defaut plateforme par @Value au constructeur. Le
    // no-arg constructeur (conserve pour les tests / construction hors-Spring) force le binding JavaBean
    // (setter) plutot que constructor-binding - sans lui, Spring tenterait un constructor-binding et
    // ignorerait le @Value. C'est pourquoi on ne reutilise pas le simple @Value("${a:${b:c}}") imbrique.

    /** Construction simple (tests / hors-Spring) : repli dev. */
    public StorageMappingConfig() {
        this(DEV_FALLBACK_CATALOG_BASE_URL);
    }

    @Autowired
    public StorageMappingConfig(
            @Value("${services.catalog-url:" + DEV_FALLBACK_CATALOG_BASE_URL + "}") String defaultCatalogBaseUrl) {
        this.defaultCatalogBaseUrl = defaultCatalogBaseUrl;
    }

    /**
     * URL catalog effective : l'override explicite {@code storage.mapping.catalog-base-url} s'il est pose,
     * sinon l'URL plateforme {@code services.catalog-url} (cf. {@link #defaultCatalogBaseUrl}).
     */
    public String getCatalogBaseUrl() {
        return (catalogBaseUrl != null && !catalogBaseUrl.isBlank()) ? catalogBaseUrl : defaultCatalogBaseUrl;
    }

    public void setCatalogBaseUrl(String catalogBaseUrl) {
        this.catalogBaseUrl = catalogBaseUrl;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
