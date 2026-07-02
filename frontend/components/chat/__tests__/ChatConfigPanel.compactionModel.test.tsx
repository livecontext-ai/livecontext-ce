/**
 * @vitest-environment jsdom
 *
 * Summariser-model override row inside the compaction block. Off = inherit
 * (hint text, no picker; agent conversations inherit the agent's compaction
 * model if set, otherwise the platform default). Toggling ON immediately
 * persists the resolved default pair (so the picker never displays a selection
 * that was not saved) and NEVER a bridge pair (a CLI bridge cannot serve the
 * summariser's bare single completion), toggling OFF clears with a blank pair,
 * and a pick writes BOTH keys together through updateConfig (both-or-neither).
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import * as React from 'react';

const h = vi.hoisted(() => ({
  updateConfig: vi.fn(),
  config: {} as Record<string, unknown>,
  defaultSelection: { provider: '', id: '' },
  modelsCache: null as unknown,
}));

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/hooks/useChatConfig', () => ({
  useChatConfig: () => ({
    config: h.config,
    updateConfig: h.updateConfig,
    isLoading: false,
    isSaving: false,
    error: null,
    target: 'conversation',
  }),
}));
// Partial mock: the platform default + catalog cache are test-controlled, but the
// seed guard (toNonBridgeSelectedModel / isEmptySelectedModel) stays REAL so the
// "never seed a bridge pair" behaviour is exercised, not stubbed.
vi.mock('@/hooks/useModels', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/hooks/useModels')>();
  return {
    ...actual,
    getEffectiveDefaultSelectedModel: () => h.defaultSelection,
    getModelsCache: () => h.modelsCache,
  };
});
// Stub the shared picker: expose the received value + bridge-exclusion flag and
// emit a fixed pick on click.
vi.mock('@/components/ai/ModelPicker', () => ({
  ModelPicker: (props: {
    value: { provider: string; id: string };
    onChange: (next: { provider: string; id: string }) => void;
    excludeBridgeProviders?: boolean;
  }) => (
    <button
      data-testid="model-picker"
      data-provider={props.value.provider}
      data-model={props.value.id}
      data-exclude-bridge={props.excludeBridgeProviders ? 'true' : 'false'}
      onClick={() => props.onChange({ provider: 'anthropic', id: 'claude-haiku-4-5' })}
    />
  ),
}));
// Stub the radix-based wrappers so jsdom doesn't need ResizeObserver / pointer APIs.
// Switch is NOT mocked - the toggle behaviour is exactly what we assert on.
vi.mock('@/components/ui/slider', () => ({ Slider: () => <div data-testid="slider" /> }));
vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectItem: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectTrigger: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectValue: () => <div />,
}));
vi.mock('@/components/ui/tooltip', () => ({
  Tooltip: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  TooltipContent: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  TooltipProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  TooltipTrigger: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

import { ChatConfigPanel } from '../ChatConfigPanel';

// advancedOpenByDefault so the composer branch renders the compaction block directly.
const renderPanel = () => render(<ChatConfigPanel conversationId="c1" advancedOpenByDefault />);

describe('ChatConfigPanel - compaction summariser-model override', () => {
  beforeEach(() => {
    h.updateConfig.mockClear();
    h.config = { compactionEnabled: true };
    h.defaultSelection = { provider: '', id: '' };
    h.modelsCache = null;
  });
  afterEach(() => cleanup());

  it('is hidden while compaction is disabled (mirrors the after-N-turns gating)', () => {
    h.config = {};
    renderPanel();
    expect(screen.queryByText('compactionModelLabel')).toBeNull();
  });

  it('unset override shows the inherit hint and no picker', () => {
    renderPanel();
    expect(screen.getByText('compactionModelPlatformDefault')).toBeInTheDocument();
    expect(screen.queryByTestId('model-picker')).toBeNull();
    expect(screen.getByRole('switch', { name: 'compactionModelLabel' })).toHaveAttribute('aria-checked', 'false');
  });

  it('toggling ON persists the resolved default pair immediately (shown pick = saved pick)', () => {
    h.defaultSelection = { provider: 'openai', id: 'gpt-5-mini' };
    renderPanel();
    fireEvent.click(screen.getByRole('switch', { name: 'compactionModelLabel' }));
    expect(h.updateConfig).toHaveBeenCalledWith({
      compactionModelProvider: 'openai',
      compactionModelName: 'gpt-5-mini',
    });
  });

  it('toggling ON with an empty catalog cache opens the picker without persisting a pair', () => {
    renderPanel();
    fireEvent.click(screen.getByRole('switch', { name: 'compactionModelLabel' }));
    expect(h.updateConfig).not.toHaveBeenCalled();
    expect(screen.getByTestId('model-picker')).toBeInTheDocument();
  });

  it('toggling ON with a BRIDGE platform default seeds the first non-bridge provider instead (never a bridge pair)', () => {
    // A CLI bridge cannot serve the summariser's bare single completion (the
    // backend rejects a bridge-linked pair with 400), so the seed must swap to
    // a non-bridge provider even when the platform default is a bridge.
    h.defaultSelection = { provider: 'claude-code', id: 'claude-opus-4-6' };
    h.modelsCache = {
      providers: [
        {
          name: 'claude-code', defaultModel: 'claude-opus-4-6', displayOrder: 1,
          supportsStreaming: true, supportsToolCalling: true,
          models: [{ id: 'claude-opus-4-6', name: 'claude-opus-4-6', provider: 'claude-code', providerKind: 'bridge' }],
        },
        {
          name: 'deepseek', defaultModel: 'deepseek-chat', displayOrder: 2,
          supportsStreaming: true, supportsToolCalling: true,
          models: [{ id: 'deepseek-chat', name: 'deepseek-chat', provider: 'deepseek', providerKind: 'cloud' }],
        },
      ],
      defaultProvider: 'claude-code',
      defaultModel: 'claude-opus-4-6',
    };
    renderPanel();
    fireEvent.click(screen.getByRole('switch', { name: 'compactionModelLabel' }));
    expect(h.updateConfig).toHaveBeenCalledWith({
      compactionModelProvider: 'deepseek',
      compactionModelName: 'deepseek-chat',
    });
  });

  it('toggling ON with a bridge default and a bridge-only catalog persists nothing (no bridge pair, ever)', () => {
    h.defaultSelection = { provider: 'codex', id: 'gpt-5.4' };
    h.modelsCache = {
      providers: [
        {
          name: 'codex', defaultModel: 'gpt-5.4', displayOrder: 1,
          supportsStreaming: true, supportsToolCalling: true,
          models: [{ id: 'gpt-5.4', name: 'gpt-5.4', provider: 'codex', providerKind: 'bridge' }],
        },
      ],
      defaultProvider: 'codex',
      defaultModel: 'gpt-5.4',
    };
    renderPanel();
    fireEvent.click(screen.getByRole('switch', { name: 'compactionModelLabel' }));
    expect(h.updateConfig).not.toHaveBeenCalled();
    expect(screen.getByTestId('model-picker')).toBeInTheDocument();
  });

  it('renders the summariser picker with bridge providers excluded', () => {
    h.config = {
      compactionEnabled: true,
      compactionModelProvider: 'openai',
      compactionModelName: 'gpt-5-mini',
    };
    renderPanel();
    expect(screen.getByTestId('model-picker')).toHaveAttribute('data-exclude-bridge', 'true');
  });

  it('a persisted pair renders the picker with the stored value (no hint)', () => {
    h.config = {
      compactionEnabled: true,
      compactionModelProvider: 'openai',
      compactionModelName: 'gpt-5-mini',
    };
    renderPanel();
    const picker = screen.getByTestId('model-picker');
    expect(picker).toHaveAttribute('data-provider', 'openai');
    expect(picker).toHaveAttribute('data-model', 'gpt-5-mini');
    expect(screen.queryByText('compactionModelPlatformDefault')).toBeNull();
    expect(screen.getByRole('switch', { name: 'compactionModelLabel' })).toHaveAttribute('aria-checked', 'true');
  });

  it('picking a model writes BOTH keys through updateConfig (both-or-neither)', () => {
    h.config = {
      compactionEnabled: true,
      compactionModelProvider: 'openai',
      compactionModelName: 'gpt-5-mini',
    };
    renderPanel();
    fireEvent.click(screen.getByTestId('model-picker'));
    expect(h.updateConfig).toHaveBeenCalledWith({
      compactionModelProvider: 'anthropic',
      compactionModelName: 'claude-haiku-4-5',
    });
  });

  it('toggling OFF clears back to inherit with a blank pair', () => {
    h.config = {
      compactionEnabled: true,
      compactionModelProvider: 'openai',
      compactionModelName: 'gpt-5-mini',
    };
    renderPanel();
    fireEvent.click(screen.getByRole('switch', { name: 'compactionModelLabel' }));
    expect(h.updateConfig).toHaveBeenCalledWith({
      compactionModelProvider: '',
      compactionModelName: '',
    });
  });

  it('a stored PARTIAL pair reads as unset (hint shown, switch off)', () => {
    h.config = { compactionEnabled: true, compactionModelProvider: 'openai' };
    renderPanel();
    expect(screen.getByText('compactionModelPlatformDefault')).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'compactionModelLabel' })).toHaveAttribute('aria-checked', 'false');
  });
});
