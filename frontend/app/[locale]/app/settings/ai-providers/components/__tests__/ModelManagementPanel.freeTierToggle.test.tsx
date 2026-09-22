// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

/**
 * The admin switch that decides which models a Free account may spend its AI
 * allowance on (V493).
 *
 * <p>The chip writes optimistically: it flips instantly and persists in the
 * background. That is the right feel and it has a failure mode worth pinning, which
 * the backend change made reachable rather than theoretical. Opening a model now
 * REFUSES when the billing mirror cannot be written (the gate reads that mirror, so a
 * catalog row saved without it would light the chip while every free account is still
 * refused) and when the model carries no price. On either refusal the chip must go
 * back to where it was: a switch that stays lit after a failed save is a lie about
 * what the platform will do, and it is the one thing no server-side test can catch.
 */

const mocks = vi.hoisted(() => ({
  getEffectiveModels: vi.fn(),
  saveOverride: vi.fn(),
  setCategoryEnabled: vi.fn(),
  bulkUpdateRankings: vi.fn(),
  deleteOverride: vi.fn(),
  resetAll: vi.fn(),
  clearModelsCache: vi.fn(),
  listExecutionLinks: vi.fn().mockResolvedValue([]),
  saveExecutionLink: vi.fn(),
  deleteExecutionLink: vi.fn(),
}));

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
// The chip is cloud-only: a CE install meters nothing, so there is no allowance to open.
vi.mock('@/lib/edition/edition', () => ({
  EDITION: 'cloud', IS_CE: false, IS_CLOUD: true, IS_MANAGED_CLOUD: true,
}));
vi.mock('../AddModelDialog', () => ({ default: () => null }));

import ModelManagementPanel from '../ModelManagementPanel';

const t = (k: string) => k;
const CHIP = 'model-free-tier-anthropic-claude-haiku-4-5';

function haiku(over: Record<string, unknown> = {}) {
  return {
    id: 'claude-haiku-4-5',
    name: 'Haiku',
    provider: 'anthropic',
    displayOrder: 1,
    enabled: true,
    tier: 'fast',
    providerKind: 'cloud' as const,
    freeTierEnabled: false,
    ...over,
  };
}

/** The chip carries its state in the colour it is drawn with, so that is what we read. */
function isLit(el: HTMLElement): boolean {
  return el.className.includes('sky');
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('ModelManagementPanel - the free-tier switch', () => {
  it('opens a closed model and sends exactly that one field', async () => {
    mocks.getEffectiveModels.mockResolvedValue([haiku()]);
    mocks.saveOverride.mockResolvedValue({});

    render(<ModelManagementPanel t={t} />);
    const chip = await screen.findByTestId(CHIP);
    expect(isLit(chip)).toBe(false);

    fireEvent.click(chip);

    await waitFor(() => expect(mocks.saveOverride).toHaveBeenCalledWith({
      provider: 'anthropic',
      modelId: 'claude-haiku-4-5',
      freeTierEnabled: true,
    }));
    // Only this key travels: a payload carrying the row's other fields would let
    // an unrelated stale value overwrite what another admin changed meanwhile.
    expect(Object.keys(mocks.saveOverride.mock.calls[0][0]).sort())
      .toEqual(['freeTierEnabled', 'modelId', 'provider']);
    expect(isLit(await screen.findByTestId(CHIP))).toBe(true);
  });

  it('closes an open model', async () => {
    mocks.getEffectiveModels.mockResolvedValue([haiku({ freeTierEnabled: true })]);
    mocks.saveOverride.mockResolvedValue({});

    render(<ModelManagementPanel t={t} />);
    fireEvent.click(await screen.findByTestId(CHIP));

    await waitFor(() => expect(mocks.saveOverride).toHaveBeenCalledWith({
      provider: 'anthropic',
      modelId: 'claude-haiku-4-5',
      freeTierEnabled: false,
    }));
    await waitFor(() => expect(isLit(screen.getByTestId(CHIP))).toBe(false));
  });

  it('rolls the chip back when the save is refused, and says why', async () => {
    // Scoped to this component: what it pins is that a REJECTED save rolls the chip
    // back and surfaces the message, not that the backend's 409 body survives the
    // transport (that is the API client's contract, tested there). The refusal is a
    // live one though - the catalog row is only saved if the billing mirror took the
    // flag - and leaving the chip lit would tell the admin the model is open while
    // the gate still refuses every free turn on it.
    mocks.getEffectiveModels.mockResolvedValue([haiku()]);
    mocks.saveOverride.mockRejectedValue(new Error('Could not mirror the free-tier setting'));

    render(<ModelManagementPanel t={t} />);
    fireEvent.click(await screen.findByTestId(CHIP));

    await waitFor(() => expect(isLit(screen.getByTestId(CHIP))).toBe(false));
    expect(await screen.findByText(/Could not mirror the free-tier setting/)).toBeInTheDocument();
  });

  it('rolls an OPEN model back to open when closing it fails', async () => {
    // The other direction, which a single-direction rollback would get wrong:
    // the chip must return to lit, not to the default off.
    mocks.getEffectiveModels.mockResolvedValue([haiku({ freeTierEnabled: true })]);
    mocks.saveOverride.mockRejectedValue(new Error('auth-service unreachable'));

    render(<ModelManagementPanel t={t} />);
    fireEvent.click(await screen.findByTestId(CHIP));

    await waitFor(() => expect(isLit(screen.getByTestId(CHIP))).toBe(true));
  });
});

describe('ModelManagementPanel - the free tier is announced but nothing is open', () => {
  it('warns when no model is open, because the pricing page promises the allowance anyway', async () => {
    // The plan cards, the landing and the comparison table all state "100 AI credits
    // per month" unconditionally. With no model open, that allowance can buy nothing
    // and a Free account is refused on its first turn. Nothing errors, nothing logs,
    // and this panel is the only place the person who can fix it will ever see it.
    mocks.getEffectiveModels.mockResolvedValue([haiku(), haiku({ id: 'opus', name: 'Opus' })]);

    render(<ModelManagementPanel t={t} />);

    expect(await screen.findByTestId('free-tier-none-open')).toBeInTheDocument();
  });

  it('says nothing once at least one model is open', async () => {
    mocks.getEffectiveModels.mockResolvedValue([
      haiku({ freeTierEnabled: true }),
      haiku({ id: 'opus', name: 'Opus' }),
    ]);

    render(<ModelManagementPanel t={t} />);
    await screen.findByTestId(CHIP);

    expect(screen.queryByTestId('free-tier-none-open')).toBeNull();
  });
});
