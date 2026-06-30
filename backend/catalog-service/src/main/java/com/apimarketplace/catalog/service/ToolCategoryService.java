package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ApiSubcategoryEntity;
import com.apimarketplace.catalog.domain.ToolCategoryEntity;
import com.apimarketplace.catalog.domain.ToolNameEntity;
import com.apimarketplace.catalog.repository.ApiSubcategoryRepository;
import com.apimarketplace.catalog.repository.ToolCategoryRepository;
import com.apimarketplace.catalog.repository.ToolNameRepository;
import com.apimarketplace.catalog.util.SlugUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Service for managing tool categories and tool names
 */
@Service
public class ToolCategoryService {
    
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ToolCategoryRepository toolCategoryRepository;
    private final ToolNameRepository toolNameRepository;
    private final ApiSubcategoryRepository apiSubcategoryRepository;

    public ToolCategoryService(ToolCategoryRepository toolCategoryRepository,
                               ToolNameRepository toolNameRepository,
                               ApiSubcategoryRepository apiSubcategoryRepository) {
        this.toolCategoryRepository = toolCategoryRepository;
        this.toolNameRepository = toolNameRepository;
        this.apiSubcategoryRepository = apiSubcategoryRepository;
    }
    
    /**
     * Get all active tool categories that have at least one active tool
     */
    public List<ToolCategoryEntity> getAllToolCategories() {
        return toolCategoryRepository.findByIsActiveTrueOrderBySortOrderAsc();
    }
    
    /**
     * Get tool category by name
     */
    public Optional<ToolCategoryEntity> getToolCategoryByName(String name) {
        return toolCategoryRepository.findByName(name);
    }
    
    /**
     * Get tool category by slug
     */
    public Optional<ToolCategoryEntity> getToolCategoryBySlug(String slug) {
        return toolCategoryRepository.findBySlug(slug);
    }
    
    /**
     * Get tool category by ID
     */
    public Optional<ToolCategoryEntity> getToolCategoryById(UUID id) {
        return toolCategoryRepository.findById(id);
    }
    
    /**
     * Get all tool names for a specific category
     */
    public List<ToolNameEntity> getToolNamesByCategory(UUID categoryId) {
        return toolNameRepository.findByToolCategoryIdAndIsActiveTrueOrderByNameAsc(categoryId);
    }

    // REMOVED: getToolNamesByCategoryAndMethod - method field no longer exists in ToolNameEntity

    /**
     * Get tool names by category ID and run scopes
     */
    public List<ToolNameEntity> getToolNamesByCategoryAndRunScopes(UUID categoryId, List<String> runScopes) {
        return toolNameRepository.findByToolCategoryIdAndRunScopeInAndIsActiveTrueOrderByNameAsc(categoryId, runScopes);
    }

    /**
     * Get tool names by run scopes
     */
    public List<ToolNameEntity> getToolNamesByRunScopes(List<String> runScopes) {
        return toolNameRepository.findByRunScopeInAndIsActiveTrueOrderByNameAsc(runScopes);
    }

    /**
     * Get tool name by name and category ID
     */
    
    /**
     * Get tool name by name and category ID
     */
    public Optional<ToolNameEntity> getToolNameByNameAndCategory(String name, UUID categoryId) {
        return toolNameRepository.findByNameAndToolCategoryId(name, categoryId);
    }
    
    /**
     * Get tool name by slug and category ID
     */
    public Optional<ToolNameEntity> getToolNameBySlugAndCategory(String slug, UUID categoryId) {
        return toolNameRepository.findBySlugAndToolCategoryId(slug, categoryId);
    }
    
    /**
     * Get tool name by ID
     */
    public Optional<ToolNameEntity> getToolNameById(UUID id) {
        return toolNameRepository.findById(id);
    }
    
    /**
     * Get tool name by tool_name_id (String)
     */
    public Optional<ToolNameEntity> getToolNameByToolNameId(String toolNameId) {
        if (toolNameId == null || toolNameId.trim().isEmpty()) {
            return Optional.empty();
        }
        try {
            UUID id = UUID.fromString(toolNameId);
            return toolNameRepository.findById(id);
        } catch (IllegalArgumentException e) {
            // If toolNameId is not a valid UUID, return empty
            return Optional.empty();
        }
    }
    
    /**
     * Get all tool names by name (can return multiple results from different categories)
     */
    public List<ToolNameEntity> getToolNamesByName(String name) {
        return toolNameRepository.findByNameAndIsActiveTrue(name);
    }

    public List<ToolNameEntity> findToolNamesByName(String name) {
        if (name == null || name.isBlank()) {
            return Collections.emptyList();
        }
        return toolNameRepository.findByNameAndIsActiveTrue(name);
    }
    
    /**
     * Get all tool names
     */
    public List<ToolNameEntity> getAllToolNames() {
        return toolNameRepository.findByIsActiveTrueOrderByNameAsc();
    }
    
    // REMOVED: getToolNamesByMethod - method field no longer exists in ToolNameEntity

    /**
     * Search tool names by name containing
     */
    public List<ToolNameEntity> searchToolNames(String searchTerm) {
        return toolNameRepository.findByNameContainingIgnoreCaseAndIsActiveTrue(searchTerm);
    }
    
    // Note: Tool template methods removed as ToolTemplateEntity was part of V1 architecture
    
    /**
     * Create a new tool category, or return the existing one if a category with the same
     * name or slug already exists. This makes the call idempotent so parallel importers
     * (and re-imports against a non-truncated tool_categories table) don't trip the
     * `tool_categories_name_key` unique constraint.
     */
    public ToolCategoryEntity createToolCategory(ToolCategoryEntity toolCategory) {
        // Idempotency: short-circuit if a row with the same name already exists
        if (toolCategory.getName() != null) {
            Optional<ToolCategoryEntity> byName = toolCategoryRepository.findByName(toolCategory.getName());
            if (byName.isPresent()) {
                return byName.get();
            }
        }

        // Set default values if not provided
        if (toolCategory.getSortOrder() == null) {
            toolCategory.setSortOrder(999); // Default sort order for new categories
        }
        if (toolCategory.getIcon() == null) {
            toolCategory.setIcon("default");
        }
        if (toolCategory.getColor() == null) {
            toolCategory.setColor("#6B7280");
        }
        if (toolCategory.getCreatedAt() == null) {
            toolCategory.setCreatedAt(System.currentTimeMillis());
        }
        if (toolCategory.getUpdatedAt() == null) {
            toolCategory.setUpdatedAt(System.currentTimeMillis());
        }

        // Generate unique slug if not provided
        String resolvedSlug = ensureCategorySlug(toolCategory.getSlug(), toolCategory.getName());
        toolCategory.setSlug(resolvedSlug);

        // Slug-based idempotency check (covers cases where name differs by case/whitespace)
        if (resolvedSlug != null) {
            Optional<ToolCategoryEntity> bySlug = toolCategoryRepository.findBySlug(resolvedSlug);
            if (bySlug.isPresent()) {
                return bySlug.get();
            }
        }

        try {
            return toolCategoryRepository.save(toolCategory);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            // Another thread inserted concurrently between the find and save - fetch and return it.
            if (toolCategory.getName() != null) {
                Optional<ToolCategoryEntity> byName = toolCategoryRepository.findByName(toolCategory.getName());
                if (byName.isPresent()) {
                    return byName.get();
                }
            }
            if (resolvedSlug != null) {
                Optional<ToolCategoryEntity> bySlug = toolCategoryRepository.findBySlug(resolvedSlug);
                if (bySlug.isPresent()) {
                    return bySlug.get();
                }
            }
            throw dup;
        }
    }
    
    /**
     * Create a new tool name
     */
    public ToolNameEntity createToolName(ToolNameEntity toolName) {
        // Set default values if not provided
        // Note: method and endpointPattern removed - now stored only in ApiToolEntity
        if (toolName.getCreatedAt() == null) {
            toolName.setCreatedAt(System.currentTimeMillis());
        }
        if (toolName.getUpdatedAt() == null) {
            toolName.setUpdatedAt(System.currentTimeMillis());
        }
        
        // Generate unique slug if not provided
        toolName.setSlug(ensureToolNameSlug(toolName));
        
        return toolNameRepository.save(toolName);
    }
    
    // Note: Tool template create method removed as ToolTemplateEntity was part of V1 architecture
    
    /**
     * Update a tool category
     */
    public ToolCategoryEntity updateToolCategory(ToolCategoryEntity toolCategory) {
        return toolCategoryRepository.save(toolCategory);
    }
    
    /**
     * Update a tool name
     */
    public ToolNameEntity updateToolName(ToolNameEntity toolName) {
        return toolNameRepository.save(toolName);
    }
    
    // Note: Tool template update method removed as ToolTemplateEntity was part of V1 architecture
    
    /**
     * Delete a tool category (soft delete)
     */
    public void deleteToolCategory(UUID categoryId) {
        toolCategoryRepository.findById(categoryId).ifPresent(category -> {
            category.setIsActive(false);
            toolCategoryRepository.save(category);
        });
    }
    
    /**
     * Delete a tool name (soft delete)
     */
    public void deleteToolName(UUID toolNameId) {
        toolNameRepository.findById(toolNameId).ifPresent(toolName -> {
            toolName.setIsActive(false);
            toolNameRepository.save(toolName);
        });
    }

    /**
     * Get tool categories by subcategory ID
     * Finds all tool categories that have tool names associated with the given subcategory
     */
    public List<ToolCategoryEntity> getToolCategoriesBySubcategory(UUID subcategoryId) {
        // Find all tool names associated with the given subcategory
        List<ToolNameEntity> toolNames = toolNameRepository.findBySubcategoryIdAndIsActiveTrue(subcategoryId);

        // Extract unique category IDs from the tool names
        List<UUID> categoryIds = toolNames.stream()
                .map(ToolNameEntity::getToolCategoryId)
                .distinct()
                .collect(Collectors.toList());

        // Find and return the corresponding tool categories
        Iterable<ToolCategoryEntity> categoriesIterable = toolCategoryRepository.findAllById(categoryIds);
        return StreamSupport.stream(categoriesIterable.spliterator(), false)
                .filter(ToolCategoryEntity::getIsActive)
                .collect(Collectors.toList());
    }

    /**
     * Get tool categories by subcategory name with pagination support
     */
    public List<ToolCategoryEntity> getToolCategoriesBySubcategoryName(String subcategoryName) {
        return getToolCategoriesBySubcategoryName(subcategoryName, 0, DEFAULT_PAGE_SIZE);
    }

    /**
     * Get tool categories by subcategory name with pagination
     */
    public List<ToolCategoryEntity> getToolCategoriesBySubcategoryName(String subcategoryName, int page, int size) {
        if (subcategoryName == null || subcategoryName.trim().isEmpty()) {
            return Collections.emptyList();
        }

        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int offset = safePage * safeSize;

        List<ApiSubcategoryEntity> matchingSubcategories = findSubcategoriesByNameOrSlug(subcategoryName.trim());
        if (matchingSubcategories.isEmpty()) {
            return Collections.emptyList();
        }

        List<UUID> subcategoryIds = matchingSubcategories.stream()
                .map(ApiSubcategoryEntity::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (subcategoryIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<ToolNameEntity> toolNames = toolNameRepository.findBySubcategoryIdInAndIsActiveTrue(subcategoryIds);
        if (toolNames.isEmpty()) {
            return Collections.emptyList();
        }

        List<UUID> categoryIds = toolNames.stream()
                .map(ToolNameEntity::getToolCategoryId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (categoryIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<ToolCategoryEntity> orderedCategories = toolCategoryRepository.findByIdInOrderBySortOrderAsc(categoryIds)
                .stream()
                .filter(category -> Boolean.TRUE.equals(category.getIsActive()))
                .collect(Collectors.toList());
        if (orderedCategories.isEmpty() || offset >= orderedCategories.size()) {
            return Collections.emptyList();
        }

        int toIndex = Math.min(offset + safeSize, orderedCategories.size());
        return new ArrayList<>(orderedCategories.subList(offset, toIndex));
    }

    /**
     * Get tool names by subcategory ID
     */
    public List<ToolNameEntity> getToolNamesBySubcategory(UUID subcategoryId) {
        return toolNameRepository.findBySubcategoryIdAndIsActiveTrueOrderByNameAsc(subcategoryId);
    }

    /**
     * Get tool names by tool category ID and subcategory ID (combined filter)
     */
    public List<ToolNameEntity> getToolNamesByToolCategoryAndSubcategory(UUID toolCategoryId, UUID subcategoryId) {
        return toolNameRepository.findByToolCategoryIdAndSubcategoryIdAndIsActiveTrueOrderByNameAsc(toolCategoryId, subcategoryId);
    }

    private List<ApiSubcategoryEntity> findSubcategoriesByNameOrSlug(String subcategoryName) {
        LinkedHashMap<UUID, ApiSubcategoryEntity> matches = new LinkedHashMap<>();
        String slugCandidate = SlugUtils.generateSlug(subcategoryName);
        if (!slugCandidate.isBlank()) {
            apiSubcategoryRepository.findBySlug(slugCandidate)
                    .ifPresent(entity -> matches.put(entity.getId(), entity));
        }
        apiSubcategoryRepository.findByNameIgnoreCase(subcategoryName)
                .forEach(entity -> matches.putIfAbsent(entity.getId(), entity));
        return new ArrayList<>(matches.values());
    }

    private String ensureCategorySlug(String providedSlug, String fallbackName) {
        String base = resolveBaseSlug(providedSlug, fallbackName, "category");
        String candidate = base;
        int suffix = 2;
        while (toolCategoryRepository.existsBySlug(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private String ensureToolNameSlug(ToolNameEntity toolName) {
        String base = resolveBaseSlug(toolName.getSlug(), toolName.getName(), "tool");
        String candidate = base;
        int suffix = 2;
        while (toolNameSlugExists(toolName.getToolCategoryId(), candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private String resolveBaseSlug(String providedSlug, String fallbackName, String defaultValue) {
        String base = providedSlug != null && !providedSlug.isBlank()
                ? SlugUtils.sanitizeSlug(providedSlug)
                : SlugUtils.generateSlug(fallbackName);
        if (base == null || base.isBlank()) {
            return defaultValue;
        }
        return base;
    }

    private boolean toolNameSlugExists(UUID toolCategoryId, String slug) {
        if (toolCategoryId != null) {
            return toolNameRepository.existsBySlugAndToolCategoryId(slug, toolCategoryId);
        }
        return toolNameRepository.existsBySlug(slug);
    }

    // Note: Tool template delete method removed as ToolTemplateEntity was part of V1 architecture
}
