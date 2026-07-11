/**
 * @vitest-environment jsdom
 *
 * AppShell arranges sidebar + content + side panel per the dock-position preference.
 * The panel is mounted in exactly ONE place per mode, and THAT is what these tests pin:
 *   - 'right'       -> row root; panel inside the content region (flex-row), beside content.
 *   - 'bottom'      -> row root; content region is flex-col; panel under the content only
 *                      (region does NOT contain the sidebar).
 *   - 'bottom-full' -> column root; panel is a direct child of the root, a SIBLING of the
 *                      row that holds the sidebar (so it spans full width, under the sidebar).
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';

vi.mock('@/components/app/AppSidebar', () => ({ AppSidebar: () => <div data-testid="sidebar" /> }));
vi.mock('@/components/app/AppHeader', () => ({ AppHeader: () => <div data-testid="header" /> }));
vi.mock('@/components/app/SidePanel', () => ({ SidePanel: () => <div data-testid="side-panel" /> }));
vi.mock('@/contexts/ConversationActivityContext', () => ({
  ConversationActivityProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

import { AppShell } from '../AppShell';
import { SidePanelLayoutProvider } from '@/contexts/SidePanelLayoutContext';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';

beforeEach(() => {
  window.localStorage.clear();
  act(() => useCurrentOrgStore.getState().clear());
});
afterEach(cleanup);

function renderShell(position?: string) {
  if (position) window.localStorage.setItem('lc.sidePanel.position:personal', position);
  const { container } = render(
    <SidePanelLayoutProvider>
      <AppShell><div>page</div></AppShell>
    </SidePanelLayoutProvider>,
  );
  return {
    root: container.firstChild as HTMLElement,
    sidebar: screen.getByTestId('sidebar'),
    header: screen.getByTestId('header'),
    panel: screen.getByTestId('side-panel'),
  };
}

/** The flex-1 region wrapping the content (and, when docked there, the panel). */
function region(root: HTMLElement): HTMLElement {
  return root.querySelector('.flex-1.flex.min-h-0') as HTMLElement;
}

describe('AppShell dock arrangement', () => {
  it('right: row root; panel beside content in a flex-row region (not with the sidebar)', () => {
    const { root, sidebar, header, panel } = renderShell(); // default 'right'
    expect(root.classList.contains('flex-col')).toBe(false);
    const reg = region(root);
    expect(reg.classList.contains('flex-row')).toBe(true);
    expect(reg.contains(header)).toBe(true);
    expect(reg.contains(panel)).toBe(true);
    expect(reg.contains(sidebar)).toBe(false); // sidebar is a sibling of the region
  });

  it('bottom: row root; content region is flex-col and holds the panel, not the sidebar', () => {
    const { root, sidebar, header, panel } = renderShell('bottom');
    expect(root.classList.contains('flex-col')).toBe(false);
    const reg = region(root);
    expect(reg.classList.contains('flex-col')).toBe(true);
    expect(reg.contains(header)).toBe(true);
    expect(reg.contains(panel)).toBe(true);
    expect(reg.contains(sidebar)).toBe(false);
  });

  it('bottom-full: column root; panel is a root sibling of the sidebar+content row', () => {
    const { root, sidebar, panel } = renderShell('bottom-full');
    expect(root.classList.contains('flex-col')).toBe(true);
    // The inner row holds the sidebar; it must NOT hold the panel (panel spans full width).
    const innerRow = sidebar.closest('.flex.flex-1') as HTMLElement;
    expect(innerRow).toBeTruthy();
    expect(innerRow.contains(panel)).toBe(false);
    // The panel is a direct child of the root column (sibling of the inner row).
    expect(panel.parentElement).toBe(root);
    expect(root.contains(sidebar)).toBe(true);
  });
});
