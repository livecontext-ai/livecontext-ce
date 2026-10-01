import { describe, expect, it } from 'vitest';
import { normalizeFieldOptions, normalizeFormField } from '../formFieldShape';

/**
 * The tolerant reader of a stored form field. Agents wrote a default as `default` or
 * `default_value` before 2026-09-29; plan versions, run plans and marketplace snapshots keep that
 * spelling, and the editor and the public form must still show the default.
 */
describe('normalizeFormField - default spelling', () => {
  it.each([
    ['`default` becomes defaultValue', { name: 'a', default: 'A' }, 'A'],
    ['`default_value` becomes defaultValue', { name: 'a', default_value: 'D' }, 'D'],
    ['a non-blank defaultValue wins over an alias', { name: 'a', defaultValue: 'keep', default: 'drop' }, 'keep'],
    ['a blank defaultValue gives way to the alias', { name: 'a', defaultValue: ' \t', default: 'C' }, 'C'],
    ['a non-string default keeps its type', { name: 'n', default: 2000 }, 2000],
    ['a false default is a value, not a blank', { name: 'b', default: false }, false],
  ])('%s', (_name, field, expected) => {
    const out = normalizeFormField(field, 0);
    expect(out.defaultValue).toEqual(expected);
    expect(out).not.toHaveProperty('default');
    expect(out).not.toHaveProperty('default_value');
  });

  it('a blank alias is dropped without inventing a default', () => {
    const out = normalizeFormField({ name: 'a', default: '  ' }, 0);
    expect(out).not.toHaveProperty('defaultValue');
    expect(out).not.toHaveProperty('default');
  });

  it('never mutates the stored field, and fills a missing id', () => {
    const stored = { name: 'a', default: 'A' };
    const out = normalizeFormField(stored, 3);
    expect(stored).toEqual({ name: 'a', default: 'A' });
    expect(out.id).toBe('field-3');
  });

  it('options of a select become [{id, label, value}]', () => {
    const out = normalizeFormField({ name: 't', type: 'select', options: ['free', { label: 'Pro', value: 'pro' }] }, 0);
    expect(out.options).toEqual([
      { id: 'opt-0', label: 'free', value: 'free' },
      { id: 'opt-1', label: 'Pro', value: 'pro' },
    ]);
  });

  it('non-object input is returned as is', () => {
    expect(normalizeFormField(null, 0)).toBeNull();
    expect(normalizeFormField('x', 0)).toBe('x');
    expect(normalizeFieldOptions('x')).toEqual([]);
  });
});
