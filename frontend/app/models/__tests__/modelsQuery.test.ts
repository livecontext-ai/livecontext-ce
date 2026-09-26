import { describe, it, expect } from 'vitest';

import { CATALOG_MODELS } from '../_components/modelsData';
import { PROVIDER_KEYS, PROVIDER_PARAM, providerHref, resolveProviderParam } from '../_components/modelsQuery';
import { WELL_KNOWN_MODELS } from '@/lib/models/wellKnownModels';

describe('PROVIDER_KEYS', () => {
  it('is derived from the dataset, so it cannot name a provider with no rows', () => {
    const fromData = new Set(CATALOG_MODELS.map((model) => model.provider));
    expect([...PROVIDER_KEYS].sort()).toEqual([...fromData].sort());
  });
});

describe('resolveProviderParam', () => {
  it('accepts a provider the page carries', () => {
    expect(resolveProviderParam('xai')).toBe('xai');
  });

  it('normalises case and surrounding space, because the value comes from a URL', () => {
    expect(resolveProviderParam('  XAI ')).toBe('xai');
  });

  it('resolves an unknown provider to no filter, not to an empty list', () => {
    // The alternative, honoured verbatim, would render a page with nothing on it
    // for a link we changed or a URL somebody mistyped.
    expect(resolveProviderParam('a-provider-we-dropped')).toBeNull();
  });

  it('resolves a missing or blank param to no filter', () => {
    expect(resolveProviderParam(undefined)).toBeNull();
    expect(resolveProviderParam('')).toBeNull();
    expect(resolveProviderParam('   ')).toBeNull();
  });

  it('reads the first value when the param is repeated, and ignores the rest', () => {
    expect(resolveProviderParam(['xai', 'anthropic'])).toBe('xai');
    expect(resolveProviderParam([])).toBeNull();
  });
});

describe('providerHref', () => {
  it('builds the filtered URL from the same param name the page reads', () => {
    expect(providerHref('xai')).toBe(`/models?${PROVIDER_PARAM}=xai`);
  });

  it('round-trips: every href it builds resolves back to the provider it named', () => {
    for (const key of PROVIDER_KEYS) {
      const value = new URL(providerHref(key), 'https://livecontext.ai').searchParams.get(PROVIDER_PARAM);
      expect(resolveProviderParam(value ?? undefined)).toBe(key);
    }
  });
});

describe('the footer Models column links somewhere real', () => {
  it('names only providers the page can filter on', () => {
    // Each family in the footer deep-links into /models. A family
    // whose provider key is not in the dataset would resolve to no filter and
    // silently open the full list: the link would still "work", which is exactly
    // why nothing would ever report it.
    for (const model of WELL_KNOWN_MODELS) {
      expect(PROVIDER_KEYS, `${model.label} (${model.provider}) has no rows on /models`)
        .toContain(model.provider);
    }
  });

  it('gives each family a distinct destination', () => {
    const hrefs = WELL_KNOWN_MODELS.map((model) => providerHref(model.provider));
    expect(new Set(hrefs).size).toBe(WELL_KNOWN_MODELS.length);
  });
});
