package com.apimarketplace.catalog.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ToolSignalEntity class.
 *
 * Tests Lombok-generated constructors, getters, setters, equals, hashCode, and toString.
 */
@DisplayName("ToolSignalEntity Tests")
class ToolSignalEntityTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTRUCTOR TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("Should create entity with no-args constructor")
        void shouldCreateWithNoArgsConstructor() {
            ToolSignalEntity entity = new ToolSignalEntity();

            assertNotNull(entity);
            assertNull(entity.getToolId());
            assertNull(entity.getAction());
            assertNull(entity.getResource());
            assertNull(entity.getProvider());
            assertNull(entity.getMethod());
            assertNull(entity.getRequiresUserCredentials());
            assertNull(entity.getRunScope());
            assertNull(entity.getIsActive());
            assertNull(entity.getPopularity());
            assertNull(entity.getSuccessRate());
            assertNull(entity.getLatencyMsP50());
            assertNull(entity.getUpdatedAt());
        }

        @Test
        @DisplayName("Should create entity with all-args constructor")
        void shouldCreateWithAllArgsConstructor() {
            UUID toolId = UUID.randomUUID();
            String action = "fetch";
            String resource = "users";
            String provider = "github";
            String method = "GET";
            Boolean requiresUserCredentials = true;
            String runScope = "user";
            Boolean isActive = true;
            Integer popularity = 100;
            BigDecimal successRate = new BigDecimal("0.99");
            Integer latencyMsP50 = 150;
            Long updatedAt = System.currentTimeMillis();

            ToolSignalEntity entity = new ToolSignalEntity(
                toolId, action, resource, provider, method,
                requiresUserCredentials, runScope, isActive,
                popularity, successRate, latencyMsP50, updatedAt
            );

            assertEquals(toolId, entity.getToolId());
            assertEquals(action, entity.getAction());
            assertEquals(resource, entity.getResource());
            assertEquals(provider, entity.getProvider());
            assertEquals(method, entity.getMethod());
            assertEquals(requiresUserCredentials, entity.getRequiresUserCredentials());
            assertEquals(runScope, entity.getRunScope());
            assertEquals(isActive, entity.getIsActive());
            assertEquals(popularity, entity.getPopularity());
            assertEquals(successRate, entity.getSuccessRate());
            assertEquals(latencyMsP50, entity.getLatencyMsP50());
            assertEquals(updatedAt, entity.getUpdatedAt());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GETTER/SETTER TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("Should set and get toolId")
        void shouldSetAndGetToolId() {
            ToolSignalEntity entity = new ToolSignalEntity();
            UUID toolId = UUID.randomUUID();

            entity.setToolId(toolId);

            assertEquals(toolId, entity.getToolId());
        }

        @Test
        @DisplayName("Should set and get action")
        void shouldSetAndGetAction() {
            ToolSignalEntity entity = new ToolSignalEntity();

            entity.setAction("create");

            assertEquals("create", entity.getAction());
        }

        @Test
        @DisplayName("Should set and get resource")
        void shouldSetAndGetResource() {
            ToolSignalEntity entity = new ToolSignalEntity();

            entity.setResource("repositories");

            assertEquals("repositories", entity.getResource());
        }

        @Test
        @DisplayName("Should set and get provider")
        void shouldSetAndGetProvider() {
            ToolSignalEntity entity = new ToolSignalEntity();

            entity.setProvider("slack");

            assertEquals("slack", entity.getProvider());
        }

        @Test
        @DisplayName("Should set and get method")
        void shouldSetAndGetMethod() {
            ToolSignalEntity entity = new ToolSignalEntity();

            entity.setMethod("POST");

            assertEquals("POST", entity.getMethod());
        }

        @Test
        @DisplayName("Should set and get requiresUserCredentials")
        void shouldSetAndGetRequiresUserCredentials() {
            ToolSignalEntity entity = new ToolSignalEntity();

            entity.setRequiresUserCredentials(false);

            assertFalse(entity.getRequiresUserCredentials());
        }

        @Test
        @DisplayName("Should set and get runScope")
        void shouldSetAndGetRunScope() {
            ToolSignalEntity entity = new ToolSignalEntity();

            entity.setRunScope("global");

            assertEquals("global", entity.getRunScope());
        }

        @Test
        @DisplayName("Should set and get isActive")
        void shouldSetAndGetIsActive() {
            ToolSignalEntity entity = new ToolSignalEntity();

            entity.setIsActive(true);

            assertTrue(entity.getIsActive());
        }

        @Test
        @DisplayName("Should set and get popularity")
        void shouldSetAndGetPopularity() {
            ToolSignalEntity entity = new ToolSignalEntity();

            entity.setPopularity(500);

            assertEquals(500, entity.getPopularity());
        }

        @Test
        @DisplayName("Should set and get successRate")
        void shouldSetAndGetSuccessRate() {
            ToolSignalEntity entity = new ToolSignalEntity();
            BigDecimal rate = new BigDecimal("0.95");

            entity.setSuccessRate(rate);

            assertEquals(rate, entity.getSuccessRate());
        }

        @Test
        @DisplayName("Should set and get latencyMsP50")
        void shouldSetAndGetLatencyMsP50() {
            ToolSignalEntity entity = new ToolSignalEntity();

            entity.setLatencyMsP50(200);

            assertEquals(200, entity.getLatencyMsP50());
        }

        @Test
        @DisplayName("Should set and get updatedAt")
        void shouldSetAndGetUpdatedAt() {
            ToolSignalEntity entity = new ToolSignalEntity();
            Long updatedAt = 1700000000000L;

            entity.setUpdatedAt(updatedAt);

            assertEquals(updatedAt, entity.getUpdatedAt());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOMBOK @DATA TESTS (equals, hashCode, toString)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Lombok @Data")
    class LombokDataTests {

        @Test
        @DisplayName("Should implement equals correctly")
        void shouldImplementEquals() {
            UUID toolId = UUID.randomUUID();
            ToolSignalEntity entity1 = new ToolSignalEntity();
            entity1.setToolId(toolId);
            entity1.setAction("fetch");

            ToolSignalEntity entity2 = new ToolSignalEntity();
            entity2.setToolId(toolId);
            entity2.setAction("fetch");

            assertEquals(entity1, entity2);
        }

        @Test
        @DisplayName("Should not be equal when different")
        void shouldNotBeEqualWhenDifferent() {
            ToolSignalEntity entity1 = new ToolSignalEntity();
            entity1.setToolId(UUID.randomUUID());

            ToolSignalEntity entity2 = new ToolSignalEntity();
            entity2.setToolId(UUID.randomUUID());

            assertNotEquals(entity1, entity2);
        }

        @Test
        @DisplayName("Should have consistent hashCode")
        void shouldHaveConsistentHashCode() {
            UUID toolId = UUID.randomUUID();
            ToolSignalEntity entity1 = new ToolSignalEntity();
            entity1.setToolId(toolId);
            entity1.setAction("fetch");

            ToolSignalEntity entity2 = new ToolSignalEntity();
            entity2.setToolId(toolId);
            entity2.setAction("fetch");

            assertEquals(entity1.hashCode(), entity2.hashCode());
        }

        @Test
        @DisplayName("Should generate meaningful toString")
        void shouldGenerateMeaningfulToString() {
            ToolSignalEntity entity = new ToolSignalEntity();
            entity.setAction("fetch");
            entity.setProvider("github");

            String toString = entity.toString();

            assertNotNull(toString);
            assertTrue(toString.contains("ToolSignalEntity"));
            assertTrue(toString.contains("fetch"));
            assertTrue(toString.contains("github"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE CASES
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle zero popularity")
        void shouldHandleZeroPopularity() {
            ToolSignalEntity entity = new ToolSignalEntity();

            entity.setPopularity(0);

            assertEquals(0, entity.getPopularity());
        }

        @Test
        @DisplayName("Should handle zero latency")
        void shouldHandleZeroLatency() {
            ToolSignalEntity entity = new ToolSignalEntity();

            entity.setLatencyMsP50(0);

            assertEquals(0, entity.getLatencyMsP50());
        }

        @Test
        @DisplayName("Should handle 100% success rate")
        void shouldHandleFullSuccessRate() {
            ToolSignalEntity entity = new ToolSignalEntity();

            entity.setSuccessRate(BigDecimal.ONE);

            assertEquals(BigDecimal.ONE, entity.getSuccessRate());
        }

        @Test
        @DisplayName("Should handle 0% success rate")
        void shouldHandleZeroSuccessRate() {
            ToolSignalEntity entity = new ToolSignalEntity();

            entity.setSuccessRate(BigDecimal.ZERO);

            assertEquals(BigDecimal.ZERO, entity.getSuccessRate());
        }

        @Test
        @DisplayName("Should handle different HTTP methods")
        void shouldHandleDifferentHttpMethods() {
            ToolSignalEntity entity = new ToolSignalEntity();

            entity.setMethod("GET");
            assertEquals("GET", entity.getMethod());

            entity.setMethod("POST");
            assertEquals("POST", entity.getMethod());

            entity.setMethod("DELETE");
            assertEquals("DELETE", entity.getMethod());

            entity.setMethod("PATCH");
            assertEquals("PATCH", entity.getMethod());
        }
    }
}
