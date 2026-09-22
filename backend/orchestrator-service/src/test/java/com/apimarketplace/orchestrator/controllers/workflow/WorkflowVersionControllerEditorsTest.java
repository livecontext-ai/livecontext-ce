package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.auth.client.access.OrgAccessDeniedException;
import com.apimarketplace.orchestrator.controllers.dto.ResourceEditorDto;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowPinService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.orchestrator.services.activity.WorkflowEditorsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The "who recently edited this workflow" read.
 *
 * <p>The interesting behaviour is not the happy path, it is who is refused and what the wire
 * actually carries: a workflow in another workspace must be indistinguishable from one that
 * does not exist, a member the org deny-list restricts must be refused the same way they are
 * refused the workflow itself, and neither may cost an editor lookup.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowVersionController - recent editors")
class WorkflowVersionControllerEditorsTest {

    @Mock private WorkflowPlanVersionService versionService;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowPinService pinService;
    @Mock private com.apimarketplace.auth.client.access.OrgAccessGuard orgAccessGuard;
    @Mock private WorkflowManagementService workflowManagementService;
    @Mock private WorkflowEditorsService editorsService;

    private WorkflowVersionController controller;

    /**
     * Deliberately carries hex LETTERS. An all-digit uuid is identical in upper and lower case,
     * so the case-canonicalisation test below would pass against the very bug it exists for.
     */
    private static final UUID WORKFLOW_ID = UUID.fromString("a1b2c3d4-e5f6-4789-abcd-ef0123456789");
    private static final String WORKFLOW_ID_STR = WORKFLOW_ID.toString();
    private static final String TENANT_ID = "tenant-owner";
    private static final String ORG_ID = "org-1";
    private static final String ROLE = "MEMBER";

    @BeforeEach
    void setUp() {
        controller = new WorkflowVersionController(versionService, workflowRepository, workflowRunRepository,
                pinService, new ObjectMapper(), orgAccessGuard, workflowManagementService, editorsService);
    }

    @Test
    @DisplayName("returns the editors of a workflow in the caller's workspace")
    void returnsEditorsInScope() {
        // Arrange
        givenWorkflowIn(ORG_ID);
        givenNotRestricted();
        when(editorsService.listRecentEditors(WORKFLOW_ID)).thenReturn(List.of(
                new ResourceEditorDto("7", "Ada", Instant.parse("2026-09-07T09:12:00Z"), 3)));

        // Act
        ResponseEntity<?> response = controller.listRecentEditors(WORKFLOW_ID_STR, TENANT_ID, ORG_ID, ROLE);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(editorsOf(response)).hasSize(1);
        assertThat(editorsOf(response).get(0).displayName()).isEqualTo("Ada");
    }

    @Test
    @DisplayName("a workflow owned by another workspace is 404, and its editors are never computed")
    void outOfScopeIsNotFoundAndComputesNothing() {
        // Arrange - the row exists, but belongs to a different org than the caller's active one.
        // The deny-list is stubbed PERMISSIVE on purpose: with Mockito's default `false` it
        // would refuse this call by itself, so deleting the scope check would still produce a
        // refusal and this test would pass while the leak was wide open. Allowing everything
        // leaves the scope check as the only thing standing here.
        givenWorkflowIn("some-other-org");
        // `lenient` because the scope check refuses before the guard is ever consulted - which
        // is the point: this stub is here so that DELETING the scope check produces a leak
        // rather than another refusal, not because the happy path uses it.
        lenient().when(orgAccessGuard.canAccess(any(), any(), any(), any(), any())).thenReturn(true);

        // Act
        ResponseEntity<?> response = controller.listRecentEditors(WORKFLOW_ID_STR, TENANT_ID, ORG_ID, ROLE);

        // Assert - 404 not 403: the row's existence must not leak across workspaces, and the
        // names of people in another workspace must never be resolved for this caller.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(editorsService, never()).listRecentEditors(any());
    }

    @Test
    @DisplayName("a member the org deny-list restricts is refused, exactly as they are for the workflow itself")
    void restrictedMemberIsRefused() {
        // Arrange - in scope, but this member is restricted from this specific workflow.
        givenWorkflowIn(ORG_ID);
        when(orgAccessGuard.canAccess(ORG_ID, TENANT_ID, "workflow", WORKFLOW_ID_STR, ROLE)).thenReturn(false);

        // Act + Assert - knowing WHO works on a workflow is knowing something about a
        // workflow you were refused; a softer answer here would be a hole in the deny-list.
        assertThatThrownBy(() -> controller.listRecentEditors(WORKFLOW_ID_STR, TENANT_ID, ORG_ID, ROLE))
                .isInstanceOf(OrgAccessDeniedException.class);
        verify(editorsService, never()).listRecentEditors(any());
    }

    @Test
    @DisplayName("a restricted member cannot slip past the deny-list by changing the id's case")
    void denyListIsCheckedOnTheCanonicalId() {
        // Arrange - the same workflow, asked for in uppercase. `UUID.fromString` accepts it, so
        // the row resolves and the scope check passes; only the deny-list stands between this
        // caller and the answer.
        givenWorkflowIn(ORG_ID);
        // The guard as it really behaves: an exact match against the ids an admin restricted,
        // which are canonical lowercase. Any OTHER spelling misses the set and comes back
        // "allowed" - so a mock that denied everything could not tell the two apart, and this
        // test would pass with the bug in place.
        when(orgAccessGuard.canAccess(any(), any(), any(), any(), any())).thenReturn(true);
        when(orgAccessGuard.canAccess(ORG_ID, TENANT_ID, "workflow", WORKFLOW_ID_STR, ROLE)).thenReturn(false);

        // Act + Assert - asked for in uppercase, which UUID.fromString accepts. Handing the raw
        // path to the guard would miss the deny-list, and the member would be told who works on
        // a workflow they were explicitly denied.
        assertThatThrownBy(() -> controller.listRecentEditors(
                WORKFLOW_ID_STR.toUpperCase(Locale.ROOT), TENANT_ID, ORG_ID, ROLE))
                .isInstanceOf(OrgAccessDeniedException.class);
        verify(editorsService, never()).listRecentEditors(any());
    }

    @Test
    @DisplayName("the version drawer is withheld from the same restricted member, not just the editor list")
    void versionDrawerIsDenyListedToo() {
        // The whole class, not the reported instance: every version row carries `createdBy`,
        // so a deny-list on the editors endpoint alone withholds nothing - the member simply
        // asks /versions and reads the same names.
        givenWorkflowIn(ORG_ID);
        when(orgAccessGuard.canAccess(ORG_ID, TENANT_ID, "workflow", WORKFLOW_ID_STR, ROLE)).thenReturn(false);

        assertThatThrownBy(() -> controller.listVersions(WORKFLOW_ID_STR, TENANT_ID, ORG_ID, ROLE))
                .isInstanceOf(OrgAccessDeniedException.class);
        verify(versionService, never()).listVersions(any());
    }

    @Test
    @DisplayName("one version with its plan is withheld from the same restricted member")
    void singleVersionReadIsDenyListedToo() {
        // The heaviest of the three reads: it returns the whole plan as well as the author, and
        // version numbers start at 1, so a member who was refused the workflow could walk them.
        givenWorkflowIn(ORG_ID);
        when(orgAccessGuard.canAccess(ORG_ID, TENANT_ID, "workflow", WORKFLOW_ID_STR, ROLE)).thenReturn(false);

        assertThatThrownBy(() -> controller.getVersion(WORKFLOW_ID_STR, 1, TENANT_ID, ORG_ID, ROLE))
                .isInstanceOf(OrgAccessDeniedException.class);
        verify(versionService, never()).getVersion(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("the anonymous share viewer is given the versions, but not who wrote them")
    void shareViewerGetsNoAuthorOnTheVersionList() {
        // The /s/{token} application viewer reaches this endpoint through the gateway's
        // allow-list. It is refused the workflow's OWNER id elsewhere in the same change, so
        // handing it an author id per version would have withheld nothing - a strictly larger
        // set of user ids about the same workflow.
        givenWorkflowIn(null);
        WorkflowPlanVersionEntity version = new WorkflowPlanVersionEntity();
        version.setVersion(1);
        version.setCreatedBy("42");
        when(versionService.listVersions(WORKFLOW_ID)).thenReturn(List.of(version));
        when(versionService.getCurrentVersion(WORKFLOW_ID)).thenReturn(1);
        when(workflowRunRepository.countRunsByPlanVersion(WORKFLOW_ID)).thenReturn(List.of());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Share-Context", "true");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            ResponseEntity<?> response = controller.listVersions(WORKFLOW_ID_STR, TENANT_ID, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("versions");
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).containsKey("version");
            assertThat(rows.get(0)).doesNotContainKey("createdBy");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("a signed-in caller still gets the author on the version list")
    void signedInCallerKeepsTheAuthorOnTheVersionList() {
        // The other half: withholding must be about the SHARE context, not about the endpoint.
        givenWorkflowIn(null);
        WorkflowPlanVersionEntity version = new WorkflowPlanVersionEntity();
        version.setVersion(1);
        version.setCreatedBy("42");
        when(versionService.listVersions(WORKFLOW_ID)).thenReturn(List.of(version));
        when(versionService.getCurrentVersion(WORKFLOW_ID)).thenReturn(1);
        when(workflowRunRepository.countRunsByPlanVersion(WORKFLOW_ID)).thenReturn(List.of());

        ResponseEntity<?> response = controller.listVersions(WORKFLOW_ID_STR, TENANT_ID, null, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("versions");
        assertThat(rows.get(0)).containsEntry("createdBy", "42");
    }

    @Test
    @DisplayName("a personal-workspace workflow is not put through the org deny-list at all")
    void personalScopeSkipsTheDenyList() {
        // Arrange - no org on the row: there is no membership to restrict.
        givenWorkflowIn(null);
        when(editorsService.listRecentEditors(WORKFLOW_ID)).thenReturn(List.of());

        // Act
        ResponseEntity<?> response = controller.listRecentEditors(WORKFLOW_ID_STR, TENANT_ID, null, null);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(orgAccessGuard, never()).canAccess(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("an unknown workflow is 404")
    void unknownWorkflowIsNotFound() {
        // Arrange
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.empty());

        // Act
        ResponseEntity<?> response = controller.listRecentEditors(WORKFLOW_ID_STR, TENANT_ID, ORG_ID, ROLE);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(editorsService, never()).listRecentEditors(any());
    }

    @Test
    @DisplayName("a malformed id is a bad request, not a 500")
    void malformedIdIsBadRequest() {
        // Act
        ResponseEntity<?> response = controller.listRecentEditors("not-a-uuid", TENANT_ID, ORG_ID, ROLE);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(editorsService, never()).listRecentEditors(any());
    }

    @Test
    @DisplayName("a workflow nobody has edited yet answers with an empty list, not an error")
    void noEditorsIsAnEmptyList() {
        // Arrange
        givenWorkflowIn(ORG_ID);
        givenNotRestricted();
        when(editorsService.listRecentEditors(WORKFLOW_ID)).thenReturn(List.of());

        // Act
        ResponseEntity<?> response = controller.listRecentEditors(WORKFLOW_ID_STR, TENANT_ID, ORG_ID, ROLE);

        // Assert - the popover renders no editors section for this; an error would make it
        // retry forever on a workflow that simply has no recorded history.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(editorsOf(response)).isEmpty();
    }

    @Test
    @DisplayName("a failure inside the service is a 500 that leaks no detail")
    void serviceFailureIsFiveHundred() {
        // Arrange
        givenWorkflowIn(ORG_ID);
        givenNotRestricted();
        when(editorsService.listRecentEditors(WORKFLOW_ID)).thenThrow(new RuntimeException("jdbc: table gone"));

        // Act
        ResponseEntity<?> response = controller.listRecentEditors(WORKFLOW_ID_STR, TENANT_ID, ORG_ID, ROLE);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(String.valueOf(response.getBody())).doesNotContain("jdbc");
    }

    @Test
    @DisplayName("serializes an unnamed editor by OMITTING the name, which is what the client reads as unresolved")
    void serializesAnUnnamedEditorByOmittingTheName() throws Exception {
        // Arrange - a real HTTP round-trip, because the record's @JsonInclude(NON_NULL) is the
        // thing under test and calling the method directly never exercises Jackson at all.
        givenWorkflowIn(ORG_ID);
        givenNotRestricted();
        when(editorsService.listRecentEditors(WORKFLOW_ID)).thenReturn(List.of(
                new ResourceEditorDto("7", null, Instant.parse("2026-09-07T09:12:00Z"), 2)));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        // Act + Assert
        mockMvc.perform(get("/api/v2/workflows/dag/{id}/editors", WORKFLOW_ID_STR)
                        .header("X-User-ID", TENANT_ID)
                        .header("X-Organization-ID", ORG_ID)
                        .header("X-Organization-Role", ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editors[0].userId").value("7"))
                .andExpect(jsonPath("$.editors[0].editCount").value(2))
                // ABSENT, not null: the client's fallback chain tests for a missing name and
                // would otherwise print the string "null" next to an avatar.
                .andExpect(jsonPath("$.editors[0].displayName").doesNotExist())
                // `editedAt` is present, but its FORMAT is deliberately not asserted here.
                // Whether an Instant reaches the client as an ISO string or as an epoch is
                // decided by the app-wide `WRITE_DATES_AS_TIMESTAMPS: false` in
                // application.yml, which a standalone MockMvc does not read: pinning it
                // against a mapper this test configured itself would certify the fixture,
                // not the app. Every other DTO on this service ships Instants the same way.
                .andExpect(jsonPath("$.editors[0].editedAt").exists());
    }

    private void givenWorkflowIn(String organizationId) {
        WorkflowEntity workflow = new WorkflowEntity();
        ReflectionTestUtils.setField(workflow, "id", WORKFLOW_ID);
        workflow.setTenantId(TENANT_ID);
        workflow.setOrganizationId(organizationId);
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
    }

    private void givenNotRestricted() {
        when(orgAccessGuard.canAccess(eq(ORG_ID), eq(TENANT_ID), eq("workflow"), eq(WORKFLOW_ID_STR), any()))
                .thenReturn(true);
    }

    @SuppressWarnings("unchecked")
    private static List<ResourceEditorDto> editorsOf(ResponseEntity<?> response) {
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        return (List<ResourceEditorDto>) body.get("editors");
    }
}
