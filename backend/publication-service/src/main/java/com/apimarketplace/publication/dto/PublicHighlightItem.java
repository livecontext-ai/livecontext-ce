package com.apimarketplace.publication.dto;

import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Slim DTO for the anonymous-accessible {@code GET /api/publications/highlights/{displayMode}}
 * endpoint. Excludes all heavy / sensitive jsonb columns ({@code planSnapshot},
 * {@code agentSnapshot}, {@code showcaseSnapshot}) that anonymous callers have
 * no business reading and that would otherwise bloat the cached payload to many
 * KB per row. Only fields actually consumed by {@code HighlightedApps.tsx} are
 * exposed.
 */
public record PublicHighlightItem(
        UUID id,
        String title,
        String description,
        String displayMode,
        Integer creditsPerUse,
        String publisherId,
        String publisherName,
        String publisherAvatarUrl,
        UUID showcaseInterfaceId,
        String showcaseRunId,
        List<Map<String, Object>> nodeIcons,
        Integer agentCount,
        Integer skillCount,
        Integer workflowCount,
        Integer interfaceCount,
        Integer datasourceCount,
        Double averageRating,
        Integer reviewCount
) {
    public static PublicHighlightItem from(WorkflowPublicationEntity p) {
        DisplayMode mode = p.getDisplayMode();
        return new PublicHighlightItem(
                p.getId(),
                p.getTitle(),
                p.getDescription(),
                mode == null ? null : mode.name(),
                p.getCreditsPerUse(),
                p.getPublisherId(),
                p.getPublisherName(),
                p.getPublisherAvatarUrl(),
                p.getShowcaseInterfaceId(),
                p.getShowcaseRunId(),
                p.getNodeIcons(),
                p.getAgentCount(),
                p.getSkillCount(),
                p.getWorkflowCount(),
                p.getInterfaceCount(),
                p.getDatasourceCount(),
                p.getAverageRating(),
                p.getReviewCount()
        );
    }
}
