import { describe, it, expect } from 'vitest';
import {
  isProviderHiddenInCe,
  CE_HIDDEN_PROVIDERS,
  PROVIDER_ICON_MAP,
  PROVIDER_DISPLAY_NAMES,
} from '@/lib/ai-providers/providerIcons';

describe('CE-hidden providers boundary', () => {
  it('hides the multi-provider aggregator (openrouter) and cohere', () => {
    expect(isProviderHiddenInCe('openrouter')).toBe(true);
    expect(isProviderHiddenInCe('cohere')).toBe(true);
  });

  it('does not hide any other provider, incl. the new qwen/moonshot', () => {
    for (const p of ['openai', 'anthropic', 'google', 'deepseek', 'zai', 'qwen', 'moonshot']) {
      expect(isProviderHiddenInCe(p)).toBe(false);
    }
  });

  it('is case-insensitive and null-safe', () => {
    expect(isProviderHiddenInCe('OpenRouter')).toBe(true);
    expect(isProviderHiddenInCe(null)).toBe(false);
    expect(isProviderHiddenInCe(undefined)).toBe(false);
    expect(isProviderHiddenInCe('')).toBe(false);
  });

  it('exposes exactly the two blocked providers', () => {
    expect([...CE_HIDDEN_PROVIDERS].sort()).toEqual(['cohere', 'openrouter']);
  });
});

describe('new Chinese providers are wired into the icon/label maps', () => {
  it('qwen and moonshot have an icon slug and a display name', () => {
    expect(PROVIDER_ICON_MAP.qwen).toBe('qwen');
    expect(PROVIDER_ICON_MAP.moonshot).toBe('moonshot');
    expect(PROVIDER_DISPLAY_NAMES.qwen).toBe('Qwen');
    expect(PROVIDER_DISPLAY_NAMES.moonshot).toBe('Moonshot');
  });
});
