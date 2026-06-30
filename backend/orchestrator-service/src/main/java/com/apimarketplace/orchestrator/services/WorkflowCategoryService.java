package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.domain.WorkflowCategoryEntity;
import com.apimarketplace.orchestrator.repository.WorkflowCategoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing workflow categories.
 */
@Service
@Transactional
public class WorkflowCategoryService {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowCategoryService.class);

    private final WorkflowCategoryRepository categoryRepository;

    public WorkflowCategoryService(WorkflowCategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    /**
     * Get all active categories ordered by display order
     */
    @Transactional(readOnly = true)
    public List<WorkflowCategoryEntity> getActiveCategories() {
        return categoryRepository.findByIsActiveTrueOrderByDisplayOrderAsc();
    }

    /**
     * Get all categories ordered by display order
     */
    @Transactional(readOnly = true)
    public List<WorkflowCategoryEntity> getAllCategories() {
        return categoryRepository.findAllByOrderByDisplayOrderAsc();
    }

    /**
     * Get category by ID
     */
    @Transactional(readOnly = true)
    public Optional<WorkflowCategoryEntity> getCategoryById(UUID id) {
        return categoryRepository.findById(id);
    }

    /**
     * Get category by slug
     */
    @Transactional(readOnly = true)
    public Optional<WorkflowCategoryEntity> getCategoryBySlug(String slug) {
        return categoryRepository.findBySlug(slug);
    }

    /**
     * Create a new category
     */
    public WorkflowCategoryEntity createCategory(
            String slug,
            String name,
            String description,
            String iconSlug,
            String color,
            Integer displayOrder) {

        if (categoryRepository.existsBySlug(slug)) {
            throw new IllegalArgumentException("Category with slug already exists: " + slug);
        }

        WorkflowCategoryEntity category = new WorkflowCategoryEntity();
        category.setSlug(slug);
        category.setName(name);
        category.setDescription(description);
        category.setIconSlug(iconSlug);
        category.setColor(color);
        category.setDisplayOrder(displayOrder != null ? displayOrder : 0);
        category.setIsActive(true);

        WorkflowCategoryEntity saved = categoryRepository.save(category);
        logger.info("Created category: {} ({})", saved.getName(), saved.getSlug());

        return saved;
    }

    /**
     * Update a category
     */
    public WorkflowCategoryEntity updateCategory(
            UUID id,
            String name,
            String description,
            String iconSlug,
            String color,
            Integer displayOrder,
            Boolean isActive) {

        WorkflowCategoryEntity category = categoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Category not found: " + id));

        if (name != null) category.setName(name);
        if (description != null) category.setDescription(description);
        if (iconSlug != null) category.setIconSlug(iconSlug);
        if (color != null) category.setColor(color);
        if (displayOrder != null) category.setDisplayOrder(displayOrder);
        if (isActive != null) category.setIsActive(isActive);

        WorkflowCategoryEntity saved = categoryRepository.save(category);
        logger.info("Updated category: {} ({})", saved.getName(), saved.getSlug());

        return saved;
    }

    /**
     * Delete a category
     */
    public void deleteCategory(UUID id) {
        WorkflowCategoryEntity category = categoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Category not found: " + id));

        categoryRepository.delete(category);
        logger.info("Deleted category: {} ({})", category.getName(), category.getSlug());
    }
}
