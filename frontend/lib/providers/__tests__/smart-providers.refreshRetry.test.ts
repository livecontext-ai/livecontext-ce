import { describe, expect, it } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';

/**
 * Wiring of the refresh retry (CASA round 7): getAccessToken must route its refresh through
 * refreshWithTransientRetry (behaviour covered in lib/auth/refreshRetry.test.ts) and sign out only
 * on the redirect it decides, never on any rejection. Before the fix its catch sent every
 * rejection, the 10 s request timeout included, to the login page.
 */

const SOURCE = fs.readFileSync(path.join(process.cwd(), 'lib', 'providers', 'smart-providers.tsx'), 'utf8');

function getAccessTokenBlock(): string {
  const start = SOURCE.indexOf('const getAccessToken = useCallback(');
  expect(start).toBeGreaterThanOrEqual(0);
  const end = SOURCE.indexOf('}, [refreshSession]);', start);
  expect(end).toBeGreaterThan(start);
  return SOURCE.slice(start, end);
}

describe('getAccessToken refresh failure handling', () => {
  it('refreshes through refreshWithTransientRetry, still via refreshSession (the cross-tab lock)', () => {
    const block = getAccessTokenBlock();
    expect(block).toMatch(/refreshWithTransientRetry\(\{[\s\S]*refresh:\s*\(\)\s*=>\s*refreshSession\(\)/);
  });

  it('signs out only when the retry decided so', () => {
    const block = getAccessTokenBlock();
    const redirects = [...block.matchAll(/safeRedirectToLoginRef\.current\(([^)]*)\)/g)].map((m) => m[1].trim());
    // sessionInvalid: a refused or expired-and-failed refresh is a dead session (only then may a
    // failed redirect remove the shared stored user).
    expect(redirects).toEqual(['decision.reason, { sessionInvalid: true }']);
    expect(block).toMatch(/if \(decision\.kind === 'redirect'\)/);
    expect(block).not.toMatch(/catch\s*\(/);
  });
});
