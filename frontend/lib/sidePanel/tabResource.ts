/**
 * Side-panel tab ids, built and read back in ONE place.
 *
 *   workflow-<wfId>                     the workflow
 *   workflow-run-<wfId>-<runId>         one execution of the workflow
 *   application-<pubId>                 the application
 *   application-<pubId>-<runId>         one execution of the application
 *
 * A tab id is the panel's merge key (same id => the tab is reused, different id
 * => a second tab), so a run gets its own id: without the suffix, opening a run
 * would take over the workflow's tab and two runs would collapse onto one.
 *
 * That decoration means reading an id back is a PARSE, never a prefix strip.
 * A `workflow-builder-<wfId>` form used to exist too; stripping `workflow-` off it
 * left `builder-<wfId>`, so "Go to page" navigated to /app/workflow/builder-<wfId>,
 * a workflow id that does not exist: the page answered "Failed to load this
 * workflow" while the panel beside it showed that same workflow fine (the panel
 * gets the id as a prop, it never parses the tab id).
 */

/**
 * The pinned "Workflow Panel" tab. Reserved: it is a control panel for the page's
 * workflow, not a tab showing one addressable workflow, so it must not be read as
 * the workflow whose id is "panel".
 */
export const WORKFLOW_PANEL_TAB_ID = 'workflow-panel';

/** Its application twin, reserved for the same reason. */
export const APPLICATION_PANEL_TAB_ID = 'application-panel';

/** The workspace agenda is a global panel surface, with a dedicated full-page counterpart. */
export const AGENDA_PANEL_TAB_ID = 'agenda-panel';

/**
 * Build the side-panel tab id for a workflow, optionally scoped to one run.
 *
 * Every producer goes through this so a workflow opened from a sub-workflow node,
 * from the resource picker, from a project or from the header lands on the SAME
 * tab. A `workflow-builder-<wfId>` variant used to exist for the sub-workflow
 * entry point: it showed the very same content as `workflow-<wfId>`, so all it
 * bought was a duplicate tab for one workflow, plus the broken URL above. Adding
 * a variant back means adding a branch to {@link parseTabResource} too.
 */
export function workflowPanelTabId(workflowId: string, runId?: string | null): string {
  return runId ? `workflow-run-${workflowId}-${runId}` : `workflow-${workflowId}`;
}

/**
 * Build the side-panel tab id for an application, optionally scoped to one run.
 *
 * Each `application:execute` creates a new run and the chat renders one preview
 * card per execution, so a run gets its own tab: keyed by publication alone, two
 * executions collapsed onto one tab and the second card showed the first one's
 * run (the epoch-collapse bug).
 */
export function applicationPanelTabId(publicationId: string, runId?: string | null): string {
  return runId ? `application-${publicationId}-${runId}` : `application-${publicationId}`;
}

/** Anchored at the start: the resource id always comes first, decoration follows. */
const UUID_PREFIX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;

export type TabResourceKind = 'workflow' | 'interface' | 'application' | 'datasource' | 'agent' | 'conversation';

export interface TabResource {
  kind: TabResourceKind;
  /** The resource's own id, free of any decoration. */
  id: string;
  /** Set when the tab is scoped to ONE execution of the resource. */
  runId?: string;
}

/**
 * Split `<uuid>-<runId>` into its two parts.
 *
 * Falls back to the whole string as the id when it does not start with a UUID,
 * rather than splitting at an arbitrary dash: a non-UUID id (demo tabs, tests,
 * any future non-UUID resource) then resolves to itself. The trade-off is that a
 * non-UUID id carrying a run suffix cannot be split either, so it resolves to the
 * whole `<id>-<runId>` string. Every workflow, run and publication id is a UUID
 * here; the fallback exists so an unexpected id degrades visibly instead of being
 * silently truncated.
 */
function splitRunSuffix(rest: string): { id: string; runId?: string } {
  const match = UUID_PREFIX.exec(rest);
  if (!match) return { id: rest };
  const id = match[0];
  const runId = rest.slice(id.length + 1); // skip the dash separator
  return runId ? { id, runId } : { id };
}

/** Parse a side-panel tab id into the resource it shows, or null if it shows none. */
export function parseTabResource(tabId: string): TabResource | null {
  if (tabId === WORKFLOW_PANEL_TAB_ID || tabId === APPLICATION_PANEL_TAB_ID) return null;
  // A live browser session, not an agent: 'agent-browse-<sessionId>' decorates a
  // known prefix exactly the way 'workflow-builder-<id>' did, and read as an agent
  // it put a red Delete on the tab that could only ever fail.
  if (tabId.startsWith('agent-browse-')) return null;
  if (tabId.startsWith('workflow-run-')) {
    const { id, runId } = splitRunSuffix(tabId.slice('workflow-run-'.length));
    return { kind: 'workflow', id, runId };
  }
  // Legacy form. No producer writes it any more (grep is the guarantee: the only
  // builder is workflowPanelTabId), but a tab opened by an older bundle before a
  // reload still carries it, so it stays readable rather than losing its menu.
  // Do not delete this as dead code without checking that no client can still
  // hold such a tab.
  if (tabId.startsWith('workflow-builder-')) {
    return { kind: 'workflow', id: tabId.slice('workflow-builder-'.length) };
  }
  if (tabId.startsWith('workflow-')) {
    return { kind: 'workflow', id: tabId.slice('workflow-'.length) };
  }
  if (tabId.startsWith('application-')) {
    const { id, runId } = splitRunSuffix(tabId.slice('application-'.length));
    return { kind: 'application', id, runId };
  }
  if (tabId.startsWith('interface-')) {
    return { kind: 'interface', id: tabId.slice('interface-'.length) };
  }
  if (tabId.startsWith('datasource-')) {
    return { kind: 'datasource', id: tabId.slice('datasource-'.length) };
  }
  if (tabId.startsWith('agent-')) {
    return { kind: 'agent', id: tabId.slice('agent-'.length) };
  }
  if (tabId.startsWith('conversation-')) {
    return { kind: 'conversation', id: tabId.slice('conversation-'.length) };
  }
  return null;
}

/** Derive a full-page URL from a tab id, or null if the resource has no dedicated page. */
export function getTabResourceUrl(tabId: string): string | null {
  if (tabId === AGENDA_PANEL_TAB_ID) return '/app/agenda';
  const resource = parseTabResource(tabId);
  if (!resource || !resource.id) return null;
  switch (resource.kind) {
    case 'workflow':
      return resource.runId
        ? `/app/workflow/${resource.id}/run/${resource.runId}`
        : `/app/workflow/${resource.id}`;
    // The applications page takes the publication only: a run tab lands on the app itself.
    case 'application':
      return `/app/applications/${resource.id}`;
    case 'interface':
      return `/app/interface/${resource.id}`;
    case 'datasource':
      return `/app/data/${resource.id}`;
    // The agents page is a single board, not one page per agent: the agent it carries is
    // named in the query, and the board opens its panel on arrival. Dropping the id here sent
    // the button to the bare list, which is the whole complaint it exists to answer.
    case 'agent':
      return `/app/agent?openAgent=${resource.id}`;
    // A conversation lives on one of TWO surfaces and its `kind` decides which; a
    // tab id carries an id and nothing else, so this cannot ask. Sending a studio
    // thread to the chat route renders it blank and then feeds its generation
    // envelopes to a chat model, so the answer is no page rather than a guess.
    // The kind is here only so a deleted conversation's tab can be matched and
    // closed, which needs no URL.
    case 'conversation':
      return null;
  }
}
