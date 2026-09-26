// @vitest-environment jsdom
/**
 * The usage history table: one fixed-height line per row, a pager that never moves, and a
 * full-screen mode.
 *
 * <p>Rows used to grow with their content (the cached-token count on a line of its own, a long
 * type or model label wrapping) and a short last page shrank the table, so the pager under it
 * jumped between clicks and a reader paging with the mouse kept missing the button.
 */
import React from 'react';
import '@testing-library/jest-dom/vitest';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, within, cleanup, waitFor } from '@testing-library/react';

const t = vi.hoisted(() => (key: string, values?: Record<string, unknown>) =>
  values ? `${key}:${JSON.stringify(values)}` : key);
vi.mock('next-intl', () => ({ useTranslations: () => t }));
vi.mock('../modelLabels', () => ({
  ProviderModelCell: ({ model }: { model: string | null }) => <span>{model ?? '-'}</span>,
}));
vi.mock('../../OwnKeyRowNote', () => ({ OwnKeyRowNote: () => null }));

import { UsageHistoryPanel } from '../UsageHistoryPanel';

const entry = (id: number, extra: Record<string, unknown> = {}) => ({
  id,
  createdAt: '2026-09-01T00:00:00Z',
  sourceType: 'AGENT_EXECUTION',
  provider: 'anthropic',
  model: 'claude-sonnet-5',
  promptTokens: 1200,
  completionTokens: 300,
  cachedTokens: null,
  amount: -4,
  description: `row-${id}`,
  ...extra,
});

const page = (rows: ReturnType<typeof entry>[], totalPages: number) => ({
  content: rows,
  number: totalPages - 1,
  totalPages,
});

function renderPanel(history: ReturnType<typeof page> | null, footer: React.ReactNode = null) {
  return render(
    <UsageHistoryPanel
      history={history as never}
      pageSize={15}
      amountHeader="history.credits"
      formatAmount={(n) => String(n)}
      modelNames={null}
      toolbar={<select aria-label="filter" />}
      footer={footer}
    />,
  );
}

afterEach(() => {
  cleanup();
});

describe('UsageHistoryPanel - rows keep one height', () => {
  it('regression - the cached-token count stays on the same line instead of a block below', () => {
    renderPanel(page([entry(1, { cachedTokens: 900 })], 1));
    const cached = screen.getByText(/history\.cachedTokens/);
    // It used to be `display: block`, which doubled the height of every cached row.
    expect(cached.className).not.toMatch(/\bblock\b/);
    const cell = cached.closest('td')!;
    expect(cell.className).toContain('whitespace-nowrap');
    // The full text survives in the tooltip when the cell truncates.
    expect(cell).toHaveAttribute('title', expect.stringContaining('history.cachedTokens'));
  });

  it('every cell is a single truncated line of the same fixed height', () => {
    renderPanel(page([entry(1, { sourceType: 'SOME_VERY_LONG_UNMAPPED_SOURCE_TYPE_NAME' })], 1));
    const row = screen.getByTestId('usage-history-row');
    const cells = within(row).getAllByRole('cell');
    expect(cells).toHaveLength(6);
    for (const cell of cells) {
      expect(cell.className).toContain('h-10');
      expect(cell.className).toContain('whitespace-nowrap');
      expect(cell.className).toContain('text-ellipsis');
    }
    // Fixed layout: a long label can no longer widen its column and reflow the others.
    expect(row.closest('table')!.className).toContain('table-fixed');
    expect(cells[1]).toHaveAttribute('title', 'SOME_VERY_LONG_UNMAPPED_SOURCE_TYPE_NAME');
    expect(cells[2]).toHaveAttribute('title', 'anthropic / claude-sonnet-5');
  });

  it('regression - a short last page is padded to the page size so the pager does not move up', () => {
    renderPanel(page([entry(1), entry(2), entry(3)], 4));
    expect(screen.getAllByTestId('usage-history-row')).toHaveLength(3);
    const fillers = screen.getAllByTestId('usage-history-filler');
    expect(fillers).toHaveLength(12);
    // Invisible to assistive tech, and the same height as a real row.
    expect(fillers[0]).toHaveAttribute('aria-hidden', 'true');
    expect(within(fillers[0]).getByRole('cell', { hidden: true }).className).toContain('h-10');
  });

  it('a full page of a paged history needs no padding', () => {
    renderPanel(page(Array.from({ length: 15 }, (_, i) => entry(i + 1)), 3));
    expect(screen.getAllByTestId('usage-history-row')).toHaveLength(15);
    expect(screen.queryAllByTestId('usage-history-filler')).toHaveLength(0);
  });

  it('a single page has no pager to hold in place, so it is not padded', () => {
    renderPanel(page([entry(1), entry(2)], 1));
    expect(screen.queryAllByTestId('usage-history-filler')).toHaveLength(0);
  });

  it('an empty history shows the empty state and no filler', () => {
    renderPanel(page([], 1));
    expect(screen.getByText('history.noHistory')).toBeInTheDocument();
    expect(screen.queryAllByTestId('usage-history-filler')).toHaveLength(0);
  });

  it('image generation rows show no token count', () => {
    renderPanel(page([entry(1, { sourceType: 'IMAGE_GENERATION', promptTokens: 1, completionTokens: null })], 1));
    const cells = within(screen.getByTestId('usage-history-row')).getAllByRole('cell');
    expect(cells[3]).toHaveTextContent(/^-$/);
  });
  it('regression - the date cell carries its full text as a tooltip, like every other truncating cell', () => {
    renderPanel(page([entry(1)], 1));
    const cells = within(screen.getByTestId('usage-history-row')).getAllByRole('cell');
    expect(cells[0].getAttribute('title')).toBe(cells[0].textContent);
    expect(cells[0].textContent).not.toBe('');
  });

  it('the amount cell keeps its sign, and its full text in the tooltip', () => {
    renderPanel(page([entry(1, { amount: -4 }), entry(2, { amount: 7 })], 1));
    const [spend, refund] = screen.getAllByTestId('usage-history-row')
      .map((r) => within(r).getAllByRole('cell')[4]);
    expect(spend).toHaveTextContent('-4');
    expect(spend).toHaveAttribute('title', '-4');
    expect(refund).toHaveTextContent('+7');
    expect(refund.className).toContain('text-emerald-500');
    expect(screen.getByRole('columnheader', { name: 'history.credits' })).toBeInTheDocument();
  });

  it('an empty page of a paged history keeps the height of a full page', () => {
    renderPanel(page([], 3));
    const empty = screen.getByText('history.noHistory');
    // 15 rows of h-10 (2.5rem) and the 14 borders between them.
    expect(empty.style.height).toMatch(/37.5rem/);
    expect(empty.style.height).toMatch(/14px/);
  });

  it('busy dims the table and says so to assistive tech', () => {
    render(
      <UsageHistoryPanel history={page([entry(1)], 1) as never} busy pageSize={15}
        amountHeader="h" formatAmount={String} modelNames={null} />,
    );
    const table = screen.getByTestId('usage-history-table');
    expect(table).toHaveAttribute('aria-busy', 'true');
    expect(table.className).toContain('opacity-60');
  });
});

describe('UsageHistoryPanel - full screen', () => {
  const panel = () => screen.getByTestId('usage-history-panel');
  const toggle = () => screen.getByTestId('usage-history-fullscreen-toggle');

  it('opens as a modal dialog over the viewport, carrying the toolbar, the table and the pager', () => {
    renderPanel(page([entry(1)], 2), <button type="button">history.nextPage</button>);
    expect(panel()).not.toHaveAttribute('data-fullscreen');
    expect(toggle()).toHaveAccessibleName('history.fullscreen');

    fireEvent.click(toggle());

    const dialog = screen.getByRole('dialog', { name: 'history.title' });
    expect(dialog).toBe(panel());
    expect(dialog).toHaveAttribute('data-fullscreen', 'true');
    expect(dialog.className).toContain('w-screen');
    expect(dialog.className).toContain('h-[100dvh]');
    // Same controls inside the overlay, not a copy that loses the filter or the pager.
    expect(within(dialog).getByLabelText('filter')).toBeInTheDocument();
    expect(within(dialog).getByText('history.nextPage')).toBeInTheDocument();
    expect(within(dialog).getByText('row-1')).toBeInTheDocument();
    // The table scrolls inside the overlay.
    expect(within(dialog).getByTestId('usage-history-table').className).toContain('overflow-auto');
    // The way out is the dialog's own close button, named for what it does.
    expect(within(dialog).getByRole('button', { name: 'history.exitFullscreen' })).toBeInTheDocument();
  });

  it('the close button leaves full screen and gives focus back to the toggle', async () => {
    renderPanel(page([entry(1)], 1));
    fireEvent.click(toggle());
    fireEvent.click(screen.getByRole('button', { name: 'history.exitFullscreen' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(panel()).not.toHaveAttribute('data-fullscreen');
    // The inline panel is remounted; focus must not fall back to <body>.
    await waitFor(() => expect(toggle()).toHaveFocus());
  });

  it('Escape leaves full screen', async () => {
    renderPanel(page([entry(1)], 1));
    fireEvent.click(toggle());
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(toggle()).toHaveFocus());
  });

  it('focus moves into the dialog when it opens', async () => {
    renderPanel(page([entry(1)], 1));
    fireEvent.click(toggle());
    await waitFor(() => expect(screen.getByRole('dialog').contains(document.activeElement)).toBe(true));
  });
});
