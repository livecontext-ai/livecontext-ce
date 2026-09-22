// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import React from 'react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${Object.values(vars).join(',')}` : key,
}));

import { RunDataViewTabs, RawJsonView, JsonTableView } from '../RunDataViews';
import { MAX_TABLE_COLUMNS } from '../runValueUtils';
import { NodeRunStateNotice } from '../NodeRunStateNotice';
import type { PendingSignal } from '@/lib/websocket/ws-types';

describe('RunDataViewTabs', () => {
  beforeEach(() => {
    Object.assign(navigator, { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } });
  });

  it('hides the Table segment when the payload is not row-shaped', () => {
    render(<RunDataViewTabs mode="tree" onModeChange={vi.fn()} tableAvailable={false} />);
    expect(screen.queryByRole('tab', { name: 'viewTable' })).toBeNull();
    expect(screen.getByRole('tab', { name: 'viewTree' })).toBeTruthy();
    expect(screen.getByRole('tab', { name: 'viewJson' })).toBeTruthy();
  });

  it('offers the Table segment when the payload is row-shaped', () => {
    render(<RunDataViewTabs mode="tree" onModeChange={vi.fn()} tableAvailable />);
    expect(screen.getByRole('tab', { name: 'viewTable' })).toBeTruthy();
  });

  it('marks the active view for assistive tech, not only visually', () => {
    render(<RunDataViewTabs mode="json" onModeChange={vi.fn()} tableAvailable={false} />);
    expect(screen.getByRole('tab', { name: 'viewJson' }).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('tab', { name: 'viewTree' }).getAttribute('aria-selected')).toBe('false');
  });

  it('reports the chosen view', () => {
    const onModeChange = vi.fn();
    render(<RunDataViewTabs mode="tree" onModeChange={onModeChange} tableAvailable />);
    fireEvent.click(screen.getByRole('tab', { name: 'viewTable' }));
    expect(onModeChange).toHaveBeenCalledWith('table');
  });

  it('copies the whole payload when a copy value is given, and hides the button otherwise', () => {
    const { rerender } = render(
      <RunDataViewTabs mode="tree" onModeChange={vi.fn()} tableAvailable={false} copyValue={{ a: 1 }} />,
    );
    fireEvent.click(screen.getByTestId('run-data-copy-all'));
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('{\n  "a": 1\n}');

    rerender(<RunDataViewTabs mode="tree" onModeChange={vi.fn()} tableAvailable={false} />);
    expect(screen.queryByTestId('run-data-copy-all')).toBeNull();
  });
});

describe('RawJsonView', () => {
  it('pretty-prints the payload', () => {
    render(<RawJsonView data={{ a: [1] }} />);
    expect(screen.getByTestId('run-data-json-view').textContent).toBe('{\n  "a": [\n    1\n  ]\n}');
  });
});

describe('JsonTableView', () => {
  it('lays out an array of objects as rows, unioning the keys', () => {
    render(<JsonTableView data={[{ id: 1, name: 'Ada' }, { id: 2, role: 'eng' }]} />);
    const table = screen.getByTestId('run-data-table-view');
    expect(table.textContent).toContain('name');
    expect(table.textContent).toContain('role');
    expect(table.textContent).toContain('Ada');
  });

  it('summarises a nested cell rather than expanding it', () => {
    render(<JsonTableView data={[{ meta: { a: 1, b: 2 }, tags: ['x'] }]} />);
    const table = screen.getByTestId('run-data-table-view');
    expect(table.textContent).toContain('{2}');
    expect(table.textContent).toContain('[1]');
  });

  it('says so instead of rendering an empty grid when the payload is not row-shaped', () => {
    render(<JsonTableView data={{ a: 1 }} />);
    expect(screen.queryByTestId('run-data-table-view')).toBeNull();
    expect(screen.getByText('tableUnavailable')).toBeTruthy();
  });
});

describe('NodeRunStateNotice', () => {
  it('says the node is executing, and what THIS column will show', () => {
    render(<NodeRunStateNotice state="running" column="output" />);
    expect(screen.getByTestId('node-run-state-running')).toBeTruthy();
    expect(screen.getByText('stateRunningOutput')).toBeTruthy();
  });

  it('explains the params column differently from the output column', () => {
    render(<NodeRunStateNotice state="running" column="params" />);
    expect(screen.getByText('stateRunningParams')).toBeTruthy();
  });

  it('names the signal a parked node is waiting on, and when it expires', () => {
    const signals: PendingSignal[] = [
      {
        id: 1,
        nodeId: 'core:wait',
        signalType: 'WAIT_TIMER',
        status: 'PENDING',
        expiresAt: '2026-05-16T09:15:00Z',
      },
    ];
    render(<NodeRunStateNotice state="awaiting" column="output" pendingSignals={signals} />);
    expect(screen.getByTestId('node-run-state-awaiting')).toBeTruthy();
    expect(screen.getByText('signalWaitTimer')).toBeTruthy();
    expect(document.body.textContent).toContain('signalExpiresAt');
  });

  it('falls back to the raw signal name rather than a missing translation key', () => {
    const signals: PendingSignal[] = [
      { id: 2, nodeId: 'core:x', signalType: 'SOMETHING_NEW', status: 'PENDING' },
    ];
    render(<NodeRunStateNotice state="awaiting" column="output" pendingSignals={signals} />);
    expect(screen.getByText('SOMETHING_NEW')).toBeTruthy();
  });
});

describe('JsonTableView column cap', () => {
  it('says how many columns it had to hide, rather than silently dropping them', () => {
    const wide = Object.fromEntries(
      Array.from({ length: MAX_TABLE_COLUMNS + 7 }, (_, i) => [`col${i}`, i]),
    );
    render(<JsonTableView data={[wide]} />);

    const headers = screen.getByTestId('run-data-table-view').querySelectorAll('thead th');
    // The index column plus the capped data columns.
    expect(headers).toHaveLength(MAX_TABLE_COLUMNS + 1);
    expect(screen.getByText(`tableColumnsTruncated:${MAX_TABLE_COLUMNS},7`)).toBeTruthy();
  });

  it('says nothing when every column fits', () => {
    render(<JsonTableView data={[{ a: 1, b: 2 }]} />);
    expect(screen.queryByText(/tableColumnsTruncated/)).toBeNull();
  });

  it('counts hidden columns across ALL rows, not only the first', () => {
    const rows = Array.from({ length: MAX_TABLE_COLUMNS + 3 }, (_, i) => ({ [`col${i}`]: i }));
    render(<JsonTableView data={rows} />);
    expect(screen.getByText(`tableColumnsTruncated:${MAX_TABLE_COLUMNS},3`)).toBeTruthy();
  });

describe('JsonTableView field selection', () => {
  // These are the tests whose absence let the field selector ship as dead code:
  // both callers were pre-unwrapping the payload, so the label and the selector
  // could never render, and every existing case passed an already-picked array.
  it('names the field it is laying out, so the table never silently disagrees with the tree', () => {
    render(<JsonTableView data={{ items: [{ a: 1 }], count: 1 }} />);

    expect(screen.getByTestId('run-data-table-field').textContent).toBe('items');
    expect(screen.getByTestId('run-data-table-view')).toBeTruthy();
  });

  it('offers a selector when several fields qualify, and lays out the chosen one', () => {
    render(<JsonTableView data={{ items: [{ a: 1 }], errors: [{ code: 'X' }] }} />);

    const select = screen.getByTestId('run-data-table-field') as HTMLSelectElement;
    expect(select.tagName).toBe('SELECT');
    expect([...select.options].map((o) => o.value)).toEqual(['items', 'errors']);
    // Default is the first field, and the table shows ITS columns.
    expect(screen.getByText('a')).toBeTruthy();

    fireEvent.change(select, { target: { value: 'errors' } });
    expect(screen.getByText('code')).toBeTruthy();
  });

  it('names no field when the payload IS the rows, because nothing was picked', () => {
    render(<JsonTableView data={[{ a: 1 }]} />);
    expect(screen.queryByTestId('run-data-table-field')).toBeNull();
    expect(screen.getByTestId('run-data-table-view')).toBeTruthy();
  });
});
});
