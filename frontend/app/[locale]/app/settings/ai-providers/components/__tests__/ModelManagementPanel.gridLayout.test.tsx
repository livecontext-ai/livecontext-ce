// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

/**
 * Layout regression for the Models table (2026-07-03): the CE-ship chip was
 * added as an 11th row child while the header and rows kept a 10-column grid
 * template, so the last cell of every row wrapped onto an implicit second
 * grid line and the whole table misaligned. Each row is its OWN grid
 * container, so the only safe shape is: ONE shared template (ROW_GRID_COLS)
 * and a child count that matches it exactly, in the header AND every row.
 * These tests pin that invariant structurally - any future cell added
 * without extending the template (or vice versa) fails here.
 */

const mocks = vi.hoisted(() => ({
  getEffectiveModels: vi.fn(),
  saveOverride: vi.fn(),
  setCategoryEnabled: vi.fn(),
  bulkUpdateRankings: vi.fn(),
  deleteOverride: vi.fn(),
  resetAll: vi.fn(),
  clearModelsCache: vi.fn(),
}));

vi.mock('@/lib/api/model-config.service', () => ({
  modelConfigService: {
    getEffectiveModels: mocks.getEffectiveModels,
    saveOverride: mocks.saveOverride,
    setCategoryEnabled: mocks.setCategoryEnabled,
    bulkUpdateRankings: mocks.bulkUpdateRankings,
    deleteOverride: mocks.deleteOverride,
    resetAll: mocks.resetAll,
  },
}));
vi.mock('@/hooks/useModels', () => ({ clearModelsCache: mocks.clearModelsCache }));
vi.mock('../AddModelDialog', () => ({ default: () => null }));

import ModelManagementPanel from '../ModelManagementPanel';

const t = (k: string) => k;

function buildModel(over: Record<string, unknown> = {}) {
  return {
    id: 'gpt-5',
    name: 'GPT-5',
    provider: 'openai',
    displayOrder: 1,
    enabled: true,
    tier: 'top',
    providerKind: 'cloud' as const,
    ...over,
  };
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

/** The header + row grids all start with the 40px drag/number column. */
function tableGrids(container: HTMLElement): Element[] {
  return Array.from(container.querySelectorAll('div[class*="grid-cols-[40px"]'));
}

function templateOf(el: Element): string {
  const m = el.className.match(/grid-cols-\[[^\]]+\]/);
  return m ? m[0] : '';
}

describe('ModelManagementPanel - header/row grid parity', () => {
  it('header and every row share ONE template and fill exactly its column count', async () => {
    mocks.getEffectiveModels.mockResolvedValue([
      // Three rows covering the chip's 3 visual states (different label widths)
      buildModel({ id: 'gpt-5', provider: 'openai', bundleEnabled: null }),
      buildModel({ id: 'claude-fable-5', name: 'Fable', provider: 'anthropic', displayOrder: 2, bundleEnabled: true }),
      buildModel({ id: 'deepseek-chat', name: 'DeepSeek', provider: 'deepseek', displayOrder: 3, bundleEnabled: false }),
    ]);

    const { container } = render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-toggle-openai-gpt-5');

    const grids = tableGrids(container);
    // header + 3 rows
    expect(grids.length).toBe(4);

    const templates = new Set(grids.map(templateOf));
    expect(templates, 'header and rows must share ONE grid template').toEqual(
      new Set([templateOf(grids[0])]),
    );

    const columnCount = templateOf(grids[0])
      .replace(/^grid-cols-\[|\]$/g, '')
      .split('_').length;
    for (const grid of grids) {
      // A child count above the template wraps the surplus cells onto an
      // implicit second grid line - the exact 2026-07-03 misalignment bug.
      expect(grid.childElementCount, 'cells must fill the template exactly').toBe(columnCount);
    }
  });

  it('cloud build renders the CE-ship chip inside its own fixed column', async () => {
    mocks.getEffectiveModels.mockResolvedValue([buildModel({ bundleEnabled: true })]);

    const { container } = render(<ModelManagementPanel t={t} />);
    const chip = await screen.findByTestId('model-bundle-enabled-openai-gpt-5');

    // The chip is a DIRECT grid child (its own column), not nested inside the
    // name/provider cells where it would stretch them.
    const row = tableGrids(container).find((g) => g.contains(chip));
    expect(row).toBeDefined();
    expect(chip.parentElement).toBe(row);
    // Fixed-width column: the template pins 88px so the varying auto/on/off
    // labels cannot shift the following columns from row to row.
    expect(templateOf(row!)).toContain('88px');
  });
});
