package com.apimarketplace.agent.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guardrail for task lifecycle bulk updates.
 *
 * <p>The task service returns the task immediately after these JPQL updates. If
 * the persistence context is not cleared, Hibernate can return a stale managed
 * entity that was loaded before the bulk mutation, making the REST response say
 * {@code in_progress} while the database row is already {@code in_review}.</p>
 */
@DisplayName("AgentTaskRepository bulk lifecycle mutations")
class AgentTaskRepositoryBulkMutationContractTest {

    @Test
    @DisplayName("lifecycle JPQL updates clear and flush automatically before service re-reads the task")
    void lifecycleBulkUpdatesClearPersistenceContextBeforeReread() throws NoSuchMethodException {
        List<Method> methods = List.of(
            method("submitForReview", UUID.class, String.class, UUID.class, String.class),
            method("submitFailureForReview", UUID.class, String.class, UUID.class, String.class),
            method("claimIfAvailableByOrganizationId", UUID.class, String.class, UUID.class),
            // claimIfAvailableByTenantIdAndOrganizationIdIsNull removed post-V261 - the org-IsNull
            // branch no longer exists; claimIfAvailableByOrganizationId is the surviving entry point.
            method("promoteToInProgress", UUID.class, String.class, UUID.class),
            method("approveIfReviewer", UUID.class, String.class, UUID.class),
            method("approveIfReviewerExecution", UUID.class, String.class, UUID.class, UUID.class),
            method("approveByTenantOwner", UUID.class, String.class),
            method("rejectReviewIfReviewer", UUID.class, String.class, UUID.class, String.class),
            method("rejectReviewIfReviewerExecution", UUID.class, String.class, UUID.class, UUID.class, String.class),
            method("failReviewIfReviewer", UUID.class, String.class, UUID.class, String.class),
            method("failReviewIfReviewerExecution", UUID.class, String.class, UUID.class, UUID.class, String.class),
            method("rejectReviewByTenantOwner", UUID.class, String.class, String.class)
        );

        for (Method method : methods) {
            Modifying modifying = method.getAnnotation(Modifying.class);
            assertThat(modifying)
                .as("%s should be annotated with @Modifying", method.getName())
                .isNotNull();
            assertThat(modifying.flushAutomatically())
                .as("%s should flush before the bulk update", method.getName())
                .isTrue();
            assertThat(modifying.clearAutomatically())
                .as("%s should clear Hibernate L1 cache after the bulk update", method.getName())
                .isTrue();
        }
    }

    @Test
    @DisplayName("Tenant-owner review decisions are allowed even when a reviewer agent is configured")
    void tenantOwnerReviewMutationsDoNotRequireMissingReviewerAgent() throws NoSuchMethodException {
        List<Method> methods = List.of(
            method("approveByTenantOwner", UUID.class, String.class),
            method("rejectReviewByTenantOwner", UUID.class, String.class, String.class)
        );

        for (Method method : methods) {
            Query query = method.getAnnotation(Query.class);
            assertThat(query)
                .as("%s should declare the lifecycle mutation query", method.getName())
                .isNotNull();
            assertThat(query.value())
                .as("%s should let the task-board user resolve reviewer-agent tasks", method.getName())
                .doesNotContain("reviewerAgentId IS NULL")
                .contains("t.status = 'in_review'");
        }
    }

    @Test
    @DisplayName("Review mutations clear an active reviewer execution lock when leaving in_review")
    void reviewMutationsClearReviewerExecutionLockWhenLeavingReview() throws NoSuchMethodException {
        List<Method> methods = List.of(
            method("approveIfReviewer", UUID.class, String.class, UUID.class),
            method("approveByTenantOwner", UUID.class, String.class),
            method("rejectReviewIfReviewer", UUID.class, String.class, UUID.class, String.class),
            method("failReviewIfReviewer", UUID.class, String.class, UUID.class, String.class),
            method("rejectReviewByTenantOwner", UUID.class, String.class, String.class)
        );

        for (Method method : methods) {
            Query query = method.getAnnotation(Query.class);
            assertThat(query)
                .as("%s should declare the lifecycle mutation query", method.getName())
                .isNotNull();
            assertThat(query.value())
                .as("%s should release any active reviewer lock when review is resolved", method.getName())
                .contains("t.reviewerExecutionId = NULL");
        }
    }

    @Test
    @DisplayName("Reviewer execution lock can only be acquired while the task is still in_review")
    void reviewerExecutionLockIsScopedToReviewStatus() throws NoSuchMethodException {
        Method method = method("tryLockReviewerExecution", UUID.class, UUID.class);
        Query query = method.getAnnotation(Query.class);
        assertThat(query)
            .as("%s should declare the reviewer lock CAS query", method.getName())
            .isNotNull();
        assertThat(query.value())
            .as("%s should not recreate reviewer locks after a human decision leaves in_review", method.getName())
            .contains("status = 'in_review'")
            .contains("reviewer_agent_id IS NOT NULL");
    }

    @Test
    @DisplayName("New review submissions reset reviewer execution token for the next review cycle")
    void submitForReviewMutationsResetReviewerExecutionToken() throws NoSuchMethodException {
        List<Method> methods = List.of(
            method("submitForReview", UUID.class, String.class, UUID.class, String.class),
            method("submitForReviewByOrganizationIdStrict", UUID.class, String.class, String.class,
                UUID.class, String.class),
            method("submitFailureForReview", UUID.class, String.class, UUID.class, String.class),
            method("submitFailureForReviewByOrganizationIdStrict", UUID.class, String.class, String.class,
                UUID.class, String.class)
        );

        for (Method method : methods) {
            Query query = method.getAnnotation(Query.class);
            assertThat(query)
                .as("%s should declare the review submission query", method.getName())
                .isNotNull();
            assertThat(query.value())
                .as("%s should start every review cycle without a stale reviewer execution token",
                    method.getName())
                .contains("t.reviewerExecutionId = NULL");
        }
    }

    @Test
    @DisplayName("Execution-scoped reviewer mutations require the current reviewer execution token")
    void executionScopedReviewerMutationsRequireReviewerExecutionToken() throws NoSuchMethodException {
        List<Method> methods = List.of(
            method("approveIfReviewerExecution", UUID.class, String.class, UUID.class, UUID.class),
            method("rejectReviewIfReviewerExecution", UUID.class, String.class, UUID.class, UUID.class, String.class),
            method("failReviewIfReviewerExecution", UUID.class, String.class, UUID.class, UUID.class, String.class),
            method("incrementReviewAttemptCountForExecution", UUID.class, String.class, UUID.class, UUID.class)
        );

        for (Method method : methods) {
            Query query = method.getAnnotation(Query.class);
            assertThat(query)
                .as("%s should declare an execution-scoped mutation query", method.getName())
                .isNotNull();
            assertThat(query.value())
                .as("%s should reject stale reviewer executions from older review cycles", method.getName())
                .satisfiesAnyOf(
                    value -> assertThat(value).contains("reviewerExecutionId"),
                    value -> assertThat(value).contains("reviewer_execution_id")
                );
        }
    }

    @Test
    @DisplayName("Legacy reviewer mutations do not override an active reviewer execution lock")
    void legacyReviewerMutationsRequireNoActiveReviewerExecutionLock() throws NoSuchMethodException {
        List<Method> methods = List.of(
            method("approveIfReviewer", UUID.class, String.class, UUID.class),
            method("rejectReviewIfReviewer", UUID.class, String.class, UUID.class, String.class),
            method("failReviewIfReviewer", UUID.class, String.class, UUID.class, String.class),
            method("incrementReviewAttemptCount", UUID.class, String.class, UUID.class)
        );

        for (Method method : methods) {
            Query query = method.getAnnotation(Query.class);
            assertThat(query)
                .as("%s should declare the legacy reviewer mutation query", method.getName())
                .isNotNull();
            assertThat(query.value())
                .as("%s should reject no-token reviewer calls while a review execution lock is active",
                    method.getName())
                .satisfiesAnyOf(
                    value -> assertThat(value).contains("reviewerExecutionId IS NULL"),
                    value -> assertThat(value).contains("reviewer_execution_id IS NULL")
                );
        }
    }

    private static Method method(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        return AgentTaskRepository.class.getMethod(methodName, parameterTypes);
    }
}
