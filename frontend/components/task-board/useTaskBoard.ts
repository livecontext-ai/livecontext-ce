'use client';

import { useState, useCallback, useMemo, useEffect, useRef } from 'react';
import { useSearchParams } from 'next/navigation';
import { taskService } from '@/lib/api/orchestrator/task.service';
import { agentService } from '@/lib/api/orchestrator/agent.service';
import type { Task, TaskStats, TaskListParams, TaskPerson, TaskStatusConfig, TaskLabel } from '@/lib/api/orchestrator/task.types';
import type { Agent } from '@/lib/api/orchestrator/types';
import { useTaskPeople } from './useTaskPeople';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';
import {
  useTaskBoardStreamStore,
  useTaskBoardSubscription,
  applyStreamEvents,
} from './useTaskBoardStream';

export type TaskSortField = 'priority' | 'updated_at' | 'created_at' | 'due_by' | 'manual';

interface UseTaskBoardReturn {
  // Data
  tasks: Task[];
  stats: TaskStats | null;
  agents: Agent[];
  people: TaskPerson[];
  /** Configurable board columns (F4); empty until loaded, then the board falls back to defaults. */
  statuses: TaskStatusConfig[];
  /** Board label catalog (F2), for resolving a card's labelIds to name/color. */
  labels: TaskLabel[];
  total: number;
  loading: boolean;
  error: string | null;

  // Filters
  agentFilter: string | null;
  setAgentFilter: (agentId: string | null) => void;
  searchQuery: string;
  setSearchQuery: (q: string) => void;
  sortBy: TaskSortField;
  setSortBy: (s: TaskSortField) => void;

  // Actions
  refresh: () => void;
  selectedTaskId: string | null;
  setSelectedTaskId: (id: string | null) => void;
}

export function useTaskBoard(): UseTaskBoardReturn {
  const searchParams = useSearchParams();

  // URL-driven state
  const initialAgent = searchParams.get('agent') || null;

  const [agentFilter, setAgentFilterState] = useState<string | null>(initialAgent);
  const [searchQuery, setSearchQueryRaw] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [selectedTaskId, setSelectedTaskId] = useState<string | null>(null);

  // Data
  const [tasks, setTasks] = useState<Task[]>([]);
  const [stats, setStats] = useState<TaskStats | null>(null);
  const [agents, setAgents] = useState<Agent[]>([]);
  const [statuses, setStatuses] = useState<TaskStatusConfig[]>([]);
  const [labels, setLabels] = useState<TaskLabel[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [sortBy, setSortBy] = useState<TaskSortField>('priority');
  const [refreshKey, setRefreshKey] = useState(0);

  const setSearchQuery = useCallback((q: string) => {
    setSearchQueryRaw(q);
  }, []);

  const setAgentFilter = useCallback((agentId: string | null) => {
    setAgentFilterState(agentId);
  }, []);

  // Debounce search by 300ms
  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(searchQuery), 300);
    return () => clearTimeout(timer);
  }, [searchQuery]);

  const refresh = useCallback(() => setRefreshKey(k => k + 1), []);

  // Phase 3 (2026-05-18) - clear local task/agent/stats accumulators when
  // the active workspace changes. The WS task channel is tenant-only
  // (task:board:{numericUserId}) so events from a previous workspace can
  // leak into the new workspace via applyStreamEvents; resetting on switch
  // closes that window. The next refresh() / refetch cycle repopulates
  // with the new workspace's data.
  useOrgScopedReset(() => {
    setTasks([]);
    setStats(null);
    setAgents([]);
    setTotal(0);
    setError(null);
    setRefreshKey(k => k + 1);
  });

  // Build query params - always fetch all statuses for the kanban
  const queryParams = useMemo((): TaskListParams => ({
    status: undefined,         // all statuses
    assignedTo: agentFilter || undefined,
    search: debouncedSearch || undefined,
    page: 0,
    size: 200,                 // kanban needs all tasks
    sort: sortBy,
  }), [agentFilter, debouncedSearch, sortBy]);

  // Fetch agents once
  useEffect(() => {
    agentService.getAgents().then(r => setAgents(Array.isArray(r) ? r : [])).catch(() => {});
  }, []);

  // Board columns (F4) + label catalog (F2). Re-fetched on refresh / org switch.
  useEffect(() => {
    let cancelled = false;
    Promise.all([taskService.listStatuses(), taskService.listLabels()])
      .then(([s, l]) => {
        if (cancelled) return;
        setStatuses(Array.isArray(s) ? s : []);
        setLabels(Array.isArray(l) ? l : []);
      })
      .catch(() => { /* board falls back to the default columns */ });
    return () => { cancelled = true; };
  }, [refreshKey]);

  // Teammates (human assignees / reviewers) for the active workspace.
  const people = useTaskPeople();

  // Fetch tasks + stats (initial load + manual refresh)
  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);

    Promise.all([
      taskService.listTasks(queryParams),
      taskService.getStats(),
    ])
      .then(([listRes, statsRes]) => {
        if (cancelled) return;
        setTasks(Array.isArray(listRes?.tasks) ? listRes.tasks : []);
        setTotal(listRes?.total ?? 0);
        setStats(statsRes);
        setLoading(false);
      })
      .catch((err) => {
        if (cancelled) return;
        setError(err.message || 'Failed to load tasks');
        setLoading(false);
      });

    return () => { cancelled = true; };
  }, [queryParams, refreshKey]);

  // ─── WebSocket real-time updates ───

  // Subscribe to the WS channel
  useTaskBoardSubscription();

  // Apply incoming WS events to local state
  const streamSeq = useTaskBoardStreamStore((s) => s.seq);
  const flush = useTaskBoardStreamStore((s) => s.flush);

  // Track previous seq to detect new events
  const prevSeqRef = useRef(streamSeq);
  // Keep a ref to the current tasks so the effect can read without stale closures
  const tasksRef = useRef(tasks);
  tasksRef.current = tasks;

  useEffect(() => {
    if (streamSeq === prevSeqRef.current) return;
    prevSeqRef.current = streamSeq;

    const events = flush();
    if (events.length === 0) return;

    const { tasks: next, stats: newStats, changed } = applyStreamEvents(
      tasksRef.current,
      events,
      { assignedTo: agentFilter, search: debouncedSearch },
    );

    if (changed) {
      setTasks(next);
      if (newStats) {
        setStats(newStats);
        setTotal(next.length);
      }
    }
  }, [streamSeq, flush, agentFilter, debouncedSearch]);

  // Fallback: slow poll every 60s as safety net (in case WS disconnects)
  useEffect(() => {
    const interval = setInterval(refresh, 60_000);
    return () => clearInterval(interval);
  }, [refresh]);

  return {
    tasks, stats, agents, people, statuses, labels, total, loading, error,
    agentFilter, setAgentFilter,
    searchQuery, setSearchQuery,
    sortBy, setSortBy,
    refresh,
    selectedTaskId, setSelectedTaskId,
  };
}
