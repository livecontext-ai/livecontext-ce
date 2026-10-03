import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import en from '@/messages/en.json';

/**
 * The /security page (the Policy target of security.txt) reads its copy from the
 * `securityDisclosure` namespace. A key typo there renders the raw key path on a public page,
 * and locale parity cannot see it (the key would be missing in all six files alike).
 */
describe('/security page message keys', () => {
  const source = readFileSync(join(__dirname, '..', 'page.tsx'), 'utf8');
  const ns = (en as Record<string, unknown>).securityDisclosure as Record<string, unknown>;

  const lookup = (path: string): unknown =>
    path.split('.').reduce<unknown>((node, part) => (node as Record<string, unknown> | undefined)?.[part], ns);

  it('every t(...) / t.rich(...) key used by the page exists in en.json', () => {
    // generateMetadata uses its own 'securityDisclosure.metadata' namespace (covered below).
    const body = source.slice(source.indexOf('export default'));
    const keys = [...body.matchAll(/\bt(?:\.rich)?\('([A-Za-z.]+)'/g)].map((m) => m[1]);
    expect(keys.length).toBeGreaterThan(10);
    for (const key of keys) expect(typeof lookup(key), key).toBe('string');
  });

  it('every rule listed by the page exists in en.json', () => {
    const list = /RULE_KEYS = \[([^\]]+)\]/.exec(source)?.[1] ?? '';
    const rules = [...list.matchAll(/'([A-Za-z]+)'/g)].map((m) => m[1]);
    expect(rules.length).toBe(5);
    for (const rule of rules) expect(typeof lookup(`rules.${rule}`), rule).toBe('string');
  });

  it('metadata keys exist', () => {
    expect(typeof lookup('metadata.title')).toBe('string');
    expect(typeof lookup('metadata.description')).toBe('string');
  });
});
