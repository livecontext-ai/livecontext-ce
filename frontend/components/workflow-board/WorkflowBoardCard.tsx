'use client';

import React, { useState } from 'react';
import { useTranslations } from 'next-intl';
// Locale-aware router (next-intl) - card navigation must preserve the active locale.
import { useRouter } from '@/i18n/navigation';
import { AlertTriangle, Calendar, Clock, Globe, MessageSquareQuote, Workflow as WorkflowIcon } from 'lucide-react';
import { RunApprovalsDialog } from '@/components/approvals/RunApprovalsDialog';
import { WorkflowNodeIcons } from '@/components/WorkflowNodeIcons';
import { ShowcasePreview } from '@/components/marketplace/ShowcasePreview';
import { VisibilityBadge } from '@/components/ui/VisibilityBadge';
import { formatRelativeDate } from '@/lib/utils/dateFormatters';
import type { WorkflowBoardCard as WorkflowBoardCardType } from '@/lib/api/orchestrator/types';

interface WorkflowBoardCardProps {
  card: WorkflowBoardCardType;
  isDragging?: boolean;
  onDragStart: () => void;
}

export function WorkflowBoardCard({ card, isDragging, onDragStart }: WorkflowBoardCardProps) {
  const t = useTranslations('workflowBoard');
  // Reuse the exact "shared / in review / rejected" wording the /app/workflow list uses, rather
  // than duplicating keys into the workflowBoard namespace.
  const tw = useTranslations('workflow');
  const router = useRouter();

  const nodeCount = card.nodeIcons?.length ?? 0;

  // Application rows (sourcePublicationId set - acquired APPLICATION-type rows, plus the publisher's
  // own published-as-application workflows on the applications board) preview their live interface
  // instead of the workflow-style node icons, reusing the exact showcase thumbnail the marketplace
  // renders. If that render fails (no showcase, retention 404, cross-tenant) we fall back to the
  // node-icon view so the card never renders empty.
  const [previewFailed, setPreviewFailed] = useState(false);
  // Needs-review cards can open the run's approval review modal in place,
  // without navigating into the workflow.
  const [reviewOpen, setReviewOpen] = useState(false);
  const canReviewApprovals = card.column === 'needsReview' && !!card.productionRunId;
  const showInterfacePreview = !!card.sourcePublicationId && !previewFailed;
  // Own published-as-application rows carry the publication's showcase run + interface → render via
  // the AUTHENTICATED per-run path, which works at ANY publication visibility (the run is the
  // caller's own) - so a private / in-review / rejected app still previews, mirroring
  // /app/applications. Acquired rows (the run belongs to the publisher → cross-tenant) have no
  // showcase ids here and fall back to the publication-scoped showcase render.
  const useShowcaseRun = !!card.showcaseRunId && !!card.showcaseInterfaceId;
  // Acquired/legacy rows render via the publication-scoped showcase (publicationId path). A LOCAL
  // acquired app reads it through the AUTHENTICATED endpoint so the acquirer's receipt admits it
  // even when the publisher made the source publication private / in-review (the anonymous /by-id
  // path 403s then, dropping the card onto the node-icon tile). A cloud-sourced (remote) acquisition
  // on a cloud-linked CE has no local publication, so its showcase is read through the cloud proxy
  // (a local render would 404 on the cloud-only pub id). Own apps (useShowcaseRun) ignore both.
  const acquiredShowcase = showInterfacePreview && !useShowcaseRun;

  return (
    <>
    <div
      draggable
      onDragStart={(e) => {
        e.dataTransfer.effectAllowed = 'move';
        e.dataTransfer.setData('text/plain', card.workflowId);
        onDragStart();
      }}
      onClick={() => {
        // Applications open their own surface (run viewing handled there). Regular workflows -
        // and legacy apps with no source publication - open the live production run if one
        // exists, else the builder.
        if (card.sourcePublicationId) {
          router.push(`/app/applications/${card.sourcePublicationId}`);
        } else if (card.productionRunId && card.column !== 'draft') {
          router.push(`/app/workflow/${card.workflowId}/run/${card.productionRunId}`);
        } else {
          router.push(`/app/workflow/${card.workflowId}`);
        }
      }}
      className={`group relative rounded-xl border border-theme overflow-hidden transition-colors duration-200 cursor-pointer ${
        isDragging ? 'opacity-40 scale-95' : 'hover:bg-slate-50 dark:hover:bg-slate-800/60'
      }`}
    >
      {/* Full-card dot pattern background */}
      <div
        className="absolute inset-0 dark:hidden"
        style={{ backgroundImage: 'radial-gradient(circle, #cbd5e1 1px, transparent 1px)', backgroundSize: '10px 10px' }}
      />
      <div
        className="hidden dark:block absolute inset-0"
        style={{ backgroundImage: 'radial-gradient(circle, #475569 1px, transparent 1px)', backgroundSize: '10px 10px' }}
      />

      {/* Top area - application interface preview, else the workflow node icons. */}
      {showInterfacePreview ? (
        <div className="relative z-10">
          <ShowcasePreview
            runId={useShowcaseRun ? card.showcaseRunId! : undefined}
            interfaceId={useShowcaseRun ? card.showcaseInterfaceId! : undefined}
            // Own app → authenticated per-run render (publicationId omitted). Acquired/legacy →
            // publication-scoped showcase render via the publication id (cross-tenant-safe).
            publicationId={useShowcaseRun ? undefined : card.sourcePublicationId!}
            // LOCAL acquired app → receipt-gated authenticated showcase (admits private/in-review
            // sources). Cloud-sourced acquired app → cloud proxy. Both off for own apps.
            authenticated={acquiredShowcase && !card.remote}
            remote={acquiredShowcase && !!card.remote}
            hidePagination
            suppressErrorUi
            onError={() => setPreviewFailed(true)}
            // Square the thumbnail's bottom corners so it meets the footer flush; the
            // rounded top is handled by the card's outer `overflow-hidden`.
            className="rounded-b-none"
          />
        </div>
      ) : (
        <div className="relative z-10 h-[52px] flex items-center justify-center">
          {card.nodeIcons && card.nodeIcons.length > 0 ? (
            <WorkflowNodeIcons nodeIcons={card.nodeIcons} totalNodeCount={nodeCount} compact />
          ) : (
            <div className="w-7 h-7 bg-theme-secondary rounded-full flex items-center justify-center">
              <WorkflowIcon className="w-3.5 h-3.5 text-theme-primary" />
            </div>
          )}
        </div>
      )}

      {/* Footer */}
      <div className="relative z-10 bg-white/80 dark:bg-slate-800/80 backdrop-blur-sm border-t border-theme px-2.5 py-1.5 rounded-b-xl">
        <div className="flex items-center gap-1.5">
          <span className="text-xs font-medium text-theme-primary truncate">{card.name}</span>
          {card.pinnedVersion != null && (
            <span className="shrink-0 text-[10px] font-mono px-1 py-0.5 rounded bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300 leading-none">
              v{card.pinnedVersion}
            </span>
          )}
          {/* Publication marker. Workflows board: in review (amber) / rejected (red) take
              precedence (the app isn't live yet); otherwise a shared workflow shows its public /
              private visibility (Globe = public, Lock = private), falling back to the generic
              "shared" Globe when visibility is unknown. Applications board: app cards carry no
              status marker here, so the visibility badge (own apps only - `card.visibility` set) is
              the public / private indicator; acquired app cards have no visibility → no marker. */}
          {!card.sourcePublicationId && card.publicationStatus === 'PENDING_REVIEW' ? (
            <span className="shrink-0 text-amber-600 dark:text-amber-400" title={tw('sharedInReview')}>
              <Clock className="h-3 w-3" />
            </span>
          ) : !card.sourcePublicationId && card.publicationStatus === 'REJECTED' ? (
            <span className="shrink-0 text-red-600 dark:text-red-400" title={tw('sharedRejected')}>
              <AlertTriangle className="h-3 w-3" />
            </span>
          ) : card.visibility ? (
            <VisibilityBadge visibility={card.visibility} />
          ) : !card.sourcePublicationId && card.isPublished ? (
            <span className="shrink-0 text-theme-muted" title={tw('shared')}>
              <Globe className="h-3 w-3" />
            </span>
          ) : null}
        </div>
        {card.description && (
          <p className="text-[11px] text-theme-muted truncate mt-0.5">{card.description}</p>
        )}
        <div className="flex items-center gap-1 mt-0.5 text-[11px] text-theme-muted">
          {card.lastExecutedAt && (
            <>
              <Clock className="h-2.5 w-2.5" />
              <span>{formatRelativeDate(card.lastExecutedAt)}</span>
            </>
          )}
          {card.runCount > 0 && (
            <>
              {card.lastExecutedAt && <span className="text-slate-300 dark:text-slate-600">&middot;</span>}
              <span>{t('card.runs', { count: card.runCount })}</span>
            </>
          )}
          {/* Epoch count for the production run - only shown when the workflow has
              a pinned version that has actually fired at least once. Lets the
              board surface "how active is this in prod" without opening the run. */}
          {card.productionRunEpochCount != null && card.productionRunEpochCount > 0 && (
            <>
              <span className="text-slate-300 dark:text-slate-600">&middot;</span>
              <Calendar className="h-2.5 w-2.5" />
              <span>{t('card.epochs', { count: card.productionRunEpochCount })}</span>
            </>
          )}
          {card.productionRunStatus && (
            <>
              <span className="text-slate-300 dark:text-slate-600">&middot;</span>
              <RunStatusBadge status={card.productionRunStatus} t={t} />
            </>
          )}
        </div>
        {canReviewApprovals && (
          <button
            type="button"
            data-testid="board-card-review-approvals"
            onClick={(e) => {
              // Never fall through to the card's open-workflow navigation.
              e.stopPropagation();
              setReviewOpen(true);
            }}
            className="mt-1.5 w-full flex items-center justify-center gap-1 px-2 py-1 rounded-lg text-[11px] font-medium bg-amber-100 text-amber-700 hover:bg-amber-200 dark:bg-amber-500/20 dark:text-amber-300 dark:hover:bg-amber-500/30 transition-colors"
          >
            <MessageSquareQuote className="h-3 w-3" />
            {t('card.reviewApprovals')}
          </button>
        )}
      </div>
    </div>
    {/* Sibling of the card root, NOT a child: React synthetic events bubble
        through portals up the REACT tree, so a dialog nested in the card div
        would re-trigger the card's open-workflow onClick. Mounted lazily so
        closed cards never fetch signals. */}
    {canReviewApprovals && reviewOpen && (
      <RunApprovalsDialog
        runId={card.productionRunId!}
        open={reviewOpen}
        onOpenChange={setReviewOpen}
      />
    )}
    </>
  );
}

function RunStatusBadge({ status, t }: { status: string; t: (key: string) => string }) {
  const lower = status.toLowerCase();
  if (lower === 'waiting_trigger') {
    return (
      <span className="inline-flex items-center gap-0.5 text-emerald-600 dark:text-emerald-400">
        <span className="h-1.5 w-1.5 rounded-full bg-emerald-500 animate-pulse" />
        {t('status.live')}
      </span>
    );
  }
  if (lower === 'running') {
    return (
      <span className="inline-flex items-center gap-0.5 text-blue-600 dark:text-blue-400">
        <span className="h-1.5 w-1.5 rounded-full bg-blue-500 animate-pulse" />
        {t('status.running')}
      </span>
    );
  }
  if (lower === 'awaiting_signal') {
    return (
      <span className="inline-flex items-center gap-0.5 text-amber-600 dark:text-amber-400">
        <span className="h-1.5 w-1.5 rounded-full bg-amber-500 animate-pulse" />
        {t('status.needsApproval')}
      </span>
    );
  }
  if (lower === 'cancelled') {
    return <span className="text-red-500 dark:text-red-400">{t('status.paused')}</span>;
  }
  if (lower === 'completed') {
    return <span className="text-green-600 dark:text-green-400">{t('status.completed')}</span>;
  }
  if (lower === 'failed') {
    return <span className="text-red-600 dark:text-red-400">{t('status.failed')}</span>;
  }
  if (lower === 'timeout') {
    return <span className="text-amber-600 dark:text-amber-400">{t('status.timeout')}</span>;
  }
  return <span>{status}</span>;
}
