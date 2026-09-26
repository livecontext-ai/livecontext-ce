/**
 * @vitest-environment jsdom
 *
 * The Free plan's opening model against the REAL app context and its localStorage
 * restore, which is where the reported bug lived.
 *
 * <p>The stored selection (`unifiedAppState`) is browser-wide, so a Free account opened
 * on the model the previous account left there (DeepSeek, the lowest-ranked free model)
 * instead of the free tier's #1. The restore runs in the provider's own mount effect,
 * which React runs AFTER the effects of the surfaces below it: with the catalogue and
 * the plan verdict already known on mount (a warm cache, a client-side move into the
 * app), a steer written before the restore is overwritten by it. These specs mount the
 * provider with that warm state on purpose.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';

const h = vi.hoisted(() => ({ prefers: true }));

vi.mock('next/navigation', () => ({ usePathname: () => '/en/app' }));
vi.mock('@/lib/hooks/useMonthlyCreditsCannotPay', () => ({
  useMonthlyCreditsCannotPay: () => ({ prefersFreeTierModels: h.prefers }),
}));

import { UnifiedAppProvider, useUnifiedApp } from '@/contexts/UnifiedAppContext';
import { resetFreeTierOpeningForTests, usePreferFreeTierModel } from '@/lib/hooks/usePreferFreeTierModel';
import type { AIModel } from '@/hooks/useModels';

const RANKED = [
  { id: 'opus', name: 'Opus', provider: 'anthropic', freeTierEnabled: false },
  { id: 'sonnet', name: 'Sonnet', provider: 'anthropic', freeTierEnabled: true },
  { id: 'deepseek-flash', name: 'DeepSeek Flash', provider: 'deepseek', freeTierEnabled: true },
] as AIModel[];

/** A composer surface: steers the opening, and shows the selection it ends up on. */
function Composer() {
  usePreferFreeTierModel(RANKED);
  const { state } = useUnifiedApp();
  return <div data-testid="selected">{`${state.selectedModel.provider}:${state.selectedModel.id}`}</div>;
}

function mountApp() {
  render(
    <UnifiedAppProvider>
      <Composer />
    </UnifiedAppProvider>,
  );
  return screen.getByTestId('selected').textContent;
}

beforeEach(() => {
  resetFreeTierOpeningForTests();
  h.prefers = true;
  localStorage.clear();
});

afterEach(cleanup);

describe('UnifiedAppProvider + usePreferFreeTierModel', () => {
  it('a Free account opens on the free #1 even though the browser restored DeepSeek', () => {
    localStorage.setItem('unifiedAppState', JSON.stringify({ selectedModel: 'deepseek:deepseek-flash' }));

    expect(mountApp()).toBe('anthropic:sonnet');
  });

  it('a Free account with nothing stored opens on the free #1', () => {
    expect(mountApp()).toBe('anthropic:sonnet');
  });

  it('a paid account keeps the model the browser restored', () => {
    h.prefers = false;
    localStorage.setItem('unifiedAppState', JSON.stringify({ selectedModel: 'deepseek:deepseek-flash' }));

    expect(mountApp()).toBe('deepseek:deepseek-flash');
  });
});
