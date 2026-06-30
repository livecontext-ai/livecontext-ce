import { describe, expect, it } from 'vitest';

import { buildTriggerMap, groupByAgentId } from '../fleetBatchMappers';
import type { FleetTrigger } from '@/lib/api/orchestrator/agent.service';

describe('groupByAgentId', () => {
  it('groups flat batch rows by agentId, preserving per-agent order', () => {
    const rows = [
      { agentId: 'a', toolName: 'web_search' },
      { agentId: 'b', toolName: 'table' },
      { agentId: 'a', toolName: 'interface' },
    ];
    const grouped = groupByAgentId(rows);
    expect(grouped.get('a')).toEqual([
      { agentId: 'a', toolName: 'web_search' },
      { agentId: 'a', toolName: 'interface' },
    ]);
    expect(grouped.get('b')).toEqual([{ agentId: 'b', toolName: 'table' }]);
    expect(grouped.size).toBe(2);
  });

  it('drops rows with no agentId and tolerates null/undefined input', () => {
    const grouped = groupByAgentId([
      { agentId: 'a', n: 1 },
      { agentId: null, n: 2 },
      { agentId: undefined, n: 3 },
      { n: 4 } as any,
    ]);
    expect(grouped.size).toBe(1);
    expect(grouped.get('a')).toEqual([{ agentId: 'a', n: 1 }]);
    expect(groupByAgentId(null).size).toBe(0);
    expect(groupByAgentId(undefined).size).toBe(0);
  });

  it('coerces a non-string agentId to a string key', () => {
    const grouped = groupByAgentId([{ agentId: 42 as any, n: 1 }]);
    expect(grouped.has('42')).toBe(true);
  });
});

describe('buildTriggerMap', () => {
  const t = (over: Partial<FleetTrigger>): FleetTrigger => ({
    agentId: 'a', hasWebhook: false, hasSchedule: false, ...over,
  });

  it('maps webhook + schedule fields for agents that have a trigger', () => {
    const map = buildTriggerMap([
      t({ agentId: 'a', hasWebhook: true, webhookUrl: 'https://wh/a' }),
      t({ agentId: 'b', hasSchedule: true, cronExpression: '0 9 * * *', timezone: 'UTC' }),
      t({ agentId: 'c', hasWebhook: true, hasSchedule: true, webhookUrl: 'https://wh/c', cronExpression: '0 0 * * *' }),
    ]);
    expect(map.get('a')).toEqual({ hasWebhook: true, hasSchedule: false, webhookUrl: 'https://wh/a', cronExpression: undefined, timezone: undefined });
    expect(map.get('b')).toMatchObject({ hasSchedule: true, cronExpression: '0 9 * * *', timezone: 'UTC' });
    expect(map.get('c')).toMatchObject({ hasWebhook: true, hasSchedule: true });
  });

  it('skips rows with neither a webhook nor a schedule, and rows with no agentId', () => {
    const map = buildTriggerMap([
      t({ agentId: 'a', hasWebhook: false, hasSchedule: false }),
      t({ agentId: '', hasWebhook: true }),
      t({ agentId: undefined as any, hasSchedule: true }),
    ]);
    expect(map.size).toBe(0);
  });

  it('normalizes empty-string url/cron/timezone to undefined', () => {
    const map = buildTriggerMap([
      t({ agentId: 'a', hasWebhook: true, webhookUrl: '', cronExpression: '', timezone: '' }),
    ]);
    expect(map.get('a')).toEqual({ hasWebhook: true, hasSchedule: false, webhookUrl: undefined, cronExpression: undefined, timezone: undefined });
  });

  it('tolerates null/undefined input', () => {
    expect(buildTriggerMap(null).size).toBe(0);
    expect(buildTriggerMap(undefined).size).toBe(0);
  });
});
