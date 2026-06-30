package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ToolCard;
import com.apimarketplace.catalog.domain.ToolCategoryEntity;
import com.apimarketplace.catalog.domain.ToolNameEntity;
import com.apimarketplace.catalog.domain.dto.ToolListResponse;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.repository.ToolNameRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@RequiredArgsConstructor
@Slf4j
public class CatalogToolQueryService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final ApiToolRepository apiToolRepository;
    private final ToolNameRepository toolNameRepository;
    private final ToolCategoryService toolCategoryService;

    public ToolListResponse getTools(int limit, String categoryFilter, String search, String tenantId) {
        int sanitizedLimit = sanitizeLimit(limit);
        List<CatalogToolSummary> matchingTools = findMatchingTools(categoryFilter, search, tenantId);

        List<ToolCard> page = matchingTools.stream()
                .limit(sanitizedLimit)
                .map(CatalogToolSummary::card)
                .collect(Collectors.toList());

        return ToolListResponse.builder()
                .tools(page)
                .total(matchingTools.size())
                .limit(sanitizedLimit)
                .offset(0)
                .build();
    }

    /** Backwards-compatible overload (public APIs only). */
    public ToolListResponse getTools(int limit, String categoryFilter, String search) {
        return getTools(limit, categoryFilter, search, null);
    }

    public List<CatalogToolSummary> findAllActiveTools() {
        return findMatchingTools(null, null, null);
    }

    private int sanitizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private List<CatalogToolSummary> findMatchingTools(String categoryFilter, String search, String tenantId) {
        CategoryFilter filter = CategoryFilter.from(categoryFilter, toolCategoryService);
        String searchTerm = normalize(search);

        Map<UUID, ToolCategoryEntity> categoryCache = new HashMap<>();

        List<CatalogToolSummary> summaries = new ArrayList<>();
        // Both branches exclude bundle-deprecated rows (V331 deprecated_at):
        // this list surfaces tools to users; execution by UUID stays unfiltered.
        List<ApiToolEntity> activeTools = tenantId != null
                ? apiToolRepository.findActiveVisibleToTenant(tenantId)
                : apiToolRepository.findByIsActiveTrueAndDeprecatedAtIsNull();
        for (ApiToolEntity tool : activeTools) {
            resolveSummary(tool, categoryCache).ifPresent(summary -> {
                if (filter.matches(summary) && matchesSearch(summary, searchTerm)) {
                    summaries.add(summary);
                }
            });
        }

        return summaries;
    }

    private Optional<CatalogToolSummary> resolveSummary(ApiToolEntity tool,
                                                        Map<UUID, ToolCategoryEntity> categoryCache) {
        ToolNameEntity toolName = resolveToolName(tool.getToolNameId());
        String displayName = deriveName(tool, toolName);
        if (displayName == null) {
            return Optional.empty();
        }

        String description = deriveDescription(tool, toolName);
        UUID categoryId = toolName != null ? toolName.getToolCategoryId() : null;
        ToolCategoryEntity category = categoryId != null ? categoryCache.computeIfAbsent(
                categoryId,
                id -> toolCategoryService.getToolCategoryById(id).orElse(null)
        ) : null;

        String platform = category != null ? coalesce(category.getName(), "Unknown") : "Unknown";
        String reliability = "MEDIUM";

        ToolCard card = ToolCard.of(
                displayName,
                coalesce(description, "No description available"),
                Map.of(),
                Map.of(),
                platform,
                reliability
        );

        return Optional.of(new CatalogToolSummary(
                tool.getId(),
                displayName,
                description,
                categoryId,
                category != null ? category.getName() : null,
                category != null ? category.getSlug() : null,
                card
        ));
    }

    private ToolNameEntity resolveToolName(String toolNameId) {
        if (toolNameId == null || toolNameId.isBlank()) {
            return null;
        }
        try {
            UUID uuid = UUID.fromString(toolNameId);
            return toolNameRepository.findById(uuid).orElse(null);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid toolNameId {}", toolNameId);
            return null;
        }
    }

    private String deriveName(ApiToolEntity tool, ToolNameEntity toolName) {
        if (toolName != null && toolName.getName() != null && !toolName.getName().isBlank()) {
            return toolName.getName();
        }
        if (tool.getToolSlug() != null && !tool.getToolSlug().isBlank()) {
            return tool.getToolSlug();
        }
        return tool.getId() != null ? "Tool " + tool.getId() : null;
    }

    private String deriveDescription(ApiToolEntity tool, ToolNameEntity toolName) {
        if (toolName != null && toolName.getDescription() != null && !toolName.getDescription().isBlank()) {
            return toolName.getDescription();
        }
        return tool.getDescription();
    }

    private boolean matchesSearch(CatalogToolSummary summary, String searchTerm) {
        if (searchTerm == null || searchTerm.isBlank()) {
            return true;
        }
        String name = normalize(summary.name());
        String description = normalize(summary.description());
        return (name != null && name.contains(searchTerm))
                || (description != null && description.contains(searchTerm));
    }

    private String normalize(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static String coalesce(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    public record CatalogToolSummary(
            UUID toolId,
            String name,
            String description,
            UUID categoryId,
            String categoryName,
            String categorySlug,
            ToolCard card
    ) {}

    private static final class CategoryFilter {
        private final UUID categoryId;
        private final String normalizedLabel;

        private CategoryFilter(UUID categoryId, String normalizedLabel) {
            this.categoryId = categoryId;
            this.normalizedLabel = normalizedLabel;
        }

        static CategoryFilter from(String rawValue, ToolCategoryService toolCategoryService) {
            if (rawValue == null || rawValue.isBlank()) {
                return new CategoryFilter(null, null);
            }
            String trimmed = rawValue.trim();
            UUID parsedId = tryParseUuid(trimmed);
            if (parsedId == null) {
                parsedId = toolCategoryService.getToolCategoryBySlug(trimmed)
                        .map(ToolCategoryEntity::getId)
                        .orElseGet(() -> toolCategoryService.getToolCategoryByName(trimmed)
                                .map(ToolCategoryEntity::getId)
                                .orElse(null));
            }
            return new CategoryFilter(parsedId, trimmed.toLowerCase(Locale.ROOT));
        }

        private static UUID tryParseUuid(String value) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        boolean matches(CatalogToolSummary summary) {
            if (categoryId == null && normalizedLabel == null) {
                return true;
            }
            boolean idMatches = categoryId != null && Objects.equals(categoryId, summary.categoryId());
            if (idMatches) {
                return true;
            }
            if (normalizedLabel == null) {
                return true;
            }
            return (summary.categoryName() != null && summary.categoryName().toLowerCase(Locale.ROOT).equals(normalizedLabel))
                    || (summary.categorySlug() != null && summary.categorySlug().toLowerCase(Locale.ROOT).equals(normalizedLabel));
        }
    }
}
