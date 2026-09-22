import Link from 'next/link';
import {
  authTypeLabel,
  integrationPath,
  integrationSummary,
  type PublicIntegration,
} from '@/lib/integrations/integrations';
import { IntegrationLogo } from './IntegrationLogo';

/**
 * One integration in a grid, linking to its own page.
 *
 * <p>No `'use client'` and no hooks, deliberately: the directory renders this on
 * the server so every card is in the crawled HTML, and the search box renders
 * the SAME component on the client for its results. Two copies of a card is how
 * a search result starts looking different from the thing it searched.
 *
 * <p>The href is a bare path, with no sub-host prefix: these cards render on the
 * apex only (/integrations and the landing). The footer's column is the one
 * surface that also renders on the docs sub-host, and it builds its own links.
 */
export function IntegrationCard({ integration }: { integration: PublicIntegration }) {
  const href = integrationPath(integration.slug);
  const auth = authTypeLabel(integration.authType);

  return (
    <Link
      href={href}
      prefetch={false}
      className="group flex flex-col gap-2 rounded-xl border p-4 transition-colors hover:bg-[var(--bg-tertiary)]"
      style={{ borderColor: 'var(--border-color)', background: 'var(--bg-primary)' }}
    >
      <div className="flex items-center gap-2.5">
        <IntegrationLogo integration={integration} size={24} className="shrink-0" />
        <span
          className="truncate text-sm font-medium"
          style={{ color: 'var(--text-primary)' }}
          title={integration.name}
        >
          {integration.name}
        </span>
      </div>
      <p
        className="line-clamp-2 text-sm leading-relaxed"
        style={{ color: 'var(--text-secondary)' }}
      >
        {integrationSummary(integration, 110)}
      </p>
      <div className="mt-auto flex items-center gap-2 pt-1 text-xs" style={{ color: 'var(--text-muted)' }}>
        {/* The endpoint count is the honest measure of depth here: it is what the
            catalog actually exposes, one tool per endpoint, and it is what makes
            "1000+ integrations" mean something more than a logo wall. */}
        <span>
          {integration.toolCount} {integration.toolCount === 1 ? 'tool' : 'tools'}
        </span>
        {auth && (
          <>
            <span aria-hidden="true">·</span>
            <span>{auth}</span>
          </>
        )}
      </div>
    </Link>
  );
}

export default IntegrationCard;
