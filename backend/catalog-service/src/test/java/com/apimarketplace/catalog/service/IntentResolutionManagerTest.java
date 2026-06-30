package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ToolCard;
import com.apimarketplace.catalog.domain.dto.IntentResolutionResponse;
import com.apimarketplace.catalog.service.CatalogToolQueryService.CatalogToolSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntentResolutionManagerTest {

    @Mock
    private CatalogToolQueryService catalogToolQueryService;

    private IntentResolutionManager manager;

    @BeforeEach
    void setUp() {
        manager = new IntentResolutionManager(catalogToolQueryService);
    }

    @Test
    @DisplayName("resolve returns candidates ordered by confidence")
    void resolveIntent() {
        CatalogToolSummary first = summary("Analytics", "Fetch analytics", "analytics");
        CatalogToolSummary second = summary("Payments", "Process payments", "payments");
        when(catalogToolQueryService.findAllActiveTools()).thenReturn(List.of(first, second));

        IntentResolutionResponse response = manager.resolve("analytics", 5);

        assertThat(response.getCandidates()).hasSize(1);
        assertThat(response.getCandidates().get(0).getName()).isEqualTo("Analytics");
        assertThat(response.getCandidates().get(0).getConfidence()).isGreaterThan(0);
    }

    private CatalogToolSummary summary(String name, String description, String slug) {
        return new CatalogToolSummary(
                UUID.randomUUID(),
                name,
                description,
                UUID.randomUUID(),
                slug,
                slug,
                ToolCard.of(name, description, slug, "MEDIUM")
        );
    }
}
