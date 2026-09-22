package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.orchestrator.controllers.dto.WorkflowSummary;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowBoardService;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.folder.WorkflowFolderService;
import com.apimarketplace.publication.client.PublicationClient;
import com.apimarketplace.trigger.client.TriggerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/workflows?nodeTypes=...} - the node-type filter and the facets
 * that populate its picker.
 *
 * <p>Two behaviours carry the feature and are easy to break silently: the filter
 * is ANY-of over the whole tenant set (so {@code totalCount} describes the
 * filtered set, not the page), and the facet counts are taken BEFORE the filter
 * is applied (so ticking one option does not zero every other one).
 */
@DisplayName("WorkflowListController.listWorkflows - node-type filter")
class WorkflowListControllerNodeTypeFilterTest {

    private static final String TENANT = "tenant-1";

    private WorkflowManagementService workflowService;
    private WorkflowListController controller;

    @BeforeEach
    void setUp() {
        workflowService = mock(WorkflowManagementService.class);
        PublicationClient publicationClient = mock(PublicationClient.class);
        controller = new WorkflowListController(
                mock(WorkflowRepository.class),
                mock(WorkflowRunRepository.class),
                mock(SignalWaitRepository.class),
                mock(TriggerClient.class),
                publicationClient,
                workflowService,
                mock(WorkflowBoardService.class),
                mock(OrgAccessGuard.class),
                mock(WorkflowFolderService.class));
        lenient().when(publicationClient.findPublicationStatusesByWorkflowIds(any(), any()))
                .thenReturn(Map.of());
    }

    /**
     * A workflow whose plan holds the given nodes. The plan is what carries the
     * node types here: the entity derives them, so the test never has to know
     * the token spelling the extractor will produce.
     */
    private WorkflowEntity wf(String name, Map<String, Object> plan) {
        WorkflowEntity entity = new WorkflowEntity(TENANT, name, TENANT);
        entity.setId(UUID.randomUUID());
        entity.setUpdatedAt(Instant.now());
        entity.setPlan(plan);
        return entity;
    }

    private static Map<String, Object> planWithMcp(String apiSlug) {
        Map<String, Object> plan = new HashMap<>();
        plan.put("mcps", List.of(Map.of("id", apiSlug + "/action")));
        return plan;
    }

    private static Map<String, Object> planWithCore(String type) {
        Map<String, Object> plan = new HashMap<>();
        plan.put("cores", List.of(Map.of("type", type)));
        return plan;
    }

    private ResponseEntity<Map<String, Object>> list(String nodeTypes) {
        return controller.listWorkflows(
                TENANT, null, null, null, null, 25, 0, null, null, null, nodeTypes, true, null, false);
    }

    @SuppressWarnings("unchecked")
    private static List<String> names(ResponseEntity<Map<String, Object>> response) {
        return ((List<WorkflowSummary>) response.getBody().get("workflows"))
                .stream().map(WorkflowSummary::name).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> facets(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) response.getBody().get("nodeTypeFacets");
    }

    @Test
    @DisplayName("keeps only the workflows containing the requested node type")
    void filtersToMatchingWorkflows() {
        when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(
                wf("Gmail digest", planWithMcp("gmail")),
                wf("Slack alerts", planWithMcp("slack"))));

        assertThat(names(list("mcp:gmail"))).containsExactly("Gmail digest");
    }

    @Test
    @DisplayName("several types mean ANY of them, not all of them")
    void severalTypesMeanAnyOfThem() {
        when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(
                wf("Gmail digest", planWithMcp("gmail")),
                wf("Slack alerts", planWithMcp("slack")),
                wf("Notion sync", planWithMcp("notion"))));

        // Order is not asserted, because this endpoint does not return the input order: it sorts
        // by last-modified descending, and `wf` stamps Instant.now() per row, so the rows come
        // back newest-first. Whether that reverses these two depends on the clock's granularity -
        // two consecutive Instant.now() calls can land on the same tick and fall through to the
        // name tie-break instead - which is why asserting the order passed locally and failed in
        // CI. What this test is about is the ANY-of filter, and that is what it now asserts. The
        // sibling test at `unfilteredListReturnsEverything` already reads it this way.
        assertThat(names(list("mcp:gmail,mcp:slack")))
                .containsExactlyInAnyOrder("Gmail digest", "Slack alerts");
    }

    @Test
    @DisplayName("filters the whole tenant set, so totalCount reports the filtered size")
    void totalCountReflectsTheFilteredSet() {
        when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(
                wf("Gmail digest", planWithMcp("gmail")),
                wf("Slack alerts", planWithMcp("slack")),
                wf("Notion sync", planWithMcp("notion"))));

        assertThat(list("mcp:gmail").getBody().get("totalCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("an absent filter leaves the list untouched")
    void noFilterReturnsEverything() {
        when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(
                wf("Gmail digest", planWithMcp("gmail")),
                wf("Slack alerts", planWithMcp("slack"))));

        assertThat(names(list(null))).containsExactlyInAnyOrder("Gmail digest", "Slack alerts");
    }

    @Test
    @DisplayName("an unknown token returns nothing rather than everything")
    void unknownTokenReturnsNothing() {
        // The opposite failure - a token nobody carries widening back to the full
        // list - would look like the filter silently doing nothing.
        when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(
                wf("Gmail digest", planWithMcp("gmail"))));

        assertThat(names(list("mcp:nonexistent"))).isEmpty();
    }

    @Test
    @DisplayName("the filter is case-insensitive, so a hand-typed token still matches")
    void filterIsCaseInsensitive() {
        when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(
                wf("Gmail digest", planWithMcp("gmail"))));

        assertThat(names(list("MCP:Gmail"))).containsExactly("Gmail digest");
    }

    @Test
    @DisplayName("facets count the workspace's types, ordered by how many workflows use them")
    void facetsCountTheWorkspace() {
        when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(
                wf("A", planWithCore("loop")),
                wf("B", planWithCore("loop")),
                wf("C", planWithMcp("gmail"))));

        assertThat(facets(list(null))).containsExactly(
                Map.of("value", "core:loop", "count", 2),
                Map.of("value", "mcp:gmail", "count", 1));
    }

    @Test
    @DisplayName("facets are counted BEFORE the filter, so a ticked option keeps its count")
    void facetsIgnoreTheNodeTypeFilterItself() {
        // Counting after the filter would leave the picker showing the chosen
        // option and nothing else - every other option reading 0 and looking dead.
        when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(
                wf("Gmail digest", planWithMcp("gmail")),
                wf("Slack alerts", planWithMcp("slack"))));

        ResponseEntity<Map<String, Object>> response = list("mcp:gmail");

        assertThat(names(response)).containsExactly("Gmail digest");
        assertThat(facets(response)).containsExactlyInAnyOrder(
                Map.of("value", "mcp:gmail", "count", 1),
                Map.of("value", "mcp:slack", "count", 1));
    }

    @Test
    @DisplayName("no facets are computed unless the caller asks - the node pickers read this endpoint too")
    void facetsAreOmittedUnlessRequested() {
        when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(
                wf("Gmail digest", planWithMcp("gmail"))));

        ResponseEntity<Map<String, Object>> response = controller.listWorkflows(
                TENANT, null, null, null, null, 25, 0, null, null, null, null, false, null, false);

        assertThat(response.getBody()).doesNotContainKey("nodeTypeFacets");
    }

    @Test
    @DisplayName("a workspace with no workflows reports no facets rather than failing")
    void emptyWorkspaceHasNoFacets() {
        when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of());

        assertThat(facets(list(null))).isEmpty();
    }
}
