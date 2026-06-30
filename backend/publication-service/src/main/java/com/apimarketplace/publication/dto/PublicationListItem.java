package com.apimarketplace.publication.dto;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lightweight DTO for publication list endpoints.
 * Contains all fields from WorkflowPublicationEntity EXCEPT planSnapshot.
 */
public record PublicationListItem(
        UUID id,
        String publicationType,
        UUID workflowId,
        UUID agentConfigId,
        String title,
        String description,
        UUID showcaseInterfaceId,
        String showcaseRunId,
        String displayMode,
        Integer creditsPerUse,
        String publisherId,
        String publisherName,
        String publisherEmail,
        String publisherAvatarUrl,
        String status,
        String visibility,
        // Ownership scope (V223/#151): owner_type USER|ORG + owner_id is the scoping
        // source of truth (publisherId is only the human who clicked publish). Exposed
        // so the marketplace can compute org-aware "already owned" client-side.
        String ownerType,
        String ownerId,
        Integer useCount,
        Integer totalCreditsEarned,
        Integer planVersion,
        String nodeIcons,
        Integer agentCount,
        Integer skillCount,
        Integer interfaceCount,
        Integer datasourceCount,
        Integer workflowCount,
        Double averageRating,
        Integer reviewCount,
        Instant publishedAt,
        Instant updatedAt,
        UUID categoryId,
        String categorySlug,
        String categoryName,
        String categoryIconSlug,
        String categoryColor,
        UUID projectId,
        // Moderation fields
        String rejectionReason,
        // Agent-specific fields extracted from agent_snapshot JSONB
        String agentAvatarUrl,
        String agentModelProvider,
        String agentModelName,
        // Resource id for standalone TABLE / INTERFACE / SKILL publications (null for WORKFLOW/AGENT,
        // which use workflowId / agentConfigId). Lets the resource listing pages resolve a card's
        // shared/private state from ONE /publications/my fetch instead of one status call per item.
        String resourceId
) {

    public Map<String, Object> toResponseMap() {
        Map<String, Object> response = new HashMap<>();
        response.put("id", id.toString());
        response.put("publicationType", publicationType != null ? publicationType : "WORKFLOW");
        response.put("workflowId", workflowId != null ? workflowId.toString() : null);
        response.put("agentConfigId", agentConfigId != null ? agentConfigId.toString() : null);
        response.put("title", title);
        response.put("description", description);

        response.put("showcaseInterfaceId", showcaseInterfaceId != null ? showcaseInterfaceId.toString() : null);
        response.put("showcaseRunId", showcaseRunId);
        response.put("hasShowcase", showcaseInterfaceId != null && showcaseRunId != null && !showcaseRunId.isEmpty());
        response.put("isApplication", showcaseInterfaceId != null);
        response.put("displayMode", displayMode != null ? displayMode : "WORKFLOW");

        if (categoryId != null) {
            Map<String, Object> categoryInfo = new HashMap<>();
            categoryInfo.put("id", categoryId.toString());
            categoryInfo.put("slug", categorySlug);
            categoryInfo.put("name", categoryName);
            categoryInfo.put("iconSlug", categoryIconSlug);
            categoryInfo.put("color", categoryColor);
            response.put("category", categoryInfo);
        } else {
            response.put("category", null);
        }

        response.put("creditsPerUse", creditsPerUse);
        // publisherId is exposed because the marketplace avatar component
        // resolves /api/proxy/users/{publisherId}/avatar - without it
        // every card falls back to a generic User icon.
        // publisherEmail stays stripped - that was the real harvesting risk.
        response.put("publisherId", publisherId);
        response.put("publisherName", publisherName);
        response.put("publisherAvatarUrl", publisherAvatarUrl);
        response.put("status", status);
        response.put("published", "ACTIVE".equals(status));
        response.put("visibility", visibility);
        response.put("ownerType", ownerType);
        response.put("ownerId", ownerId);
        response.put("useCount", useCount);
        response.put("totalCreditsEarned", totalCreditsEarned);
        response.put("planVersion", planVersion);
        response.put("nodeIcons", parseNodeIcons());
        response.put("agentCount", agentCount != null ? agentCount : 0);
        response.put("skillCount", skillCount != null ? skillCount : 0);
        response.put("interfaceCount", interfaceCount != null ? interfaceCount : 0);
        response.put("datasourceCount", datasourceCount != null ? datasourceCount : 0);
        response.put("workflowCount", workflowCount != null ? workflowCount : 0);
        response.put("averageRating", averageRating != null ? averageRating : 0.0);
        response.put("reviewCount", reviewCount != null ? reviewCount : 0);
        response.put("publishedAt", publishedAt != null ? publishedAt.toString() : null);
        response.put("updatedAt", updatedAt != null ? updatedAt.toString() : null);
        response.put("projectId", projectId != null ? projectId.toString() : null);

        // Moderation fields
        response.put("rejectionReason", rejectionReason);

        // Resource id for standalone TABLE / INTERFACE / SKILL publications (null otherwise).
        response.put("resourceId", resourceId);

        // Agent-specific fields (only present for AGENT publications)
        response.put("agentAvatarUrl", agentAvatarUrl);
        response.put("agentModelProvider", agentModelProvider);
        response.put("agentModelName", agentModelName);

        return response;
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private Object parseNodeIcons() {
        if (nodeIcons == null || nodeIcons.isEmpty()) return null;
        try {
            return OBJECT_MAPPER.readValue(nodeIcons, List.class);
        } catch (Exception e) {
            return null;
        }
    }
}
