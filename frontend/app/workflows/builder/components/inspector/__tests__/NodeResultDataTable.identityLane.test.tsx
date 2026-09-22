/**
 * @vitest-environment jsdom
 *
 * Regression: the inspector panel's per-node Logs must keep an identity lane at
 * EVERY level of the JSON, exactly like the run-result Logs modal.
 *
 * Both surfaces render the same {@link WorkflowStepTable} on the same step data,
 * but this one overrode `showIdColumn` to false. That override built no fixed ID
 * lane, so an `id` column existed at the step root only because the backend sends
 * one there; drilling into `input` or `output` derives the columns from the DATA,
 * and the rows then had no identity at all whenever the nested items carried no
 * `id` of their own - a step's input parameters, an agent's output.
 *
 * The assertion is on the value that reaches DataTable, not on a prop of the
 * wrapper: what matters is the lane the grid ends up building, wherever the
 * default lives.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';

const dataTableProps = vi.hoisted(() => ({ current: null as Record<string, unknown> | null }));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));
vi.mock('@/app/workflows/builder/hooks/useStepData', () => ({
  useStepData: () => ({ stepData: [], isLoading: false }),
}));
// The grid itself is not under test: what it was HANDED is.
vi.mock('@/components/DataTable', () => ({
  default: (props: Record<string, unknown>) => {
    dataTableProps.current = props;
    return <div data-testid="data-table" />;
  },
}));

import { NodeResultDataTable } from '../NodeResultDataTable';

const node = {
  id: 'node-1',
  type: 'tableNode',
  position: { x: 0, y: 0 },
  data: { label: 'Read Seeded Rows', stepAlias: 'table:read_seeded_rows' },
} as never;

afterEach(() => {
  dataTableProps.current = null;
  cleanup();
});

describe('NodeResultDataTable - identity lane', () => {
  it('hands the grid an enabled id column, like the run-result Logs modal', () => {
    // The modal renders the same wrapper with no showIdColumn, so the effective
    // value here must be the one the wrapper defaults to - true. Reading it off
    // the rendered tree covers the inspector's call site AND that default in one
    // assertion; `false` is the bug, and it is what suppressed the fixed lane.
    render(<NodeResultDataTable node={node} runId="run-1" workflowId="wf-1" />);

    expect(dataTableProps.current?.showIdColumn).toBe(true);
  });
});
