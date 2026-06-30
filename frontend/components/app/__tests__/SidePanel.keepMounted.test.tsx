/**
 * @vitest-environment jsdom
 *
 * Regression test for the multi-application side-panel bug ("only the last app
 * resolved"). Root cause (b): application tabs were not `keepMounted`, so only
 * the ACTIVE tab's content was mounted - an inactive app's data fetch was
 * cancelled on unmount and never resolved.
 *
 * This pins the SidePanel rendering contract that DELIVERS the fix: when tabs
 * are opened with `keepMounted: true`, ALL of their content stays mounted in the
 * DOM (one visible, the rest display:none); without it, only the active tab's
 * content mounts. AppHeader opens `application` tabs with keepMounted:true, so
 * this 2-vs-1 difference is exactly what keeps both apps alive.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  usePathname: () => '/app/c/conv-1',
}));
vi.mock('@/hooks/useMobileDetection', () => ({ useMobileDetection: () => false }));
vi.mock('@/hooks/useMouseResize', () => ({
  useMouseResize: () => ({ isResizing: false, startResize: vi.fn(), hasManuallyResizedRef: { current: false } }),
}));
vi.mock('@/contexts/SharedConversationContext', () => ({ useSharedConversation: () => null }));
vi.mock('@/components/app/AddTabPicker', () => ({ AddTabPicker: () => null }));
vi.mock('@/components/ui/PanelResizeHandle', () => ({ PanelResizeHandle: () => null }));
vi.mock('@/components/ui/BulkDeleteModal', () => ({ BulkDeleteModal: () => null }));
vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));

import { SidePanelProvider, useSidePanel, type SidePanelTab } from '@/contexts/SidePanelContext';
import { SidePanel } from '@/components/app/SidePanel';

afterEach(cleanup);

const tab = (id: string, body: string, keepMounted: boolean): SidePanelTab => ({
  id,
  label: id,
  icon: <span />,
  keepMounted,
  content: <div data-testid="app-content">{body}</div>,
});

function Opener({ tabs }: { tabs: SidePanelTab[] }) {
  const sp = useSidePanel();
  const done = React.useRef(false);
  React.useEffect(() => {
    if (done.current) return;
    done.current = true;
    tabs.forEach((t) => sp.openTab(t));
  }, [sp, tabs]);
  return null;
}

function renderWith(tabs: SidePanelTab[]) {
  return render(
    <SidePanelProvider>
      <Opener tabs={tabs} />
      <SidePanel />
    </SidePanelProvider>,
  );
}

describe('SidePanel keepMounted (multi-app)', () => {
  it('keeps BOTH keepMounted app tabs mounted (one hidden) so each resolves', () => {
    const { container } = renderWith([
      tab('application-pub-a', 'A', true),
      tab('application-pub-b', 'B', true),
    ]);
    // Both contents are in the DOM even though only one tab is active.
    expect(screen.getAllByTestId('app-content')).toHaveLength(2);
    // Exactly one keepMounted wrapper is hidden (the inactive tab).
    const hidden = container.querySelectorAll('div[style*="display: none"]');
    expect(hidden).toHaveLength(1);
  });

  it('without keepMounted, only the active tab content mounts (the pre-fix bug)', () => {
    renderWith([
      tab('application-pub-a', 'A', false),
      tab('application-pub-b', 'B', false),
    ]);
    expect(screen.getAllByTestId('app-content')).toHaveLength(1);
  });
});

/**
 * Regression test for the epoch-bleed bug: opening app A in the side panel
 * (viewed epoch 19) then switching to a different app B (max epoch 0) showed
 * app A's epoch 19 for app B too → no items → base template.
 *
 * Root cause: the non-keepMounted active slot was rendered WITHOUT a React key,
 * so two app tabs (each a runId-scoped id) reconciled onto ONE
 * ApplicationTabContent instance - its mount-initialised viewed-epoch state
 * survived the app switch. EpochProbe below models that "capture at mount,
 * never reset on prop change" state. Keying the slot by tab id remounts the
 * content per app, so the probe re-captures the new app's value.
 */
describe('SidePanel non-keepMounted app switch (epoch bleed)', () => {
  // Captures its appId AT MOUNT and never updates it on a prop change - the
  // same shape as ApplicationTabContent.localViewingEpoch.
  function EpochProbe({ appId }: { appId: string }) {
    const [capturedAtMount] = React.useState(appId);
    return <div data-testid="probe">{capturedAtMount}</div>;
  }

  let sp: ReturnType<typeof useSidePanel> | null = null;
  function Capture() {
    sp = useSidePanel();
    return null;
  }

  const appTab = (id: string, appId: string): SidePanelTab => ({
    id,
    label: id,
    icon: <span />,
    keepMounted: false,
    content: <EpochProbe appId={appId} />,
  });

  it('remounts the content when switching app tabs so per-app state does not bleed', () => {
    render(
      <SidePanelProvider>
        <Capture />
        <SidePanel />
      </SidePanelProvider>,
    );

    act(() => {
      sp!.openTab(appTab('application-pub-a', 'A'));
      sp!.openTab(appTab('application-pub-b', 'B'));
    });

    // Start on app A → probe captures 'A'.
    act(() => sp!.setActiveTab('application-pub-a'));
    expect(screen.getByTestId('probe').textContent).toBe('A');

    // Switch to app B. Post-fix: the keyed slot remounts EpochProbe → 'B'.
    // Pre-fix (no key): React reconciled one instance → it would still read 'A'.
    act(() => sp!.setActiveTab('application-pub-b'));
    expect(screen.getByTestId('probe').textContent).toBe('B');
  });
});
