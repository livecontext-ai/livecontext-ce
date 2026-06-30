import { describe, it, expect } from 'vitest';
import { resolveFleetChipStatusCounts, RESOURCE_FAMILY_TYPES } from '../fleetChipStatusCounts';

describe('resolveFleetChipStatusCounts - resource-family chips (the bug)', () => {
  // The regression: resource-family leaves used to be stamped with the FAMILY
  // aggregate (agentToolStats.get('table')), so every table showed the same number
  // and the leaf-summing rollup multiplied it by the resource count. The fix keys
  // each leaf off its OWN resource id.
  const resourceStats = new Map([
    ['tbl-A', { successCount: 5, failureCount: 0 }],
    ['tbl-B', { successCount: 2, failureCount: 1 }],
  ]);

  it('gives each table its OWN per-resource count, not a shared family total', () => {
    const a = resolveFleetChipStatusCounts({ type: 'table', id: 'tbl-A', label: 'A' }, { resourceStats });
    const b = resolveFleetChipStatusCounts({ type: 'table', id: 'tbl-B', label: 'B' }, { resourceStats });

    expect(a).toEqual({ COMPLETED: 5, FAILED: 0 });
    expect(b).toEqual({ COMPLETED: 2, FAILED: 1 });
    // Distinct leaves get distinct counts - never the same replicated number.
    expect(a).not.toEqual(b);
  });

  it('summing the per-resource leaves yields the honest family total (no ×N inflation)', () => {
    // What the category/aggregator rollup does: sum the leaves. With per-resource
    // counts that is 5+2 completed = 7, not (family total) × (2 tables).
    const a = resolveFleetChipStatusCounts({ type: 'table', id: 'tbl-A', label: 'A' }, { resourceStats })!;
    const b = resolveFleetChipStatusCounts({ type: 'table', id: 'tbl-B', label: 'B' }, { resourceStats })!;
    const totalCompleted = (a.COMPLETED ?? 0) + (b.COMPLETED ?? 0);
    const totalFailed = (a.FAILED ?? 0) + (b.FAILED ?? 0);
    expect({ COMPLETED: totalCompleted, FAILED: totalFailed }).toEqual({ COMPLETED: 7, FAILED: 1 });
  });

  it('returns undefined for a resource the agent never used (no fabricated count)', () => {
    expect(resolveFleetChipStatusCounts({ type: 'table', id: 'tbl-UNUSED', label: 'U' }, { resourceStats })).toBeUndefined();
  });

  it('coerces a numeric leaf id to string for the lookup (datasource ids are numeric)', () => {
    const stats = new Map([['123', { successCount: 4, failureCount: 0 }]]);
    expect(resolveFleetChipStatusCounts({ type: 'table', id: 123 as unknown as string, label: 'n' }, { resourceStats: stats }))
      .toEqual({ COMPLETED: 4, FAILED: 0 });
  });

  it('covers every resource family (interface/workflow/skill/application) by id', () => {
    const stats = new Map([['x', { successCount: 1, failureCount: 0 }]]);
    for (const type of ['interface', 'workflow', 'skill', 'application']) {
      expect(RESOURCE_FAMILY_TYPES.has(type)).toBe(true);
      expect(resolveFleetChipStatusCounts({ type, id: 'x', label: 'l' }, { resourceStats: stats }))
        .toEqual({ COMPLETED: 1, FAILED: 0 });
    }
  });
});

describe('resolveFleetChipStatusCounts - tool / web_search chips', () => {
  it('keys a tool chip by its normalized tool name', () => {
    const toolStats = new Map([['gmail_send_email', { successCount: 3, failureCount: 1 }]]);
    expect(resolveFleetChipStatusCounts(
      { type: 'tool', id: 't1', label: 'Send Email', toolName: 'Gmail Send-Email' },
      { toolStats },
    )).toEqual({ COMPLETED: 3, FAILED: 1 });
  });

  it('falls back to the label when no toolName is provided', () => {
    const toolStats = new Map([['web_search', { successCount: 2, failureCount: 0 }]]);
    expect(resolveFleetChipStatusCounts({ type: 'web_search', id: 'web-search', label: 'Web Search' }, { toolStats }))
      .toEqual({ COMPLETED: 2, FAILED: 0 });
  });

  it('returns undefined when the tool has no recorded stat', () => {
    expect(resolveFleetChipStatusCounts({ type: 'tool', id: 't', label: 'X' }, { toolStats: new Map() })).toBeUndefined();
  });
});

describe('resolveFleetChipStatusCounts - model chip', () => {
  it('carries BUDGET_EXHAUSTED as a separate subset of FAILED', () => {
    const modelStats = new Map([['claude-opus-4-8', { successCount: 10, failureCount: 3, budgetExhaustedCount: 2 }]]);
    expect(resolveFleetChipStatusCounts(
      { type: 'model', id: 'model', label: 'claude-opus-4-8' },
      { modelStats, modelName: 'claude-opus-4-8' },
    )).toEqual({ COMPLETED: 10, FAILED: 3, BUDGET_EXHAUSTED: 2 });
  });

  it('returns undefined when the model has neither success nor failure', () => {
    const modelStats = new Map([['m', { successCount: 0, failureCount: 0 }]]);
    expect(resolveFleetChipStatusCounts({ type: 'model', id: 'model', label: 'm' }, { modelStats, modelName: 'm' }))
      .toBeUndefined();
  });
});
