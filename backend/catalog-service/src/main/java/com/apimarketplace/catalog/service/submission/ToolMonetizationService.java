package com.apimarketplace.catalog.service.submission;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiToolMonetizationEntity;
import com.apimarketplace.catalog.service.exception.MonetizationException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class ToolMonetizationService {

    /**
     * Apply rate limits to a monetization entity from tool-specific or global configuration.
     * Tool-specific rate limits take precedence over global ones.
     */
    private void applyRateLimits(ApiToolMonetizationEntity entity, JsonNode toolRateLimits,
                                  JsonNode globalRateLimit, String toolName) {
        if (toolRateLimits != null && toolRateLimits.has(toolName)) {
            JsonNode rateLimit = toolRateLimits.get(toolName);
            if (rateLimit.has("requests")) {
                entity.setRateLimitRequests(rateLimit.path("requests").asInt());
            }
            if (rateLimit.has("period")) {
                entity.setRateLimitPeriod(rateLimit.path("period").asText());
            }
        } else if (globalRateLimit != null) {
            if (globalRateLimit.has("requests")) {
                entity.setRateLimitRequests(globalRateLimit.path("requests").asInt());
            }
            if (globalRateLimit.has("period")) {
                entity.setRateLimitPeriod(globalRateLimit.path("period").asText());
            }
        }
    }
    public List<ApiToolMonetizationEntity> createMonetizationFromApiData(JsonNode submissionData, JsonNode toolData, ApiToolEntity tool) {
        List<ApiToolMonetizationEntity> monetizations = new ArrayList<>();

        JsonNode monetization = submissionData.get("monetization");
        if (monetization == null) {
            throw new MonetizationException("monetization is null");
        }

        String toolName = toolData.path("name").asText();

        // Check for hybrid approach (selectedPricingModels)
        JsonNode selectedPricingModels = monetization.get("selectedPricingModels");
        List<String> modelsToProcess = new ArrayList<>();

        if (selectedPricingModels != null && selectedPricingModels.isArray() && selectedPricingModels.size() > 0) {
            // Hybrid approach: process each selected model
            log.info("🎯 Approche hybride detectee avec {} modeles selectionnes", selectedPricingModels.size());
            for (JsonNode modelNode : selectedPricingModels) {
                String model = modelNode.asText().toUpperCase();
                modelsToProcess.add(model);
                log.debug("📋 Modele a traiter: {}", model);
            }
        } else {
            // Legacy single model approach
            String monetizationType = monetization.path("pricing").asText("freemium").toUpperCase();
            modelsToProcess.add(monetizationType);
            log.info("Approche simple - Type: {}", monetizationType);
        }

        // Process each model (hybrid or single)
        int initialCount = monetizations.size();
        for (String currentModel : modelsToProcess) {
            log.info("⚙️ Processing model {} for tool {}", currentModel, toolName);
            createMonetizationForTool(monetization, toolData, tool, monetizations, currentModel);
        }

        int finalCount = monetizations.size();
        if (modelsToProcess.size() > 1) {
            log.info("🎉 Hybrid approach completed - {} monetization entities created for {} models", finalCount - initialCount, modelsToProcess.size());
        } else {
            log.info("✅ Simple monetization completed - {} entity(ies) created", finalCount - initialCount);
        }

        if (monetizations.isEmpty()) {
            throw MonetizationException.missingConfiguration(toolName);
        }

        return monetizations;
    }

    /**
    * Create monetization for a tool based on type (FREEMIUM or PAID)
    */
    private void createMonetizationForTool(JsonNode monetization, JsonNode toolData, ApiToolEntity tool,
    List<ApiToolMonetizationEntity> monetizations, String monetizationType) {
        String toolName = toolData.path("name").asText();

        if ("FREEMIUM".equals(monetizationType)) {
            createFreemiumMonetization(monetization, toolData, tool, monetizations);
        } else if ("PAID".equals(monetizationType)) {
            createPaidMonetization(monetization, toolData, tool, monetizations);
        } else {
            throw MonetizationException.invalidType(monetizationType);
        }
    }

    /**
    * Create FREEMIUM monetization for a specific tool
    */
    private void createFreemiumMonetization(JsonNode monetization, JsonNode toolData, ApiToolEntity tool,
    List<ApiToolMonetizationEntity> monetizations) {
        String toolName = toolData.path("name").asText();

        // FREEMIUM logic
        JsonNode toolFreeRequests = monetization.get("toolFreeRequests");
        JsonNode toolRateLimits = monetization.get("toolRateLimits");
        JsonNode toolPricing = monetization.get("toolPricing");
        JsonNode globalRateLimit = monetization.get("rateLimit");

        if (toolFreeRequests != null && toolFreeRequests.has(toolName) && !toolName.endsWith("_type")) {
            ApiToolMonetizationEntity monetizationEntity = new ApiToolMonetizationEntity();
            monetizationEntity.setApiToolId(tool.getId());
            monetizationEntity.setMonetizationType("FREEMIUM");
            monetizationEntity.setPlanName(null);

            monetizationEntity.setFreeRequests(toolFreeRequests.path(toolName).asInt());

            // Handle free requests type (per-user or global)
            String typeKey = toolName + "_type";
            if (toolFreeRequests.has(typeKey)) {
                String freeRequestsType = toolFreeRequests.path(typeKey).asText();
                monetizationEntity.setFreeRequestsType(freeRequestsType);
                log.info("Free requests type set for {}: {}", toolName, freeRequestsType);
            }

            if (toolPricing != null && toolPricing.has(toolName)) {
                JsonNode pricing = toolPricing.get(toolName);
                if (pricing.has("mauValue")) {
                    monetizationEntity.setMauValue(pricing.path("mauValue").asInt());
                }
                if (pricing.has("price")) {
                    monetizationEntity.setPricePerMau(pricing.path("price").decimalValue());
                }
                if (pricing.has("calls")) {
                    monetizationEntity.setCalls(pricing.path("calls").asInt());
                } else {
                    monetizationEntity.setCalls(1);
                }
            } else {
                monetizationEntity.setCalls(1);
            }

            // Handle rate limits: tool-specific first, then global
            applyRateLimits(monetizationEntity, toolRateLimits, globalRateLimit, toolName);

            monetizationEntity.setQuota(null);
            monetizationEntity.setPrice(null);
            monetizationEntity.setOverusageCost(null);
            monetizationEntity.setHardLimit(null);

            long currentTime = System.currentTimeMillis();
            monetizationEntity.setCreatedAt(currentTime);
            monetizationEntity.setUpdatedAt(currentTime);

            monetizations.add(monetizationEntity);
        } else if (monetization.has("freeRequestsPerUser") && !monetization.path("freeRequestsPerUser").isNull()) {
            // Global FREEMIUM configuration
            ApiToolMonetizationEntity monetizationEntity = new ApiToolMonetizationEntity();
            monetizationEntity.setApiToolId(tool.getId());
            monetizationEntity.setMonetizationType("FREEMIUM");
            monetizationEntity.setPlanName(null);

            monetizationEntity.setFreeRequests(monetization.path("freeRequestsPerUser").asInt());

            // Handle free requests type
            String freeRequestsType = "per-user";
            if (monetization.has("freeRequestsType") && !monetization.path("freeRequestsType").isNull()) {
                freeRequestsType = monetization.path("freeRequestsType").asText();
            }
            monetizationEntity.setFreeRequestsType(freeRequestsType);
            log.info("Using global free requests for FREEMIUM {}: {} (type: {})", toolName, monetization.path("freeRequestsPerUser").asInt(), freeRequestsType);

            // Uniform pricing
            if (monetization.has("uniformToolPrice") && !monetization.path("uniformToolPrice").isNull()) {
                monetizationEntity.setMauValue(monetization.path("uniformToolPrice").asInt());
            }
            if (monetization.has("uniformToolPriceInDollars") && !monetization.path("uniformToolPriceInDollars").isNull()) {
                monetizationEntity.setPricePerMau(monetization.path("uniformToolPriceInDollars").decimalValue());
            }
            if (monetization.has("uniformCalls") && !monetization.path("uniformCalls").isNull()) {
                monetizationEntity.setCalls(monetization.path("uniformCalls").asInt());
            } else {
                monetizationEntity.setCalls(1);
            }

            // Handle rate limits
            applyRateLimits(monetizationEntity, toolRateLimits, globalRateLimit, toolName);

            monetizationEntity.setQuota(null);
            monetizationEntity.setPrice(null);
            monetizationEntity.setOverusageCost(null);
            monetizationEntity.setHardLimit(null);

            long currentTime = System.currentTimeMillis();
            monetizationEntity.setCreatedAt(currentTime);
            monetizationEntity.setUpdatedAt(currentTime);

            monetizations.add(monetizationEntity);
        }
    }

    /**
    * Create PAID monetization for a specific tool
    */
    private void createPaidMonetization(JsonNode monetization, JsonNode toolData, ApiToolEntity tool,
    List<ApiToolMonetizationEntity> monetizations) {
        String toolName = toolData.path("name").asText();

        // PAID logic
        JsonNode selectedPlans = monetization.get("selectedPlans");
        JsonNode planTools = monetization.get("planTools");

        if (selectedPlans != null && planTools != null) {
            boolean toolIncludedInAnyPlan = false;

            String[] planNames = {"basic", "pro", "ultra", "mega"};
            String[] planNameEnums = {"BASIC", "PRO", "ULTRA", "MEGA"};

            for (int i = 0; i < planNames.length; i++) {
                String planName = planNames[i];
                String planNameEnum = planNameEnums[i];

                if (selectedPlans.has(planName) && selectedPlans.path(planName).asBoolean()) {
                    JsonNode planToolsList = planTools.get(planName);
                    if (planToolsList != null && planToolsList.isArray()) {
                        boolean toolIncludedInPlan = false;
                        for (JsonNode toolInPlan : planToolsList) {
                            if (toolName.equals(toolInPlan.asText())) {
                                toolIncludedInPlan = true;
                                toolIncludedInAnyPlan = true;
                                break;
                            }
                        }

                        if (toolIncludedInPlan) {
                            ApiToolMonetizationEntity monetizationEntity = new ApiToolMonetizationEntity();
                            monetizationEntity.setApiToolId(tool.getId());
                            monetizationEntity.setMonetizationType("PAID");
                            monetizationEntity.setPlanName(planNameEnum);

                            // Plan-specific configuration
                            String priceField = "price" + planName.substring(0, 1).toUpperCase() + planName.substring(1);
                            String quotaField = "quota" + planName.substring(0, 1).toUpperCase() + planName.substring(1);
                            String rpsField = "rps" + planName.substring(0, 1).toUpperCase() + planName.substring(1);
                            String rpsPeriodField = "rpsPeriod" + planName.substring(0, 1).toUpperCase() + planName.substring(1);
                            String overusageCostField = "overusageCost" + planName.substring(0, 1).toUpperCase() + planName.substring(1);
                            String hardLimitField = "hardLimit" + planName.substring(0, 1).toUpperCase() + planName.substring(1);

                            // Price and quota
                            if (monetization.has(priceField) && !monetization.path(priceField).isNull()) {
                                monetizationEntity.setPrice(monetization.path(priceField).decimalValue());
                            }
                            if (monetization.has(quotaField) && !monetization.path(quotaField).isNull()) {
                                monetizationEntity.setQuota(monetization.path(quotaField).asInt());
                            }

                            // Rate limits
                            if (monetization.has(rpsField) && !monetization.path(rpsField).isNull()) {
                                monetizationEntity.setRateLimitRequests(monetization.path(rpsField).asInt());
                            }
                            if (monetization.has(rpsPeriodField) && !monetization.path(rpsPeriodField).isNull()) {
                                monetizationEntity.setRateLimitPeriod(monetization.path(rpsPeriodField).asText());
                            }

                            // Hard limit and overusage cost
                            if (monetization.has(hardLimitField) && !monetization.path(hardLimitField).isNull()) {
                                boolean hardLimit = monetization.path(hardLimitField).asBoolean();
                                monetizationEntity.setHardLimit(hardLimit);

                                if (hardLimit) {
                                    monetizationEntity.setOverusageCost(null);
                                } else {
                                    if (monetization.has(overusageCostField) && !monetization.path(overusageCostField).isNull()) {
                                        monetizationEntity.setOverusageCost(monetization.path(overusageCostField).decimalValue());
                                    }
                                }
                            }

                            monetizationEntity.setFreeRequests(null);
                            monetizationEntity.setFreeRequestsType(null);
                            monetizationEntity.setMauValue(null);
                            monetizationEntity.setPricePerMau(null);
                            monetizationEntity.setCalls(1);

                            long currentTime = System.currentTimeMillis();
                            monetizationEntity.setCreatedAt(currentTime);
                            monetizationEntity.setUpdatedAt(currentTime);

                            monetizations.add(monetizationEntity);
                            log.info("PAID monetization created for tool {} in plan {}", toolName, planNameEnum);
                        }
                    }
                }
            }

            if (!toolIncludedInAnyPlan) {
                throw MonetizationException.toolNotInPlan(toolName);
            }
        } else {
            throw MonetizationException.invalidPaidConfig();
        }
    }

    /**
    * Saves tool parameters after its creation
    */
}
