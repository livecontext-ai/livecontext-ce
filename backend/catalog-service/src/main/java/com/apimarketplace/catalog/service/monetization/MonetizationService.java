package com.apimarketplace.catalog.service.monetization;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiToolMonetizationEntity;
import com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest;
import com.apimarketplace.catalog.domain.dto.MonetizationResponse;
import com.apimarketplace.catalog.repository.ApiToolMonetizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service for handling all monetization-related operations.
 * Extracted from ApiService following SOLID principles (Single Responsibility).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MonetizationService {

    private final ApiToolMonetizationRepository monetizationRepository;
    private final JdbcTemplate jdbcTemplate;

    // Default plan configurations for PAID monetization
    private static final Map<String, Map<String, Object>> DEFAULT_PAID_PLANS = Map.of(
        "basic", Map.of(
            "price", BigDecimal.ZERO,
            "quota", 1000,
            "rps", 10,
            "rpsPeriod", "minute",
            "overusageCost", BigDecimal.valueOf(0.01),
            "hardLimit", true
        ),
        "pro", Map.of(
            "price", BigDecimal.valueOf(9.99),
            "quota", 10000,
            "rps", 100,
            "rpsPeriod", "minute",
            "overusageCost", BigDecimal.valueOf(0.008),
            "hardLimit", true
        ),
        "ultra", Map.of(
            "price", BigDecimal.valueOf(29.99),
            "quota", 50000,
            "rps", 500,
            "rpsPeriod", "minute",
            "overusageCost", BigDecimal.valueOf(0.005),
            "hardLimit", true
        ),
        "mega", Map.of(
            "price", BigDecimal.valueOf(99.99),
            "quota", 200000,
            "rps", 1000,
            "rpsPeriod", "minute",
            "overusageCost", BigDecimal.valueOf(0.003),
            "hardLimit", false
        )
    );

    /**
     * Delete all monetization records for a tool.
     */
    public void deleteMonetizationsForTool(UUID toolId) {
        List<ApiToolMonetizationEntity> monetizations = monetizationRepository.findByApiToolId(toolId);
        for (ApiToolMonetizationEntity monetization : monetizations) {
            monetizationRepository.delete(monetization);
        }
    }

    /**
     * Get tool monetization data from database.
     */
    public List<MonetizationResponse> getToolMonetization(UUID toolId) {
        String sql = "SELECT id, api_tool_id, monetization_type, plan_name, rate_limit_requests, rate_limit_period, " +
                    "free_requests, free_requests_type, mau_value, price_per_mau, calls, quota, price, " +
                    "overusage_cost, hard_limit, created_at, updated_at " +
                    "FROM api_tool_monetization WHERE api_tool_id = ?";

        return jdbcTemplate.query(sql, (rs, rowNum) -> new MonetizationResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("api_tool_id", UUID.class),
                rs.getString("monetization_type"),
                rs.getString("plan_name"),
                rs.getObject("rate_limit_requests", Integer.class),
                rs.getString("rate_limit_period"),
                rs.getObject("free_requests", Integer.class),
                rs.getString("free_requests_type"),
                rs.getObject("mau_value", Integer.class),
                rs.getBigDecimal("price_per_mau"),
                rs.getObject("calls", Integer.class),
                rs.getObject("quota", Integer.class),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("overusage_cost"),
                rs.getObject("hard_limit", Boolean.class),
                rs.getObject("created_at", Long.class),
                rs.getObject("updated_at", Long.class)
        ), toolId);
    }

    /**
     * Update pricing models for tools.
     * Creates or removes monetization configurations based on selected pricing models.
     */
    @Transactional
    public void updatePricingModelsForTools(List<ApiToolEntity> tools,
                                            ApiConfigurationRequest.PricingModelsUpdateRequest request,
                                            Function<ApiToolEntity, String> toolNameProvider) {
        // Validate that at least one pricing model is selected
        if (request.selectedPricingModels() == null || request.selectedPricingModels().isEmpty()) {
            throw new IllegalArgumentException("At least one pricing model must be selected. Cannot deselect all pricing models.");
        }

        // Smart update: only modify what needs to be changed
        for (ApiToolEntity tool : tools) {
            List<ApiToolMonetizationEntity> existingMonetizations = monetizationRepository.findByApiToolId(tool.getId());

            // Check which monetization types already exist for this tool
            Set<String> existingTypes = existingMonetizations.stream()
                    .map(ApiToolMonetizationEntity::getMonetizationType)
                    .collect(Collectors.toSet());

            // Remove monetization types that are no longer selected
            for (ApiToolMonetizationEntity monetization : existingMonetizations) {
                String monetizationType = monetization.getMonetizationType();
                boolean shouldKeep = request.selectedPricingModels().stream()
                        .anyMatch(selected -> selected.equalsIgnoreCase(monetizationType));

                if (!shouldKeep) {
                    monetizationRepository.deleteById(monetization.getId());
                    log.info("Removed monetization for tool {}: {} (no longer selected)",
                            toolNameProvider.apply(tool), monetizationType);
                }
            }

            // Create new monetization configurations only for types that don't exist yet
            List<ApiToolMonetizationEntity> newMonetizations = createMonetizationFromUpdateRequest(
                    request, tool, existingTypes, toolNameProvider.apply(tool));
            for (ApiToolMonetizationEntity monetization : newMonetizations) {
                monetizationRepository.save(monetization);
                log.info("Created new monetization for tool {}: {} - Plan: {}",
                        toolNameProvider.apply(tool),
                        monetization.getMonetizationType(),
                        monetization.getPlanName());
            }
        }
    }

    /**
     * Update FREEMIUM configuration for a specific tool.
     */
    @Transactional
    public void updateToolFreemiumConfig(UUID apiToolId, ApiConfigurationRequest.ToolFreemiumConfigDto config) {
        // Find existing FREEMIUM monetization for this tool
        List<ApiToolMonetizationEntity> existingMonetizations = monetizationRepository.findByApiToolId(apiToolId);
        ApiToolMonetizationEntity freemiumMonetization = existingMonetizations.stream()
                .filter(m -> "FREEMIUM".equals(m.getMonetizationType()))
                .findFirst()
                .orElse(null);

        if (freemiumMonetization == null) {
            // Create new FREEMIUM monetization
            freemiumMonetization = new ApiToolMonetizationEntity();
            freemiumMonetization.setApiToolId(apiToolId);
            freemiumMonetization.setMonetizationType("FREEMIUM");
            freemiumMonetization.setCreatedAt(System.currentTimeMillis());
        }

        // Update configuration
        if (config.freeRequests() != null) {
            freemiumMonetization.setFreeRequests(config.freeRequests());
        }
        if (config.freeRequestsType() != null) {
            freemiumMonetization.setFreeRequestsType(config.freeRequestsType());
        }
        if (config.rateLimitRequests() != null) {
            freemiumMonetization.setRateLimitRequests(config.rateLimitRequests());
        }
        if (config.rateLimitPeriod() != null) {
            freemiumMonetization.setRateLimitPeriod(config.rateLimitPeriod());
        }
        if (config.mauValue() != null) {
            freemiumMonetization.setMauValue(config.mauValue());
        }
        if (config.calls() != null) {
            freemiumMonetization.setCalls(config.calls());
        }

        freemiumMonetization.setUpdatedAt(System.currentTimeMillis());
        monetizationRepository.save(freemiumMonetization);
    }

    /**
     * Batch update FREEMIUM configuration for multiple tools.
     */
    @Transactional
    public void batchUpdateToolsFreemiumConfig(Map<UUID, ApiConfigurationRequest.ToolFreemiumConfigDto> toolsConfig) {
        for (Map.Entry<UUID, ApiConfigurationRequest.ToolFreemiumConfigDto> entry : toolsConfig.entrySet()) {
            UUID apiToolId = entry.getKey();
            ApiConfigurationRequest.ToolFreemiumConfigDto config = entry.getValue();

            try {
                updateToolFreemiumConfig(apiToolId, config);
                log.info("Updated FREEMIUM config for tool {}", apiToolId);
            } catch (Exception e) {
                log.error("Error updating FREEMIUM config for tool {}: {}", apiToolId, e.getMessage(), e);
                // Continue with other tools
            }
        }
    }

    /**
     * Update PAID configuration for a specific tool.
     */
    @Transactional
    public void updateToolPaidConfig(UUID apiToolId, ApiConfigurationRequest.ToolPaidConfigDto config) {
        // Find existing PAID monetization for this tool
        List<ApiToolMonetizationEntity> existingMonetizations = monetizationRepository.findByApiToolId(apiToolId);
        ApiToolMonetizationEntity paidMonetization = existingMonetizations.stream()
                .filter(m -> "PAID".equals(m.getMonetizationType()))
                .findFirst()
                .orElse(null);

        if (paidMonetization == null) {
            // Create new PAID monetization
            paidMonetization = new ApiToolMonetizationEntity();
            paidMonetization.setApiToolId(apiToolId);
            paidMonetization.setMonetizationType("PAID");
            paidMonetization.setCreatedAt(System.currentTimeMillis());
        }

        // Update configuration
        if (config.planName() != null) {
            paidMonetization.setPlanName(config.planName());
        }
        if (config.quota() != null) {
            paidMonetization.setQuota(config.quota());
        }
        if (config.price() != null) {
            paidMonetization.setPrice(config.price());
        }
        if (config.overusageCost() != null) {
            paidMonetization.setOverusageCost(config.overusageCost());
        }
        if (config.hardLimit() != null) {
            paidMonetization.setHardLimit(config.hardLimit());
        }
        if (config.rateLimitRequests() != null) {
            paidMonetization.setRateLimitRequests(config.rateLimitRequests());
        }
        if (config.rateLimitPeriod() != null) {
            paidMonetization.setRateLimitPeriod(config.rateLimitPeriod());
        }

        paidMonetization.setUpdatedAt(System.currentTimeMillis());
        monetizationRepository.save(paidMonetization);
    }

    /**
     * Batch update PAID configuration for multiple tools.
     */
    @Transactional
    public void batchUpdateToolsPaidConfig(Map<UUID, ApiConfigurationRequest.ToolPaidConfigDto> toolsConfig) {
        for (Map.Entry<UUID, ApiConfigurationRequest.ToolPaidConfigDto> entry : toolsConfig.entrySet()) {
            UUID apiToolId = entry.getKey();
            ApiConfigurationRequest.ToolPaidConfigDto config = entry.getValue();

            try {
                // Check if this is a deletion request (all values are null)
                boolean isDeletionRequest = config.planName() == null &&
                                          config.quota() == null &&
                                          config.price() == null &&
                                          config.overusageCost() == null &&
                                          config.hardLimit() == null &&
                                          config.rateLimitRequests() == null &&
                                          config.rateLimitPeriod() == null;

                if (isDeletionRequest) {
                    // Delete existing PAID monetization
                    List<ApiToolMonetizationEntity> existingMonetizations = monetizationRepository.findByApiToolId(apiToolId);
                    ApiToolMonetizationEntity paidMonetization = existingMonetizations.stream()
                            .filter(m -> "PAID".equals(m.getMonetizationType()))
                            .findFirst()
                            .orElse(null);

                    if (paidMonetization != null) {
                        monetizationRepository.delete(paidMonetization);
                        log.info("Deleted PAID config for tool {}", apiToolId);
                    }
                } else {
                    updateToolPaidConfig(apiToolId, config);
                    log.info("Updated PAID config for tool {}", apiToolId);
                }
            } catch (Exception e) {
                log.error("Error updating PAID config for tool {}: {}", apiToolId, e.getMessage(), e);
                // Continue with other tools
            }
        }
    }

    /**
     * Update PAID plans selection for tools.
     */
    @Transactional
    public void updatePaidPlans(List<ApiToolEntity> tools,
                                ApiConfigurationRequest.PaidPlansUpdateRequest request,
                                Function<ApiToolEntity, String> toolNameProvider) {
        log.info("Request selectedPlans: {}", request.selectedPlans());
        log.info("Request planTools: {}", request.planTools());

        // Process each tool
        for (ApiToolEntity tool : tools) {
            handleToolPaidPlansUpdate(tool, request, toolNameProvider);
        }
    }

    /**
     * Create monetization configurations from update request.
     * Uses default values for pricing and configuration.
     * Only creates configurations for types that don't already exist.
     */
    private List<ApiToolMonetizationEntity> createMonetizationFromUpdateRequest(
            ApiConfigurationRequest.PricingModelsUpdateRequest request,
            ApiToolEntity tool,
            Set<String> existingTypes,
            String toolName) {
        List<ApiToolMonetizationEntity> monetizations = new ArrayList<>();

        // Process each selected pricing model
        for (String pricingModel : request.selectedPricingModels()) {
            // Only create if this type doesn't already exist
            if (!existingTypes.contains(pricingModel.toUpperCase())) {
                if ("FREEMIUM".equalsIgnoreCase(pricingModel)) {
                    createFreemiumMonetizationWithDefaults(tool, monetizations, toolName);
                    log.info("Creating new FREEMIUM monetization for tool {} (didn't exist)", toolName);
                } else if ("PAID".equalsIgnoreCase(pricingModel)) {
                    createPaidMonetizationWithDefaults(tool, monetizations, toolName);
                    log.info("Creating new PAID monetization for tool {} (didn't exist)", toolName);
                }
            } else {
                log.info("Skipping {} monetization for tool {} (already exists)", pricingModel, toolName);
            }
        }

        return monetizations;
    }

    /**
     * Create FREEMIUM monetization with default values.
     */
    private void createFreemiumMonetizationWithDefaults(ApiToolEntity tool,
                                                        List<ApiToolMonetizationEntity> monetizations,
                                                        String toolName) {
        // Default FREEMIUM configuration
        ApiToolMonetizationEntity freemiumMonetization = new ApiToolMonetizationEntity();
        freemiumMonetization.setApiToolId(tool.getId());
        freemiumMonetization.setMonetizationType("FREEMIUM");
        freemiumMonetization.setFreeRequests(1000);
        freemiumMonetization.setFreeRequestsType("per-user");
        freemiumMonetization.setRateLimitRequests(1000);
        freemiumMonetization.setRateLimitPeriod("hour");
        freemiumMonetization.setMauValue(1);
        freemiumMonetization.setPricePerMau(BigDecimal.ZERO);
        freemiumMonetization.setCalls(1);
        freemiumMonetization.setCreatedAt(System.currentTimeMillis());
        freemiumMonetization.setUpdatedAt(System.currentTimeMillis());

        monetizations.add(freemiumMonetization);
        log.info("Created FREEMIUM monetization for tool {} with default values", toolName);
    }

    /**
     * Create PAID monetization with default values.
     * Creates all standard plans (basic, pro, ultra, mega) with default values.
     */
    private void createPaidMonetizationWithDefaults(ApiToolEntity tool,
                                                    List<ApiToolMonetizationEntity> monetizations,
                                                    String toolName) {
        // Create monetization for all standard plans
        for (Map.Entry<String, Map<String, Object>> planEntry : DEFAULT_PAID_PLANS.entrySet()) {
            String planName = planEntry.getKey();
            Map<String, Object> planConfig = planEntry.getValue();

            ApiToolMonetizationEntity paidMonetization = new ApiToolMonetizationEntity();
            paidMonetization.setApiToolId(tool.getId());
            paidMonetization.setMonetizationType("PAID");
            paidMonetization.setPlanName(planName.toUpperCase());
            paidMonetization.setRateLimitRequests((Integer) planConfig.get("rps"));
            paidMonetization.setRateLimitPeriod((String) planConfig.get("rpsPeriod"));
            paidMonetization.setPrice((BigDecimal) planConfig.get("price"));
            paidMonetization.setQuota((Integer) planConfig.get("quota"));
            paidMonetization.setOverusageCost((BigDecimal) planConfig.get("overusageCost"));
            paidMonetization.setHardLimit((Boolean) planConfig.get("hardLimit"));
            paidMonetization.setCreatedAt(System.currentTimeMillis());
            paidMonetization.setUpdatedAt(System.currentTimeMillis());

            monetizations.add(paidMonetization);
            log.info("Created PAID monetization for tool {} with plan {} and default values", toolName, planName);
        }
    }

    /**
     * Handle PAID plans update for a specific tool.
     */
    private void handleToolPaidPlansUpdate(ApiToolEntity tool,
                                          ApiConfigurationRequest.PaidPlansUpdateRequest request,
                                          Function<ApiToolEntity, String> toolNameProvider) {
        String toolName = toolNameProvider.apply(tool);
        UUID toolId = tool.getId();

        log.info("Processing tool: {} (ID: {})", toolName, toolId);

        // Get current PAID monetizations for this tool
        List<ApiToolMonetizationEntity> currentPaidConfigs = monetizationRepository
            .findByApiToolIdAndMonetizationType(toolId, "PAID");

        log.info("Current PAID configs for tool {}: {}", toolName, currentPaidConfigs.size());

        // Create a map of current plans for this tool
        Map<String, ApiToolMonetizationEntity> currentPlans = currentPaidConfigs.stream()
            .collect(Collectors.toMap(
                    ApiToolMonetizationEntity::getPlanName,
                    Function.identity(),
                    (existing, replacement) -> existing
            ));

        // Process each plan
        String[] planNames = {"basic", "pro", "ultra", "mega"};

        for (String planName : planNames) {
            // Try both formats: "basic" and "selectedBasic"
            String selectedPlanKey = "selected" + planName.substring(0, 1).toUpperCase() + planName.substring(1);

            // Check both formats, but prioritize "selectedXxx" if both exist
            Boolean isSelected = request.selectedPlans().get(selectedPlanKey);
            if (isSelected == null) {
                // Fallback to simple format
                isSelected = request.selectedPlans().get(planName);
            }

            log.info("Plan {}: isSelected={} (checked keys: '{}', '{}')", planName, isSelected, selectedPlanKey, planName);

            if (Boolean.TRUE.equals(isSelected)) {
                // Plan is selected - create or update configuration
                handlePlanSelection(tool, planName, currentPlans, request, toolNameProvider);
            } else {
                // Plan is deselected - remove configuration
                handlePlanDeselection(tool, planName, currentPlans, toolNameProvider);
            }
        }
    }

    /**
     * Handle plan selection - create or update configuration.
     */
    private void handlePlanSelection(ApiToolEntity tool, String planName,
                                   Map<String, ApiToolMonetizationEntity> currentPlans,
                                   ApiConfigurationRequest.PaidPlansUpdateRequest request,
                                   Function<ApiToolEntity, String> toolNameProvider) {
        String toolName = toolNameProvider.apply(tool);
        String planNameUpper = planName.toUpperCase();
        ApiToolMonetizationEntity config = currentPlans.get(planNameUpper);

        // Check if this tool should be included in this plan
        String selectedPlanToolsKey = "selected" + planName.substring(0, 1).toUpperCase() + planName.substring(1);

        List<String> planTools = request.planTools().get(selectedPlanToolsKey);
        if (planTools == null) {
            // Fallback to simple format
            planTools = request.planTools().get(planName);
        }

        log.info("Plan {} tools for key '{}': {}", planName, selectedPlanToolsKey, planTools);
        if (planTools == null) {
            log.info("Plan {} tools for key '{}': {}", planName, planName, request.planTools().get(planName));
        }

        boolean shouldIncludeTool = planTools != null && planTools.contains(toolName);
        log.info("Tool {} should be included in plan {}: {} (planTools: {})", toolName, planName, shouldIncludeTool, planTools);

        if (shouldIncludeTool) {
            if (config == null) {
                // Create new configuration with default values
                config = createDefaultPaidConfig(tool.getId(), planName);
                monetizationRepository.save(config);
                log.info("Created new PAID config for tool {} in plan {}", toolName, planName);
            } else {
                // Update existing configuration if needed
                config.setUpdatedAt(System.currentTimeMillis());
                monetizationRepository.save(config);
                log.info("Updated existing PAID config for tool {} in plan {}", toolName, planName);
            }
        } else {
            // Tool should not be included in this plan, remove if exists
            if (config != null) {
                monetizationRepository.delete(config);
                log.info("Removed PAID config for tool {} from plan {} (not in planTools)", toolName, planName);
            }
        }
    }

    /**
     * Handle plan deselection - remove configuration.
     */
    private void handlePlanDeselection(ApiToolEntity tool, String planName,
                                     Map<String, ApiToolMonetizationEntity> currentPlans,
                                     Function<ApiToolEntity, String> toolNameProvider) {
        String planNameUpper = planName.toUpperCase();
        ApiToolMonetizationEntity config = currentPlans.get(planNameUpper);

        if (config != null) {
            monetizationRepository.delete(config);
            log.info("Removed PAID config for tool {} from plan {}", toolNameProvider.apply(tool), planName);
        }
    }

    /**
     * Create default PAID configuration for a plan.
     */
    private ApiToolMonetizationEntity createDefaultPaidConfig(UUID toolId, String planName) {
        ApiToolMonetizationEntity config = new ApiToolMonetizationEntity();
        config.setApiToolId(toolId);
        config.setMonetizationType("PAID");
        config.setPlanName(planName.toUpperCase());
        config.setCreatedAt(System.currentTimeMillis());
        config.setUpdatedAt(System.currentTimeMillis());

        // Set default values based on plan
        Map<String, Object> planConfig = DEFAULT_PAID_PLANS.get(planName.toLowerCase());
        if (planConfig != null) {
            config.setPrice((BigDecimal) planConfig.get("price"));
            config.setQuota((Integer) planConfig.get("quota"));
            config.setRateLimitRequests((Integer) planConfig.get("rps"));
            config.setRateLimitPeriod((String) planConfig.get("rpsPeriod"));
            config.setOverusageCost((BigDecimal) planConfig.get("overusageCost"));
            config.setHardLimit((Boolean) planConfig.get("hardLimit"));
        }

        return config;
    }

    /**
     * Convert monetization data to map format for API responses.
     */
    public List<Map<String, Object>> convertMonetizationToMapList(List<MonetizationResponse> monetizations) {
        List<Map<String, Object>> monetizationData = new ArrayList<>();
        if (monetizations != null) {
            for (MonetizationResponse monetization : monetizations) {
                Map<String, Object> monetizationMap = new HashMap<>();
                monetizationMap.put("id", monetization.id().toString());
                monetizationMap.put("apiToolId", monetization.apiToolId().toString());
                monetizationMap.put("monetizationType", monetization.monetizationType());
                monetizationMap.put("planName", monetization.planName());
                monetizationMap.put("rateLimitRequests", monetization.rateLimitRequests());
                monetizationMap.put("rateLimitPeriod", monetization.rateLimitPeriod());
                monetizationMap.put("freeRequests", monetization.freeRequests());
                monetizationMap.put("freeRequestsType", monetization.freeRequestsType());
                monetizationMap.put("mauValue", monetization.mauValue());
                monetizationMap.put("pricePerMau", monetization.pricePerMau());
                monetizationMap.put("calls", monetization.calls());
                monetizationMap.put("quota", monetization.quota());
                monetizationMap.put("price", monetization.price());
                monetizationMap.put("overusageCost", monetization.overusageCost());
                monetizationMap.put("hardLimit", monetization.hardLimit());
                monetizationMap.put("createdAt", monetization.createdAt());
                monetizationMap.put("updatedAt", monetization.updatedAt());
                monetizationData.add(monetizationMap);
            }
        }
        return monetizationData;
    }
}
