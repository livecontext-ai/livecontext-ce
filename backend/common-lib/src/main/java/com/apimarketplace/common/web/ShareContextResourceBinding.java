package com.apimarketplace.common.web;

/**
 * CASA LC-037: binds a share-token (owner-impersonation) request to the ONE shared resource its
 * publication actually covers, for handlers the gateway/monolith filter can only allow-list BY
 * PATH SHAPE (it has no idea which {@code interfaceId}/{@code fileId} belongs to which workflow).
 *
 * <p>The gateway resolves an APPLICATION share token to the OWNER's real identity so the shared
 * viewer can reach the owner's authenticated endpoints - a plain strict-scope (tenant/org) check
 * therefore authorizes the visitor against the OWNER'S ENTIRE WORKSPACE, not just the one shared
 * publication. The orchestrator's {@code WorkflowControllerHelper.shareContextPermitsRun} already
 * closes this for run reads (binds to {@code run.publicationId}); this is the same idea for a
 * resource that carries its OWNING WORKFLOW id directly on the row (e.g.
 * {@code storage.storage.workflow_id}) instead of a run's publication id - a share-token request
 * for it must name that same workflow via {@code X-Share-Resource-Id} (see
 * {@code WorkflowExecutionController.validateSharedApplicationExecutionScope}, which establishes
 * that for an APPLICATION share this header always carries the shared WORKFLOW's own uuid, not a
 * publication id).
 *
 * <p><b>Sub-workflow files are covered too, one layer up from this class.</b> A file produced
 * inside a {@code sub_workflow} child run is stamped with the CHILD sub-workflow's own id, not
 * the parent shared workflow's, so the direct equality {@link #permitsWorkflowResource} performs
 * here still fails for it (by design - this class stays a pure two-id comparator). The caller
 * (storage-service's {@code FileController}/{@code MonolithFileController}) falls back to a
 * lineage check when the direct match fails: {@code OrchestratorSubWorkflowLineageClient} calls
 * orchestrator's {@code GET /api/internal/workflows/{workflowId}/sub-workflow-runs/{childRunId}
 * /descendant}, which confirms the child run was genuinely invoked as a {@code core:sub_workflow}
 * step of SOME run of the shared parent workflow (via {@code StorageService
 * #existsSubWorkflowInvocation}, matching the sub_workflow node's own persisted step output). Same
 * fail-closed rule as everywhere else here: no evidence, no lookup, or a lookup failure all deny.
 *
 * <p><b>Round-3 audit - full coverage table for the gateway/CE {@code SHARE_APPLICATION_GET_ALLOW}
 * allow-list</b> (byte-identical regex in {@code AuthenticationFilter} and
 * {@code MonolithSecurityFilter}; re-enumerated end to end so nothing added to either regex in the
 * future silently skips this table). "Bound" means a share-context request naming a resource
 * outside the one shared publication/workflow now 404s; "public" means the endpoint has no
 * per-caller auth at all (share or not) so owner-impersonation grants nothing beyond what any
 * anonymous caller already sees.
 *
 * <table border="1">
 * <caption>Allow-list pattern -&gt; handler -&gt; binding status</caption>
 * <tr><th>Pattern branch</th><th>Handler</th><th>Status</th></tr>
 * <tr><td>{@code publications/acquired}</td><td>{@code WorkflowPublicationController
 *     #getAcquiredApplications}</td><td>Intentionally unbound - the visitor's own acquired-apps
 *     library has no single publication to bind to by nature (it is inherently a cross-publication
 *     list); documented as an accepted, low-sensitivity owner-wide read alongside the
 *     {@code /api/agents} / {@code /api/credentials/all} exclusions in {@code
 *     AuthenticationFilter#isAllowedShareTokenApplicationGet}'s javadoc.</td></tr>
 * <tr><td>{@code publications/{uuid}}</td><td>{@code WorkflowPublicationController
 *     #getPublicationById}</td><td>Bound - {@code isShareTokenForPublication} (publicationId ==
 *     {@code X-Share-Resource-Token}), a token-equality mechanism distinct from this class since
 *     the resource IS the publication, not something stamped with a workflow id.</td></tr>
 * <tr><td>{@code publications/{uuid}/application-workflow}</td><td>same controller,
 *     {@code #getApplicationWorkflow}</td><td>Bound - same {@code isShareTokenForPublication}
 *     check.</td></tr>
 * <tr><td>{@code publications/{uuid}/reviews}, {@code /reviews/comments-count},
 *     {@code /reviews/{uuid}/replies}</td><td>same controller</td><td>Public - zero auth headers
 *     read at all (not even {@code X-User-ID}); reviews are marketplace-public data for ANY
 *     caller, share token or not. No binding needed.</td></tr>
 * <tr><td>{@code publications/{uuid}/reviews/mine}</td><td>same controller,
 *     {@code #getMyReview}</td><td>Bound (round-3 fix) - unlike its siblings above this answers
 *     "what did {@code X-User-ID} write", and a share token resolves that to the OWNER's identity;
 *     without a gate a share holder could pass ANY marketplace {@code publicationId} and learn the
 *     owner's review on it. Now gated with the same {@code isShareTokenForPublication}
 *     check.</td></tr>
 * <tr><td>{@code workflows/{uuid}}</td><td>{@code WorkflowListController#getWorkflow} (the
 *     actual handler at this bare path - NOT the identically-named
 *     {@code WorkflowCrudController#getWorkflow}, which lives under {@code /v2/workflows/dag} and
 *     is not matched by this branch)</td><td>Bound (round-3 fix) - was ScopeGuard-only; a share
 *     token could read ANY workflow the owner ever built. Now gated with
 *     {@link #permitsWorkflowResource} before the scope check.</td></tr>
 * <tr><td>{@code workflows/{uuid}/runs/application}, {@code /runs/pinned}</td><td>{@code
 *     WorkflowRunQueryController}</td><td>Bound - resolves a RUN, not the workflow row directly,
 *     and goes through {@code isRunInScope}/{@code shareContextPermitsRun} (binds to
 *     {@code run.publicationId == X-Share-Resource-Token}).</td></tr>
 * <tr><td>{@code v2/workflows/dag/{uuid}/versions}</td><td>{@code
 *     WorkflowVersionController#listVersions}</td><td>Bound (round-3 fix) - same gap as the bare
 *     workflow GET; a share token could list the version history of ANY owner workflow. Now
 *     gated with {@link #permitsWorkflowResource} before {@code canReadHistory}. (The
 *     {@code createdBy} field was already withheld in share context, pre-dating this fix - see
 *     {@code WorkflowControllerHelper#isShareContext} usage there.)</td></tr>
 * <tr><td>{@code v2/workflows/dag/runs/{runId}/state}</td><td>{@code
 *     WorkflowRunController#getRunState}</td><td>Bound - {@code
 *     WorkflowControllerHelper#isRunInScope} composes {@code shareContextPermitsRun}.</td></tr>
 * <tr><td>{@code v2/workflows/dag/runs/{runId}/signals}</td><td>{@code
 *     WorkflowSignalController}</td><td>Bound - same {@code isRunInScope} gate.</td></tr>
 * <tr><td>{@code interfaces/{uuid}}</td><td>interface-service's {@code InterfaceController
 *     #getInterface}</td><td>Bound (LC-037 gap 1) - {@link #permitsWorkflowResource} via
 *     {@code OrchestratorInterfaceMembershipClient} (a membership lookup, since an interface has
 *     no {@code workflow_id} column - re-derived from the workflow's plan).</td></tr>
 * <tr><td>{@code interfaces/{uuid}/render}</td><td>orchestrator's {@code InterfaceController
 *     #renderInterface}</td><td>Bound - {@code InterfaceRenderService#isInterfaceReferencedByRun}
 *     (membership re-derived from the RUN's own frozen plan, not the live workflow plan - see that
 *     method's javadoc for the round-3 fix).</td></tr>
 * <tr><td>{@code files/by-id/{id}/raw}</td><td>storage-service's {@code FileController}/
 *     {@code MonolithFileController}</td><td>Bound (LC-037 gap 2/3) - {@link
 *     #permitsWorkflowResource} directly against {@code StorageEntity.workflowId}, falling back to
 *     the sub-workflow lineage check documented above.</td></tr>
 * <tr><td>{@code users/{id}/avatar}</td><td>auth-service's {@code UserController
 *     #getAvatar}</td><td>Public - no auth check at all for any caller (profile pictures are
 *     public by design; the endpoint documents it "NEVER returns 404"). No binding
 *     possible or needed.</td></tr>
 * </table>
 */
public final class ShareContextResourceBinding {

    public static final String SHARE_CONTEXT_HEADER = "X-Share-Context";
    public static final String SHARE_RESOURCE_TYPE_HEADER = "X-Share-Resource-Type";
    public static final String SHARE_RESOURCE_ID_HEADER = "X-Share-Resource-Id";

    private ShareContextResourceBinding() {
    }

    /**
     * @param shareContext        {@code X-Share-Context} as received (null/blank outside a share
     *                            request)
     * @param shareResourceType   {@code X-Share-Resource-Type} as received
     * @param shareResourceId     {@code X-Share-Resource-Id} as received - for an APPLICATION
     *                            share, the shared workflow's own id
     * @param resourceWorkflowId  the workflow id THIS resource (file, interface, ...) is stamped
     *                            with; null/blank if the row carries no workflow association
     * @return true when the request may proceed: either it is not a share-token request at all
     *         (unchanged, non-share callers keep their own auth), or it is an APPLICATION share
     *         whose {@code X-Share-Resource-Id} equals the resource's own workflow id. Anything
     *         else - a non-APPLICATION share (owner-impersonation is illegitimate there to begin
     *         with), a resource with no workflow id to check, or a workflow id that does not
     *         match - is denied.
     */
    public static boolean permitsWorkflowResource(String shareContext, String shareResourceType,
                                                   String shareResourceId, String resourceWorkflowId) {
        if (!"true".equalsIgnoreCase(shareContext)) {
            return true;
        }
        if (!"APPLICATION".equalsIgnoreCase(shareResourceType)) {
            return false;
        }
        if (resourceWorkflowId == null || resourceWorkflowId.isBlank()
                || shareResourceId == null || shareResourceId.isBlank()) {
            return false;
        }
        return resourceWorkflowId.equals(shareResourceId);
    }
}
