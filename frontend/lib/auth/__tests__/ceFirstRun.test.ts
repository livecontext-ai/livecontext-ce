import { describe, expect, it } from 'vitest';
import { isCeFirstRun } from '../ceFirstRun';

/**
 * First-run predicate behind the /login -> /register redirect on a virgin CE
 * install. The redirect fires ONLY on an explicit "no account exists AND
 * registration is open" - every unknown/degraded state must read as NOT
 * first-run so real users of an existing install are never bounced away from
 * the sign-in form.
 */
describe('isCeFirstRun', () => {
  it('true on a virgin install: hasUsers=false and registration open', () => {
    expect(isCeFirstRun({ hasUsers: false, registrationOpen: true })).toBe(true);
  });

  it('false as soon as any account exists', () => {
    expect(isCeFirstRun({ hasUsers: true, registrationOpen: true })).toBe(false);
  });

  it('false when registration is closed even with no users (nothing to route to)', () => {
    expect(isCeFirstRun({ hasUsers: false, registrationOpen: false })).toBe(false);
  });

  it('false when hasUsers is absent (older backend without the field)', () => {
    expect(isCeFirstRun({ registrationOpen: true })).toBe(false);
  });

  it('false when registrationOpen is absent', () => {
    expect(isCeFirstRun({ hasUsers: false })).toBe(false);
  });

  it('false on null/undefined status (failed fetch)', () => {
    expect(isCeFirstRun(null)).toBe(false);
    expect(isCeFirstRun(undefined)).toBe(false);
  });
});
