'use client';

import { use, useMemo, useState, useEffect, useCallback } from 'react';
import { useTranslations } from 'next-intl';
import { Zap } from 'lucide-react';
import { ApplicationDetailView } from '@/components/views/application/ApplicationDetailView';
import { PublicationPreviewShell } from '@/components/marketplace/PublicationPreviewShell';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { WorkflowLoadingState } from '@/components/views/workflow/WorkflowLoadingState';
import { AgentFleetCanvas } from '@/components/agent-fleet/AgentFleetCanvas';
import DataTable from '@/components/DataTable';
import { snapshotToDataTable } from '@/lib/datatable/snapshot-adapter';
import { InterfacePreview } from '@/components/InterfacePreview';
import { PublicationInfoPanel } from '@/components/marketplace/PublicationInfoPanel';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useCeCloudLinkStatus } from '@/hooks/useCeCloudLinkStatus';
import { IS_CE } from '@/lib/edition';
import type { WorkflowPublication, AgentPublicationSnapshot } from '@/lib/api/orchestrator/types';
import type { DataSourceSnapshot } from '@/contexts/PublicationSnapshotContext';

/**
 * Marketplace preview - dispatches on publicationType. Non-WORKFLOW types
 * render their frozen snapshot directly; they never call the publisher's
 * tenant (403 + data leak risk).
 */
export default function MarketplacePreviewPage({ params }: { params: Promise<{ publicationId: string }> }) {
  const { publicationId } = use(params);
  // key={publicationId} forces a clean remount of the entire data-loading +
  // provider tree when the URL param changes. Without this, the outer
  // component reuses its `publication` state across navigations: it briefly
  // re-renders with the OLD publication's data, mounts WorkflowModeProvider
  // with the OLD runId (locked at mount via isProgrammaticRef), and never
  // remounts again when fresh data arrives - so the side-panel canvas keeps
  // rendering the FIRST opened publication forever.
  return <MarketplacePreviewInner key={publicationId} publicationId={publicationId} />;
}

// Exported for unit testing the load path (anonymous read -> auth'd acquirer/owner
// fallback) without the `use(params)` Suspense boundary of the default export.
export function MarketplacePreviewInner({ publicationId }: { publicationId: string }) {
  const t = useTranslations('marketplace');
  const { isAuthChecking } = useAuthGuard();
  // CE-cloud parity: a cloud-linked CE previews a CLOUD publication, whose id is
  // absent from the local DB - route the detail + agent-snapshot reads (and the
  // gated showcase reads, via PublicationPreviewShell) through the cloud proxy.
  // Gate on the INSTALL-global link, not the per-user one: an inherited-link member (linked=false,
  // installLinked=true) is still browsing CLOUD publications absent from the local DB, so the reads
  // must go through the cloud proxy for them too - else the page 404s into "Failed to load marketplace".
  const { isLoading: isLinkLoading, isInstallCloudLinked } = useCeCloudLinkStatus();
  const remote = IS_CE && isInstallCloudLinked;
  const [publication, setPublication] = useState<WorkflowPublication | null>(null);
  const [agentSnapshot, setAgentSnapshot] = useState<AgentPublicationSnapshot | null>(null);
  const [error, setError] = useState<string | null>(null);
  // True once the publication had to be read through the AUTH'D by-id endpoint
  // (acquirer/owner of a non-public pub). Threaded to the shell so the gated
  // showcase render uses the receipt-gated twin instead of the anonymous one.
  const [authenticatedPreview, setAuthenticatedPreview] = useState(false);

  const loadPublication = useCallback(async () => {
    // 1. Anonymous public read - owners see the same sanitized preview as visitors.
    // 2. On failure for a NON-PUBLIC pub (publisher unpublished/deleted it -> INACTIVE,
    //    or it is PRIVATE), retry the AUTH'D by-id read: the receipt/owner bypass admits
    //    a publication the caller acquired or owns. Mark `authenticated` so the showcase
    //    render routes through the receipt-gated twin too. Cloud-linked CE (remote) has
    //    no local authed twin, so it surfaces the error directly.
    let pub: WorkflowPublication;
    let authed = false;
    try {
      pub = await publicationService.getPublicationByIdPublic(publicationId, remote);
    } catch {
      if (remote) {
        setError(t('loadError'));
        return;
      }
      try {
        pub = await publicationService.getPublicationById(publicationId);
        authed = true;
      } catch {
        setError(t('loadError'));
        return;
      }
    }
    let nextAgentSnapshot: AgentPublicationSnapshot | null = null;
    if (pub.publicationType === 'AGENT') {
      try {
        nextAgentSnapshot = await publicationService.getAgentSnapshot(publicationId, remote);
      } catch {
        // Agent snapshot is optional - canvas renders empty if missing
        nextAgentSnapshot = null;
      }
    }
    setAgentSnapshot(nextAgentSnapshot);
    setPublication(pub);
    setAuthenticatedPreview(authed);
  }, [publicationId, remote, t]);

  useEffect(() => {
    // Wait for the cloud-link status to resolve before fetching, so a linked CE
    // never fires the read against the wrong (local) source first. No auth gate
    // here (it would delay every anonymous visitor): the anonymous read needs no
    // token, and the acquirer/owner authed fallback goes through apiClient, which
    // already awaits the token before issuing the request.
    if (IS_CE && isLinkLoading) return;
    loadPublication();
  }, [loadPublication, isLinkLoading]);

  if (error) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <p className="text-sm text-theme-muted">{error}</p>
      </div>
    );
  }

  if (!publication || isAuthChecking || (IS_CE && isLinkLoading)) {
    return <WorkflowLoadingState />;
  }

  const type = publication.publicationType || 'WORKFLOW';

  // WORKFLOW - single shell for authenticated and anonymous visitors. The
  // shell wires up PublicationSnapshotProvider so getActivePublicPreview()
  // returns {publicationId, showcaseRunId}; every gated hook routes to the
  // public /showcase-render / /run-state endpoints instead of the auth'd
  // /api/interfaces/* / /v2/workflows/dag/* (which would leak the publisher's
  // real workflow runs when the publisher views their own preview). The
  // publisher MUST see exactly what an anonymous visitor sees - that's why
  // publicPreviewMode is forced true regardless of auth state.
  if (type === 'WORKFLOW') {
    if (!publication.showcaseRunId || !publication.workflowId) {
      return (
        <div className="flex-1 flex items-center justify-center">
          <p className="text-sm text-theme-muted">{t('previewUnavailable')}</p>
        </div>
      );
    }
    return (
      <PublicationPreviewShell publication={publication} remote={remote} authenticated={authenticatedPreview}>
        <div className="relative flex-1 min-h-0 w-full">
          <ApplicationDetailView
            workflowId={publication.workflowId}
            runId={publication.showcaseRunId}
            title={publication.title}
            publisherName={publication.publisherName}
            publisherId={publication.publisherId}
            planOverride={publication.planSnapshot}
            publication={publication}
            showInfoPanel={false}
            publicPreviewMode
            remote={remote}
          />
          <PreviewInfoOverlay publication={publication} remote={remote} />
        </div>
      </PublicationPreviewShell>
    );
  }

  // AGENT - fleet canvas fed from the agent snapshot
  if (type === 'AGENT') {
    return (
      <div className="relative flex-1 min-h-0">
        <AgentFleetCanvas snapshot={agentSnapshot} snapshotMode />
        <PreviewInfoOverlay publication={publication} remote={remote} />
      </div>
    );
  }

  // TABLE - mirror /app/tables/[tableId] shell, fed from frozen snapshot
  if (type === 'TABLE') {
    const snap: DataSourceSnapshot = {
      name: publication.planSnapshot?.name || publication.title,
      description: publication.planSnapshot?.description,
      columnOrder: publication.planSnapshot?.columnOrder,
      sourceType: publication.planSnapshot?.sourceType,
      sourceConfig: publication.planSnapshot?.sourceConfig,
      mappingSpec: publication.planSnapshot?.mappingSpec,
      items: publication.planSnapshot?.items,
    };
    return (
      <div className="relative h-full w-full p-6 flex flex-col gap-3">
        <TableSnapshotPreview snap={snap} />
        <PreviewInfoOverlay publication={publication} remote={remote} />
      </div>
    );
  }

  // INTERFACE - full-bleed iframe, same shell as /app/interface/[id].
  // Publisher JS runs in the preview so the marketplace card matches what the buyer
  // will see post-acquisition. Sandbox is `allow-scripts` (no allow-same-origin) so
  // the script can manipulate its own DOM but not reach parent storage / cookies.
  if (type === 'INTERFACE') {
    const snap = publication.planSnapshot || {};
    return (
      <div className="relative h-full overflow-hidden">
        <InterfacePreview
          htmlTemplate={snap.htmlTemplate || ''}
          cssTemplate={snap.cssTemplate}
          jsTemplate={snap.jsTemplate}
          className="w-full h-full"
          autoFit={false}
          // The published interface's own shape: without it a vertical publication previews at
          // its native size in this box instead of being letterboxed to fit.
          format={snap.format as string | null | undefined}
          emptyLabel={t('previewUnavailable')}
        />
        <PreviewInfoOverlay publication={publication} remote={remote} />
      </div>
    );
  }

  // SKILL - name / description / instructions (read-only)
  if (type === 'SKILL') {
    const snap = publication.planSnapshot || {};
    return (
      <div className="relative flex-1 min-h-0 overflow-auto">
        <div className="max-w-3xl mx-auto p-6 space-y-6">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-full bg-theme-secondary flex items-center justify-center shrink-0">
              <Zap className="h-5 w-5 text-theme-primary" />
            </div>
            <div className="min-w-0">
              <h1 className="text-lg font-semibold text-theme-primary truncate">
                {snap.name || publication.title}
              </h1>
              {snap.description && (
                <p className="text-sm text-theme-secondary mt-0.5">{snap.description}</p>
              )}
            </div>
          </div>
          {snap.instructions ? (
            <div className="rounded-xl border border-theme bg-theme-primary p-4">
              <p className="text-xs font-medium text-theme-secondary mb-2">{t('skillInstructionsLabel')}</p>
              <pre className="text-sm text-theme-primary whitespace-pre-wrap font-sans">{snap.instructions}</pre>
            </div>
          ) : (
            <div className="rounded-xl border border-theme bg-theme-secondary px-4 py-6 text-center text-sm text-theme-muted">
              {t('skillNoInstructions')}
            </div>
          )}
        </div>
        <PreviewInfoOverlay publication={publication} remote={remote} />
      </div>
    );
  }

  return (
    <div className="flex-1 flex items-center justify-center">
      <p className="text-sm text-theme-muted">{t('previewUnavailable')}</p>
    </div>
  );
}

/**
 * TABLE branch - wraps the shared DataTable in snapshot mode so the marketplace
 * preview mirrors /app/tables/[tableId] without ever calling the publisher's tenant.
 */
function TableSnapshotPreview({ snap }: { snap: DataSourceSnapshot }) {
  const snapshotData = useMemo(() => snapshotToDataTable(snap), [snap]);

  return (
    <div className="flex-1 min-h-0">
      <DataTable snapshotData={snapshotData} readOnly className="h-full" />
    </div>
  );
}

/** Info "i" panel pinned to the top-right of the preview content.
 *  Opens by default on the Info tab so visitors immediately see the
 *  publication metadata. The panel still closes on click-outside / Escape. */
function PreviewInfoOverlay({ publication, remote }: { publication: WorkflowPublication; remote?: boolean }) {
  return (
    <div className="absolute top-3 right-3 z-40">
      <PublicationInfoPanel publication={publication} defaultOpen floating remote={remote} />
    </div>
  );
}
