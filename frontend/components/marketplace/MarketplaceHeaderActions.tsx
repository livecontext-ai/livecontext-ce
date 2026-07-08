'use client';

import React, { useState, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { Gift, Coins } from 'lucide-react';
import { Button } from '@/components/ui/button';
import AcquirePublicationModal from '@/components/marketplace/AcquirePublicationModal';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { isCeMode } from '@/lib/format-cost';
import { useCeCloudLinkStatus } from '@/hooks/useCeCloudLinkStatus';
import { IS_CE } from '@/lib/edition';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

interface MarketplaceHeaderActionsProps {
  publicationId: string;
  compact?: boolean;
}

/**
 * Install/Free button rendered inside the ChatHeader for the marketplace
 * preview route. Fetches its own publication so the header stays
 * self-contained (no prop drilling from the preview page). The Info "i"
 * panel lives as a floating overlay on the preview content itself.
 */
export function MarketplaceHeaderActions({ publicationId, compact = false }: MarketplaceHeaderActionsProps) {
  const t = useTranslations();
  const router = useRouter();
  // CE-cloud parity: on a cloud-linked CE this is a CLOUD publication - fetch it
  // through the cloud proxy and route the install through the remote acquire path.
  // INSTALL-global link (not per-user): an inherited-link member must fetch the cloud publication
  // through the proxy AND route the install through the remote acquire path (ceMode), like the owner.
  const { isInstallCloudLinked } = useCeCloudLinkStatus();
  const remote = IS_CE && isInstallCloudLinked;
  const [publication, setPublication] = useState<WorkflowPublication | null>(null);
  const [isAcquireModalOpen, setIsAcquireModalOpen] = useState(false);

  useEffect(() => {
    let cancelled = false;
    publicationService.getPublicationByIdPublic(publicationId, remote)
      .then(pub => { if (!cancelled) setPublication(pub); })
      .catch(() => { /* silent - header just won't render actions */ });
    return () => { cancelled = true; };
  }, [publicationId, remote]);

  if (!publication) return null;

  const credits = publication.creditsPerUse ?? null;
  const priceLabel = credits === 0
    ? t('marketplace.free')
    : (isCeMode ? `$${credits}` : `${credits} ${t('marketplace.credits')}`);

  const isAgent = (publication.publicationType || 'WORKFLOW') === 'AGENT';
  // Inline (on-card) install progress only works for publications that HAVE a
  // card on the marketplace Explore grid - which surfaces APPLICATION display
  // mode only. Agents, tables, interfaces, skills and bare workflows keep the
  // classic full-modal progress + success screen.
  const inlineInstall = (publication.displayMode || 'WORKFLOW') === 'APPLICATION' && !isAgent;

  return (
    <>
      <Button
        variant="default"
        size="sm"
        onClick={() => setIsAcquireModalOpen(true)}
        className="relative overflow-hidden h-8 px-3 gap-1.5"
        title={priceLabel}
      >
        {/* Subtle theme-aware shimmer sweep over the price / Free pill. Uses the
            --shimmer-color tokens so it adapts to the inverted accent button:
            a white sheen on the dark button in light theme, a dark sheen on the
            light button in dark theme. Sits behind the icon + label (which stay
            crisp on top); decorative, so aria-hidden + pointer-events-none. */}
        <span
          aria-hidden="true"
          className="pointer-events-none absolute inset-0"
          style={{
            background:
              'linear-gradient(90deg, transparent 0%, var(--shimmer-color) 25%, var(--shimmer-color-strong) 50%, var(--shimmer-color) 75%, transparent 100%)',
            backgroundSize: '200% 100%',
            animation: 'shimmer-scan 2.5s ease-in-out infinite',
          }}
        />
        {credits === 0 ? (
          <Gift className="w-3.5 h-3.5" />
        ) : (
          <Coins className="w-3.5 h-3.5" />
        )}
        {!compact && credits !== 0 && (
          <span className="hidden sm:inline">{priceLabel}</span>
        )}
        {!compact && credits === 0 && (
          <span className="hidden sm:inline">{t('marketplace.free')}</span>
        )}
      </Button>

      {isAcquireModalOpen && typeof document !== 'undefined' && createPortal(
        <AcquirePublicationModal
          isOpen={isAcquireModalOpen}
          onClose={() => setIsAcquireModalOpen(false)}
          publication={publication}
          ceMode={remote}
          // Applications: confirming returns the user to the marketplace grid,
          // where the app's card previews un-grey as the install gauge fills
          // (inlineProgress - the machine lives in the marketplace-install
          // store, so it survives this navigation). Every other type has no
          // card on the Explore grid, so it keeps the classic in-modal
          // progress + success screen and the pre-existing success routing.
          inlineProgress={inlineInstall}
          onInstallStarted={() => router.push('/app/marketplace')}
          onSuccess={() => {
            if (inlineInstall) return; // the marketplace card takes over
            if (isAgent) {
              router.push('/app/agent');
            } else {
              router.push(`/app/applications/${publication.id}`);
            }
          }}
        />,
        document.body,
      )}
    </>
  );
}
