'use client';

import React from 'react';
import { PublicationSnapshotProvider } from '@/contexts/PublicationSnapshotContext';
import { WorkflowModeProvider } from '@/contexts/WorkflowModeContext';
import { WorkflowRunProvider } from '@/contexts/WorkflowRunContext';
import {
  WorkflowLayoutDirectionProvider,
  isWorkflowLayoutDirection,
  DEFAULT_WORKFLOW_LAYOUT_DIRECTION,
} from '@/contexts/WorkflowLayoutDirectionContext';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

interface PublicationPreviewShellProps {
  publication: Pick<WorkflowPublication, 'id' | 'planSnapshot' | 'showcaseRunId' | 'workflowId'>;
  /**
   * When true (default) wraps in WorkflowRunProvider too. Side-panel surfaces
   * already mounted under an app layout that provides one can opt out by
   * passing false to avoid a duplicate provider in the tree.
   */
  withRunProvider?: boolean;
  /**
   * CE-cloud parity: the publication is a CLOUD one (cloud-linked CE preview),
   * so the gated showcase reads (showcase-render / run-state / aggregated-steps /
   * per-epoch state) route through the CE backend's cloud proxy. Default false.
   */
  remote?: boolean;
  /**
   * Acquirer/owner preview of a NON-PUBLIC publication (publisher-deleted
   * INACTIVE / PRIVATE): route the interface render through the receipt-gated
   * AUTH'D showcase-render twin instead of the anonymous by-id one (which 403s
   * for non-public pubs). Default false. Ignored when {@code remote} is set.
   *
   * SCOPE: this only re-routes the interface SHOWCASE RENDER (the primary preview
   * surface). The other gated reads (run-state / aggregated-steps / per-epoch
   * state) have NO authed twin yet, so for a non-public pub they still 403 - but
   * they back the secondary side-panel canvas/inspector only, fail silently
   * (caught in WorkflowBuilder's initRun .catch and the inner hooks' try/catch),
   * and do not block the interface. The headline "Failed to load marketplace" and
   * the blank interface are what this fixes.
   */
  authenticated?: boolean;
  children: React.ReactNode;
}

/**
 * Single canonical wrapper for "I'm rendering a publication preview" - used by
 * the marketplace preview page, the chat ApplicationVisualizeCard side-panel,
 * the marketplace card thumbnails, and any future inline preview surface.
 *
 * Invariant: inside this shell every data fetch routes through the public
 * showcase endpoints (via {@code getActivePublicPreview()}) regardless of who
 * is logged in. The publisher viewing their own publication sees exactly what
 * an anonymous visitor sees - the frozen snapshot, no live tenant data.
 *
 * Mounts:
 * 1. {@code PublicationSnapshotProvider} - populates the module-level store
 *    with {planSnapshot, publicationId, showcaseRunId} so every gated hook
 *    (useInterfaceRender, useEpochStateViewing, WorkflowRunManager, …) sees
 *    {@code getActivePublicPreview()} return non-null and routes to the
 *    {@code /api/publications/by-id/.../*} public endpoints.
 * 2. {@code WorkflowModeProvider} - readOnly + initialRunId so the canvas
 *    locks to run mode against the showcase clone.
 * 3. {@code WorkflowRunProvider} - only when {@code withRunProvider=true}.
 */
export function PublicationPreviewShell({
  publication,
  withRunProvider = true,
  remote = false,
  authenticated = false,
  children,
}: PublicationPreviewShellProps) {
  const showcaseRunId = publication.showcaseRunId ?? null;
  // The published plan's reading direction is its identity: pin it so the preview
  // canvas renders the way the publisher authored it, NOT the viewer's own workflow
  // preference. `forcedDirection` also makes the in-canvas toggle a no-op, matching
  // the read-only preview. Old plans (pre-feature) have no key: fall back to
  // horizontal, the historical layout every legacy canvas was positioned in.
  const rawDir = (publication.planSnapshot as { layoutDirection?: string } | null)?.layoutDirection;
  const previewDirection = isWorkflowLayoutDirection(rawDir)
    ? rawDir
    : DEFAULT_WORKFLOW_LAYOUT_DIRECTION;
  const inner = (
    <WorkflowLayoutDirectionProvider forcedDirection={previewDirection}>
      <WorkflowModeProvider
        workflowId={publication.workflowId}
        initialRunId={showcaseRunId ?? undefined}
        readOnly
      >
        {withRunProvider ? <WorkflowRunProvider>{children}</WorkflowRunProvider> : children}
      </WorkflowModeProvider>
    </WorkflowLayoutDirectionProvider>
  );

  return (
    <PublicationSnapshotProvider
      planSnapshot={publication.planSnapshot}
      publicationId={publication.id}
      showcaseRunId={showcaseRunId}
      remote={remote}
      authenticated={authenticated}
    >
      {inner}
    </PublicationSnapshotProvider>
  );
}
