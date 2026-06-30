package com.apimarketplace.common.storage.config;

/**
 * Constantes centralisees pour le module de stockage.
 * Evite la duplication de valeurs magiques.
 */
public final class StorageConstants {

    private StorageConstants() {
        // Classe utilitaire, pas d'instanciation
    }

    // ========== Cache names ==========
    public static final String CACHE_QUOTA_STATUS = "quotaStatus";
    public static final String CACHE_TENANT_QUOTA = "tenantQuota";
    // PR18 - org-scope peers of CACHE_QUOTA_STATUS / CACHE_TENANT_QUOTA.
    // Distinct namespaces so a userId and an org UUID cannot collide in
    // the same Caffeine map and serve the wrong scope's row.
    public static final String CACHE_ORG_QUOTA_STATUS = "orgQuotaStatus";
    public static final String CACHE_ORG_QUOTA = "orgQuota";
    public static final String CACHE_STORAGE_DATA = "storageData";
    public static final String CACHE_MAPPING_RESULTS = "mappingResults";
    public static final String CACHE_MAPPING_SPECS = "mappingSpecs";

    // ========== Valeurs par defaut pour les quotas ==========
    // Matches QuotaService.DEFAULT_MAX_BYTES - the FREE plan's
    // included_storage_bytes from V4__seed_auth_data.sql. Used as a safety
    // fallback only; live tenants get their plan quota applied via
    // auth-service's SubscriptionService (routed through PlanStorageQuotaSyncer).
    public static final long DEFAULT_MAX_BYTES = 104_857_600L; // 100 MB (FREE)
    public static final double DEFAULT_SOFT_LIMIT_RATIO = 0.8; // 80%

    // ========== Types de stockage ==========
    public static final String STORAGE_TYPE_JSON = "JSON";
    public static final String STORAGE_TYPE_TEXT = "TEXT";
    public static final String STORAGE_TYPE_BINARY = "BINARY";

    // ========== JsonPath defaults ==========
    public static final String DEFAULT_JSON_PATH = "$";
    public static final String DEFAULT_FORMAT = "json";

    // ========== Limites ==========
    public static final int PREVIEW_LIMIT = 200;
    public static final int MAX_DEPTH = 50;
    public static final int MAX_OBJECT_KEYS = 10_000;
    public static final int MAX_ARRAY_ITEMS = 500;

    // ========== Scheduling ==========
    public static final long CLEANUP_INTERVAL_MS = 3_600_000L; // 1 heure
}
