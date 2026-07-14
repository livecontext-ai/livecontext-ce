package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.PublicationReviewRepository;
import com.apimarketplace.publication.repository.PublicationSnapshotVersionRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Grant-aware toolsConfig scoping at WORKFLOW publish time.
 *
 * <p>Regression pinned here: an agent with {@code <family>Grant=all} persists an
 * EMPTY id list (normalizeToolsConfig reconciliation), which the old list-only
 * scoping read as "explicitly blocked" - the cloned agent silently collapsed to
 * grant=none and lost access even to the plan's own resources. The grant-aware
 * scoping downscopes {@code all} to {@code custom + all plan IDs} instead (the
 * plan IS the dependency set), and never emits an {@code all} grant to acquirers.
 */
@DisplayName("WorkflowPublicationService.scopeToolsConfig per-family grant scoping")
class WorkflowPublicationServiceScopeToolsConfigTest {

    private static WorkflowPublicationService service;
    private static Method scopeToolsConfig;

    private static final Set<String> PLAN_TABLES = Set.of("101", "102");
    private static final Set<String> PLAN_INTERFACES = Set.of("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final Set<String> PLAN_AGENTS = Set.of("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

    @BeforeAll
    static void setUp() throws Exception {
        service = new WorkflowPublicationService(
                mock(WorkflowPublicationRepository.class), mock(PublicationSnapshotVersionRepository.class),
                mock(PublicationReceiptRepository.class), mock(PublicationReviewRepository.class),
                mock(OrchestratorInternalClient.class), mock(AgentClient.class), mock(InterfaceClient.class),
                mock(DataSourceClient.class), mock(StorageBreakdownService.class), new ObjectMapper(),
                mock(SnapshotCloneService.class), mock(EntitlementGuard.class), mock(AuthClient.class));
        scopeToolsConfig = WorkflowPublicationService.class.getDeclaredMethod(
                "scopeToolsConfig", Map.class, Set.class, Set.class, Set.class);
        scopeToolsConfig.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> scope(Map<String, Object> original) {
        try {
            return (Map<String, Object>) scopeToolsConfig.invoke(
                    service, original, PLAN_TABLES, PLAN_INTERFACES, PLAN_AGENTS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> ids(Map<String, Object> scoped, String key) {
        return (List<String>) scoped.get(key);
    }

    @Test
    @DisplayName("grant=all with the reconciled EMPTY list downscopes to custom + ALL plan IDs (regression: old logic collapsed it to none)")
    void grantAllDownscopesToPlanIds() {
        Map<String, Object> original = new HashMap<>();
        original.put("tablesGrant", "all");
        original.put("tables", List.of()); // what normalizeToolsConfig persists for grant=all

        Map<String, Object> scoped = scope(original);

        assertThat(ids(scoped, "tables")).containsExactlyInAnyOrderElementsOf(PLAN_TABLES);
        assertThat(scoped).containsEntry("tablesGrant", "custom");
        // Never re-emit "all" to acquirers.
        assertThat(scoped.values()).doesNotContain("all");
    }

    @Test
    @DisplayName("grant=custom intersects the explicit list with the plan (out-of-plan ids dropped)")
    void grantCustomIntersectsWithPlan() {
        Map<String, Object> original = new HashMap<>();
        original.put("tablesGrant", "custom");
        original.put("tables", List.of("101", "999"));

        Map<String, Object> scoped = scope(original);

        assertThat(ids(scoped, "tables")).containsExactly("101");
        assertThat(scoped).containsEntry("tablesGrant", "custom");
    }

    @Test
    @DisplayName("grant=none stays none even with a stale populated list")
    void grantNoneStaysNone() {
        Map<String, Object> original = new HashMap<>();
        original.put("interfacesGrant", "none");
        original.put("interfaces", List.of("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));

        Map<String, Object> scoped = scope(original);

        assertThat(ids(scoped, "interfaces")).isEmpty();
        assertThat(scoped).containsEntry("interfacesGrant", "none");
    }

    @Test
    @DisplayName("legacy row without grants keeps list-derived semantics: null list = all -> plan IDs, [] -> none, [ids] -> intersection")
    void legacyRowsKeepListDerivedSemantics() {
        // null/absent list = all -> plan IDs (+ custom grant emitted)
        Map<String, Object> scopedAbsent = scope(new HashMap<>());
        assertThat(ids(scopedAbsent, "tables")).containsExactlyInAnyOrderElementsOf(PLAN_TABLES);
        assertThat(scopedAbsent).containsEntry("tablesGrant", "custom");

        // [] = explicitly blocked -> none
        Map<String, Object> emptyList = new HashMap<>();
        emptyList.put("tables", List.of());
        Map<String, Object> scopedEmpty = scope(emptyList);
        assertThat(ids(scopedEmpty, "tables")).isEmpty();
        assertThat(scopedEmpty).containsEntry("tablesGrant", "none");

        // [ids] -> intersection
        Map<String, Object> withIds = new HashMap<>();
        withIds.put("tables", List.of("102", "888"));
        Map<String, Object> scopedIds = scope(withIds);
        assertThat(ids(scopedIds, "tables")).containsExactly("102");
        assertThat(scopedIds).containsEntry("tablesGrant", "custom");
    }

    @Test
    @DisplayName("custom grant whose intersection is empty emits grant=none (self-consistent: never custom with an empty payload)")
    void customWithEmptyIntersectionEmitsNone() {
        Map<String, Object> original = new HashMap<>();
        original.put("agentsGrant", "custom");
        original.put("agents", List.of("cccccccc-cccc-4ccc-8ccc-cccccccccccc")); // not in plan

        Map<String, Object> scoped = scope(original);

        assertThat(ids(scoped, "agents")).isEmpty();
        assertThat(scoped).containsEntry("agentsGrant", "none");
    }

    @Test
    @DisplayName("workflows always ['__self__'] with grant=custom; applications always denied; tools/webSearch pass through; mode forced custom")
    void fixedFamiliesAndPassthrough() {
        Map<String, Object> original = new HashMap<>();
        original.put("mode", "all");
        original.put("workflowsGrant", "all");
        original.put("applicationsGrant", "all");
        original.put("applications", List.of("dddddddd-dddd-4ddd-8ddd-dddddddddddd"));
        original.put("tools", List.of("gmail_send"));
        original.put("webSearch", true);

        Map<String, Object> scoped = scope(original);

        assertThat(ids(scoped, "workflows")).containsExactly("__self__");
        assertThat(scoped).containsEntry("workflowsGrant", "custom");
        assertThat(ids(scoped, "applications")).isEmpty();
        assertThat(scoped).containsEntry("applicationsGrant", "none");
        assertThat(scoped).containsEntry("mode", "custom");
        assertThat(scoped).containsEntry("tools", List.of("gmail_send"));
        assertThat(scoped).containsEntry("webSearch", true);
    }

    @Test
    @DisplayName("null original toolsConfig scopes to plan IDs across the three plan families (legacy null = all)")
    void nullOriginalScopesToPlan() {
        Map<String, Object> scoped = scope(null);

        assertThat(ids(scoped, "tables")).containsExactlyInAnyOrderElementsOf(PLAN_TABLES);
        assertThat(ids(scoped, "interfaces")).containsExactlyInAnyOrderElementsOf(PLAN_INTERFACES);
        assertThat(ids(scoped, "agents")).containsExactlyInAnyOrderElementsOf(PLAN_AGENTS);
        assertThat(scoped).containsEntry("tablesGrant", "custom");
        assertThat(scoped).containsEntry("interfacesGrant", "custom");
        assertThat(scoped).containsEntry("agentsGrant", "custom");
    }
}
