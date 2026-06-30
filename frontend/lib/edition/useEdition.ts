'use client';

/**
 * Client-side hook re-exporting the build-time edition constant.
 *
 * The value is synchronous and stable across renders (frozen at build time, see
 * `./edition.ts`). The hook exists for ergonomic parity with other React hooks
 * but does NOT introduce reactivity - the underlying value never changes.
 *
 * For non-component callers (utility modules, middleware-adjacent code), import
 * `EDITION`, `IS_CE`, or `IS_CLOUD` directly from `./edition`.
 */

import { EDITION, IS_CE, IS_CLOUD, type Edition } from './edition';

export function useEdition(): Edition {
    return EDITION;
}

export { IS_CE, IS_CLOUD };
export type { Edition };
