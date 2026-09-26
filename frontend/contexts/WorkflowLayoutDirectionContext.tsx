'use client';

/**
 * Reading direction of the workflow builder canvas.
 *
 * `horizontal` is the historical layout (trigger on the left, flow runs right).
 * `vertical` reads top-down (trigger on top, flow runs down) like the agent fleet
 * canvas already does, and like most workflow tools.
 *
 * There are TWO layers:
 *   - The user's per-workspace DEFAULT: a client preference (like the theme and the
 *     side-panel dock) stored in localStorage, no backend round-trip, scoped per
 *     workspace (mirroring `SidePanelLayoutContext`). Held by
 *     `WorkflowLayoutDirectionProvider` (mounted once by the app layout) and written by
 *     `setDirection` (Settings > Preferences). It decides only how a plan that carries
 *     no layout information at all is read.
 *   - The direction of ONE canvas, which is that workflow's identity: persisted in its
 *     plan (`plan.layoutDirection`) on save and read back on load. Held by a
 *     `WorkflowCanvasDirectionScope` that every builder mounts around itself, so two
 *     canvases on screen at once (a workflow and the sub-workflow opened from it in the
 *     side panel) each keep their own. With a single app-wide value, opening a
 *     horizontal sub-workflow flipped the vertical parent behind it, and the parent's
 *     next Save stamped the wrong direction into its plan for good.
 *
 * The direction drives THREE things, and they must stay in agreement or the canvas
 * contradicts itself:
 *   1. dagre's `rankdir` (LayoutService) - where auto-layout puts the nodes,
 *   2. the node handles (`getHandleGeometry`) - which edges of the box connect,
 *   3. the node side-attachments (`NodeBottomBar` & co) - which edge is free to
 *      hang buttons off, since the flow edge is taken by handles.
 * Read it from `useWorkflowLayoutDirection()` rather than threading a prop: the
 * node components are mounted by ReactFlow from a type registry, so a prop cannot
 * reach them.
 */

import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { useCurrentOrg } from '@/lib/stores/current-org-store';

export type WorkflowLayoutDirection = 'horizontal' | 'vertical';

/**
 * Horizontal, deliberately: every existing workflow was authored and positioned
 * left-to-right, so defaulting to vertical would silently re-read every canvas the
 * user already knows. Vertical is opt-in from the preferences.
 */
export const DEFAULT_WORKFLOW_LAYOUT_DIRECTION: WorkflowLayoutDirection = 'horizontal';

interface WorkflowLayoutDirectionContextValue {
  /** The direction the canvas renders in. */
  direction: WorkflowLayoutDirection;
  /**
   * The stored per-workspace DEFAULT, unaffected by whatever workflow is open. Surfaces
   * that describe the default (Settings) read this; a canvas reads `direction`.
   */
  defaultDirection: WorkflowLayoutDirection;
  /**
   * The surface fixes its reading direction (`forcedDirection`): the marketplace preview
   * renders the publisher's direction, the fleet and the landing their own. Both setters
   * are then no-ops, and a plan loaded there is laid out in the pinned direction.
   */
  isPinned: boolean;
  /** Set the user's DEFAULT (persisted to localStorage). Settings > Preferences only. */
  setDirection: (direction: WorkflowLayoutDirection) => void;
  /**
   * Set the direction of THIS canvas only, in memory. Callers: the loader (the direction
   * a plan resolves to on load and on a version restore) and the in-canvas toggle (which
   * re-lays the graph out; the direction reaches the database with the next Save).
   */
  setWorkflowDirection: (direction: WorkflowLayoutDirection) => void;
}

const WorkflowLayoutDirectionContext = createContext<WorkflowLayoutDirectionContextValue | null>(null);

const STORAGE_PREFIX = 'lc.workflow.layoutDirection';

const NOOP = () => {};

export function isWorkflowLayoutDirection(value: string | null | undefined): value is WorkflowLayoutDirection {
  return value === 'horizontal' || value === 'vertical';
}

/** localStorage key for a given workspace (null org = personal workspace). */
function storageKey(orgId: string | null | undefined): string {
  return `${STORAGE_PREFIX}:${orgId ?? 'personal'}`;
}

function readStoredDirection(orgId: string | null | undefined): WorkflowLayoutDirection | null {
  if (typeof window === 'undefined') return null;
  try {
    const saved = window.localStorage.getItem(storageKey(orgId));
    return isWorkflowLayoutDirection(saved) ? saved : null;
  } catch {
    // Storage unavailable (private mode): fall back to the default.
    return null;
  }
}

export function WorkflowLayoutDirectionProvider({
  children,
  forcedDirection,
}: {
  children: React.ReactNode;
  /**
   * Pin the direction, ignoring the stored preference, and make the setters no-ops.
   * Used by surfaces that reuse the builder's node components but must NOT follow the
   * workflow preference, e.g. the agent fleet (its own always-TB canvas): without
   * this, flipping the workflow layout would silently move the fleet's node buttons.
   */
  forcedDirection?: WorkflowLayoutDirection;
}) {
  const { currentOrgId } = useCurrentOrg();
  // Seed the DEFAULT so the server render and the first client render agree; the
  // stored value is restored in an effect below (reading localStorage during render
  // would produce a hydration mismatch).
  const [defaultDirection, setDefaultDirectionState] = useState<WorkflowLayoutDirection>(
    forcedDirection ?? DEFAULT_WORKFLOW_LAYOUT_DIRECTION,
  );

  // Re-read on mount AND whenever the workspace changes: the preference is per-org.
  // Skipped when the direction is forced (the fleet), which owns its own value.
  useEffect(() => {
    if (forcedDirection) return;
    // Syncing from an external store (localStorage) on mount and on org switch; it cannot
    // run during render without breaking hydration, which is the case the rule allows for.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setDefaultDirectionState(readStoredDirection(currentOrgId) ?? DEFAULT_WORKFLOW_LAYOUT_DIRECTION);
  }, [currentOrgId, forcedDirection]);

  const setDirection = useCallback(
    (next: WorkflowLayoutDirection) => {
      if (forcedDirection) return; // pinned: ignore writes
      // The default only. An open canvas keeps its own direction (its plan's), so stating
      // a default in Settings never re-orients, or re-stamps on its next Save, a workflow
      // mounted behind that page.
      setDefaultDirectionState(next);
      try {
        window.localStorage.setItem(storageKey(currentOrgId), next);
      } catch {
        // Storage unavailable: keep the in-memory choice for this session.
      }
    },
    [currentOrgId, forcedDirection],
  );

  // No canvas lives directly under the provider (every builder mounts its own scope), so
  // there is no canvas direction to set at this level. Said out loud in development: a
  // canvas mounted without a scope would otherwise drop every direction change silently.
  const setWorkflowDirection = useCallback((_next: WorkflowLayoutDirection) => {
    if (process.env.NODE_ENV !== 'production') {
      console.warn('[WorkflowLayoutDirection] setWorkflowDirection outside a WorkflowCanvasDirectionScope is ignored');
    }
  }, []);

  const effective = forcedDirection ?? defaultDirection;
  const value = useMemo(
    () => ({
      direction: effective,
      defaultDirection: effective,
      isPinned: !!forcedDirection,
      setDirection,
      setWorkflowDirection,
    }),
    [effective, forcedDirection, setDirection, setWorkflowDirection],
  );

  return (
    <WorkflowLayoutDirectionContext.Provider value={value}>{children}</WorkflowLayoutDirectionContext.Provider>
  );
}

/**
 * The direction of ONE canvas. Mounted by `WorkflowBuilder` around itself, so every
 * canvas (the page, a side-panel tab, a run view, a preview) owns its own value.
 *
 * <p>Until the loader has resolved the plan it follows the enclosing value (the user's
 * default, or a pin). Under a pinned provider it stays pinned: the setter is a no-op and
 * `isPinned` tells the loader to lay the plan out in that direction.
 */
export function WorkflowCanvasDirectionScope({ children }: { children: React.ReactNode }) {
  const parent = useContext(WorkflowLayoutDirectionContext);
  const inherited = parent?.direction ?? DEFAULT_WORKFLOW_LAYOUT_DIRECTION;
  const isPinned = parent?.isPinned ?? false;
  const [own, setOwn] = useState<WorkflowLayoutDirection | null>(null);

  const setWorkflowDirection = useCallback(
    (next: WorkflowLayoutDirection) => {
      if (isPinned) return;
      setOwn(next);
    },
    [isPinned],
  );

  const direction = isPinned ? inherited : (own ?? inherited);
  const defaultDirection = parent?.defaultDirection ?? DEFAULT_WORKFLOW_LAYOUT_DIRECTION;
  const setDirection = parent?.setDirection ?? NOOP;
  const value = useMemo(
    () => ({ direction, defaultDirection, isPinned, setDirection, setWorkflowDirection }),
    [direction, defaultDirection, isPinned, setDirection, setWorkflowDirection],
  );

  return (
    <WorkflowLayoutDirectionContext.Provider value={value}>{children}</WorkflowLayoutDirectionContext.Provider>
  );
}

/** Throws outside the provider: use in canvas code that is always mounted under it. */
export function useWorkflowLayoutDirection(): WorkflowLayoutDirectionContextValue {
  const ctx = useContext(WorkflowLayoutDirectionContext);
  if (!ctx) {
    throw new Error('useWorkflowLayoutDirection must be used within a WorkflowLayoutDirectionProvider');
  }
  return ctx;
}

/**
 * Defaults + a no-op setter outside the provider, mirroring `useSidePanelLayoutSafe`.
 * Node components use THIS one: they are also mounted by surfaces that do not carry
 * the provider (the marketplace preview, the landing, a snapshot canvas), and a node
 * must never crash a page just because nobody declared a reading direction.
 */
export function useWorkflowLayoutDirectionSafe(): WorkflowLayoutDirectionContextValue {
  const ctx = useContext(WorkflowLayoutDirectionContext);
  return (
    ctx ?? {
      direction: DEFAULT_WORKFLOW_LAYOUT_DIRECTION,
      defaultDirection: DEFAULT_WORKFLOW_LAYOUT_DIRECTION,
      isPinned: false,
      setDirection: NOOP,
      setWorkflowDirection: NOOP,
    }
  );
}
