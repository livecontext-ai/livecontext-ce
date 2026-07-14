/**
 * Bounded pretty-printer for tool-result JSON shown in the chat.
 *
 * Tool results can carry very large payloads (full system prompts, verbatim
 * toolsConfig maps, resolved resource lists...). Rendering them raw under a
 * tool message reads as a wall of JSON. This helper keeps the OUTPUT bounded
 * while staying valid-looking JSON: long strings are ellipsized, long arrays
 * keep their head plus a "+N more" marker, and depth is capped. When the
 * first pass is still too large, a tighter second pass runs, so the display
 * never explodes whatever the input shape.
 *
 * Display-only: the underlying result (what the LLM and copy/inspect flows
 * see) is never modified.
 */

interface CompactLimits {
  maxTotalChars: number;
  maxStringChars: number;
  maxArrayItems: number;
  maxObjectKeys: number;
  maxDepth: number;
}

const FIRST_PASS: CompactLimits = {
  maxTotalChars: 1500,
  maxStringChars: 200,
  maxArrayItems: 20,
  maxObjectKeys: 40,
  maxDepth: 6,
};

const SECOND_PASS: CompactLimits = {
  maxTotalChars: 1500,
  maxStringChars: 80,
  maxArrayItems: 5,
  maxObjectKeys: 15,
  maxDepth: 3,
};

const ELLIPSIS = '…';

/**
 * Absolute output ceiling. Pathological shapes (wide AND deep) can survive the
 * second pass above this size; the display contract is "never a wall of JSON",
 * so past this point we cut the string itself (the trailing marker makes the
 * cut explicit; the full payload stays available on the underlying result).
 */
const HARD_CAP_CHARS = 6000;

function compactValue(value: unknown, depth: number, limits: CompactLimits): unknown {
  if (typeof value === 'string') {
    return value.length > limits.maxStringChars
      ? value.slice(0, limits.maxStringChars) + ELLIPSIS
      : value;
  }
  if (value === null || typeof value !== 'object') {
    return value;
  }
  if (depth >= limits.maxDepth) {
    return Array.isArray(value) ? `[${ELLIPSIS} ${value.length} items]` : `{${ELLIPSIS}}`;
  }
  if (Array.isArray(value)) {
    const head = value.slice(0, limits.maxArrayItems).map((v) => compactValue(v, depth + 1, limits));
    if (value.length > limits.maxArrayItems) {
      head.push(`${ELLIPSIS} +${value.length - limits.maxArrayItems} more items`);
    }
    return head;
  }
  const entries = Object.entries(value as Record<string, unknown>);
  const out: Record<string, unknown> = {};
  for (const [k, v] of entries.slice(0, limits.maxObjectKeys)) {
    out[k] = compactValue(v, depth + 1, limits);
  }
  if (entries.length > limits.maxObjectKeys) {
    out[ELLIPSIS] = `+${entries.length - limits.maxObjectKeys} more keys`;
  }
  return out;
}

/**
 * Pretty-print `value` as indented JSON, bounded for chat display.
 * Small payloads are returned untouched (byte-identical to the raw pretty
 * print), so the compactor is a no-op for the common case.
 */
export function compactJsonForDisplay(value: unknown): string {
  let raw: string | undefined;
  try {
    raw = JSON.stringify(value, null, 2);
  } catch {
    // Circular structure: the depth-capped compactor below still terminates,
    // so fall through with raw treated as oversized.
    raw = undefined;
  }
  if (raw !== undefined && raw.length <= FIRST_PASS.maxTotalChars) return raw;

  try {
    if (raw !== undefined) {
      const first = JSON.stringify(compactValue(value, 0, FIRST_PASS), null, 2);
      if (first.length <= FIRST_PASS.maxTotalChars * 2) return first;
    }
    const second = JSON.stringify(compactValue(value, 0, SECOND_PASS), null, 2);
    if (second === undefined) return String(value);
    if (second.length <= HARD_CAP_CHARS) return second;
    return second.slice(0, HARD_CAP_CHARS) + `\n${ELLIPSIS} (output truncated)`;
  } catch {
    return String(value);
  }
}
