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
  // The panel reads the execution links once so each row can show its routing
  // badge; unrouted catalogs answer with an empty list.
  listExecutionLinks: vi.fn().mockResolvedValue([]),
  saveExecutionLink: vi.fn(),
  deleteExecutionLink: vi.fn(),
}));

// The per-model execution-link badge translates its own labels.
vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (k: string) => (ns ? `${ns}.${k}` : k),
}));

vi.mock('@/lib/api/model-config.service', () => ({
  modelConfigService: {
    getEffectiveModels: mocks.getEffectiveModels,
    saveOverride: mocks.saveOverride,
    setCategoryEnabled: mocks.setCategoryEnabled,
    bulkUpdateRankings: mocks.bulkUpdateRankings,
    deleteOverride: mocks.deleteOverride,
    resetAll: mocks.resetAll,
    listExecutionLinks: mocks.listExecutionLinks,
    saveExecutionLink: mocks.saveExecutionLink,
    deleteExecutionLink: mocks.deleteExecutionLink,
  },
}));
vi.mock('@/hooks/useModels', () => ({ clearModelsCache: mocks.clearModelsCache }));
// Pinned, not ambient: the execution-link cell renders on cloud only, and this suite
// exists to check the row shape WITH it rendered.
vi.mock('@/lib/edition/edition', () => ({
  EDITION: 'cloud', IS_CE: false, IS_CLOUD: true, IS_MANAGED_CLOUD: true,
}));
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
    // Renders the execution-link cell: it nests INSIDE the name cell rather than adding
    // a grid child, and this suite is the guard for that (a template/child-count
    // mismatch wraps the last cell onto a second line).
    cliBridgeProvider: 'codex',
    ...over,
  };
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

/** The header + row grids all start with the 40px drag/number column. */
/**
 * The header and the model rows, which share one template. Matched on the FIRST two track
 * widths rather than the whole string so the helper survives a column being added, which is
 * what it did not do when the selection column arrived in front of the row number.
 */
function tableGrids(container: HTMLElement): Element[] {
  return Array.from(container.querySelectorAll('div[class*="grid-cols-[28px_40px"]'));
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
      buildModel({ id: 'claude-fable-5', name: 'Fable', provider: 'anthropic', displayOrder: 2, bundleEnabled: true, cliBridgeProvider: 'claude-code' }),
      // No CLI counterpart: the row renders WITHOUT the cell, which is the other half of
      // the invariant (the cell must not be what holds the columns together).
      buildModel({ id: 'deepseek-chat', name: 'DeepSeek', provider: 'deepseek', displayOrder: 3, bundleEnabled: false, cliBridgeProvider: undefined }),
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

  it('clips a long model name inside its own cell instead of running under the selects', async () => {
    // The 2026-09-21 regression: the name line was a bare <button>, which is
    // shrink-to-fit, so `truncate` had nothing to clip - the button was simply as wide
    // as its text. The name then ran out of the `1fr` column and under the tier and
    // effort selects, which paint their own background, so the selects appeared to sit
    // on top of the model name. jsdom computes no layout, so the fix is pinned where it
    // lives: the name line must be a block that fills its cell and clips, exactly like
    // the id line under it already did.
    mocks.getEffectiveModels.mockResolvedValue([
      buildModel({ id: '~anthropic/claude-sonnet-latest', name: '~anthropic/claude-sonnet-latest' }),
    ]);

    render(<ModelManagementPanel t={t} />);
    const name = await screen.findByTestId('model-name-openai-~anthropic/claude-sonnet-latest');

    expect(name.className).toContain('truncate');
    // `truncate` alone was the bug: without a width the overflow box is the text itself.
    expect(name.className).toContain('w-full');
    // Not toContain: 'inline-block' contains 'block' and is exactly the shrink-to-fit
    // display the fix replaces, so the loose check would pass on the pre-fix markup.
    expect(name.className.split(/\s+/)).toContain('block');
    // And the cell it sits in must be allowed to be narrower than the text, or the
    // grid column grows to fit and pushes every later column off the row instead.
    expect(name.parentElement?.className).toContain('min-w-0');
  });

  it('keeps the whole name reachable once it is clipped', async () => {
    // Clipping is only acceptable because the full value is still readable on hover,
    // and the action the button performs moves to the accessible name rather than
    // being dropped.
    mocks.getEffectiveModels.mockResolvedValue([buildModel({ name: 'A very long display name' })]);

    render(<ModelManagementPanel t={t} />);
    const name = await screen.findByTestId('model-name-openai-gpt-5');

    expect(name).toHaveAttribute('title', 'A very long display name');
    expect(name.getAttribute('aria-label')).toContain('A very long display name');
    expect(name.getAttribute('aria-label')).toContain('modelConfig.editName');
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

  it('cloud build renders the free-tier chip inside its own fixed column', async () => {
    mocks.getEffectiveModels.mockResolvedValue([buildModel({ freeTierEnabled: true })]);

    const { container } = render(<ModelManagementPanel t={t} />);
    const chip = await screen.findByTestId('model-free-tier-openai-gpt-5');

    // Same invariant as the CE-ship chip: a DIRECT grid child of the row, in a
    // fixed column, so toggling it can never reflow the columns after it.
    const row = tableGrids(container).find((g) => g.contains(chip));
    expect(row).toBeDefined();
    expect(chip.parentElement).toBe(row);
    expect(templateOf(row!)).toContain('60px');
  });
});
