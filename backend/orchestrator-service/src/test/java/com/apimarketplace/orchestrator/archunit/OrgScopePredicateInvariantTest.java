package com.apimarketplace.orchestrator.archunit;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.scope.TolerantScope;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces the org-scope predicate contract introduced by
 * {@link ScopeGuard} on 2026-05-18.
 *
 * <p><b>Rule 1 - hand-rolled scope predicates are banned.</b> Any method
 * under {@code *.controllers.*} or {@code *.services.*} or {@code *.tools.*}
 * that calls BOTH a scope-tenant getter ({@code getTenantId} or
 * {@code getUserId}) AND {@code getOrganizationId} on entity references must
 * also call {@link ScopeGuard#isInStrictScope} OR
 * {@link ScopeGuard#isInOwnerOrOrgScope}. Hand-rolling
 * {@code ownerMatch || orgMatch} is the bug shape this guard prevents.
 *
 * <p><b>Rule 2 - every tolerant call site is documented.</b> Any method
 * calling {@link ScopeGuard#isInOwnerOrOrgScope} MUST also be annotated
 * with {@link TolerantScope} (or be inside a type annotated with it). The
 * annotation carries a {@code reason} string visible to future readers.
 *
 * <p><b>Scope of this test.</b> ArchUnit scans the orchestrator-service
 * production classpath (the largest historical surface of lax predicates).
 * Each other service can mirror this rule by adding a copy of this class to
 * its own test source set - the rule logic is fully shareable. Per-service
 * placement is deliberate: ArchUnit cannot scan classes that are not on its
 * test classpath, and {@code common-lib} does not depend on the services.
 */
@DisplayName("ScopeGuard callsite invariants (Rule 1: no hand-rolled predicate; Rule 2: @TolerantScope required)")
class OrgScopePredicateInvariantTest {

    /**
     * Allow-list of {@code Class#method} entries that legitimately read
     * BOTH scope getters but do NOT implement a scope predicate (the
     * {@code userId.equals(x) || orgId.equals(y)} bug shape). Categories:
     * <ul>
     *   <li><b>Stamping / persistence</b> - sets one getter onto an entity
     *       while reading the other for breakdown tracking or claim
     *       indexing (no comparison).</li>
     *   <li><b>Cross-resource</b> - compares parent-workflow scope to
     *       child-datasource scope across two different entities (not the
     *       single-entity predicate the rule guards).</li>
     *   <li><b>Multi-purpose flows</b> - strict-tenant ownership check
     *       FIRST, then reads {@code getOrganizationId} as an argument to
     *       {@link com.apimarketplace.auth.client.access.OrgAccessGuard}
     *       (no boolean OR of the two equals).</li>
     * </ul>
     *
     * <p>Format: {@code SimpleClassName#methodName}. New entries MUST come
     * with a code review comment justifying the category - adding a row
     * just to silence the rule re-introduces the bug shape.
     */
    private static final List<String> RULE_1_ALLOWLIST = List.of(
            // Multi-purpose: strict-scope / strict-tenant ownership FIRST, then orgId read for OrgAccessGuard arg.
            "WorkflowManagementService#deleteWorkflow",
            "WorkflowManagementService#saveWorkflow",
            "WorkflowManagementService#updateWorkflowStatus",

            // Stamping / persistence - reads one getter, sets it onto another entity.
            "RunCloneService#cloneStorageEntries",
            "WorkflowRunPersistenceService#buildRunEntity",
            "ScheduleSyncService#syncSingleSchedule",
            "SignalResumeService#onSignalResolved",
            "SignalResumeService#persistSignalResolutionOutput",
            "WorkflowRunStatusService#persistSnapshot",
            "RunCloneService#cloneStepData",
            "InternalNotificationController#emit",
            "StateReconstructorHelper#loadStepOutput",
            // Pin-time public-endpoint auto-create (69234c596): reads the workflow's
            // tenant + org only to CREATE a standalone form/chat endpoint (passed as
            // createFormEndpoint/createChatEndpoint args + logged) - no owner-vs-org
            // comparison, so the documented false-positive remedy is an allow-list entry.
            "PinAwareTriggerSyncService#ensureFormEndpointForWorkflow",
            "PinAwareTriggerSyncService#ensureChatEndpointForWorkflow",
            // V291 Redis execution queue: factory snapshots the run's tenant + org + role
            // into the queued message for later rehydration. Pure copy, no comparison.
            "QueuedExecutionMessage#fromRun",

            // Runtime dispatch: reads run scope to re-bind worker ThreadLocals,
            // label metrics, and enforce production-run metadata; upstream callers
            // have already selected an authorized run.
            "ExecutionQueueService#enqueueAsync",
            "ExecutionQueueService#dispatchLoop",
            "ReusableTriggerService#executeTriggerInternal",

            // Publication read paths - internal, gated upstream by publication-service.
            "InternalPublicationSupportController#findAllBySourcePublication",
            "InternalPublicationSupportController#findBySourcePublication",
            "InternalPublicationSupportController#getAcquiredWorkflows"
            // 2026-05-18 Phase A - the 9 dispatch entries removed: every dispatch
            // service now routes through ScopeGuard.crossResourceMatches so Rule 1
            // sees a ScopeGuard.* call and admits the method without an allow-list
            // entry. Single source of truth for the cross-resource workspace match.
    );

    private final JavaClasses orchestratorClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("com.apimarketplace.orchestrator");

    @Test
    @DisplayName("Rule 1 - methods touching both scope getters must call ScopeGuard.*")
    void rule1NoHandRolledScopePredicate() {
        List<String> offenders = orchestratorClasses.stream()
                .filter(this::isCandidateClass)
                .flatMap(c -> c.getMethods().stream())
                .filter(this::touchesBothScopeGetters)
                .filter(m -> !callsScopeGuard(m))
                .filter(m -> !RULE_1_ALLOWLIST.contains(describe(m)))
                .map(this::describe)
                .sorted()
                .toList();

        assertThat(offenders)
                .as("Methods that touch both getTenantId()/getUserId() AND getOrganizationId() "
                        + "without calling ScopeGuard.* - hand-rolled scope predicate detected. "
                        + "Replace with ScopeGuard.isInStrictScope(callerUserId, callerOrgId, "
                        + "entity.getTenantId(), entity.getOrganizationId()) or, for documented "
                        + "internal channels, ScopeGuard.isInOwnerOrOrgScope + @TolerantScope.")
                .isEmpty();
    }

    @Test
    @DisplayName("Rule 2 - every isInOwnerOrOrgScope call site is @TolerantScope-annotated")
    void rule2TolerantCallSitesMustBeAnnotated() {
        List<String> offenders = orchestratorClasses.stream()
                .flatMap(c -> c.getMethods().stream())
                .filter(this::callsTolerantHelper)
                .filter(m -> !hasTolerantScopeAnnotation(m))
                .map(this::describe)
                .sorted()
                .toList();

        assertThat(offenders)
                .as("Methods calling ScopeGuard.isInOwnerOrOrgScope without @TolerantScope. "
                        + "Tolerance MUST be documented with a reason - annotate the method "
                        + "(or enclosing type) with @TolerantScope(reason=\"...\").")
                .isEmpty();
    }

    // ============== helpers ==============

    private boolean isCandidateClass(JavaClass c) {
        String pkg = c.getPackageName();
        return pkg.contains(".controllers.")
                || pkg.contains(".services") // .services and .services.*
                || pkg.contains(".tools")    // .tools and .tools.*
                || pkg.contains(".schedule")
                || pkg.contains(".trigger")
                || pkg.contains(".webhook");   // 2026-05-18 audit: webhook dispatch services
    }

    private boolean touchesBothScopeGetters(JavaMethod m) {
        boolean hasTenantGetter = m.getMethodCallsFromSelf().stream().anyMatch(call -> {
            String name = call.getTarget().getName();
            return name.equals("getTenantId") || name.equals("getUserId");
        });
        boolean hasOrgGetter = m.getMethodCallsFromSelf().stream()
                .anyMatch(call -> call.getTarget().getName().equals("getOrganizationId"));
        return hasTenantGetter && hasOrgGetter;
    }

    private boolean callsScopeGuard(JavaMethod m) {
        return m.getMethodCallsFromSelf().stream().anyMatch(call ->
                call.getTarget().getOwner().getFullName().equals(ScopeGuard.class.getName()));
    }

    private boolean callsTolerantHelper(JavaMethod m) {
        return m.getMethodCallsFromSelf().stream().anyMatch(call ->
                call.getTarget().getOwner().getFullName().equals(ScopeGuard.class.getName())
                        && call.getTarget().getName().equals("isInOwnerOrOrgScope"));
    }

    private boolean hasTolerantScopeAnnotation(JavaMethod m) {
        return m.isAnnotatedWith(TolerantScope.class)
                || m.getOwner().isAnnotatedWith(TolerantScope.class);
    }

    private String describe(JavaMethod m) {
        return m.getOwner().getSimpleName() + "#" + m.getName();
    }
}
