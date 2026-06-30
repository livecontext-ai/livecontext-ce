'use client';

import { use, useState, useEffect, useCallback } from 'react';
import { useTranslations } from 'next-intl';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import type { AgentPublicationSnapshot } from '@/lib/api/orchestrator/types';
import { AgentFleetCanvas } from '@/components/agent-fleet/AgentFleetCanvas';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useCeCloudLinkStatus } from '@/hooks/useCeCloudLinkStatus';
import { IS_CE } from '@/lib/edition';

export default function AgentPublicationDetailPage({ params }: { params: Promise<{ publicationId: string }> }) {
  const { publicationId } = use(params);
  const t = useTranslations('marketplace');
  // CE-cloud parity: route the cloud publication's detail + agent-snapshot
  // reads through the cloud proxy (cloud id is absent from the local DB).
  // Gate on the INSTALL-global link, not per-user: an inherited-link member must also read the
  // cloud agent through the proxy (linked=false but installLinked=true), else this 404s.
  const { isLoading: isLinkLoading, isInstallCloudLinked } = useCeCloudLinkStatus();
  const remote = IS_CE && isInstallCloudLinked;
  const [publication, setPublication] = useState<any>(null);
  const [snapshot, setSnapshot] = useState<AgentPublicationSnapshot | null>(null);
  const [error, setError] = useState<string | null>(null);

  const loadData = useCallback(async () => {
    try {
      const [pub, snap] = await Promise.all([
        publicationService.getPublicationByIdPublic(publicationId, remote),
        publicationService.getAgentSnapshot(publicationId, remote),
      ]);
      setPublication(pub);
      setSnapshot(snap);
    } catch (err) {
      setError(t('agents.loadError'));
    }
  }, [publicationId, remote, t]);

  useEffect(() => {
    // Wait for cloud-link status before fetching so a linked CE picks the cloud source.
    if (IS_CE && isLinkLoading) return;
    loadData();
  }, [loadData, isLinkLoading]);

  if (error) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <p className="text-sm text-theme-muted">{error}</p>
      </div>
    );
  }

  if (!publication || (IS_CE && isLinkLoading)) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <LoadingSpinner />
      </div>
    );
  }

  return (
    <div className="flex-1 min-h-0">
      <AgentFleetCanvas snapshot={snapshot} snapshotMode />
    </div>
  );
}
