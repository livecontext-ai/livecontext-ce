package com.apimarketplace.publication.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for PublicationListItem DTO and its toResponseMap() conversion.
 */
@DisplayName("PublicationListItem")
class PublicationListItemTest {

    private static final UUID PUB_ID = UUID.randomUUID();
    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final UUID SHOWCASE_IFACE_ID = UUID.randomUUID();
    private static final UUID CATEGORY_ID = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final Instant PUBLISHED_AT = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-02-01T12:00:00Z");

    private PublicationListItem createFullItem() {
        return new PublicationListItem(
                PUB_ID, "WORKFLOW", WORKFLOW_ID, null,
                "Test Title", "Test Description",
                SHOWCASE_IFACE_ID, "run_20260115_abc123", "APPLICATION",
                10, "publisher-1", "John Doe", "john@example.com", "https://avatar.url",
                "ACTIVE", "PUBLIC", "ORG", "org-1", 42, 420, 3,
                "[{\"icon\":\"zap\",\"color\":\"#3b82f6\"}]",
                0, 0, 2, 1, 0, 4.2, 12, PUBLISHED_AT, UPDATED_AT,
                CATEGORY_ID, "automation", "Automation", "zap", "#8b5cf6",
                PROJECT_ID, null, null, null, null, null
        );
    }

    private PublicationListItem createMinimalItem(String status, String visibility,
                                                   UUID showcaseIfaceId, String showcaseRunId,
                                                   String displayMode, String nodeIcons,
                                                   Integer interfaceCount, Integer datasourceCount,
                                                   Instant publishedAt, Instant updatedAt) {
        return new PublicationListItem(
                PUB_ID, "WORKFLOW", WORKFLOW_ID, null,
                "Title", "Desc",
                showcaseIfaceId, showcaseRunId, displayMode,
                0, "pub-1", null, null, null,
                status, visibility, "USER", "pub-1", 0, 0, null,
                nodeIcons,
                0, 0, interfaceCount, datasourceCount, 0, null, 0,
                publishedAt, updatedAt,
                null, null, null, null, null,
                null, null, null, null, null, null
        );
    }

    @Nested
    @DisplayName("toResponseMap()")
    class ToResponseMap {

        @Test
        @DisplayName("should produce all required fields for API compatibility")
        void allFieldsPresent() {
            PublicationListItem item = createFullItem();
            Map<String, Object> response = item.toResponseMap();

            assertThat(response).containsEntry("id", PUB_ID.toString());
            assertThat(response).containsEntry("publicationType", "WORKFLOW");
            assertThat(response).containsEntry("workflowId", WORKFLOW_ID.toString());
            assertThat(response).containsEntry("agentConfigId", null);
            assertThat(response).containsEntry("title", "Test Title");
            assertThat(response).containsEntry("description", "Test Description");
            assertThat(response).containsEntry("showcaseInterfaceId", SHOWCASE_IFACE_ID.toString());
            assertThat(response).containsEntry("showcaseRunId", "run_20260115_abc123");
            assertThat(response).containsEntry("displayMode", "APPLICATION");
            assertThat(response).containsEntry("creditsPerUse", 10);
            // publisherEmail is intentionally NOT in the public list shape -
            // it was the wholesale-harvesting risk. publisherId is kept
            // because the marketplace avatar component needs it to resolve
            // /api/proxy/users/{publisherId}/avatar.
            assertThat(response).doesNotContainKey("publisherEmail");
            assertThat(response).containsEntry("publisherId", "publisher-1");
            assertThat(response).containsEntry("publisherName", "John Doe");
            assertThat(response).containsEntry("publisherAvatarUrl", "https://avatar.url");
            assertThat(response).containsEntry("status", "ACTIVE");
            assertThat(response).containsEntry("published", true);
            assertThat(response).containsEntry("visibility", "PUBLIC");
            // Ownership scope exposed for org-aware "already owned" computation on the client.
            assertThat(response).containsEntry("ownerType", "ORG");
            assertThat(response).containsEntry("ownerId", "org-1");
            assertThat(response).containsEntry("useCount", 42);
            assertThat(response).containsEntry("totalCreditsEarned", 420);
            assertThat(response).containsEntry("planVersion", 3);
            assertThat(response).containsEntry("agentCount", 0);
            assertThat(response).containsEntry("skillCount", 0);
            assertThat(response).containsEntry("interfaceCount", 2);
            assertThat(response).containsEntry("datasourceCount", 1);
            assertThat(response).containsEntry("averageRating", 4.2);
            assertThat(response).containsEntry("reviewCount", 12);
            assertThat(response).containsEntry("publishedAt", PUBLISHED_AT.toString());
            assertThat(response).containsEntry("updatedAt", UPDATED_AT.toString());
            assertThat(response).containsEntry("projectId", PROJECT_ID.toString());
        }

        @Test
        @DisplayName("should set hasShowcase=true when both interface and run are present")
        void hasShowcaseTrue() {
            PublicationListItem item = createFullItem();
            Map<String, Object> response = item.toResponseMap();

            assertThat(response).containsEntry("hasShowcase", true);
            assertThat(response).containsEntry("isApplication", true);
        }

        @Test
        @DisplayName("should set hasShowcase=false when no interface")
        void hasShowcaseFalseWithoutInterface() {
            PublicationListItem item = createMinimalItem(
                    "ACTIVE", "PUBLIC", null, "run_123", "WORKFLOW",
                    null, 0, 0, PUBLISHED_AT, UPDATED_AT);
            Map<String, Object> response = item.toResponseMap();

            assertThat(response).containsEntry("hasShowcase", false);
            assertThat(response).containsEntry("isApplication", false);
        }

        @Test
        @DisplayName("should include category info when category fields are present")
        void categoryPresent() {
            PublicationListItem item = createFullItem();
            Map<String, Object> response = item.toResponseMap();

            @SuppressWarnings("unchecked")
            Map<String, Object> category = (Map<String, Object>) response.get("category");
            assertThat(category).isNotNull();
            assertThat(category).containsEntry("id", CATEGORY_ID.toString());
            assertThat(category).containsEntry("slug", "automation");
            assertThat(category).containsEntry("name", "Automation");
            assertThat(category).containsEntry("iconSlug", "zap");
            assertThat(category).containsEntry("color", "#8b5cf6");
        }

        @Test
        @DisplayName("should set category=null when no category")
        void categoryNull() {
            PublicationListItem item = createMinimalItem(
                    "INACTIVE", "PUBLIC", null, null, "WORKFLOW",
                    null, 0, 0, PUBLISHED_AT, UPDATED_AT);
            Map<String, Object> response = item.toResponseMap();
            assertThat(response).containsEntry("category", null);
        }

        @Test
        @DisplayName("should set published=false when status is not ACTIVE")
        void publishedFalseForInactive() {
            PublicationListItem item = createMinimalItem(
                    "INACTIVE", "PUBLIC", null, null, "WORKFLOW",
                    null, 0, 0, PUBLISHED_AT, UPDATED_AT);
            Map<String, Object> response = item.toResponseMap();
            assertThat(response).containsEntry("published", false);
        }

        @Test
        @DisplayName("should parse nodeIcons JSON string into List")
        void nodeIconsParsed() {
            PublicationListItem item = createFullItem();
            Map<String, Object> response = item.toResponseMap();

            Object nodeIcons = response.get("nodeIcons");
            assertThat(nodeIcons).isInstanceOf(List.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> iconsList = (List<Map<String, Object>>) nodeIcons;
            assertThat(iconsList).hasSize(1);
            assertThat(iconsList.get(0)).containsEntry("icon", "zap");
        }

        @Test
        @DisplayName("should return null nodeIcons when JSON string is null")
        void nodeIconsNullWhenEmpty() {
            PublicationListItem item = createMinimalItem(
                    "ACTIVE", "PUBLIC", null, null, "WORKFLOW",
                    null, 0, 0, PUBLISHED_AT, UPDATED_AT);
            Map<String, Object> response = item.toResponseMap();
            assertThat(response.get("nodeIcons")).isNull();
        }

        @Test
        @DisplayName("should return null nodeIcons when JSON is malformed")
        void nodeIconsNullWhenMalformed() {
            PublicationListItem item = createMinimalItem(
                    "ACTIVE", "PUBLIC", null, null, "WORKFLOW",
                    "not valid json{{{", 0, 0, PUBLISHED_AT, UPDATED_AT);
            Map<String, Object> response = item.toResponseMap();
            assertThat(response.get("nodeIcons")).isNull();
        }

        @Test
        @DisplayName("should default displayMode to WORKFLOW when null")
        void displayModeDefaultsToWorkflow() {
            PublicationListItem item = createMinimalItem(
                    "ACTIVE", "PUBLIC", null, null, null,
                    null, 0, 0, PUBLISHED_AT, UPDATED_AT);
            Map<String, Object> response = item.toResponseMap();
            assertThat(response).containsEntry("displayMode", "WORKFLOW");
        }

        @Test
        @DisplayName("should default resource counts to 0 when null")
        void resourceCountsDefaultToZero() {
            PublicationListItem item = createMinimalItem(
                    "ACTIVE", "PUBLIC", null, null, "WORKFLOW",
                    null, null, null, PUBLISHED_AT, UPDATED_AT);
            Map<String, Object> response = item.toResponseMap();
            assertThat(response).containsEntry("interfaceCount", 0);
            assertThat(response).containsEntry("datasourceCount", 0);
            assertThat(response).containsEntry("agentCount", 0);
            assertThat(response).containsEntry("skillCount", 0);
        }

        @Test
        @DisplayName("should include agent-specific fields when present")
        void agentFieldsPresent() {
            PublicationListItem item = new PublicationListItem(
                    PUB_ID, "AGENT", null, UUID.randomUUID(),
                    "My Agent", "Agent desc",
                    null, null, "AGENT",
                    0, "pub-1", "Jane", null, null,
                    "ACTIVE", "PUBLIC", "USER", "pub-1", 0, 0, null,
                    null,
                    1, 3, 0, 0, 0, null, 0,
                    PUBLISHED_AT, UPDATED_AT,
                    null, null, null, null, null,
                    null, null, "preset:robot", "anthropic", "claude-sonnet", null
            );
            Map<String, Object> response = item.toResponseMap();
            assertThat(response).containsEntry("agentAvatarUrl", "preset:robot");
            assertThat(response).containsEntry("agentModelProvider", "anthropic");
            assertThat(response).containsEntry("agentModelName", "claude-sonnet");
        }

        @Test
        @DisplayName("should expose resourceId for standalone TABLE / INTERFACE publications")
        void resourceIdPresentForStandaloneResource() {
            PublicationListItem item = new PublicationListItem(
                    PUB_ID, "TABLE", null, null,
                    "My Table", "Table desc",
                    null, null, "TABLE",
                    0, "pub-1", "Jane", null, null,
                    "ACTIVE", "PUBLIC", "USER", "pub-1", 0, 0, null,
                    null,
                    0, 0, 0, 0, 0, null, 0,
                    PUBLISHED_AT, UPDATED_AT,
                    null, null, null, null, null,
                    null, null, null, null, null, "datasource-42"
            );
            Map<String, Object> response = item.toResponseMap();
            assertThat(response).containsEntry("resourceId", "datasource-42");
            assertThat(response).containsEntry("publicationType", "TABLE");
        }

        @Test
        @DisplayName("should expose resourceId=null for WORKFLOW / AGENT publications")
        void resourceIdNullForWorkflow() {
            PublicationListItem item = createFullItem();
            Map<String, Object> response = item.toResponseMap();
            assertThat(response).containsEntry("resourceId", null);
        }

        @Test
        @DisplayName("should handle null timestamps gracefully")
        void nullTimestamps() {
            PublicationListItem item = createMinimalItem(
                    "ACTIVE", "PUBLIC", null, null, "WORKFLOW",
                    null, 0, 0, null, null);
            Map<String, Object> response = item.toResponseMap();
            assertThat(response).containsEntry("publishedAt", null);
            assertThat(response).containsEntry("updatedAt", null);
        }
    }
}
