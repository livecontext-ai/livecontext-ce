/**
 * @vitest-environment jsdom
 *
 * The Logs view's header reads left to right like the rest of the run views: "back to Run" on
 * the left, and, on the right, the way on to this run's Analysis, in the same bordered look.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getRun: vi.fn(() => new Promise(() => {})) } }));
vi.mock('../WorkflowLogsExplorer', () => ({ WorkflowLogsExplorer: () => null }));
vi.mock('@/components/workflow/run-panel/RunSummaryBar', () => ({
  RunSummaryBar: ({ leading, trailing }: { leading?: React.ReactNode; trailing?: React.ReactNode }) => (
    <div data-testid="summary">{leading}{trailing}</div>
  ),
}));

import { WorkflowLogsPanelContent } from '../WorkflowLogsPanelContent';

afterEach(cleanup);

const toAnalysis = () => document.querySelector('[data-logs-to-analysis]') as HTMLButtonElement | null;

describe('WorkflowLogsPanelContent - on to Analysis', () => {
  it('offers Analysis on the right of the header, named, with the arrow on its side', () => {
    const onOpenAnalysis = vi.fn();
    render(<WorkflowLogsPanelContent workflowId="wf" runId="run" onBack={vi.fn()} onOpenAnalysis={onOpenAnalysis} />);

    const button = toAnalysis()!;
    expect(button.textContent).toContain('sidePanel.analysisTab');
    expect(button.className).toContain('border');
    expect(button.lastElementChild?.getAttribute('class')).toContain('lucide-arrow-right');

    fireEvent.click(button);
    expect(onOpenAnalysis).toHaveBeenCalledTimes(1);
  });

  it('shows no such button where the run has no Analysis view', () => {
    render(<WorkflowLogsPanelContent workflowId="wf" runId="run" onBack={vi.fn()} />);

    expect(toAnalysis()).toBeNull();
  });
});
