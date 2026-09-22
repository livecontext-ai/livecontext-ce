/**
 * Pure helpers shared by every run-mode data view (Params column, Output column).
 *
 * They answer five questions the inspector asks about a value it got back from a
 * run, and nothing else - no React, no fetching - so each one is directly testable:
 *
 *  1. Is this string actually an unresolved template the engine failed to fill?
 *  2. Is this string actually JSON that deserves a tree instead of one long line?
 *  3. Can this value be laid out as a table (an array of row-shaped objects)?
 *  4. What exactly goes on the clipboard when the user copies it?
 *  5. How many rows does opening it render, which is what its {n} marker must say?
 */

/**
 * Longest string we will try to parse as embedded JSON. A run value can be a
 * multi-megabyte HTTP body; parsing it on every render to find out it is not
 * JSON is the kind of cost that only shows up on the biggest, slowest run.
 */
export const MAX_EMBEDDED_JSON_CHARS = 200_000;

/** Above this many characters a string is clamped behind a "show more" toggle. */
export const LONG_STRING_CHARS = 400;

/** Hard cap on table columns so a wide row cannot produce an unusable grid. */
export const MAX_TABLE_COLUMNS = 30;

/**
 * Why a value is displayed as unresolved.
 *
 * ONLY unambiguous engine artefacts are listed. A bare `${...}` or `{{...}}` is
 * deliberately NOT one: a code node's JS template literal, a shell command, an
 * HTTP body and an interface template all legitimately contain them, and several
 * nodes echo their configured expression verbatim on purpose (FilterNode reports
 * `input` as the expression it was given). Flagging those would be the same
 * false positive that made the persistence layer DELETE them, which is the bug
 * this work removed - reproducing it as a badge would only move the lie.
 */
export type UnresolvedKind =
  /** `INVALID_TEMPLATE:...` - the engine could not parse the expression. */
  | 'invalid_template'
  /** `{{__UNRESOLVED__:path}}` - the engine's own "variable not found" marker. */
  | 'unresolved_variable'
  /** JavaScript's `[object Object]` - an object concatenated into a string. */
  | 'stringified_object';

const INVALID_TEMPLATE_PREFIX = 'INVALID_TEMPLATE:';
const STRINGIFIED_OBJECT = '[object Object]';
/**
 * Emitted by TemplateEngine.resolveTemplatesSimple when a variable is missing.
 * Both delimiters are matched: the engine writes the `{{` form, while
 * V2TemplateAdapter.containsUnresolved guards against the `${` one, and the
 * marker name is unmistakable either way.
 */
const UNRESOLVED_MARKERS = ['{{__UNRESOLVED__:', '${__UNRESOLVED__:'] as const;

/**
 * Classify a value as unresolved, or null when it looks like real data.
 *
 * Only strings can be unresolved: a number, a boolean or an object came out of
 * the engine as a value, not as text that failed to be substituted.
 */
export function detectUnresolvedValue(value: unknown): UnresolvedKind | null {
  if (typeof value !== 'string') return null;
  // trimStart, not trim: only the prefix check needs it, and this runs on every
  // render for every value - including a multi-megabyte HTTP body.
  if (value.trimStart().startsWith(INVALID_TEMPLATE_PREFIX)) return 'invalid_template';
  if (UNRESOLVED_MARKERS.some((marker) => value.includes(marker))) return 'unresolved_variable';
  // `includes`, not equality: the common shape is CONCATENATION - a set
  // assignment `"Owner: {{core:x.output.obj}}"` yields `"Owner: [object Object]"`,
  // and joining a list of maps yields `"[object Object],[object Object]"`. Both
  // are genuinely broken values, and an equality check renders them as ordinary
  // text. The cost is asymmetric: the only false positive is prose that quotes
  // the literal (an error message explaining the artefact), where the badge is
  // merely redundant - it IS about a stringified object. Missing a real one
  // leaves the reader hunting a value the panel told them was fine.
  if (value.includes(STRINGIFIED_OBJECT)) return 'stringified_object';
  return null;
}

/**
 * Parse a string that carries JSON, so the inspector can show a tree instead of
 * one unreadable quoted line.
 *
 * Deliberately strict: only an object or an array counts. A bare `"42"` or
 * `"true"` is valid JSON but rendering it as a "JSON document" would relabel
 * ordinary text the user wrote. Returns undefined for everything else.
 */
export function parseEmbeddedJson(value: unknown): unknown | undefined {
  if (typeof value !== 'string') return undefined;
  const trimmed = value.trim();
  if (trimmed.length < 2 || trimmed.length > MAX_EMBEDDED_JSON_CHARS) return undefined;
  const first = trimmed[0];
  const last = trimmed[trimmed.length - 1];
  const looksLikeJson = (first === '{' && last === '}') || (first === '[' && last === ']');
  if (!looksLikeJson) return undefined;
  // An unresolved template is not JSON to expand, even when it is brace-shaped.
  if (detectUnresolvedValue(trimmed)) return undefined;
  try {
    const parsed = JSON.parse(trimmed);
    if (parsed !== null && typeof parsed === 'object') return parsed;
    return undefined;
  } catch {
    return undefined;
  }
}

/** The fields a recognised file reference is rendered as, in order. */
export const FILE_REF_DISPLAY_PROPS = ['path', 'name', 'mimeType', 'size'] as const;

/**
 * How many rows a fully-opened value renders. ONE rule, for every `{n}` marker the
 * TREE draws.
 *
 * Two boundaries, both deliberate. A recognised file reference nested in a JSON
 * STRING opens onto its card, which is itself collapsed, so those four rows are one
 * click further away than every other shape; the marker still counts them, because it
 * describes the value, not the clicks. And `formatCellValue` counts a table cell by
 * its raw keys instead: a cell never expands, so its number summarises the object
 * rather than promising rows, and the two views of one payload can differ there.
 *
 * The marker and the rows were computed separately and disagreed. The marker
 * subtracted `_type` while the rows below it did not, so any payload carrying a
 * discriminator opened one row longer than the count above it announced. A second,
 * quieter disagreement sat underneath: a file reference renders a FIXED four fields
 * whatever else it carries, so an embedded one with an `id` (which every file
 * produced since the opaque-URL cutover has) was counted 5 by the string row and
 * drawn as 4 by the file row nested inside it.
 *
 * Nothing is subtracted from an ordinary object. Hiding `_type` would take away the
 * one thing that says
 * a malformed `{_type:'file', path, name}` was MEANT to be a file, which is exactly
 * what a reader needs when a downstream fileRef parameter refuses it, and it would
 * make a user's own `_type` field invisible in a webhook body or a table row. A
 * RECOGNISED reference is different: its view draws four named fields on purpose, so
 * `id`, `url` and `key` are not rows there and are not counted as ones.
 *
 * @param isFileRefValue whether the caller's `isFileRef` recognised this value, which
 *                       decides whether it is drawn as a file card or as plain rows
 */
export function expandedRowCount(value: unknown, isFileRefValue: boolean): number {
  if (Array.isArray(value)) return value.length;
  if (value === null || typeof value !== 'object') return 0;
  if (isFileRefValue) return FILE_REF_DISPLAY_PROPS.length;
  return Object.keys(value).length;
}

/** Pretty-print any value the way the JSON view and the clipboard show it. */
export function formatJson(value: unknown): string {
  if (value === undefined) return 'undefined';
  try {
    return JSON.stringify(value, null, 2) ?? String(value);
  } catch {
    // Circular structures cannot reach here from a fetched run payload, but a
    // caller passing live component state could - degrade instead of throwing.
    return String(value);
  }
}

/**
 * What the copy button puts on the clipboard.
 *
 * A string is copied as itself (copying `"hello"` with the quotes would be
 * useless in a terminal or an editor); everything else is copied as JSON.
 */
export function toClipboardText(value: unknown): string {
  if (typeof value === 'string') return value;
  if (value === null) return 'null';
  if (value === undefined) return '';
  if (typeof value !== 'object') return String(value);
  return formatJson(value);
}

/** True when a value can be laid out as rows: a non-empty array of plain objects. */
export function isTabularArray(value: unknown): value is Array<Record<string, unknown>> {
  if (!Array.isArray(value) || value.length === 0) return false;
  return value.every(
    (row) => row !== null && typeof row === 'object' && !Array.isArray(row),
  );
}

/**
 * Column order for a table view: keys in the order they first appear across the
 * rows, so the first row's shape leads and later rows only append what they add.
 */
export function collectTableColumns(rows: Array<Record<string, unknown>>): string[] {
  const columns: string[] = [];
  const seen = new Set<string>();
  for (const row of rows) {
    for (const key of Object.keys(row)) {
      if (seen.has(key)) continue;
      seen.add(key);
      columns.push(key);
      if (columns.length >= MAX_TABLE_COLUMNS) return columns;
    }
  }
  return columns;
}

/** Compact one-line rendering of a cell value for the table view. */
export function formatCellValue(value: unknown): string {
  if (value === null) return 'null';
  if (value === undefined) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'object') {
    return Array.isArray(value) ? `[${value.length}]` : `{${Object.keys(value).length}}`;
  }
  return String(value);
}

/**
 * A step payload is almost never an array at the top level: the rows live under
 * one field (`items`, `results`, `conditions`, …). This lists every field that
 * could be laid out as rows.
 *
 * The table used to offer itself only when exactly ONE existed, on the reasoning
 * that picking between two would be an arbitrary choice. The effect was worse
 * than the problem: a payload with `items` AND `errors` lost the table view
 * entirely, for no reason the reader could see. The picking is now the reader's,
 * through a field selector, and the chosen field is always named on screen.
 */
export function tabularFields(data: unknown): string[] {
  if (data === null || typeof data !== 'object' || Array.isArray(data)) return [];
  return Object.entries(data as Record<string, unknown>)
    .filter(([, value]) => isTabularArray(value))
    .map(([key]) => key);
}

/** Whether a table view can be offered for this payload. */
export function hasTableView(data: unknown): boolean {
  return isTabularArray(data) || tabularFields(data).length > 0;
}

/**
 * The rows the table lays out: the payload itself when it is already an array,
 * otherwise the named field (defaulting to the first row-shaped one).
 *
 * Returns undefined when there is nothing tabular, so a caller cannot mistake
 * "no rows" for "the whole payload".
 */
export function pickTabularValue(data: unknown, field?: string): unknown {
  if (isTabularArray(data)) return data;
  const fields = tabularFields(data);
  if (fields.length === 0) return undefined;
  const chosen = field && fields.includes(field) ? field : fields[0];
  return (data as Record<string, unknown>)[chosen];
}
