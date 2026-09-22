import { notFound } from 'next/navigation';
import { ExternalLink } from 'lucide-react';
import { LandingShell } from '@/components/landing/LandingShell';
import { docsStyles } from '@/app/docs/_components/docsStyles';
import { statusStyles } from '@/app/status/_components/statusStyles';
import { fetchServiceStatus } from '@/lib/status/fetchStatus';
import { buildDayWindow, formatUptime } from '@/lib/status/probeData';
import { severityOf } from '@/lib/status/overall';
import {
  MOBILE_WINDOW_DAYS,
  SEVERITY_COLOR,
  axisStartLabels,
  barClass,
  barTitle,
  componentColor,
  hasMeasuredHistory,
  incidentClass,
  incidentFeedNote,
  incidentMeta,
  leadCopy,
  methodCopy,
  oldestVisibleIndex,
} from '@/lib/status/presentation';
import {
  componentStateLabel,
  formatUtcInstant,
  incidentKindLabel,
  overallCopy,
  statusTokenLabel,
} from '@/lib/status/format';
import { IS_CE } from '@/lib/edition/edition';
import { socialCard } from '@/lib/seo/socialCard';

/**
 * Public status page: what is up right now, what is not, and 90 days of measured
 * uptime per component.
 *
 * It is a MIRROR, not the source of truth. The canonical page is hosted by
 * incident.io on its own infrastructure, because a status page served from our
 * cluster is unreachable exactly during the outage it should be reporting, and
 * because only that page can still notify subscribers while we are down. This
 * one exists so a user gets the answer without leaving the product, and it links
 * out for full history and notifications.
 *
 * Data comes from `fetchServiceStatus`: the ops-host probe rollup over the VLAN
 * (components + bars) and incident.io's Widget API (ongoing incidents), each
 * degrading on its own. Nothing here ever renders "operational" for data it could
 * not obtain (see `deriveOverall`).
 *
 * Every sentence the page states about itself comes from `lib/status/presentation`
 * rather than being written inline, so the claims are unit-tested and cannot
 * drift from the collector's actual behaviour.
 *
 * English-only, like every page outside the `[locale]` tree (LandingShell has no
 * intl context). Cloud-only: a self-hosted install makes no availability promise
 * about someone else's servers, so CE 404s this route.
 */

// Dynamic, and honestly so: `fetchServiceStatus` fetches with `cache: 'no-store'`
// (it owns its own expiring memo, see that file), and a no-store fetch already
// excludes a page from the full route cache. Declaring `revalidate` here would
// look like ISR while changing nothing. The upstream cost stays bounded by the
// memo, not by this page's render count.
export const dynamic = 'force-dynamic';

export const metadata = {
  // Just the page name: the root layout already appends " - LiveContext" via its
  // title template, so spelling the brand out here would double it in the tab.
  title: 'Status',
  description:
    'Live availability of the LiveContext platform: current state per component, ongoing incidents and 90 days of measured uptime.',
  alternates: { canonical: '/status' },
  ...socialCard({ title: 'Status', description: 'Live availability of the LiveContext platform: current state per component, ongoing incidents and 90 days of measured uptime.', path: '/status' }),
  robots: IS_CE ? { index: false, follow: false } : undefined,
};

export default async function StatusPage() {
  // Cloud-only surface (see the header comment).
  if (IS_CE) notFound();

  const status = await fetchServiceStatus();
  const copy = overallCopy(status);
  const updatedAt = formatUtcInstant(status.generatedAt);
  const externalUrl = status.statusPageUrl;
  const feedNote = incidentFeedNote(status);
  // Same test as the lead paragraph: a window is only worth labelling when some
  // day in it was actually measured. The collector publishes all six components
  // with an empty history on its first tick, so counting rows is not enough.
  const hasHistory = hasMeasuredHistory(status);
  const narrowWindowDays = Math.min(status.windowDays, MOBILE_WINDOW_DAYS);

  return (
    <LandingShell extraStyles={docsStyles + statusStyles}>
      <div className="max-w-3xl mx-auto px-6 py-16 md:py-20">
        <header className="mb-10">
          <span className="docs-eyebrow">Platform</span>
          <h1 className="docs-h1">Status</h1>
          <p className="docs-lead">{leadCopy(status)}</p>
        </header>

        <section className="st-hero" aria-live="polite">
          <span
            className="st-hero-dot"
            style={{ background: SEVERITY_COLOR[severityOf(status.overall)] }}
            aria-hidden="true"
          />
          <div className="st-hero-text">
            <p className="st-hero-title">{copy.title}</p>
            <p className="st-hero-sub">
              {copy.sub}
              {updatedAt ? ` Updated ${updatedAt}.` : ''}
            </p>
          </div>
          {externalUrl && (
            <a className="st-hero-link" href={externalUrl} target="_blank" rel="noopener noreferrer">
              Subscribe to updates
            </a>
          )}
        </section>

        {status.incidents.length > 0 && (
          <>
            <div className="st-section">
              <h2>Active &amp; scheduled</h2>
            </div>
            {status.incidents.map((incident) => {
              const meta = incidentMeta(incident);
              return (
                <article
                  key={`${incident.kind}-${incident.id}`}
                  className={incidentClass(incident.kind)}
                >
                  <div className="st-incident-head">
                    <span className="st-badge">{incidentKindLabel(incident.kind)}</span>
                    {incident.status !== '' && (
                      <span className="st-badge">{statusTokenLabel(incident.status)}</span>
                    )}
                    <span className="st-incident-name">{incident.name}</span>
                  </div>
                  {incident.message && <p className="st-incident-msg">{incident.message}</p>}
                  {(meta !== '' || incident.url) && (
                    <p className="st-incident-meta">
                      {meta}
                      {incident.url && (
                        <>
                          {meta !== '' ? ' - ' : ''}
                          <a
                            href={incident.url}
                            target="_blank"
                            rel="noopener noreferrer"
                            style={{ color: 'var(--expression-color)', fontWeight: 500 }}
                          >
                            Details
                          </a>
                        </>
                      )}
                    </p>
                  )}
                </article>
              );
            })}
          </>
        )}

        <div className="st-section">
          <h2>Components</h2>
          {hasHistory && (
            <span className="st-section-note">
              <span className="st-bars-window-long">Last {status.windowDays} days</span>
              <span className="st-bars-window-short">Last {narrowWindowDays} days</span>
            </span>
          )}
        </div>

        {status.components.length === 0 ? (
          <p className="st-fallback">
            Live component data is unavailable right now, so this page cannot report per-component
            state.
            {externalUrl && (
              <>
                {' '}
                Check{' '}
                <a
                  href={externalUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  style={{ color: 'var(--expression-color)', fontWeight: 500 }}
                >
                  our status page
                </a>
                , which is hosted outside our infrastructure.
              </>
            )}
          </p>
        ) : (
          <div className="st-components">
            {status.components.map((component) => {
              const days = buildDayWindow(component.history, status.windowDays);
              const oldest = oldestVisibleIndex(days.length);
              const axis = axisStartLabels(days);
              const uptime = formatUptime(component.uptimeRatio);
              return (
                <div key={component.id} className="st-row">
                  <div className="st-row-head">
                    <span
                      className="st-dot"
                      style={{ background: componentColor(component.state) }}
                      aria-hidden="true"
                    />
                    <span className="st-row-name">{component.name}</span>
                    <span className="st-row-uptime">
                      {uptime ? `${uptime} uptime` : componentStateLabel(component.state)}
                    </span>
                  </div>
                  {days.length > 0 && (
                    <>
                      <div
                        className="st-bars"
                        role="img"
                        aria-label={`${component.name} daily availability`}
                      >
                        {days.map((day, index) => (
                          <span
                            key={day.date}
                            className={barClass(day, index < oldest)}
                            title={barTitle(day)}
                          />
                        ))}
                      </div>
                      <div className="st-bar-scale">
                        <span>
                          {/* Two labels, toggled by the same media query that hides
                              the older bars, so the axis matches what is drawn. */}
                          <span className="st-bars-window-long">{axis.wide}</span>
                          <span className="st-bars-window-short">{axis.narrow}</span>
                        </span>
                        <span>Today</span>
                      </div>
                    </>
                  )}
                </div>
              );
            })}
          </div>
        )}

        <p className="st-foot">
          {methodCopy(status)}
          {feedNote ? ` ${feedNote}` : ''}
          {externalUrl && (
            <>
              {' '}
              Full incident history, past updates and email notifications live on{' '}
              <a href={externalUrl} target="_blank" rel="noopener noreferrer">
                our status page <ExternalLink className="inline h-3.5 w-3.5" />
              </a>
              , which stays online independently of the platform.
            </>
          )}
        </p>
      </div>
    </LandingShell>
  );
}
