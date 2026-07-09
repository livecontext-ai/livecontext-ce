/**
 * @vitest-environment jsdom
 *
 * AppContentRegion is the flex region to the right of the sidebar holding
 * (header + page) + the side panel. Its direction follows the dock preference:
 * 'right' -> flex-row (panel to the side, content shrinks in width); 'bottom' ->
 * flex-col (panel under the content, content shrinks in height). This direction
 * flip - together with min-h-0 on the vertical chain - is what actually lets the
 * main content give up vertical space to a bottom-docked panel.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';

// Stub the heavy children - we only assert on the region wrapper's flex direction.
vi.mock('@/components/app/AppHeader', () => ({ AppHeader: () => <div data-testid="header" /> }));
vi.mock('@/components/app/SidePanel', () => ({ SidePanel: () => <div data-testid="panel" /> }));
vi.mock('@/contexts/ConversationActivityContext', () => ({
  ConversationActivityProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

import { AppContentRegion } from '../AppContentRegion';
import { SidePanelLayoutProvider } from '@/contexts/SidePanelLayoutContext';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';

beforeEach(() => {
  window.localStorage.clear();
  act(() => useCurrentOrgStore.getState().clear());
});
afterEach(cleanup);

function regionEl(container: HTMLElement): HTMLElement {
  return container.querySelector('.flex-1.flex.min-h-0') as HTMLElement;
}

describe('AppContentRegion flex direction', () => {
  it('right (default): row so the panel sits beside the content', () => {
    const { container } = render(
      <SidePanelLayoutProvider>
        <AppContentRegion><div>page</div></AppContentRegion>
      </SidePanelLayoutProvider>,
    );
    const region = regionEl(container);
    expect(region.classList.contains('flex-row')).toBe(true);
    expect(region.classList.contains('flex-col')).toBe(false);
  });

  it('bottom: column so the panel docks under the content (content shrinks in height)', () => {
    window.localStorage.setItem('lc.sidePanel.position:personal', 'bottom');
    const { container } = render(
      <SidePanelLayoutProvider>
        <AppContentRegion><div>page</div></AppContentRegion>
      </SidePanelLayoutProvider>,
    );
    const region = regionEl(container);
    expect(region.classList.contains('flex-col')).toBe(true);
    expect(region.classList.contains('flex-row')).toBe(false);
    // The vertical shrink chain must carry min-h-0 or the content can't yield space.
    expect(region.classList.contains('min-h-0')).toBe(true);
  });
});
