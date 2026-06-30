import { describe, expect, it } from 'vitest';
import {
  isBridgeModel,
  filterVisibleModels,
  BRIDGE_PROVIDER_NAMES,
  type AIModel,
  type AIProvider,
} from '@/hooks/useModels';

const model = (provider: string, id: string, providerKind?: AIModel['providerKind']): AIModel => ({
  id,
  name: id,
  provider,
  ...(providerKind ? { providerKind } : {}),
});

const provider = (name: string, models: AIModel[]): AIProvider => ({
  name,
  defaultModel: models[0]?.id ?? '',
  supportsStreaming: true,
  supportsToolCalling: true,
  models,
});

describe('isBridgeModel', () => {
  it('recognises bridges via providerKind', () => {
    expect(isBridgeModel(model('codex', 'gpt-5.4', 'bridge'))).toBe(true);
  });

  it('recognises bridges via provider name when providerKind is absent', () => {
    for (const name of BRIDGE_PROVIDER_NAMES) {
      expect(isBridgeModel(model(name, 'x'))).toBe(true);
    }
    expect(isBridgeModel(model('Claude-Code', 'x'))).toBe(true); // case-insensitive
  });

  it('treats cloud/byok providers as non-bridge', () => {
    expect(isBridgeModel(model('openai', 'gpt-5.4', 'cloud'))).toBe(false);
    expect(isBridgeModel(model('anthropic', 'claude-opus-4-6', 'byok'))).toBe(false);
    expect(isBridgeModel(model('openai', 'gpt-5.4'))).toBe(false);
  });
});

describe('filterVisibleModels', () => {
  const base = {
    models: [
      model('openai', 'gpt-5.4', 'cloud'),
      model('codex', 'gpt-5.4', 'bridge'),
      model('anthropic', 'claude-opus-4-6', 'cloud'),
      model('claude-code', 'claude-opus-4-6', 'bridge'),
    ],
    providers: [
      provider('openai', [model('openai', 'gpt-5.4', 'cloud')]),
      provider('codex', [model('codex', 'gpt-5.4', 'bridge')]),
      provider('anthropic', [model('anthropic', 'claude-opus-4-6', 'cloud')]),
      provider('claude-code', [model('claude-code', 'claude-opus-4-6', 'bridge')]),
    ],
    defaultModel: 'gpt-5.4',
    defaultProvider: 'openai',
    isLoading: false,
    error: null,
    refresh: async () => {},
  };

  it('returns the catalog unchanged for an admin', () => {
    expect(filterVisibleModels(base, true)).toBe(base);
  });

  it('removes bridge models and empty bridge providers for a non-admin', () => {
    const out = filterVisibleModels(base, false);

    expect(out.models.map(m => `${m.provider}/${m.id}`)).toEqual([
      'openai/gpt-5.4',
      'anthropic/claude-opus-4-6',
    ]);
    expect(out.providers.map(p => p.name)).toEqual(['openai', 'anthropic']);
    // non-bridge providers/models are untouched, defaults preserved
    expect(out.defaultModel).toBe('gpt-5.4');
    expect(out.defaultProvider).toBe('openai');
  });

  it('keeps non-bridge models of a mixed provider while dropping its bridge rows', () => {
    const mixed = {
      ...base,
      models: [],
      providers: [
        provider('weird', [
          model('weird', 'cloud-model', 'cloud'),
          model('weird', 'bridge-model', 'bridge'),
        ]),
      ],
    };
    const out = filterVisibleModels(mixed, false);
    expect(out.providers).toHaveLength(1);
    expect(out.providers[0].models.map(m => m.id)).toEqual(['cloud-model']);
  });
});
