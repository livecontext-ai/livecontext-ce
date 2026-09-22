import Link from 'next/link';
import { integrationPath } from '@/lib/integrations/integrations';
import { FOOTER_INTEGRATION_COUNT, WELL_KNOWN_INTEGRATIONS } from '@/lib/integrations/wellKnownIntegrations';

/**
 * The footer's Integrations column: the integrations a visitor recognises, each linking to
 * its own page, plus the directory.
 *
 * <p><strong>It used to be ranked by the node-usage ledger, and that was the bug.</strong>
 * The ranking is honest about what the platform executes and useless as an answer to "does
 * this connect to what my company uses": in production it read "xAI, Instagram, Telegram,
 * TikTok, YouTube Data API, Seedance, Gmail, Apify", because the ledger follows whatever
 * the heaviest workflows call. The list is chosen now, and `WELL_KNOWN_INTEGRATIONS`
 * carries the reasoning.
 *
 * <p><strong>Consequence worth keeping in mind: this column no longer reads the
 * gateway.</strong> That read was the source of two real production failures. It cannot
 * work at BUILD time (the CI builder cannot reach the gateway), and it timed out when a
 * render landed mid-rollout against restarting pods, which left every public page showing
 * an Integrations column containing nothing but "All integrations" for over an hour. A
 * static column cannot fail that way, and every page carrying the footer got its gateway
 * dependency removed with it.
 *
 * <p>The slugs are not guesses: `wellKnownIntegrations.test.ts` resolves each one, and its
 * label, against the API-migration seed corpus the importer loads, which is what makes a
 * hand-written list safe here. A rename fails a test instead of shipping eight 404s into
 * the footer of every page on the site.
 *
 * <p>Still a synchronous CHILD of the footer rather than inline, so `LandingFooter` stays
 * one component and the column can go back to being data-driven without moving it.
 */

export default function FooterIntegrations({ siteBaseUrl, heading = 'Integrations', allLabel = 'All integrations' }: {
  siteBaseUrl?: string;
  /** The column heading. The default is the English the column always had; the sole render
   *  site always passes one, so it is a safety net rather than a path anything takes. */
  heading?: string;
  /** The last entry, which opens the full catalogue. The connector NAMES stay as they are:
   *  they are product names, not copy. */
  allLabel?: string;
}) {
  const href = (path: string) => (siteBaseUrl ? `${siteBaseUrl}${path}` : path);

  return (
    <div>
      <p className="text-[11px] uppercase tracking-wider mb-3" style={{ color: 'var(--text-muted)' }}>
        {heading}
      </p>
      <ul className="space-y-2" style={{ color: 'var(--text-secondary)' }}>
        {WELL_KNOWN_INTEGRATIONS.slice(0, FOOTER_INTEGRATION_COUNT).map((integration) => (
          <li key={integration.slug}>
            <Link href={href(integrationPath(integration.slug))} prefetch={false}>
              {integration.name}
            </Link>
          </li>
        ))}
        <li>
          <Link href={href('/integrations')} prefetch={false}>
            {allLabel}
          </Link>
        </li>
      </ul>
    </div>
  );
}
