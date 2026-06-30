/**
 * Shared "is this canvas embedded?" heuristic for workflow canvases.
 *
 * A workflow canvas is EMBEDDED when it is mounted outside its own
 * /app/workflow/<id> page - e.g. inside a SidePanel tab on a chat page, or as
 * a sub-workflow tab opened from another workflow's page. Embedded canvases
 * must not navigate (they swap mode in place via setRunId) and must not show
 * page-level chrome like the empty-canvas AI composer.
 *
 * The check is locale-prefix agnostic (`/fr/app/workflow/<id>` matches) and
 * run-URL agnostic (`/app/workflow/<id>/run/<runId>` matches). The id match
 * is boundary-aware: workflow `wf-1` does NOT own `/app/workflow/wf-12`.
 */
export function isEmbeddedWorkflowCanvas(
  pathname: string | null | undefined,
  workflowId: string | undefined,
): boolean {
  if (!workflowId) return false;
  if (!pathname) return true;
  const escapedId = workflowId.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return !new RegExp(`/workflow/${escapedId}(?=[/?#]|$)`).test(pathname);
}
