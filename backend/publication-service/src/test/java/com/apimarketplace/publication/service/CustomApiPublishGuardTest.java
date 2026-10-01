package com.apimarketplace.publication.service;

import com.apimarketplace.publication.config.CatalogInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CustomApiPublishGuard} - refuses to SHARE a snapshot built on a tenant-private
 * custom API, and leaves a PRIVATE (own-account) publication alone.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CustomApiPublishGuard")
class CustomApiPublishGuardTest {

    private static final String TENANT = "tenant-1";
    private static final String ORG = "org-7";

    @Mock private CatalogInternalClient catalogInternalClient;

    private CustomApiPublishGuard guard;

    @BeforeEach
    void setUp() {
        guard = new CustomApiPublishGuard(catalogInternalClient);
    }

    private static Map<String, Object> customApiRef(String slug, String name, String... tools) {
        return Map.of("apiSlug", slug, "apiName", name, "toolIdentifiers", List.of(tools));
    }

    /** A plan with one mcp node on the given catalog tool. */
    private static Map<String, Object> planWithMcp(String toolId) {
        return Map.of("mcps", List.of(Map.of("id", toolId, "label", "Call it")));
    }

    private void assertShared(PublicationVisibility visibility, Map<String, Object> snapshot) {
        guard.assertPublishable(visibility, snapshot, TENANT, ORG);
    }

    @SuppressWarnings("unchecked")
    private Collection<String> capturedIdentifiers() {
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(catalogInternalClient).findCustomApiRefs(captor.capture(), eq(TENANT), eq(ORG));
        return captor.getValue();
    }

    // ==================== visibility gate ====================

    @Test
    @DisplayName("PRIVATE publication is never gated - the custom API resolves in the owner's own tenant")
    void privateVisibilityIsExempt() {
        assertDoesNotThrow(() ->
                assertShared(PublicationVisibility.PRIVATE, planWithMcp("my-api/do-thing")));

        verify(catalogInternalClient, never()).findCustomApiRefs(any(), any(), any());
    }

    @Test
    @DisplayName("PUBLIC publication referencing a custom API is refused")
    void publicVisibilityIsRefused() {
        when(catalogInternalClient.findCustomApiRefs(any(), any(), any()))
                .thenReturn(List.of(customApiRef("my-api", "My API", "my-api/do-thing")));

        PublicationValidationException thrown = assertThrows(PublicationValidationException.class, () ->
                assertShared(PublicationVisibility.PUBLIC, planWithMcp("my-api/do-thing")));

        assertEquals(PublicationValidationException.CUSTOM_API_NOT_PUBLISHABLE, thrown.getErrorCode());
        assertTrue(thrown.getMessage().contains("My API"),
                "the publisher has to be told WHICH api blocks the share");
        assertEquals(List.of(customApiRef("my-api", "My API", "my-api/do-thing")),
                thrown.getDetails().get("customApis"));
    }

    @Test
    @DisplayName("UNLISTED publication referencing a custom API is refused too (link sharing still leaves the tenant)")
    void unlistedVisibilityIsRefused() {
        when(catalogInternalClient.findCustomApiRefs(any(), any(), any()))
                .thenReturn(List.of(customApiRef("my-api", "My API", "my-api/do-thing")));

        assertThrows(PublicationValidationException.class, () ->
                assertShared(PublicationVisibility.UNLISTED, planWithMcp("my-api/do-thing")));
    }

    @Test
    @DisplayName("a plan whose tools are all shipped catalog APIs passes")
    void catalogOnlyPlanPasses() {
        when(catalogInternalClient.findCustomApiRefs(any(), any(), any())).thenReturn(List.of());

        assertDoesNotThrow(() ->
                assertShared(PublicationVisibility.PUBLIC, planWithMcp("github/get-user")));
    }

    @Test
    @DisplayName("the publishing scope is forwarded so the catalog can widen the match for the publisher's OWN APIs")
    void publisherScopeIsForwarded() {
        when(catalogInternalClient.findCustomApiRefs(any(), any(), any())).thenReturn(List.of());

        assertShared(PublicationVisibility.PUBLIC, planWithMcp("my-api/do-thing"));

        verify(catalogInternalClient).findCustomApiRefs(any(), eq(TENANT), eq(ORG));
    }

    @Test
    @DisplayName("a plan with no tool reference at all never calls the catalog")
    void planWithoutToolsSkipsTheLookup() {
        Map<String, Object> plan = Map.of("triggers", List.of(Map.of("type", "manual")));

        assertDoesNotThrow(() -> assertShared(PublicationVisibility.PUBLIC, plan));

        verify(catalogInternalClient, never()).findCustomApiRefs(any(), any(), any());
    }

    @Test
    @DisplayName("a null or empty snapshot is a no-op")
    void emptySnapshotIsANoOp() {
        assertDoesNotThrow(() -> assertShared(PublicationVisibility.PUBLIC, null));
        assertDoesNotThrow(() -> assertShared(PublicationVisibility.PUBLIC, Map.of()));
        verify(catalogInternalClient, never()).findCustomApiRefs(any(), any(), any());
    }

    @Test
    @DisplayName("the refusal names every offending API when more than one is used")
    void severalApisAreAllNamed() {
        when(catalogInternalClient.findCustomApiRefs(any(), any(), any())).thenReturn(List.of(
                customApiRef("api-one", "API One", "api-one/a"),
                customApiRef("api-two", "API Two", "api-two/b")));

        PublicationValidationException thrown = assertThrows(PublicationValidationException.class, () ->
                assertShared(PublicationVisibility.PUBLIC, planWithMcp("api-one/a")));

        assertTrue(thrown.getMessage().contains("API One"));
        assertTrue(thrown.getMessage().contains("API Two"));
    }

    @Test
    @DisplayName("a nameless API entry falls back to its slug rather than printing null")
    void namelessApiFallsBackToSlug() {
        Map<String, Object> ref = new java.util.HashMap<>();
        ref.put("apiSlug", "my-api");
        ref.put("apiName", null);
        when(catalogInternalClient.findCustomApiRefs(any(), any(), any())).thenReturn(List.of(ref));

        PublicationValidationException thrown = assertThrows(PublicationValidationException.class, () ->
                assertShared(PublicationVisibility.PUBLIC, planWithMcp("my-api/do-thing")));

        assertTrue(thrown.getMessage().contains("my-api"));
        assertTrue(!thrown.getMessage().contains("null"));
    }

    // ==================== what gets collected ====================

    @Test
    @DisplayName("collects mcp node ids")
    void collectsMcpNodeIds() {
        Map<String, Object> plan = Map.of("mcps", List.of(
                Map.of("id", "github/get-user"),
                Map.of("id", "my-api/do-thing"),
                Map.of("label", "no id here")));

        assertEquals(Set.of("github/get-user", "my-api/do-thing"),
                guard.collectToolIdentifiers(plan));
    }

    @Test
    @DisplayName("collects an agent's explicit tool grant in its CANONICAL apiSlug:toolSlug form")
    void collectsAgentToolGrantsInColonForm() {
        Map<String, Object> plan = Map.of("agents", List.of(Map.of(
                "agentConfigId", "a-1",
                "toolsConfig", Map.of("mode", "custom",
                        "tools", List.of("my-api:do-thing", "github:get-user")))));

        assertEquals(Set.of("my-api:do-thing", "github:get-user"),
                guard.collectToolIdentifiers(plan));
    }

    @Test
    @DisplayName("collects a LEGACY grant stored as a raw api_tools.id UUID")
    void collectsLegacyUuidGrants() {
        Map<String, Object> plan = Map.of("agents", List.of(Map.of(
                "toolsConfig", Map.of("tools", List.of("11111111-2222-3333-4444-555555555555")))));

        assertEquals(Set.of("11111111-2222-3333-4444-555555555555"),
                guard.collectToolIdentifiers(plan));
    }

    @Test
    @DisplayName("collects tool grants from the publish-time agent snapshot key too")
    void collectsSnapshotAgentToolGrants() {
        Map<String, Object> plan = Map.of("agents", List.of(Map.of(
                "_snapshot_agent_toolsConfig", Map.of("tools", List.of("my-api:do-thing")))));

        assertEquals(Set.of("my-api:do-thing"), guard.collectToolIdentifiers(plan));
    }

    @Test
    @DisplayName("a plan's normalised mcp:<label> tool refs are NOT collected (they live outside toolsConfig)")
    void planLevelLabelRefsAreNotCollected() {
        // The raw plan's agent node lists its plan-local tools as "mcp:<label>" under a
        // top-level `tools` key. Those are not catalog identifiers, and the mcp node they
        // point at is already collected from `mcps[]`.
        Map<String, Object> plan = Map.of(
                "mcps", List.of(Map.of("id", "my-api/do-thing", "label", "Call it")),
                "agents", List.of(Map.of("agentConfigId", "a-1", "tools", List.of("mcp:call_it"))));

        assertEquals(Set.of("my-api/do-thing"), guard.collectToolIdentifiers(plan));
    }

    @Test
    @DisplayName("tolerates the object form of a tool grant ({id} / {toolSlug})")
    void collectsObjectFormToolGrants() {
        Map<String, Object> plan = Map.of("agent", Map.of(
                "toolsConfig", Map.of("tools", List.of(
                        Map.of("id", "my-api:do-thing"),
                        Map.of("toolSlug", "do-other")))));

        assertEquals(Set.of("my-api:do-thing", "do-other"), guard.collectToolIdentifiers(plan));
    }

    @Test
    @DisplayName("walks an agent snapshot: embedded workflow plans and sub-agents are covered")
    void collectsFromAgentSnapshotClosure() {
        Map<String, Object> snapshot = Map.of(
                "agent", Map.of("toolsConfig", Map.of("tools", List.of("root-api:a"))),
                "workflows", Map.of("wf-1", Map.of("plan", planWithMcp("wf-api/b"))),
                "subAgents", Map.of("ag-2", Map.of(
                        "agent", Map.of("toolsConfig", Map.of("tools", List.of("sub-api:c"))))));

        assertEquals(Set.of("root-api:a", "wf-api/b", "sub-api:c"),
                guard.collectToolIdentifiers(snapshot));
    }

    @Test
    @DisplayName("walks an enriched plan: the sub-workflow plans resolved at publish time are covered")
    void collectsSubWorkflowTools() {
        Map<String, Object> enriched = Map.of(
                "mcps", List.of(Map.of("id", "root-api/a")),
                "_snapshot_subworkflows", Map.of(
                        "wf-child", Map.of("mcps", List.of(Map.of("id", "child-api/b")))));

        assertEquals(Set.of("root-api/a", "child-api/b"), guard.collectToolIdentifiers(enriched));
    }

    @Test
    @DisplayName("a plan nested deeper than the depth bound is abandoned, not a StackOverflowError")
    void absurdlyDeepPlanIsBounded() {
        Object node = new java.util.HashMap<>(Map.of("mcps", List.of(Map.of("id", "deep-api/x"))));
        for (int i = 0; i < 2_500; i++) {
            node = new java.util.HashMap<>(Map.of("nested", node));
        }
        Object root = node;

        Set<String> found = assertDoesNotThrow(() -> guard.collectToolIdentifiers(root));

        assertTrue(found.isEmpty(), "the tool sat past the bound, so it is simply not collected");
    }

    @Test
    @DisplayName("a null snapshot collects nothing")
    void nullSnapshotCollectsNothing() {
        assertTrue(guard.collectToolIdentifiers(null).isEmpty());
    }

    @Test
    @DisplayName("blank ids are dropped and duplicates collapse before the catalog round trip")
    void identifiersAreCleanedBeforeTheLookup() {
        when(catalogInternalClient.findCustomApiRefs(any(), any(), any())).thenReturn(List.of());
        Map<String, Object> plan = Map.of("mcps", List.of(
                Map.of("id", " my-api/do-thing "),
                Map.of("id", "my-api/do-thing"),
                Map.of("id", "  ")));

        assertShared(PublicationVisibility.PUBLIC, plan);

        assertEquals(List.of("my-api/do-thing"), List.copyOf(capturedIdentifiers()));
    }
}
