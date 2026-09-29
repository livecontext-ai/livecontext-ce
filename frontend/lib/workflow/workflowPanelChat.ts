import type { AutoOpenVisualization } from '@/contexts/sidePanelAutoOpen';

/**
 * Which conversation the AI chat of the workflow page's panel holds, per workflow.
 *
 * The page follows what THAT agent does (it edits the plan: back to editing; the user toggles
 * edit/run while it works: the panel stays on the chat), and nothing else's: an agent chatting
 * elsewhere about the same workflow must not move the canvas under the user. The panel body is
 * unmounted whenever the side panel is closed or shows another tab, while its stream carries on
 * in StreamingContext, so the page cannot ask the panel: the panel records its conversation here
 * and the page reads it at the moment it decides.
 */
export const WORKFLOW_PANEL_CHAT_TAB_ID = '__chat_ia__';

const conversationByWorkflow = new Map<string, string>();

export function rememberWorkflowPanelConversation(workflowId: string, conversationId: string | null): void {
  // A workflow has ONE panel conversation, and the panel reports null on every remount until it
  // has found it again: only a real id overwrites, or reopening the panel would forget an agent
  // that is still streaming.
  if (conversationId) conversationByWorkflow.set(workflowId, conversationId);
}

/**
 * The edit/run toggle marks its own clicks, keyed by the run it is about to show (null = edit).
 * The page cannot tell a toggle from a Run press, a history pick or the agent's own binding by
 * looking at the run change alone, and only the toggle keeps the agent's chat in front.
 */
const userToggleTarget = new Map<string, string | null>();

export function markUserModeToggle(workflowId: string, targetRunId: string | null): void {
  userToggleTarget.set(workflowId, targetRunId);
}

/** True when the run change just observed is the toggle's; the mark is spent either way. */
export function consumeUserModeToggle(workflowId: string, boundRunId: string | null): boolean {
  if (!userToggleTarget.has(workflowId)) return false;
  const target = userToggleTarget.get(workflowId);
  userToggleTarget.delete(workflowId);
  return target === boundRunId;
}

export function workflowPanelConversation(workflowId: string): string | undefined {
  return conversationByWorkflow.get(workflowId);
}

/** True when the workflow panel's own agent produced this auto-open marker. */
export function isFromWorkflowPanelChat(workflowId: string, detail: Pick<AutoOpenVisualization, 'conversationId'>): boolean {
  const own = conversationByWorkflow.get(workflowId);
  return !!own && detail.conversationId === own;
}

/**
 * The page reaction to an agent marker about THIS workflow, decided without React so it can be
 * tested on its own:
 * - `edit`: the panel's agent changed the stored plan while the canvas shows a run.
 * - `bindRun`: a run of this workflow was launched, replayed or resumed; `keepPlan` says the
 *   canvas already shows the version that run executes, so the run overlays it without a reload.
 * - `none`: anything else.
 */
export type AgentMarkerReaction =
  | { kind: 'none' }
  | { kind: 'edit' }
  | { kind: 'bindRun'; runId: string; keepPlan: boolean };

const RUN_MARKERS = new Set(['workflow_run', 'present_run', 'present_application']);

export function reactionToAgentMarker(
  detail: AutoOpenVisualization,
  view: { workflowId: string; boundRunId: string | null; shownVersion: number | null },
): AgentMarkerReaction {
  if (detail.id !== view.workflowId) return { kind: 'none' };

  if (detail.type === 'workflow') {
    if (detail.planChanged && view.boundRunId && isFromWorkflowPanelChat(view.workflowId, detail)) {
      return { kind: 'edit' };
    }
    return { kind: 'none' };
  }

  if (!RUN_MARKERS.has(detail.type) || !detail.runId) return { kind: 'none' };
  // Keeping the plan on screen is only right when it IS the run's plan: the canvas is in edit
  // mode (HEAD) and the run executes that same version. A run of a pinned version, or a canvas
  // showing another run, reloads the run's own plan instead.
  const keepPlan = !view.boundRunId
    && (detail.planVersion == null || view.shownVersion == null || detail.planVersion === view.shownVersion);
  return { kind: 'bindRun', runId: detail.runId, keepPlan };
}
