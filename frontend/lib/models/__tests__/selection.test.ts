import { describe, expect, it } from 'vitest';
import { isSelectionAvailable } from '@/lib/models/selection';

/**
 * The check behind every composer's "reset to the default" (V554). An unlisted model is still
 * available, so a selection on one must survive; only a model gone from the catalogue is replaced.
 */
describe('isSelectionAvailable', () => {
  const listed = [{ id: 'gpt-5', provider: 'openai' }];
  const unlisted = [{ id: 'gpt-4o', provider: 'openai' }];

  it('accepts a listed model', () => {
    expect(isSelectionAvailable({ provider: 'openai', id: 'gpt-5' }, listed, unlisted)).toBe(true);
  });

  it('accepts an UNLISTED model: the user picked it from the hidden group', () => {
    expect(isSelectionAvailable({ provider: 'openai', id: 'gpt-4o' }, listed, unlisted)).toBe(true);
  });

  it('refuses a model gone from the catalogue altogether', () => {
    expect(isSelectionAvailable({ provider: 'openai', id: 'gpt-3.5' }, listed, unlisted)).toBe(false);
  });

  it('matches on the provider too: the same id under another provider is another model', () => {
    expect(isSelectionAvailable({ provider: 'codex', id: 'gpt-4o' }, listed, unlisted)).toBe(false);
  });

  it('refuses an empty selection, and works without an unlisted list (a caller predating V554)', () => {
    expect(isSelectionAvailable({ provider: '', id: '' }, listed, unlisted)).toBe(false);
    expect(isSelectionAvailable(undefined, listed)).toBe(false);
    expect(isSelectionAvailable({ provider: 'openai', id: 'gpt-4o' }, listed)).toBe(false);
  });
});
