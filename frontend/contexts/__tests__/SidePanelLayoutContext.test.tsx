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
  const { position, setPosition, defaultPosition, setDefaultPosition, bottomMode, setBottomMode } = useSidePanelLayout();
  return (
    <div>
      <span data-testid="pos">{position}</span>
      {/* bottomMode + defaultPosition exposed as attributes so getByText('bottom-full')
          / getByText('right') in the position tests keep matching ONLY the buttons. */}
      <span data-testid="mode" data-mode={bottomMode} />
      <span data-testid="def" data-default={defaultPosition} />
      <button onClick={() => setPosition('bottom')}>bottom</button>
      <button onClick={() => setPosition('bottom-full')}>bottom-full</button>
      <button onClick={() => setPosition('right')}>right</button>
      <button onClick={() => setBottomMode('bottom')}>mode-bottom</button>
      <button onClick={() => setBottomMode('bottom-full')}>mode-bottom-full</button>
      <button onClick={() => setDefaultPosition('right')}>set-def-right</button>
      <button onClick={() => setDefaultPosition('bottom')}>set-def-bottom</button>
    </div>
  );
}

const KEY = (org: string) => `lc.sidePanel.position:${org}`;
const MODE_KEY = (org: string) => `lc.sidePanel.bottomMode:${org}`;
const DEF_KEY = (org: string) => `lc.sidePanel.defaultPosition:${org}`;

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

  it('persists and restores the bottom-full value', () => {
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);
    act(() => screen.getByText('bottom-full').click());
    expect(screen.getByTestId('pos')).toHaveTextContent('bottom-full');
    expect(window.localStorage.getItem(KEY('personal'))).toBe('bottom-full');
  });

  it('restores a stored bottom-full value on mount', () => {
    window.localStorage.setItem(KEY('personal'), 'bottom-full');
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);
    expect(screen.getByTestId('pos')).toHaveTextContent('bottom-full');
  });
});

describe('SidePanelLayoutContext bottomMode preference', () => {
  it('defaults to bottom-full when nothing is stored', () => {
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);
    expect(screen.getByTestId('mode')).toHaveAttribute('data-mode', 'bottom-full');
  });

  it('persists the choice to the active-org bottomMode bucket', () => {
    act(() => useCurrentOrgStore.getState().setCurrentOrg('org-a', 'OWNER'));
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);

    act(() => screen.getByText('mode-bottom').click());

    expect(screen.getByTestId('mode')).toHaveAttribute('data-mode', 'bottom');
    expect(window.localStorage.getItem(MODE_KEY('org-a'))).toBe('bottom');
  });

  it('re-hydrates per workspace on switch', () => {
    window.localStorage.setItem(MODE_KEY('org-a'), 'bottom');
    act(() => useCurrentOrgStore.getState().setCurrentOrg('org-a', 'OWNER'));
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);
    expect(screen.getByTestId('mode')).toHaveAttribute('data-mode', 'bottom');

    act(() => useCurrentOrgStore.getState().setCurrentOrg('org-b', 'MEMBER'));
    expect(screen.getByTestId('mode')).toHaveAttribute('data-mode', 'bottom-full');
  });

  it('seeds from a legacy stored bottom position when no bottomMode is stored', () => {
    // Before bottomMode existed, the Settings dock select stored the chosen
    // bottom variant in the position bucket - honor it.
    window.localStorage.setItem(KEY('personal'), 'bottom');
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);
    expect(screen.getByTestId('mode')).toHaveAttribute('data-mode', 'bottom');
  });

  it('makes the legacy seed STICKY (persists it), so a later dock change cannot revert it', () => {
    // Regression: without the write-through, dock -> 'right' overwrote the position
    // bucket, and the next mount silently fell back to the 'bottom-full' default.
    window.localStorage.setItem(KEY('personal'), 'bottom');
    const first = render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);
    expect(window.localStorage.getItem(MODE_KEY('personal'))).toBe('bottom');

    act(() => screen.getByText('right').click()); // user docks right afterwards
    first.unmount();

    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);
    expect(screen.getByTestId('mode')).toHaveAttribute('data-mode', 'bottom');
  });

  it('an explicitly stored bottomMode WINS over a legacy bottom position', () => {
    window.localStorage.setItem(MODE_KEY('personal'), 'bottom-full');
    window.localStorage.setItem(KEY('personal'), 'bottom');
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);
    expect(screen.getByTestId('mode')).toHaveAttribute('data-mode', 'bottom-full');
  });

  it('does NOT seed from a stored right position', () => {
    window.localStorage.setItem(KEY('personal'), 'right');
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);
    expect(screen.getByTestId('mode')).toHaveAttribute('data-mode', 'bottom-full');
  });

  it('ignores a corrupt stored bottomMode and falls back to the default', () => {
    window.localStorage.setItem(MODE_KEY('personal'), 'right'); // 'right' is not a bottom mode
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);
    expect(screen.getByTestId('mode')).toHaveAttribute('data-mode', 'bottom-full');
  });

  it('repositions an ACTIVE bottom dock when the mode changes (WYSIWYG)', () => {
    window.localStorage.setItem(KEY('personal'), 'bottom-full');
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);
    expect(screen.getByTestId('pos')).toHaveTextContent('bottom-full');

    act(() => screen.getByText('mode-bottom').click());

    expect(screen.getByTestId('pos')).toHaveTextContent(/^bottom$/);
    expect(window.localStorage.getItem(KEY('personal'))).toBe('bottom');
  });

  it('leaves a right dock untouched when the mode changes', () => {
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);
    expect(screen.getByTestId('pos')).toHaveTextContent('right');

    act(() => screen.getByText('mode-bottom').click());

    expect(screen.getByTestId('pos')).toHaveTextContent('right');
    expect(window.localStorage.getItem(KEY('personal'))).toBeNull();
  });
});

describe('SidePanelLayoutContext defaultPosition preference', () => {
  it('defaults to right when nothing is stored', () => {
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);
    expect(screen.getByTestId('def')).toHaveAttribute('data-default', 'right');
  });

  it('persists the choice to the active-org defaultPosition bucket', () => {
    act(() => useCurrentOrgStore.getState().setCurrentOrg('org-a', 'OWNER'));
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);

    act(() => screen.getByText('set-def-bottom').click());

    expect(screen.getByTestId('def')).toHaveAttribute('data-default', 'bottom');
    expect(window.localStorage.getItem(DEF_KEY('org-a'))).toBe('bottom');
  });

  it('applies the chosen default to the ACTIVE dock immediately (WYSIWYG) via the bottomMode variant', () => {
    // bottomMode default is 'bottom-full', so a 'bottom' default lands on bottom-full.
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);
    expect(screen.getByTestId('pos')).toHaveTextContent('right');

    act(() => screen.getByText('set-def-bottom').click());

    expect(screen.getByTestId('pos')).toHaveTextContent('bottom-full');
  });

  it('resolves a bottom default through a content-width bottomMode', () => {
    window.localStorage.setItem(MODE_KEY('personal'), 'bottom');
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);

    act(() => screen.getByText('set-def-bottom').click());

    expect(screen.getByTestId('pos')).toHaveTextContent(/^bottom$/);
  });

  it('opens the panel at the stored default position on mount (bottom -> bottomMode variant)', () => {
    window.localStorage.setItem(DEF_KEY('personal'), 'bottom');
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);
    expect(screen.getByTestId('def')).toHaveAttribute('data-default', 'bottom');
    expect(screen.getByTestId('pos')).toHaveTextContent('bottom-full');
  });

  it('opens at content-width bottom on mount when default is bottom and bottomMode is content', () => {
    window.localStorage.setItem(DEF_KEY('personal'), 'bottom');
    window.localStorage.setItem(MODE_KEY('personal'), 'bottom');
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);
    expect(screen.getByTestId('pos')).toHaveTextContent(/^bottom$/);
  });

  it('lets the default preference WIN over a last-used stored position', () => {
    // The stable preference is authoritative on mount, overriding a sticky dock.
    window.localStorage.setItem(DEF_KEY('personal'), 'right');
    window.localStorage.setItem(KEY('personal'), 'bottom-full');
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);
    expect(screen.getByTestId('pos')).toHaveTextContent('right');
  });

  it('falls back to the last-used stored position when no default is set (backward compatible)', () => {
    window.localStorage.setItem(KEY('personal'), 'bottom');
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);
    expect(screen.getByTestId('def')).toHaveAttribute('data-default', 'right');
    expect(screen.getByTestId('pos')).toHaveTextContent('bottom');
  });

  it('re-hydrates the default per workspace on switch', () => {
    window.localStorage.setItem(DEF_KEY('org-a'), 'bottom');
    act(() => useCurrentOrgStore.getState().setCurrentOrg('org-a', 'OWNER'));
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);
    expect(screen.getByTestId('def')).toHaveAttribute('data-default', 'bottom');
    expect(screen.getByTestId('pos')).toHaveTextContent('bottom-full');

    // Org B never picked a default -> back to 'right'.
    act(() => useCurrentOrgStore.getState().setCurrentOrg('org-b', 'MEMBER'));
    expect(screen.getByTestId('def')).toHaveAttribute('data-default', 'right');
    expect(screen.getByTestId('pos')).toHaveTextContent('right');

    act(() => useCurrentOrgStore.getState().setCurrentOrg('org-a', 'OWNER'));
    expect(screen.getByTestId('def')).toHaveAttribute('data-default', 'bottom');
  });

  it('ignores a corrupt stored default and falls back to the last-used position', () => {
    window.localStorage.setItem(DEF_KEY('personal'), 'sideways');
    window.localStorage.setItem(KEY('personal'), 'bottom');
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);
    expect(screen.getByTestId('def')).toHaveAttribute('data-default', 'right');
    expect(screen.getByTestId('pos')).toHaveTextContent('bottom');
  });

  it('WYSIWYG: switching the default back to right returns an active bottom dock to right', () => {
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);
    act(() => screen.getByText('set-def-bottom').click());
    expect(screen.getByTestId('pos')).toHaveTextContent('bottom-full');

    act(() => screen.getByText('set-def-right').click());

    expect(screen.getByTestId('pos')).toHaveTextContent('right');
    expect(window.localStorage.getItem(DEF_KEY('personal'))).toBe('right');
  });

  it('changing the default leaves the bottomMode preference untouched', () => {
    render(<SidePanelLayoutProvider><Consumer /></SidePanelLayoutProvider>);
    expect(screen.getByTestId('mode')).toHaveAttribute('data-mode', 'bottom-full');

    act(() => screen.getByText('set-def-bottom').click());

    // Default position and bottom style are independent preferences.
    expect(screen.getByTestId('mode')).toHaveAttribute('data-mode', 'bottom-full');
    expect(window.localStorage.getItem(MODE_KEY('personal'))).toBeNull();
  });
});

describe('useSidePanelLayoutSafe outside a provider', () => {
  function SafeConsumer() {
    const { position, defaultPosition, bottomMode, setBottomMode, setDefaultPosition } = useSidePanelLayoutSafe();
    return (
      <div>
        <span data-testid="safe">{position}</span>
        <span data-testid="safe-def" data-default={defaultPosition} />
        <span data-testid="safe-mode" data-mode={bottomMode} />
        <button onClick={() => setBottomMode('bottom')}>safe-set-mode</button>
        <button onClick={() => setDefaultPosition('bottom')}>safe-set-def</button>
      </div>
    );
  }

  it('returns the right default and a no-op setter', () => {
    render(<SafeConsumer />);
    expect(screen.getByTestId('safe')).toHaveTextContent('right');
  });

  it('returns the right defaultPosition default and a no-op setDefaultPosition', () => {
    render(<SafeConsumer />);
    expect(screen.getByTestId('safe-def')).toHaveAttribute('data-default', 'right');

    act(() => screen.getByText('safe-set-def').click());

    expect(screen.getByTestId('safe-def')).toHaveAttribute('data-default', 'right');
    expect(window.localStorage.getItem(DEF_KEY('personal'))).toBeNull();
  });

  it('returns the bottom-full bottomMode default and a no-op setBottomMode', () => {
    render(<SafeConsumer />);
    expect(screen.getByTestId('safe-mode')).toHaveAttribute('data-mode', 'bottom-full');

    act(() => screen.getByText('safe-set-mode').click());

    // No provider: the setter is a no-op and nothing is persisted.
    expect(screen.getByTestId('safe-mode')).toHaveAttribute('data-mode', 'bottom-full');
    expect(window.localStorage.getItem(MODE_KEY('personal'))).toBeNull();
  });
});
