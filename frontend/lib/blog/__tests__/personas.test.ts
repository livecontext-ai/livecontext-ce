import { describe, it, expect } from 'vitest';
import { getPersona, personaName } from '../personas';

describe('personaName', () => {
  it('maps handle-style byline keys to proper display names', () => {
    // Regression: bylines used to render raw handles like "noah_schmidt".
    expect(personaName('noah_schmidt')).toBe('Noah Schmidt');
    expect(personaName('nora_a')).toBe('Nora A.');
    expect(personaName('ines_l')).toBe('Inès L.');
    expect(personaName('theo p.')).toBe('Théo P.');
  });

  it('leaves already-clean display names untouched', () => {
    expect(personaName('Sophie M.')).toBe('Sophie M.');
    expect(personaName('Emma R.')).toBe('Emma R.');
  });

  it('falls back to the key for an unregistered author', () => {
    expect(personaName('unknown')).toBe('unknown');
  });
});

describe('getPersona', () => {
  it('exposes an avatar path for a known persona', () => {
    expect(getPersona('noah_schmidt')?.avatar).toBe('/blog/authors/noah-schmidt.jpg');
    expect(getPersona('ines_l')?.name).toBe('Inès L.');
  });

  it('returns undefined for an unknown key', () => {
    expect(getPersona('unknown')).toBeUndefined();
  });
});
