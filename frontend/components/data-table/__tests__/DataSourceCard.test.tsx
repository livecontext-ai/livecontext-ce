// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  // No-namespace useTranslations() → echo the key; values are irrelevant to these assertions.
  useTranslations: () => (key: string) => key,
}));

import { DataSourceCard } from '../DataSourceCard';

const tableWithColumns: any = {
  id: 10,
  name: 'Emails',
  mapping_spec: { subject: { type: 'text', display: { label: 'Subject' } } },
  column_order: [{ field: 'subject' }],
};

afterEach(() => cleanup());

describe('DataSourceCard', () => {
  it('renders the mini-table preview: column header + sample row cells (the /app/tables card)', () => {
    render(
      <DataSourceCard
        ds={tableWithColumns}
        rowCount={2}
        sampleRows={[{ subject: 'Hello' }, { subject: 'World' }]}
        onClick={() => {}}
      />,
    );

    // Header label resolved from display.label, and the sampled cell values show.
    expect(screen.getByText('Subject')).toBeInTheDocument();
    expect(screen.getByText('Hello')).toBeInTheDocument();
    expect(screen.getByText('World')).toBeInTheDocument();
  });

  it('shows the "no rows yet" placeholder when the table has columns but no sample rows', () => {
    render(<DataSourceCard ds={tableWithColumns} rowCount={0} sampleRows={[]} onClick={() => {}} />);

    expect(screen.getByText('Subject')).toBeInTheDocument(); // header still renders
    expect(screen.getByText('data.noRowsYet')).toBeInTheDocument();
  });

  it('falls back to the icon hero (no preview table) for a column-less, row-less table', () => {
    render(<DataSourceCard ds={{ id: 7, name: 'Empty' } as any} rowCount={0} sampleRows={[]} onClick={() => {}} />);

    // No column headers / no "no rows" placeholder - just the centered icon hero.
    expect(screen.queryByText('data.noRowsYet')).not.toBeInTheDocument();
    expect(screen.queryByRole('columnheader')).not.toBeInTheDocument();
  });

  it('renders NO selection checkbox when onToggleSelect is omitted (read-only project tab)', () => {
    render(<DataSourceCard ds={tableWithColumns} rowCount={1} sampleRows={[{ subject: 'x' }]} onClick={() => {}} />);
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
  });

  it('renders a selection checkbox that toggles without triggering the card onClick', () => {
    const onClick = vi.fn();
    const onToggleSelect = vi.fn();
    render(
      <DataSourceCard
        ds={tableWithColumns}
        rowCount={1}
        sampleRows={[{ subject: 'x' }]}
        onClick={onClick}
        selected={false}
        onToggleSelect={onToggleSelect}
      />,
    );

    const checkbox = screen.getByRole('checkbox');
    fireEvent.click(checkbox);
    expect(onToggleSelect).toHaveBeenCalledTimes(1);
    // The checkbox stops propagation, so the card-open onClick must NOT fire.
    expect(onClick).not.toHaveBeenCalled();
  });
});
