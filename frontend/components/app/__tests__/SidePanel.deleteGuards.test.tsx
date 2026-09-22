/**
 * @vitest-environment jsdom
 *
 * The tab's "Delete" entry, which unpinning the agents-page tab newly exposed.
 *
 * `deleteHandler` is built only for an unpinned tab, so while the agents list
 * opened its tab pinned, the entry was unreachable on an agent. Unpinning the tab
 * was the right fix for a different problem and it uncovered two things that were
 * already true of every workflow / interface / table tab:
 *
 *   1. nothing checked whether this member may delete anything, so an org VIEWER
 *      got an entry that could only ever 403;
 *   2. `confirmDelete` had no catch, so a refusal closed the modal exactly as a
 *      success does - no message, an unhandled rejection, and a user who
 *      reasonably concluded the resource was gone. That is the expensive failure
 *      mode: green when wrong.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';

let canMutate = true;
const deleteAgent = vi.fn();

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) => (
    vars?.name ? `${key}:${vars.name}` : key
  ),
}));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  usePathname: () => '/app/agent',
}));
vi.mock('@/hooks/useMobileDetection', () => ({ useMobileDetection: () => false }));
vi.mock('@/hooks/useMouseResize', () => ({
  useMouseResize: () => ({ isResizing: false, startResize: vi.fn(), hasManuallyResizedRef: { current: false } }),
}));
vi.mock('@/contexts/SharedConversationContext', () => ({ useSharedConversation: () => null }));
vi.mock('@/components/app/AddTabPicker', () => ({ AddTabPicker: () => null }));
vi.mock('@/components/ui/PanelResizeHandle', () => ({ PanelResizeHandle: () => null }));
// Spread the real module: SidePanel pulls useCurrentOrg out of it too (through
// useFloatingPanelRect), and a literal factory silently drops every other export.
vi.mock('@/lib/stores/current-org-store', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/stores/current-org-store')>()),
  useCanMutateInCurrentOrg: () => canMutate,
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: { deleteAgent: (...a: unknown[]) => deleteAgent(...a) } }));
// Render the tab menu inline. Radix opens its popover through portals and pointer
// capture that jsdom does not implement, and the assertions here are about WHICH
// entries the menu contains, not about how it opens.
vi.mock('@/components/ui/popover', () => ({
  Popover: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  PopoverTrigger: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  PopoverContent: ({ children }: { children: React.ReactNode }) => <div data-testid="tab-menu">{children}</div>,
}));

/** Stands in for the confirmation modal so the assertions can read what it was told. */
let modalProps: Record<string, any> | null = null;
vi.mock('@/components/ui/BulkDeleteModal', () => ({
  BulkDeleteModal: (props: Record<string, any>) => {
    modalProps = props;
    return props.isOpen ? <div data-testid="confirm">{props.message}</div> : null;
  },
}));

import { SidePanelProvider, useSidePanel, type SidePanelTab } from '@/contexts/SidePanelContext';
import { SidePanel } from '@/components/app/SidePanel';

const AGENT = 'a0000000-0000-4000-8000-000000000001';

const agentTab = (): SidePanelTab => ({
  id: `agent-${AGENT}`,
  label: 'Nova',
  icon: <span />,
  content: <div />,
});

function Opener({ tabs }: { tabs: SidePanelTab[] }) {
  const sp = useSidePanel();
  const done = React.useRef(false);
  React.useEffect(() => {
    if (done.current) return;
    done.current = true;
    tabs.forEach(t => sp.openTab(t));
  }, [sp, tabs]);
  return null;
}

const renderWith = (tabs: SidePanelTab[]) => render(
  <SidePanelProvider>
    <Opener tabs={tabs} />
    <SidePanel />
  </SidePanelProvider>,
);

/** The tab menu's Delete entry, or null when the menu does not offer one. */
function deleteEntry(): HTMLElement | null {
  const menu = screen.queryByTestId('tab-menu');
  // A missing menu would make every assertion below vacuously true, so prove it is
  // there: an agent tab always has one (it carries a "Go to page" entry whatever
  // the permission), and only the Delete entry is in question.
  expect(menu).not.toBeNull();
  const entries = Array.from(menu!.querySelectorAll('button'));
  return entries.find(b => (b.textContent || '').trim() === 'delete') ?? null;
}

beforeEach(() => {
  canMutate = true;
  modalProps = null;
  deleteAgent.mockReset().mockResolvedValue(undefined);
});
afterEach(cleanup);

describe('the tab delete entry respects who may mutate', () => {
  it('offers Delete to a member who may mutate', () => {
    renderWith([agentTab()]);
    expect(deleteEntry()).not.toBeNull();
  });

  it('offers NOTHING to a VIEWER, whose delete could only ever be refused', () => {
    // The pin used to hide this by accident on agent tabs; it was never a
    // permission check, and it never covered workflow / interface / table tabs.
    canMutate = false;
    renderWith([agentTab()]);
    expect(deleteEntry()).toBeNull();
  });
});

describe('a refused delete is not reported as a success', () => {
  it('keeps the confirmation open and says an error occurred', async () => {
    renderWith([agentTab()]);
    const entry = deleteEntry();
    expect(entry).not.toBeNull();
    act(() => { entry!.click(); });
    expect(screen.queryByTestId('confirm')).not.toBeNull();

    deleteAgent.mockRejectedValueOnce(new Error('HTTP 403'));
    await act(async () => { await modalProps!.onConfirm(); });

    // Pre-fix this modal closed exactly as it does on success, with nothing said.
    expect(modalProps!.isOpen).toBe(true);
    expect(modalProps!.isConfirming).toBe(false);
    // One translated sentence naming what was NOT deleted: the title still reads
    // "Delete agent", so a generic failure leaves the user unsure which resource
    // it is about, and a name concatenated to it would not be localized.
    expect(modalProps!.message).toBe('deleteFailed:Nova');
  });

  it('closes the confirmation on a real success', async () => {
    renderWith([agentTab()]);
    act(() => { deleteEntry()!.click(); });

    await act(async () => { await modalProps!.onConfirm(); });

    expect(deleteAgent).toHaveBeenCalledWith(AGENT);
    expect(modalProps!.isOpen).toBe(false);
  });
});
