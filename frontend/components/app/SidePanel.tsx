'use client';

import React, { useState, useRef, useEffect, useCallback, useId, useReducer } from 'react';
import { useTranslations } from 'next-intl';
import { X, GripVertical, Pin, MoreVertical, ExternalLink, Trash2, PanelRightOpen, PanelRight, PanelBottom, PictureInPicture2, ChevronsUpDown, ChevronsDownUp, Maximize2, Minimize2 } from 'lucide-react';
import { BulkDeleteModal } from '@/components/ui/BulkDeleteModal';
import { useRouter } from 'next/navigation';
import { Button } from '@/components/ui/button';
import { panelTabClass, panelTabInnerHoverClass } from '@/components/ui/panel-tab';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { TooltipProvider } from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { useSidePanelLayoutSafe } from '@/contexts/SidePanelLayoutContext';
import { useMouseResize } from '@/hooks/useMouseResize';
import { useMobileDetection } from '@/hooks/useMobileDetection';
import { FLOATING_DRAG_CURSOR, useFloatingPanelRect, type FloatingDragMode, type FloatingResizeMode } from '@/hooks/useFloatingPanelRect';
import { PanelResizeHandle } from '@/components/ui/PanelResizeHandle';
import { AddTabPicker } from '@/components/app/AddTabPicker';
import { useSharedConversation } from '@/contexts/SharedConversationContext';
import { orchestratorApi } from '@/lib/api';
import { getTabResourceUrl, parseTabResource } from '@/lib/sidePanel/tabResource';
import { useCanMutateInCurrentOrg } from '@/lib/stores/current-org-store';

/** Stable no-op for the render path outside a provider (shared conversations). */
const noop = () => {};

/**
 * The collapsed detached window: one row, and no wider than a tab label needs.
 *
 * The tab bar is not rendered at this size, so the grip that normally lives in it
 * is not either - the row itself is the drag surface, which is both smaller
 * overall and a bigger target than a grip would be.
 */
const COLLAPSED_HEIGHT = 36;
/** Wide enough for the chevron, a readable slice of the tab name and the close
 *  button, and no wider: the strip is meant to get out of the way, and 260px of
 *  it read as a window that had not really collapsed. A long tab name truncates,
 *  which is what the tooltip and the expanded window are for. */
const COLLAPSED_WIDTH = 180;
const COLLAPSED_RENDER_SIZE = { width: COLLAPSED_WIDTH, height: COLLAPSED_HEIGHT };

/**
 * The roles the app's overlay primitives render, i.e. the layers that own the
 * Escape key while they are open: modals and Popover content (`dialog` /
 * `alertdialog`), Select (`listbox`) and DropdownMenu (`menu`).
 */
const OVERLAY_ROLES = '[role="dialog"], [role="alertdialog"], [role="listbox"], [role="menu"]';

/**
 * The element's own top-level ancestor under `document.body`, i.e. the app tree.
 *
 * Anything the app renders in place sits inside it; anything portalled (every
 * Radix overlay) is a sibling of it. That is the only line that separates "on
 * top of the panel" from "hidden behind it" without measuring pixels.
 */
function appRootOf(el: HTMLElement | null): HTMLElement | null {
  let node: HTMLElement | null = el;
  while (node?.parentElement && node.parentElement !== document.body) {
    node = node.parentElement;
  }
  return node;
}

/**
 * SidePanel - the unified right panel for the entire app.
 *
 * Lives in the app layout as a flex sibling of the main content area.
 * When open, the main area naturally shrinks (no marginRight hacks).
 * Content is lazy-rendered: nothing is mounted until the panel opens.
 *
 * Tabs are managed via SidePanelContext - each page registers its own tabs.
 */
export function SidePanel() {
  const t = useTranslations('common');
  const ctx = useSidePanelSafe();
  const isSharedMode = !!useSharedConversation();

  const [panelWidth, setPanelWidth] = useState(0);
  const panelRef = useRef<HTMLDivElement>(null);
  const isMobile = useMobileDetection();
  /** What the collapse and expand controls disclose, so they read as one pair.
   *  Generated rather than a constant: a hardcoded id is only unique for as long
   *  as nobody mounts a second panel, and `aria-controls` breaks silently if they do. */
  const panelBodyId = useId();

  // Dock position preference (Settings > Preferences). Both bottom variants
  // ('bottom' = content width, 'bottom-full' = full viewport width) dock the panel at
  // the bottom and render it identically (full width of its container, height-sized);
  // only WHERE it is mounted differs (see AppShell). Bottom only takes effect on
  // desktop - on mobile the panel keeps its fixed full-screen overlay regardless.
  const { position, setPosition, lastDock, bottomMode } = useSidePanelLayoutSafe();
  const isBottom = (position === 'bottom' || position === 'bottom-full') && !isMobile;
  /**
   * Detached: the panel is a movable card floating over the app instead of a dock.
   *
   * Gated on `!isMobile` like `isBottom`, and for the same reason: below the
   * breakpoint the panel is already a full-screen overlay, so a stored 'floating'
   * simply renders as the right dock there. That gate is also what keeps the
   * feature off small screens without a second breakpoint to maintain - shrink the
   * window under 768px while detached and the panel re-docks on its own.
   */
  const isFloating = position === 'floating' && !isMobile;
  /**
   * Collapsed = the detached window shaded down to one row.
   *
   * A pure RENDER mode: it changes neither the panel's open/closed state nor the
   * stored rect, so expanding restores the exact window the user had sized, and
   * `keepMounted` tab content is only hidden, never unmounted. Session-only on
   * purpose - a reload should not bring the panel back as a strip the user has to
   * find and expand.
   *
   * Held by the CONTEXT, not here: every surface that brings the panel forward has
   * to lift the shade, and the app header has to know that a shaded window is not
   * forward. Masked by `isFloating`, so a dock can never render shaded.
   */
  const collapsed = ctx?.collapsed ?? false;
  const setCollapsed = ctx?.setCollapsed ?? noop;
  /**
   * Full screen = the panel painted over the whole viewport, dock and all.
   *
   * A pure RENDER mode on top of whatever position the panel already has, exactly
   * like `isCollapsed` below it: the dock, the dragged width and the detached
   * rect are all left alone, so restoring gives back the panel the user had
   * rather than a default one. Above all, the panel is NOT re-mounted anywhere
   * else in the tree, which is the one thing that would take a running canvas, an
   * SSE stream or an interface iframe with it (see AppShell's per-dock branches).
   *
   * Gated on `!isMobile` like `isBottom` and `isFloating`: below the breakpoint
   * the panel is already a full-screen overlay, so there is nothing to maximise.
   * Gated on shared mode too, where every other panel control is hidden.
   *
   * NOT a modal, deliberately, and the app behind it is NOT made `inert`. The
   * mode is "this pane takes the whole window", the way an editor maximises a
   * pane, not "answer this before continuing" - and the mechanical reason
   * matters more: the overlays this panel opens (menus, pickers, Selects) are
   * portalled to `document.body` as SIBLINGS of the app root, so inerting the
   * covered tree by walking the panel's ancestors would either miss them or
   * disable them along with everything else. The way out is a visible control in
   * the tab bar plus Escape, both of which a keyboard reaches without one.
   */
  const maximized = ctx?.maximized ?? false;
  const setMaximized = ctx?.setMaximized ?? noop;
  /* `isOpen` is part of the RENDER condition, not only of the context's reset
     rule: with a keepMounted tab the container is in the tree even while the
     panel is closed, so a flag that outlived a close by the one frame it takes
     an effect to run would paint an opaque full-screen box over the app. Read
     here rather than at the far end of the component so the two cannot drift. */
  const isOpen = ctx?.isOpen ?? false;
  const isMaximized = maximized && isOpen && !isMobile && !isSharedMode;
  /* Masked by `isMaximized` on top of `isFloating`, and for the same reason: a
     full-screen panel is not a 36px strip, so the two render modes cannot both
     apply. The context already refuses to maximise a shaded panel; this keeps the
     render honest if the flags ever meet by another route. */
  const isCollapsed = isFloating && collapsed && !isMaximized;
  const {
    rect: floatRect, dragMode: floatDragMode, viewport: floatViewport,
    startDrag: startFloatDrag, nudge: nudgeFloat,
  } = useFloatingPanelRect(
    isFloating,
    // Collapsed the card paints a strip, so the MOVE has to clamp against the
    // strip: clamping against the expanded rect reserves room for a window that is
    // not on screen, and the strip stops short of the right and bottom edges by its
    // own expanded size - it cannot be parked in a corner, which is the point of it.
    isCollapsed ? COLLAPSED_RENDER_SIZE : undefined,
  );
  const isFloatDragging = floatDragMode !== null;

  // Dispatch fitView so the workflow canvas recenters after the panel size changes
  const dispatchFitView = useCallback(() => {
    window.dispatchEvent(new CustomEvent('workflowViewFitView', {
      detail: { animated: true },
    }));
  }, []);

  // Resize via shared hook - trigger fitView when manual resize ends. In bottom mode we
  // resize HEIGHT (y axis) from the top edge; otherwise WIDTH (x axis) from the left edge.
  const resizeOptions = React.useMemo(
    () => ({ onResizeEnd: dispatchFitView, axis: (isBottom ? 'y' : 'x') as 'x' | 'y', minWidth: isBottom ? 200 : undefined }),
    [dispatchFitView, isBottom],
  );
  const { isResizing, startResize, hasManuallyResizedRef } = useMouseResize(setPanelWidth, resizeOptions);

  /**
   * Recentre the canvas after a geometry change the `transitionend` listener below
   * cannot see.
   *
   * That listener hangs off the panel's own width/height transition, which a
   * detached window does not have: detaching swaps the main area's width in one
   * frame, and a resize drag ends on a rect that is already final. Re-attaching IS
   * covered by it (the docked style animates back), so this only fires on the way
   * out and at the end of a floating drag.
   */
  const wasFloatingRef = useRef(isFloating);
  const lastFloatDragModeRef = useRef<FloatingDragMode | null>(null);
  useEffect(() => {
    const justDetached = isFloating && !wasFloatingRef.current;
    const endedMode = lastFloatDragModeRef.current;
    // A MOVE is not a re-fit: a detached window is out of flow, so sliding it
    // changes no element's box anywhere. Firing on it would re-fit (and animate)
    // the canvas behind the window every time the user parks it, throwing away
    // the pan and zoom they had set - on the most frequent gesture of the feature.
    const justFinishedResize = isFloating && floatDragMode === null && !!endedMode && endedMode !== 'move';
    wasFloatingRef.current = isFloating;
    lastFloatDragModeRef.current = floatDragMode;
    if (justDetached || justFinishedResize) dispatchFitView();
  }, [isFloating, floatDragMode, dispatchFitView]);

  // Lazy rendering: track whether content has been mounted at least once
  const [hasBeenOpened, setHasBeenOpened] = useState(false);

  const tabs = ctx?.tabs ?? [];
  const activeTabId = ctx?.activeTabId ?? null;

  const activeTab = tabs.find(t => t.id === activeTabId) || tabs[0] || null;

  // Mark as opened for lazy mounting
  useEffect(() => {
    if (isOpen && !hasBeenOpened) {
      setHasBeenOpened(true);
    }
  }, [isOpen, hasBeenOpened]);

  // Default size along the active axis. Right mode: width from the tab's preferredWidth
  // (or 35%), clamped to [320, 70%]. Bottom mode: height at 40% of the viewport, clamped
  // to [240, 70%] (preferredWidth is a width fraction, so it is not reused for height).
  const calculatePanelWidth = useCallback((preferred?: number) => {
    if (typeof window === 'undefined') return isBottom ? 360 : 384;
    if (isBottom) {
      const screenHeight = window.innerHeight;
      const maxHeight = Math.floor(screenHeight * 0.7);
      const minHeight = 240;
      return Math.max(minHeight, Math.min(maxHeight, Math.floor(screenHeight * 0.4)));
    }
    const screenWidth = window.innerWidth;
    if (screenWidth < 768) return screenWidth;
    const fraction = preferred || 0.35;
    const maxWidth = Math.floor(screenWidth * 0.7);
    const minWidth = 320;
    const calculated = Math.floor(screenWidth * fraction);
    return Math.max(minWidth, Math.min(maxWidth, calculated));
  }, [isBottom]);

  // Read latest panelWidth from a ref so the window-resize effect doesn't
  // re-attach on every drag tick.
  const panelWidthRef = useRef(panelWidth);
  panelWidthRef.current = panelWidth;

  // Constrain on window resize
  useEffect(() => {
    if (!isOpen) return;
    const handleResize = () => {
      if (isResizing) return; // never fight an active drag
      if (hasManuallyResizedRef.current) {
        const maxExtent = (isBottom ? window.innerHeight : window.innerWidth) * 0.7;
        if (panelWidthRef.current > maxExtent) setPanelWidth(maxExtent);
        return;
      }
      setPanelWidth(calculatePanelWidth(activeTab?.preferredWidth));
    };
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, [isOpen, calculatePanelWidth, activeTab?.preferredWidth, isResizing, isBottom]);

  // Switching dock position (right <-> bottom) invalidates the stored px size, which is
  // axis-specific. Drop any manual resize and recompute the default for the new axis.
  //
  // Keyed on the DOCK's axis, not on `isBottom`: detaching forces `isBottom` false
  // whatever dock it came from, so keying on that reset the size on the way out AND
  // on the way back, handing a user who had dragged their bottom dock to a chosen
  // height the 40%-of-viewport default after a detach round trip. A detached window
  // owns its own rect and does not use this size at all.
  const dockAxis = (isFloating ? lastDock !== 'right' : isBottom) ? 'y' : 'x';
  useEffect(() => {
    if (isFloating) return;
    hasManuallyResizedRef.current = false;
    if (isOpen) setPanelWidth(calculatePanelWidth(activeTab?.preferredWidth));
    // Recompute only when the axis flips - not on every tab/size change.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dockAxis]);

  // Sync width with open/close
  useEffect(() => {
    if (isResizing) return; // never overwrite an active drag
    const targetWidth = calculatePanelWidth(activeTab?.preferredWidth);
    if (isOpen && panelWidthRef.current === 0) {
      setPanelWidth(targetWidth);
    } else if (!isOpen && panelWidthRef.current > 0) {
      setPanelWidth(0);
    }
  }, [isOpen, calculatePanelWidth, activeTab?.preferredWidth, isResizing]);

  // Resize when switching to a tab with a different preferredWidth (unless manually resized)
  useEffect(() => {
    if (!isOpen || hasManuallyResizedRef.current || isResizing) return;
    setPanelWidth(calculatePanelWidth(activeTab?.preferredWidth));
  }, [activeTabId, activeTab?.preferredWidth, isOpen, calculatePanelWidth, isResizing]);

  // Reset manual resize flag when panel closes
  useEffect(() => {
    if (!isOpen) {
      hasManuallyResizedRef.current = false;
    }
  }, [isOpen]);

  /**
   * The shade lifts on the one route the context cannot see: the panel stops being
   * detached.
   *
   * Collapsing is a WINDOW state and cannot outlive the window. `isCollapsed` only
   * MASKS the flag while docked, so leaving it set would be invisible on screen and
   * very loud everywhere else: `isForward` stays false, the header button's first
   * press is spent un-shading nothing, every preview card loses its "click to close"
   * state, and the empty-canvas composer comes back on top of the panel's own chat.
   *
   * Stated as a RULE about the docked state ("a dock is never shaded"), deliberately
   * not as a transition. A transition needs a ref seeded at mount, and AppShell
   * renders the panel in a different branch per dock, so crossing that boundary
   * (`bottom` to `bottom-full`) remounts it: a ref seeded `false` on a docked mount
   * then never fires, and the flag is stranded for the session. An earlier version
   * did exactly that. The `isFloating` early return is what keeps the OTHER remount
   * - one that lands still detached - from expanding a window the user collapsed.
   *
   * Every other route, anything that brings the panel forward, lifts the shade at
   * its source in the context, so there is nothing else to watch here.
   */
  useEffect(() => {
    if (isFloating) return;
    // Reacts to state OUTSIDE this component (the dock), which is what the rule
    // allows for. Nothing here reads `collapsed`, so it cannot cascade.
    setCollapsed(false);
  }, [isFloating, setCollapsed]);

  // Trigger fitView after open/close CSS transition ends
  useEffect(() => {
    const panel = panelRef.current;
    if (!panel) return;
    const handleTransitionEnd = (e: TransitionEvent) => {
      if (e.propertyName === 'width' || e.propertyName === 'height') {
        dispatchFitView();
      }
    };
    panel.addEventListener('transitionend', handleTransitionEnd);
    return () => panel.removeEventListener('transitionend', handleTransitionEnd);
  }, [dispatchFitView]);

  const router = useRouter();

  // ── Drag-to-reorder state ──
  const [draggedIndex, setDraggedIndex] = useState<number | null>(null);
  const [dropTargetIndex, setDropTargetIndex] = useState<number | null>(null);

  // ── Tab context menu (3-dot) state ──
  const [openMenuTabId, setOpenMenuTabId] = useState<string | null>(null);

  // ── Delete confirmation modal state ──
  const [pendingDeleteTab, setPendingDeleteTab] = useState<
    { id: string; label: string; handler: () => void; failed?: boolean } | null
  >(null);
  const [isDeleting, setIsDeleting] = useState(false);

  const tSidePanel = useTranslations('sidePanel');
  const canMutate = useCanMutateInCurrentOrg();

  /**
   * Keyboard placement for the detached window: arrows move it, Shift+arrows resize
   * it from the bottom-right, Ctrl+Shift+arrows from the TOP-LEFT, and Alt drops the
   * step to a single pixel. The pointer handles are the primary path, but detaching
   * is the only way to place the panel freely, so it must not be pointer-only.
   *
   * Two anchors rather than one, because one only reaches two of the four edges: a
   * single 'se' anchor left the north and west edges mouse-only, and moving the
   * window instead is a different gesture - it does not hold the opposite edge still.
   * The same pair of combinations window managers use, so it is not an invention.
   */
  const handleTitleBarKeyDown = useCallback((e: React.KeyboardEvent<HTMLElement>) => {
    const step = e.altKey ? 1 : 16;
    const delta: Record<string, [number, number]> = {
      ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step],
    };
    const move = delta[e.key];
    if (!move) return;
    e.preventDefault();
    // The application carousel paginates on Left/Right from a WINDOW listener, so
    // one keypress would both nudge the window and turn its page.
    e.stopPropagation();
    // Shift is not a verb the collapsed row has: it advertises the four arrows and
    // nothing else, and the hook refuses a resize under a painted box. Letting it
    // through would MOVE the strip, which is an unadvertised gesture rather than a
    // no-op.
    if (e.shiftKey && isCollapsed) return;
    // The hook refuses a resize while collapsed anyway; this clause's own job is
    // to stop the canvas behind the window being re-fitted for a gesture that
    // changed nothing.
    // Ctrl or Meta anchors on the opposite corner, so every edge is reachable:
    // Ctrl+Shift+Left widens to the LEFT, where plain Shift+Left narrows from the
    // right. `false` when not resizing, which is the move verb.
    const resizing: false | FloatingResizeMode = e.shiftKey && !isCollapsed
      ? ((e.ctrlKey || e.metaKey) ? 'nw' : 'se')
      : false;
    nudgeFloat(move[0], move[1], resizing);
    // Same rule as the pointer path: a resize changes the box, a move does not -
    // and collapsed there is no resize, so nothing to re-fit either.
    if (resizing) dispatchFitView();
  }, [nudgeFloat, dispatchFitView, isCollapsed]);

  /**
   * Collapsing and expanding each unmount the control that was activated, so a
   * keyboard user's focus falls to `<body>` and they have to tab in from the top
   * of the document. Focus is handed to the counterpart instead, which is also
   * what makes the pair read as one disclosure control.
   */
  const collapseRef = useRef<HTMLButtonElement | null>(null);
  const expandRef = useRef<HTMLButtonElement | null>(null);
  const collapseWasFocusedRef = useRef(false);
  /**
   * ...including when the shade is lifted from OUTSIDE the panel.
   *
   * Collapsing focuses the row, and a live run pausing on an interface node un-shades
   * the window without touching either local control - so the focused row unmounted
   * under the user and dropped them on `<body>`, on the very route the feature is
   * built around. Tracked from the row itself rather than from the controls.
   */
  const rowHadFocusRef = useRef(false);
  useEffect(() => {
    // A closed panel unmounts the row without firing `focusout`, so the claim would
    // survive: the NEXT reopen, from anywhere, then yanks focus into the panel and
    // the user's keystrokes stop reaching what they were typing in.
    if (!isFloating || !isOpen) {
      collapseWasFocusedRef.current = false;
      rowHadFocusRef.current = false;
      return;
    }
    const handoff = collapseWasFocusedRef.current || (!isCollapsed && rowHadFocusRef.current);
    collapseWasFocusedRef.current = false;
    rowHadFocusRef.current = false;
    if (!handoff) return;
    (isCollapsed ? expandRef : collapseRef).current?.focus();
  }, [isCollapsed, isFloating, isOpen]);

  /**
   * The collapsed row is a drag surface AND a button, so a drag must not also fire
   * its expand. Measured rather than flagged: a plain "did a drag start" test would
   * swallow the press of anyone without a steady hand.
   *
   * Resolved on `pointerup` at the WINDOW rather than on the row's `click`, because
   * a click needs press and release on the same element and the drag mounts a
   * full-viewport overlay the instant the press lands. Pointer capture normally
   * retargets the release back to the row, but capture is best-effort (it is wrapped
   * in a try/catch precisely because a browser may refuse it), and when it is
   * refused the release goes to the overlay, no click is dispatched, and the strip
   * cannot be expanded by tapping AT ALL - the one gesture the collapsed window has.
   *
   * The listeners live on the same short leash as the drag itself, which is the part
   * that is easy to get wrong: a press the window loses focus during never delivers
   * a pointerup, so listeners that only remove themselves from inside their own
   * handler outlive the gesture. The next unrelated release within 4px of a press
   * the user has long forgotten then expands the window, and every such press leaks
   * a pair for the session. Aborted on the release, on a cancel, on blur (which is
   * how the drag tears itself down) and on unmount.
   */
  const tapAbortRef = useRef<AbortController | null>(null);
  useEffect(() => () => tapAbortRef.current?.abort(), []);
  const expand = useCallback(() => {
    collapseWasFocusedRef.current = document.activeElement === expandRef.current;
    setCollapsed(false);
  }, [setCollapsed]);
  const handleCollapsedPointerDown = useCallback((e: React.PointerEvent<HTMLElement>) => {
    // Same reason as the title bar: the drag preventDefaults the pointerdown, which
    // suppresses the default focus, so without this the row's arrow keys are dead
    // after any mouse use.
    if (e.button === 0) e.currentTarget.focus();
    if (e.button !== 0) return;
    // One gesture at a time, the same rule the drag itself enforces - and for a
    // sharper reason here. A second finger landing on the strip cannot start a drag
    // (the first one holds it), so if it armed its own tap it would release almost
    // where it landed, pass the 4px test, and expand the window mid-drag, while
    // silently disarming the finger that is actually moving it.
    if (tapAbortRef.current) return;
    const controller = new AbortController();
    tapAbortRef.current = controller;
    const { signal } = controller;
    const press = { x: e.clientX, y: e.clientY };
    const pointerId = e.pointerId;
    const drop = () => {
      controller.abort();
      if (tapAbortRef.current === controller) tapAbortRef.current = null;
    };
    window.addEventListener('pointerup', (ev) => {
      // The id test comes FIRST: on a multi-touch device a second finger lifting
      // early would otherwise tear down the real press before it ever released.
      if (ev.pointerId !== pointerId) return;
      drop();
      if (Math.hypot(ev.clientX - press.x, ev.clientY - press.y) > 4) return;
      expand();
    }, { signal });
    // A cancelled pointer (a system gesture taking over) is not a tap.
    window.addEventListener('pointercancel', (ev) => {
      if (ev.pointerId === pointerId) drop();
    }, { signal });
    // Nor is a press the window loses focus during: the drag gives up there too.
    window.addEventListener('blur', drop, { signal });
    startFloatDrag('move')(e);
  }, [startFloatDrag, expand]);
  const handleCollapsedClick = useCallback((e: React.MouseEvent<HTMLElement>) => {
    // Keyboard only. A pointer press is resolved above, and its synthesised click
    // arrives afterwards with `detail > 0`: acting on it too would expand a window
    // the pointer path had just decided to leave alone after a drag.
    if (e.detail > 0) return;
    expand();
  }, [expand]);

  /**
   * Grab focus on the way into a drag.
   *
   * `startDrag` preventDefaults the pointerdown to stop the text selection a drag
   * would otherwise paint, and that also suppresses the default focus - so after
   * any mouse use the bar was not focused and its arrow keys did nothing until the
   * user tabbed back to it.
   */
  const handleTitleBarPointerDown = useCallback((e: React.PointerEvent<HTMLElement>) => {
    // Primary button only, like the drag itself: a right-click opens the context
    // menu, and pulling focus onto a grab handle is not part of that.
    if (e.button === 0) e.currentTarget.focus();
    startFloatDrag('move')(e);
  }, [startFloatDrag]);

  /**
   * Detach / re-attach. Re-attaching goes back to the dock the panel was on when
   * it was detached - with the bottom VARIANT re-resolved through the current
   * preference - rather than to the user's default, so the round trip is a no-op
   * for someone who had overridden the dock.
   */
  const toggleFloating = useCallback(() => {
    if (!isFloating) { setPosition('floating'); return; }
    // Back to the dock it came from - but a bottom dock re-resolves through the
    // CURRENT bottom-variant preference, in case the user changed it while the
    // window was floating. That preference cannot be applied to `lastDock` live
    // (the app shell arranges itself around it, and the two bottom variants mount
    // the panel in different places, so a mid-detach change would remount it), so
    // it is applied here, at the moment the panel moves anyway.
    const isBottomDock = lastDock === 'bottom' || lastDock === 'bottom-full';
    setPosition(isBottomDock ? bottomMode : lastDock);
  }, [isFloating, lastDock, bottomMode, setPosition]);

  /**
   * Enter / leave full screen.
   *
   * Only the flag moves: the dock, the panel width and a detached window's rect
   * are all left exactly as they were, so this is a round trip with no state to
   * restore afterwards.
   */
  const toggleMaximized = useCallback(() => {
    setMaximized(!isMaximized);
  }, [isMaximized, setMaximized]);

  /**
   * Escape leaves full screen.
   *
   * The restore button is right there in the tab bar, so this is a convenience,
   * not the only way out - which is what lets it be this conservative:
   *  - a handled Escape (`defaultPrevented`) belongs to whoever handled it;
   *  - so does any open OVERLAY. The panel hosts content full of them (the
   *    inspector alone is mostly Selects), and dismissing the whole full-screen
   *    view along with the listbox the user was closing is worse than not
   *    reacting at all.
   *
   * Radix dismisses its layers WITHOUT calling `preventDefault`, so the first
   * clause does not cover the second: the roles have to be named. `dialog` and
   * `alertdialog` are modals and Popover content, `listbox` is Select, `menu` is
   * DropdownMenu - the four roles the app's overlay primitives actually render.
   *
   * And WHERE the match is matters as much as what it is. A bare document-wide
   * query also finds the overlays of the page BEHIND the panel, which are hidden
   * under it and can own nothing: the conversation-activity card carries
   * `role="dialog"` and lives in the chat page's own tree, so leaving it open
   * made Escape a silent no-op with no visible cause. Only two things are really
   * on top: an overlay INSIDE the panel, and one portalled out of the app tree
   * (Radix mounts those as direct children of `document.body`, which is why the
   * app root is the boundary rather than the panel itself).
   */
  useEffect(() => {
    if (!isMaximized) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== 'Escape' || e.defaultPrevented) return;
      const panel = panelRef.current;
      const appRoot = appRootOf(panel);
      const owned = [...document.querySelectorAll(OVERLAY_ROLES)].some((el) => (
        panel?.contains(el) || !appRoot?.contains(el)
      ));
      if (owned) return;
      setMaximized(false);
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [isMaximized, setMaximized]);

  /**
   * Everything the panel COVERS is made inert while it is full screen.
   *
   * The mode is not modal (see `isMaximized`), but an opaque box over the whole
   * viewport still takes the page out of reach in every sense except the tab
   * order, and a keyboard user tabbing past the panel's last control otherwise
   * lands on a sidebar and a page they cannot see.
   *
   * The walk stops BELOW `document.body` on purpose. Every overlay this panel
   * opens - menus, pickers, Selects - is portalled to `body` as a sibling of the
   * app root, so inerting body's children would disable the panel's own
   * controls; inerting the app root would disable the panel, which lives inside
   * it. Marking each ancestor's OTHER children, up to but not including body,
   * hides exactly the covered tree and nothing else.
   */
  useEffect(() => {
    const panel = panelRef.current;
    if (!isMaximized || !panel) return undefined;
    const marked: HTMLElement[] = [];
    // The loop CONDITION is what stops at body, not a break after the fact: a
    // break placed at the end of the body still marks that level's siblings
    // first, which is exactly the portal roots this is meant to spare.
    for (let node: HTMLElement = panel; node.parentElement && node.parentElement !== document.body; node = node.parentElement) {
      for (const sibling of node.parentElement.children) {
        if (sibling === node || !(sibling instanceof HTMLElement)) continue;
        // Never re-mark, so restoring cannot clear an `inert` somebody else set.
        if (sibling.hasAttribute('inert')) continue;
        sibling.setAttribute('inert', '');
        marked.push(sibling);
      }
    }
    return () => marked.forEach((el) => el.removeAttribute('inert'));
  }, [isMaximized]);

  /**
   * A full-screen toggle is INSTANT in both directions, and re-fits the canvas once.
   *
   * Entering is instant for free (the full-screen style carries `transition:
   * none`). LEAVING is not: the docked style comes back with `width` AND
   * `transition: width 0.3s` in the same commit, so the browser arms a transition
   * from the viewport width down to the dock width - while the panel is back in
   * the flex flow, which squeezes the main content toward zero for the length of
   * the animation. It also fires the `transitionend` listener above, so the
   * canvas would be re-fitted twice, 300ms apart, each one animated and fighting
   * the other.
   *
   * So the frame the mode changes on is painted with no transition at all, and
   * the fit is dispatched from here. Two rAFs, not one: the class and the width
   * have to be COMMITTED with transitions off before they are re-enabled, or the
   * browser still sees a transitionable change and animates it anyway.
   *
   * `wasMaximizedRef` is seeded from the first render, so a mount fires nothing -
   * and a re-mount costing one skipped re-fit is harmless, unlike a stranded flag.
   */
  const wasMaximizedRef = useRef(isMaximized);
  // Derived during RENDER, and that is the whole point: an effect - passive or
  // layout - runs after the commit, so the commit that re-applies `width` and
  // `transition: width .3s` together has already gone out with the transition
  // armed, and suppressing it one commit later only shortens the animation.
  // Comparing the ref here puts `transition: none` in the SAME commit as the
  // width, so the browser never sees a transitionable change at all.
  const instantResize = wasMaximizedRef.current !== isMaximized;
  // Re-arm afterwards from a plain re-render, NOT from a timer or a rAF: by the
  // time this runs the width is committed, so restoring the transition cannot
  // animate anything, and there is no frame budget to get wrong. (A rAF pair
  // was the first version, and jsdom runs neither, so the test that was meant
  // to pin this passed on a flag that had simply never been cleared.)
  const [, rearmTransition] = useReducer((n: number) => n + 1, 0);
  useEffect(() => {
    if (wasMaximizedRef.current === isMaximized) return;
    wasMaximizedRef.current = isMaximized;
    dispatchFitView();
    rearmTransition();
  }, [isMaximized, dispatchFitView]);

  if (!ctx) return null;

  const { setActiveTab, removeTab, moveTab, close, isPeeking, dismissPeek, openTab } = ctx;

  const handleCloseTab = (tabId: string) => {
    const tab = tabs.find(t => t.id === tabId);
    if (tab?.pinned) return;
    const remaining = tabs.filter(t => t.id !== tabId);
    removeTab(tabId);
    if (remaining.length === 0) {
      close();
    }
  };

  /** Build the actual API delete + tab cleanup function for a tab */
  const buildDeleteAction = (tab: typeof tabs[number]): (() => Promise<void>) | undefined => {
    // A VIEWER in an org workspace cannot delete anything: the server refuses with
    // a 403 whatever this offers. The entry was previously hidden on agent tabs
    // only because they were pinned, which was never a permission check.
    if (!canMutate) return undefined;
    const id = tab.id;
    const closeTabAfterDelete = () => { removeTab(id); if (tabs.length <= 1) close(); };

    if (tab.onDelete) {
      return async () => { tab.onDelete!(); closeTabAfterDelete(); };
    }
    const resource = parseTabResource(id);
    if (!resource || !resource.id) return undefined;
    // A run tab shows ONE execution, not the resource itself: deleting the whole
    // workflow from it would be a surprise, so it gets no delete entry. Before the
    // tab-id parsing was centralised it got one that always failed (it deleted the
    // literal id "run-<wfId>-<runId>").
    if (resource.runId) return undefined;
    const resourceId = resource.id;
    switch (resource.kind) {
      case 'workflow':
        return async () => { await orchestratorApi.deleteWorkflow(resourceId); closeTabAfterDelete(); };
      case 'interface':
        return async () => { await orchestratorApi.deleteInterface(resourceId); closeTabAfterDelete(); };
      case 'datasource':
        return async () => { await orchestratorApi.deleteDataSource(resourceId); closeTabAfterDelete(); };
      case 'agent':
        return async () => { await orchestratorApi.deleteAgent(resourceId); closeTabAfterDelete(); };
      default:
        return undefined;
    }
  };

  /** Resolve the delete handler for a tab - opens confirmation modal instead of deleting immediately */
  const getTabDeleteHandler = (tab: typeof tabs[number]): (() => void) | undefined => {
    const action = buildDeleteAction(tab);
    if (!action) return undefined;
    return () => {
      setPendingDeleteTab({ id: tab.id, label: tab.label, handler: action });
    };
  };

  /** Resolve the i18n delete title from the resource the tab shows */
  const getDeleteTitle = (tabId: string): string => {
    const kind = parseTabResource(tabId)?.kind;
    if (kind === 'workflow') return tSidePanel('deleteWorkflowTitle');
    if (kind === 'interface') return tSidePanel('deleteInterfaceTitle');
    if (kind === 'datasource') return tSidePanel('deleteDatasourceTitle');
    if (kind === 'agent') return tSidePanel('deleteAgentTitle');
    return t('delete');
  };

  /** Resolve the i18n delete confirm message from the resource the tab shows */
  const getDeleteMessage = (tabId: string, label: string): string => {
    const kind = parseTabResource(tabId)?.kind;
    if (kind === 'interface') return tSidePanel('deleteInterfaceConfirm', { name: label });
    if (kind === 'datasource') return tSidePanel('deleteDatasourceConfirm', { name: label });
    if (kind === 'agent') return tSidePanel('deleteAgentConfirm', { name: label });
    return tSidePanel('deleteWorkflowConfirm', { name: label });
  };

  const confirmDelete = async () => {
    if (!pendingDeleteTab) return;
    setIsDeleting(true);
    try {
      await pendingDeleteTab.handler();
      setPendingDeleteTab(null);
    } catch (err) {
      // The modal used to close on a refusal exactly as it closes on a success:
      // no message, an unhandled rejection, and a user who reasonably concluded
      // the resource was gone. Keep it open and say so instead.
      console.error('[SidePanel] delete failed:', err);
      setPendingDeleteTab(prev => (prev ? { ...prev, failed: true } : prev));
    } finally {
      setIsDeleting(false);
    }
  };

  // ── Drag handlers ──
  const handleDragStart = (e: React.DragEvent, index: number) => {
    setDraggedIndex(index);
    e.dataTransfer.effectAllowed = 'move';
    // Make the drag image semi-transparent
    if (e.currentTarget instanceof HTMLElement) {
      e.dataTransfer.setDragImage(e.currentTarget, e.nativeEvent.offsetX, e.nativeEvent.offsetY);
    }
  };

  const handleDragOver = (e: React.DragEvent, index: number) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    if (draggedIndex === null || draggedIndex === index) {
      setDropTargetIndex(null);
      return;
    }
    setDropTargetIndex(index);
  };

  const handleDrop = (e: React.DragEvent, index: number) => {
    e.preventDefault();
    if (draggedIndex !== null && draggedIndex !== index) {
      moveTab(draggedIndex, index);
    }
    setDraggedIndex(null);
    setDropTargetIndex(null);
  };

  const handleDragEnd = () => {
    setDraggedIndex(null);
    setDropTargetIndex(null);
  };
  const currentWidth = isOpen && panelWidth === 0 ? calculatePanelWidth(activeTab?.preferredWidth) : panelWidth;
  const isVisible = currentWidth > 50;

  // Handle peek indicator click - open the panel with the pending tab
  const handlePeekClick = useCallback(() => {
    dismissPeek();
    if (activeTab) {
      openTab(activeTab);
    } else {
      ctx.open();
    }
  }, [dismissPeek, openTab, activeTab, ctx]);

  // Auto-dismiss peek when panel opens
  useEffect(() => {
    if (isOpen && isPeeking) {
      dismissPeek();
    }
  }, [isOpen, isPeeking, dismissPeek]);

  return (
    // 2s hover delay for every tooltip rendered inside the right side panel
    // (tab tooltips, inspector field hints, panel content tooltips). Descendant
    // <TooltipProvider> wrappers still override on a case-by-case basis.
    <TooltipProvider delayDuration={2000} skipDelayDuration={0}>
      {/* Mobile peek indicator - slides in from right edge when panel has deferred content */}
      {isMobile && isPeeking && !isOpen && (
        <button
          type="button"
          onClick={handlePeekClick}
          className="fixed right-0 top-1/2 -translate-y-1/2 z-[42] flex items-center gap-1.5 pl-3 pr-2 py-3 rounded-l-xl bg-theme-primary border border-r-0 border-theme shadow-lg animate-[peekSlideIn_0.4s_ease-out_forwards,peekPulse_2s_ease-in-out_0.5s_2]"
        >
          <PanelRightOpen className="h-4 w-4 text-theme-primary" />
          {activeTab && (
            <span className="text-xs font-medium text-theme-primary max-w-[80px] truncate">
              {activeTab.label}
            </span>
          )}
        </button>
      )}

      {/* Mobile overlay - below panel (z-[38]) but above main content */}
      {isMobile && isOpen && (
        <div className="fixed inset-0 bg-black/50 z-[38] md:hidden" onClick={close} />
      )}

      {/* Resize handle - left edge (right dock) or top edge (bottom dock). A detached
          window is not welded to any edge, so it carries its own handles instead. */}
      {!isMobile && !isFloating && !isMaximized && isOpen && isVisible && (
        <PanelResizeHandle
          panelWidth={currentWidth}
          isResizing={isResizing}
          onResizeStart={startResize}
          orientation={isBottom ? 'bottom' : 'right'}
        />
      )}

      {/* Resize grips of the DETACHED window - all four edges and all four corners,
          each corner pulling both axes at once.

          Rendered OUTSIDE the card and positioned from its rect, exactly like the
          docked panel's edge handle above: a grip laid over the card would sit on
          top of the content's own right-edge scrollbar, and the card clips its
          children (rounded + overflow-hidden), which would eat the corner targets
          as well. Hence 1px of overlap and no more, enough to make the border
          itself grabbable; the corners may overlap further, since the rounding
          leaves no content under them. */}
      {isFloating && isOpen && isVisible && !isCollapsed && !isMaximized && (() => {
        const GRIP = 9;
        const CORNER = 20;
        const { left, top, width, height } = floatRect;
        const vw = floatViewport.width;
        const vh = floatViewport.height;
        /* Flush against a viewport edge - which the drag clamp actively produces,
           since it stops the window exactly AT the edge - a band cannot keep its
           full thickness outside the card, so it narrows to what is left with a
           floor that keeps it a real target. Left as-is it would hang off screen
           and become a 1px target: the one state in which that edge could no
           longer be resized with a pointer at all. */
        const band = (outer: number, limit: number) => Math.max(4, Math.min(GRIP, limit - outer));
        const eastOuter = left + width - 1;
        const southOuter = top + height - 1;
        const eastW = band(eastOuter, vw);
        const southH = band(southOuter, vh);
        // Flush against the top or left there is no room outside the card, so the
        // band narrows to what is left - with the SAME 4px floor as its east and
        // south siblings, since a 1px band is the one state in which that edge can
        // no longer be grabbed with a pointer at all. 4px onto the card is safe
        // here: there is no scrollbar at the top-left, and the grip in the tab bar
        // is 24x56 starting 6px in, so it stays usable.
        const northH = Math.max(4, Math.min(GRIP, top + 1));
        const westW = Math.max(4, Math.min(GRIP, left + 1));
        /* Corners are placed first, then each band spans corner to corner and the
           corners paint OVER its ends (z-63 against z-62). Insetting the bands by
           the corner size instead left a gap on either end of every side - eight
           strips where the pointer hit nothing - because the corners are not
           anchored exactly `CORNER` px from the card. Letting them overlap costs
           nothing and cannot leave a gap by construction. */
        const cLeft = Math.max(0, left - (CORNER - 6));
        const cTop = Math.max(0, top - (CORNER - 6));
        // Every corner overlaps the card by 6px on both axes - except flush against a
        // viewport edge, where there is no room outside the card at all: the corner
        // then narrows to an 8px floor sitting entirely ON it, the same trade its
        // band siblings make, because a target thinner than that cannot be grabbed
        // with a pointer. 6px is what the rounding leaves with no content under it;
        // 14 would reach the panel content's own scrollbar, which is the very thing
        // rendering the grips outside the card exists to avoid, and would cover the
        // grip at the head of the tab bar. The spans below are derived from these,
        // so tightening the overlap cannot reopen a gap.
        // Flush against a viewport edge there is no room for a full corner outside
        // the card, so it narrows like the bands rather than sliding 20px INTO the
        // card. All FOUR get this, not just the east and south pair: at `left: 0`
        // the west corners kept their full width against a `cLeft` already clamped
        // to 0, which put a 20px resize target over the top-left of the card and
        // swallowed the grip at the head of the tab bar with it - the exact thing
        // the comment above says the overlap must not do.
        const cornerE = Math.max(8, Math.min(CORNER, vw - (left + width - 6)));
        const cornerS = Math.max(8, Math.min(CORNER, vh - (top + height - 6)));
        const cornerW = Math.max(8, Math.min(CORNER, left + 6));
        const cornerN = Math.max(8, Math.min(CORNER, top + 6));
        const cRight = Math.max(0, Math.min(left + width - 6, vw - cornerE));
        const cBottom = Math.max(0, Math.min(top + height - 6, vh - cornerS));
        const spanX = { left: cLeft, width: Math.max(0, cRight + cornerE - cLeft) };
        const spanY = { top: cTop, height: Math.max(0, cBottom + cornerS - cTop) };

        const edge = (
          dir: 'n' | 's' | 'e' | 'w',
          cursor: string,
          style: React.CSSProperties,
          accent: string,
        ) => (
          <div
            key={dir}
            data-side-panel-resize={dir}
            onPointerDown={startFloatDrag(dir)}
            /* Pointer affordance only, and an empty div at that: the keyboard route
               to the same geometry is the title bar's arrow keys, which are labelled
               and announced. Leaving these exposed adds eight unnamed nodes to the
               tree for no verb anyone can reach. */
            aria-hidden="true"
            className={`fixed z-[62] ${cursor} touch-none group/grip flex items-center justify-center`}
            style={style}
          >
            {/* Same hover accent the docked edge handle paints, so the detached
                window is not the one resize surface with no feedback. */}
            <span className={`${accent} bg-blue-500 transition-all`} />
          </div>
        );
        const corner = (dir: 'ne' | 'nw' | 'se' | 'sw', cursor: string, style: React.CSSProperties) => (
          <div
            key={dir}
            data-side-panel-resize={dir}
            onPointerDown={startFloatDrag(dir)}
            aria-hidden="true"
            className={`fixed z-[63] ${cursor} touch-none`}
            style={style}
          />
        );

        return (
          <>
            {edge('n', 'cursor-ns-resize', { ...spanX, top: Math.max(0, top - northH + 1), height: northH }, 'w-full h-0 group-hover/grip:h-1')}
            {edge('s', 'cursor-ns-resize', { ...spanX, top: Math.min(southOuter, vh - southH), height: southH }, 'w-full h-0 group-hover/grip:h-1')}
            {edge('w', 'cursor-ew-resize', { ...spanY, left: Math.max(0, left - westW + 1), width: westW }, 'h-full w-0 group-hover/grip:w-1')}
            {edge('e', 'cursor-ew-resize', { ...spanY, left: Math.min(eastOuter, vw - eastW), width: eastW }, 'h-full w-0 group-hover/grip:w-1')}
            {corner('nw', 'cursor-nwse-resize', { left: cLeft, top: cTop, width: cornerW, height: cornerN })}
            {corner('ne', 'cursor-nesw-resize', { left: cRight, top: cTop, width: cornerE, height: cornerN })}
            {corner('sw', 'cursor-nesw-resize', { left: cLeft, top: cBottom, width: cornerW, height: cornerS })}
            {corner('se', 'cursor-nwse-resize', { left: cRight, top: cBottom, width: cornerE, height: cornerS })}
          </>
        );
      })()}

      {/* Full-viewport overlay during resize - neutralizes iframes / ReactFlow
       *  / any child element that would otherwise swallow mousemove/mouseup
       *  and leave the panel stuck to the cursor. */}
      {(isResizing || isFloatDragging) && (
        <div
          data-side-panel-drag-overlay
          className="fixed inset-0 z-[99]"
          style={{ cursor: floatDragMode ? FLOATING_DRAG_CURSOR[floatDragMode] : (isBottom ? 'ns-resize' : 'ew-resize') }}
          aria-hidden="true"
        />
      )}

      {/* Panel container - flex sibling on desktop, fixed overlay on mobile */}
      <div
        ref={panelRef}
        data-testid="side-panel"
        data-side-panel-floating={isFloating || undefined}
        data-side-panel-maximized={isMaximized || undefined}
        className={cn(
          'bg-theme-primary overflow-hidden flex-shrink-0 border-theme',
          // A ternary, not an additive override: the dock's `border-l` / `border-t`
          // and the detached card's `border rounded-2xl` are utilities of the same
          // specificity, so cancelling them with a `border-0` would come down to
          // which one Tailwind happened to emit last. Full screen simply does not
          // ask for them.
          //
          // The SAME tier as the detached card, deliberately, rather than a new one
          // above it: z-[61] is the lowest value that clears the desktop sidebar
          // (`md:relative z-[60]` while expanded), and every step higher is a step
          // over more of the app's portalled surfaces. The floating grips at
          // z-[62]/z-[63] are not rendered in this mode, so nothing is left to
          // compete with, and modals (z-[9999] and up) still paint over the panel.
          isMaximized
            ? 'fixed inset-0 z-[61]'
            : isFloating
              // Chrome only while the window is actually open: closed, the card is a
              // 0x0 box, and a border plus a shadow on it paints a dot on the page.
              // Above the sidebar's own z-[60] (its open mobile-drawer state, which
              // survives a resize up to desktop width): a window buried under an
              // opaque sidebar cannot be grabbed by its title bar.
              ? cn('fixed z-[61]', isVisible && 'border rounded-2xl shadow-2xl')
              : (isBottom ? 'w-full border-t' : 'border-l'),
          !isMaximized && isMobile && 'fixed right-0 top-0 h-full z-[40]',
        )}
        style={{
          // Detached: a full rect, fixed, so it takes no layout space and the main
          // area spans the whole width behind it. Docked: bottom resizes HEIGHT
          // (full width), right resizes WIDTH (full height).
          ...(isMaximized
            ? {
                // `inset-0` already sizes it: an explicit width/height here would
                // reinstate the dock's box, and a transition would animate a jump
                // between two boxes that share no edge.
                transition: 'none',
              }
            : isFloating
            ? {
                left: `${floatRect.left}px`,
                top: `${floatRect.top}px`,
                // Collapsed to nothing when closed - keepMounted tabs stay in the
                // React tree (SSE, ReactFlow) exactly as they do in a dock.
                // Collapsed the card renders at a fixed compact size instead of its
                // rect. The rect itself is untouched, which is what makes expanding
                // exact rather than approximate.
                width: isVisible ? `${isCollapsed ? COLLAPSED_WIDTH : floatRect.width}px` : 0,
                height: isVisible ? `${isCollapsed ? COLLAPSED_HEIGHT : floatRect.height}px` : 0,
                // Never animated. left/top are set every frame by the drag and
                // cannot transition, so animating only width/height gave half an
                // animation - and left the (untransitioned) grips trailing the
                // edges they sit on after a keyboard resize or a viewport clamp.
                transition: 'none',
              }
            : isBottom
            ? { height: `${currentWidth}px`, transition: isResizing || instantResize ? 'none' : 'height 0.3s ease-in-out' }
            : { width: `${currentWidth}px`, transition: isResizing || instantResize ? 'none' : 'width 0.3s ease-in-out' }),
          // Safe area insets for notch devices
          ...(isMobile ? {
            paddingTop: 'env(safe-area-inset-top, 0px)',
            paddingBottom: 'env(safe-area-inset-bottom, 0px)',
          } : {}),
        }}
      >
        {/* Render panel internals when visible OR when keepMounted tabs exist.
         *  keepMounted tabs stay in the React tree at all times (SSE, ReactFlow, etc.)
         *  - only their CSS visibility is toggled. */}
        {((hasBeenOpened && isVisible) || tabs.some(t => t.keepMounted)) && (
          <div className="h-full flex flex-col relative min-w-0 overflow-hidden">
            {/* Collapsed window - the whole panel reduced to one row.
                Kept OUTSIDE the `isVisible` tab-bar block below rather than folded
                into it: collapsing must not touch the panel's open/closed state or
                its tabs, so the tab bar and the content are simply not rendered and
                everything they hold is restored untouched on expand. */}
            {isCollapsed && isVisible && (
              <div
                data-side-panel-collapsed-row
                /* Plays once, on mount, and this row is only ever mounted by a
                   collapse - so the cue needs no state, no timer and nothing to
                   reset. See `side-panel-collapse-hint` in globals.css for the
                   reduced-motion variant. */
                className="side-panel-collapse-hint flex-1 min-h-0 flex items-center pr-1 rounded-2xl"
              >
                {/* The row is BOTH the drag surface and the expand control: a click
                    anywhere on it reopens the window, a drag moves it. The tab bar
                    that carries the grip is not rendered at this size, and a strip
                    of its own is height the collapsed window cannot afford, so the
                    whole 36px row takes the job. */}
                <button
                  type="button"
                  data-side-panel-expand
                  aria-expanded={false}
                  aria-controls={panelBodyId}
                  /* Move only: the strip paints a fixed box, so a resize could not
                     change anything on screen and is refused. */
                  aria-keyshortcuts="ArrowLeft ArrowRight ArrowUp ArrowDown"
                  /* The row's only text is the TAB's label, and text content wins
                     over `title` for the accessible name - so without this a screen
                     reader announced the tab and never the control's purpose. */
                  /* Composed, not replaced: the tab label alone would read as a
                     destination rather than an action, but dropping it tells a
                     screen-reader user to "expand the panel" without ever saying
                     WHICH panel, while the sighted user reads the name right there. */
                  aria-label={activeTab
                    ? `${tSidePanel('expandWindow')}: ${activeTab.label}`
                    : tSidePanel('expandWindow')}
                  title={tSidePanel('expandWindowHint')}
                  ref={expandRef}
                  onFocus={() => { rowHadFocusRef.current = true; }}
                  onBlur={() => { rowHadFocusRef.current = false; }}
                  onPointerDown={handleCollapsedPointerDown}
                  onKeyDown={handleTitleBarKeyDown}
                  onClick={handleCollapsedClick}
                  /* The app clears every default outline, so a control the code
                     deliberately focuses must bring its own indicator - and while
                     collapsed this row is the entire window. */
                  className="flex-1 min-w-0 h-full flex items-center gap-2 px-2 text-left rounded-lg touch-none cursor-grab active:cursor-grabbing hover:bg-[var(--bg-secondary)] transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-[var(--color-primary,#6366f1)]"
                >
                  <ChevronsUpDown className="side-panel-collapse-hint-chevron h-3.5 w-3.5 flex-shrink-0 text-theme-secondary" />
                  {/* No fallback label: a panel with no tab has no name, and the
                      nearest string ("Add tab") reads as an action while this row
                      expands. The chevron and the tooltip carry the meaning. */}
                  {activeTab && (
                    <span className="flex-1 min-w-0 truncate text-sm font-medium text-theme-primary">
                      {activeTab.label}
                    </span>
                  )}
                </button>
                {/* Kept in the row because the tab bar that normally carries it is
                    not rendered while collapsed: a window nobody can close is worse
                    than a slightly wider row. */}
                <Button variant="ghost" size="icon" onClick={close} title={t('close')} className="w-7 h-7 flex-shrink-0">
                  <X className="h-3.5 w-3.5" />
                </Button>
              </div>
            )}
            {/* Tab bar - only when panel is visible */}
            {isVisible && !isCollapsed && (
              <div className="flex-shrink-0 border-b border-theme bg-theme-primary">
                <div className="flex items-center h-14 px-1.5">
                  {/* Title bar - the window's drag surface, detached only.
                      It rides IN the tab bar rather than in a strip above it: a
                      dedicated strip is honest about being a title bar, but it
                      also spends 16px of a window whose whole point is to stay
                      small, and stacked above a 56px tab bar it read as padding.
                      A fixed grip at the head of the row costs no height at all.
                      It sits OUTSIDE the scrollable tab area on purpose - making
                      the tab bar itself draggable would fight tab reorder with a
                      mouse and swallow the touch scroll on a tablet, which are
                      the two places this window is used. `self-stretch` gives it
                      the row's full 56px, so the target is bigger than the strip
                      it replaces even though it is narrower. */}
                  {isFloating && !isMaximized && (
                    <div
                      data-side-panel-titlebar
                      /* Focusable, but NOT role="button": that promises Enter/Space
                         activation to assistive tech, and this control has no action -
                         it is a grab handle whose keyboard verbs are the arrow keys.
                         `group` rather than no role at all, because `aria-label` is only
                         reliably announced on an element whose role permits a name. */
                      role="group"
                      tabIndex={0}
                      aria-label={tSidePanel('moveWindowHint')}
                      aria-keyshortcuts="ArrowLeft ArrowRight ArrowUp ArrowDown Shift+ArrowLeft Shift+ArrowRight Shift+ArrowUp Shift+ArrowDown Control+Shift+ArrowLeft Control+Shift+ArrowRight Control+Shift+ArrowUp Control+Shift+ArrowDown"
                      onPointerDown={handleTitleBarPointerDown}
                      onKeyDown={handleTitleBarKeyDown}
                      title={tSidePanel('moveWindow')}
                      /* Same handle the inspector panel uses, down to the glyph,
                         the grab cursor and the hover plate: two windows that move
                         the same way should not look like two different controls. */
                      className="flex-shrink-0 self-stretch w-6 pointer-coarse:w-7 flex items-center justify-center touch-none cursor-grab active:cursor-grabbing rounded-lg text-theme-secondary hover:text-theme-primary hover:bg-[var(--bg-secondary)] transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-[var(--color-primary,#6366f1)]"
                    >
                      {/* VERTICAL, like the inspector's: the dots run along the
                          axis of the handle, and this handle is a tall narrow band
                          at the head of the row, not a wide flat strip. */}
                      <GripVertical className="h-4 w-4" />
                    </div>
                  )}
                  {/* Scrollable tab area */}
                  <div className="flex-1 min-w-0 flex items-center gap-1 overflow-x-auto overflow-y-hidden">
                    {tabs.map((tab, index) => {
                      const isActive = tab.id === activeTabId;
                      const isDragged = draggedIndex === index;
                      const isDropTarget = dropTargetIndex === index;
                      return (<React.Fragment key={tab.id}>
                        <button
                          type="button"
                          draggable={tabs.length > 1}
                          onClick={() => setActiveTab(tab.id)}
                          onDragStart={(e) => handleDragStart(e, index)}
                          onDragOver={(e) => handleDragOver(e, index)}
                          onDrop={(e) => handleDrop(e, index)}
                          onDragEnd={handleDragEnd}
                          aria-pressed={isActive}
                          data-active={isActive ? 'true' : undefined}
                          data-testid="side-panel-tab"
                          className={cn(
                            panelTabClass(isActive),
                            // Asymmetric padding: room on the right for the close/menu
                            // control. twMerge only lets `px` override an earlier
                            // `pl`/`pr`, never the reverse, so the inherited `px-4`
                            // stays in the attribute; Tailwind emits the longhands
                            // after the shorthand, so these still win.
                            'min-w-0 max-w-[200px] flex-shrink-0 pl-3',
                            tab.pinned ? 'pr-3' : 'pr-8',
                            isDragged && 'opacity-40',
                          )}
                          style={isDropTarget ? {
                            boxShadow: draggedIndex !== null && draggedIndex < index
                              ? 'inset -2px 0 0 0 var(--color-primary, #6366f1)'
                              : 'inset 2px 0 0 0 var(--color-primary, #6366f1)',
                          } : undefined}
                        >
                          {/* Shimmer effect */}
                          {tab.shimmer && !isActive && (
                            <span
                              className="absolute inset-0 rounded-xl pointer-events-none overflow-hidden"
                              style={{
                                backgroundImage: `linear-gradient(90deg, transparent 0%, ${
                                  tab.shimmerColor || 'rgba(59, 130, 246, 0.15)'
                                } 50%, transparent 100%)`,
                                backgroundSize: '200% 100%',
                                animation: 'shimmer-scan 4s ease-in-out infinite',
                              }}
                            />
                          )}
                          {/* Drag handle - overlays on hover, takes no space */}
                          {tabs.length > 1 && (
                            <GripVertical className="h-2.5 w-2.5 absolute left-1/2 -translate-x-1/2 top-0 rotate-90 opacity-0 group-hover:opacity-50 cursor-grab transition-opacity" />
                          )}
                          <span className="flex-shrink-0">{tab.icon}</span>
                          <span className="truncate">{tab.label}</span>
                          {(() => {
                            const deleteHandler = tab.pinned || isSharedMode ? undefined : getTabDeleteHandler(tab);
                            const hasNavUrl = !isSharedMode && getTabResourceUrl(tab.id);
                            const showMenu = !tab.pinned && (hasNavUrl || deleteHandler);

                            if (tab.pinned) {
                              return <Pin className="h-2.5 w-2.5 ml-0.5 text-current opacity-60 rotate-45 flex-shrink-0" />;
                            }

                            if (showMenu) {
                              return (
                                <Popover open={openMenuTabId === tab.id} onOpenChange={(open) => setOpenMenuTabId(open ? tab.id : null)}>
                                  <PopoverTrigger asChild>
                                    <span
                                      role="button"
                                      tabIndex={0}
                                      onClick={(e) => e.stopPropagation()}
                                      className={cn(
                                        'absolute right-1.5 top-1/2 -translate-y-1/2 opacity-0 group-hover:opacity-100 focus:opacity-100 active:opacity-100 rounded-lg p-1 transition-opacity z-20',
                                        panelTabInnerHoverClass(isActive),
                                      )}
                                    >
                                      <MoreVertical className="h-3 w-3" />
                                    </span>
                                  </PopoverTrigger>
                                  <PopoverContent
                                    align="start"
                                    sideOffset={5}
                                    className="w-auto min-w-[160px] p-1.5 bg-theme-primary rounded-xl border border-theme shadow-lg"
                                    onClick={(e) => e.stopPropagation()}
                                  >
                                    {hasNavUrl && (
                                      <button
                                        type="button"
                                        onClick={(e) => {
                                          e.stopPropagation();
                                          setOpenMenuTabId(null);
                                          router.push(getTabResourceUrl(tab.id)!);
                                        }}
                                        className="w-full flex items-center gap-2.5 px-2.5 py-2 rounded-lg text-sm transition-colors text-theme-primary hover:bg-gray-100 dark:hover:bg-gray-800"
                                      >
                                        <ExternalLink className="h-3.5 w-3.5" />
                                        <span>{t('goToPage')}</span>
                                      </button>
                                    )}
                                    <button
                                      type="button"
                                      onClick={(e) => {
                                        e.stopPropagation();
                                        setOpenMenuTabId(null);
                                        handleCloseTab(tab.id);
                                      }}
                                      className="w-full flex items-center gap-2.5 px-2.5 py-2 rounded-lg text-sm transition-colors text-theme-primary hover:bg-gray-100 dark:hover:bg-gray-800"
                                    >
                                      <X className="h-3.5 w-3.5" />
                                      <span>{t('close')}</span>
                                    </button>
                                    {deleteHandler && (
                                      <button
                                        type="button"
                                        onClick={(e) => {
                                          e.stopPropagation();
                                          setOpenMenuTabId(null);
                                          deleteHandler();
                                        }}
                                        className="w-full flex items-center gap-2.5 px-2.5 py-2 rounded-lg text-sm transition-colors text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/30"
                                      >
                                        <Trash2 className="h-3.5 w-3.5" />
                                        <span>{t('delete')}</span>
                                      </button>
                                    )}
                                  </PopoverContent>
                                </Popover>
                              );
                            }

                            return (
                              <span
                                role="button"
                                tabIndex={0}
                                onClick={(e) => { e.stopPropagation(); handleCloseTab(tab.id); }}
                                onKeyDown={(e) => { if (e.key === 'Enter') { e.stopPropagation(); handleCloseTab(tab.id); } }}
                                className={cn(
                                  'absolute right-1.5 top-1/2 -translate-y-1/2 opacity-0 group-hover:opacity-100 focus:opacity-100 active:opacity-100 rounded-lg p-1 transition-opacity z-20',
                                  panelTabInnerHoverClass(isActive),
                                )}
                              >
                                <X className="h-3 w-3" />
                              </span>
                            );
                          })()}
                        </button>
                      </React.Fragment>);
                    })}
                    {/* Add tab - sticky so it stays visible when tabs overflow (hidden in shared mode) */}
                    {!isSharedMode && (
                      <div className="sticky right-0 flex items-center self-stretch flex-shrink-0 bg-theme-primary z-20">
                        <AddTabPicker variant="tab-bar" />
                      </div>
                    )}
                  </div>
                  {/* Window controls - always visible outside the scroll area */}
                  <div className="flex items-center flex-shrink-0 pl-1">
                    {/* Collapse - detached only, where a window can be in the way.
                        A docked panel already has close and the dock buttons for that.
                        Not while full screen: a maximised panel has no window to
                        shade, and the control that applies there is Restore. */}
                    {isFloating && !isSharedMode && !isMaximized && (
                      <Button
                        ref={collapseRef}
                        variant="ghost"
                        size="icon"
                        aria-expanded
                        aria-controls={panelBodyId}
                        onClick={() => { collapseWasFocusedRef.current = true; setCollapsed(true); }}
                        title={tSidePanel('collapseWindow')}
                        data-testid="side-panel-collapse"
                        className="w-7 h-7"
                      >
                        <ChevronsDownUp className="h-3.5 w-3.5" />
                      </Button>
                    )}
                    {/* Detach / re-attach. Desktop and tablet only: on a phone the
                        panel is a full-screen overlay, so there is nothing to float
                        it over. Hidden in shared mode like every other panel control.
                        Hidden while full screen as well: detaching there would move
                        a window nobody can see and land the user somewhere else the
                        moment they restore. */}
                    {!isMobile && !isSharedMode && !isMaximized && (
                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={toggleFloating}
                        aria-pressed={isFloating}
                        title={isFloating ? tSidePanel('attach') : tSidePanel('detach')}
                        data-testid="side-panel-detach"
                        className="w-7 h-7"
                      >
                        {isFloating
                          ? (lastDock === 'right' ? <PanelRight className="h-3.5 w-3.5" /> : <PanelBottom className="h-3.5 w-3.5" />)
                          : <PictureInPicture2 className="h-3.5 w-3.5" />}
                      </Button>
                    )}
                    {/* Full screen - next to Detach, and the same shape of control:
                        both answer "how much room does this panel get", one by
                        taking it out of the layout and one by giving it all of it.
                        Same gates as Detach (desktop/tablet, not shared mode), so
                        the pair either both show or both do not. */}
                    {!isMobile && !isSharedMode && (
                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={toggleMaximized}
                        aria-pressed={isMaximized}
                        title={isMaximized ? tSidePanel('restoreSize') : tSidePanel('maximize')}
                        data-testid="side-panel-maximize"
                        className="w-7 h-7"
                      >
                        {isMaximized
                          ? <Minimize2 className="h-3.5 w-3.5" />
                          : <Maximize2 className="h-3.5 w-3.5" />}
                      </Button>
                    )}
                    <Button
                      variant="ghost"
                      size="icon"
                      onClick={close}
                      title={t('close')}
                      className="w-7 h-7"
                    >
                      <X className="h-3.5 w-3.5" />
                    </Button>
                  </div>
                </div>

                {/* Sub-header for active tab (e.g. model selector) */}
                {activeTab?.subHeader}
              </div>
            )}

            {/* Tab content area */}
            <div id={panelBodyId} className="flex-1 min-h-0 flex flex-col overflow-hidden" style={isCollapsed ? { display: 'none' } : undefined}>
              {/* keepMounted tabs: always in DOM, visibility toggled via display */}
              {tabs.filter(t => t.keepMounted).map(tab => (
                <div
                  key={tab.id}
                  className="flex-1 min-h-0 flex flex-col"
                  style={{ display: (isVisible && tab.id === activeTabId) ? undefined : 'none' }}
                >
                  {tab.content}
                </div>
              ))}
              {/* Non-keepMounted active tab: only when panel is visible. Keyed
                  by tab id so switching between two app tabs (each id is
                  runId-scoped) REMOUNTS the content instead of reconciling one
                  instance in place - otherwise the previous app's per-instance
                  state (e.g. ApplicationTabContent's viewed epoch) bleeds into
                  the next app and renders the wrong/empty epoch. */}
              {isVisible && activeTab && !activeTab.keepMounted && (
                <div key={activeTab.id} className="flex-1 min-h-0 flex flex-col">
                  {activeTab.content}
                </div>
              )}
            </div>
          </div>
        )}
      </div>

      {/* Delete confirmation modal */}
      <BulkDeleteModal
        isOpen={!!pendingDeleteTab}
        title={pendingDeleteTab ? getDeleteTitle(pendingDeleteTab.id) : ''}
        message={pendingDeleteTab
          ? (pendingDeleteTab.failed
            // One translated sentence naming what was NOT deleted, rather than a
            // name concatenated to a generic string: the title still reads "Delete
            // agent", so a bare "An error occurred" leaves the user unsure which
            // resource it is about, and the separator would not be localized.
            ? tSidePanel('deleteFailed', { name: pendingDeleteTab.label })
            : getDeleteMessage(pendingDeleteTab.id, pendingDeleteTab.label))
          : ''}
        confirmLabel={t('delete')}
        cancelLabel={t('cancel')}
        onConfirm={confirmDelete}
        onCancel={() => setPendingDeleteTab(null)}
        isConfirming={isDeleting}
      />
    </TooltipProvider>
  );
}
