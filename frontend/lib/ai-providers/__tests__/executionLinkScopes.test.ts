import { describe, expect, it } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import {
  EXECUTION_LINK_SCOPES,
  EXECUTION_LINK_SCOPE_LABEL_KEY,
} from '../executionLinkScopes';

/**
 * This list is what BOTH admin surfaces (the Execution links tab and the per-model
 * badge in the Models panel) offer, and it mirrors the backend
 * ModelExecutionLinkScope enum. Two drifts are invisible at run time and cost an admin
 * a routing they think they configured: a surface the backend accepts but no screen
 * offers, and a label key with no message behind it (next-intl renders the raw key, so
 * a surface silently reads "aiProviders.executionLinks.scopeWorkflow").
 *
 * The backend values are restated here rather than parsed out of the Java source: a
 * test that reads across into ../backend breaks on a harmless reorder, and the repo's
 * mechanism for a real cross-layer contract is shared/contracts + codegen. A scope
 * added backend-side simply is not offered until it is added here, which is a missing
 * feature rather than a broken one.
 */
const BACKEND_SCOPES = [
  'ALL', 'CHAT', 'WORKFLOW', 'WEBHOOK', 'WIDGET', 'SCHEDULE', 'TASK', 'TASK_REVIEW',
];

describe('EXECUTION_LINK_SCOPES', () => {
  it('offers exactly the backend ModelExecutionLinkScope values, ALL first', () => {
    expect(EXECUTION_LINK_SCOPES.map((s) => s.value)).toEqual(BACKEND_SCOPES);
    // ALL leads because it is the wildcard every other surface falls back to; the
    // popover renders it as the row that governs the rest.
    expect(EXECUTION_LINK_SCOPES[0].value).toBe('ALL');
  });

  it('names a real message for every surface, in every locale', () => {
    // English alone would pass while a French admin reads
    // "aiProviders.executionLinks.scopeWorkflow" in the picker: next-intl falls back to
    // en.json for a MISSING key, but a key present-and-empty renders as nothing.
    for (const locale of ['en', 'fr', 'de', 'es', 'pt', 'zh']) {
      const messages = JSON.parse(
        fs.readFileSync(path.join(process.cwd(), 'messages', `${locale}.json`), 'utf8'),
      );
      const namespace = messages.aiProviders.executionLinks;
      for (const scope of EXECUTION_LINK_SCOPES) {
        expect(
          namespace[scope.labelKey],
          `${locale}.json is missing a message for ${scope.value}`,
        ).toBeTruthy();
      }
    }
  });

  it('carries a message for every key the per-model badge asks for, in every locale', () => {
    // The component suites mock next-intl to echo the key back, so a key that exists
    // nowhere renders as "aiProviders.executionLinks.routedViaTitle" in production while
    // every test stays green. This is the only place that would notice.
    const KEYS_USED_BY_THE_BADGE = [
      'linkToCliHint', 'cliNotAvailable', 'allScopeCliCaveat',
      'routedVia', 'routedViaDisabled', 'routedViaTitle',
      'coveredByAll', 'perSurfaceHint', 'appliesTo', 'disabled',
      'removeRouting', 'writeError', 'loadError', 'accessPolicyCaveat',
    ];
    for (const locale of ['en', 'fr', 'de', 'es', 'pt', 'zh']) {
      const messages = JSON.parse(
        fs.readFileSync(path.join(process.cwd(), 'messages', `${locale}.json`), 'utf8'),
      );
      const namespace = messages.aiProviders.executionLinks;
      for (const key of KEYS_USED_BY_THE_BADGE) {
        expect(namespace[key], `${locale}.json is missing aiProviders.executionLinks.${key}`)
          .toBeTruthy();
      }
    }
  });

  it('indexes every surface by value, so an unknown scope is the only fallback case', () => {
    expect(Object.keys(EXECUTION_LINK_SCOPE_LABEL_KEY).sort()).toEqual([...BACKEND_SCOPES].sort());
    expect(EXECUTION_LINK_SCOPE_LABEL_KEY.ALL).toBe('scopeAll');
    expect(EXECUTION_LINK_SCOPE_LABEL_KEY.TASK_REVIEW).toBe('scopeTaskReview');
  });
});
