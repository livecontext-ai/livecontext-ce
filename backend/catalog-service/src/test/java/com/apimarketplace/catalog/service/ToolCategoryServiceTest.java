package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ApiSubcategoryEntity;
import com.apimarketplace.catalog.domain.ToolCategoryEntity;
import com.apimarketplace.catalog.domain.ToolNameEntity;
import com.apimarketplace.catalog.repository.ApiSubcategoryRepository;
import com.apimarketplace.catalog.repository.ToolCategoryRepository;
import com.apimarketplace.catalog.repository.ToolNameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolCategoryServiceTest {

    @Mock
    private ToolCategoryRepository toolCategoryRepository;

    @Mock
    private ToolNameRepository toolNameRepository;

    @Mock
    private ApiSubcategoryRepository apiSubcategoryRepository;

    private ToolCategoryService toolCategoryService;

    @BeforeEach
    void setUp() {
        toolCategoryService = new ToolCategoryService(toolCategoryRepository, toolNameRepository, apiSubcategoryRepository);
    }

    @Test
    void getToolCategoriesBySubcategoryNameReturnsPaginatedResults() {
        String slug = "ai-tools";
        UUID subcategoryId = UUID.randomUUID();
        ApiSubcategoryEntity subcategory = new ApiSubcategoryEntity();
        subcategory.setId(subcategoryId);
        when(apiSubcategoryRepository.findBySlug(slug)).thenReturn(Optional.of(subcategory));
        when(apiSubcategoryRepository.findByNameIgnoreCase(slug)).thenReturn(Collections.emptyList());

        ToolNameEntity firstTool = buildToolName(UUID.randomUUID(), subcategoryId);
        ToolNameEntity secondTool = buildToolName(UUID.randomUUID(), subcategoryId);
        when(toolNameRepository.findBySubcategoryIdInAndIsActiveTrue(anyList()))
                .thenReturn(List.of(firstTool, secondTool));

        ToolCategoryEntity firstCategory = buildCategory(firstTool.getToolCategoryId(), 1);
        ToolCategoryEntity secondCategory = buildCategory(secondTool.getToolCategoryId(), 2);
        when(toolCategoryRepository.findByIdInOrderBySortOrderAsc(anyList()))
                .thenReturn(List.of(firstCategory, secondCategory));

        List<ToolCategoryEntity> firstPage = toolCategoryService.getToolCategoriesBySubcategoryName(slug, 0, 1);
        assertEquals(1, firstPage.size());
        assertEquals(firstCategory.getId(), firstPage.get(0).getId());

        List<ToolCategoryEntity> secondPage = toolCategoryService.getToolCategoriesBySubcategoryName(slug, 1, 1);
        assertEquals(1, secondPage.size());
        assertEquals(secondCategory.getId(), secondPage.get(0).getId());
    }

    @Test
    void createToolCategoryGeneratesIncrementalSlug() {
        ToolCategoryEntity entity = new ToolCategoryEntity();
        entity.setName("AI Tools");

        when(toolCategoryRepository.findByName("AI Tools")).thenReturn(Optional.empty());
        when(toolCategoryRepository.existsBySlug("ai-tools")).thenReturn(true);
        when(toolCategoryRepository.existsBySlug("ai-tools-2")).thenReturn(false);
        when(toolCategoryRepository.findBySlug("ai-tools-2")).thenReturn(Optional.empty());
        when(toolCategoryRepository.save(entity)).thenAnswer(invocation -> invocation.getArgument(0));

        ToolCategoryEntity saved = toolCategoryService.createToolCategory(entity);
        assertEquals("ai-tools-2", saved.getSlug());
    }

    @Test
    void createToolCategoryReturnsExistingWhenNameAlreadyPresent() {
        // Regression: re-importing API catalog raised tool_categories_name_key DuplicateKeyException
        // for every category that already existed (tool_categories not truncated). The fix makes
        // createToolCategory idempotent on name, so importer pre-creation is a no-op for existing rows.
        ToolCategoryEntity incoming = new ToolCategoryEntity();
        incoming.setName("Email");

        ToolCategoryEntity existing = new ToolCategoryEntity();
        existing.setId(UUID.randomUUID());
        existing.setName("Email");
        existing.setSlug("email");

        when(toolCategoryRepository.findByName("Email")).thenReturn(Optional.of(existing));

        ToolCategoryEntity result = toolCategoryService.createToolCategory(incoming);
        assertEquals(existing.getId(), result.getId());
        org.mockito.Mockito.verify(toolCategoryRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createToolCategoryRecoversWhenSaveRacesIntoDuplicateKey() {
        // Regression: between findByName(empty) and save(), a parallel importer thread can insert
        // the same name. The fix catches DuplicateKeyException and returns the row that won the race.
        ToolCategoryEntity incoming = new ToolCategoryEntity();
        incoming.setName("Productivity");

        ToolCategoryEntity raceWinner = new ToolCategoryEntity();
        raceWinner.setId(UUID.randomUUID());
        raceWinner.setName("Productivity");
        raceWinner.setSlug("productivity");

        when(toolCategoryRepository.findByName("Productivity"))
                .thenReturn(Optional.empty())     // first check: not yet inserted
                .thenReturn(Optional.of(raceWinner)); // post-conflict lookup
        when(toolCategoryRepository.existsBySlug("productivity")).thenReturn(false);
        when(toolCategoryRepository.findBySlug("productivity")).thenReturn(Optional.empty());
        when(toolCategoryRepository.save(incoming)).thenThrow(
                new org.springframework.dao.DuplicateKeyException("tool_categories_name_key"));

        ToolCategoryEntity result = toolCategoryService.createToolCategory(incoming);
        assertEquals(raceWinner.getId(), result.getId());
    }

    @Test
    void createToolNameGeneratesSlugWithinCategoryScope() {
        UUID categoryId = UUID.randomUUID();
        ToolNameEntity toolName = new ToolNameEntity();
        toolName.setName("Search Tool");
        toolName.setToolCategoryId(categoryId);

        when(toolNameRepository.existsBySlugAndToolCategoryId("search-tool", categoryId)).thenReturn(true);
        when(toolNameRepository.existsBySlugAndToolCategoryId("search-tool-2", categoryId)).thenReturn(false);
        when(toolNameRepository.save(toolName)).thenAnswer(invocation -> invocation.getArgument(0));

        ToolNameEntity saved = toolCategoryService.createToolName(toolName);
        assertEquals("search-tool-2", saved.getSlug());
    }

    private ToolNameEntity buildToolName(UUID categoryId, UUID subcategoryId) {
        ToolNameEntity entity = new ToolNameEntity();
        entity.setId(UUID.randomUUID());
        entity.setToolCategoryId(categoryId);
        entity.setSubcategoryId(subcategoryId);
        entity.setIsActive(true);
        return entity;
    }

    private ToolCategoryEntity buildCategory(UUID id, int sortOrder) {
        ToolCategoryEntity entity = new ToolCategoryEntity();
        entity.setId(id);
        entity.setIsActive(true);
        entity.setSortOrder(sortOrder);
        entity.setName("Category " + sortOrder);
        entity.setSlug("category-" + sortOrder);
        return entity;
    }
}
