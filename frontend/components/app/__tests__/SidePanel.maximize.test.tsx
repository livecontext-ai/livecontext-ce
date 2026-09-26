/**
 * @vitest-environment jsdom
 *
 * Full screen for the unified side panel: a control next to Detach that paints
 * the panel over the whole viewport.
 *
 * Like detaching, it must be a MODE FLIP and nothing else - the same container,
 * the same React subtree - because the panel holds live state (a running canvas,
 * an SSE stream, an interface iframe) that a re-mount would destroy. These pin
 * that, the box it paints, the controls that step aside while it is on, and the
 * three ways out (the button, Escape, closing the panel).
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';

// The REAL message bundle, not an identity stub: `maximize` and `restoreSize`
// are new keys, and a stub returns the key itself for a key that does not exist,
// so a typo or a missing entry would still read as a pass.
vi.mock('next-intl', async () => {
  const en = (await import('@/messages/en.json')).default as Record<string, unknown>;
  return {
    useTranslations: (ns: string) => {
      const section = ns.split('.').reduce<any>((acc, k) => acc?.[k], en);
      return (key: string) => {
        const value = section?.[key];
        if (typeof value !== 'string') throw new Error(`missing i18n key: ${ns}.${key}`);
        return value;
      };
    },
  };
});
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  usePathname: () => '/app/c/conv-1',
}));
const mobile = vi.hoisted(() => ({ value: false }));
vi.mock('@/hooks/useMobileDetection', () => ({ useMobileDetection: () => mobile.value }));
const shared = vi.hoisted(() => ({ value: null as unknown }));
vi.mock('@/contexts/SharedConversationContext', () => ({ useSharedConversation: () => shared.value }));
vi.mock('@/components/app/AddTabPicker', () => ({ AddTabPicker: () => null }));
vi.mock('@/components/ui/PanelResizeHandle', () => ({
  PanelResizeHandle: () => <div data-testid="edge-resize-handle" />,
}));
vi.mock('@/components/ui/BulkDeleteModal', () => ({ BulkDeleteModal: () => null }));
vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));

import { SidePanelProvider, useSidePanel, type SidePanelTab } from '@/contexts/SidePanelContext';
import { SidePanelLayoutProvider, useSidePanelLayout } from '@/contexts/SidePanelLayoutContext';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';
import { SidePanel } from '@/components/app/SidePanel';

function setViewport(width: number, height: number) {
  Object.defineProperty(window, 'innerWidth', { value: width, configurable: true, writable: true });
  Object.defineProperty(window, 'innerHeight', { value: height, configurable: true, writable: true });
}

beforeEach(() => {
  window.localStorage.clear();
  mobile.value = false;
  shared.value = null;
  setViewport(1600, 900);
  act(() => useCurrentOrgStore.getState().clear());
});
afterEach(cleanup);

/** Counts how many times the TAB CONTENT is mounted - the panel's own subtree.
 *  A counter on the container would sit at a fixed JSX position and prove nothing. */
const contentMounts = { count: 0 };
function TabBody() {
  React.useEffect(() => { contentMounts.count += 1; }, []);
  return <div>body</div>;
}

function Opener() {
  const sp = useSidePanel();
  const done = React.useRef(false);
  React.useEffect(() => {
    if (done.current) return;
    done.current = true;
    sp.openTab({
      id: 'workflow-1', label: 'WF', icon: <span />, content: <TabBody />, keepMounted: true,
    } as SidePanelTab);
  }, [sp]);
  return null;
}

/**
 * Records the panel's inline style once per COMMIT.
 *
 * A MutationObserver cannot do this job: jsdom emits one attribute record per
 * style PROPERTY write, so `width` and `transition` land as two records inside
 * a single commit and the sequence looks identical whether the transition was
 * suppressed or not - which is how the first version of these tests passed on
 * the very bug they are named for. A layout effect with no dep array runs after
 * the DOM mutations of every commit this component re-renders in, and the
 * panel's mode lives in the context above it, so it re-renders in the commit
 * that matters.
 */
const commits: string[] = [];
function CommitProbe() {
  // Consumes the context on purpose: `children` is a stable element prop, so a
  // provider state change alone does not re-render a sibling that reads nothing.
  // Without this the probe recorded only its own mount.
  useSidePanel();
  React.useLayoutEffect(() => {
    const el = document.querySelector('[data-testid="side-panel"]');
    if (el) commits.push(el.getAttribute('style') ?? '');
  });
  return null;
}

/** The routes into the panel's state that other surfaces in the app use. */
function PanelControls() {
  const sp = useSidePanel();
  const { setPosition } = useSidePanelLayout();
  return (
    <>
      <button type="button" data-testid="close-panel" onClick={() => sp.close()} />
      <button type="button" data-testid="open-panel" onClick={() => sp.open()} />
      <button type="button" data-testid="dock-to-bottom" onClick={() => setPosition('bottom')} />
      {/* Stand-ins for the header's detach control and for any surface that
          shades the window, so the "detached AND full screen" combination can be
          driven without going through the panel's own chrome. */}
      <button type="button" data-testid="dock-to-floating" onClick={() => setPosition('floating')} />
      <button type="button" data-testid="shade-panel" onClick={() => sp.setCollapsed(true)} />
      {/* A shaded window renders no tab bar, so it has no maximize button: any
          future caller asking for full screen reaches the context like this. */}
      <button type="button" data-testid="maximize-via-context" onClick={() => sp.setMaximized(true)} />
      <span data-testid="shaded-flag">{String(sp.collapsed)}</span>
    </>
  );
}

function renderPanel() {
  contentMounts.count = 0;
  commits.length = 0;
  return render(
    <SidePanelLayoutProvider>
      <SidePanelProvider>
        <PanelControls />
        <Opener />
        <SidePanel />
        <CommitProbe />
      </SidePanelProvider>
    </SidePanelLayoutProvider>,
  );
}

const panelBox = () => screen.getByTestId('side-panel');
const maximizeButton = () => screen.queryByTestId('side-panel-maximize');
const detachButton = () => screen.queryByTestId('side-panel-detach');
const click = (el: HTMLElement) => act(() => { fireEvent.click(el); });
const pressEscape = () => act(() => { fireEvent.keyDown(window, { key: 'Escape' }); });

describe('SidePanel - full screen', () => {
  it('offers the control next to Detach, off by default', () => {
    renderPanel();
    expect(maximizeButton()).toBeTruthy();
    expect(maximizeButton()!.getAttribute('aria-pressed')).toBe('false');
    expect(panelBox().getAttribute('data-side-panel-maximized')).toBeNull();
    expect(panelBox().classList.contains('border-l')).toBe(true);
  });

  it('paints the whole viewport, dropping the dock border it no longer has an edge for', () => {
    renderPanel();
    click(maximizeButton()!);

    const box = panelBox();
    expect(box.getAttribute('data-side-panel-maximized')).toBe('true');
    expect(box.classList.contains('fixed')).toBe(true);
    expect(box.classList.contains('inset-0')).toBe(true);
    // The dock border is REMOVED, not cancelled: a `border-0` on top of it would
    // come down to which utility Tailwind happened to emit last.
    expect(box.classList.contains('border-l')).toBe(false);
    // No explicit box: `inset-0` sizes it, and a width would reinstate the dock's.
    expect(box.style.width).toBe('');
    expect(box.style.height).toBe('');
  });

  it('keeps the SAME container element and does NOT re-mount the panel content', () => {
    // The whole point: a re-mount here takes a running canvas, an SSE stream or an
    // interface iframe with it. AppShell mounts the panel in a different branch per
    // dock, so a full-screen mode built as a new dock would have done exactly that.
    renderPanel();
    const before = panelBox();
    const mountsBefore = contentMounts.count;

    click(maximizeButton()!);
    expect(panelBox()).toBe(before);

    click(maximizeButton()!);
    expect(panelBox()).toBe(before);
    expect(contentMounts.count).toBe(mountsBefore);
  });

  it('restores the dock it came from, not a default one', () => {
    renderPanel();
    click(screen.getByTestId('dock-to-bottom'));
    expect(panelBox().classList.contains('border-t')).toBe(true);

    click(maximizeButton()!);
    click(maximizeButton()!);

    expect(panelBox().getAttribute('data-side-panel-maximized')).toBeNull();
    expect(panelBox().classList.contains('border-t')).toBe(true);
  });

  it('stands the resize handle and the Detach control down while it is on', () => {
    // There is no edge to drag and no window to float: leaving either on offer
    // would move geometry the user cannot see and surprise them on restore.
    renderPanel();
    expect(screen.queryByTestId('edge-resize-handle')).toBeTruthy();
    // Positive control: without it a renamed test id would make the assertion
    // below pass on a Detach button that is still on screen.
    expect(detachButton()).toBeTruthy();

    click(maximizeButton()!);

    expect(screen.queryByTestId('edge-resize-handle')).toBeNull();
    expect(detachButton()).toBeNull();
    expect(maximizeButton()).toBeTruthy();
    expect(maximizeButton()!.getAttribute('aria-pressed')).toBe('true');
  });

  it('leaves full screen on Escape', () => {
    renderPanel();
    click(maximizeButton()!);

    pressEscape();

    expect(panelBox().getAttribute('data-side-panel-maximized')).toBeNull();
  });

  // Every overlay role the app's primitives render owns Escape while it is open.
  // Radix dismisses its layers WITHOUT calling preventDefault, so the roles have
  // to be named: `listbox` is a Select and `menu` a DropdownMenu, and the
  // inspector - the main reason to go full screen - is mostly Selects. Dismissing
  // the whole view along with the listbox the user was closing is worse than not
  // reacting at all.
  it.each(['dialog', 'alertdialog', 'listbox', 'menu'])(
    'leaves Escape alone while a %s is open - that layer owns the key',
    (role) => {
      renderPanel();
      click(maximizeButton()!);
      const overlay = document.createElement('div');
      overlay.setAttribute('role', role);
      document.body.appendChild(overlay);

      pressEscape();

      expect(panelBox().getAttribute('data-side-panel-maximized')).toBe('true');
      overlay.remove();
    },
  );

  it('DOES leave full screen on Escape once the overlay is gone', () => {
    // The positive control for the case above: the guard must not be a permanent
    // veto that happens to look right because some stray node matched.
    renderPanel();
    click(maximizeButton()!);
    const overlay = document.createElement('div');
    overlay.setAttribute('role', 'listbox');
    document.body.appendChild(overlay);
    pressEscape();
    expect(panelBox().getAttribute('data-side-panel-maximized')).toBe('true');

    overlay.remove();
    pressEscape();

    expect(panelBox().getAttribute('data-side-panel-maximized')).toBeNull();
  });

  it('marks the Escape it used as handled, so nothing else reacts to it', () => {
    // A chat inside the panel also listens for Escape (to stop its answer). Leaving full
    // screen must not be read as a second request by anything after the panel.
    renderPanel();
    click(maximizeButton()!);
    const ev = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });

    act(() => { window.dispatchEvent(ev); });

    expect(panelBox().getAttribute('data-side-panel-maximized')).toBeNull();
    expect(ev.defaultPrevented).toBe(true);
  });

  it('leaves an already-handled Escape alone', () => {
    renderPanel();
    click(maximizeButton()!);

    act(() => {
      const ev = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
      ev.preventDefault();
      window.dispatchEvent(ev);
    });

    expect(panelBox().getAttribute('data-side-panel-maximized')).toBe('true');
  });

  it('does not come back full screen after the panel is closed and reopened', () => {
    // Stated as a rule about the closed state rather than as a close-to-restore
    // transition: a missed close route would strand the flag and cover the whole
    // app on the next open with no control having asked for it.
    renderPanel();
    click(maximizeButton()!);
    click(screen.getByTestId('close-panel'));
    click(screen.getByTestId('open-panel'));

    expect(panelBox().getAttribute('data-side-panel-maximized')).toBeNull();
  });

  it('takes over a DETACHED window too, standing its whole chrome down', () => {
    // Every floating gate is its own `!isMaximized`, and all four were untested:
    // deleting them left the suite green while a full-screen panel still painted
    // eight resize grips and a drag handle over nothing.
    renderPanel();
    click(screen.getByTestId('dock-to-floating'));
    expect(panelBox().getAttribute('data-side-panel-floating')).toBe('true');
    expect(document.querySelectorAll('[data-side-panel-resize]').length).toBeGreaterThan(0);
    expect(document.querySelector('[data-side-panel-titlebar]')).toBeTruthy();
    expect(screen.queryByTestId('side-panel-collapse')).toBeTruthy();

    click(maximizeButton()!);

    expect(panelBox().classList.contains('inset-0')).toBe(true);
    // A full-screen window has no edges to grab, nowhere to be dragged to, and
    // nothing to shade: none of its chrome means anything here.
    expect(document.querySelectorAll('[data-side-panel-resize]').length).toBe(0);
    expect(document.querySelector('[data-side-panel-titlebar]')).toBeNull();
    expect(screen.queryByTestId('side-panel-collapse')).toBeNull();
  });

  it('gives the detached window its chrome back on restore', () => {
    renderPanel();
    click(screen.getByTestId('dock-to-floating'));
    click(maximizeButton()!);

    click(maximizeButton()!);

    expect(panelBox().getAttribute('data-side-panel-floating')).toBe('true');
    expect(document.querySelectorAll('[data-side-panel-resize]').length).toBeGreaterThan(0);
    expect(document.querySelector('[data-side-panel-titlebar]')).toBeTruthy();
  });

  it('un-shades a collapsed window on the way into full screen', () => {
    // Full screen and shaded are contradictory, and the context reconciles them
    // at the SOURCE - which is the entire reason `setMaximized` is a hand-written
    // callback rather than the raw setter. No button can reach this today (a
    // shaded window renders no tab bar), so the contract is driven where it
    // lives; without this, deleting that line changed nothing anywhere.
    renderPanel();
    click(screen.getByTestId('dock-to-floating'));
    click(screen.getByTestId('shade-panel'));
    expect(screen.getByTestId('shaded-flag').textContent).toBe('true');

    click(screen.getByTestId('maximize-via-context'));

    expect(screen.getByTestId('shaded-flag').textContent).toBe('false');
    // And the panel really shows full screen, rather than a shaded strip that
    // merely happens to carry the flag.
    expect(panelBox().classList.contains('inset-0')).toBe(true);
    expect(document.querySelector('[data-side-panel-collapsed-row]')).toBeNull();
  });

  it('re-fits the canvas once per toggle, and never on mount', () => {
    // The panel paints full screen with no transition, so the `transitionend`
    // listener that re-fits after a dock resize never fires for this mode: the
    // effect is the only thing telling a canvas inside the panel to recentre.
    const fits: unknown[] = [];
    const onFit = () => fits.push(1);
    window.addEventListener('workflowViewFitView', onFit);
    try {
      renderPanel();
      expect(fits.length).toBe(0);

      click(maximizeButton()!);
      expect(fits.length).toBe(1);

      click(maximizeButton()!);
      expect(fits.length).toBe(2);
    } finally {
      window.removeEventListener('workflowViewFitView', onFit);
    }
  });

  it('re-applies the dock width in a commit that carries NO transition', () => {
    // The docked style carries `transition: width 0.3s`. Re-applied in the SAME
    // commit as the width, it animates the panel from the full viewport down to
    // its dock while it is back in the flex flow, squeezing the main content to
    // nothing on the way. Suppressing it from an effect does not help: effects
    // run after the commit, so that commit has already gone out armed.
    //
    // So the assertion has to read the commit SEQUENCE, not the settled style -
    // the settled style is animated again by design, and an earlier version of
    // this test passed on a suppression flag that had simply never been cleared.
    renderPanel();
    const docked = panelBox().style.width;
    expect(docked).not.toBe('');

    click(maximizeButton()!);
    expect(panelBox().style.width).toBe('');

    commits.length = 0;
    click(maximizeButton()!);

    const firstWithWidth = commits.find((style) => style.includes(`width: ${docked}`));
    expect(firstWithWidth, 'the dock width was never re-applied').toBeDefined();
    expect(firstWithWidth).toContain('transition: none');
  });

  it('re-arms the dock transition afterwards, so an ordinary resize still animates', () => {
    // The suppression must last exactly one commit. Left on, it silently kills
    // the open/close and dock-resize animation for the rest of the session -
    // a mutant that never cleared it survived the whole suite before this.
    renderPanel();
    const animated = panelBox().style.transition;
    expect(animated).toContain('0.3s');

    click(maximizeButton()!);
    click(maximizeButton()!);

    expect(panelBox().style.transition).toBe(animated);
  });

  it('does the same on the BOTTOM dock, which animates height rather than width', () => {
    // The docked style has two axes and the suppression has to reach both; the
    // height half was written blind and nothing covered it.
    renderPanel();
    click(screen.getByTestId('dock-to-bottom'));
    const docked = panelBox().style.height;
    expect(docked).not.toBe('');

    click(maximizeButton()!);

    commits.length = 0;
    click(maximizeButton()!);

    const firstWithHeight = commits.find((style) => style.includes(`height: ${docked}`));
    expect(firstWithHeight, 'the dock height was never re-applied').toBeDefined();
    expect(firstWithHeight).toContain('transition: none');
    expect(panelBox().style.transition).toContain('height 0.3s');
  });

  it('ignores an overlay that is BEHIND it, which owns nothing it can see', () => {
    // A document-wide role query also finds the page's own overlays, which the
    // panel is covering. The conversation-activity card carries role="dialog"
    // and lives in the chat page tree, so leaving it open turned Escape into a
    // silent no-op with no visible cause.
    renderPanel();
    click(maximizeButton()!);
    const behind = document.createElement('div');
    behind.setAttribute('role', 'dialog');
    // Inside the app tree and outside the panel: hidden under it, by definition.
    panelBox().parentElement!.appendChild(behind);

    pressEscape();

    expect(panelBox().getAttribute('data-side-panel-maximized')).toBeNull();
    behind.remove();
  });

  it('makes the covered tree inert, and leaves body-level portals alone', () => {
    // The panel is not modal, but an opaque box over the viewport still puts the
    // page out of reach in every sense except the tab order. The walk must stop
    // below body: every menu the panel opens is portalled there as a sibling of
    // the app root, and inerting those would disable the panel's own controls.
    const portal = document.createElement('div');
    portal.setAttribute('data-portal', '');
    document.body.appendChild(portal);
    renderPanel();
    const sibling = document.createElement('div');
    panelBox().parentElement!.appendChild(sibling);

    click(maximizeButton()!);

    expect(sibling.hasAttribute('inert')).toBe(true);
    expect(portal.hasAttribute('inert')).toBe(false);
    // The panel itself is never inert, or its own controls would be unreachable.
    expect(panelBox().hasAttribute('inert')).toBe(false);

    click(maximizeButton()!);

    expect(sibling.hasAttribute('inert')).toBe(false);
    portal.remove();
    sibling.remove();
  });

  it('never clears an inert somebody else set', () => {
    // Restoring removes what this effect marked, not everything it finds: an
    // element already inert for another reason has to stay that way.
    renderPanel();
    const preExisting = document.createElement('div');
    preExisting.setAttribute('inert', '');
    panelBox().parentElement!.appendChild(preExisting);

    click(maximizeButton()!);
    click(maximizeButton()!);

    expect(preExisting.hasAttribute('inert')).toBe(true);
    preExisting.remove();
  });

  it('renders as the usual mobile overlay even if the flag is set on a phone', () => {
    // The button is hidden on mobile, but the flag survives a viewport change:
    // maximize on a desktop, narrow the window, and without the `!isMobile` gate
    // the panel paints `fixed inset-0` with no control left to undo it, because
    // the restore button is hidden by the same breakpoint.
    mobile.value = true;
    renderPanel();

    click(screen.getByTestId('maximize-via-context'));

    expect(panelBox().getAttribute('data-side-panel-maximized')).toBeNull();
    expect(panelBox().classList.contains('inset-0')).toBe(false);
    // Still the mobile overlay it always was.
    expect(panelBox().classList.contains('z-[40]')).toBe(true);
  });

  it('renders no full-screen mode in a shared conversation either', () => {
    shared.value = { conversationId: 'c1' };
    renderPanel();

    click(screen.getByTestId('maximize-via-context'));

    expect(panelBox().getAttribute('data-side-panel-maximized')).toBeNull();
    expect(panelBox().classList.contains('inset-0')).toBe(false);
  });

  it('is not offered on a phone, where the panel is already a full-screen overlay', () => {
    mobile.value = true;
    renderPanel();
    expect(maximizeButton()).toBeNull();
  });

  it('is not offered in a shared conversation, like every other panel control', () => {
    shared.value = { conversationId: 'c1' };
    renderPanel();
    expect(maximizeButton()).toBeNull();
  });
});
