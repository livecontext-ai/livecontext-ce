package com.apimarketplace.common.storage.service;

import java.util.regex.Pattern;

/**
 * Decides whether a file's {@code workflow_id} / {@code run_id} name a real workflow run, and so
 * whether the file may be filed under one.
 *
 * <p><b>Why it matters.</b> The Files browser shows a workflow's files inside a virtual folder named
 * after the workflow, and drops a folder whose workflow it cannot find (that is how files of a
 * deleted workflow disappear). A file stamped with a workflow id that never existed is therefore
 * invisible to the user, while the agent's {@code files} tool, which lists rows without that
 * lookup, still sees it and tells the user the file is in their Files. Two producers did that in
 * production: an ad-hoc node execution (a single node run from chat, whose synthetic plan carries a
 * random UUID and whose run id starts with {@link #AD_HOC_RUN_ID_PREFIX}) and the file tools called
 * outside any workflow (which stamped the literal {@code "unknown"}). Such a file belongs at the
 * root of the Files browser, like any other upload that no workflow produced.
 */
public final class WorkflowFileScope {

    private WorkflowFileScope() {
    }

    /**
     * Run-id prefix of an ad-hoc node execution. It is never a real run, so a file it produced
     * belongs to no workflow even though its synthetic plan has a well-formed UUID.
     */
    public static final String AD_HOC_RUN_ID_PREFIX = "adhoc-";

    /**
     * Whether a file carrying these coordinates may be filed under a workflow.
     *
     * @param workflowId the producer's workflow id; must be a UUID to name a workflow
     * @param runId      the producer's run id, may be null; an ad-hoc run id disqualifies
     */
    public static boolean isWorkflowScoped(String workflowId, String runId) {
        if (workflowId == null || workflowId.isBlank()) {
            return false;
        }
        if (runId != null && runId.startsWith(AD_HOC_RUN_ID_PREFIX)) {
            return false;
        }
        // Canonical form only. UUID.fromString also accepts "1-2-3-4-5", which no workflow id ever
        // is; the strict pattern is the one V555 repaired existing rows with, so the rule for new
        // writes and the rule for the repair are the same rule.
        return CANONICAL_UUID.matcher(workflowId).matches();
    }

    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
}
