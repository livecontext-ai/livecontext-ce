/**
 * Marketplace feature flags - frozen at build time.
 *
 * Single source of truth for in-progress features that need to be visible
 * (so users see "Coming soon") but not yet shippable end-to-end.
 *
 * Flip via env var on a future release; never read directly from
 * {@code process.env} in components - go through this module so the call
 * sites stay grep-able and the rollout is a one-line change.
 */

/**
 * Paid templates (creditsPerUse > 0 on PUBLIC publications). Disabled until
 * the billing pipeline (payouts, VAT, refund) is production-ready. While
 * false: publish wizards grey the price input, the backend rejects any
 * curl-side attempt to set creditsPerUse > 0 on a new publish, and existing
 * paid publications keep their price (grandfathered) so already-acquired
 * users are not retroactively affected.
 *
 * The default-false constant ships disabled; flip via
 * {@code NEXT_PUBLIC_PAID_TEMPLATES=true} when the feature lands. Backend
 * rejection lives in {@code WorkflowPublicationService} / {@code
 * ResourcePublicationService} / {@code AgentPublicationService} and follows
 * the same env-var name on the JVM side.
 */
export const PAID_TEMPLATES_ENABLED: boolean =
    (process.env.NEXT_PUBLIC_PAID_TEMPLATES ?? '').trim().toLowerCase() === 'true';
