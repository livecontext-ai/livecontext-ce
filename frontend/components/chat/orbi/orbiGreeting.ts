/**
 * Orbi waves hello once after a sign-in. The sign-in paths (the Keycloak callback in cloud, the
 * embedded login and register in CE) mark a greeting as pending; the first Orbi to appear in that
 * tab consumes it, so a second Orbi (side panel) or a later page never waves again. Session
 * storage, because a sign-in belongs to a tab. Storage can throw (private mode, blocked site
 * data): then Orbi simply does not wave, which is never wrong.
 */
export const ORBI_GREETING_KEY = 'orbi_greeting_pending';

export function markOrbiGreeting(): void {
  try {
    window.sessionStorage.setItem(ORBI_GREETING_KEY, '1');
  } catch {
    // No storage, no wave.
  }
}

/** True once per pending greeting: reading it clears it. */
export function consumeOrbiGreeting(): boolean {
  try {
    if (window.sessionStorage.getItem(ORBI_GREETING_KEY) === null) return false;
    window.sessionStorage.removeItem(ORBI_GREETING_KEY);
    return true;
  } catch {
    return false;
  }
}
