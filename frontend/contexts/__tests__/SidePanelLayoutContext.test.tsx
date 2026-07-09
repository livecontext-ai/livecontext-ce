/**
 * @vitest-environment jsdom
 *
 * SidePanelLayoutContext holds the (org-aware) dock position of the unified side
 * panel. These tests pin: the 'right' default, localStorage persistence, PER-ORG
 * isolation (a choice in Org A never bleeds into Org B), re-hydration on workspace
 * switch, and the safe hook's outside-provider fallback.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import * as React from 'react';

import {
  SidePanelLayoutProvider,
  useSidePanelLayout,
  useSidePanelLayoutSafe,
} from '../SidePanelLayoutContext';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';

function Consumer() {
  const { position, setPosition } = useSidePanelLayout();
  return (
    <div>
      <span data-testid="pos">{position}</span>
      <button onClick={() => setPosition('bottom')}>bottom</button>
      <button onClick={() => setPosition('right')}>right</button>
    </div>
  );
}

const KEY = (org: string) => `lc.sidePanel.position:${org}`;

beforeEach(() => {
  window.localStorage.clear();
  act(() => useCurrentOrgStore.getState().clear());
});

afterEach(() => {
  cleanup();
});

describe('SidePanelLayoutContext', () => {
  it('defaults to right when nothing is stored', () => {
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);
    expect(screen.getByTestId('pos')).toHaveTextContent('right');
  });

  it('persists the choice to the active-org localStorage bucket', () => {
    act(() => useCurrentOrgStore.getState().setCurrentOrg('org-a', 'OWNER'));
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);

    act(() => screen.getByText('bottom').click());

    expect(screen.getByTestId('pos')).toHaveTextContent('bottom');
    expect(window.localStorage.getItem(KEY('org-a'))).toBe('bottom');
  });

  it('keeps each workspace independent and re-hydrates on switch', () => {
    // Org A saved 'bottom' previously; Org B has nothing.
    window.localStorage.setItem(KEY('org-a'), 'bottom');

    act(() => useCurrentOrgStore.getState().setCurrentOrg('org-a', 'OWNER'));
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);
    expect(screen.getByTestId('pos')).toHaveTextContent('bottom');

    // Switch to Org B -> falls back to the default, does NOT inherit Org A's value.
    act(() => useCurrentOrgStore.getState().setCurrentOrg('org-b', 'MEMBER'));
    expect(screen.getByTestId('pos')).toHaveTextContent('right');

    // Switch back to Org A -> restores its saved value.
    act(() => useCurrentOrgStore.getState().setCurrentOrg('org-a', 'OWNER'));
    expect(screen.getByTestId('pos')).toHaveTextContent('bottom');
  });

  it('uses the personal bucket when no workspace is active', () => {
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);
    act(() => screen.getByText('bottom').click());
    expect(window.localStorage.getItem(KEY('personal'))).toBe('bottom');
  });

  it('ignores a corrupt stored value and falls back to the default', () => {
    window.localStorage.setItem(KEY('personal'), 'sideways');
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);
    expect(screen.getByTestId('pos')).toHaveTextContent('right');
  });
});

describe('useSidePanelLayoutSafe outside a provider', () => {
  function SafeConsumer() {
    const { position } = useSidePanelLayoutSafe();
    return <span data-testid="safe">{position}</span>;
  }

  it('returns the right default and a no-op setter', () => {
    render(<SafeConsumer />);
    expect(screen.getByTestId('safe')).toHaveTextContent('right');
  });
});
