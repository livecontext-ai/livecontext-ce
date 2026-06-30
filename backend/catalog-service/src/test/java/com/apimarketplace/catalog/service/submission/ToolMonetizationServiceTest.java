package com.apimarketplace.catalog.service.submission;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiToolMonetizationEntity;
import com.apimarketplace.catalog.service.exception.MonetizationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ToolMonetizationService.
 *
 * ToolMonetizationService creates monetization entities from API submission data.
 */
@DisplayName("ToolMonetizationService")
class ToolMonetizationServiceTest {

    private ToolMonetizationService service;
    private ObjectMapper objectMapper;
    private ApiToolEntity tool;

    @BeforeEach
    void setUp() {
        service = new ToolMonetizationService();
        objectMapper = new ObjectMapper();
        tool = new ApiToolEntity();
        tool.setId(UUID.randomUUID());
    }

    // ========================================================================
    // createMonetizationFromApiData() - Basic tests
    // ========================================================================

    @Nested
    @DisplayName("createMonetizationFromApiData() - Basic")
    class CreateMonetizationBasicTests {

        @Test
        @DisplayName("should throw exception when monetization is null")
        void shouldThrowExceptionWhenMonetizationIsNull() {
            ObjectNode submissionData = objectMapper.createObjectNode();
            // No "monetization" field
            ObjectNode toolData = objectMapper.createObjectNode();
            toolData.put("name", "testTool");

            assertThrows(MonetizationException.class,
                () -> service.createMonetizationFromApiData(submissionData, toolData, tool));
        }

        @Test
        @DisplayName("should throw exception when no monetization configuration found")
        void shouldThrowExceptionWhenNoMonetizationConfigFound() {
            ObjectNode submissionData = objectMapper.createObjectNode();
            ObjectNode monetization = objectMapper.createObjectNode();
            monetization.put("pricing", "FREEMIUM");
            // No freeRequestsPerUser or toolFreeRequests
            submissionData.set("monetization", monetization);

            ObjectNode toolData = objectMapper.createObjectNode();
            toolData.put("name", "testTool");

            assertThrows(MonetizationException.class,
                () -> service.createMonetizationFromApiData(submissionData, toolData, tool));
        }
    }

    // ========================================================================
    // createMonetizationFromApiData() - FREEMIUM tests
    // ========================================================================

    @Nested
    @DisplayName("createMonetizationFromApiData() - FREEMIUM")
    class FreemiumTests {

        @Test
        @DisplayName("should create FREEMIUM monetization with tool-specific free requests")
        void shouldCreateFreemiumWithToolSpecificFreeRequests() {
            String toolName = "searchTool";
            ObjectNode submissionData = createFreemiumSubmissionWithToolFreeRequests(toolName, 100);
            ObjectNode toolData = objectMapper.createObjectNode();
            toolData.put("name", toolName);

            List<ApiToolMonetizationEntity> result = service.createMonetizationFromApiData(submissionData, toolData, tool);

            assertEquals(1, result.size());
            ApiToolMonetizationEntity entity = result.get(0);
            assertEquals("FREEMIUM", entity.getMonetizationType());
            assertEquals(100, entity.getFreeRequests());
            assertEquals(tool.getId(), entity.getApiToolId());
        }

        @Test
        @DisplayName("should create FREEMIUM monetization with global free requests")
        void shouldCreateFreemiumWithGlobalFreeRequests() {
            ObjectNode submissionData = objectMapper.createObjectNode();
            ObjectNode monetization = objectMapper.createObjectNode();
            monetization.put("pricing", "FREEMIUM");
            monetization.put("freeRequestsPerUser", 50);
            monetization.put("freeRequestsType", "per-user");
            submissionData.set("monetization", monetization);

            ObjectNode toolData = objectMapper.createObjectNode();
            toolData.put("name", "globalTool");

            List<ApiToolMonetizationEntity> result = service.createMonetizationFromApiData(submissionData, toolData, tool);

            assertEquals(1, result.size());
            ApiToolMonetizationEntity entity = result.get(0);
            assertEquals("FREEMIUM", entity.getMonetizationType());
            assertEquals(50, entity.getFreeRequests());
            assertEquals("per-user", entity.getFreeRequestsType());
        }

        @Test
        @DisplayName("should apply tool-specific rate limits for FREEMIUM")
        void shouldApplyToolSpecificRateLimitsForFreemium() {
            String toolName = "rateLimitedTool";
            ObjectNode submissionData = objectMapper.createObjectNode();
            ObjectNode monetization = objectMapper.createObjectNode();
            monetization.put("pricing", "FREEMIUM");

            ObjectNode toolFreeRequests = objectMapper.createObjectNode();
            toolFreeRequests.put(toolName, 100);
            monetization.set("toolFreeRequests", toolFreeRequests);

            ObjectNode toolRateLimits = objectMapper.createObjectNode();
            ObjectNode rateLimit = objectMapper.createObjectNode();
            rateLimit.put("requests", 10);
            rateLimit.put("period", "minute");
            toolRateLimits.set(toolName, rateLimit);
            monetization.set("toolRateLimits", toolRateLimits);

            submissionData.set("monetization", monetization);

            ObjectNode toolData = objectMapper.createObjectNode();
            toolData.put("name", toolName);

            List<ApiToolMonetizationEntity> result = service.createMonetizationFromApiData(submissionData, toolData, tool);

            assertEquals(1, result.size());
            ApiToolMonetizationEntity entity = result.get(0);
            assertEquals(10, entity.getRateLimitRequests());
            assertEquals("minute", entity.getRateLimitPeriod());
        }

        @Test
        @DisplayName("should apply global rate limits when tool-specific not present")
        void shouldApplyGlobalRateLimitsWhenToolSpecificNotPresent() {
            String toolName = "globalRateTool";
            ObjectNode submissionData = objectMapper.createObjectNode();
            ObjectNode monetization = objectMapper.createObjectNode();
            monetization.put("pricing", "FREEMIUM");

            ObjectNode toolFreeRequests = objectMapper.createObjectNode();
            toolFreeRequests.put(toolName, 100);
            monetization.set("toolFreeRequests", toolFreeRequests);

            ObjectNode globalRateLimit = objectMapper.createObjectNode();
            globalRateLimit.put("requests", 5);
            globalRateLimit.put("period", "second");
            monetization.set("rateLimit", globalRateLimit);

            submissionData.set("monetization", monetization);

            ObjectNode toolData = objectMapper.createObjectNode();
            toolData.put("name", toolName);

            List<ApiToolMonetizationEntity> result = service.createMonetizationFromApiData(submissionData, toolData, tool);

            assertEquals(1, result.size());
            ApiToolMonetizationEntity entity = result.get(0);
            assertEquals(5, entity.getRateLimitRequests());
            assertEquals("second", entity.getRateLimitPeriod());
        }

        @Test
        @DisplayName("should apply tool pricing for FREEMIUM")
        void shouldApplyToolPricingForFreemium() {
            String toolName = "pricedTool";
            ObjectNode submissionData = objectMapper.createObjectNode();
            ObjectNode monetization = objectMapper.createObjectNode();
            monetization.put("pricing", "FREEMIUM");

            ObjectNode toolFreeRequests = objectMapper.createObjectNode();
            toolFreeRequests.put(toolName, 100);
            monetization.set("toolFreeRequests", toolFreeRequests);

            ObjectNode toolPricing = objectMapper.createObjectNode();
            ObjectNode pricing = objectMapper.createObjectNode();
            pricing.put("mauValue", 1000);
            pricing.put("price", 9.99);
            pricing.put("calls", 5);
            toolPricing.set(toolName, pricing);
            monetization.set("toolPricing", toolPricing);

            submissionData.set("monetization", monetization);

            ObjectNode toolData = objectMapper.createObjectNode();
            toolData.put("name", toolName);

            List<ApiToolMonetizationEntity> result = service.createMonetizationFromApiData(submissionData, toolData, tool);

            assertEquals(1, result.size());
            ApiToolMonetizationEntity entity = result.get(0);
            assertEquals(1000, entity.getMauValue());
            assertEquals(new BigDecimal("9.99"), entity.getPricePerMau());
            assertEquals(5, entity.getCalls());
        }

        @Test
        @DisplayName("should set free requests type for tool-specific configuration")
        void shouldSetFreeRequestsTypeForToolSpecific() {
            String toolName = "typedTool";
            ObjectNode submissionData = objectMapper.createObjectNode();
            ObjectNode monetization = objectMapper.createObjectNode();
            monetization.put("pricing", "FREEMIUM");

            ObjectNode toolFreeRequests = objectMapper.createObjectNode();
            toolFreeRequests.put(toolName, 100);
            toolFreeRequests.put(toolName + "_type", "global");
            monetization.set("toolFreeRequests", toolFreeRequests);

            submissionData.set("monetization", monetization);

            ObjectNode toolData = objectMapper.createObjectNode();
            toolData.put("name", toolName);

            List<ApiToolMonetizationEntity> result = service.createMonetizationFromApiData(submissionData, toolData, tool);

            assertEquals(1, result.size());
            ApiToolMonetizationEntity entity = result.get(0);
            assertEquals("global", entity.getFreeRequestsType());
        }

        @Test
        @DisplayName("should use uniform pricing for global FREEMIUM")
        void shouldUseUniformPricingForGlobalFreemium() {
            ObjectNode submissionData = objectMapper.createObjectNode();
            ObjectNode monetization = objectMapper.createObjectNode();
            monetization.put("pricing", "FREEMIUM");
            monetization.put("freeRequestsPerUser", 50);
            monetization.put("uniformToolPrice", 500);
            monetization.put("uniformToolPriceInDollars", 4.99);
            monetization.put("uniformCalls", 3);
            submissionData.set("monetization", monetization);

            ObjectNode toolData = objectMapper.createObjectNode();
            toolData.put("name", "uniformTool");

            List<ApiToolMonetizationEntity> result = service.createMonetizationFromApiData(submissionData, toolData, tool);

            assertEquals(1, result.size());
            ApiToolMonetizationEntity entity = result.get(0);
            assertEquals(500, entity.getMauValue());
            assertEquals(new BigDecimal("4.99"), entity.getPricePerMau());
            assertEquals(3, entity.getCalls());
        }
    }

    // ========================================================================
    // createMonetizationFromApiData() - PAID tests
    // ========================================================================

    @Nested
    @DisplayName("createMonetizationFromApiData() - PAID")
    class PaidTests {

        @Test
        @DisplayName("should create PAID monetization for tool in single plan")
        void shouldCreatePaidMonetizationForToolInSinglePlan() {
            String toolName = "paidTool";
            ObjectNode submissionData = createPaidSubmission(toolName, "basic");
            ObjectNode toolData = objectMapper.createObjectNode();
            toolData.put("name", toolName);

            List<ApiToolMonetizationEntity> result = service.createMonetizationFromApiData(submissionData, toolData, tool);

            assertEquals(1, result.size());
            ApiToolMonetizationEntity entity = result.get(0);
            assertEquals("PAID", entity.getMonetizationType());
            assertEquals("BASIC", entity.getPlanName());
            assertEquals(tool.getId(), entity.getApiToolId());
        }

        @Test
        @DisplayName("should create PAID monetization for tool in multiple plans")
        void shouldCreatePaidMonetizationForToolInMultiplePlans() {
            String toolName = "multiPlanTool";
            ObjectNode submissionData = createPaidSubmissionMultiplePlans(toolName, "basic", "pro");
            ObjectNode toolData = objectMapper.createObjectNode();
            toolData.put("name", toolName);

            List<ApiToolMonetizationEntity> result = service.createMonetizationFromApiData(submissionData, toolData, tool);

            assertEquals(2, result.size());
            assertTrue(result.stream().anyMatch(e -> "BASIC".equals(e.getPlanName())));
            assertTrue(result.stream().anyMatch(e -> "PRO".equals(e.getPlanName())));
        }

        @Test
        @DisplayName("should set plan-specific price and quota")
        void shouldSetPlanSpecificPriceAndQuota() {
            String toolName = "pricedPaidTool";
            ObjectNode submissionData = objectMapper.createObjectNode();
            ObjectNode monetization = objectMapper.createObjectNode();
            monetization.put("pricing", "PAID");

            ObjectNode selectedPlans = objectMapper.createObjectNode();
            selectedPlans.put("basic", true);
            monetization.set("selectedPlans", selectedPlans);

            ObjectNode planTools = objectMapper.createObjectNode();
            ArrayNode basicTools = objectMapper.createArrayNode();
            basicTools.add(toolName);
            planTools.set("basic", basicTools);
            monetization.set("planTools", planTools);

            monetization.put("priceBasic", 19.99);
            monetization.put("quotaBasic", 1000);

            submissionData.set("monetization", monetization);

            ObjectNode toolData = objectMapper.createObjectNode();
            toolData.put("name", toolName);

            List<ApiToolMonetizationEntity> result = service.createMonetizationFromApiData(submissionData, toolData, tool);

            assertEquals(1, result.size());
            ApiToolMonetizationEntity entity = result.get(0);
            assertEquals(new BigDecimal("19.99"), entity.getPrice());
            assertEquals(1000, entity.getQuota());
        }

        @Test
        @DisplayName("should set plan-specific rate limits")
        void shouldSetPlanSpecificRateLimits() {
            String toolName = "rateLimitedPaidTool";
            ObjectNode submissionData = objectMapper.createObjectNode();
            ObjectNode monetization = objectMapper.createObjectNode();
            monetization.put("pricing", "PAID");

            ObjectNode selectedPlans = objectMapper.createObjectNode();
            selectedPlans.put("pro", true);
            monetization.set("selectedPlans", selectedPlans);

            ObjectNode planTools = objectMapper.createObjectNode();
            ArrayNode proTools = objectMapper.createArrayNode();
            proTools.add(toolName);
            planTools.set("pro", proTools);
            monetization.set("planTools", planTools);

            monetization.put("rpsPro", 100);
            monetization.put("rpsPeriodPro", "second");

            submissionData.set("monetization", monetization);

            ObjectNode toolData = objectMapper.createObjectNode();
            toolData.put("name", toolName);

            List<ApiToolMonetizationEntity> result = service.createMonetizationFromApiData(submissionData, toolData, tool);

            assertEquals(1, result.size());
            ApiToolMonetizationEntity entity = result.get(0);
            assertEquals(100, entity.getRateLimitRequests());
            assertEquals("second", entity.getRateLimitPeriod());
        }

        @Test
        @DisplayName("should set hard limit and overusage cost")
        void shouldSetHardLimitAndOverusageCost() {
            String toolName = "hardLimitTool";
            ObjectNode submissionData = objectMapper.createObjectNode();
            ObjectNode monetization = objectMapper.createObjectNode();
            monetization.put("pricing", "PAID");

            ObjectNode selectedPlans = objectMapper.createObjectNode();
            selectedPlans.put("ultra", true);
            monetization.set("selectedPlans", selectedPlans);

            ObjectNode planTools = objectMapper.createObjectNode();
            ArrayNode ultraTools = objectMapper.createArrayNode();
            ultraTools.add(toolName);
            planTools.set("ultra", ultraTools);
            monetization.set("planTools", planTools);

            monetization.put("hardLimitUltra", false);
            monetization.put("overusageCostUltra", 0.05);

            submissionData.set("monetization", monetization);

            ObjectNode toolData = objectMapper.createObjectNode();
            toolData.put("name", toolName);

            List<ApiToolMonetizationEntity> result = service.createMonetizationFromApiData(submissionData, toolData, tool);

            assertEquals(1, result.size());
            ApiToolMonetizationEntity entity = result.get(0);
            assertFalse(entity.getHardLimit());
            assertEquals(new BigDecimal("0.05"), entity.getOverusageCost());
        }

        @Test
        @DisplayName("should set overusage cost to null when hard limit is true")
        void shouldSetOverusageCostToNullWhenHardLimitIsTrue() {
            String toolName = "strictHardLimitTool";
            ObjectNode submissionData = objectMapper.createObjectNode();
            ObjectNode monetization = objectMapper.createObjectNode();
            monetization.put("pricing", "PAID");

            ObjectNode selectedPlans = objectMapper.createObjectNode();
            selectedPlans.put("mega", true);
            monetization.set("selectedPlans", selectedPlans);

            ObjectNode planTools = objectMapper.createObjectNode();
            ArrayNode megaTools = objectMapper.createArrayNode();
            megaTools.add(toolName);
            planTools.set("mega", megaTools);
            monetization.set("planTools", planTools);

            monetization.put("hardLimitMega", true);
            monetization.put("overusageCostMega", 0.10); // Should be ignored

            submissionData.set("monetization", monetization);

            ObjectNode toolData = objectMapper.createObjectNode();
            toolData.put("name", toolName);

            List<ApiToolMonetizationEntity> result = service.createMonetizationFromApiData(submissionData, toolData, tool);

            assertEquals(1, result.size());
            ApiToolMonetizationEntity entity = result.get(0);
            assertTrue(entity.getHardLimit());
            assertNull(entity.getOverusageCost());
        }

        @Test
        @DisplayName("should throw exception when tool not in any plan")
        void shouldThrowExceptionWhenToolNotInAnyPlan() {
            String toolName = "orphanTool";
            ObjectNode submissionData = objectMapper.createObjectNode();
            ObjectNode monetization = objectMapper.createObjectNode();
            monetization.put("pricing", "PAID");

            ObjectNode selectedPlans = objectMapper.createObjectNode();
            selectedPlans.put("basic", true);
            monetization.set("selectedPlans", selectedPlans);

            ObjectNode planTools = objectMapper.createObjectNode();
            ArrayNode basicTools = objectMapper.createArrayNode();
            basicTools.add("otherTool");  // Not our tool
            planTools.set("basic", basicTools);
            monetization.set("planTools", planTools);

            submissionData.set("monetization", monetization);

            ObjectNode toolData = objectMapper.createObjectNode();
            toolData.put("name", toolName);

            assertThrows(MonetizationException.class,
                () -> service.createMonetizationFromApiData(submissionData, toolData, tool));
        }

        @Test
        @DisplayName("should throw exception when selectedPlans is null")
        void shouldThrowExceptionWhenSelectedPlansIsNull() {
            ObjectNode submissionData = objectMapper.createObjectNode();
            ObjectNode monetization = objectMapper.createObjectNode();
            monetization.put("pricing", "PAID");
            // No selectedPlans
            submissionData.set("monetization", monetization);

            ObjectNode toolData = objectMapper.createObjectNode();
            toolData.put("name", "testTool");

            assertThrows(MonetizationException.class,
                () -> service.createMonetizationFromApiData(submissionData, toolData, tool));
        }
    }

    // ========================================================================
    // createMonetizationFromApiData() - Hybrid approach tests
    // ========================================================================

    @Nested
    @DisplayName("createMonetizationFromApiData() - Hybrid approach")
    class HybridTests {

        @Test
        @DisplayName("should process multiple pricing models with selectedPricingModels")
        void shouldProcessMultiplePricingModelsWithSelectedPricingModels() {
            String toolName = "hybridTool";
            ObjectNode submissionData = objectMapper.createObjectNode();
            ObjectNode monetization = objectMapper.createObjectNode();

            // Hybrid approach with selectedPricingModels
            ArrayNode selectedPricingModels = objectMapper.createArrayNode();
            selectedPricingModels.add("freemium");
            selectedPricingModels.add("paid");
            monetization.set("selectedPricingModels", selectedPricingModels);

            // FREEMIUM config
            ObjectNode toolFreeRequests = objectMapper.createObjectNode();
            toolFreeRequests.put(toolName, 100);
            monetization.set("toolFreeRequests", toolFreeRequests);

            // PAID config
            ObjectNode selectedPlans = objectMapper.createObjectNode();
            selectedPlans.put("basic", true);
            monetization.set("selectedPlans", selectedPlans);

            ObjectNode planTools = objectMapper.createObjectNode();
            ArrayNode basicTools = objectMapper.createArrayNode();
            basicTools.add(toolName);
            planTools.set("basic", basicTools);
            monetization.set("planTools", planTools);

            submissionData.set("monetization", monetization);

            ObjectNode toolData = objectMapper.createObjectNode();
            toolData.put("name", toolName);

            List<ApiToolMonetizationEntity> result = service.createMonetizationFromApiData(submissionData, toolData, tool);

            assertEquals(2, result.size());
            assertTrue(result.stream().anyMatch(e -> "FREEMIUM".equals(e.getMonetizationType())));
            assertTrue(result.stream().anyMatch(e -> "PAID".equals(e.getMonetizationType())));
        }

        @Test
        @DisplayName("should use legacy single model when selectedPricingModels is empty")
        void shouldUseLegacySingleModelWhenSelectedPricingModelsIsEmpty() {
            String toolName = "legacyTool";
            ObjectNode submissionData = objectMapper.createObjectNode();
            ObjectNode monetization = objectMapper.createObjectNode();

            // Empty selectedPricingModels array
            ArrayNode selectedPricingModels = objectMapper.createArrayNode();
            monetization.set("selectedPricingModels", selectedPricingModels);

            // Legacy pricing field
            monetization.put("pricing", "freemium");
            monetization.put("freeRequestsPerUser", 50);

            submissionData.set("monetization", monetization);

            ObjectNode toolData = objectMapper.createObjectNode();
            toolData.put("name", toolName);

            List<ApiToolMonetizationEntity> result = service.createMonetizationFromApiData(submissionData, toolData, tool);

            assertEquals(1, result.size());
            assertEquals("FREEMIUM", result.get(0).getMonetizationType());
        }
    }

    // ========================================================================
    // Invalid monetization type tests
    // ========================================================================

    @Nested
    @DisplayName("Invalid monetization type")
    class InvalidTypeTests {

        @Test
        @DisplayName("should throw exception for invalid monetization type")
        void shouldThrowExceptionForInvalidMonetizationType() {
            ObjectNode submissionData = objectMapper.createObjectNode();
            ObjectNode monetization = objectMapper.createObjectNode();
            monetization.put("pricing", "INVALID_TYPE");
            submissionData.set("monetization", monetization);

            ObjectNode toolData = objectMapper.createObjectNode();
            toolData.put("name", "testTool");

            assertThrows(MonetizationException.class,
                () -> service.createMonetizationFromApiData(submissionData, toolData, tool));
        }
    }

    // ========================================================================
    // Timestamp tests
    // ========================================================================

    @Nested
    @DisplayName("Timestamps")
    class TimestampTests {

        @Test
        @DisplayName("should set createdAt and updatedAt timestamps")
        void shouldSetCreatedAtAndUpdatedAtTimestamps() {
            String toolName = "timestampTool";
            ObjectNode submissionData = createFreemiumSubmissionWithToolFreeRequests(toolName, 100);
            ObjectNode toolData = objectMapper.createObjectNode();
            toolData.put("name", toolName);

            long beforeCreation = System.currentTimeMillis();
            List<ApiToolMonetizationEntity> result = service.createMonetizationFromApiData(submissionData, toolData, tool);
            long afterCreation = System.currentTimeMillis();

            assertEquals(1, result.size());
            ApiToolMonetizationEntity entity = result.get(0);
            assertTrue(entity.getCreatedAt() >= beforeCreation);
            assertTrue(entity.getCreatedAt() <= afterCreation);
            assertEquals(entity.getCreatedAt(), entity.getUpdatedAt());
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private ObjectNode createFreemiumSubmissionWithToolFreeRequests(String toolName, int freeRequests) {
        ObjectNode submissionData = objectMapper.createObjectNode();
        ObjectNode monetization = objectMapper.createObjectNode();
        monetization.put("pricing", "FREEMIUM");

        ObjectNode toolFreeRequests = objectMapper.createObjectNode();
        toolFreeRequests.put(toolName, freeRequests);
        monetization.set("toolFreeRequests", toolFreeRequests);

        submissionData.set("monetization", monetization);
        return submissionData;
    }

    private ObjectNode createPaidSubmission(String toolName, String planName) {
        ObjectNode submissionData = objectMapper.createObjectNode();
        ObjectNode monetization = objectMapper.createObjectNode();
        monetization.put("pricing", "PAID");

        ObjectNode selectedPlans = objectMapper.createObjectNode();
        selectedPlans.put(planName, true);
        monetization.set("selectedPlans", selectedPlans);

        ObjectNode planTools = objectMapper.createObjectNode();
        ArrayNode tools = objectMapper.createArrayNode();
        tools.add(toolName);
        planTools.set(planName, tools);
        monetization.set("planTools", planTools);

        submissionData.set("monetization", monetization);
        return submissionData;
    }

    private ObjectNode createPaidSubmissionMultiplePlans(String toolName, String... planNames) {
        ObjectNode submissionData = objectMapper.createObjectNode();
        ObjectNode monetization = objectMapper.createObjectNode();
        monetization.put("pricing", "PAID");

        ObjectNode selectedPlans = objectMapper.createObjectNode();
        ObjectNode planTools = objectMapper.createObjectNode();

        for (String planName : planNames) {
            selectedPlans.put(planName, true);
            ArrayNode tools = objectMapper.createArrayNode();
            tools.add(toolName);
            planTools.set(planName, tools);
        }

        monetization.set("selectedPlans", selectedPlans);
        monetization.set("planTools", planTools);
        submissionData.set("monetization", monetization);
        return submissionData;
    }
}
