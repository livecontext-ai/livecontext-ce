// @vitest-environment node
import { describe, it, expect } from 'vitest';
import { WELL_KNOWN_MODELS } from '../wellKnownModels';
import { CATALOG_MODELS } from '@/app/models/_components/modelsData';

/**
 * The footer names model families, each linking to its filtered view of /models.
 *
 * <p>Checked against the /models data, NOT against the hosted catalogue. Until 2026-09-25
 * this test read the CE seed and required an ENABLED model per family, so the day the
 * cloud switched most providers off, Grok, Mistral, Qwen and Kimi had to leave the footer
 * of every public page. The footer, like /models, is an informative index of model
 * families; whether the cloud serves one today is a hosting decision, not a fact about the
 * family.
 */

const pageProviders = new Set(CATALOG_MODELS.map((model) => model.provider));

describe('well-known models in the footer', () => {
  it('reads the /models data at all, so the cases below cannot pass vacuously', () => {
    expect(pageProviders.size).toBeGreaterThan(5);
  });

  it('names eight families, the width of the integrations column beside it', () => {
    expect(WELL_KNOWN_MODELS).toHaveLength(8);
  });

  it.each(WELL_KNOWN_MODELS.map((m) => [m.label, m.provider]))(
    '%s links to a provider /models actually lists (%s)',
    (_label, provider) => {
      expect(pageProviders.has(provider)).toBe(true);
    },
  );

  it('names each provider once, so no family is listed twice under two labels', () => {
    const keys = WELL_KNOWN_MODELS.map((m) => m.provider);
    expect(new Set(keys).size).toBe(keys.length);
  });
});
