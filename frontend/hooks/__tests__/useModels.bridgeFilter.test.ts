import { beforeEach, describe, expect, it, vi } from 'vitest';

// The visibility rule reads the ROLE only; the edition is not an input any more (hiding the
// bridges from cloud admins too is what made the CLI models vanish for the platform's own
// operators). The mutable holder stays so the tests can state, per case, that flipping the
// edition changes nothing: a reintroduced `!IS_MANAGED_CLOUD` term would show up here.
const edition = { IS_MANAGED_CLOUD: false };
// IS_CE as well: this mock REPLACES the module, and `smart-providers` (pulled in through
// useModels) reads IS_CE off it at module scope, so omitting it fails the whole suite at
// import time rather than in an assertion.
vi.mock('@/lib/edition', () => ({
  get IS_MANAGED_CLOUD() {
    return edition.IS_MANAGED_CLOUD;
  },
  IS_CE: false,
}));
import {
  isBridgeModel,
  filterVisibleModels,
  toNonBridgeSelectedModel,
  EMPTY_SELECTED_MODEL,
  BRIDGE_PROVIDER_NAMES,
  type AIModel,
  type AIProvider,
  type ModelsData,
} from '@/hooks/useModels';

const model = (provider: string, id: string, providerKind?: AIModel['providerKind']): AIModel => ({
  id,
  name: id,
  provider,
  ...(providerKind ? { providerKind } : {}),
});

const provider = (name: string, models: AIModel[], displayOrder?: number): AIProvider => ({
  name,
  defaultModel: models[0]?.id ?? '',
  supportsStreaming: true,
  supportsToolCalling: true,
  ...(displayOrder !== undefined ? { displayOrder } : {}),
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
  // Self-hosted by default: the pre-existing cases were written against it, and a value leaked
  // from a neighbouring test would silently change which rule they exercise.
  beforeEach(() => {
    edition.IS_MANAGED_CLOUD = false;
  });

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
    edition.IS_MANAGED_CLOUD = false;
    expect(filterVisibleModels(base, true)).toBe(base);
  });

  it('an ADMIN keeps the bridges in BOTH deployments - on cloud they operate the shared CLI', () => {
    // The regression this pins: hiding the bridges from cloud admins too made the CLI models
    // vanish from every picker in production for the accounts that administer them. The backend
    // admits an admin's save to the access policy (admin-only by default) and still gates the
    // dispatch, so showing them here offers nothing a user can reach.
    for (const managed of [false, true]) {
      edition.IS_MANAGED_CLOUD = managed;
      expect(filterVisibleModels(base, true), `IS_MANAGED_CLOUD=${managed}`).toBe(base);
    }
  });

  it('self-hosted: an ADMIN keeps the bridges - the CLI there belongs to the install', () => {
    edition.IS_MANAGED_CLOUD = false;

    expect(filterVisibleModels(base, true)).toBe(base);
  });

  it('a non-admin loses the bridges in BOTH deployments', () => {
    for (const managed of [false, true]) {
      edition.IS_MANAGED_CLOUD = managed;

      // Named per iteration: without it a failure says only "expected [...] to equal [...]" and
      // not which edition regressed, which is the one thing this loop exists to distinguish.
      expect(filterVisibleModels(base, false).providers.map(p => p.name), `IS_MANAGED_CLOUD=${managed}`)
        .toEqual(['openai', 'anthropic']);
    }
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

describe('toNonBridgeSelectedModel (compaction-summariser seed guard)', () => {
  // A CLI bridge can never serve the summariser's bare single completion, so
  // seeding must never produce a bridge pair - it falls back to the first
  // non-bridge provider's default model instead.
  const data: ModelsData = {
    providers: [
      provider('claude-code', [model('claude-code', 'claude-opus-4-6', 'bridge')], 1),
      provider('anthropic', [model('anthropic', 'claude-haiku-4-5', 'cloud')], 2),
      provider('openai', [model('openai', 'gpt-5-mini', 'cloud')], 3),
    ],
    defaultProvider: 'claude-code',
    defaultModel: 'claude-opus-4-6',
  };

  it('returns a non-bridge seed unchanged', () => {
    const seed = { provider: 'openai', id: 'gpt-5-mini' };
    expect(toNonBridgeSelectedModel(seed, data)).toBe(seed);
  });

  it('replaces a bridge seed with the first non-bridge provider default (by displayOrder)', () => {
    const seed = { provider: 'claude-code', id: 'claude-opus-4-6' };
    expect(toNonBridgeSelectedModel(seed, data)).toEqual({
      provider: 'anthropic',
      id: 'claude-haiku-4-5',
    });
  });

  it('recognises every bridge provider name in the seed (case-insensitive)', () => {
    for (const name of BRIDGE_PROVIDER_NAMES) {
      const out = toNonBridgeSelectedModel({ provider: name.toUpperCase(), id: 'x' }, data);
      expect(out.provider).toBe('anthropic');
    }
  });

  it('skips bridge ROWS of a mixed provider and picks its first non-bridge model', () => {
    const mixed: ModelsData = {
      providers: [
        provider('weird', [
          model('weird', 'bridge-model', 'bridge'),
          model('weird', 'cloud-model', 'cloud'),
        ], 1),
      ],
      defaultProvider: null,
      defaultModel: null,
    };
    // provider.defaultModel points at the bridge row → the fallback must pick
    // the first NON-bridge model instead of the declared default.
    expect(toNonBridgeSelectedModel({ provider: 'codex', id: 'gpt-5.4' }, mixed)).toEqual({
      provider: 'weird',
      id: 'cloud-model',
    });
  });

  it('falls back from an EMPTY seed to the first non-bridge provider default', () => {
    expect(toNonBridgeSelectedModel(EMPTY_SELECTED_MODEL, data)).toEqual({
      provider: 'anthropic',
      id: 'claude-haiku-4-5',
    });
  });

  it('returns EMPTY when the catalog has only bridge providers (caller skips seeding)', () => {
    const bridgeOnly: ModelsData = {
      providers: [provider('codex', [model('codex', 'gpt-5.4', 'bridge')], 1)],
      defaultProvider: 'codex',
      defaultModel: 'gpt-5.4',
    };
    expect(toNonBridgeSelectedModel({ provider: 'codex', id: 'gpt-5.4' }, bridgeOnly))
      .toBe(EMPTY_SELECTED_MODEL);
  });

  it('returns EMPTY for a bridge seed when the cache is not populated', () => {
    expect(toNonBridgeSelectedModel({ provider: 'claude-code', id: 'x' }, null))
      .toBe(EMPTY_SELECTED_MODEL);
  });
});
