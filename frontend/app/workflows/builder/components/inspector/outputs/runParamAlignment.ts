/**
 * Lines up what a node was CONFIGURED with against what the run actually
 * reported for it.
 *
 * Every backend node echoes its post-resolution configuration under
 * `resolved_params`, and that map is what the Params column shows in run mode.
 * Nothing checked that its keys are the keys the builder form writes, so a node
 * whose backend renamed a parameter (or stopped echoing it) silently showed the
 * user FEWER parameters than they configured - no error, no gap, just a field
 * that quietly stopped existing.
 *
 * This module produces that comparison as data for the alignment e2e spec, which
 * fails when a node stops reporting something its plan configures. It is NOT
 * rendered to the user: the inspector used to warn about a mismatch, but that is a
 * statement about the product rather than about their workflow, and it also fired
 * on runs recorded before a rename, whose rows legitimately carry the old keys.
 */

import { CONFIGURED_SECRETS } from './configuredSecrets';

/** How a configured parameter fared in the run. */
export type ParamAlignmentStatus =
  /** Reported under the same key the form writes. */
  | 'resolved'
  /** Reported, but under a differently-formatted key (camelCase vs snake_case). */
  | 'renamed'
  /** Configured on the node, absent from what the run reported. */
  | 'not_reported';

export interface ParamAlignmentEntry {
  /** Key as the builder form writes it. */
  key: string;
  status: ParamAlignmentStatus;
  /** The expression the author typed into the form. */
  configuredExpression: string;
  /** Key the run reported it under - set when status is 'renamed'. */
  runtimeKey?: string;
}

export interface ParamAlignment {
  /** Configured parameters the run did NOT report under the same key. */
  mismatches: ParamAlignmentEntry[];
  /** Every configured key, whatever its status - the denominator of the check. */
  configuredKeys: string[];
}

/**
 * Fold a key to the shape a rename would preserve: case and separators are what
 * differ between `timeoutSeconds` and `timeout_seconds`, and telling those two
 * apart from a genuinely absent parameter is the whole point of the comparison.
 */
export function normalizeParamKey(key: string): string {
  return key.replace(/[_\-\s]/g, '').toLowerCase();
}

/**
 * The legacy `resolvedX` companion-key convention: strictly camelCase, so
 * `resolvedMessage` is one and `resolved_at` is not.
 *
 * Matching on `startsWith('resolved')` alone renamed any user key that happens
 * to begin with the word: an assignment called `resolved_at` was shown as `_at`,
 * and `resolvedIssues` as `issues` - the exact "my parameter is not where I put
 * it" failure this module exists to remove.
 */
const RESOLVED_ALIAS_PATTERN = /^resolved[A-Z]/;

/**
 * Merge the legacy `resolvedX` companion keys into their base key.
 *
 * Older node outputs shipped BOTH the template and its resolved value
 * (`{ message: "{{tpl}}", resolvedMessage: "hi" }`). Only the resolved value is
 * worth showing, under the name the form uses. A `resolvedX` with no matching
 * `x` is NOT a companion - it is a key in its own right, and is left alone.
 */
export function mergeResolvedAliases(data: Record<string, unknown>): Record<string, unknown> {
  const merged: Record<string, unknown> = {};
  const consumed = new Set<string>();

  for (const key of Object.keys(data)) {
    if (!RESOLVED_ALIAS_PATTERN.test(key)) continue;
    const baseKey = key.charAt(8).toLowerCase() + key.slice(9);
    // Only a PAIR is the companion convention. Without the base key present,
    // renaming would invent a field the run never reported.
    if (!Object.prototype.hasOwnProperty.call(data, baseKey)) continue;
    merged[baseKey] = data[key];
    consumed.add(key);
    consumed.add(baseKey);
  }
  for (const [key, value] of Object.entries(data)) {
    if (!consumed.has(key)) merged[key] = value;
  }
  return merged;
}

/**
 * Keys on a plan entry that describe the node itself rather than its
 * configuration, so they are never expected back in `resolved_params`.
 */
const PLAN_METADATA_KEYS: ReadonlySet<string> = new Set([
  'id',
  'graphNodeId',
  'type',
  'label',
  'position',
  'params',
  'nodePolicy',
  'mock',
  'credentialId',
  'description',
  'backEdge',
]);

/**
 * The configuration keys a plan entry hands to its node, flattened the way the
 * node reports them back.
 *
 * A saved plan carries a node's configuration in two shapes: a flat `params`
 * map, and typed blocks named after the node's own vocabulary (`filter: {
 * conditions, mode }`, `limit: { count, from, offset }`, …). The backend node
 * echoes BOTH, flat, under `resolved_params` - `{ input, conditions, mode }` for
 * a filter. This performs the same flattening, so a plan and a run's reported
 * parameters can be compared key for key.
 *
 * Used by the alignment e2e: it is what makes "the fields declared in edit mode
 * are the fields the run reports" a checkable statement rather than a hope.
 */
export function flattenPlannedParams(planEntry: Record<string, unknown>): Record<string, unknown> {
  const flattened: Record<string, unknown> = {};

  for (const [key, value] of Object.entries(planEntry)) {
    if (PLAN_METADATA_KEYS.has(key)) continue;
    if (value === null || value === undefined) continue;
    if (typeof value === 'object' && !Array.isArray(value)) {
      // A typed block: its OWN keys are what the node reports.
      for (const [innerKey, innerValue] of Object.entries(value as Record<string, unknown>)) {
        if (innerValue === undefined) continue;
        flattened[innerKey] = innerValue;
      }
      continue;
    }
    flattened[key] = value;
  }

  // The flat map wins: it is the closest thing to what the node was handed.
  for (const [key, value] of Object.entries((planEntry.params as Record<string, unknown>) ?? {})) {
    if (value === undefined) continue;
    flattened[key] = value;
  }

  return flattened;
}

/**
 * Configured keys a node deliberately does not report, keyed `<type>.<key>`,
 * each with the reason.
 *
 * Read by the alignment e2e. Every entry is a decision - a parameter the product
 * reports some other way, or one it must never report at all - and an unlisted
 * mismatch is a real one.
 */
export const NOT_ECHOED_BY_DESIGN: ReadonlySet<string> = new Set<string>([
  // A set node reports each assignment under ITS OWN name, with the resolved
  // value, which is what the reader wants. Echoing the structural list too
  // would show every assignment twice.
  'set.assignments',
  // Same for a transform node's mappings and a data-input node's items.
  'transform.mappings',
  'data_input.items',
  // A decision reports each BRANCH under its own name with the condition's
  // resolved value (`if`, `elsif_2`, `else`). That is strictly more than the
  // structural condition list, and it is what a reader debugging a branch needs.
  'decision.decisionConditions',
  // Same shape for a switch and a fork: each case is reported under its own
  // label with the value it matches on, each branch under its own label with its
  // target. They used to report a COUNT under the plan's list key, which told the
  // reader how many existed and nothing about which.
  'switch.switchCases',
  'fork.forkOutputs',
  // A code node reports `codeLength`, not the source: it can be megabytes, is
  // unchanged from what the Edit view already shows, and would be copied into
  // every step row of every item.
  'code.code',
  // An option node reports each CHOICE under its own author-given label with the
  // resolved expression, plus `choices` for the count. Echoing the structural
  // list too would show every choice twice, exactly as for `set.assignments`.
  'option.optionChoices',
  // An sftp upload reports `localContentSize` instead: the payload is a whole
  // file, and copying it into the Params column of every step row would make the
  // panel unusable for the one node whose content is guaranteed to be large.
  'sftp.localContent',
  // SECRETS, from the one list both halves of the panel read: see CONFIGURED_SECRETS.
  ...CONFIGURED_SECRETS,
]);

/**
 * Compare configured parameters with the keys the run reported.
 *
 * Only mismatches come back: a parameter reported under its own name is the
 * normal case and needs no annotation in the UI. A parameter the node
 * deliberately does not echo is not a mismatch either, so pass `nodeType` to
 * have those recognised (omit it and every exemption is reported).
 */
export function buildParamAlignment(
  configured: Record<string, unknown>,
  reported: Record<string, unknown>,
  nodeType?: string,
): ParamAlignment {
  const reportedKeys = Object.keys(reported);
  const byNormalized = new Map<string, string>();
  for (const key of reportedKeys) {
    // First writer wins: an exact match is checked before this map is consulted,
    // so the only use is finding A candidate for a differently-formatted key.
    const normalized = normalizeParamKey(key);
    if (!byNormalized.has(normalized)) byNormalized.set(normalized, key);
  }

  const mismatches: ParamAlignmentEntry[] = [];
  const configuredKeys = Object.keys(configured);

  for (const key of configuredKeys) {
    if (Object.prototype.hasOwnProperty.call(reported, key)) continue;
    if (nodeType && NOT_ECHOED_BY_DESIGN.has(`${nodeType}.${key}`)) continue;
    const runtimeKey = byNormalized.get(normalizeParamKey(key));
    const configuredExpression = describeConfiguredValue(configured[key]);
    mismatches.push(
      runtimeKey
        ? { key, status: 'renamed', configuredExpression, runtimeKey }
        : { key, status: 'not_reported', configuredExpression },
    );
  }

  return { mismatches, configuredKeys };
}

/** One-line rendering of a configured value, for the e2e's failure report. */
function describeConfiguredValue(value: unknown): string {
  if (typeof value === 'string') return value;
  if (value === null || value === undefined) return '';
  try {
    return JSON.stringify(value) ?? String(value);
  } catch {
    return String(value);
  }
}
