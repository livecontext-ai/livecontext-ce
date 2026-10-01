/**
 * The Partner Program Terms (V557): the version a partner accepts, and where the text lives.
 *
 * <p>{@link PARTNER_TERMS_VERSION} is the version printed on the terms pages and sent back when a
 * partner accepts them. It MUST equal the backend's current version (PartnerTermsService
 * CURRENT_VERSION): the backend refuses any other version with {@code terms_outdated}.
 *
 * <p>{@link PARTNER_TERMS_FINGERPRINT} is the sha256 of that version's text (both languages, as
 * rendered), recorded by the backend with every acceptance (CURRENT_FINGERPRINT there). A
 * published text never changes: to change a word, publish a NEW version with its new fingerprint,
 * here and in the backend, and give partners notice (clause 17). A guard test fails when the
 * text and its fingerprint disagree.
 */
export const PARTNER_TERMS_VERSION = '2026-10-01';
export const PARTNER_TERMS_FINGERPRINT = 'sha256:983a99ad5e3e59a9e5f6bf0c0cccaf825a94e226603a854bd7996e0cd2d94887';

/** The English text (a translation for convenience) and the French text, which prevails. */
export const PARTNER_TERMS_PATH = '/legal/partners';
export const PARTNER_TERMS_PATH_FR = '/legal/partners/fr';

/** The terms page in the reader's language: French for a French reader, English otherwise. */
export function partnerTermsPathFor(locale: string): string {
  return locale === 'fr' ? PARTNER_TERMS_PATH_FR : PARTNER_TERMS_PATH;
}
