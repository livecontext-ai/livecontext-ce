/**
 * @vitest-environment jsdom
 *
 * ModelPicker empty-catalog CTA - a CE install with no cloud link and no BYOK
 * key resolves to ZERO providers, and the picker used to render two dead
 * Selects. It must now render the NoProviderCta instead - but ONLY once the
 * catalog has actually resolved empty (not while loading, not on a fetch
 * error) and only on CE.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import * as React from 'react';
import type { AIModel, AIProvider } from '@/hooks/useModels';

const h = vi.hoisted(() => ({
  isCe: true,
  providers: [] as unknown[],
  isLoading: false,
  error: null as string | null,
}));

vi.mock('next/image', () => ({ default: () => null }));
vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: { children: React.ReactNode }) => <div data-testid="select">{children}</div>,
  SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectItem: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectTrigger: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('@/components/ai/ModelInfo', () => ({
  ModelOptionDisplay: ({ model }: { model: { id: string } }) => <span>{model.id}</span>,
  ModelInfoPopover: () => null,
}));
vi.mock('@/components/ai/NoProviderCta', () => ({
  NoProviderCta: ({ variant }: { variant?: string }) => (
    <div data-testid="no-provider-cta" data-variant={variant} />
  ),
}));
vi.mock('@/lib/edition', () => ({
  get IS_CE() {
    return h.isCe;
  },
}));
vi.mock('@/hooks/useModels', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/hooks/useModels')>();
  return {
    ...actual,
    useVisibleModels: () => ({
      providers: h.providers,
      defaultProvider: null,
      defaultModel: null,
      isLoading: h.isLoading,
      error: h.error,
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

describe('ModelPicker - empty-catalog CTA', () => {
  beforeEach(() => {
    h.isCe = true;
    h.providers = [];
    h.isLoading = false;
    h.error = null;
  });
  afterEach(() => cleanup());

  it('replaces the dead Selects with the form-variant CTA when CE resolves zero providers', () => {
    render(<ModelPicker value={{ provider: '', id: '' }} onChange={noop} />);
    expect(screen.getByTestId('no-provider-cta')).toHaveAttribute('data-variant', 'form');
    expect(screen.queryAllByTestId('select')).toHaveLength(0);
  });

  it('does NOT flash the CTA while the catalog is still loading', () => {
    h.isLoading = true;
    render(<ModelPicker value={{ provider: '', id: '' }} onChange={noop} />);
    expect(screen.queryByTestId('no-provider-cta')).toBeNull();
    expect(screen.queryAllByTestId('select').length).toBeGreaterThan(0);
  });

  it('does NOT show the CTA on a fetch error (a transient failure is not an onboarding state)', () => {
    h.error = 'network down';
    render(<ModelPicker value={{ provider: '', id: '' }} onChange={noop} />);
    expect(screen.queryByTestId('no-provider-cta')).toBeNull();
  });

  it('does NOT show the CTA on a cloud build even with zero providers', () => {
    h.isCe = false;
    render(<ModelPicker value={{ provider: '', id: '' }} onChange={noop} />);
    expect(screen.queryByTestId('no-provider-cta')).toBeNull();
  });

  it('keeps the normal Selects when providers exist', () => {
    h.providers = [provider('openai', [model('openai', 'gpt-5-mini')])];
    render(<ModelPicker value={{ provider: '', id: '' }} onChange={noop} />);
    expect(screen.queryByTestId('no-provider-cta')).toBeNull();
    expect(screen.queryAllByTestId('select').length).toBeGreaterThan(0);
  });

  it("shows the CTA when the capability filter empties the catalog (chat-only install, filterCapability='image')", () => {
    h.providers = [provider('openai', [model('openai', 'gpt-5-mini')])];
    render(
      <ModelPicker value={{ provider: '', id: '' }} onChange={noop} filterCapability="image" />,
    );
    expect(screen.getByTestId('no-provider-cta')).toBeInTheDocument();
  });
});
