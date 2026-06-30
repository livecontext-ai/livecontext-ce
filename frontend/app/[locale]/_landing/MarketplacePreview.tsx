'use client';

import { useEffect, useState } from 'react';
import { useRouter } from '@/i18n/navigation';
import type { WorkflowPublication } from '@/lib/api';
import { PublicationCard, PublicationCardSkeleton } from '@/components/marketplace/PublicationCard';

interface MarketplaceResponse {
  publications?: WorkflowPublication[];
}

// Public read of the admin-curated LANDING highlights row. `publication` is the
// slim PublicHighlightItem DTO - but it carries every field PublicationCard reads
// (showcase ids, nodeIcons, publisher, credits, ratings), so casting it to
// WorkflowPublication is safe (same pattern as HighlightedApps).
interface LandingHighlightsResponse {
  highlights?: Array<{ rank: number; publication: WorkflowPublication | null }>;
}

/**
 * Landing-page marketplace preview. Renders the EXACT same card as the
 * authenticated marketplace Explore tab ({@link PublicationCard}) - same
 * thumbnail, footer and Install button - instead of a forked landing card,
 * so the two surfaces never drift. An anonymous visitor can't acquire in
 * place, so the Install CTA routes to the app (which prompts sign-in).
 *
 * Data source: the admin-curated LANDING highlights row first (set in
 * Settings → Marketplace Highlights → Landing page); when nothing is curated
 * it falls back to the most recent public marketplace publications, so the
 * section is never empty.
 */
export default function MarketplacePreview() {
  const router = useRouter();
  const [pubs, setPubs] = useState<WorkflowPublication[] | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    let cancelled = false;
    // Both endpoints are public (anonymous-accessible) gateway routes, so raw
    // fetch through the proxy is correct here - the landing page has no auth token.
    (async () => {
      try {
        const curatedRes = await fetch('/api/proxy/publications/highlights/LANDING', {
          headers: { Accept: 'application/json' },
        });
        if (curatedRes.ok) {
          const data: LandingHighlightsResponse = await curatedRes.json();
          const curated = (data.highlights || [])
            .map((h) => h.publication)
            .filter((p): p is WorkflowPublication => !!p)
            .slice(0, 16);
          if (curated.length > 0) {
            if (!cancelled) setPubs(curated);
            return;
          }
        }
        // No curated apps (empty row or endpoint miss) → most recent marketplace.
        const recentRes = await fetch('/api/proxy/publications/marketplace?page=0&size=16', {
          headers: { Accept: 'application/json' },
        });
        if (!recentRes.ok) throw new Error(String(recentRes.status));
        const recent: MarketplaceResponse = await recentRes.json();
        if (!cancelled) setPubs((recent.publications || []).slice(0, 16));
      } catch {
        if (!cancelled) setError(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  if (error || (pubs && pubs.length === 0)) return null;

  // Install on the public landing → the app's preview, where sign-in is prompted
  // before the real acquire flow. Keeps the marketplace card's Install button
  // visible (the user's ask) without wiring an in-place acquire for anonymous.
  const handleAcquire = (pub: WorkflowPublication) => {
    const href =
      (pub.displayMode || 'WORKFLOW') === 'AGENT'
        ? `/app/marketplace/agents/${pub.id}`
        : `/app/marketplace/${pub.id}/preview`;
    router.push(href);
  };

  if (!pubs) {
    return (
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-5 md:gap-6">
        {Array.from({ length: 8 }, (_, i) => <PublicationCardSkeleton key={`skel-${i}`} />)}
      </div>
    );
  }

  // Alternating vertical marquee: cards split across 4 columns, odd columns
  // scroll up and even ones down. Each column's content is rendered twice so
  // the -50% translate loop is seamless (spacing via per-card margin, not flex
  // gap, so half the track height lands exactly on the duplicate's start).
  const columns = Array.from({ length: 4 }, (_, c) => pubs.filter((_, i) => i % 4 === c)).filter(
    (col) => col.length > 0,
  );

  return (
    <div className="marketplace-marquee grid grid-cols-2 lg:grid-cols-4 gap-5 md:gap-6">
      {columns.map((col, c) => (
        <div
          key={`col-${c}`}
          className={`marketplace-col${c % 2 === 1 ? ' scroll-down' : ''}${c >= 2 ? ' hidden lg:flex' : ''}`}
        >
          {[...col, ...col].map((p, i) => (
            <div key={`${p.id}-${i}`} className="marketplace-col-item" aria-hidden={i >= col.length}>
              <PublicationCard publication={p} onAcquire={handleAcquire} />
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}
