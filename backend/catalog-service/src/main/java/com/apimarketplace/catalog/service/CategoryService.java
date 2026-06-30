package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ApiCategoryEntity;
import com.apimarketplace.catalog.domain.ApiSubcategoryEntity;
import com.apimarketplace.catalog.repository.ApiCategoryRepository;
import com.apimarketplace.catalog.repository.ApiSubcategoryRepository;
import com.apimarketplace.catalog.util.SlugUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Service for managing categories and subcategories
 */
@Service
@Transactional
@RequiredArgsConstructor
public class CategoryService {

    private final ApiCategoryRepository categoryRepository;
    private final ApiSubcategoryRepository subcategoryRepository;
    
    /**
     * Get all categories
     */
    @Transactional(readOnly = true)
    public List<ApiCategoryEntity> getAllCategories() {
        return categoryRepository.findAllByOrderByNameAsc();
    }
    
    /**
     * Get category by name
     */
    @Transactional(readOnly = true)
    public Optional<ApiCategoryEntity> getCategoryByName(String name) {
        return categoryRepository.findByName(name);
    }
    
    /**
     * Get category by slug
     */
    @Transactional(readOnly = true)
    public Optional<ApiCategoryEntity> getCategoryBySlug(String slug) {
        return categoryRepository.findBySlug(slug);
    }
    
    /**
     * Get subcategories by category ID
     */
    @Transactional(readOnly = true)
    public List<ApiSubcategoryEntity> getSubcategoriesByCategoryId(UUID categoryId) {
        return subcategoryRepository.findByCategoryId(categoryId);
    }
    
    /**
     * Get subcategory by name and category ID
     */
    @Transactional(readOnly = true)
    public Optional<ApiSubcategoryEntity> getSubcategoryByNameAndCategory(String name, UUID categoryId) {
        return subcategoryRepository.findByNameAndCategoryId(name, categoryId);
    }
    
    /**
     * Get subcategory by slug and category ID
     */
    @Transactional(readOnly = true)
    public Optional<ApiSubcategoryEntity> getSubcategoryBySlugAndCategory(String slug, UUID categoryId) {
        return subcategoryRepository.findBySlugAndCategoryId(slug, categoryId);
    }
    
    /**
     * Get all subcategories
     */
    @Transactional(readOnly = true)
    public List<ApiSubcategoryEntity> getAllSubcategories() {
        return subcategoryRepository.findAllByOrderByNameAsc();
    }
    
    /**
     * Create a new category
     */
    public ApiCategoryEntity createCategory(String name, String description) {
        ApiCategoryEntity category = new ApiCategoryEntity();
        // Don't set ID - let database generate it
        category.setName(name);
        category.setDescription(description);
        
        // Generate unique slug
        List<String> existingSlugs = StreamSupport.stream(categoryRepository.findAll().spliterator(), false)
                .map(ApiCategoryEntity::getSlug)
                .filter(slug -> slug != null && !slug.isEmpty())
                .collect(Collectors.toList());
        String slug = SlugUtils.generateApiCategorySlug(name, existingSlugs);
        category.setSlug(slug);
        
        category.setCreatedAt(System.currentTimeMillis());
        category.setUpdatedAt(System.currentTimeMillis());
        
        return categoryRepository.save(category);
    }
    
    /**
     * Create a new subcategory
     */
    public ApiSubcategoryEntity createSubcategory(UUID categoryId, String name, String description) {
        ApiSubcategoryEntity subcategory = new ApiSubcategoryEntity();
        // Don't set ID - let database generate it
        subcategory.setCategoryId(categoryId);
        subcategory.setName(name);
        subcategory.setDescription(description);
        
        // Generate unique slug
        List<String> existingSlugs = StreamSupport.stream(subcategoryRepository.findAll().spliterator(), false)
                .map(ApiSubcategoryEntity::getSlug)
                .filter(slug -> slug != null && !slug.isEmpty())
                .collect(Collectors.toList());
        String slug = SlugUtils.generateApiSubcategorySlug(name, existingSlugs);
        subcategory.setSlug(slug);
        
        subcategory.setCreatedAt(System.currentTimeMillis());
        subcategory.setUpdatedAt(System.currentTimeMillis());
        
        return subcategoryRepository.save(subcategory);
    }
    
    /**
     * Initialize default categories and subcategories
     */
    public void initializeDefaultCategories() {
        // Check if categories already exist
        if (categoryRepository.count() > 0) {
            return;
        }
        
        // Create main categories
        ApiCategoryEntity socialCategory = createCategory("Social Media", "Social media platforms and APIs");
        ApiCategoryEntity messagingCategory = createCategory("Messaging", "Messaging and communication APIs");
        ApiCategoryEntity dataCategory = createCategory("Data & Analytics", "Data processing and analytics APIs");
        ApiCategoryEntity paymentCategory = createCategory("Payments", "Payment processing APIs");
        ApiCategoryEntity aiCategory = createCategory("AI & ML", "Artificial Intelligence and Machine Learning APIs");
        
        // Create subcategories for Social Media
        createSubcategory(socialCategory.getId(), "Instagram", "Instagram API tools and integrations");
        createSubcategory(socialCategory.getId(), "Twitter", "Twitter/X API tools and integrations");
        createSubcategory(socialCategory.getId(), "Facebook", "Facebook API tools and integrations");
        createSubcategory(socialCategory.getId(), "TikTok", "TikTok API tools and integrations");
        createSubcategory(socialCategory.getId(), "LinkedIn", "LinkedIn API tools and integrations");
        createSubcategory(socialCategory.getId(), "YouTube", "YouTube API tools and integrations");
        
        // Create subcategories for Messaging
        createSubcategory(messagingCategory.getId(), "WhatsApp", "WhatsApp API tools and integrations");
        createSubcategory(messagingCategory.getId(), "Telegram", "Telegram API tools and integrations");
        createSubcategory(messagingCategory.getId(), "Slack", "Slack API tools and integrations");
        createSubcategory(messagingCategory.getId(), "Discord", "Discord API tools and integrations");
        
        // Create subcategories for Data & Analytics
        createSubcategory(dataCategory.getId(), "Database", "Database management and query APIs");
        createSubcategory(dataCategory.getId(), "Analytics", "Data analytics and reporting APIs");
        createSubcategory(dataCategory.getId(), "Storage", "File and data storage APIs");
        
        // Create subcategories for Payments
        createSubcategory(paymentCategory.getId(), "Stripe", "Stripe payment processing APIs");
        createSubcategory(paymentCategory.getId(), "PayPal", "PayPal payment processing APIs");
        createSubcategory(paymentCategory.getId(), "Square", "Square payment processing APIs");
        
        // Create subcategories for AI & ML
        createSubcategory(aiCategory.getId(), "OpenAI", "OpenAI GPT and AI model APIs");
        createSubcategory(aiCategory.getId(), "Anthropic", "Anthropic Claude AI model APIs");
        createSubcategory(aiCategory.getId(), "Google AI", "Google AI and ML APIs");
    }
}
