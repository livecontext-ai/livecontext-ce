// @vitest-environment jsdom
/**
 * A tab whose resource has been deleted is dropped - PINNED OR NOT.
 *
 * The reported bug: delete an agent from the agents list while its panel tab is
 * open, and the tab stays. Worse, the tab the list opened was `pinned: true`,
 * and a pinned tab has no X, no 3-dot menu, no Close and no Delete entry, so the
 * user was left staring at an agent that no longer existed with no way to
 * dismiss it (closing the whole panel brought it back on reopen, since pinned
 * tabs survive navigation).
 *
 * Two halves fix it and both are pinned here: the tab now closes on the deletion
 * broadcast, and that one route overrides `pinned` on purpose. `pinned` means
 * "the user may not close this", which is right for a panel the page owns and a
 * trap for a tab whose subject is gone.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, act, screen } from '@testing-library/react';

let mockPathname = '/en/app/agent';
vi.mock('next/navigation', () => ({ usePathname: () => mockPathname }));
vi.mock('@/hooks/useMobileDetection', () => ({ useMobileDetection: () => false }));

import { SidePanelProvider, useSidePanel, type SidePanelTab } from '@/contexts/SidePanelContext';
import { notifyResourceDeleted } from '@/lib/resources/resourceDeleted';
import { WORKFLOW_PANEL_TAB_ID } from '@/lib/sidePanel/tabResource';

const AGENT = 'a0000000-0000-4000-8000-000000000001';
const OTHER_AGENT = 'a0000000-0000-4000-8000-000000000002';
const WF = 'f54f378a-c4ff-4398-a003-107c87e9f2a6';
const RUN = '9c3f1b2e-77aa-4d61-9d0e-51d2b6a4c8f0';

const tab = (id: string, over: Partial<SidePanelTab> = {}): SidePanelTab => ({
  id,
  label: id,
  icon: null,
  content: <div />,
  ...over,
});

/** Opens the given tabs on mount and reports panel state back to the assertions. */
let api: ReturnType<typeof useSidePanel>;
function Harness({ initial }: { initial: SidePanelTab[] }) {
  const ctx = useSidePanel();
  api = ctx;
  const opened = React.useRef(false);
  React.useEffect(() => {
    if (opened.current) return;
    opened.current = true;
    initial.forEach(t => ctx.openTab(t));
  }, [ctx, initial]);
  return (
    <div>
      <span data-testid="tabs">{ctx.tabs.map(t => t.id).join(',')}</span>
      <span data-testid="active">{ctx.activeTabId ?? 'none'}</span>
      <span data-testid="open">{String(ctx.isOpen)}</span>
    </div>
  );
}

const mount = (initial: SidePanelTab[]) =>
  render(<SidePanelProvider><Harness initial={initial} /></SidePanelProvider>);

const tabIds = () => screen.getByTestId('tabs').textContent;
const activeId = () => screen.getByTestId('active').textContent;
const isOpen = () => screen.getByTestId('open').textContent;

beforeEach(() => { mockPathname = '/en/app/agent'; });

describe('SidePanel: a deleted resource takes its tab with it', () => {
  it('closes a PINNED agent tab - the exact tab the agents list opens', () => {
    mount([tab(`agent-${AGENT}`, { pinned: true }), tab(`agent-${OTHER_AGENT}`)]);
    expect(tabIds()).toBe(`agent-${AGENT},agent-${OTHER_AGENT}`);

    act(() => notifyResourceDeleted('agent', AGENT));

    expect(tabIds()).toBe(`agent-${OTHER_AGENT}`);
  });

  it('closes a PERSISTENT tab too - same reason, and removeTab refuses both', () => {
    mount([tab(`agent-${AGENT}`, { persistent: true })]);

    act(() => notifyResourceDeleted('agent', AGENT));

    expect(tabIds()).toBe('');
  });

  it('leaves every other tab alone, including another agent', () => {
    mount([tab(`agent-${OTHER_AGENT}`), tab(`workflow-${WF}`), tab(`datasource-${AGENT}`)]);

    act(() => notifyResourceDeleted('agent', AGENT));

    // `datasource-<AGENT>` shares the id but not the kind: matching on the id
    // alone would close an unrelated table every time an agent was deleted.
    expect(tabIds()).toBe(`agent-${OTHER_AGENT},workflow-${WF},datasource-${AGENT}`);
  });

  it('takes a run tab down with the workflow it shows', () => {
    // A run of a deleted workflow is as unreachable as the workflow; the match
    // goes through parseTabResource, which reads the decoration off both forms.
    mount([tab(`workflow-${WF}`), tab(`workflow-run-${WF}-${RUN}`), tab(`workflow-${'ef1d124a-610b-4c6b-b1d8-8fb6a6f20604'}`)]);

    act(() => notifyResourceDeleted('workflow', WF));

    expect(tabIds()).toBe('workflow-ef1d124a-610b-4c6b-b1d8-8fb6a6f20604');
  });

  it('does not touch the reserved page-control tab, which names no resource', () => {
    // `workflow-panel` is the page's own control panel, not the workflow whose
    // id is "panel"; parseTabResource returns null for it and it must survive.
    mount([tab(WORKFLOW_PANEL_TAB_ID, { pinned: true })]);

    act(() => notifyResourceDeleted('workflow', 'panel'));

    expect(tabIds()).toBe(WORKFLOW_PANEL_TAB_ID);
  });

  it('closes a conversation tab, so a deleted conversation cannot linger in the panel', () => {
    mount([tab(`conversation-${AGENT}`)]);

    act(() => notifyResourceDeleted('conversation', AGENT));

    expect(tabIds()).toBe('');
  });

  it('moves the selection off the closed tab instead of leaving a dead active id', () => {
    mount([tab(`workflow-${WF}`), tab(`agent-${AGENT}`, { pinned: true })]);
    expect(activeId()).toBe(`agent-${AGENT}`);

    act(() => notifyResourceDeleted('agent', AGENT));

    expect(activeId()).toBe(`workflow-${WF}`);
  });

  it('keeps the selection when the closed tab was not the active one', () => {
    mount([tab(`agent-${AGENT}`), tab(`workflow-${WF}`)]);
    expect(activeId()).toBe(`workflow-${WF}`);

    act(() => notifyResourceDeleted('agent', AGENT));

    expect(activeId()).toBe(`workflow-${WF}`);
  });

  it('closes the panel when the last tab was the deleted resource', () => {
    mount([tab(`agent-${AGENT}`, { pinned: true })]);
    expect(isOpen()).toBe('true');

    act(() => notifyResourceDeleted('agent', AGENT));

    expect(isOpen()).toBe('false');
    expect(activeId()).toBe('none');
  });

  it('leaves the panel open while other tabs remain', () => {
    mount([tab(`agent-${AGENT}`), tab(`workflow-${WF}`)]);

    act(() => notifyResourceDeleted('agent', AGENT));

    expect(isOpen()).toBe('true');
  });

  it('does nothing for a resource no tab was showing', () => {
    mount([tab(`workflow-${WF}`)]);

    act(() => notifyResourceDeleted('agent', AGENT));

    expect(tabIds()).toBe(`workflow-${WF}`);
    expect(isOpen()).toBe('true');
  });

  it('still refuses a USER close of a pinned tab - the override is the deletion, not the pin', () => {
    // Pre-fix behaviour that must not regress: `removeTab` is what the X and the
    // menu call, and a pinned tab is theirs to refuse.
    mount([tab(`agent-${AGENT}`, { pinned: true })]);

    act(() => api.removeTab(`agent-${AGENT}`));

    expect(tabIds()).toBe(`agent-${AGENT}`);
  });
});
