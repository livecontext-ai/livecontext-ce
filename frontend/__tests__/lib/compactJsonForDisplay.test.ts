import { describe, expect, it } from 'vitest';
import { compactJsonForDisplay } from '@/lib/utils/compactJsonForDisplay';

/**
 * Bounded pretty-printer for tool-result JSON in the chat. Regression: tool
 * results carrying verbatim configs / long prompts used to be dumped raw under
 * the message (unbounded wall of JSON in GroupedToolCard + ActivityFeed).
 */
describe('compactJsonForDisplay', () => {
  it('is a byte-identical no-op for small payloads', () => {
    const value = { status: 'CREATED', id: 'abc', count: 3 };
    expect(compactJsonForDisplay(value)).toBe(JSON.stringify(value, null, 2));
  });

  it('ellipsizes long string values instead of dumping them whole', () => {
    const value = { system_prompt: 'x'.repeat(5000), name: 'Agent' };
    const out = compactJsonForDisplay(value);
    expect(out.length).toBeLessThan(1000);
    expect(out).toContain('…');
    expect(out).toContain('"name": "Agent"');
  });

  it('caps long arrays with a "+N more items" marker', () => {
    const value = { ids: Array.from({ length: 300 }, (_, i) => `id-${i}-${'p'.repeat(20)}`) };
    const out = compactJsonForDisplay(value);
    expect(out).toContain('id-0');
    expect(out).toContain('+280 more items');
    expect(out).not.toContain('id-250');
  });

  it('caps objects with many keys with a "+N more keys" marker', () => {
    const value: Record<string, string> = {};
    for (let i = 0; i < 100; i++) value[`key_${i}`] = 'v'.repeat(30);
    const out = compactJsonForDisplay(value);
    expect(out).toContain('key_0');
    expect(out).toContain('more keys');
    expect(out).not.toContain('key_90');
  });

  it('tightens further when the first pass is still too large (deep wide payloads stay bounded)', () => {
    const wide = () => Object.fromEntries(Array.from({ length: 39 }, (_, i) => [`k${i}`, 'v'.repeat(190)]));
    const value = { a: wide(), b: wide(), c: wide(), d: wide() };
    const out = compactJsonForDisplay(value);
    expect(out.length).toBeLessThan(8000);
  });

  it('caps depth with explicit markers and stays valid JSON', () => {
    type Deep = { padding: string; child?: Deep; list?: number[] };
    let value: Deep = { padding: 'p'.repeat(100), list: [1, 2, 3] };
    for (let i = 0; i < 10; i++) value = { padding: 'p'.repeat(100), child: value };
    const out = compactJsonForDisplay(value);
    expect(out).toContain('{…}');
    expect(() => JSON.parse(out)).not.toThrow();
  });

  it('compacted (non hard-capped) output stays parseable JSON', () => {
    const value = { system_prompt: 'x'.repeat(5000), ids: Array.from({ length: 100 }, (_, i) => `id-${i}`) };
    const out = compactJsonForDisplay(value);
    expect(() => JSON.parse(out)).not.toThrow();
  });

  it('enforces the absolute hard cap on pathological wide-and-deep payloads', () => {
    const deepWide = (d: number): unknown =>
      d === 0
        ? 'v'.repeat(60)
        : Object.fromEntries(Array.from({ length: 15 }, (_, i) => [`k${i}`, deepWide(d - 1)]));
    const out = compactJsonForDisplay(deepWide(4));
    expect(out.length).toBeLessThanOrEqual(6000 + 40);
    expect(out).toContain('(output truncated)');
  });

  it('handles circular structures without throwing (depth-capped compaction)', () => {
    const value: Record<string, unknown> = { name: 'loop', padding: 'p'.repeat(2000) };
    value.self = value;
    const out = compactJsonForDisplay(value);
    expect(out).toContain('loop');
    expect(out.length).toBeLessThan(10000);
  });

  it('handles non-serializable roots gracefully', () => {
    expect(compactJsonForDisplay(undefined)).toBe('undefined');
  });
});
