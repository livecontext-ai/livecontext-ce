/**
 * The canonical shape of a form trigger field, as the builder inspector (through the plan
 * importer) and the public form page (/f/{token}, /s/{token}) read it.
 * Mirrors the backend FormFieldCanonicalizer / FormFieldDefaults, which write this shape on every
 * authoring path; this side is the tolerant READER for what was stored before (plan versions,
 * run plans, marketplace snapshots, plans written by a raw import).
 */

export interface FormFieldOption {
  id: string;
  label: string;
  value: string;
}

const OPTION_BEARING_TYPES = ['select', 'multiselect', 'radio', 'checkboxGroup'];
const DEFAULT_VALUE_ALIASES = ['default', 'default_value'] as const;

function isBlank(value: unknown): boolean {
  return value === undefined || value === null || (typeof value === 'string' && value.trim() === '');
}

/**
 * Coerce a select/multiselect/radio/checkboxGroup `options` array into the canonical
 * [{id, label, value}] shape.
 *
 * Hardening for plans persisted before the V161 doc fix landed: an LLM could have written
 * `options: ["a", "b"]` (the natural shape when no schema is specified). Without this coercion
 * the inspector renders empty inputs and the runtime preview emits
 * `<SelectItem value={undefined}>{undefined}</…>`.
 *
 * Accepts: string shorthand, {label, value} objects (with optional id). Drops malformed entries
 * silently - the inspector will still show the remaining good ones rather than blanking the
 * whole field.
 */
export function normalizeFieldOptions(raw: unknown): FormFieldOption[] {
  if (!Array.isArray(raw)) return [];
  const out: FormFieldOption[] = [];
  for (let i = 0; i < raw.length; i++) {
    const item = raw[i];
    if (typeof item === 'string' && item.length > 0) {
      out.push({ id: `opt-${i}`, label: item, value: item });
      continue;
    }
    if (item && typeof item === 'object') {
      const o = item as { id?: unknown; label?: unknown; value?: unknown };
      const label = typeof o.label === 'string' ? o.label : '';
      const value = typeof o.value === 'string' ? o.value : '';
      if (label.length === 0 || value.length === 0) continue;
      const id = typeof o.id === 'string' && o.id.length > 0 ? o.id : `opt-${i}`;
      out.push({ id, label, value });
    }
  }
  return out;
}

/**
 * Coerce a single form-field map into the canonical shape: a stable `id`, normalized options
 * for option-bearing types, and the default under `defaultValue`. A non-blank `defaultValue`
 * wins; otherwise the first non-blank of `default`, `default_value` becomes it (the spellings
 * agents wrote before 2026-09-29). The aliases are dropped either way.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function normalizeFormField(field: any, index: number): any {
  if (!field || typeof field !== 'object') return field;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const normalized: any = { ...field };
  if (typeof normalized.id !== 'string' || normalized.id.length === 0) {
    normalized.id = `field-${index}`;
  }
  const fieldType = typeof normalized.type === 'string' ? normalized.type : '';
  if (OPTION_BEARING_TYPES.includes(fieldType)) {
    normalized.options = normalizeFieldOptions(normalized.options);
  }
  if (isBlank(normalized.defaultValue)) {
    const alias = DEFAULT_VALUE_ALIASES.map((key) => normalized[key]).find((value) => !isBlank(value));
    if (alias !== undefined) {
      normalized.defaultValue = alias;
    }
  }
  for (const key of DEFAULT_VALUE_ALIASES) {
    delete normalized[key];
  }
  return normalized;
}
