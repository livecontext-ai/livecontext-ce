/**
 * Tab ids are decorated (`workflow-builder-<id>`, `workflow-run-<id>-<runId>`,
 * `application-<id>-<runId>`), so reading one back is a PARSE, not a prefix strip.
 *
 * The reported bug: opening a sub-workflow from a workflow node creates the tab
 * `workflow-builder-<uuid>`; the naive strip of `workflow-` left `builder-<uuid>`
 * and "Go to page" navigated to /app/workflow/builder-<uuid>, a workflow id that
 * does not exist, so the builder answered "Failed to load this workflow" while the
 * side panel showed that very workflow fine (it gets the id as a prop).
 */
import { describe, it, expect } from 'vitest';
import {
  AGENDA_PANEL_TAB_ID,
  APPLICATION_PANEL_TAB_ID,
  WORKFLOW_PANEL_TAB_ID,
  getTabResourceUrl,
  parseTabResource,
  workflowPanelTabId,
} from '@/lib/sidePanel/tabResource';

const WF = 'f54f378a-c4ff-4398-a003-107c87e9f2a6';
const SUB_WF = 'ef1d124a-610b-4c6b-b1d8-8fb6a6f20604';
const RUN = '9c3f1b2e-77aa-4d61-9d0e-51d2b6a4c8f0';

describe('workflowPanelTabId', () => {
  it('gives every entry point the SAME id, so one workflow means one tab', () => {
    // The sub-workflow node used to build 'workflow-builder-<id>' while the resource
    // picker built 'workflow-<id>': same content, two tabs, and the builder form is
    // what leaked into the URL as /app/workflow/builder-<id>.
    expect(workflowPanelTabId(SUB_WF)).toBe(`workflow-${SUB_WF}`);
    expect(workflowPanelTabId(SUB_WF)).not.toContain('builder-');
  });

  it('gives a run its own id, so opening it does not take over the workflow tab', () => {
    expect(workflowPanelTabId(WF, RUN)).toBe(`workflow-run-${WF}-${RUN}`);
    expect(workflowPanelTabId(WF, RUN)).not.toBe(workflowPanelTabId(WF));
    expect(workflowPanelTabId(WF, null)).toBe(`workflow-${WF}`);
    expect(workflowPanelTabId(WF, undefined)).toBe(`workflow-${WF}`);
  });

  it('round-trips through the parser it shares a file with', () => {
    expect(parseTabResource(workflowPanelTabId(WF))).toEqual({ kind: 'workflow', id: WF });
    expect(parseTabResource(workflowPanelTabId(WF, RUN))).toEqual({ kind: 'workflow', id: WF, runId: RUN });
    expect(getTabResourceUrl(workflowPanelTabId(WF))).toBe(`/app/workflow/${WF}`);
  });
});

describe('getTabResourceUrl', () => {
  it('opens the global agenda tab on its full-page counterpart', () => {
    expect(getTabResourceUrl(AGENDA_PANEL_TAB_ID)).toBe('/app/agenda');
  });

  it('drops the builder decoration of a legacy sub-workflow tab (the reported bug)', () => {
    // Pre-fix: '/app/workflow/builder-ef1d124a-610b-4c6b-b1d8-8fb6a6f20604'. No new tab
    // carries this id, but one opened before a reload still does.
    expect(getTabResourceUrl(`workflow-builder-${SUB_WF}`)).toBe(`/app/workflow/${SUB_WF}`);
  });

  it('keeps a plain workflow tab pointing at its workflow page', () => {
    expect(getTabResourceUrl(`workflow-${WF}`)).toBe(`/app/workflow/${WF}`);
  });

  it('sends a run tab to that run, not to the workflow', () => {
    expect(getTabResourceUrl(`workflow-run-${WF}-${RUN}`)).toBe(`/app/workflow/${WF}/run/${RUN}`);
  });

  it('falls back to the workflow page when a run tab carries no run id', () => {
    expect(getTabResourceUrl(`workflow-run-${WF}`)).toBe(`/app/workflow/${WF}`);
  });

  it('drops the run suffix of an application tab, which has no per-run page', () => {
    // Same bug shape as the sub-workflow one: applicationPanelTabId() appends
    // `-<runId>` so each execution gets its own tab.
    expect(getTabResourceUrl(`application-${WF}-${RUN}`)).toBe(`/app/applications/${WF}`);
    expect(getTabResourceUrl(`application-${WF}`)).toBe(`/app/applications/${WF}`);
  });

  it('maps the remaining resource kinds to their pages', () => {
    expect(getTabResourceUrl(`interface-${WF}`)).toBe(`/app/interface/${WF}`);
    expect(getTabResourceUrl(`datasource-${WF}`)).toBe(`/app/data/${WF}`);
    // The agents page is one board, not one page per agent, so the agent travels in the
    // query: without it the button landed on the bare list and the agent never opened.
    expect(getTabResourceUrl(`agent-${WF}`)).toBe(`/app/agent?openAgent=${WF}`);
  });

  it('offers no page for a tab that shows no addressable resource', () => {
    expect(getTabResourceUrl('ai-chat')).toBeNull();
    expect(getTabResourceUrl('files-panel')).toBeNull();
    expect(getTabResourceUrl('workflow-')).toBeNull();
    // The pinned panels are control surfaces for the page's resource, not the
    // workflow/application whose id is "panel" - read as one, they navigated to
    // /app/workflow/panel and /app/applications/panel.
    expect(getTabResourceUrl(WORKFLOW_PANEL_TAB_ID)).toBeNull();
    expect(getTabResourceUrl(APPLICATION_PANEL_TAB_ID)).toBeNull();
  });
});

describe('parseTabResource', () => {
  it('reports the sub-workflow tab as the workflow itself, with no run scope', () => {
    expect(parseTabResource(`workflow-builder-${SUB_WF}`)).toEqual({ kind: 'workflow', id: SUB_WF });
  });

  it('reports the run scope of a run tab, so callers can refuse resource-wide actions', () => {
    // This is what stops the tab menu from offering a Delete that would wipe the
    // whole workflow (pre-fix it offered one that deleted the literal id
    // "run-<wfId>-<runId>" and always failed).
    expect(parseTabResource(`workflow-run-${WF}-${RUN}`)).toEqual({ kind: 'workflow', id: WF, runId: RUN });
    expect(parseTabResource(`application-${WF}-${RUN}`)).toEqual({ kind: 'application', id: WF, runId: RUN });
  });

  it('never splits an id that is not a uuid, keeping legacy tab ids intact', () => {
    // Splitting on the first dash would turn 'pub-1-run-1' into id 'pub'.
    expect(parseTabResource('application-pub-1-run-1')).toEqual({ kind: 'application', id: 'pub-1-run-1' });
  });

  it('returns null for a tab id that names no resource', () => {
    expect(parseTabResource(AGENDA_PANEL_TAB_ID)).toBeNull();
    expect(parseTabResource('files-panel')).toBeNull();
    expect(parseTabResource(WORKFLOW_PANEL_TAB_ID)).toBeNull();
    expect(parseTabResource(APPLICATION_PANEL_TAB_ID)).toBeNull();
  });

  it('reads a live browser session as no resource, not as an agent', () => {
    // 'agent-browse-<sessionId>' decorates a known prefix the way
    // 'workflow-builder-<id>' did. Read as an agent, the tab menu offered a red
    // Delete calling deleteAgent('browse-<sessionId>'), which could only 404.
    expect(parseTabResource(`agent-browse-${RUN}`)).toBeNull();
    expect(getTabResourceUrl(`agent-browse-${RUN}`)).toBeNull();
    // A real agent tab is untouched.
    expect(parseTabResource(`agent-${WF}`)).toEqual({ kind: 'agent', id: WF });
  });
});

describe('conversation tabs', () => {
  const CONV = 'c0000000-0000-4000-8000-00000000000a';

  it('parse to the conversation they show, so a deleted one can be matched and closed', () => {
    expect(parseTabResource(`conversation-${CONV}`)).toEqual({ kind: 'conversation', id: CONV });
  });

  it('get NO page url - the id alone cannot choose between the two surfaces', () => {
    // A conversation lives on the chat surface or the studio one and its `kind`
    // decides which; a tab id carries no kind. Guessing the chat route renders a
    // studio thread blank and then feeds its generation envelopes to a chat model,
    // so the tab menu offers no destination rather than the wrong one.
    expect(getTabResourceUrl(`conversation-${CONV}`)).toBeNull();
  });
});
