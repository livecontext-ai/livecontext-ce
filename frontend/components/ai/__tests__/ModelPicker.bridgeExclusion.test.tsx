/**
 * @vitest-environment jsdom
 *
 * ModelPicker `excludeBridgeProviders` - the compaction-summariser surfaces
 * dispatch a bare single completion which a CLI bridge (claude-code / codex /
 * gemini-cli / mistral-vibe) can never serve, so those pickers must not offer
 * bridge providers even to admins. The primary-model pickers (no prop) keep
 * the full catalog.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import * as React from 'react';
import type { AIModel, AIProvider } from '@/hooks/useModels';

const h = vi.hoisted(() => ({
  providers: [] as unknown[],
  defaultProvider: null as string | null,
  defaultModel: null as string | null,
}));

vi.mock('next/image', () => ({ default: () => null }));
// Radix Select renders options in a portal only when open - inline them so the
// full option list is assertable.
vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectItem: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectTrigger: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('@/components/ai/ModelInfo', () => ({
  ModelOptionDisplay: ({ model }: { model: { id: string } }) => <span>{model.id}</span>,
  ModelInfoPopover: () => null,
}));
// Keep the real bridge predicate + selection helpers; only the hook is stubbed.
vi.mock('@/hooks/useModels', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/hooks/useModels')>();
  return {
    ...actual,
    useVisibleModels: () => ({
      providers: h.providers,
      defaultProvider: h.defaultProvider,
      defaultModel: h.defaultModel,
      isLoading: false,
      error: null,
      models: [],
      refresh: async () => {},
    }),
  };
});

import { ModelPicker } from '../ModelPicker';

const model = (provider: string, id: string, providerKind?: AIModel['providerKind']): AIModel => ({
  id,
  name: id,
  provider,
  ...(providerKind ? { providerKind } : {}),
});

const provider = (name: string, models: AIModel[], displayOrder: number): AIProvider => ({
  name,
  defaultModel: models[0]?.id ?? '',
  supportsStreaming: true,
  supportsToolCalling: true,
  displayOrder,
  models,
});

const CATALOG = [
  provider('claude-code', [model('claude-code', 'claude-opus-4-6', 'bridge')], 1),
  provider('codex', [model('codex', 'gpt-5.4', 'bridge')], 2),
  provider('openai', [model('openai', 'gpt-5-mini', 'cloud')], 3),
  provider('anthropic', [model('anthropic', 'claude-haiku-4-5', 'cloud')], 4),
];

const noop = () => {};

describe('ModelPicker - excludeBridgeProviders', () => {
  afterEach(() => cleanup());

  it('keeps bridge providers listed when the prop is absent (primary pickers unchanged)', () => {
    h.providers = CATALOG;
    h.defaultProvider = 'openai';
    h.defaultModel = 'gpt-5-mini';
    render(<ModelPicker value={{ provider: '', id: '' }} onChange={noop} />);
    expect(screen.getAllByText('Claude Code').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Codex').length).toBeGreaterThan(0);
  });

  it('hides every bridge provider from the options when set', () => {
    h.providers = CATALOG;
    h.defaultProvider = 'openai';
    h.defaultModel = 'gpt-5-mini';
    render(<ModelPicker value={{ provider: '', id: '' }} onChange={noop} excludeBridgeProviders />);
    expect(screen.queryByText('Claude Code')).toBeNull();
    expect(screen.queryByText('Codex')).toBeNull();
    expect(screen.getAllByText('OpenAI').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Anthropic').length).toBeGreaterThan(0);
  });

  it('resolves a bridge VALUE to a non-bridge provider instead of showing the bridge', () => {
    h.providers = CATALOG;
    // Even the backend default being a bridge must not leak one through.
    h.defaultProvider = 'claude-code';
    h.defaultModel = 'claude-opus-4-6';
    render(
      <ModelPicker
        value={{ provider: 'claude-code', id: 'claude-opus-4-6' }}
        onChange={noop}
        excludeBridgeProviders
      />,
    );
    expect(screen.queryByText('Claude Code')).toBeNull();
    // Falls through to the first non-bridge provider by displayOrder.
    expect(screen.getAllByText('OpenAI').length).toBeGreaterThan(0);
  });

  it('drops the bridge ROWS of a mixed provider but keeps its non-bridge models', () => {
    h.providers = [
      provider('weird', [
        model('weird', 'bridge-model', 'bridge'),
        model('weird', 'cloud-model', 'cloud'),
      ], 1),
    ];
    h.defaultProvider = 'weird';
    h.defaultModel = 'cloud-model';
    render(
      <ModelPicker value={{ provider: 'weird', id: '' }} onChange={noop} excludeBridgeProviders />,
    );
    expect(screen.queryByText('bridge-model')).toBeNull();
    expect(screen.getAllByText('cloud-model').length).toBeGreaterThan(0);
  });
});
