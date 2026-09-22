/**
 * Search-engine ownership verification, as environment, not as a code change.
 *
 * <p>Without a verified property there is no indexation report, no video
 * indexing report and no crawl-error list, which is how a page can be missing
 * from the results for weeks with nothing anywhere saying so. The tokens are
 * public strings (they appear in the HTML of every page by design), so they are
 * ordinary configuration rather than secrets.
 *
 * <p><strong>Read at render, which for most pages means at BUILD.</strong> This
 * feeds a static `export const metadata`, and the build prerenders roughly 380
 * routes with no revalidation at all; the apex revalidates every 10 minutes.
 * It is also read ONCE, when the layout module is evaluated, so the value is
 * fixed for the life of the process rather than consulted per render: a change
 * takes a restart on the dynamic pages, the apex window on the apex, and an
 * image build on the frozen marketing pages. Do not read the absence of a tag on `/about` as a
 * misconfiguration.
 *
 * <p><strong>Prefer DNS verification.</strong> Google, Bing and Yandex all
 * accept a TXT record, it covers the subdomains too, it survives every deploy,
 * and it has none of the above. Leave these variables unset and nothing is
 * emitted; this exists for the case where a DNS record is not available.
 */
import type { Metadata } from 'next';
import { IS_CE } from '@/lib/edition';

export interface VerificationEnv {
  google?: string;
  bing?: string;
  yandex?: string;
}

function clean(value: string | undefined): string | undefined {
  const trimmed = value?.trim();
  return trimmed ? trimmed : undefined;
}

/**
 * The `verification` block for the root layout, or undefined when there is
 * nothing to claim.
 *
 * `isCe` and `env` are parameters so the rules can be tested without touching
 * the process environment; the export below binds them to the real ones.
 */
export function buildVerification(isCe: boolean, env: VerificationEnv): Metadata['verification'] | undefined {
  // A self-hosted install runs on someone else's domain. Emitting our tokens
  // there would ask their visitors' browsers to assert an ownership claim about
  // a property we do not share, and it verifies nothing for anyone.
  if (isCe) return undefined;

  const google = clean(env.google);
  const bing = clean(env.bing);
  const yandex = clean(env.yandex);

  // Google and Yandex have native fields; Bing does not, so its tag name is
  // written out. Next turns `google` into `google-site-verification` and
  // `yandex` into `yandex-verification`, and passes `other` keys through as is.
  const other = bing ? { 'msvalidate.01': bing } : undefined;

  if (!google && !yandex && !other) return undefined;

  return {
    ...(google ? { google } : {}),
    ...(yandex ? { yandex } : {}),
    ...(other ? { other } : {}),
  };
}

export const searchEngineVerification = (): Metadata['verification'] | undefined =>
  buildVerification(IS_CE, {
    google: process.env.GOOGLE_SITE_VERIFICATION,
    bing: process.env.BING_SITE_VERIFICATION,
    yandex: process.env.YANDEX_SITE_VERIFICATION,
  });
