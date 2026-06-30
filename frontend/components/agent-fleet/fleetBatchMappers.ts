/**
 * Pure helpers that turn the Agent Fleet BATCH endpoint payloads (one flat list
 * for the whole fleet, each row keyed by `agentId`) into the per-agent lookups the
 * canvas builds. Kept separate from {@link useAgentFleetState} so the grouping +
 * trigger-merge contract is unit-testable without mounting the hook.
 */

import type { FleetTrigger } from '@/lib/api/orchestrator/agent.service';

export interface TriggerInfo {
  hasWebhook: boolean;
  hasSchedule: boolean;
  webhookUrl?: string;
  cronExpression?: string;
  timezone?: string;
}

/**
 * Group a flat batch list into a Map keyed by `agentId`. Rows with no agentId are
 * dropped (they belong to no agent); order within each agent's list is preserved.
 */
export function groupByAgentId<T extends { agentId?: string | null }>(rows: T[] | null | undefined): Map<string, T[]> {
  const grouped = new Map<string, T[]>();
  (rows || []).forEach(row => {
    const id = row?.agentId ? String(row.agentId) : null;
    if (!id) return;
    const list = grouped.get(id);
    if (list) list.push(row);
    else grouped.set(id, [row]);
  });
  return grouped;
}

/**
 * Build the agentId → trigger-badge lookup from the batch triggers payload. The
 * endpoint already returns only agents with an active webhook or enabled schedule,
 * but we keep the guard so a malformed/blank row never produces an empty badge.
 */
export function buildTriggerMap(rows: FleetTrigger[] | null | undefined): Map<string, TriggerInfo> {
  const map = new Map<string, TriggerInfo>();
  (rows || []).forEach(t => {
    if (!t?.agentId || !(t.hasWebhook || t.hasSchedule)) return;
    map.set(String(t.agentId), {
      hasWebhook: !!t.hasWebhook,
      hasSchedule: !!t.hasSchedule,
      webhookUrl: t.webhookUrl || undefined,
      cronExpression: t.cronExpression || undefined,
      timezone: t.timezone || undefined,
    });
  });
  return map;
}
