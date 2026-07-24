'use client';

/**
 * Reading direction of the workflow builder canvas.
 *
 * `horizontal` is the historical layout (trigger on the left, flow runs right).
 * `vertical` reads top-down (trigger on top, flow runs down) like the agent fleet
 * canvas already does, and like most workflow tools.
 *
 * There are TWO layers, both flowing through this one `direction` value:
 *   - The user's per-workspace DEFAULT for NEW workflows: a client preference (like
 *     the theme and the side-panel dock) stored in localStorage, no backend
 *     round-trip, scoped per workspace (mirroring `SidePanelLayoutContext`). Written
 *     by `setDirection` (the account Settings preference).
 *   - The ACTIVE direction of the workflow currently open, which is that workflow's
 *     identity: it is persisted in the workflow PLAN (`plan.layoutDirection`), seeded
 *     back onto the canvas on load, and overridable live by the in-canvas toggle.
 *     Written IN MEMORY ONLY by `setWorkflowDirection` (never localStorage), so a
 *     per-workflow choice never overwrites the account default.
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
  /** The ACTIVE direction the canvas renders in. */
  direction: WorkflowLayoutDirection;
  /**
   * Set the direction as the user's GLOBAL default (persisted to localStorage). Used
   * by the account Settings preference: it is the default for NEW workflows.
   */
  setDirection: (direction: WorkflowLayoutDirection) => void;
  /**
   * Set the active direction for THIS workflow only, in memory, WITHOUT touching the
   * global preference. Used by (a) the loader, seeding from the plan's stored
   * direction, and (b) the in-canvas toggle, whose choice is saved into the plan on
   * save rather than into the user's global preference.
   */
  setWorkflowDirection: (direction: WorkflowLayoutDirection) => void;
}

const WorkflowLayoutDirectionContext = createContext<WorkflowLayoutDirectionContextValue | null>(null);

const STORAGE_PREFIX = 'lc.workflow.layoutDirection';

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
   * Pin the direction, ignoring the stored preference, and make the setter a no-op.
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
  const [direction, setDirectionState] = useState<WorkflowLayoutDirection>(
    forcedDirection ?? DEFAULT_WORKFLOW_LAYOUT_DIRECTION,
  );

  // Re-read on mount AND whenever the workspace changes: the preference is per-org.
  // Skipped when the direction is forced (the fleet), which owns its own value.
  useEffect(() => {
    if (forcedDirection) return;
    const stored = readStoredDirection(currentOrgId);
    // eslint-disable-next-line react-hooks/set-state-in-effect -- syncing from an
    // external store (localStorage) on mount/org-switch; cannot run during render
    // without breaking hydration.
    setDirectionState(stored ?? DEFAULT_WORKFLOW_LAYOUT_DIRECTION);
  }, [currentOrgId, forcedDirection]);

  const setDirection = useCallback(
    (next: WorkflowLayoutDirection) => {
      if (forcedDirection) return; // pinned: ignore writes
      setDirectionState(next);
      try {
        window.localStorage.setItem(storageKey(currentOrgId), next);
      } catch {
        // Storage unavailable: keep the in-memory choice for this session.
      }
    },
    [currentOrgId, forcedDirection],
  );

  // Per-workflow: change the active direction in memory only. Does NOT write the
  // global preference (the workflow's choice belongs in its plan, saved on save).
  const setWorkflowDirection = useCallback(
    (next: WorkflowLayoutDirection) => {
      if (forcedDirection) return; // pinned surfaces (the fleet) ignore this too
      setDirectionState(next);
    },
    [forcedDirection],
  );

  const effective = forcedDirection ?? direction;
  const value = useMemo(
    () => ({ direction: effective, setDirection, setWorkflowDirection }),
    [effective, setDirection, setWorkflowDirection],
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
      setDirection: () => {},
      setWorkflowDirection: () => {},
    }
  );
}
