'use client';

import { useCallback } from 'react';
import { useChannel } from '@/lib/websocket';
import { create } from 'zustand';
import { useAuth } from '@/lib/providers/smart-providers';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';
import type { Task, TaskStats } from '@/lib/api/orchestrator/task.types';

// ─── Event types from backend TaskBoardPublisher ───

interface TaskBoardEvent {
  event: 'task_created' | 'task_updated' | 'task_deleted';
  tenantId: string;
  /**
   * Workspace org the event belongs to. Set since 2026-05-18 backend fix so
   * the consumer can filter cross-workspace events (the WS channel is keyed
   * by tenantId only - events from a different workspace's tasks arrive on
   * the same socket).
   */
  organizationId?: string | null;
  timestamp: string;
  task?: Task;
  taskId?: string;
}

interface TaskBoardStreamFilters {
  assignedTo?: string | null;
  search?: string | null;
}

// ─── Zustand store ───

interface TaskBoardStreamStore {
  /** Monotonic counter - incremented on every WS event. Consumers use this to
   *  detect changes without deep-comparing the task array. */
  seq: number;
  /** Pending events buffered since last flush. */
  pendingEvents: TaskBoardEvent[];
  pushEvent: (event: TaskBoardEvent) => void;
  /** Flush pending events and return them. Resets the buffer. */
  flush: () => TaskBoardEvent[];
}

export const useTaskBoardStreamStore = create<TaskBoardStreamStore>((set, get) => ({
  seq: 0,
  pendingEvents: [],

  pushEvent: (event: TaskBoardEvent) => {
    set((s) => ({
      seq: s.seq + 1,
      pendingEvents: [...s.pendingEvents, event],
    }));
  },

  flush: () => {
    const events = get().pendingEvents;
    set({ pendingEvents: [] });
    return events;
  },
}));

// Phase 3 (2026-05-18) - HMR-safe module-singleton subscriber. The Zustand
// store above is module-level, so its accumulated `pendingEvents` / `seq`
// counter would survive a workspace switch and flush previous-workspace
// events into the new task board. We listen for `currentOrgId` flips and
// reset the store. The Symbol.for() key on globalThis survives Next.js
// fast-refresh module reloads; we dispose the prior subscriber before
// re-binding so there's exactly one live subscriber at all times.
if (typeof window !== 'undefined') {
  const HMR_KEY = Symbol.for('__lc_orgReset:useTaskBoardStreamStore');
  const g = globalThis as unknown as Record<symbol, (() => void) | undefined>;
  if (typeof g[HMR_KEY] === 'function') g[HMR_KEY]!();
  // Import the store lazily to avoid a circular import at module init.
  import('@/lib/stores/current-org-store').then(({ useCurrentOrgStore }) => {
    g[HMR_KEY] = useCurrentOrgStore.subscribe(
      (s) => s.currentOrgId,
      () => {
        useTaskBoardStreamStore.setState({ seq: 0, pendingEvents: [] });
      },
    );
  }).catch(() => {
    // best-effort; if the import fails the reset just doesn't fire
  });
}

/**
 * Subscribes to the task board WebSocket channel for the current user's tenant.
 * Events are pushed into the Zustand store for consumption by useTaskBoard.
 */
export function useTaskBoardSubscription() {
  // IMPORTANT: the backend publishes on `ws:task:board:{tenantId}` where tenantId is
  // the internal Long DB user id (see `TaskBoardPublisher` + `AgentTaskController` using
  // `X-User-ID` which the gateway sets to `userInfo.getUserId().toString()`). The WS
  // authorizer also compares against `session.userId` (same Long), NOT the Keycloak sub.
  // Subscribing with `user.sub` (the Keycloak UUID) silently fails: the gateway sends
  // "Access denied to channel" and no board events ever arrive.
  const { numericUserId } = useAuth();
  const pushEvent = useTaskBoardStreamStore((s) => s.pushEvent);

  const channel = numericUserId != null ? `task:board:${numericUserId}` : null;

  const handler = useCallback(
    (data: TaskBoardEvent) => {
      if (!data?.event) return;
      // Cross-workspace filter: the WS channel is keyed by tenantId only, so a
      // user with active sessions in two workspaces would see events from both.
      // Backend stamps payload.organizationId since 2026-05-18 - drop events
      // that don't match the caller's active workspace. Delete events lack
      // organizationId (no entity to read from); accept them - task IDs are
      // UUID-unique and a stale delete on a non-visible row is a no-op.
      if (data.event !== 'task_deleted') {
        const activeOrg = useCurrentOrgStore.getState().currentOrgId;
        const eventOrg = data.organizationId ?? null;
        if (activeOrg !== eventOrg) return;
      }
      pushEvent(data);
    },
    [pushEvent],
  );

  useChannel<TaskBoardEvent>(channel, handler);
}

/**
 * Apply buffered WS events to the local tasks array.
 * Returns new array + recomputed stats only if something changed (referential stability).
 */
export function applyStreamEvents(
  tasks: Task[],
  events: TaskBoardEvent[],
  filters: TaskBoardStreamFilters = {},
): { tasks: Task[]; stats: TaskStats | null; changed: boolean } {
  if (events.length === 0) return { tasks, stats: null, changed: false };

  let result = [...tasks];
  let changed = false;

  for (const evt of events) {
    switch (evt.event) {
      case 'task_created': {
        if (evt.task && taskMatchesFilters(evt.task, filters) && !result.some((t) => t.id === evt.task!.id)) {
          result = [evt.task, ...result];
          changed = true;
        }
        break;
      }
      case 'task_updated': {
        if (evt.task) {
          const idx = result.findIndex((t) => t.id === evt.task!.id);
          if (idx >= 0) {
            // Merge: preserve locally-loaded notes if the WS event has none
            // (TaskBoardPublisher uses TaskResponse.from(entity) which sends notes: [])
            const merged = { ...evt.task };
            if ((!merged.notes || merged.notes.length === 0) && result[idx].notes?.length > 0) {
              merged.notes = result[idx].notes;
            }
            if (taskMatchesFilters(merged, filters)) {
              result[idx] = merged;
            } else {
              result.splice(idx, 1);
            }
            changed = true;
          } else if (taskMatchesFilters(evt.task, filters)) {
            // Task isn't in the current array - either it arrived before the initial list
            // load finished, or its previous state was filtered out and now re-enters
            // the view (e.g. status changed to one the user is currently showing).
            // Insert it so live status transitions never silently disappear.
            result = [evt.task, ...result];
            changed = true;
          }
        }
        break;
      }
      case 'task_deleted': {
        const before = result.length;
        result = result.filter((t) => t.id !== evt.taskId);
        if (result.length !== before) changed = true;
        break;
      }
    }
  }

  return { tasks: result, stats: changed ? computeStats(result) : null, changed };
}

function taskMatchesFilters(task: Task, filters: TaskBoardStreamFilters): boolean {
  const assignedTo = filters.assignedTo?.trim();
  if (assignedTo) {
    if (assignedTo === 'unassigned') {
      if (task.assignedToAgentId) return false;
    } else if (task.assignedToAgentId !== assignedTo) {
      return false;
    }
  }

  const search = filters.search?.trim().toLowerCase();
  if (search && !task.title.toLowerCase().includes(search)) {
    return false;
  }

  return true;
}

function computeStats(tasks: Task[]): TaskStats {
  let pending = 0, inProgress = 0, inReview = 0, completed = 0, failed = 0, cancelled = 0, deleted = 0;
  for (const t of tasks) {
    switch (t.status) {
      case 'pending': pending++; break;
      case 'in_progress': inProgress++; break;
      case 'in_review': inReview++; break;
      case 'completed': completed++; break;
      case 'failed': failed++; break;
      case 'cancelled': cancelled++; break;
      case 'deleted': deleted++; break;
    }
  }
  const backlog = tasks.filter((t) => t.status === 'pending' && !t.assignedToAgentId).length;
  return { pending, inProgress, inReview, completed, failed, cancelled, deleted, backlog, total: tasks.length };
}
