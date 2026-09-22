import { describe, expect, it } from 'vitest';
import { assetRoleHint, assetRoleLabel, paramLabel, readableRefusal } from '../labels';
import type { StudioField } from '../paramSpec';

/**
 * Naming a parameter, naming a file slot, and deciding what a refusal may say to a person.
 *
 * <p>All three are guards against the same class of failure: a value that arrives from the API
 * catalogue or from a provider, and reaches a reader unchanged. The catalogue ships roles and
 * parameter names this build has never seen, and the generation endpoint can hand back a machine
 * envelope instead of a sentence.
 */

/** A translator over a small dictionary, with the `has` guard the real one provides. */
function translator(dictionary: Record<string, string>) {
  const t = ((key: string) => dictionary[key] ?? key) as ((key: string) => string) & {
    has: (key: string) => boolean;
  };
  t.has = (key: string) => key in dictionary;
  return t;
}

function field(overrides: Partial<StudioField> = {}): StudioField {
  return {
    name: 'input_image',
    kind: 'asset',
    required: false,
    slots: 1,
    requiresFields: [],
    excludesFields: [],
    choices: [],
    optionsMustBeFetched: false,
    choicesAreSuggestions: false,
    ...overrides,
  };
}

describe('paramLabel', () => {
  it('uses the dictionary when it has a word', () => {
    expect(paramLabel('seed', translator({ 'params.seed': 'Seed' }))).toBe('Seed');
  });

  it('falls back to the contract name for a parameter this build has no word for', () => {
    // The catalogue ships new parameters without the app being rebuilt. An unguarded lookup would
    // render the raw key path, or throw, the first time a provider declares one.
    expect(paramLabel('cfg_scale', translator({}))).toBe('cfg_scale');
  });
});

describe('assetRoleHint', () => {
  it('says what the model DOES with the file, which the name of the slot does not', () => {
    const t = translator({ 'assetRoleHints.last_frame': 'The clip ends on this image.' });
    expect(assetRoleHint(field({ role: 'last_frame' }), t)).toBe('The clip ends on this image.');
  });

  it('says nothing at all for a role this build has no sentence for', () => {
    // Null, not a key path: an unguarded lookup would print "assetRoleHints.depth_map" under the
    // field the first time the catalogue ships a role this build predates.
    expect(assetRoleHint(field({ role: 'depth_map' }), translator({}))).toBeNull();
  });

  it('says nothing when the model declares no role, since there is nothing to explain', () => {
    expect(assetRoleHint(field({ role: undefined }), translator({}))).toBeNull();
  });
});

describe('assetRoleLabel', () => {
  it('names the slot by what the file IS to this model', () => {
    const t = translator({ 'assetRoles.first_frame': 'First frame' });
    expect(assetRoleLabel(field({ role: 'first_frame' }), t)).toBe('First frame');
  });

  it('falls back to the parameter name for a role this build has no word for', () => {
    expect(assetRoleLabel(field({ role: 'depth_map' }), translator({}))).toBe('input_image');
  });

  it('numbers a slot only when the parameter takes several', () => {
    const t = translator({ 'assetRoles.source': 'Source image' });
    // "Source image 1" on a parameter taking exactly one invites a look for a second.
    expect(assetRoleLabel(field({ role: 'source', slots: 1 }), t, 0)).toBe('Source image');
    expect(assetRoleLabel(field({ role: 'source', slots: 3 }), t, 0)).toBe('Source image 1');
    expect(assetRoleLabel(field({ role: 'source', slots: 3 }), t, 2)).toBe('Source image 3');
  });

  it('is unnumbered when no slot is named, whatever the count', () => {
    const t = translator({ 'assetRoles.source': 'Source image' });
    expect(assetRoleLabel(field({ role: 'source', slots: 3 }), t)).toBe('Source image');
  });
});

describe('readableRefusal', () => {
  it('shows the endpoint words verbatim - they name the remedy', () => {
    expect(readableRefusal('Not enough credits to run this model.', 'fallback'))
      .toBe('Not enough credits to run this model.');
  });

  it('replaces a machine envelope, which is what this guard exists for', () => {
    // A whole JSON payload printed at a reader: internal ids, an endpoint path, a request id.
    expect(readableRefusal('{"error":"boom","requestId":"abc","path":"/api/x"}', 'fallback'))
      .toBe('fallback');
    expect(readableRefusal('[{"code":1}]', 'fallback')).toBe('fallback');
  });

  it('keeps a sentence that merely opens with a brace', () => {
    // Narrow on purpose: only a payload that PARSES as JSON is swallowed, so nothing actionable is.
    expect(readableRefusal('{model} is not available on this key', 'fallback'))
      .toBe('{model} is not available on this key');
  });

  it('falls back for nothing at all', () => {
    expect(readableRefusal(undefined, 'fallback')).toBe('fallback');
    expect(readableRefusal(null, 'fallback')).toBe('fallback');
    expect(readableRefusal('   ', 'fallback')).toBe('fallback');
  });

  it('trims, so a padded sentence is still a sentence', () => {
    expect(readableRefusal('  no credits  ', 'fallback')).toBe('no credits');
  });
});
