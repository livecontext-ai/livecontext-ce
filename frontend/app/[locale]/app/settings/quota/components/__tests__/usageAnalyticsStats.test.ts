import { describe, it, expect } from 'vitest';
import {
  periodTotals,
  previousTotals,
  relativeChange,
  usageByType,
  usageByModel,
  topWithRest,
  peakDay,
  runwayDays,
} from '../usageAnalyticsStats';

const daily = [
  { date: '2026-09-01', sourceType: 'AGENT_EXECUTION', count: 2, credits: 30, tokens: 900 },
  { date: '2026-09-01', sourceType: 'CHAT_CONVERSATION', count: 5, credits: 10, tokens: 400 },
  { date: '2026-09-02', sourceType: 'AGENT_EXECUTION', count: 1, credits: 60, tokens: 300 },
  { date: '2026-09-02', sourceType: 'WORKFLOW_NODE', count: 4, credits: 4, tokens: 0 },
];
const noWorkflowNode = (t: string) => t !== 'WORKFLOW_NODE';

describe('periodTotals', () => {
  it('sums spend, calls and tokens of the period', () => {
    expect(periodTotals(daily)).toEqual({ credits: 104, calls: 12, tokens: 1600 });
  });

  it('leaves out the types the edition hides, so the cards add up to the chart', () => {
    expect(periodTotals(daily, noWorkflowNode)).toEqual({ credits: 100, calls: 8, tokens: 1600 });
  });

  it('reads numbers the server sent as strings (BigDecimal credits)', () => {
    expect(periodTotals([{ date: 'd', sourceType: 'X', count: '3' as never, credits: '1.5' as never, tokens: 0 }]).credits).toBe(1.5);
  });
});

describe('previousTotals and relativeChange', () => {
  it('regression - no comparison from the server is "unknown", never "you spent nothing before"', () => {
    // An older backend sends no previousModelUsage: reading it as zero would show every figure
    // as a rise with no base.
    expect(previousTotals(undefined)).toBeNull();
    expect(relativeChange(100, null)).toBeNull();
  });

  it('an empty previous period is zero, and a rise from zero has no percentage', () => {
    expect(previousTotals([])).toEqual({ credits: 0, calls: 0, tokens: 0 });
    expect(relativeChange(100, 0)).toBeNull();
  });

  it('computes the change against the previous period', () => {
    const before = previousTotals([
      { provider: 'a', model: 'm', sourceType: 'AGENT_EXECUTION', credits: 80, count: 4, tokens: 1 },
      { provider: null, model: null, sourceType: 'WORKFLOW_NODE', credits: 20, count: 9, tokens: 0 },
    ], noWorkflowNode);
    expect(before).toEqual({ credits: 80, calls: 4, tokens: 1 });
    expect(relativeChange(100, before!.credits)).toBeCloseTo(0.25);
    expect(relativeChange(60, 80)).toBeCloseTo(-0.25);
  });
});

describe('usageByType', () => {
  it('ranks the types by spend with their share of the period', () => {
    const rows = usageByType(daily, noWorkflowNode);
    expect(rows.map((r) => r.sourceType)).toEqual(['AGENT_EXECUTION', 'CHAT_CONVERSATION']);
    expect(rows[0]).toMatchObject({ credits: 90, calls: 3, tokens: 1200 });
    expect(rows[0].share).toBeCloseTo(0.9);
    expect(rows[1].share).toBeCloseTo(0.1);
  });
});

describe('usageByModel', () => {
  const rows = [
    { provider: 'anthropic', model: 'claude-sonnet-5', sourceType: 'AGENT_EXECUTION', credits: 50, count: 2, tokens: 100 },
    { provider: 'anthropic', model: 'claude-sonnet-5', sourceType: 'CHAT_CONVERSATION', credits: 10, count: 3, tokens: 50 },
    { provider: 'openai', model: 'gpt-5', sourceType: 'CHAT_CONVERSATION', credits: 30, count: 1, tokens: 10 },
    { provider: 'Google Gemini', model: null, sourceType: 'PLATFORM_MARKUP', credits: 6, count: 3, tokens: 0 },
    { provider: null, model: null, sourceType: 'WEB_SEARCH', credits: 4, count: 4, tokens: 0 },
  ];

  it('merges a model billed under several types into one row', () => {
    const byModel = usageByModel(rows);
    expect(byModel[0]).toMatchObject({ provider: 'anthropic', model: 'claude-sonnet-5', credits: 60, calls: 5, tokens: 150 });
    expect(byModel[0].share).toBeCloseTo(0.6);
  });

  it('keeps every spend with no model on ONE row, so the shares still add up to the whole', () => {
    const byModel = usageByModel(rows);
    const noModel = byModel.filter((r) => r.model === null);
    expect(noModel).toHaveLength(1);
    expect(noModel[0]).toMatchObject({ provider: null, credits: 10, calls: 7 });
    expect(byModel.reduce((s, r) => s + r.share, 0)).toBeCloseTo(1);
  });

  it('keeps the same model name from two providers apart', () => {
    const byModel = usageByModel([
      { provider: 'openai', model: 'm', sourceType: 'X', credits: 1, count: 1, tokens: 0 },
      { provider: 'azure', model: 'm', sourceType: 'X', credits: 2, count: 1, tokens: 0 },
    ]);
    expect(byModel.map((r) => r.provider)).toEqual(['azure', 'openai']);
  });
});

describe('topWithRest', () => {
  const rows = usageByType([
    { date: 'd', sourceType: 'A', count: 1, credits: 50, tokens: 0 },
    { date: 'd', sourceType: 'B', count: 1, credits: 30, tokens: 0 },
    { date: 'd', sourceType: 'C', count: 2, credits: 15, tokens: 0 },
    { date: 'd', sourceType: 'D', count: 3, credits: 5, tokens: 0 },
  ]);

  it('folds everything past the limit into one line that keeps its spend and share', () => {
    const { top, rest } = topWithRest(rows, 2);
    expect(top.map((r) => r.sourceType)).toEqual(['A', 'B']);
    expect(rest).toMatchObject({ credits: 20, calls: 5, count: 2 });
    expect(rest!.share).toBeCloseTo(0.2);
  });

  it('folds nothing when the rows fit', () => {
    expect(topWithRest(rows, 4).rest).toBeNull();
  });
});

describe('peakDay', () => {
  it('names the costliest day across types', () => {
    expect(peakDay(daily)).toEqual({ date: '2026-09-02', credits: 64 });
  });

  it('has no peak when nothing was spent', () => {
    expect(peakDay([{ date: 'd', sourceType: 'A', count: 1, credits: 0, tokens: 0 }])).toBeNull();
  });
});

describe('runwayDays', () => {
  it('divides the balance by the average daily spend', () => {
    expect(runwayDays(1000, 30)).toBe(33);
  });

  it('says nothing it cannot back: unknown balance, empty balance, or no spend', () => {
    expect(runwayDays(null, 30)).toBeNull();
    expect(runwayDays(0, 30)).toBeNull();
    expect(runwayDays(1000, 0)).toBeNull();
  });
});
