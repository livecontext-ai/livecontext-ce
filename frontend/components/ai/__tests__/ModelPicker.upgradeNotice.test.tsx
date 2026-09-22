/**
 * @vitest-environment jsdom
 *
 * The picker is where the upgrade warning reaches six surfaces at once: the
 * agent dialog, the chat config panel, and the agent / classify / guardrail /
 * browser-agent node inspectors all render this component. So the question is
 * asked HERE, once, and handed to the rows as a value.
 *
 * <p>Two things are pinned. The rows get marked, which is what tells the reader
 * which model they are choosing. And the way to act sits UNDER the Select, not
 * in an option of it: an option cannot host a control that ARIA will announce
 * or a key will reach, and a dialog opened from inside the menu would paint
 * beneath the menu that is still open.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import * as React from 'react';
import type { AIModel, AIProvider } from '@/hooks/useModels';

const h = vi.hoisted(() => ({
  blocked: false,
  providers: [] as unknown[],
}));

vi.mock('@/lib/hooks/useModelCostBasis', () => ({
  // The picker asks once for the credit-estimate basis; these tests are not
  // about the estimate, and an unanswered basis renders no estimate at all.
  useModelCostBasis: () => ({ basis: null, isLoading: false }),
}));
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k, useLocale: () => 'en' }));
vi.mock('next/link', () => ({
  default: ({ href, children }: { href: string, children: React.ReactNode }) => <a href={href}>{children}</a>,
}));
vi.mock('next/image', () => ({ default: () => null }));
vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: { children: React.ReactNode }) => <div data-testid="select">{children}</div>,
  SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectItem: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectTrigger: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
// The row is stubbed to report only what this test is about: whether the
// verdict reached it. What it DRAWS with that is ModelInfo's own suite.
vi.mock('@/components/ai/ModelInfo', () => ({
  ModelOptionDisplay: ({ model, upgradeRequired }: { model: { id: string }, upgradeRequired?: boolean }) => (
    <span data-testid={`row-${model.id}`} data-upgrade={String(!!upgradeRequired)}>{model.id}</span>
  ),
  ModelInfoPopover: () => null,
}));
vi.mock('@/lib/hooks/useMonthlyCreditsCannotPay', () => ({
  useMonthlyCreditsCannotPay: () => ({ blocked: h.blocked, blockedForModel: () => h.blocked, freeTierForModel: () => false, prefersFreeTierModels: false, isLoading: false }),
}));
vi.mock('@/lib/edition', () => ({ IS_CE: false }));
vi.mock('@/hooks/useModels', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/hooks/useModels')>();
  return {
    ...actual,
    useVisibleModels: () => ({
      providers: h.providers,
      defaultProvider: null,
      defaultModel: null,
      isLoading: false,
      error: null,
      models: [],
      refresh: async () => {},
    }),
  };
});

import { ModelPicker } from '../ModelPicker';

const model = (provider: string, id: string): AIModel => ({ id, name: id, provider });
const provider = (name: string, models: AIModel[]): AIProvider => ({
  name,
  defaultModel: models[0]?.id ?? '',
  supportsStreaming: true,
  supportsToolCalling: true,
  displayOrder: 1,
  models,
});

const noop = () => {};

function renderPicker() {
  return render(
    <ModelPicker value={{ provider: 'openai', id: 'gpt-5-mini' }} onChange={noop} />,
  );
}

describe('ModelPicker - the upgrade warning', () => {
  beforeEach(() => {
    h.blocked = false;
    h.providers = [provider('openai', [model('openai', 'gpt-5-mini'), model('openai', 'gpt-5')])];
  });
  afterEach(() => cleanup());

  it('marks every model row when this account cannot pay for what a model does', () => {
    h.blocked = true;
    renderPicker();

    // Every row, not just the selected one: the reader is comparing.
    for (const row of screen.getAllByTestId(/^row-/)) {
      expect(row).toHaveAttribute('data-upgrade', 'true');
    }
  });

  it('leaves the rows alone for an account that can pay', () => {
    renderPicker();

    for (const row of screen.getAllByTestId(/^row-/)) {
      expect(row).toHaveAttribute('data-upgrade', 'false');
    }
  });

  it('regression: puts the way out UNDER the Select, not inside an option', () => {
    // Inside a listbox option the control is announced to nobody, is reached by
    // no key, and opens a dialog that paints beneath the open menu.
    h.blocked = true;
    renderPicker();

    const cta = screen.getByRole('link', { name: 'cta' });
    expect(cta).toBeInTheDocument();
    expect(cta.closest('[data-testid="select"]')).toBeNull();
  });

  it('says nothing at all when the account can pay', () => {
    renderPicker();

    expect(screen.queryByRole('link', { name: 'cta' })).toBeNull();
  });
});
