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
        String resourceId,
        // V413 - public SEO surface. publicSlug backs /marketplace/{slug};
        // publisherHandle backs the author link /u/{handle}. Both may be null
        // (rows predating the backfill, publishers without a handle).
        String publicSlug,
        String publisherHandle,
        // V420 - CE-exclusive label. ceExclusive drives the marketplace badge and
        // the disabled install button on managed cloud; ceExclusiveFeatures is the
        // raw JSON array of detected feature codes (CLI_AGENT, VECTOR_SEARCH),
        // read as TEXT here like nodeIcons and parsed in toResponseMap().
        Boolean ceExclusive,
        String ceExclusiveFeatures,
        /**
         * True when this publication belongs in the Studio: it PRODUCES a media asset rather than
         * finding, publishing or reading one. A SECOND AXIS - the category keeps saying what the
         * application is about.
         */
        Boolean studio,
        /**
         * V483 - raw JSON array of node-type tokens ({@code ["mcp:gmail","core:loop"]}),
         * read as TEXT like nodeIcons and parsed in {@link #toResponseMap()}. Backs the
         * node-type filter on the applications and marketplace lists.
         */
        String nodeTypes
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

        // The studio axis travels beside the category, never instead of it: the reader still needs
        // to know what the application is ABOUT.
        response.put("studio", Boolean.TRUE.equals(studio));
        response.put("creditsPerUse", creditsPerUse);
        // publisherId is exposed because the marketplace avatar component
        // resolves /api/proxy/users/{publisherId}/avatar - without it
        // every card falls back to a generic User icon.
        // publisherEmail stays stripped - that was the real harvesting risk.
        response.put("publisherId", publisherId);
        response.put("publisherName", publisherName);
        response.put("publisherAvatarUrl", publisherAvatarUrl);
        // publisherHandle is the ONLY publisher field safe to put in a public URL:
        // it is user-chosen and already public by design, unlike publisherId.
        response.put("publisherHandle", publisherHandle);
        response.put("publicSlug", publicSlug);
        // CE-exclusive label. Always emitted (never omitted on false) so the UI
        // can render "installable" without treating an absent key as unknown.
        response.put("ceExclusive", Boolean.TRUE.equals(ceExclusive));
        response.put("ceExclusiveFeatures", parseCeExclusiveFeatures());
        response.put("status", status);
        response.put("published", "ACTIVE".equals(status));
        response.put("visibility", visibility);
        response.put("ownerType", ownerType);
        response.put("ownerId", ownerId);
        response.put("useCount", useCount);
        response.put("totalCreditsEarned", totalCreditsEarned);
        response.put("planVersion", planVersion);
        response.put("nodeIcons", parseNodeIcons());
        response.put("nodeTypes", parseNodeTypes());
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

    /**
     * Detected CE-exclusive feature codes. Falls back to an EMPTY list (never
     * null) so the badge tooltip has something to iterate even for a legacy row
     * whose column predates the backfill or holds malformed JSON.
     */
    private List<?> parseCeExclusiveFeatures() {
        if (ceExclusiveFeatures == null || ceExclusiveFeatures.isBlank()) return List.of();
        try {
            return OBJECT_MAPPER.readValue(ceExclusiveFeatures, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private Object parseNodeIcons() {
        if (nodeIcons == null || nodeIcons.isEmpty()) return null;
        try {
            return OBJECT_MAPPER.readValue(nodeIcons, List.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Node-type tokens as a list. Falls back to an EMPTY list rather than null
     * (unlike {@link #parseNodeIcons()}, whose null means "draw no glyphs"): the
     * client filters on this, and "no tokens" is a real, filterable answer while
     * a null would force every reader to null-check a collection.
     */
    private Object parseNodeTypes() {
        if (nodeTypes == null || nodeTypes.isEmpty()) return List.of();
        try {
            return OBJECT_MAPPER.readValue(nodeTypes, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
