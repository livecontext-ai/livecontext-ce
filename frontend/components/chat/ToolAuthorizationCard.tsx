'use client';

import React, { useState, useEffect } from 'react';
import { useTranslations } from 'next-intl';
import { PackagePlus, Play, CheckCircle, Ban, Rocket, PowerOff, CalendarClock } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import { PublicationCard, PublicationCardSkeleton } from '@/components/marketplace/PublicationCard';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { workflowService } from '@/lib/api/orchestrator/workflow.service';
import { agentService } from '@/lib/api/orchestrator/agent.service';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';
import type { PendingToolAuthorization } from '@/contexts/StreamingContext';

/**
 * How long the card waits for a name before becoming answerable anyway. Short on purpose:
 * the person is looking at a held call, and an unanswerable card is worse than an unnamed one.
 */
const NAME_FETCH_MAX_WAIT_MS = 4000;

export interface ToolAuthorizationCardProps {
  /** Conversation ID */
  conversationId: string;
  /** Pending tool-authorization request */
  pendingAuthorization: PendingToolAuthorization;
  /**
   * Approve - receives the rule, `blanket` (whether the user ticked "ne plus
   * demander dans cette conversation", turning on `chatConfig.autoAuthorizeTools`),
   * and the card's toolCallId so the caller can clear THIS card without
   * suppressing a sibling card of the same rule (F16).
   */
  onApproved?: (rule: string, blanket: boolean, toolCallId?: string) => void;
  /** Decline - receives the rule and the card's toolCallId (see onApproved). */
  onDenied?: (rule: string, toolCallId?: string) => void;
  className?: string;
}

/**
 * Inline action card shown when the chat agent requests a sensitive action.
 *
 * Neutral (not a warning), styled like the marketplace cards. For
 * `application:acquire` it shows the actual marketplace publication preview (the
 * exact {@link PublicationCard} visual, minus its install CTA - the card's own
 * "Install" button performs it). The copy adapts: install vs run. A "ne plus
 * demander dans cette conversation" checkbox flips the per-conversation
 * auto-authorize toggle.
 */
export function ToolAuthorizationCard({
  pendingAuthorization,
  onApproved,
  onDenied,
  className = '',
}: ToolAuthorizationCardProps) {
  const t = useTranslations('toolAuthorization');
  const [isApproved, setIsApproved] = useState(false);
  const [isDenied, setIsDenied] = useState(false);
  const [pending, setPending] = useState(false);
  const [blanket, setBlanket] = useState(false);

  const rule = pendingAuthorization.rule;
  const isInstall = rule === 'application:acquire';
  const applicationId = pendingAuthorization.applicationId;

  // The subject of the card: what is about to go live, or which cron is being armed.
  // Absent for every rule that names nothing, and for a backend older than this field.
  const subject = pendingAuthorization.subject;

  // The subject's name is the one thing the backend cannot always send: agent-service has
  // no orchestrator client, and on an agent UPDATE the call carries an id rather than a
  // name. Fetch it, exactly as the install card fetches its publication.
  const [fetchedName, setFetchedName] = useState<string | null>(null);
  // Stays true until the answer is in, success or failure. The card is not answerable
  // while it is: approving a pin before its workflow has been named is precisely the
  // outcome this card exists to prevent, and a slow orchestrator is enough to cause it.
  const [nameLoading, setNameLoading] = useState(false);
  const idToName = subject && !subject.name ? subject.id : undefined;
  const kindToName = subject?.kind;
  useEffect(() => {
    if (!idToName || (kindToName !== 'workflow' && kindToName !== 'agent')) return;
    let cancelled = false;
    setNameLoading(true);
    const settle = () => { if (!cancelled) setNameLoading(false); };
    // Waiting for a name must never outlast the call that is being HELD. apiClient's own
    // timeout is 30 s, and on the CLI-bridge route a park can be capped at 25, so a hanging
    // orchestrator would leave the user unable to answer until the hold expired by itself.
    // After this the card is answerable with the id shown; a name that arrives late still
    // replaces it, so nothing is lost by giving up on the wait rather than on the fetch.
    const deadline = setTimeout(settle, NAME_FETCH_MAX_WAIT_MS);
    // The id comes from an LLM-written argument and becomes a path segment.
    const encoded = encodeURIComponent(idToName);
    const request = kindToName === 'workflow'
      ? workflowService.getWorkflow(encoded)
      : agentService.getAgent(encoded);
    request
      .then((resource) => { if (!cancelled) setFetchedName(resource?.name?.trim() || null); })
      // Silent: the copy below simply names nothing, and the id is shown instead.
      .catch(() => { if (!cancelled) setFetchedName(null); })
      .finally(() => { clearTimeout(deadline); settle(); });
    return () => { cancelled = true; clearTimeout(deadline); };
  }, [idToName, kindToName]);

  const subjectName = subject?.name?.trim() || fetchedName || null;
  // When the name could not be resolved, show the id rather than nothing: "Take this
  // workflow off the air?" with no identification at all is a question the user cannot
  // answer, and unpin has no version to fall back on either.
  const unresolvedId = !subjectName && !nameLoading ? subject?.id ?? null : null;

  // Per-action title/subtitle so the user sees a clear description of what the
  // agent is about to do (e.g. continue a paused interface / resolve an approval)
  // rather than the generic "run a sensitive action". Falls back to the generic
  // run copy for any other gated action.
  //
  // The three arming rules each have a NAMED and an unnamed variant instead of one
  // message with an optional placeholder: an empty placeholder leaves a dangling
  // quote or a double space in six languages, and "put "" live?" reads as a bug.
  const titleKey = isInstall ? 'installTitle'
    : rule === 'workflow:continue_interface' ? 'continueInterfaceTitle'
    : rule === 'workflow:resolve_approval' ? 'resolveApprovalTitle'
    : rule === 'workflow:pin' ? (subjectName ? 'pinTitleNamed' : 'pinTitle')
    : rule === 'workflow:unpin' ? (subjectName ? 'unpinTitleNamed' : 'unpinTitle')
    : rule === 'agent:schedule' ? (subjectName ? 'agentScheduleTitleNamed' : 'agentScheduleTitle')
    // Mail is the one approval on this card whose subject is a PERSON. On the generic copy it
    // reads exactly like pinning a workflow, and the only thing telling the reader that an
    // email is about to leave their own address is the raw params dump underneath.
    : rule === 'mailbox:send' ? 'mailboxSendTitle'
    : rule === 'mailbox:delete' ? 'mailboxDeleteTitle'
    : 'runTitle';
  const subtitleKey = isInstall ? 'installSubtitle'
    : rule === 'workflow:continue_interface' ? 'continueInterfaceSubtitle'
    : rule === 'workflow:resolve_approval' ? 'resolveApprovalSubtitle'
    : rule === 'workflow:pin' ? (subject?.version != null ? 'pinSubtitleVersioned' : 'pinSubtitle')
    : rule === 'workflow:unpin' ? 'unpinSubtitle'
    : rule === 'agent:schedule' ? (subject?.cron ? 'agentScheduleSubtitle' : 'agentScheduleSubtitleBare')
    : rule === 'mailbox:send' ? 'mailboxSendSubtitle'
    : rule === 'mailbox:delete' ? 'mailboxDeleteSubtitle'
    : 'runSubtitle';

  // Values the two keys above may reference. next-intl throws on a missing placeholder,
  // so every one a selected key can name is always supplied.
  const copyValues = {
    name: subjectName ?? '',
    version: subject?.version ?? 0,
    cron: subject?.cron ?? '',
    timezone: subject?.timezone ?? 'UTC',
  };

  // Load the publication so we render its marketplace preview instead of raw args.
  // Public (marketplace-safe) fetch - the app may not be owned yet (we're acquiring it).
  const [publication, setPublication] = useState<WorkflowPublication | null>(null);
  const [pubLoading, setPubLoading] = useState(!!applicationId);

  useEffect(() => {
    if (!applicationId) {
      setPubLoading(false);
      return;
    }
    let cancelled = false;
    setPubLoading(true);
    publicationService
      .getPublicationByIdPublic(applicationId)
      .then((p) => { if (!cancelled) setPublication(p as WorkflowPublication); })
      .catch(() => { if (!cancelled) setPublication(null); })
      .finally(() => { if (!cancelled) setPubLoading(false); });
    return () => { cancelled = true; };
  }, [applicationId]);

  const approve = () => {
    setPending(true);
    setIsApproved(true);
    onApproved?.(rule, blanket, pendingAuthorization.toolCallId);
  };

  const deny = () => {
    setIsDenied(true);
    onDenied?.(rule, pendingAuthorization.toolCallId);
  };

  if (isApproved) {
    return (
      <div className={`my-4 ${className}`}>
        <div className="rounded-2xl border border-green-200 dark:border-green-800 bg-green-50 dark:bg-green-900/20 p-4">
          <div className="flex items-center gap-3">
            <div className="flex items-center justify-center w-8 h-8 rounded-xl bg-white dark:bg-slate-800">
              <CheckCircle className="w-4 h-4 text-green-600 dark:text-green-400" />
            </div>
            <div className="flex-1">
              <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100">
                {isInstall ? t('installing') : t('approved')}
              </h3>
              <p className="text-xs text-slate-600 dark:text-slate-400">
                {isInstall ? t('installingMessage') : t('approvedMessage')}
              </p>
            </div>
          </div>
        </div>
      </div>
    );
  }

  if (isDenied) {
    return (
      <div className={`my-4 ${className}`}>
        <div className="rounded-2xl border border-theme bg-theme-secondary/50 p-4">
          <div className="flex items-center gap-3">
            <div className="flex items-center justify-center w-8 h-8 rounded-xl bg-theme-primary">
              <Ban className="w-4 h-4 text-theme-secondary" />
            </div>
            <div className="flex-1">
              <h3 className="text-sm font-semibold text-theme-secondary">{t('denied')}</h3>
              <p className="text-xs text-theme-muted">{t('deniedMessage')}</p>
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className={`my-4 ${className}`} data-testid="tool-authorization-card">
      <div className="rounded-2xl border border-theme bg-gradient-to-br from-[var(--bg-secondary)] to-[var(--bg-tertiary)] p-4 shadow-sm">
        {/* Header */}
        <div className="flex items-center gap-3 mb-3">
          <div className="flex items-center justify-center w-8 h-8 rounded-xl bg-theme-primary border border-theme shrink-0">
            {/* The icon says which KIND of consequence this is: installing, putting
                something live, taking it off the air, arming a schedule, or running
                one thing once. */}
            {isInstall ? (
              <PackagePlus className="w-4 h-4 text-theme-primary" />
            ) : rule === 'workflow:pin' ? (
              <Rocket className="w-4 h-4 text-theme-primary" />
            ) : rule === 'workflow:unpin' ? (
              <PowerOff className="w-4 h-4 text-theme-primary" />
            ) : rule === 'agent:schedule' ? (
              <CalendarClock className="w-4 h-4 text-theme-primary" />
            ) : (
              <Play className="w-4 h-4 text-theme-primary" />
            )}
          </div>
          <div className="min-w-0">
            <h3 className="text-sm font-semibold text-theme-primary">
              {t(titleKey, copyValues)}
            </h3>
            <p className="text-xs text-theme-secondary">
              {t(subtitleKey, copyValues)}
            </p>
            {/* Could not resolve a name: show the id, so the question stays answerable.
                Nothing identifying at all is worse than a raw id. */}
            {unresolvedId && (
              <p className="text-xs text-theme-muted mt-0.5 font-mono truncate"
                 data-testid="tool-authorization-subject-id">
                {unresolvedId}
              </p>
            )}
            {/* The agent is holding this call: say so, otherwise the tool above just looks
                stuck spinning and the user has no reason to connect the two. */}
            {pendingAuthorization.blocking && (
              <p className="text-xs text-theme-muted mt-0.5" data-testid="tool-authorization-waiting">
                {t('waitingForYou')}
              </p>
            )}
          </div>
        </div>

        {/* Application preview - the exact marketplace card, no install CTA (the card's
            own Install button performs it) and non-navigating (pointer-events-none). */}
        {applicationId && (
          <div className="rounded-2xl bg-theme-primary border border-theme p-3">
            {pubLoading ? (
              <PublicationCardSkeleton />
            ) : publication ? (
              <div className="pointer-events-none select-none">
                <PublicationCard publication={publication} />
              </div>
            ) : null}
          </div>
        )}

        {/* Footer: "don't ask again" checkbox + actions */}
        <div className="mt-3 flex items-center justify-between gap-3">
          <label className="flex items-center gap-2 text-xs text-theme-secondary cursor-pointer select-none">
            <input
              type="checkbox"
              checked={blanket}
              onChange={(e) => setBlanket(e.target.checked)}
              disabled={pending}
              className="h-3.5 w-3.5 rounded border-theme accent-[var(--accent-primary)] cursor-pointer"
              data-testid="tool-authorization-dont-ask"
            />
            {t('dontAskAgain')}
          </label>
          <div className="flex gap-2">
            <Button variant="ghost" size="sm" onClick={deny} disabled={pending}>
              {t('deny')}
            </Button>
            {/* Declining stays available while the name resolves - refusing needs no
                identification. Approving does, so it waits. */}
            <Button variant="default" size="sm" onClick={approve} disabled={pending || nameLoading} className="gap-2">
              {pending || nameLoading ? (
                <>
                  <LoadingSpinner size="xs" />
                  {nameLoading && !pending ? t('loadingSubject') : isInstall ? t('installing') : t('approving')}
                </>
              ) : (
                <>{isInstall ? t('install') : t('approve')}</>
              )}
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}

export default ToolAuthorizationCard;
