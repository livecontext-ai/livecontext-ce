package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.domain.ToolCategoryEntity;
import com.apimarketplace.catalog.domain.ToolNameEntity;
import com.apimarketplace.catalog.service.ToolCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * REST Controller for tool categories and tool names
 */
@RestController
@RequestMapping("/api/tool-categories")
@RequiredArgsConstructor
public class ToolCategoryController {

    private final ToolCategoryService toolCategoryService;

    /**
     * Admin-token guard for DELETE endpoints. Audit 2026-05-16: prior
     * implementation had zero auth on category/tool-name DELETEs - any
     * authenticated user could wipe global catalog rows.
     */
    @org.springframework.beans.factory.annotation.Value("${catalog.admin-token:}")
    private String catalogAdminToken;

    private boolean isAdminCaller(String headerToken) {
        if (catalogAdminToken == null || catalogAdminToken.isBlank()) return false;
        if (headerToken == null || headerToken.isBlank()) return false;
        return java.security.MessageDigest.isEqual(
                catalogAdminToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                headerToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    
    /**
     * Get all tool categories that have at least one active tool
     */
    @GetMapping
    public ResponseEntity<List<ToolCategoryEntity>> getAllToolCategories(
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        List<ToolCategoryEntity> categories = toolCategoryService.getAllToolCategories();
        return ResponseEntity.ok(categories);
    }
    
    /**
     * Get tool category by name
     */
    @GetMapping("/by-name/{name}")
    public ResponseEntity<ToolCategoryEntity> getToolCategoryByName(@PathVariable String name) {
        Optional<ToolCategoryEntity> category = toolCategoryService.getToolCategoryByName(name);
        return category.map(ResponseEntity::ok)
                      .orElse(ResponseEntity.notFound().build());
    }
    
    /**
     * Get tool names by category ID
     */
    @GetMapping("/{categoryId}/tool-names")
    public ResponseEntity<List<ToolNameEntity>> getToolNamesByCategory(
            @PathVariable UUID categoryId,
            @RequestParam(value = "runScopes", required = false) String[] runScopes,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        List<ToolNameEntity> toolNames;
        if (runScopes != null && runScopes.length > 0) {
            List<String> runScopesList = Arrays.asList(runScopes);
            toolNames = toolCategoryService.getToolNamesByCategoryAndRunScopes(categoryId, runScopesList);
        } else {
            toolNames = toolCategoryService.getToolNamesByCategory(categoryId);
        }
        return ResponseEntity.ok(toolNames);
    }
    
    // REMOVED: getToolNamesByCategoryAndMethod endpoint - method field no longer exists in ToolNameEntity

    /**
     * Get all tool names
     */
    @GetMapping("/tool-names")
    public ResponseEntity<List<ToolNameEntity>> getAllToolNames(
            @RequestParam(value = "runScopes", required = false) String[] runScopes,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        List<ToolNameEntity> toolNames;
        if (runScopes != null && runScopes.length > 0) {
            List<String> runScopesList = Arrays.asList(runScopes);
            toolNames = toolCategoryService.getToolNamesByRunScopes(runScopesList);
        } else {
            toolNames = toolCategoryService.getAllToolNames();
        }
        return ResponseEntity.ok(toolNames);
    }

    /**
     * Get tool categories that have tool names associated with the given subcategory
     */
    @GetMapping("/by-subcategory/{subcategoryId}")
    public ResponseEntity<List<ToolCategoryEntity>> getToolCategoriesBySubcategory(
            @PathVariable UUID subcategoryId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        List<ToolCategoryEntity> categories = toolCategoryService.getToolCategoriesBySubcategory(subcategoryId);
        return ResponseEntity.ok(categories);
    }

    /**
     * Get tool categories that have tool names associated with the given subcategory name
     */
    @GetMapping("/by-subcategory-name/{subcategoryName}")
    public ResponseEntity<List<ToolCategoryEntity>> getToolCategoriesBySubcategoryName(
            @PathVariable String subcategoryName,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        List<ToolCategoryEntity> categories = toolCategoryService.getToolCategoriesBySubcategoryName(subcategoryName, page, size);
        return ResponseEntity.ok(categories);
    }
    
    /**
     * Get tool names by subcategory ID
     */
    @GetMapping("/subcategory/{subcategoryId}/tool-names")
    public ResponseEntity<List<ToolNameEntity>> getToolNamesBySubcategory(
            @PathVariable UUID subcategoryId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        List<ToolNameEntity> toolNames = toolCategoryService.getToolNamesBySubcategory(subcategoryId);
        return ResponseEntity.ok(toolNames);
    }
    
    /**
     * Get tool names by tool category ID and subcategory ID (combined filter)
     */
    @GetMapping("/{toolCategoryId}/subcategory/{subcategoryId}/tool-names")
    public ResponseEntity<List<ToolNameEntity>> getToolNamesByToolCategoryAndSubcategory(
            @PathVariable UUID toolCategoryId,
            @PathVariable UUID subcategoryId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        List<ToolNameEntity> toolNames = toolCategoryService.getToolNamesByToolCategoryAndSubcategory(toolCategoryId, subcategoryId);
        return ResponseEntity.ok(toolNames);
    }
    
    // REMOVED: getToolNamesByMethod endpoint - method field no longer exists in ToolNameEntity

    /**
     * Search tool names
     */
    @GetMapping("/tool-names/search")
    public ResponseEntity<List<ToolNameEntity>> searchToolNames(@RequestParam String q) {
        List<ToolNameEntity> toolNames = toolCategoryService.searchToolNames(q);
        return ResponseEntity.ok(toolNames);
    }
    
    /**
     * Get tool names by name (can return multiple results from different categories)
     */
    @GetMapping("/tool-names/by-name/{name}")
    public ResponseEntity<List<ToolNameEntity>> getToolNamesByName(@PathVariable String name) {
        List<ToolNameEntity> toolNames = toolCategoryService.getToolNamesByName(name);
        return toolNames.isEmpty() ? 
               ResponseEntity.notFound().build() : 
               ResponseEntity.ok(toolNames);
    }
    
    /**
     * Get tool name by name and category ID
     */
    @GetMapping("/tool-names/by-name/{name}/category/{categoryId}")
    public ResponseEntity<ToolNameEntity> getToolNameByNameAndCategory(
            @PathVariable String name, 
            @PathVariable UUID categoryId) {
        Optional<ToolNameEntity> toolName = toolCategoryService.getToolNameByNameAndCategory(name, categoryId);
        return toolName.map(ResponseEntity::ok)
                      .orElse(ResponseEntity.notFound().build());
    }
    
    /**
     * Create a new tool category
     */
    @PostMapping
    public ResponseEntity<ToolCategoryEntity> createToolCategory(
            @RequestBody ToolCategoryEntity toolCategory,
            @RequestHeader(value = "X-Internal-Admin-Token", required = false) String adminToken) {
        if (!isAdminCaller(adminToken)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
        }
        ToolCategoryEntity created = toolCategoryService.createToolCategory(toolCategory);
        return ResponseEntity.ok(created);
    }

    /**
     * Create a new tool name
     */
    @PostMapping("/tool-names")
    public ResponseEntity<ToolNameEntity> createToolName(
            @RequestBody ToolNameEntity toolName,
            @RequestHeader(value = "X-Internal-Admin-Token", required = false) String adminToken) {
        if (!isAdminCaller(adminToken)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
        }
        ToolNameEntity created = toolCategoryService.createToolName(toolName);
        return ResponseEntity.ok(created);
    }

    /**
     * Update a tool category
     */
    @PutMapping("/{categoryId}")
    public ResponseEntity<ToolCategoryEntity> updateToolCategory(
            @PathVariable UUID categoryId,
            @RequestBody ToolCategoryEntity toolCategory,
            @RequestHeader(value = "X-Internal-Admin-Token", required = false) String adminToken) {
        if (!isAdminCaller(adminToken)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
        }
        toolCategory.setId(categoryId);
        ToolCategoryEntity updated = toolCategoryService.updateToolCategory(toolCategory);
        return ResponseEntity.ok(updated);
    }

    /**
     * Update a tool name
     */
    @PutMapping("/tool-names/{toolNameId}")
    public ResponseEntity<ToolNameEntity> updateToolName(
            @PathVariable UUID toolNameId,
            @RequestBody ToolNameEntity toolName,
            @RequestHeader(value = "X-Internal-Admin-Token", required = false) String adminToken) {
        if (!isAdminCaller(adminToken)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
        }
        toolName.setId(toolNameId);
        ToolNameEntity updated = toolCategoryService.updateToolName(toolName);
        return ResponseEntity.ok(updated);
    }
    
    /**
     * Delete a tool category
     */
    @DeleteMapping("/{categoryId}")
    public ResponseEntity<Void> deleteToolCategory(
            @PathVariable UUID categoryId,
            @RequestHeader(value = "X-Internal-Admin-Token", required = false) String adminToken) {
        if (!isAdminCaller(adminToken)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
        }
        toolCategoryService.deleteToolCategory(categoryId);
        return ResponseEntity.ok().build();
    }

    /**
     * Delete a tool name
     */
    @DeleteMapping("/tool-names/{toolNameId}")
    public ResponseEntity<Void> deleteToolName(
            @PathVariable UUID toolNameId,
            @RequestHeader(value = "X-Internal-Admin-Token", required = false) String adminToken) {
        if (!isAdminCaller(adminToken)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
        }
        toolCategoryService.deleteToolName(toolNameId);
        return ResponseEntity.ok().build();
    }
}
