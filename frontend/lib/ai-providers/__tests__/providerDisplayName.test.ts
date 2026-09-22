import { describe, expect, it } from 'vitest';

import { getProviderDisplayName, getProviderIconSlug } from '../providerIcons';

/**
 * How a provider key becomes a name a reader recognises.
 *
 * <p>The case rule is the whole reason this file exists. The two resolvers in
 * `providerIcons` disagreed: the icon one lower-cased its key and the label one
 * did not, so the same row could get the right logo and the raw wire key beside
 * it. The credit ledger stores whatever the calling service sent, and the
 * catalogue promises no casing, so "it is always lower-case in practice" was an
 * assumption with nothing holding it up.
 */

describe('getProviderDisplayName', () => {
  it('names a provider the catalogue knows', () => {
    expect(getProviderDisplayName('anthropic')).toBe('Anthropic');
    expect(getProviderDisplayName('claude-code')).toBe('Claude Code');
  });

  it('matches whatever case the key was stored in', () => {
    expect(getProviderDisplayName('Anthropic')).toBe('Anthropic');
    expect(getProviderDisplayName('OpenAI')).toBe('OpenAI');
    expect(getProviderDisplayName('XAI')).toBe('xAI');
  });

  it('resolves the same keys the icon lookup does, which is the bug it closes', () => {
    // A row that gets a logo and a raw key beside it is the visible symptom, so
    // both resolvers are asked for the SAME keys and each is asserted against
    // the name it must produce. `not.toBe(lower-case)` would be vacuous here:
    // the old exact-key lookup already returned the input verbatim, so it
    // satisfied that for every key it failed to match.
    const EXPECTED: ReadonlyArray<[string, string]> = [
      ['Anthropic', 'Anthropic'],
      ['OpenAI', 'OpenAI'],
      ['Gemini-CLI', 'Gemini CLI'],
      ['Claude-Code', 'Claude Code'],
    ];
    for (const [key, label] of EXPECTED) {
      expect(getProviderIconSlug(key), `${key} icon`).toBe(key.toLowerCase());
      expect(getProviderDisplayName(key), `${key} label`).toBe(label);
    }
  });

  it('hands back an unknown provider exactly as given, never lower-cased', () => {
    // A key nobody here knows may already BE a name: catalogue tool calls store
    // the API's own title in this column. Lower-casing it would mangle a good
    // label to chase a match that does not exist.
    expect(getProviderDisplayName('Google Gemini')).toBe('Google Gemini');
    expect(getProviderDisplayName('some-new-provider')).toBe('some-new-provider');
  });

  it('answers with an empty string for nothing at all', () => {
    expect(getProviderDisplayName(null)).toBe('');
    expect(getProviderDisplayName(undefined)).toBe('');
    expect(getProviderDisplayName('')).toBe('');
  });
});
