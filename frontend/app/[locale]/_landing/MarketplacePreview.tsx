'use client';

import { useEffect, useState } from 'react';
import { useRouter } from '@/i18n/navigation';
import type { WorkflowPublication } from '@/lib/api';
import { PublicationCard, PublicationCardSkeleton } from '@/components/marketplace/PublicationCard';
import type { PersonaKey } from '@/components/landing/personas/personas';

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
 * Data source, in order, first non-empty wins:
 * 1. the persona's own curated row (LANDING_OPS, LANDING_CREATOR, ...) on a
 *    /for/<persona> page, set in Settings → Marketplace Highlights;
 * 2. the generic LANDING row, which the home page uses;
 * 3. the most recent public marketplace publications.
 *
 * In practice a persona page stops at step 1 wherever the LANDING row was
 * curated, because the migration that opened the six buckets SEEDED each one
 * with a copy of it: an admin then edits one persona at a time from a working
 * starting point rather than from nothing. Where LANDING was empty the migration
 * copied nothing, and the page falls through to step 3 exactly as before. Those copies are SNAPSHOTS, not a
 * view of LANDING: editing LANDING afterwards moves the home page only, and each
 * persona row has to be edited on its own. That is the intent, a persona row
 * exists precisely to diverge. Step 2 then catches the persona whose row an
 * admin has emptied, and step 3 the install that has never curated anything.
 */
export default function MarketplacePreview({ persona }: { persona?: PersonaKey } = {}) {
  const router = useRouter();
  const [pubs, setPubs] = useState<WorkflowPublication[] | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    let cancelled = false;
    // Both endpoints are public (anonymous-accessible) gateway routes, so raw
    // fetch through the proxy is correct here - the landing page has no auth token.
    (async () => {
      const curatedRow = async (bucket: string) => {
        const res = await fetch(`/api/proxy/publications/highlights/${bucket}`, {
          headers: { Accept: 'application/json' },
        });
        if (!res.ok) return [];
        const data: LandingHighlightsResponse = await res.json();
        return (data.highlights || [])
          .map((h) => h.publication)
          .filter((p): p is WorkflowPublication => !!p)
          .slice(0, 16);
      };
      try {
        for (const bucket of persona ? [`LANDING_${persona.toUpperCase()}`, 'LANDING'] : ['LANDING']) {
          const curated = await curatedRow(bucket);
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
  }, [persona]);

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
