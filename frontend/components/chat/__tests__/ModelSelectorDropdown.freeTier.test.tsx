// @vitest-environment jsdom
/**
 * V494 on the MAIN chat path.
 *
 * <p>This dropdown, not ModelPicker, is what the chat page and the two side
 * panels render. The Free plan's AI allowance pays for the models a cloud admin
 * opened to the free tier and for no others, so two things have to be true here:
 * the covered models lead, and only the uncovered ones carry an upgrade badge.
 *
 * <p>The per-model verdict arrives as a PROP rather than from a hook, because this
 * component is translation-free and data-hook-free by design (see its file header)
 * and the panels render it without a query client. That is exactly why it needs its
 * own test: the caller could stop passing it and nothing else would notice.
 */
import '@testing-library/jest-dom/vitest';
import * as React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/hooks/useModelCostBasis', () => ({
  useModelCostBasis: () => ({ basis: null, isLoading: false }),
}));
vi.mock('next/image', () => ({ default: () => null }));
vi.mock('@/components/ai/ModelInfo', () => ({
  ModelOptionDisplay: ({ model, upgradeRequired }: { model: { id: string }, upgradeRequired?: boolean }) => (
    <span data-testid={`row-${model.id}`} data-upgrade={String(!!upgradeRequired)}>{model.id}</span>
  ),
  ModelInfoPopover: () => null,
}));

import { ModelSelectorDropdown } from '../ModelSelectorDropdown';

const models = [
  { id: 'opus', name: 'Opus', provider: 'anthropic', iconSlug: 'anthropic' },
  { id: 'haiku', name: 'Haiku', provider: 'anthropic', iconSlug: 'anthropic', freeTierEnabled: true },
  { id: 'sonnet', name: 'Sonnet', provider: 'anthropic', iconSlug: 'anthropic' },
] as never[];

function openMenu(props: Record<string, unknown> = {}) {
  return render(
    <ModelSelectorDropdown
      showModelSelector
      setShowModelSelector={() => {}}
      selectedModel={{ provider: 'anthropic', id: 'haiku' } as never}
      selectedModelData={{ name: 'Haiku', id: 'haiku' }}
      availableModels={models}
      setSelectedModel={() => {}}
      changeModelTitle="change model"
      {...props}
    />,
  );
}

/** The ids as the menu listed them. */
function rowOrder(): string[] {
  return screen.getAllByTestId(/^row-/).map((el) => el.getAttribute('data-testid')!.replace('row-', ''));
}

function badged(id: string): boolean {
  return screen.getByTestId(`row-${id}`).getAttribute('data-upgrade') === 'true';
}

let rectSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  // The menu renders only once it has measured its trigger, and jsdom measures
  // everything as zero - same fixture as the sibling suites.
  Object.defineProperty(window, 'innerHeight', { value: 900, writable: true, configurable: true });
  Object.defineProperty(window, 'innerWidth', { value: 1400, writable: true, configurable: true });
  const rect = {
    top: 600, bottom: 632, left: 1000, right: 1120, width: 120, height: 32,
    x: 1000, y: 600, toJSON: () => ({}),
  } as DOMRect;
  rectSpy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(rect);
});

afterEach(() => {
  rectSpy.mockRestore();
  cleanup();
});

describe('ModelSelectorDropdown - the free tier on the chat path', () => {
  it('offers the covered model first when the account is on the free tier', () => {
    openMenu({ prefersFreeTierModels: true });

    expect(rowOrder()).toEqual(['haiku', 'opus', 'sonnet']);
  });

  it('leaves the catalogue order alone for an account that is not on the free tier', () => {
    openMenu({ prefersFreeTierModels: false });

    expect(rowOrder()).toEqual(['opus', 'haiku', 'sonnet']);
  });

  it('badges only the models the allowance cannot pay for', () => {
    // The whole point of the per-model verdict: an account holding an allowance
    // can already run `haiku`, so telling it to upgrade there is false.
    openMenu({
      upgradeRequired: true,
      prefersFreeTierModels: true,
      blockedForModel: (m: { freeTierEnabled?: boolean }) => m.freeTierEnabled !== true,
    });

    expect(badged('haiku')).toBe(false);
    expect(badged('opus')).toBe(true);
    expect(badged('sonnet')).toBe(true);
  });

  it('falls back to the account-level verdict when no per-model one is supplied', () => {
    // Back-compat: a caller that has not been updated keeps the pre-V494 behaviour
    // rather than silently dropping every badge.
    openMenu({ upgradeRequired: true });

    expect(badged('haiku')).toBe(true);
    expect(badged('opus')).toBe(true);
  });
  it('hides the upgrade notice while the SELECTED model is one the allowance covers', () => {
    // The notice sits under the list and used to be gated on the account-level
    // verdict, so a Free account read "upgrade to continue" directly beneath the
    // very model its allowance pays for. Restoring that gate is a one-word change
    // in the component and nothing else in this file would notice.
    openMenu({
      upgradeRequired: true,
      upgradeNotice: <span data-testid="upgrade-notice">upgrade</span>,
      blockedForModel: (m: { freeTierEnabled?: boolean }) => m.freeTierEnabled !== true,
      prefersFreeTierModels: true,
    });

    expect(screen.queryByTestId('upgrade-notice')).toBeNull();
  });

  it('shows it again when the selected model is one the allowance cannot pay for', () => {
    openMenu({
      selectedModel: { provider: 'anthropic', id: 'opus' } as never,
      selectedModelData: { name: 'Opus', id: 'opus' },
      upgradeRequired: true,
      upgradeNotice: <span data-testid="upgrade-notice">upgrade</span>,
      blockedForModel: (m: { freeTierEnabled?: boolean }) => m.freeTierEnabled !== true,
      prefersFreeTierModels: true,
    });

    expect(screen.getByTestId('upgrade-notice')).toBeInTheDocument();
  });

  it('keeps the account-level notice when no per-model verdict is supplied', () => {
    // The panels that do not pass the prop must behave exactly as before.
    openMenu({
      upgradeRequired: true,
      upgradeNotice: <span data-testid="upgrade-notice">upgrade</span>,
    });

    expect(screen.getByTestId('upgrade-notice')).toBeInTheDocument();
  });
});
