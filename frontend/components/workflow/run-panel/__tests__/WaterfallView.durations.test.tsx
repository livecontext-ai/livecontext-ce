/**
 * @vitest-environment jsdom
 *
 * The step waterfall draws what `stepDisplayDurationMs` answers, and its tooltip says the same
 * figure. Pinned here: a skipped or never-timed step draws no gauge and no "<1s", a parallel
 * split shows the time it held the epoch (with the summed work in the tooltip), and a run where
 * nothing was timed still renders.
 */
import React from 'react';
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/components/ui/tooltip', () => ({
  Tooltip: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  TooltipTrigger: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  // Rendered inline so the tooltip body can be asserted without hovering.
  TooltipContent: ({ children }: { children: React.ReactNode }) => <div data-testid="tooltip">{children}</div>,
}));
vi.mock('@/app/workflows/builder/components/nodes/shared', () => ({
  getIconSlug: () => '',
  NodeIcon: () => <span />,
  nodeIconRadiusClass: () => 'rounded',
}));
vi.mock('@/app/workflows/builder/nodes/nodeClasses', () => ({ findNodeClassById: () => null }));
vi.mock('@/components/workflow/StepRowActions', () => ({ StepRowActions: () => null }));
vi.mock('@/contexts/workflow-run/RunStateStore', () => ({
  TERMINAL_STATUSES: new Set<string>(),
  UNREVIVABLE_STATUSES: new Set<string>(),
}));

import { WaterfallView } from '../WaterfallView';
import type { StepEntry } from '../runFormatting';

afterEach(cleanup);

const findNode = () => undefined;

function rows() {
  return [...document.querySelectorAll('.py-0\\.5 > div')].filter(el => el.getAttribute('data-testid') !== 'tooltip');
}

describe('WaterfallView durations', () => {
  it('draws no gauge and no "<1s" for a skipped step, next to a timed one', () => {
    const steps: StepEntry[] = [
      { alias: 'core:fetch', status: 'completed', startTime: '2026-09-26T10:00:00Z', endTime: '2026-09-26T10:00:02Z', executionTimeMs: 2_000, elapsedMs: 2_000 },
      { alias: 'core:branch', status: 'skipped', startTime: '2026-09-26T10:00:02Z', endTime: '2026-09-26T10:00:02Z', executionTimeMs: 0, elapsedMs: 0, statusCounts: { skipped: 1 } },
    ];

    render(<WaterfallView steps={steps} findNodeForStep={findNode} showCumulative={false} />);

    const [timed, skipped] = rows();
    expect(timed).toHaveTextContent('2.0s');
    expect(skipped).not.toHaveTextContent('<1s');
    expect((skipped.querySelector('[style]') as HTMLElement).style.width).toBe('0%');
  });

  it('shows the time a parallel split held the epoch, and the summed work only in its tooltip', () => {
    const steps: StepEntry[] = [
      { alias: 'agent:summarize', status: 'completed', startTime: '2026-09-26T10:00:00Z', endTime: '2026-09-26T10:00:05Z',
        executionTimeMs: 50_000, elapsedMs: 5_000, statusCounts: { completed: 10 } },
    ];

    render(<WaterfallView steps={steps} findNodeForStep={findNode} showCumulative={false} />);

    expect(rows()[0]).toHaveTextContent('5.0s');
    expect(rows()[0]).not.toHaveTextContent('50s');
    const tooltip = screen.getByTestId('tooltip');
    expect(tooltip.querySelector('[data-step-total-work]')).toHaveTextContent('50s');
  });

  it('adds no "total work" line when the work equals the time held', () => {
    const steps: StepEntry[] = [
      { alias: 'core:fetch', status: 'completed', startTime: '2026-09-26T10:00:00Z', endTime: '2026-09-26T10:00:02Z', executionTimeMs: 2_000, elapsedMs: 2_000 },
    ];

    render(<WaterfallView steps={steps} findNodeForStep={findNode} showCumulative={false} />);

    expect(screen.getByTestId('tooltip').querySelector('[data-step-total-work]')).toBeNull();
  });

  it('renders a run where nothing was timed, without a single gauge or duration', () => {
    const steps: StepEntry[] = [
      { alias: 'core:a', status: 'completed', startTime: null, endTime: null },
      { alias: 'core:b', status: 'skipped', startTime: null, endTime: null },
    ];

    render(<WaterfallView steps={steps} findNodeForStep={findNode} showCumulative={false} />);

    for (const row of rows()) {
      expect(row).not.toHaveTextContent(/\d+(\.\d)?s|<1s/);
      expect((row.querySelector('[style]') as HTMLElement).style.width).toBe('0%');
    }
  });
});
