/**
 * A partner application typed on the public /partners page by a visitor who is not signed in
 * yet. It is kept in sessionStorage across the sign-in round trip (same tab), then sent once the
 * visitor is back, so signing in never costs them what they typed.
 *
 * <p>Every read and write is wrapped: storage can be missing or refused (private window, blocked
 * site data), and the form must still work, only without the carry-over.
 */

export interface PartnerApplyDraft {
  company: string;
  website: string;
  audience: string;
  message: string;
  /**
   * The version of the Partner Program Terms the visitor ticked before signing in. Absent when
   * they did not tick the box, or on a draft saved before the terms existed.
   */
  termsVersion?: string;
}

export const PARTNER_APPLY_DRAFT_KEY = 'lc.partnerApplyDraft';

/**
 * Where the sign-in comes back to: the page, flagged so it sends the saved draft and scrolls to
 * the form. No fragment: an OAuth return address must not carry one (RFC 6749, 3.1.2).
 */
export const PARTNER_APPLY_RETURN_TO = '/partners?apply=1';

function isDraft(value: unknown): value is PartnerApplyDraft {
  if (typeof value !== 'object' || value === null) return false;
  const v = value as Record<string, unknown>;
  return ['company', 'website', 'audience', 'message'].every((k) => typeof v[k] === 'string')
    && (v.termsVersion === undefined || typeof v.termsVersion === 'string');
}

export function saveApplyDraft(draft: PartnerApplyDraft): void {
  try {
    window.sessionStorage.setItem(PARTNER_APPLY_DRAFT_KEY, JSON.stringify(draft));
  } catch {
    // Storage refused: the visitor types it again after signing in.
  }
}

/** The saved draft, or null when there is none, it is malformed, or storage is unavailable. */
export function loadApplyDraft(): PartnerApplyDraft | null {
  try {
    const raw = window.sessionStorage.getItem(PARTNER_APPLY_DRAFT_KEY);
    if (!raw) return null;
    const parsed: unknown = JSON.parse(raw);
    return isDraft(parsed) && parsed.company.trim() ? parsed : null;
  } catch {
    return null;
  }
}

export function clearApplyDraft(): void {
  try {
    window.sessionStorage.removeItem(PARTNER_APPLY_DRAFT_KEY);
  } catch {
    // Nothing to clear.
  }
}
