/**
 * @vitest-environment jsdom
 *
 * The tab menu's "Go to page" re-derives a URL from the tab ID, because the tab
 * itself carries no route. Tab ids are decorated per flavour, so that derivation
 * must UNDECORATE them: a sub-workflow opens as `workflow-builder-<uuid>` and the
 * naive `workflow-` strip sent the user to /app/workflow/builder-<uuid>, an id
 * that does not exist ("Failed to load this workflow") while the panel beside it
 * displayed that same sub-workflow fine.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';

const push = vi.fn();
const deleteWorkflow = vi.hoisted(() => vi.fn().mockResolvedValue(undefined));

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push }),
  usePathname: () => '/app/workflow/f54f378a-c4ff-4398-a003-107c87e9f2a6',
}));
vi.mock('@/hooks/useMobileDetection', () => ({ useMobileDetection: () => false }));
vi.mock('@/hooks/useMouseResize', () => ({
  useMouseResize: () => ({ isResizing: false, startResize: vi.fn(), hasManuallyResizedRef: { current: false } }),
}));
vi.mock('@/contexts/SharedConversationContext', () => ({ useSharedConversation: () => null }));
vi.mock('@/components/app/AddTabPicker', () => ({ AddTabPicker: () => null }));
vi.mock('@/components/ui/PanelResizeHandle', () => ({ PanelResizeHandle: () => null }));
vi.mock('@/components/ui/BulkDeleteModal', () => ({
  BulkDeleteModal: ({ isOpen, title, onConfirm }: { isOpen: boolean; title: string; onConfirm: () => void }) =>
    isOpen ? <button type="button" data-testid="confirm-delete" title={title} onClick={onConfirm}>confirm</button> : null,
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: { deleteWorkflow } }));

import { SidePanelProvider, useSidePanel, type SidePanelTab } from '@/contexts/SidePanelContext';
import { SidePanel } from '@/components/app/SidePanel';
import { AGENDA_PANEL_TAB_ID } from '@/lib/sidePanel/tabResource';

const SUB_WF = 'ef1d124a-610b-4c6b-b1d8-8fb6a6f20604';
const WF = 'f54f378a-c4ff-4398-a003-107c87e9f2a6';
const RUN = '9c3f1b2e-77aa-4d61-9d0e-51d2b6a4c8f0';

beforeEach(() => { push.mockClear(); deleteWorkflow.mockClear(); });
afterEach(cleanup);

function Opener({ tab }: { tab: SidePanelTab }) {
  const sp = useSidePanel();
  const done = React.useRef(false);
  React.useEffect(() => {
    if (done.current) return;
    done.current = true;
    sp.openTab(tab);
  }, [sp, tab]);
  return null;
}

/** Render the panel with a single tab and open that tab's overflow menu. */
function openTabMenu(id: string) {
  const utils = render(
    <SidePanelProvider>
      <Opener tab={{ id, label: id, icon: <span />, content: <div>{id}</div> }} />
      <SidePanel />
    </SidePanelProvider>,
  );
  act(() => { /* flush the opening effect */ });
  const trigger = utils.container.querySelector<HTMLElement>('[data-testid="side-panel-tab"] [role="button"]');
  expect(trigger).toBeTruthy();
  fireEvent.click(trigger!);
  return utils;
}

describe('SidePanel tab menu - "Go to page"', () => {
  it('navigates the agenda tab to the full agenda page', () => {
    openTabMenu(AGENDA_PANEL_TAB_ID);
    fireEvent.click(screen.getByText('goToPage'));
    expect(push).toHaveBeenCalledWith('/app/agenda');
  });

  it('navigates a sub-workflow tab to the sub-workflow itself, not to a "builder-" id', () => {
    openTabMenu(`workflow-builder-${SUB_WF}`);
    fireEvent.click(screen.getByText('goToPage'));
    // Pre-fix: '/app/workflow/builder-ef1d124a-610b-4c6b-b1d8-8fb6a6f20604' → load error page.
    expect(push).toHaveBeenCalledWith(`/app/workflow/${SUB_WF}`);
  });

  it('navigates a run tab to that run', () => {
    openTabMenu(`workflow-run-${WF}-${RUN}`);
    fireEvent.click(screen.getByText('goToPage'));
    expect(push).toHaveBeenCalledWith(`/app/workflow/${WF}/run/${RUN}`);
  });

  it('navigates an agent tab to that agent, not to the bare agents board', () => {
    openTabMenu(`agent-${WF}`);
    fireEvent.click(screen.getByText('goToPage'));
    // Agents have no page of their own, so the one the panel shows travels in the query.
    // Pre-fix this pushed '/app/agent' and the agent the user was looking at vanished.
    expect(push).toHaveBeenCalledWith(`/app/agent?openAgent=${WF}`);
  });

  it('offers no menu on a tab that shows no addressable resource, just the close control', () => {
    const { container } = render(
      <SidePanelProvider>
        <Opener tab={{ id: 'files-panel', label: 'Files', icon: <span />, content: <div /> }} />
        <SidePanel />
      </SidePanelProvider>,
    );
    act(() => { /* flush */ });
    const tab = container.querySelector('[data-testid="side-panel-tab"]')!;
    expect(tab.querySelector('svg.lucide-more-vertical')).toBeNull();
    expect(tab.querySelector('svg.lucide-x')).toBeTruthy();
  });
});

describe('SidePanel tab menu - delete entry', () => {
  it('deletes the real sub-workflow from a legacy builder tab, not a "builder-" id', async () => {
    openTabMenu(`workflow-builder-${SUB_WF}`);
    fireEvent.click(screen.getByText('delete'));

    // Pre-fix this called deleteWorkflow('builder-<uuid>'), which could only ever
    // 404: the menu entry looked alive and did nothing.
    await act(async () => { fireEvent.click(screen.getByTestId('confirm-delete')); });
    expect(deleteWorkflow).toHaveBeenCalledWith(SUB_WF);
  });

  it('deletes the workflow itself from a plain workflow tab', async () => {
    openTabMenu(`workflow-${WF}`);
    fireEvent.click(screen.getByText('delete'));

    await act(async () => { fireEvent.click(screen.getByTestId('confirm-delete')); });
    expect(deleteWorkflow).toHaveBeenCalledWith(WF);
  });

  it('offers no delete on a run tab, which shows one execution and not the workflow', () => {
    openTabMenu(`workflow-run-${WF}-${RUN}`);
    expect(screen.getByText('goToPage')).toBeTruthy();
    expect(screen.queryByText('delete')).toBeNull();
  });
});
