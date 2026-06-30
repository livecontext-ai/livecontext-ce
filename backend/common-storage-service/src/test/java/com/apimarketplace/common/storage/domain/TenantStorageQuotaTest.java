package com.apimarketplace.common.storage.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TenantStorageQuota Domain Entity Tests")
class TenantStorageQuotaTest {

    private static final String TENANT_ID = "tenant-123";
    private static final long MAX_BYTES = 1_073_741_824L; // 1GB

    @Nested
    @DisplayName("Default Constructor")
    class DefaultConstructorTests {

        @Test
        @DisplayName("should create entity with null fields")
        void shouldCreateWithNullFields() {
            TenantStorageQuota quota = new TenantStorageQuota();

            assertThat(quota.getTenantId()).isNull();
            assertThat(quota.getMaxBytes()).isNull();
            assertThat(quota.getUsedBytes()).isNull();
        }
    }

    @Nested
    @DisplayName("Parameterized Constructor")
    class ParameterizedConstructorTests {

        @Test
        @DisplayName("should set tenantId and maxBytes")
        void shouldSetTenantIdAndMaxBytes() {
            TenantStorageQuota quota = new TenantStorageQuota(TENANT_ID, MAX_BYTES);

            assertThat(quota.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(quota.getMaxBytes()).isEqualTo(MAX_BYTES);
        }

        @Test
        @DisplayName("should initialize usedBytes to zero")
        void shouldInitializeUsedBytesToZero() {
            TenantStorageQuota quota = new TenantStorageQuota(TENANT_ID, MAX_BYTES);

            assertThat(quota.getUsedBytes()).isZero();
        }

        @Test
        @DisplayName("should set soft limit to 80% of max bytes")
        void shouldSetSoftLimitTo80Percent() {
            TenantStorageQuota quota = new TenantStorageQuota(TENANT_ID, 1000L);

            assertThat(quota.getSoftLimitBytes()).isEqualTo(800L);
        }

        @Test
        @DisplayName("should set hard limit equal to max bytes")
        void shouldSetHardLimitEqualToMaxBytes() {
            TenantStorageQuota quota = new TenantStorageQuota(TENANT_ID, MAX_BYTES);

            assertThat(quota.getHardLimitBytes()).isEqualTo(MAX_BYTES);
        }

        @Test
        @DisplayName("should set timestamps on creation")
        void shouldSetTimestampsOnCreation() {
            Instant before = Instant.now();

            TenantStorageQuota quota = new TenantStorageQuota(TENANT_ID, MAX_BYTES);

            Instant after = Instant.now();

            assertThat(quota.getCreatedAt()).isBetween(before, after);
            assertThat(quota.getUpdatedAt()).isBetween(before, after);
        }
    }

    @Nested
    @DisplayName("getAvailableBytes")
    class GetAvailableBytesTests {

        @Test
        @DisplayName("should return correct available bytes")
        void shouldReturnCorrectAvailableBytes() {
            TenantStorageQuota quota = new TenantStorageQuota(TENANT_ID, 1000L);
            quota.setUsedBytes(300L);

            assertThat(quota.getAvailableBytes()).isEqualTo(700L);
        }

        @Test
        @DisplayName("should return zero when usage exceeds max")
        void shouldReturnZeroWhenUsageExceedsMax() {
            TenantStorageQuota quota = new TenantStorageQuota(TENANT_ID, 1000L);
            quota.setUsedBytes(1500L);

            assertThat(quota.getAvailableBytes()).isZero();
        }

        @Test
        @DisplayName("should return max bytes when nothing is used")
        void shouldReturnMaxBytesWhenNothingUsed() {
            TenantStorageQuota quota = new TenantStorageQuota(TENANT_ID, 1000L);

            assertThat(quota.getAvailableBytes()).isEqualTo(1000L);
        }
    }

    @Nested
    @DisplayName("getUsagePercentage")
    class GetUsagePercentageTests {

        @ParameterizedTest
        @CsvSource({
            "1000, 500, 50.0",
            "1000, 0, 0.0",
            "1000, 1000, 100.0",
            "1000, 250, 25.0",
            "200, 50, 25.0"
        })
        @DisplayName("should calculate correct usage percentage")
        void shouldCalculateCorrectPercentage(long maxBytes, long usedBytes, double expectedPercentage) {
            TenantStorageQuota quota = new TenantStorageQuota(TENANT_ID, maxBytes);
            quota.setUsedBytes(usedBytes);

            assertThat(quota.getUsagePercentage()).isEqualTo(expectedPercentage);
        }

        @Test
        @DisplayName("should return 0 when maxBytes is zero")
        void shouldReturnZeroWhenMaxBytesIsZero() {
            TenantStorageQuota quota = new TenantStorageQuota(TENANT_ID, 0L);
            quota.setUsedBytes(0L);

            assertThat(quota.getUsagePercentage()).isZero();
        }
    }

    @Nested
    @DisplayName("isSoftLimitReached")
    class IsSoftLimitReachedTests {

        @Test
        @DisplayName("should return true when used equals soft limit")
        void shouldReturnTrueWhenUsedEqualsSoftLimit() {
            TenantStorageQuota quota = new TenantStorageQuota(TENANT_ID, 1000L);
            // Soft limit = 800
            quota.setUsedBytes(800L);

            assertThat(quota.isSoftLimitReached()).isTrue();
        }

        @Test
        @DisplayName("should return true when used exceeds soft limit")
        void shouldReturnTrueWhenUsedExceedsSoftLimit() {
            TenantStorageQuota quota = new TenantStorageQuota(TENANT_ID, 1000L);
            quota.setUsedBytes(900L);

            assertThat(quota.isSoftLimitReached()).isTrue();
        }

        @Test
        @DisplayName("should return false when below soft limit")
        void shouldReturnFalseWhenBelowSoftLimit() {
            TenantStorageQuota quota = new TenantStorageQuota(TENANT_ID, 1000L);
            quota.setUsedBytes(799L);

            assertThat(quota.isSoftLimitReached()).isFalse();
        }
    }

    @Nested
    @DisplayName("isHardLimitReached")
    class IsHardLimitReachedTests {

        @Test
        @DisplayName("should return true when used equals hard limit")
        void shouldReturnTrueWhenUsedEqualsHardLimit() {
            TenantStorageQuota quota = new TenantStorageQuota(TENANT_ID, 1000L);
            quota.setUsedBytes(1000L);

            assertThat(quota.isHardLimitReached()).isTrue();
        }

        @Test
        @DisplayName("should return true when used exceeds hard limit")
        void shouldReturnTrueWhenUsedExceedsHardLimit() {
            TenantStorageQuota quota = new TenantStorageQuota(TENANT_ID, 1000L);
            quota.setUsedBytes(1100L);

            assertThat(quota.isHardLimitReached()).isTrue();
        }

        @Test
        @DisplayName("should return false when below hard limit")
        void shouldReturnFalseWhenBelowHardLimit() {
            TenantStorageQuota quota = new TenantStorageQuota(TENANT_ID, 1000L);
            quota.setUsedBytes(999L);

            assertThat(quota.isHardLimitReached()).isFalse();
        }
    }

    @Nested
    @DisplayName("canStore")
    class CanStoreTests {

        @Test
        @DisplayName("should return true when enough space available")
        void shouldReturnTrueWhenEnoughSpace() {
            TenantStorageQuota quota = new TenantStorageQuota(TENANT_ID, 1000L);
            quota.setUsedBytes(500L);

            assertThat(quota.canStore(400L)).isTrue();
        }

        @Test
        @DisplayName("should return true when exactly at hard limit after storing")
        void shouldReturnTrueWhenExactlyAtHardLimit() {
            TenantStorageQuota quota = new TenantStorageQuota(TENANT_ID, 1000L);
            quota.setUsedBytes(500L);

            assertThat(quota.canStore(500L)).isTrue();
        }

        @Test
        @DisplayName("should return false when exceeding hard limit")
        void shouldReturnFalseWhenExceedingHardLimit() {
            TenantStorageQuota quota = new TenantStorageQuota(TENANT_ID, 1000L);
            quota.setUsedBytes(500L);

            assertThat(quota.canStore(501L)).isFalse();
        }

        @Test
        @DisplayName("does not overflow when additional bytes are extremely large")
        void canStoreDoesNotOverflowForHugeAdditionalBytes() {
            TenantStorageQuota quota = new TenantStorageQuota(TENANT_ID, 1000L);
            quota.setUsedBytes(500L);

            assertThat(quota.canStore(Long.MAX_VALUE))
                    .as("A huge upload must not overflow used+additional into a false OK")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("getQuotaStatus")
    class GetQuotaStatusTests {

        @Test
        @DisplayName("should return OK when below soft limit")
        void shouldReturnOkWhenBelowSoftLimit() {
            TenantStorageQuota quota = new TenantStorageQuota(TENANT_ID, 1000L);
            quota.setUsedBytes(500L);

            assertThat(quota.getQuotaStatus()).isEqualTo(QuotaStatus.OK);
        }

        @Test
        @DisplayName("should return SOFT_LIMIT_REACHED when at soft limit but below hard limit")
        void shouldReturnSoftLimitReached() {
            TenantStorageQuota quota = new TenantStorageQuota(TENANT_ID, 1000L);
            // Soft limit = 800, Hard limit = 1000
            quota.setUsedBytes(850L);

            assertThat(quota.getQuotaStatus()).isEqualTo(QuotaStatus.SOFT_LIMIT_REACHED);
        }

        @Test
        @DisplayName("should return HARD_LIMIT_REACHED when at hard limit")
        void shouldReturnHardLimitReached() {
            TenantStorageQuota quota = new TenantStorageQuota(TENANT_ID, 1000L);
            quota.setUsedBytes(1000L);

            assertThat(quota.getQuotaStatus()).isEqualTo(QuotaStatus.HARD_LIMIT_REACHED);
        }

        @Test
        @DisplayName("should return HARD_LIMIT_REACHED when above hard limit")
        void shouldReturnHardLimitReachedAbove() {
            TenantStorageQuota quota = new TenantStorageQuota(TENANT_ID, 1000L);
            quota.setUsedBytes(1200L);

            assertThat(quota.getQuotaStatus()).isEqualTo(QuotaStatus.HARD_LIMIT_REACHED);
        }
    }

    @Nested
    @DisplayName("Getters and Setters")
    class GettersSettersTests {

        @Test
        @DisplayName("should set and get all fields")
        void shouldSetAndGetAllFields() {
            TenantStorageQuota quota = new TenantStorageQuota();
            Instant now = Instant.now();

            quota.setTenantId("t-1");
            quota.setMaxBytes(2000L);
            quota.setUsedBytes(500L);
            quota.setSoftLimitBytes(1600L);
            quota.setHardLimitBytes(2000L);
            quota.setCreatedAt(now);
            quota.setUpdatedAt(now);

            assertThat(quota.getTenantId()).isEqualTo("t-1");
            assertThat(quota.getMaxBytes()).isEqualTo(2000L);
            assertThat(quota.getUsedBytes()).isEqualTo(500L);
            assertThat(quota.getSoftLimitBytes()).isEqualTo(1600L);
            assertThat(quota.getHardLimitBytes()).isEqualTo(2000L);
            assertThat(quota.getCreatedAt()).isEqualTo(now);
            assertThat(quota.getUpdatedAt()).isEqualTo(now);
        }
    }
}
