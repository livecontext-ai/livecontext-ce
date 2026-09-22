import { describe, expect, it } from 'vitest';

import { buildModelNameIndex, resolveModelDisplayName } from '../modelDisplayName';

/**
 * A usage row stores the id a call was made with; an admin renames models in
 * Settings > AI Providers. These are the cases where getting the join wrong is
 * invisible: a row silently keeps showing the raw id, or worse, shows the name
 * of a DIFFERENT model that happens to share the id under another provider.
 */

const CATALOGUE = [
  { id: 'claude-sonnet-5', name: 'Claude Sonnet 5', provider: 'anthropic' },
  // Same id under the CLI bridge, named differently on purpose.
  { id: 'claude-sonnet-5', name: 'Sonnet 5 (Claude Code)', provider: 'claude-code' },
  { id: 'gpt-5.4', name: 'Our house GPT', provider: 'openai' },
  // Never renamed: the catalogue name IS the id.
  { id: 'deepseek-chat', name: 'deepseek-chat', provider: 'deepseek' },
];

describe('resolveModelDisplayName', () => {
  it('names a model by what the admin called it', () => {
    const index = buildModelNameIndex(CATALOGUE);

    expect(resolveModelDisplayName('openai', 'gpt-5.4', index)).toBe('Our house GPT');
  });

  it('keeps two providers that share an id apart', () => {
    // Since the bridge ids dropped their suffix, `claude-sonnet-5` exists twice.
    // A lookup by id alone would show one account's name for the other's spend.
    const index = buildModelNameIndex(CATALOGUE);

    expect(resolveModelDisplayName('anthropic', 'claude-sonnet-5', index)).toBe('Claude Sonnet 5');
    expect(resolveModelDisplayName('claude-code', 'claude-sonnet-5', index))
      .toBe('Sonnet 5 (Claude Code)');
  });

  it('refuses to guess when the provider is unknown and the id is claimed twice', () => {
    // Returning either name would be a coin toss rendered as a fact. Null means
    // the caller falls back to the stored id, which is always true.
    const index = buildModelNameIndex(CATALOGUE);

    expect(resolveModelDisplayName(null, 'claude-sonnet-5', index)).toBeNull();
    expect(resolveModelDisplayName('mystery-relay', 'claude-sonnet-5', index)).toBeNull();
  });

  it('still names a model whose provider spelling drifted, when only one name claims the id', () => {
    // A relayed row, or a provider renamed upstream: the pair misses, the id is
    // unambiguous, and the name is still the right answer.
    const index = buildModelNameIndex(CATALOGUE);

    expect(resolveModelDisplayName('openai-eu', 'gpt-5.4', index)).toBe('Our house GPT');
  });

  it('matches whatever case the stored provider was written in', () => {
    const index = buildModelNameIndex(CATALOGUE);

    expect(resolveModelDisplayName('Anthropic', 'Claude-Sonnet-5', index)).toBe('Claude Sonnet 5');
  });

  it('adds nothing for a model nobody renamed', () => {
    // Its catalogue name IS its id, so there is no alias to show and the row
    // reads exactly as it did before.
    const index = buildModelNameIndex(CATALOGUE);

    expect(resolveModelDisplayName('deepseek', 'deepseek-chat', index)).toBeNull();
  });

  it('answers null for a model the catalogue no longer carries, and for a row with no model', () => {
    const index = buildModelNameIndex(CATALOGUE);

    expect(resolveModelDisplayName('anthropic', 'claude-opus-3', index)).toBeNull();
    expect(resolveModelDisplayName('anthropic', null, index)).toBeNull();
  });

  it('answers null while the catalogue has not loaded, rather than throwing under the table', () => {
    expect(resolveModelDisplayName('anthropic', 'claude-sonnet-5', null)).toBeNull();
    expect(resolveModelDisplayName('anthropic', 'claude-sonnet-5', buildModelNameIndex([]))).toBeNull();
  });

  it('names an id two providers share when they agree on the name', () => {
    // "Ambiguous" means two DIFFERENT names, not two rows. A bridge and its
    // cloud twin usually carry the same name, and refusing there would drop the
    // name on exactly the models the bridge exists to duplicate.
    const index = buildModelNameIndex([
      { id: 'gpt-5.4', name: 'GPT-5.4', provider: 'openai' },
      { id: 'gpt-5.4', name: 'GPT-5.4', provider: 'codex' },
    ]);

    expect(resolveModelDisplayName('some-relay', 'gpt-5.4', index)).toBe('GPT-5.4');
  });

  it('does not let an un-renamed twin make a renamed id look ambiguous', () => {
    // The `name === id` skip is what makes the ambiguity test above about real
    // disagreements: without it, a catalogue row whose name is simply its id
    // would count as a second, conflicting name and silence the one an admin
    // actually typed.
    const index = buildModelNameIndex([
      { id: 'gpt-5.4', name: 'Our house GPT', provider: 'openai' },
      { id: 'gpt-5.4', name: 'gpt-5.4', provider: 'codex' },
    ]);

    expect(resolveModelDisplayName('some-relay', 'gpt-5.4', index)).toBe('Our house GPT');
  });

  it('ignores a catalogue row with no name at all', () => {
    const index = buildModelNameIndex([{ id: 'nameless', provider: 'p' }]);

    expect(resolveModelDisplayName('p', 'nameless', index)).toBeNull();
  });
});
