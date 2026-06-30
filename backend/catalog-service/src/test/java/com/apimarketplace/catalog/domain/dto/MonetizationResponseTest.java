package com.apimarketplace.catalog.domain.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MonetizationResponse record.
 *
 * MonetizationResponse is a DTO for monetization responses.
 */
@DisplayName("MonetizationResponse")
class MonetizationResponseTest {

    // ========================================================================
    // Record construction tests
    // ========================================================================

    @Nested
    @DisplayName("Record construction")
    class ConstructionTests {

        @Test
        @DisplayName("should create response with all fields")
        void shouldCreateResponseWithAllFields() {
            UUID id = UUID.randomUUID();
            UUID apiToolId = UUID.randomUUID();

            MonetizationResponse response = new MonetizationResponse(
                id, apiToolId, "FREEMIUM", "BASIC",
                1000, "hour", 100, "per-user",
                50, new BigDecimal("9.99"), 100, 500,
                new BigDecimal("49.99"), new BigDecimal("0.01"), true,
                System.currentTimeMillis(), System.currentTimeMillis()
            );

            assertEquals(id, response.id());
            assertEquals(apiToolId, response.apiToolId());
            assertEquals("FREEMIUM", response.monetizationType());
            assertEquals("BASIC", response.planName());
            assertEquals(1000, response.rateLimitRequests());
            assertEquals("hour", response.rateLimitPeriod());
            assertEquals(100, response.freeRequests());
            assertEquals("per-user", response.freeRequestsType());
            assertEquals(50, response.mauValue());
            assertEquals(new BigDecimal("9.99"), response.pricePerMau());
            assertEquals(100, response.calls());
            assertEquals(500, response.quota());
            assertEquals(new BigDecimal("49.99"), response.price());
            assertEquals(new BigDecimal("0.01"), response.overusageCost());
            assertTrue(response.hardLimit());
        }

        @Test
        @DisplayName("should accept null for optional fields")
        void shouldAcceptNullForOptionalFields() {
            UUID id = UUID.randomUUID();
            UUID apiToolId = UUID.randomUUID();

            MonetizationResponse response = new MonetizationResponse(
                id, apiToolId, "PAID", "PRO",
                null, null, null, null,
                null, null, null, null,
                new BigDecimal("99.99"), null, null,
                null, null
            );

            assertNull(response.rateLimitRequests());
            assertNull(response.freeRequests());
            assertNull(response.mauValue());
            assertNull(response.overusageCost());
        }
    }

    // ========================================================================
    // Monetization type tests
    // ========================================================================

    @Nested
    @DisplayName("Monetization types")
    class MonetizationTypeTests {

        @Test
        @DisplayName("should handle FREEMIUM type")
        void shouldHandleFreemiumType() {
            MonetizationResponse response = new MonetizationResponse(
                UUID.randomUUID(), UUID.randomUUID(),
                "FREEMIUM", "BASIC",
                100, "day", 50, "per-user",
                null, null, null, null,
                null, null, false,
                System.currentTimeMillis(), System.currentTimeMillis()
            );

            assertEquals("FREEMIUM", response.monetizationType());
            assertEquals(50, response.freeRequests());
            assertEquals("per-user", response.freeRequestsType());
        }

        @Test
        @DisplayName("should handle PAID type")
        void shouldHandlePaidType() {
            MonetizationResponse response = new MonetizationResponse(
                UUID.randomUUID(), UUID.randomUUID(),
                "PAID", "ULTRA",
                5000, "minute", null, null,
                100, new BigDecimal("19.99"), 200, 1000,
                new BigDecimal("199.99"), new BigDecimal("0.005"), true,
                System.currentTimeMillis(), System.currentTimeMillis()
            );

            assertEquals("PAID", response.monetizationType());
            assertEquals("ULTRA", response.planName());
            assertEquals(new BigDecimal("199.99"), response.price());
        }
    }

    // ========================================================================
    // Plan name tests
    // ========================================================================

    @Nested
    @DisplayName("Plan names")
    class PlanNameTests {

        @Test
        @DisplayName("should handle BASIC plan")
        void shouldHandleBasicPlan() {
            MonetizationResponse response = createResponseWithPlan("BASIC");
            assertEquals("BASIC", response.planName());
        }

        @Test
        @DisplayName("should handle PRO plan")
        void shouldHandleProPlan() {
            MonetizationResponse response = createResponseWithPlan("PRO");
            assertEquals("PRO", response.planName());
        }

        @Test
        @DisplayName("should handle ULTRA plan")
        void shouldHandleUltraPlan() {
            MonetizationResponse response = createResponseWithPlan("ULTRA");
            assertEquals("ULTRA", response.planName());
        }

        @Test
        @DisplayName("should handle MEGA plan")
        void shouldHandleMegaPlan() {
            MonetizationResponse response = createResponseWithPlan("MEGA");
            assertEquals("MEGA", response.planName());
        }

        private MonetizationResponse createResponseWithPlan(String planName) {
            return new MonetizationResponse(
                UUID.randomUUID(), UUID.randomUUID(),
                "PAID", planName,
                null, null, null, null,
                null, null, null, null,
                null, null, null, null, null
            );
        }
    }

    // ========================================================================
    // Record equality tests
    // ========================================================================

    @Nested
    @DisplayName("Record equality")
    class EqualityTests {

        @Test
        @DisplayName("should be equal for same values")
        void shouldBeEqualForSameValues() {
            UUID id = UUID.randomUUID();
            UUID apiToolId = UUID.randomUUID();

            MonetizationResponse resp1 = new MonetizationResponse(
                id, apiToolId, "FREEMIUM", "BASIC",
                100, "hour", 50, "per-user",
                null, null, null, null,
                null, null, false, 1000L, 2000L
            );

            MonetizationResponse resp2 = new MonetizationResponse(
                id, apiToolId, "FREEMIUM", "BASIC",
                100, "hour", 50, "per-user",
                null, null, null, null,
                null, null, false, 1000L, 2000L
            );

            assertEquals(resp1, resp2);
            assertEquals(resp1.hashCode(), resp2.hashCode());
        }

        @Test
        @DisplayName("should not be equal for different values")
        void shouldNotBeEqualForDifferentValues() {
            UUID id = UUID.randomUUID();
            UUID apiToolId = UUID.randomUUID();

            MonetizationResponse resp1 = new MonetizationResponse(
                id, apiToolId, "FREEMIUM", "BASIC",
                null, null, null, null,
                null, null, null, null,
                null, null, null, null, null
            );

            MonetizationResponse resp2 = new MonetizationResponse(
                id, apiToolId, "PAID", "PRO",
                null, null, null, null,
                null, null, null, null,
                null, null, null, null, null
            );

            assertNotEquals(resp1, resp2);
        }
    }
}
