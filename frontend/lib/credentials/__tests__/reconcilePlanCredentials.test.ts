// @vitest-environment node
/**
 * Tests for reconcilePlanCredentials - re-points/drops a run plan's pinned
 * selectedCredentialId when it references a credential the user has deleted or
 * reconnected, so the backend's strict (no-fallback) resolver never sees a dead
 * id and returns `credentials_required` at execution.
 *
 * Regression context (2026-05-30): a node pinned credential 118 (Search Console),
 * the user reconnected → new credential 151 (118 deleted), but only the node the
 * user re-opened in the inspector was healed. The unopened "Submit Sitemap" node
 * kept 118 and failed at run with credentials_required.
 */
import { describe, it, expect } from 'vitest';
import type { Credential } from '@/lib/api/orchestrator';
import { reconcilePlanCredentials } from '../reconcilePlanCredentials';

function cred(id: number, integration: string, is_default = false): Credential {
  return { id, integration, is_default, name: `${integration}-${id}` } as unknown as Credential;
}

const userCreds = [
  cred(151, 'googlesearchconsole', true), // current, default
  cred(160, 'googlesearchconsole', false), // current, secondary
  cred(200, 'gmail', true),
];

describe('reconcilePlanCredentials', () => {
  it('re-points a dead selectedCredentialId to the integration default (the reported Submit Sitemap bug)', () => {
    const plan = {
      mcps: [
        { id: 'a', iconSlug: 'googlesearchconsole', label: 'List Sites', selectedCredentialId: 151 },
        { id: 'b', iconSlug: 'googlesearchconsole', label: 'Submit Sitemap', selectedCredentialId: 118 }, // deleted
      ],
    };

    const out = reconcilePlanCredentials(plan, userCreds);

    expect(out).not.toBe(plan); // changed → new object
    expect(out.mcps[0].selectedCredentialId).toBe(151); // valid one untouched
    expect(out.mcps[1].selectedCredentialId).toBe(151); // dead 118 → default 151
  });

  it('drops the pin when no current credential exists for the integration', () => {
    const plan = {
      mcps: [{ id: 'b', iconSlug: 'dialogflow', label: 'List Agents', selectedCredentialId: 999 }],
    };

    const out = reconcilePlanCredentials(plan, userCreds);

    expect('selectedCredentialId' in out.mcps[0]).toBe(false); // dropped → backend resolves default
  });

  it('returns the SAME plan reference when every pinned id is still valid (no-op)', () => {
    const plan = {
      mcps: [
        { id: 'a', iconSlug: 'googlesearchconsole', selectedCredentialId: 151 },
        { id: 'g', iconSlug: 'gmail', selectedCredentialId: 200 },
      ],
    };

    expect(reconcilePlanCredentials(plan, userCreds)).toBe(plan);
  });

  it('leaves platform-sourced steps untouched', () => {
    const plan = {
      mcps: [
        { id: 'p', iconSlug: 'googlesearchconsole', credentialSource: 'platform' as const, selectedCredentialId: 118 },
      ],
    };

    expect(reconcilePlanCredentials(plan, userCreds)).toBe(plan);
  });

  it('leaves steps without a pinned credential untouched', () => {
    const plan = { mcps: [{ id: 'n', iconSlug: 'googlesearchconsole', label: 'List Sites' }] };

    expect(reconcilePlanCredentials(plan, userCreds)).toBe(plan);
  });

  it('prefers the is_default credential when re-picking among multiple matches', () => {
    const plan = { mcps: [{ id: 'b', iconSlug: 'googlesearchconsole', selectedCredentialId: 118 }] };

    const out = reconcilePlanCredentials(plan, userCreds);

    expect(out.mcps[0].selectedCredentialId).toBe(151); // 151 is is_default, not 160
  });

  it('is a no-op for plans with no mcps', () => {
    const plan = { mcps: [] };
    expect(reconcilePlanCredentials(plan, userCreds)).toBe(plan);
    const planNoMcps = { triggers: [] } as { triggers: unknown[]; mcps?: never };
    expect(reconcilePlanCredentials(planNoMcps, userCreds)).toBe(planNoMcps);
  });

  it('drops every dead pin when the user is LOADED with zero credentials ([])', () => {
    const plan = {
      mcps: [{ id: 'b', iconSlug: 'googlesearchconsole', selectedCredentialId: 118 }],
    };

    const out = reconcilePlanCredentials(plan, []);

    expect('selectedCredentialId' in out.mcps[0]).toBe(false);
  });

  it('is a no-op when the credentials list has NOT loaded yet (undefined) - never drops valid pins', () => {
    const plan = {
      mcps: [{ id: 'b', iconSlug: 'googlesearchconsole', selectedCredentialId: 151 }],
    };

    // undefined = not loaded. Reconciling here would wrongly treat 151 as dead.
    expect(reconcilePlanCredentials(plan, undefined)).toBe(plan);
    expect(plan.mcps[0].selectedCredentialId).toBe(151);
  });
});
