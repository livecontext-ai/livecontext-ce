package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.domain.ApiCategoryEntity;
import com.apimarketplace.catalog.domain.ApiSubcategoryEntity;
import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.apimarketplace.catalog.domain.dto.ToolListResponse;
import com.apimarketplace.catalog.domain.dto.IntentResolutionResponse;
import com.apimarketplace.catalog.service.CategoryService;
import com.apimarketplace.catalog.service.CatalogV1Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST Controller for category management and catalog v1 operations
 * Fusionne avec CatalogV1Controller pour centraliser les endpoints
 */
@RestController
@RequestMapping("/api/catalog")
@RequiredArgsConstructor
@Slf4j
public class CategoryController {

    private final CategoryService categoryService;
    private final CatalogV1Service catalogV1Service;
    
    // ===== CATEGORY ENDPOINTS =====
    
    /**
     * Get all categories
     */
    @GetMapping("/categories")
    public ResponseEntity<List<ApiCategoryEntity>> getAllCategories(
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            log.info("Recuperation des categories - userId={}, orgId={}", userId, orgId);
            List<ApiCategoryEntity> categories = categoryService.getAllCategories();
            return ResponseEntity.ok(categories);
        } catch (Exception e) {
            log.error("Erreur lors de la recuperation des categories", e);
            return ResponseEntity.status(500).build();
        }
    }
    
    /**
     * Get subcategories by category ID
     */
    @GetMapping("/categories/{categoryId}/subcategories")
    public ResponseEntity<List<ApiSubcategoryEntity>> getSubcategoriesByCategoryId(
            @PathVariable UUID categoryId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            log.info("Recuperation des sous-categories pour la categorie {} - userId={}, orgId={}",
                       categoryId, userId, orgId);
            List<ApiSubcategoryEntity> subcategories = categoryService.getSubcategoriesByCategoryId(categoryId);
            return ResponseEntity.ok(subcategories);
        } catch (Exception e) {
            log.error("Erreur lors de la recuperation des sous-categories pour la categorie {}", categoryId, e);
            return ResponseEntity.status(500).build();
        }
    }
    
    /**
     * Get all subcategories
     */
    @GetMapping("/categories/subcategories")
    public ResponseEntity<List<ApiSubcategoryEntity>> getAllSubcategories(
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            log.info("Recuperation de toutes les sous-categories - userId={}, orgId={}", userId, orgId);
            List<ApiSubcategoryEntity> subcategories = categoryService.getAllSubcategories();
            return ResponseEntity.ok(subcategories);
        } catch (Exception e) {
            log.error("Erreur lors de la recuperation de toutes les sous-categories", e);
            return ResponseEntity.status(500).build();
        }
    }
    
    /**
     * Initialize default categories (for development/testing)
     */
    @PostMapping("/categories/initialize")
    public ResponseEntity<Void> initializeDefaultCategories(
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            log.info("Initialisation des categories par defaut - userId={}, orgId={}", userId, orgId);
            categoryService.initializeDefaultCategories();
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Erreur lors de l'initialisation des categories par defaut", e);
            return ResponseEntity.status(500).build();
        }
    }
}
