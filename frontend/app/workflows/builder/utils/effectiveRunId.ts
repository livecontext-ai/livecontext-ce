/**
 * Single source of truth for the run id the canvas binds to (`effectiveRunId`).
 *
 * The canvas can learn its run id from two places:
 *  - `contextRunId` - the WorkflowModeContext run id (set by the URL `/run/<id>`,
 *    by firing a trigger from edit mode, or by the edit/run toggle). Authoritative.
 *  - `runIdProp` - the STATIC `runId` prop passed once when a side-panel tab is
 *    opened against a specific run (e.g. an agent-launched `workflow_run` tab).
 *    Never changes for the life of the tab.
 *
 * Why the `isRunMode` guard matters (regression: agent-opened run panel lost its
 * node status counts on an edit→run toggle):
 *
 *   The naive `contextRunId || runIdProp` pins `effectiveRunId` to the static prop
 *   forever. When the user toggles run→edit, the canvas blanks every node's
 *   `statusCounts` and sets `contextRunId = null` - but `null || runIdProp` keeps
 *   `effectiveRunId` at the prop value, so the WS never re-subscribes and
 *   `runState` never changes. Toggling edit→run then resolves (via
 *   `getLatestWorkflowRun`) to the SAME run as the prop, so `effectiveRunId`
 *   doesn't change either → the `useRunStateProcessing` repaint (keyed on
 *   `effectiveRunId` / `runState.batchSteps`) never re-fires and the freshly
 *   blanked nodes stay blank. The `+`-menu path has no prop, so `effectiveRunId`
 *   toggles `undefined ↔ run` and repaints correctly.
 *
 * Fix: the context run id always wins; the static prop is only a fallback WHILE
 * in run mode. In edit mode there is no bound run, so `effectiveRunId` is
 * `undefined` regardless of the prop - exactly like the prop-less `+` path.
 *
 * Note: `contextRunId` truthy implies run mode (every setter that assigns a run
 * id also sets `mode = 'run'`), so when `contextRunId` is present the
 * `isRunMode` branch is never consulted - the guard only changes the edit-mode
 * case where `contextRunId` is null but a static prop is present.
 */
export function resolveEffectiveRunId(
  contextRunId: string | null | undefined,
  runIdProp: string | null | undefined,
  isRunMode: boolean,
): string | undefined {
  return contextRunId || (isRunMode ? runIdProp || undefined : undefined);
}
