// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi, beforeEach } from 'vitest';
import React from 'react';
import { cleanup, render, screen, fireEvent, within, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';

// Toggle edition per test - IS_CE is read at render time via the getter.
let isCe = false;
vi.mock('@/lib/edition', () => ({ get IS_CE() { return isCe; } }));
// The modal only needs orchestratorApi from the api barrel; stub it so nothing hits the network.
vi.mock('@/lib/api', () => ({
  orchestratorApi: { createDataSource: vi.fn(), createColumn: vi.fn() },
}));

import { CreateDataSourceModal } from '../CreateDataSourceModal';
import { orchestratorApi } from '@/lib/api';

const createDataSourceMock = orchestratorApi.createDataSource as ReturnType<typeof vi.fn>;
const createColumnMock = orchestratorApi.createColumn as ReturnType<typeof vi.fn>;

afterEach(() => cleanup());

function renderModal() {
  render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <CreateDataSourceModal onClose={() => {}} onDataSourceCreated={() => {}} />
    </NextIntlClientProvider>,
  );
}

/** Step 1 (name → Next) then open the inline column-type picker on step 2. */
function openColumnPicker() {
  fireEvent.change(screen.getByPlaceholderText('Enter table name'), {
    target: { value: 'My Table' },
  });
  fireEvent.click(screen.getByRole('button', { name: /next/i }));
  fireEvent.click(screen.getByRole('button', { name: /add column/i }));
}

function vectorTile(): HTMLButtonElement {
  return screen.getByText('Embedding vector').closest('button') as HTMLButtonElement;
}

describe('CreateDataSourceModal - vector column is self-hosted only', () => {
  it('cloud: the Embedding vector tile is disabled and shows the "CE only" badge', () => {
    isCe = false;
    renderModal();
    openColumnPicker();

    const tile = vectorTile();
    expect(tile).toBeDisabled();
    expect(within(tile).getByText('CE only')).toBeInTheDocument();
    expect(tile).toHaveAttribute(
      'title',
      'Available on the self-hosted Community Edition only',
    );
  });

  it('self-hosted (CE): the Embedding vector tile is selectable with no badge', () => {
    isCe = true;
    renderModal();
    openColumnPicker();

    const tile = vectorTile();
    expect(tile).not.toBeDisabled();
    expect(within(tile).queryByText('CE only')).not.toBeInTheDocument();
  });

  it('a non-gated tile (Rich text) stays selectable even in cloud', () => {
    isCe = false;
    renderModal();
    openColumnPicker();

    const textTile = screen.getByText('Rich text').closest('button') as HTMLButtonElement;
    expect(textTile).not.toBeDisabled();
    expect(within(textTile).queryByText('CE only')).not.toBeInTheDocument();
  });
});

describe('CreateDataSourceModal - a vector column added at table-creation time carries its display contract', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    createDataSourceMock.mockResolvedValue({ id: 123 });
    createColumnMock.mockResolvedValue({});
  });

  /**
   * Step1 name → Next → open picker → name the column, pick the type tile by its
   * visible label, then confirm it into the staged list.
   */
  function stageColumn(tileLabel: string, colName: string) {
    fireEvent.change(screen.getByPlaceholderText('Enter table name'), {
      target: { value: 'Docs' },
    });
    fireEvent.click(screen.getByRole('button', { name: /next/i }));
    // Open the inline column adder (toggle button).
    fireEvent.click(screen.getByRole('button', { name: /add column/i }));
    // Name the column, then choose the type tile.
    fireEvent.change(screen.getByPlaceholderText('Enter column name'), {
      target: { value: colName },
    });
    fireEvent.click(screen.getByText(tileLabel).closest('button') as HTMLButtonElement);
    // Confirm the column into the staged list (the inline adder's "Add Column").
    fireEvent.click(screen.getByRole('button', { name: /add column/i }));
  }

  it('CE: a vector column sends display={dimension,metric,label} under "display" (NOT displayConfig) so the backend accepts it', async () => {
    isCe = true;
    renderModal();
    stageColumn('Embedding vector', 'embedding');

    // Submit the table.
    fireEvent.click(screen.getByRole('button', { name: /create table/i }));

    await waitFor(() => expect(createColumnMock).toHaveBeenCalledTimes(1));

    const [dsId, payload] = createColumnMock.mock.calls[0];
    expect(dsId).toBe(123);
    expect(payload).toMatchObject({
      name: 'embedding',
      type: 'vector',
      // The vector preset's defaults must reach the backend or validateVectorDimension rejects it.
      display: { dimension: 1536, metric: 'cosine', label: 'embedding' },
    });
    // Regression guard: the old payload used the wrong key, which the backend ignores.
    expect(payload).not.toHaveProperty('displayConfig');
    expect(payload.display).not.toEqual({});
  });

  it('a select column (no inline edit) still ships the preset default display.options - backend rejects an empty options list', async () => {
    // Same bug class: select/multi_select require display.options; the old wrong
    // key dropped them, so a select column created at table-creation time was
    // silently discarded too. The preset defaults must reach the backend.
    isCe = false;
    renderModal();
    stageColumn('Select', 'status');

    fireEvent.click(screen.getByRole('button', { name: /create table/i }));

    await waitFor(() => expect(createColumnMock).toHaveBeenCalledTimes(1));

    const [, payload] = createColumnMock.mock.calls[0];
    expect(payload.type).toBe('select');
    expect(payload).not.toHaveProperty('displayConfig');
    expect(Array.isArray(payload.display?.options)).toBe(true);
    expect(payload.display.options.length).toBeGreaterThan(0);
  });
});
