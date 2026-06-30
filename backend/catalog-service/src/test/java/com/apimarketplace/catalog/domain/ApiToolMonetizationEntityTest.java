package com.apimarketplace.catalog.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApiToolMonetizationEntity class.
 *
 * Tests constructors, getters, setters, enums, and basic entity behavior.
 */
@DisplayName("ApiToolMonetizationEntity Tests")
class ApiToolMonetizationEntityTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // ENUM TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Enums")
    class EnumTests {

        @Test
        @DisplayName("MonetizationType should have FREEMIUM and PAID values")
        void monetizationTypeShouldHaveCorrectValues() {
            assertEquals(2, ApiToolMonetizationEntity.MonetizationType.values().length);
            assertNotNull(ApiToolMonetizationEntity.MonetizationType.FREEMIUM);
            assertNotNull(ApiToolMonetizationEntity.MonetizationType.PAID);
        }

        @Test
        @DisplayName("PlanName should have BASIC, PRO, ULTRA, MEGA values")
        void planNameShouldHaveCorrectValues() {
            assertEquals(4, ApiToolMonetizationEntity.PlanName.values().length);
            assertNotNull(ApiToolMonetizationEntity.PlanName.BASIC);
            assertNotNull(ApiToolMonetizationEntity.PlanName.PRO);
            assertNotNull(ApiToolMonetizationEntity.PlanName.ULTRA);
            assertNotNull(ApiToolMonetizationEntity.PlanName.MEGA);
        }

        @Test
        @DisplayName("Should convert enum to string correctly")
        void shouldConvertEnumToString() {
            assertEquals("FREEMIUM", ApiToolMonetizationEntity.MonetizationType.FREEMIUM.name());
            assertEquals("PAID", ApiToolMonetizationEntity.MonetizationType.PAID.name());
            assertEquals("BASIC", ApiToolMonetizationEntity.PlanName.BASIC.name());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTRUCTOR TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("Should create entity with default constructor")
        void shouldCreateWithDefaultConstructor() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();

            assertNotNull(entity);
            assertNull(entity.getId());
            assertNull(entity.getApiToolId());
            assertNull(entity.getMonetizationType());
            assertNull(entity.getPlanName());
            assertNull(entity.getRateLimitRequests());
            assertNull(entity.getRateLimitPeriod());
            assertNull(entity.getFreeRequests());
            assertNull(entity.getFreeRequestsType());
            assertNull(entity.getMauValue());
            assertNull(entity.getPricePerMau());
            assertNull(entity.getCalls());
            assertNull(entity.getQuota());
            assertNull(entity.getPrice());
            assertNull(entity.getOverusageCost());
            assertNull(entity.getHardLimit());
            assertNull(entity.getCreatedAt());
            assertNull(entity.getUpdatedAt());
            assertEquals("1.0.0", entity.getVersion()); // Default value
        }

        @Test
        @DisplayName("Should create entity with all-args constructor")
        void shouldCreateWithAllArgsConstructor() {
            UUID id = UUID.randomUUID();
            UUID apiToolId = UUID.randomUUID();
            String monetizationType = "FREEMIUM";
            String planName = "PRO";
            Integer rateLimitRequests = 1000;
            String rateLimitPeriod = "month";
            Integer freeRequests = 100;
            Integer mauValue = 50;
            BigDecimal pricePerMau = new BigDecimal("0.10");
            Integer calls = 20;
            Integer quota = 500;
            BigDecimal price = new BigDecimal("29.99");
            BigDecimal overusageCost = new BigDecimal("0.05");
            Boolean hardLimit = false;
            Long createdAt = System.currentTimeMillis();
            Long updatedAt = System.currentTimeMillis();

            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity(
                id, apiToolId, monetizationType, planName, rateLimitRequests, rateLimitPeriod,
                freeRequests, mauValue, pricePerMau, calls, quota, price, overusageCost,
                hardLimit, createdAt, updatedAt
            );

            assertEquals(id, entity.getId());
            assertEquals(apiToolId, entity.getApiToolId());
            assertEquals(monetizationType, entity.getMonetizationType());
            assertEquals(planName, entity.getPlanName());
            assertEquals(rateLimitRequests, entity.getRateLimitRequests());
            assertEquals(rateLimitPeriod, entity.getRateLimitPeriod());
            assertEquals(freeRequests, entity.getFreeRequests());
            assertEquals(mauValue, entity.getMauValue());
            assertEquals(pricePerMau, entity.getPricePerMau());
            assertEquals(calls, entity.getCalls());
            assertEquals(quota, entity.getQuota());
            assertEquals(price, entity.getPrice());
            assertEquals(overusageCost, entity.getOverusageCost());
            assertEquals(hardLimit, entity.getHardLimit());
            assertEquals(createdAt, entity.getCreatedAt());
            assertEquals(updatedAt, entity.getUpdatedAt());
        }

        @Test
        @DisplayName("Should allow null values in all-args constructor")
        void shouldAllowNullValuesInConstructor() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
            );

            assertNotNull(entity);
            assertNull(entity.getId());
            assertNull(entity.getApiToolId());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GETTER/SETTER TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("Should set and get id")
        void shouldSetAndGetId() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();
            UUID id = UUID.randomUUID();

            entity.setId(id);

            assertEquals(id, entity.getId());
        }

        @Test
        @DisplayName("Should set and get apiToolId")
        void shouldSetAndGetApiToolId() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();
            UUID apiToolId = UUID.randomUUID();

            entity.setApiToolId(apiToolId);

            assertEquals(apiToolId, entity.getApiToolId());
        }

        @Test
        @DisplayName("Should set and get monetizationType")
        void shouldSetAndGetMonetizationType() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();

            entity.setMonetizationType("PAID");

            assertEquals("PAID", entity.getMonetizationType());
        }

        @Test
        @DisplayName("Should set and get planName")
        void shouldSetAndGetPlanName() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();

            entity.setPlanName("ULTRA");

            assertEquals("ULTRA", entity.getPlanName());
        }

        @Test
        @DisplayName("Should set and get rateLimitRequests")
        void shouldSetAndGetRateLimitRequests() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();

            entity.setRateLimitRequests(5000);

            assertEquals(5000, entity.getRateLimitRequests());
        }

        @Test
        @DisplayName("Should set and get rateLimitPeriod")
        void shouldSetAndGetRateLimitPeriod() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();

            entity.setRateLimitPeriod("day");

            assertEquals("day", entity.getRateLimitPeriod());
        }

        @Test
        @DisplayName("Should set and get freeRequests")
        void shouldSetAndGetFreeRequests() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();

            entity.setFreeRequests(50);

            assertEquals(50, entity.getFreeRequests());
        }

        @Test
        @DisplayName("Should set and get freeRequestsType")
        void shouldSetAndGetFreeRequestsType() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();

            entity.setFreeRequestsType("per-user");

            assertEquals("per-user", entity.getFreeRequestsType());
        }

        @Test
        @DisplayName("Should set and get mauValue")
        void shouldSetAndGetMauValue() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();

            entity.setMauValue(100);

            assertEquals(100, entity.getMauValue());
        }

        @Test
        @DisplayName("Should set and get pricePerMau")
        void shouldSetAndGetPricePerMau() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();
            BigDecimal price = new BigDecimal("0.15");

            entity.setPricePerMau(price);

            assertEquals(price, entity.getPricePerMau());
        }

        @Test
        @DisplayName("Should set and get calls")
        void shouldSetAndGetCalls() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();

            entity.setCalls(25);

            assertEquals(25, entity.getCalls());
        }

        @Test
        @DisplayName("Should set and get quota")
        void shouldSetAndGetQuota() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();

            entity.setQuota(1000);

            assertEquals(1000, entity.getQuota());
        }

        @Test
        @DisplayName("Should set and get price")
        void shouldSetAndGetPrice() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();
            BigDecimal price = new BigDecimal("99.99");

            entity.setPrice(price);

            assertEquals(price, entity.getPrice());
        }

        @Test
        @DisplayName("Should set and get overusageCost")
        void shouldSetAndGetOverusageCost() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();
            BigDecimal cost = new BigDecimal("0.01");

            entity.setOverusageCost(cost);

            assertEquals(cost, entity.getOverusageCost());
        }

        @Test
        @DisplayName("Should set and get hardLimit")
        void shouldSetAndGetHardLimit() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();

            entity.setHardLimit(true);

            assertTrue(entity.getHardLimit());
        }

        @Test
        @DisplayName("Should set and get version")
        void shouldSetAndGetVersion() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();

            entity.setVersion("2.0.0");

            assertEquals("2.0.0", entity.getVersion());
        }

        @Test
        @DisplayName("Should set and get createdAt")
        void shouldSetAndGetCreatedAt() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();
            Long createdAt = 1700000000000L;

            entity.setCreatedAt(createdAt);

            assertEquals(createdAt, entity.getCreatedAt());
        }

        @Test
        @DisplayName("Should set and get updatedAt")
        void shouldSetAndGetUpdatedAt() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();
            Long updatedAt = 1700000000000L;

            entity.setUpdatedAt(updatedAt);

            assertEquals(updatedAt, entity.getUpdatedAt());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BIGDECIMAL TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("BigDecimal Handling")
    class BigDecimalTests {

        @Test
        @DisplayName("Should handle zero price")
        void shouldHandleZeroPrice() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();

            entity.setPrice(BigDecimal.ZERO);
            entity.setPricePerMau(BigDecimal.ZERO);
            entity.setOverusageCost(BigDecimal.ZERO);

            assertEquals(BigDecimal.ZERO, entity.getPrice());
            assertEquals(BigDecimal.ZERO, entity.getPricePerMau());
            assertEquals(BigDecimal.ZERO, entity.getOverusageCost());
        }

        @Test
        @DisplayName("Should handle large price values")
        void shouldHandleLargePriceValues() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();
            BigDecimal largeValue = new BigDecimal("999999.99");

            entity.setPrice(largeValue);

            assertEquals(largeValue, entity.getPrice());
        }

        @Test
        @DisplayName("Should handle precise decimal values")
        void shouldHandlePreciseDecimalValues() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();
            BigDecimal preciseValue = new BigDecimal("0.001234567890");

            entity.setPricePerMau(preciseValue);

            assertEquals(preciseValue, entity.getPricePerMau());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE CASES
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle null freeRequestsType")
        void shouldHandleNullFreeRequestsType() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();
            entity.setFreeRequestsType("per-user");

            entity.setFreeRequestsType(null);

            assertNull(entity.getFreeRequestsType());
        }

        @Test
        @DisplayName("Should handle global freeRequestsType")
        void shouldHandleGlobalFreeRequestsType() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();

            entity.setFreeRequestsType("global");

            assertEquals("global", entity.getFreeRequestsType());
        }

        @Test
        @DisplayName("Should handle zero values for integers")
        void shouldHandleZeroIntegerValues() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();

            entity.setRateLimitRequests(0);
            entity.setFreeRequests(0);
            entity.setMauValue(0);
            entity.setCalls(0);
            entity.setQuota(0);

            assertEquals(0, entity.getRateLimitRequests());
            assertEquals(0, entity.getFreeRequests());
            assertEquals(0, entity.getMauValue());
            assertEquals(0, entity.getCalls());
            assertEquals(0, entity.getQuota());
        }

        @Test
        @DisplayName("Should handle hardLimit as false")
        void shouldHandleHardLimitFalse() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();

            entity.setHardLimit(false);

            assertFalse(entity.getHardLimit());
        }

        @Test
        @DisplayName("Should handle null hardLimit")
        void shouldHandleNullHardLimit() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();
            entity.setHardLimit(true);

            entity.setHardLimit(null);

            assertNull(entity.getHardLimit());
        }

        @Test
        @DisplayName("Should override default version")
        void shouldOverrideDefaultVersion() {
            ApiToolMonetizationEntity entity = new ApiToolMonetizationEntity();
            assertEquals("1.0.0", entity.getVersion());

            entity.setVersion("3.0.0");

            assertEquals("3.0.0", entity.getVersion());
        }
    }
}
