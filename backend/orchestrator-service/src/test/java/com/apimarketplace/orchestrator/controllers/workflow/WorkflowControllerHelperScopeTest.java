package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Strict-isolation regression tests for
 * {@link WorkflowControllerHelper#isRunInScope}, aligned 2026-05-18 with
 * {@code com.apimarketplace.common.scope.ScopeGuard}.
 *
 * <p>Pre-alignment, the predicate accepted ANY of:
 * <ol>
 *   <li>{@code callerUserId == run.tenantId} regardless of caller's active
 *       workspace, OR</li>
 *   <li>{@code callerOrgId == run.organizationId}.</li>
 * </ol>
 * That let a caller currently in OrgA workspace touch their personal runs
 * (and the symmetric case). The new contract is strict per
 * {@code ConversationQueryService#getConversationById}.
 */
@DisplayName("WorkflowControllerHelper.isRunInScope - strict-isolation contract")
class WorkflowControllerHelperScopeTest {

    private static final String USER_A = "user-A";
    private static final String USER_B = "user-B";
    private static final String ORG_A = "org-A";
    private static final String ORG_B = "org-B";

    private WorkflowRunEntity run(String tenantId, String orgId) {
        WorkflowRunEntity run = Mockito.mock(WorkflowRunEntity.class);
        Mockito.when(run.getTenantId()).thenReturn(tenantId);
        Mockito.when(run.getOrganizationId()).thenReturn(orgId);
        return run;
    }

    @Test
    @DisplayName("returns false when run is null (no NPE)")
    void nullRunReturnsFalse() {
        assertThat(WorkflowControllerHelper.isRunInScope(null, USER_A, ORG_A)).isFalse();
    }

    @Nested
    @DisplayName("caller in org workspace")
    class CallerInOrgWorkspace {

        @Test
        @DisplayName("matches a run tagged with the same org regardless of owner (team-shared)")
        void matchesSameOrgRegardlessOfOwner() {
            assertThat(WorkflowControllerHelper.isRunInScope(run(USER_B, ORG_A), USER_A, ORG_A))
                    .isTrue();
        }

        @Test
        @DisplayName("rejects a run tagged with a different org")
        void rejectsDifferentOrg() {
            assertThat(WorkflowControllerHelper.isRunInScope(run(USER_A, ORG_B), USER_A, ORG_A))
                    .isFalse();
        }

        @Test
        @DisplayName("rejects a personal run owned by caller - the bug fix")
        void rejectsPersonalRunWhenCallerInOrg() {
            // Pre-fix, the predicate matched on userId == tenantId regardless
            // of caller's active org. Strict isolation MUST reject.
            assertThat(WorkflowControllerHelper.isRunInScope(run(USER_A, null), USER_A, ORG_A))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("caller in personal workspace")
    class CallerInPersonalWorkspace {

        @Test
        @DisplayName("matches a personal run owned by caller")
        void matchesOwnPersonalRun() {
            assertThat(WorkflowControllerHelper.isRunInScope(run(USER_A, null), USER_A, null))
                    .isTrue();
        }

        @Test
        @DisplayName("rejects a personal run owned by another user")
        void rejectsOtherUserPersonalRun() {
            assertThat(WorkflowControllerHelper.isRunInScope(run(USER_B, null), USER_A, null))
                    .isFalse();
        }

        @Test
        @DisplayName("rejects an org-tagged run even when owned by caller - symmetric guard")
        void rejectsOrgTaggedRunWhenCallerInPersonal() {
            // Symmetric to the bug fix: caller in personal MUST NOT see org rows,
            // even if they created them. They'll see them when they switch to
            // that org's workspace.
            assertThat(WorkflowControllerHelper.isRunInScope(run(USER_A, ORG_A), USER_A, null))
                    .isFalse();
        }

        @Test
        @DisplayName("treats blank orgId (whitespace) as personal scope")
        void blankOrgIdMeansPersonal() {
            assertThat(WorkflowControllerHelper.isRunInScope(run(USER_A, null), USER_A, "   "))
                    .isTrue();
            assertThat(WorkflowControllerHelper.isRunInScope(run(USER_A, ORG_A), USER_A, "   "))
                    .isFalse();
        }

        @Test
        @DisplayName("treats empty-string orgId as personal scope (symmetric to whitespace)")
        void emptyOrgIdMeansPersonal() {
            // Lock the isBlank() contract for both whitespace and empty-string.
            assertThat(WorkflowControllerHelper.isRunInScope(run(USER_A, null), USER_A, ""))
                    .isTrue();
            assertThat(WorkflowControllerHelper.isRunInScope(run(USER_A, ORG_A), USER_A, ""))
                    .isFalse();
        }
    }

    @Test
    @DisplayName("rejects anonymous caller (no userId, no orgId)")
    void anonymousCallerRejected() {
        assertThat(WorkflowControllerHelper.isRunInScope(run(USER_A, null), null, null)).isFalse();
        assertThat(WorkflowControllerHelper.isRunInScope(run(USER_A, ORG_A), null, null)).isFalse();
    }
}
