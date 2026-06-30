/**
 * Frontend single source of truth for the deployment edition (CE vs Cloud).
 *
 * Resolution is **frozen at build time**: Next.js inlines `process.env.NEXT_PUBLIC_*`
 * into both server and client bundles during `next build`. There is no runtime
 * variance, no per-request variance, no per-user variance.
 *
 * Precedence (matches backend AppEditionProvider semantics):
 *
 *   APP_EDITION  AUTH_MODE         Result
 *   -----------  ----------------  ------
 *   ce           any               ce
 *   cloud        oidc / empty      cloud
 *   cloud        embedded          ce      (warn - auth path wins defensively)
 *   unset/empty  embedded          ce      (legacy shim - kept for one release)
 *   unset/empty  oidc / empty      cloud
 *   invalid str  embedded          ce      (warn on invalid; fall through to AUTH_MODE)
 *   invalid str  other             cloud   (warn)
 *
 * Both env vars are trimmed and lowercased before comparison.
 */

export type Edition = 'ce' | 'cloud';

function resolve(): Edition {
    const explicit = (process.env.NEXT_PUBLIC_APP_EDITION ?? '').trim().toLowerCase();
    const auth = (process.env.NEXT_PUBLIC_AUTH_MODE ?? '').trim().toLowerCase();

    if (explicit === 'ce' || explicit === 'cloud') {
        if (explicit === 'cloud' && auth === 'embedded') {
            // eslint-disable-next-line no-console
            console.warn(
                '[edition] NEXT_PUBLIC_APP_EDITION=cloud conflicts with NEXT_PUBLIC_AUTH_MODE=embedded - '
                + 'resolving to "ce" (auth path wins defensively).',
            );
            return 'ce';
        }
        return explicit;
    }

    if (explicit) {
        // eslint-disable-next-line no-console
        console.warn(
            `[edition] Invalid NEXT_PUBLIC_APP_EDITION="${explicit}" - falling back to NEXT_PUBLIC_AUTH_MODE.`,
        );
    }

    return auth === 'embedded' ? 'ce' : 'cloud';
}

/** Frozen at build time. */
export const EDITION: Edition = resolve();

/** Convenience constants - prefer over inline comparisons. */
export const IS_CE = EDITION === 'ce';
export const IS_CLOUD = EDITION === 'cloud';
