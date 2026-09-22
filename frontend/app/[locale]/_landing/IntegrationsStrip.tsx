import Link from 'next/link';
import { BrandMark } from '@/components/integrations/BrandMark';
import { integrationPath } from '@/lib/integrations/integrations';
import { WELL_KNOWN_INTEGRATIONS } from '@/lib/integrations/wellKnownIntegrations';
import {
  CATALOG_INTEGRATIONS_CLAIM,
  CATALOG_OPERATIONS_CLAIM,
} from '@/lib/integrations/integrationCount';

/**
 * The brand marks under the hero: the tools a visitor recognises, linked to their own pages.
 *
 * <p><strong>This band existed twice before and was removed twice, for two different
 * reasons. It is rebuilt to be neither.</strong>
 *
 * <p>The FIRST version was a hand-written strip of twelve slugs beside a tool-count
 * figure nobody maintained: unverified links and a number that drifted from the catalogue
 * every time the catalogue moved. It was replaced (7337ea9c6) by a live 24-card grid with a
 * search box, ordered by the node-usage ledger.
 *
 * <p>That SECOND version is the one whose removal (7131fa534) this component must not
 * repeat, and the commit gives both reasons. It was too heavy for the position: a card grid
 * plus a search field between the hero and everything the landing exists to say, duplicating
 * `/integrations`, which does it better with the whole catalogue behind it. And it read the
 * gateway, which cannot work at build time (the CI builder cannot reach it) and timed out
 * against restarting pods mid-rollout on 2026-09-06, so an empty render was cached and
 * served to every visitor for over an hour.
 *
 * <p>So: <strong>a strip, not a directory</strong> (one wrapped row of marks, no cards, no
 * search, no counts per integration; the directory is one click away and keeps its header
 * entry), and <strong>static, not fetched</strong>. Rendering from
 * {@link WELL_KNOWN_INTEGRATIONS} means this band has no gateway dependency and therefore no
 * empty state to cache: the failure that removed its predecessor cannot occur here. What
 * made the first version unsafe is answered by verification rather than avoidance, exactly
 * as the footer's column was: `wellKnownIntegrations.test.ts` resolves every slug, name and
 * icon key against the seed corpus the importer loads, so a rename fails a test instead of
 * shipping dead links or blank marks.
 *
 * <p>The whole list is in the HTML, and on a narrow screen CSS hides the tail. Twenty-five
 * chips wrap into twelve rows at 390px, which puts the same wall between the hero and the
 * rest of the page that got the previous band deleted, just on a phone. Hiding rather than
 * slicing keeps every link in the served markup for a crawler.
 *
 * <p>The count was never why its predecessor was too
 * heavy: that band showed CARDS, each with a description and a tool count, plus a search
 * field. These are chips, so two dozen names wrap into three short rows and the hero above
 * them is still what the page opens with. Slicing here would also leave verified entries
 * rendering nowhere.
 */
interface IntegrationsStripProps {
  /** Persona ordering keeps the entire verified list and never adds unverified links. */
  prioritySlugs?: readonly string[];
  labels?: { heading: string; browseAll: string; details: string };
  className?: string;
}

export default function IntegrationsStrip({ prioritySlugs = [], labels, className }: IntegrationsStripProps = {}) {
  const priorities = new Map([...new Set(prioritySlugs)].map((slug, index) => [slug, index]));
  const integrations = [...WELL_KNOWN_INTEGRATIONS].sort((first, second) =>
    (priorities.get(first.slug) ?? priorities.size) - (priorities.get(second.slug) ?? priorities.size),
  );
  return (
    <section
      id="integrations"
      className={className}
      style={{
        background: 'var(--bg-secondary)',
        borderTop: '1px solid var(--border-color)',
        borderBottom: '1px solid var(--border-color)',
      }}
    >
      <div className="max-w-6xl mx-auto px-6 py-12 md:py-14">
        <p className="text-center text-[11px] uppercase tracking-wider" style={{ color: 'var(--text-muted)' }}>
          {labels?.heading ?? 'Connects to the tools your team already uses'}
        </p>

        <ul className="integration-chips mt-8 flex flex-wrap items-stretch justify-center gap-2 md:gap-3">
          {integrations.map((integration) => (
            <li key={integration.slug}>
              <Link
                href={integrationPath(integration.slug)}
                prefetch={false}
                className="integration-chip inline-flex items-center gap-2 h-9 px-3 rounded-xl border text-sm"
                style={{ borderColor: 'var(--border-color)', color: 'var(--text-secondary)' }}
              >
                <BrandMark iconSlug={integration.iconSlug} size={18} />
                {integration.name}
              </Link>
            </li>
          ))}
        </ul>

        <p className="mt-8 text-center text-sm" style={{ color: 'var(--text-muted)' }}>
          <Link href="/integrations" prefetch={false} className="integration-strip-all">
            {labels?.browseAll ?? `Browse all ${CATALOG_INTEGRATIONS_CLAIM} integrations`}
          </Link>{' '}
          {labels?.details ?? `· ${CATALOG_OPERATIONS_CLAIM} ready-to-call operations · or bring any API of your own`}
        </p>
      </div>
    </section>
  );
}
