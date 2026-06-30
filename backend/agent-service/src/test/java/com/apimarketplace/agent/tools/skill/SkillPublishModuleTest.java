package com.apimarketplace.agent.tools.skill;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.publication.client.PublicationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for SkillPublishModule - wraps publication-service's generic
 * publishResource endpoint for the SKILL type. interface_id is REQUIRED
 * (skills need a landing page; the resource itself is opaque to acquirers).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillPublishModule")
class SkillPublishModuleTest {

    @Mock private PublicationClient publicationClient;

    private SkillPublishModule module;
    private static final String TENANT = "tenant-1";
    private static final String TEST_ORG_ID = "org-77";
    private static final UUID SKILL_ID = UUID.randomUUID();
    private static final UUID INTERFACE_ID = UUID.randomUUID();
    private static final UUID PUB_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        module = new SkillPublishModule(publicationClient);
    }

    private ToolExecutionContext ctx() {
        // 2026-05-21 - context carries TEST_ORG_ID so unpublish regression
        // test asserts orgId is threaded to publicationClient.unpublishResource.
        return new ToolExecutionContext(TENANT, java.util.Map.of(), java.util.Map.of(),
                java.util.Set.of(), null, null, TEST_ORG_ID, null);
    }

    @Nested
    @DisplayName("publish")
    class PublishTests {

        @Test
        @DisplayName("Sends type=SKILL, resourceId, interfaceId to publication-service")
        void publishSendsCorrectShape() {
            when(publicationClient.publishResource(any(), eq(TENANT), eq(TEST_ORG_ID)))
                    .thenReturn(Map.of("id", PUB_ID.toString(), "title", "Cool Skill"));

            Map<String, Object> params = new HashMap<>();
            params.put("skill_id", SKILL_ID.toString());
            params.put("title", "Cool Skill");
            params.put("interface_id", INTERFACE_ID.toString());
            params.put("visibility", "unlisted");
            params.put("credits_per_use", 2);

            ToolExecutionResult result = module.execute("publish", params, TENANT, ctx()).orElseThrow();

            assertThat(result.success()).isTrue();
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(publicationClient).publishResource(captor.capture(), eq(TENANT), eq(TEST_ORG_ID));
            Map<String, Object> sent = captor.getValue();
            assertThat(sent).containsEntry("type", "SKILL");
            assertThat(sent).containsEntry("resourceId", SKILL_ID.toString());
            assertThat(sent).containsEntry("interfaceId", INTERFACE_ID.toString());
            assertThat(sent).containsEntry("visibility", "UNLISTED");
            assertThat(sent).containsEntry("creditsPerUse", 2);
        }

        @Test @DisplayName("Returns failure when skill_id is missing")
        void publishMissingSkillId() {
            ToolExecutionResult result = module.execute("publish",
                    Map.of("title", "X", "interface_id", INTERFACE_ID.toString()), TENANT, ctx()).orElseThrow();
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("skill_id is required");
        }

        @Test @DisplayName("Returns failure when interface_id is missing - skills need a landing page")
        void publishMissingInterfaceId() {
            ToolExecutionResult result = module.execute("publish",
                    Map.of("skill_id", SKILL_ID.toString(), "title", "X"), TENANT, ctx()).orElseThrow();
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("interface_id is required");
        }

        @Test @DisplayName("Returns failure when title is missing")
        void publishMissingTitle() {
            ToolExecutionResult result = module.execute("publish",
                    Map.of("skill_id", SKILL_ID.toString(), "interface_id", INTERFACE_ID.toString()),
                    TENANT, ctx()).orElseThrow();
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("title is required");
        }
    }

    @Nested
    @DisplayName("unpublish")
    class UnpublishTests {

        @Test @DisplayName("unpublish threads ctx.orgId() to unpublishResource - regression: org-bleed when two workspaces published the same SKILL resourceId")
        void unpublishHappyPath() {
            when(publicationClient.isResourcePublished("SKILL", SKILL_ID.toString())).thenReturn(true);

            ToolExecutionResult result = module.execute("unpublish",
                    Map.of("skill_id", SKILL_ID.toString()), TENANT, ctx()).orElseThrow();

            assertThat(result.success()).isTrue();
            // Strict eq() on TEST_ORG_ID - passing null orgId would still
            // match `any()` but breaks the wire contract that scopes the
            // unpublish to the caller's workspace.
            verify(publicationClient).unpublishResource("SKILL", SKILL_ID.toString(), TENANT, TEST_ORG_ID);
        }

        @Test @DisplayName("Returns failure when skill not published - never calls unpublishResource")
        void unpublishNotPublished() {
            when(publicationClient.isResourcePublished("SKILL", SKILL_ID.toString())).thenReturn(false);

            ToolExecutionResult result = module.execute("unpublish",
                    Map.of("skill_id", SKILL_ID.toString()), TENANT, ctx()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Resource not published");
            verify(publicationClient, never()).unpublishResource(any(), any(), any(), any());
        }
    }
}
