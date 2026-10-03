/**
 * Sets `<html lang>` from an inline script, mirroring the one `app/[locale]/layout.tsx` already
 * renders for every route (see `documentLanguage.test.ts` and that layout's own comment for why
 * the TRUE root layout, `app/layout.tsx`, cannot know the locale itself).
 *
 * `app/[locale]/layout.tsx` keeps rendering its OWN copy of this script WITHOUT a nonce, for
 * every route, nonce-class included - that copy is left untouched on purpose (it is the one
 * `documentLanguage.test.ts` pins at the source level, and touching it would force the shared
 * root-of-[locale] layout to call `headers()`, which opts the ENTIRE `[locale]` tree - marketing,
 * docs, blog - out of static rendering; see securityHeaders.mjs's module header on why that is
 * off the table for this batch).
 *
 * Every nonce-class area layout (`/app`, `/login`, `/register`, `/onboarding`,
 * `/forgot-password`, `/reset-password`, `/invitations`, `/auth`, `/ce-setup` - all already
 * `force-dynamic`, see each layout.tsx) renders a SECOND copy of the identical statement, this
 * time WITH the per-request nonce, via this component. Verified live (2026-09-23,
 * `next start` + curl): the two copies are BOTH shipped, and only this nonced one is guaranteed
 * to run under the strict script-src, independent of how the DOM ends up creating it. (These
 * routes are heavily Client-Component-driven - `AppLayoutClient` and every auth page are `'use
 * client'` - so both script elements are actually reconstructed during hydration from the RSC
 * payload rather than parsed as literal markup; under `'strict-dynamic'` a script INSERTED by an
 * already-trusted script, such as Next's own nonced hydration bundle, is trusted regardless of
 * its own nonce, which would let even the unnonced copy run in THAT specific case - but that is
 * an artifact of this rendering path, not something to depend on. A future page in this class
 * that is less client-heavy, or SSRs before hydrating, would have the unnonced copy be
 * parser-inserted with no trusted inserter, which the strict script-src DOES block.) This nonced
 * copy is correct either way, which is the property that matters: without it,
 * `document.documentElement.lang` would be relying entirely on that hydration-timing nuance to
 * ever get corrected on a nonce-class route (screen readers reading the wrong language,
 * `PlanLimitToastListener` picking wording from a stale attribute).
 */
export default function HtmlLangSync({ locale, nonce }: { locale: string; nonce?: string | null }) {
  return (
    <script
      nonce={nonce ?? undefined}
      dangerouslySetInnerHTML={{ __html: `document.documentElement.lang=${JSON.stringify(locale)}` }}
    />
  );
}
