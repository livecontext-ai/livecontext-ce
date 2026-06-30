package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ApiSubcategoryEntity;
import com.apimarketplace.catalog.domain.ToolNameEntity;
import com.apimarketplace.catalog.domain.ToolSignalEntity;
import com.apimarketplace.catalog.repository.ApiSubcategoryRepository;
import com.apimarketplace.catalog.repository.ToolNameRepository;
import com.apimarketplace.catalog.repository.ToolSignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class SignalsService {
    
    private final ToolNameRepository toolNameRepository;
    private final ToolSignalRepository toolSignalRepository;
    private final ApiSubcategoryRepository apiSubcategoryRepository;
    
    private static final Pattern ACTION_PATTERN = Pattern.compile("^(get|list|create|send|apply|watch|query|update|delete)_?(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESOURCE_PATTERN = Pattern.compile("^(?:get|list|create|send|apply|watch|query|update|delete)_?(.+)$", Pattern.CASE_INSENSITIVE);
    
    @Transactional
    public void generateOrUpdateSignals(ToolNameEntity toolName) {
        ToolSignalEntity signalEntity = toolSignalRepository.findByToolId(toolName.getId())
            .orElse(new ToolSignalEntity());
        
        signalEntity.setToolId(toolName.getId());
        
        // Derive action and resource from name
        signalEntity.setAction(deriveAction(toolName.getName()));
        signalEntity.setResource(deriveResource(toolName.getName()));
        
        // Get provider from ApiSubcategoryEntity
        UUID subcategoryId = toolName.getSubcategoryId();
        if (subcategoryId != null) {
            apiSubcategoryRepository.findById(subcategoryId).ifPresent(subcategory -> {
                signalEntity.setProvider(subcategory.getName());
            });
        }
        
        // Derive method from action (method is no longer stored in ToolNameEntity)
        signalEntity.setMethod(deriveMethodFromAction(signalEntity.getAction()));
        signalEntity.setRequiresUserCredentials(toolName.getRequiresUserCredentials());
        signalEntity.setRunScope(toolName.getRunScope());
        signalEntity.setIsActive(toolName.getIsActive());
        
        // Default/placeholder values for popularity, success_rate, latency_ms_p50
        signalEntity.setPopularity(0);
        signalEntity.setSuccessRate(BigDecimal.ZERO);
        signalEntity.setLatencyMsP50(0);
        
        signalEntity.setUpdatedAt(System.currentTimeMillis());
        
        toolSignalRepository.save(signalEntity);
        log.debug("Generated/Updated signals for tool_id: {}", toolName.getId());
    }
    
    private String deriveAction(String toolName) {
        Matcher matcher = ACTION_PATTERN.matcher(toolName);
        if (matcher.matches()) {
            return matcher.group(1).toLowerCase();
        }
        return null;
    }
    
    private String deriveResource(String toolName) {
        Matcher matcher = RESOURCE_PATTERN.matcher(toolName);
        if (matcher.matches() && matcher.groupCount() >= 1) {
            String resourcePart = matcher.group(1);
            resourcePart = resourcePart.replaceAll("(_by_id|_list|_all|_details)$", "");
            return resourcePart.toLowerCase();
        }
        return null;
    }

    /**
     * Derive HTTP method from action verb.
     * get/list/query → GET, create/send → POST, update → PUT, delete → DELETE
     */
    private String deriveMethodFromAction(String action) {
        if (action == null) return "GET";
        return switch (action.toLowerCase()) {
            case "get", "list", "query", "watch" -> "GET";
            case "create", "send", "apply" -> "POST";
            case "update" -> "PUT";
            case "delete" -> "DELETE";
            default -> "GET";
        };
    }

    @Transactional
    public void generateAllSignals() {
        List<ToolNameEntity> toolNames = toolNameRepository.findByIsActiveTrueOrderByNameAsc();
        
        log.info("Generating signals for {} tool names", toolNames.size());
        
        int processed = 0;
        int errors = 0;
        
        for (ToolNameEntity toolName : toolNames) {
            try {
                generateOrUpdateSignals(toolName);
                processed++;
            } catch (Exception e) {
                log.error("Error generating signals for tool_id {}: {}", toolName.getId(), e.getMessage());
                errors++;
            }
        }
        log.info("Signals generation completed. Processed: {}, Errors: {}", processed, errors);
    }
}