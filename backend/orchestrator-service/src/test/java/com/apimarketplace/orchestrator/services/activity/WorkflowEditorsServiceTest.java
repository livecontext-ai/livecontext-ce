package com.apimarketplace.orchestrator.services.activity;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.auth.UserSummaryDto;
import com.apimarketplace.orchestrator.controllers.dto.ResourceEditorDto;
import com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The people behind a workflow's stored plan versions, as the resource-info popover reads them.
 */
class WorkflowEditorsServiceTest {

    private WorkflowPlanVersionRepository versionRepository;
    private AuthClient authClient;
    private WorkflowEditorsService service;

    private static final UUID WORKFLOW_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");

    @BeforeEach
    void setUp() {
        versionRepository = mock(WorkflowPlanVersionRepository.class);
        authClient = mock(AuthClient.class);
        service = new WorkflowEditorsService(versionRepository, authClient);
        when(authClient.batchResolveUsers(anySet())).thenReturn(Map.of());
    }

    @Test
    @DisplayName("collapses several saves by one person into one editor carrying their latest")
    void collapsesSavesByTheSamePersonIntoOneEditor() {
        // Arrange - three saves, newest first, all by user 7.
        givenVersions(
                author(12, T0.plusSeconds(300), "7"),
                author(11, T0.plusSeconds(200), "7"),
                author(10, T0.plusSeconds(100), "7"));

        // Act
        List<ResourceEditorDto> editors = service.listRecentEditors(WORKFLOW_ID);

        // Assert - one PERSON, tallied, stamped with the most recent save.
        assertThat(editors).hasSize(1);
        assertThat(editors.get(0).userId()).isEqualTo("7");
        assertThat(editors.get(0).editCount()).isEqualTo(3);
        assertThat(editors.get(0).editedAt()).isEqualTo(T0.plusSeconds(300));
    }

    @Test
    @DisplayName("orders editors by their most recent save, not by how much they saved")
    void ordersByMostRecentSaveNotByVolume() {
        // Arrange - user 9 saved once, most recently; user 7 saved three times before that.
        givenVersions(
                author(13, T0.plusSeconds(400), "9"),
                author(12, T0.plusSeconds(300), "7"),
                author(11, T0.plusSeconds(200), "7"),
                author(10, T0.plusSeconds(100), "7"));

        // Act
        List<ResourceEditorDto> editors = service.listRecentEditors(WORKFLOW_ID);

        // Assert
        assertThat(editors).extracting(ResourceEditorDto::userId).containsExactly("9", "7");
        assertThat(editors.get(0).editCount()).isEqualTo(1);
        assertThat(editors.get(1).editCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("skips versions with no recorded author instead of attributing them to anyone")
    void skipsAuthorlessVersions() {
        // Arrange - the newest two rows predate author stamping.
        givenVersions(
                author(12, T0.plusSeconds(300), null),
                author(11, T0.plusSeconds(200), "  "),
                author(10, T0.plusSeconds(100), "7"));

        // Act
        List<ResourceEditorDto> editors = service.listRecentEditors(WORKFLOW_ID);

        // Assert - the one real author, and NOT credited with the anonymous saves.
        assertThat(editors).hasSize(1);
        assertThat(editors.get(0).userId()).isEqualTo("7");
        assertThat(editors.get(0).editCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a history with no recorded author at all yields no editors and asks auth nothing")
    void wholeHistoryWithoutAuthorsYieldsNothing() {
        // Arrange
        givenVersions(author(2, T0.plusSeconds(200), null), author(1, T0, null));

        // Act
        List<ResourceEditorDto> editors = service.listRecentEditors(WORKFLOW_ID);

        // Assert - an empty answer must not cost an RPC.
        assertThat(editors).isEmpty();
        verify(authClient, never()).batchResolveUsers(anySet());
    }

    @Test
    @DisplayName("a workflow with no stored version yields no editors")
    void noVersionsYieldsNothing() {
        // Arrange
        when(versionRepository.findVersionAuthors(WORKFLOW_ID)).thenReturn(List.of());

        // Act + Assert
        assertThat(service.listRecentEditors(WORKFLOW_ID)).isEmpty();
        verify(authClient, never()).batchResolveUsers(anySet());
    }

    @Test
    @DisplayName("caps the list, keeping the most recently active people")
    void capsTheListAtTheMostRecentlyActivePeople() {
        // Arrange - one more distinct editor than the cap allows, newest first.
        List<WorkflowPlanVersionRepository.VersionAuthorProjection> rows = new ArrayList<>();
        int distinct = WorkflowEditorsService.MAX_EDITORS + 1;
        for (int i = 0; i < distinct; i++) {
            rows.add(author(100 - i, T0.minusSeconds(i * 60L), "user-" + i));
        }
        when(versionRepository.findVersionAuthors(WORKFLOW_ID)).thenReturn(rows);

        // Act
        List<ResourceEditorDto> editors = service.listRecentEditors(WORKFLOW_ID);

        // Assert - the oldest editor is the one dropped.
        assertThat(editors).hasSize(WorkflowEditorsService.MAX_EDITORS);
        assertThat(editors).extracting(ResourceEditorDto::userId)
                .doesNotContain("user-" + (distinct - 1));
        assertThat(editors.get(0).userId()).isEqualTo("user-0");
    }

    @Test
    @DisplayName("counts a listed editor's OLDER saves, even those below the cap-th distinct person")
    void tallyCountsSavesBeyondTheCapthDistinctEditor() {
        // Arrange - the cap's worth of distinct editors, then one more person, and only THEN
        // a second save by the very first one. Walking the whole window is what finds it.
        List<WorkflowPlanVersionRepository.VersionAuthorProjection> rows = new ArrayList<>();
        int distinct = WorkflowEditorsService.MAX_EDITORS + 1;
        for (int i = 0; i < distinct; i++) {
            rows.add(author(100 - i, T0.minusSeconds(i * 60L), "user-" + i));
        }
        rows.add(author(100 - distinct, T0.minusSeconds(distinct * 60L), "user-0"));
        when(versionRepository.findVersionAuthors(WORKFLOW_ID)).thenReturn(rows);

        // Act
        List<ResourceEditorDto> editors = service.listRecentEditors(WORKFLOW_ID);

        // Assert - stopping the walk once the cap is full would report "1 edit" for someone
        // who made two, which is the list's own headline number. It must be 2.
        assertThat(editors.get(0).userId()).isEqualTo("user-0");
        assertThat(editors.get(0).editCount()).isEqualTo(2);
        // ...and their stamp is still their MOST RECENT save, not the one just found.
        assertThat(editors.get(0).editedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("lists at most five people, a number the popover's layout is built around")
    void theCapIsFive() {
        // Pinned as a literal on purpose: every other test reads the constant, so raising it
        // would keep them all green while the popover silently became a scrolling changelog.
        assertThat(WorkflowEditorsService.MAX_EDITORS).isEqualTo(5);
    }

    @Test
    @DisplayName("resolves every returned editor's name in ONE batch call")
    void resolvesNamesInASingleBatch() {
        // Arrange
        givenVersions(
                author(12, T0.plusSeconds(300), "7"),
                author(11, T0.plusSeconds(200), "9"));
        when(authClient.batchResolveUsers(Set.of("7", "9"))).thenReturn(Map.of(
                "7", new UserSummaryDto("7", "Ada", null),
                "9", new UserSummaryDto("9", "Grace", null)));

        // Act
        List<ResourceEditorDto> editors = service.listRecentEditors(WORKFLOW_ID);

        // Assert
        assertThat(editors).extracting(ResourceEditorDto::displayName).containsExactly("Ada", "Grace");
        verify(authClient, times(1)).batchResolveUsers(anySet());
    }

    @Test
    @DisplayName("an unresolvable name leaves the editor listed, with the id and no name")
    void unresolvedNameStillListsTheEditor() {
        // Arrange - auth knows nothing about this id (deleted account, or a failed lookup).
        givenVersions(author(12, T0.plusSeconds(300), "7"));
        when(authClient.batchResolveUsers(anySet())).thenReturn(Map.of());

        // Act
        List<ResourceEditorDto> editors = service.listRecentEditors(WORKFLOW_ID);

        // Assert - dropping the row would claim nobody edited it, which is a different fact.
        assertThat(editors).hasSize(1);
        assertThat(editors.get(0).userId()).isEqualTo("7");
        assertThat(editors.get(0).displayName()).isNull();
    }

    @Test
    @DisplayName("a failing name lookup degrades to ids rather than failing the whole list")
    void nameLookupFailureDoesNotFailTheList() {
        // Arrange
        givenVersions(author(12, T0.plusSeconds(300), "7"));
        when(authClient.batchResolveUsers(anySet())).thenThrow(new RuntimeException("auth down"));

        // Act
        List<ResourceEditorDto> editors = service.listRecentEditors(WORKFLOW_ID);

        // Assert - the frontend resolves names from the workspace roster anyway; losing this
        // fallback must not turn into "this workflow has no editors".
        assertThat(editors).hasSize(1);
        assertThat(editors.get(0).userId()).isEqualTo("7");
        assertThat(editors.get(0).displayName()).isNull();
    }

    @Test
    @DisplayName("reads the projection query, never the plan-carrying finder")
    void neverLoadsPlanBodies() {
        // Arrange
        givenVersions(author(1, T0, "7"));

        // Act
        service.listRecentEditors(WORKFLOW_ID);

        // Assert - the full finder deserializes one JSONB plan per row (see its javadoc); a
        // popover must never pay that, and this is the only place the choice is visible.
        verify(versionRepository, times(1)).findVersionAuthors(WORKFLOW_ID);
        verify(versionRepository, never()).findByWorkflowIdOrderByVersionDesc(any());
    }

    private void givenVersions(WorkflowPlanVersionRepository.VersionAuthorProjection... rows) {
        when(versionRepository.findVersionAuthors(eq(WORKFLOW_ID))).thenReturn(List.of(rows));
    }

    /**
     * One stored version's authorship, in query order (newest first).
     *
     * <p>`version` is taken and ignored on purpose: the rows are ordered by it in the real
     * query, so writing it at each call site keeps these fixtures readable as a history rather
     * than a bag of rows - even though the projection no longer carries the number.
     */
    private static WorkflowPlanVersionRepository.VersionAuthorProjection author(
            int version, Instant editedAt, String userId) {
        return new WorkflowPlanVersionRepository.VersionAuthorProjection() {
            @Override public Instant getEditedAt() { return editedAt; }
            @Override public String getUserId() { return userId; }
            @Override public String toString() { return "v" + version + " by " + userId; }
        };
    }
}
