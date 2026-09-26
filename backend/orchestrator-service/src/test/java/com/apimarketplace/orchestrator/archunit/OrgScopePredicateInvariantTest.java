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
            // Execution-log retention sweep (2026-09-02, retention keyed by workspace):
            // sweep() and sweepScope() read the (organization_id, tenant_id) PAIR of
            // each candidate scope only to ROUTE two different lookups: the retention
            // window is asked of auth-service by organization id (the workspace
            // owner's plan), and the payload purge is issued by tenant id (whose
            // storage quota the payloads were booked to). There is no owner-vs-org
            // comparison and no branch on either value: both are arguments. The rows
            // themselves are selected by the repository's own scope predicate
            // (organization_id = :organizationId AND tenant_id = :tenantId), and the
            // job deletes journal, it never grants access. Same category as the
            // "stamping / routing" entries above.
            "ExecutionLogRetentionSweeper#sweep",
            "ExecutionLogRetentionSweeper#sweepScope",
            // Multi-purpose: strict-scope / strict-tenant ownership FIRST, then orgId read for OrgAccessGuard arg.
            "WorkflowManagementService#deleteWorkflow",
            "WorkflowManagementService#saveWorkflow",
            "WorkflowManagementService#updateWorkflowStatus",

            // Stamping / persistence - reads one getter, sets it onto another entity.
            "RunCloneService#cloneStorageEntries",
            "WorkflowRunPersistenceService#buildRunEntity",
            "ScheduleSyncService#syncSingleSchedule",
            // Scheduled agent fire: reads the schedule's tenant + org only to PASS them
            // to conversation-service, so the conversation it finds or creates and the
            // credits it consumes land in the schedule's workspace instead of the
            // owner's personal one (the reason the org was threaded here in the first
            // place, audit 2026-05-17 round-5). There is no predicate at all in this
            // method, no owner-vs-org comparison and no branch on either value: both are
            // arguments. Surfaced by bcb970694 extracting this block into its own method,
            // which is the granularity Rule 1 keys on, not by any change to the scoping.
            "ScheduleExecutorService#runAgentAfterAdvance",
            // Answering an agent's permission request from a linked chat: reads the
            // request row's tenant + org only to PASS them to conversation-service, so
            // the endpoint that releases the parked call sees the same workspace the
            // agent was running in. No owner-vs-org comparison and no branch on either
            // value: both are arguments. The row itself was selected by its unguessable
            // callback token, and the authority to release is the conversation
            // endpoint's own check, not anything decided here.
            "AgentAuthorizationAnswerApplier#apply",
            // Expiring an unanswered request: reads the row's org to re-bind the
            // workspace scope for the catalog call (this runs on a scheduler thread with
            // no request context) and its tenant to address that same call. Routing, not
            // a predicate - and the pass deletes nothing and grants nothing.
            "ChatAuthorizationExpiryScheduler#closeMessage",
            // Closing a decided request's message: identical shape to the entry above,
            // on the webhook thread instead of the scheduler one. The org re-binds the
            // workspace scope, the tenant addresses the call, neither is compared, and
            // the decision it reflects was already applied and authorized elsewhere.
            "AgentAuthorizationChannelService#closeUnderOrgScope",
            // Closing an EXPIRED request's message: same shape again, reached from the three
            // paths that write EXPIRED (the sweep, the overdue retire on delivery, the failed
            // hand-back on answer). The channel comes from the ROW, so the org re-binds the
            // workspace scope for the connector's catalog call and the tenant addresses it;
            // neither is compared, and nothing is granted. It arrived after the three entries
            // above because it was extracted later, when the retire path was found to be
            // leaving live buttons under an expired request.
            "AgentAuthorizationChannelService#closeExpired",
            // The question half of the same feature, and the same five shapes as the entries
            // above it. Each reads a request row's tenant and org to ROUTE something, never to
            // decide anything: nothing is compared, nothing branches on either value, and no
            // access is granted here.
            //
            // #apply and #startFollowUpTurn hand a recorded answer to the conversation that
            // asked for it, passing both values as arguments so conversation-service sees the
            // workspace the agent was running in. That endpoint applies its own scope to the
            // headers it receives, the same posture the approval applier documents.
            //
            // #closeAnswered and #toggle re-bind the workspace scope for a catalog call on a
            // thread with no request context (a webhook, a scheduler), and address it with the
            // tenant. #acknowledge does the same for the button press receipt.
            //
            // The row each of them starts from was selected by its unguessable callback token
            // or by the message it replies to, which is the capability; the authority to answer
            // is the per-chat allow-list, checked in ChatQuestionService#isAllowed.
            "ChatQuestionAnswerApplier#apply",
            "ChatQuestionAnswerApplier#startFollowUpTurn",
            // #closeWith took over from #closeAnswered when the expiry path needed the same
            // edit with a different line; #sayBack is the message a typed reply gets when it
            // could not be recorded, which a button press gets through its own acknowledgement.
            // Both are the same shape as every entry above: the org re-binds the workspace scope
            // for a catalog call on a thread with no request context, the tenant addresses it,
            // neither is compared and nothing is granted.
            "ChatQuestionService#closeWith",
            "TelegramQuestionCallbackHandler#sayBack",
            "ChatQuestionService#toggle",
            "TelegramQuestionCallbackHandler#acknowledge",
            // Same shape for every other chat provider: copies the request row's tenant, org
            // and credential into the router's Result so the provider controller can
            // acknowledge with the credential the message went out with. Pure copy, no
            // owner-vs-org comparison; who may answer was decided by the services before it.
            "ChannelInboundRouter#fromRow",
            // Product analytics (channel_request_answered): the settled request row's tenant and
            // org are copied into the event as distinct_id and organization group. Pure copy, no
            // owner-vs-org comparison; who may answer was decided earlier in the same method.
            "AgentAuthorizationChannelService#answer",
            "ChatQuestionService#applyIfComplete",
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
            // Datasource-subscription sync: reads the workflow's tenant + org only to
            // copy them into the DatasourceSubscriptionRequest sent to trigger-service
            // (subscription rows carry owner scope for later event dispatch). Pure
            // copy, no owner-vs-org comparison - same category as the endpoint
            // auto-create entries above.
            "DatasourceSubscriptionSyncService#syncFromPlan",
            // V291 Redis execution queue: factory snapshots the run's tenant + org + role
            // into the queued message for later rehydration. Pure copy, no comparison.
            "QueuedExecutionMessage#fromRun",
            // 2026-07-31, surfaced by widening isCandidateClass to `.execution.`: the
            // engine copies the tree's tenant + org into the ExecutionContext and re-binds
            // the org scope on the ForkJoinPool worker. Pure propagation of the run's own
            // scope, no owner-vs-org comparison - same category as the entries above.
            "UnifiedExecutionEngine#executeItem",
            // Share-link chat send (1aa452f7f): reads the endpoint's org only to key the
            // per-share invocation rate limiter, and its tenant only to persist messages
            // and set X-User-ID. No owner-vs-org comparison happens here - the actual
            // workspace match for this path is ScopeGuard.crossResourceMatches in
            // ChatDispatchService#dispatchToWorkflow. Same documented false-positive
            // category as the endpoint auto-create entries above.
            "ChatDispatchService#sendMessage",
            // Same class, same shape, same category: both hand the endpoint's tenant and org
            // to endpointScopedHeaders(), which PROPAGATES that scope downstream as X-User-ID
            // and X-Organization-ID on a call to conversation-service. Nothing is compared and
            // nothing is decided here; the workspace match for this path is
            // ScopeGuard.crossResourceMatches in #dispatchToWorkflow, and conversation-service
            // applies its own scope to the headers it receives.
            //
            // They were introduced by 08c1d98030 (2026-09-19) and the gate did not catch them
            // then for a reason worth writing down: Maven is fail-fast per module, and
            // publication-service was failing Rule 1 earlier in the same job, so the build
            // never reached orchestrator-service. Fixing that one is what revealed these two.
            "ChatDispatchService#createConversation",
            "ChatDispatchService#getHistory",

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
                || pkg.contains(".webhook")    // 2026-05-18 audit: webhook dispatch services
                // 2026-07-31: execution nodes reach other tenants' rows too. SubWorkflowNode
                // resolves its target workflowId through the template adapter, so it is a
                // cross-workspace dispatch surface exactly like the trigger services.
                || pkg.contains(".execution.");
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
