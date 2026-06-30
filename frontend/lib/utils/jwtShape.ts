/**
 * Heuristic SHAPE check for a JWS compact JWT (`base64url(header).payload.sig`): the
 * header always starts with `{"`, which base64url-encodes to the `eyJ` prefix, and a
 * compact JWT has exactly three dot-separated segments.
 *
 * This is a shape test, NOT a signature/validity check. The API proxy (both the
 * `proxy.ts` middleware and the `/api/proxy` route handler) uses it to decide whether a
 * `?token=` query param is the access token (to promote into an `Authorization: Bearer`
 * header for `<img>`/`window.open` which can't send headers) versus an opaque RESOURCE
 * token that the backend itself reads from `?token=` - the unauthenticated
 * invitation-accept lookup, email verification, password reset, etc. Resource tokens are
 * UUID/opaque and never match this shape, so they are forwarded to the gateway untouched.
 *
 * Shared so the two proxy layers cannot drift apart again (the original bug was the same
 * strip duplicated in both, breaking `/organizations/invitations/info?token=`).
 */
export function isJwtShapedToken(value: string | null | undefined): boolean {
  return !!value && value.startsWith('eyJ') && value.split('.').length === 3;
}
