import { describe, it, expect } from 'vitest';
import { normalizeFieldOptions, normalizeFormField } from '../TriggerNodeCreator';

/**
 * Regression tests for the form-trigger options coercion in
 * {@link normalizeFieldOptions} / {@link normalizeFormField}. The bug that
 * opened this work: an LLM agent submitted a form trigger with
 * {@code options: ["gpt-image-1.5", "gpt-image-2"]} on a `select` field.
 * The inspector + TriggerPanel preview keyed on `option.id / .label /
 * .value`, so they rendered empty inputs / undefined SelectItems.
 *
 * The importer now normalizes both the string-shorthand and the canonical
 * object form to {id, label, value}. Backend coercion in
 * TriggerCreator.coerceFieldOptions runs first on the
 * authoring path, but the importer is the single point of truth when a
 * persisted plan is reloaded - defensive against legacy plans created
 * before the V161 backend fix landed.
 */
describe('normalizeFieldOptions', () => {
  it('coerces a string-array shorthand to {id, label, value} objects', () => {
    const out = normalizeFieldOptions(['gpt-image-1.5', 'gpt-image-2', 'gpt-image-1-mini']);
    expect(out).toEqual([
      { id: 'opt-0', label: 'gpt-image-1.5', value: 'gpt-image-1.5' },
      { id: 'opt-1', label: 'gpt-image-2', value: 'gpt-image-2' },
      { id: 'opt-2', label: 'gpt-image-1-mini', value: 'gpt-image-1-mini' },
    ]);
  });

  it('preserves canonical {label, value} objects and fills missing id with opt-N', () => {
    const out = normalizeFieldOptions([
      { label: 'GPT 2', value: 'gpt-2' },
      { id: 'user-keep', label: 'GPT 1.5', value: 'gpt-1.5' },
    ]);
    expect(out).toEqual([
      { id: 'opt-0', label: 'GPT 2', value: 'gpt-2' },
      { id: 'user-keep', label: 'GPT 1.5', value: 'gpt-1.5' },
    ]);
  });

  it('drops malformed entries instead of producing undefined fields', () => {
    const out = normalizeFieldOptions([
      'good',                          // string shorthand, kept
      '',                              // empty, dropped
      { label: 'Has value', value: 'hv' }, // kept
      { label: 'Missing value' },      // dropped (no value)
      { value: 'no-label' },           // dropped (no label)
      42,                              // wrong type, dropped
      null,                            // null, dropped
    ]);
    expect(out).toEqual([
      { id: 'opt-0', label: 'good', value: 'good' },
      { id: 'opt-2', label: 'Has value', value: 'hv' },
    ]);
  });

  it('returns empty array on non-array input (null, undefined, object)', () => {
    expect(normalizeFieldOptions(null)).toEqual([]);
    expect(normalizeFieldOptions(undefined)).toEqual([]);
    expect(normalizeFieldOptions({} as unknown)).toEqual([]);
    expect(normalizeFieldOptions('not-an-array' as unknown)).toEqual([]);
  });
});

describe('normalizeFormField', () => {
  it('auto-fills missing field.id with field-N for inspector React keys', () => {
    const out = normalizeFormField({ name: 'email', type: 'email', label: 'Email' }, 3);
    expect(out.id).toBe('field-3');
  });

  it('preserves existing field.id', () => {
    const out = normalizeFormField(
      { id: 'user-set', name: 'email', type: 'email' }, 0);
    expect(out.id).toBe('user-set');
  });

  it('coerces options for select fields', () => {
    const out = normalizeFormField(
      { name: 'model', type: 'select', label: 'Model', options: ['a', 'b'] }, 0);
    expect(out.options).toEqual([
      { id: 'opt-0', label: 'a', value: 'a' },
      { id: 'opt-1', label: 'b', value: 'b' },
    ]);
  });

  it('does NOT touch options on non-option-bearing field types', () => {
    const out = normalizeFormField(
      { name: 'comment', type: 'textarea', label: 'Comment', options: ['leftover'] }, 0);
    expect(out.options).toEqual(['leftover']);
  });

  it('applies the same coercion across multiselect / radio / checkboxGroup', () => {
    for (const fieldType of ['multiselect', 'radio', 'checkboxGroup']) {
      const out = normalizeFormField(
        { name: 'pick', type: fieldType, label: 'Pick', options: ['x', 'y'] }, 0);
      expect(out.options, `for type=${fieldType}`).toEqual([
        { id: 'opt-0', label: 'x', value: 'x' },
        { id: 'opt-1', label: 'y', value: 'y' },
      ]);
    }
  });

  it('passes non-object inputs through unchanged', () => {
    expect(normalizeFormField(null, 0)).toBe(null);
    expect(normalizeFormField('str' as unknown, 0)).toBe('str');
  });
});
