package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ToolCategoryEntity;
import com.apimarketplace.catalog.domain.dto.ToolListResponse;
import com.apimarketplace.catalog.domain.ToolNameEntity;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.repository.ToolNameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogToolQueryServiceTest {

    @Mock
    private ApiToolRepository apiToolRepository;
    @Mock
    private ToolNameRepository toolNameRepository;
    @Mock
    private ToolCategoryService toolCategoryService;

    private CatalogToolQueryService service;

    @BeforeEach
    void setUp() {
        service = new CatalogToolQueryService(apiToolRepository, toolNameRepository, toolCategoryService);
    }

    @Test
    @DisplayName("getTools filters by category slug and search term")
    void getToolsFiltersByCategoryAndSearch() {
        UUID toolNameId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();

        ApiToolEntity tool = buildTool(UUID.randomUUID(), toolNameId, "analytics-tool", "Fetch analytics");

        // The tenant-less list path filters bundle-deprecated rows (V331).
        when(apiToolRepository.findByIsActiveTrueAndDeprecatedAtIsNull()).thenReturn(List.of(tool));
        when(toolNameRepository.findById(toolNameId)).thenReturn(Optional.of(buildToolName(toolNameId, categoryId, "Analytics", "Analytics data")));

        ToolCategoryEntity category = buildCategory(categoryId, "Analytics", "analytics");
        when(toolCategoryService.getToolCategoryById(categoryId)).thenReturn(Optional.of(category));
        when(toolCategoryService.getToolCategoryBySlug("analytics")).thenReturn(Optional.of(category));

        ToolListResponse response = service.getTools(5, "analytics", "Analytics");

        assertThat(response.getTools()).hasSize(1);
        assertThat(response.getTools().get(0).name()).isEqualTo("Analytics");
        assertThat(response.getTotal()).isEqualTo(1);
        assertThat(response.getTools().get(0).platform()).isEqualTo("Analytics");
    }

    private ApiToolEntity buildTool(UUID toolId, UUID toolNameId, String slug, String description) {
        ApiToolEntity entity = new ApiToolEntity();
        entity.setId(toolId);
        entity.setToolNameId(toolNameId.toString());
        entity.setToolSlug(slug);
        entity.setDescription(description);
        entity.setIsActive(true);
        entity.setMethod("GET");
        entity.setEndpoint("/endpoint");
        entity.setApiId(UUID.randomUUID());
        return entity;
    }

    private ToolNameEntity buildToolName(UUID id, UUID categoryId, String name, String description) {
        ToolNameEntity entity = new ToolNameEntity();
        entity.setId(id);
        entity.setToolCategoryId(categoryId);
        entity.setName(name);
        entity.setDescription(description);
        entity.setIsActive(true);
        return entity;
    }

    private ToolCategoryEntity buildCategory(UUID id, String name, String slug) {
        ToolCategoryEntity entity = new ToolCategoryEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setSlug(slug);
        entity.setIsActive(true);
        return entity;
    }
}
