/**
 * The token of a partner's offer link (/offer/<token>), as the server accepts it (it draws 10
 * characters; 6 to 16 are read). One definition for the page's server read, the onboarding guard
 * that recognises the route, and the post-onboarding return that may send a person back to it.
 * Dependency-free on purpose: the guard sits in the root layout.
 */
export const OFFER_TOKEN_SOURCE = '[A-Za-z0-9]{6,16}';

export const OFFER_TOKEN_RE = new RegExp(`^${OFFER_TOKEN_SOURCE}$`);

/** `/offer/<token>`, nothing before or after it. */
export const OFFER_PATH_RE = new RegExp(`^/offer/${OFFER_TOKEN_SOURCE}$`);
