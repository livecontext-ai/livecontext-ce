/**
 * @vitest-environment jsdom
 *
 * The unified side panel docks according to the org-aware position preference:
 *   - 'right'  -> WIDTH-sized, border on the left edge (historical behavior).
 *   - 'bottom' -> full-width, HEIGHT-sized, border on the top edge, content shrinks up.
 * This pins the container's box model for each mode (desktop; mobile keeps its overlay).
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  usePathname: () => '/app/c/conv-1',
}));
const mobile = vi.hoisted(() => ({ value: false }));
vi.mock('@/hooks/useMobileDetection', () => ({ useMobileDetection: () => mobile.value }));
vi.mock('@/contexts/SharedConversationContext', () => ({ useSharedConversation: () => null }));
vi.mock('@/components/app/AddTabPicker', () => ({ AddTabPicker: () => null }));
vi.mock('@/components/ui/PanelResizeHandle', () => ({ PanelResizeHandle: () => null }));
vi.mock('@/components/ui/BulkDeleteModal', () => ({ BulkDeleteModal: () => null }));
vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));

import { SidePanelProvider, useSidePanel, type SidePanelTab } from '@/contexts/SidePanelContext';
import { SidePanelLayoutProvider } from '@/contexts/SidePanelLayoutContext';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';
import { SidePanel } from '@/components/app/SidePanel';

beforeEach(() => {
  window.localStorage.clear();
  mobile.value = false;
  act(() => useCurrentOrgStore.getState().clear());
});
afterEach(cleanup);

function Opener() {
  const sp = useSidePanel();
  const done = React.useRef(false);
  React.useEffect(() => {
    if (done.current) return;
    done.current = true;
    sp.openTab({ id: 'workflow-1', label: 'WF', icon: <span />, content: <div>body</div> } as SidePanelTab);
  }, [sp]);
  return null;
}

function renderPanel() {
  return render(
    <SidePanelLayoutProvider>
      <SidePanelProvider>
        <Opener />
        <SidePanel />
      </SidePanelProvider>
    </SidePanelLayoutProvider>,
  );
}

/** The panel container is the flex-shrink-0 box that carries the dock border. */
function panelBox(container: HTMLElement): HTMLElement {
  return container.querySelector('.flex-shrink-0.border-theme') as HTMLElement;
}

describe('SidePanel dock position', () => {
  it('right (default): width-sized with a left border, not full width', () => {
    const { container } = renderPanel();
    const box = panelBox(container);
    expect(box).toBeTruthy();
    expect(box.classList.contains('border-l')).toBe(true);
    expect(box.classList.contains('border-t')).toBe(false);
    expect(box.classList.contains('w-full')).toBe(false);
    expect(box.style.width).not.toBe('');
    expect(box.style.height).toBe('');
  });

  it('bottom: full-width, height-sized with a top border', () => {
    window.localStorage.setItem('lc.sidePanel.position:personal', 'bottom');
    const { container } = renderPanel();
    const box = panelBox(container);
    expect(box).toBeTruthy();
    expect(box.classList.contains('w-full')).toBe(true);
    expect(box.classList.contains('border-t')).toBe(true);
    expect(box.classList.contains('border-l')).toBe(false);
    expect(box.style.height).not.toBe('');
    expect(box.style.width).toBe('');
  });

  it('bottom-full: renders the same bottom box-model as bottom (full width, top border)', () => {
    window.localStorage.setItem('lc.sidePanel.position:personal', 'bottom-full');
    const { container } = renderPanel();
    const box = panelBox(container);
    expect(box).toBeTruthy();
    expect(box.classList.contains('w-full')).toBe(true);
    expect(box.classList.contains('border-t')).toBe(true);
    expect(box.classList.contains('border-l')).toBe(false);
    expect(box.style.height).not.toBe('');
    expect(box.style.width).toBe('');
  });

  it('mobile: keeps the fixed right overlay even when the preference is bottom', () => {
    mobile.value = true;
    window.localStorage.setItem('lc.sidePanel.position:personal', 'bottom');
    const { container } = renderPanel();
    const box = panelBox(container);
    expect(box).toBeTruthy();
    // The 'bottom' dock is desktop-only: on mobile the panel stays the width-sized,
    // left-bordered, fixed overlay - not full-width / height-sized.
    expect(box.classList.contains('border-l')).toBe(true);
    expect(box.classList.contains('w-full')).toBe(false);
    expect(box.classList.contains('fixed')).toBe(true);
    expect(box.style.width).not.toBe('');
    expect(box.style.height).toBe('');
  });
});
