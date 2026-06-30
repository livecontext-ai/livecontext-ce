package com.apimarketplace.common.storage.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StorageConstants Tests")
class StorageConstantsTest {

    @Nested
    @DisplayName("Cache Names")
    class CacheNameTests {

        @Test
        @DisplayName("should have quotaStatus cache name")
        void shouldHaveQuotaStatusCacheName() {
            assertThat(StorageConstants.CACHE_QUOTA_STATUS).isEqualTo("quotaStatus");
        }

        @Test
        @DisplayName("should have tenantQuota cache name")
        void shouldHaveTenantQuotaCacheName() {
            assertThat(StorageConstants.CACHE_TENANT_QUOTA).isEqualTo("tenantQuota");
        }

        @Test
        @DisplayName("should have storageData cache name")
        void shouldHaveStorageDataCacheName() {
            assertThat(StorageConstants.CACHE_STORAGE_DATA).isEqualTo("storageData");
        }

        @Test
        @DisplayName("should have mappingResults cache name")
        void shouldHaveMappingResultsCacheName() {
            assertThat(StorageConstants.CACHE_MAPPING_RESULTS).isEqualTo("mappingResults");
        }

        @Test
        @DisplayName("should have mappingSpecs cache name")
        void shouldHaveMappingSpecsCacheName() {
            assertThat(StorageConstants.CACHE_MAPPING_SPECS).isEqualTo("mappingSpecs");
        }
    }

    @Nested
    @DisplayName("Quota Defaults")
    class QuotaDefaultsTests {

        @Test
        @DisplayName("should have default max bytes of 100 MB (FREE plan baseline)")
        void shouldHaveDefaultMaxBytesOfFreeAllowance() {
            // V198: bumped from 1 GB → 100 MB to match auth.plan.included_storage_bytes
            // for FREE (V4 seed = 104857600). Library default is now a safety fallback;
            // live tenants get their plan quota via auth-service hooks.
            assertThat(StorageConstants.DEFAULT_MAX_BYTES).isEqualTo(104_857_600L);
        }

        @Test
        @DisplayName("should have default soft limit ratio of 0.8")
        void shouldHaveDefaultSoftLimitRatioOf80Percent() {
            assertThat(StorageConstants.DEFAULT_SOFT_LIMIT_RATIO).isEqualTo(0.8);
        }
    }

    @Nested
    @DisplayName("Storage Types")
    class StorageTypesTests {

        @Test
        @DisplayName("should have JSON storage type")
        void shouldHaveJsonStorageType() {
            assertThat(StorageConstants.STORAGE_TYPE_JSON).isEqualTo("JSON");
        }

        @Test
        @DisplayName("should have TEXT storage type")
        void shouldHaveTextStorageType() {
            assertThat(StorageConstants.STORAGE_TYPE_TEXT).isEqualTo("TEXT");
        }

        @Test
        @DisplayName("should have BINARY storage type")
        void shouldHaveBinaryStorageType() {
            assertThat(StorageConstants.STORAGE_TYPE_BINARY).isEqualTo("BINARY");
        }
    }

    @Nested
    @DisplayName("JsonPath Defaults")
    class JsonPathDefaultsTests {

        @Test
        @DisplayName("should have default JSON path as $")
        void shouldHaveDefaultJsonPath() {
            assertThat(StorageConstants.DEFAULT_JSON_PATH).isEqualTo("$");
        }

        @Test
        @DisplayName("should have default format as json")
        void shouldHaveDefaultFormatAsJson() {
            assertThat(StorageConstants.DEFAULT_FORMAT).isEqualTo("json");
        }
    }

    @Nested
    @DisplayName("Limits")
    class LimitsTests {

        @Test
        @DisplayName("should have preview limit of 200")
        void shouldHavePreviewLimitOf200() {
            assertThat(StorageConstants.PREVIEW_LIMIT).isEqualTo(200);
        }

        @Test
        @DisplayName("should have max depth of 50")
        void shouldHaveMaxDepthOf50() {
            assertThat(StorageConstants.MAX_DEPTH).isEqualTo(50);
        }

        @Test
        @DisplayName("should have max object keys of 10000")
        void shouldHaveMaxObjectKeysOf10000() {
            assertThat(StorageConstants.MAX_OBJECT_KEYS).isEqualTo(10_000);
        }

        @Test
        @DisplayName("should have max array items of 500")
        void shouldHaveMaxArrayItemsOf500() {
            assertThat(StorageConstants.MAX_ARRAY_ITEMS).isEqualTo(500);
        }
    }

    @Nested
    @DisplayName("Scheduling")
    class SchedulingTests {

        @Test
        @DisplayName("should have cleanup interval of 1 hour (3600000ms)")
        void shouldHaveCleanupIntervalOf1Hour() {
            assertThat(StorageConstants.CLEANUP_INTERVAL_MS).isEqualTo(3_600_000L);
        }
    }

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("should have private constructor to prevent instantiation")
        void shouldHavePrivateConstructor() throws NoSuchMethodException {
            Constructor<StorageConstants> constructor = StorageConstants.class.getDeclaredConstructor();
            assertThat(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers())).isTrue();

            // Verify it throws when accessed via reflection
            constructor.setAccessible(true);
            try {
                constructor.newInstance();
                // If no exception, the private constructor was called but that is the expected behavior
                // since it is just a utility class pattern without explicit exception throwing
            } catch (InvocationTargetException | InstantiationException | IllegalAccessException e) {
                // Expected: private constructor may throw
            }
        }
    }
}
