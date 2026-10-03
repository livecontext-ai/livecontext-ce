'use client';

import React, { useState, useEffect } from 'react';
import { useTranslations } from 'next-intl';
import { PackagePlus, Play, CheckCircle, Ban, Rocket, PowerOff, CalendarClock, ShieldOff } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import { PublicationCard, PublicationCardSkeleton } from '@/components/marketplace/PublicationCard';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { workflowService } from '@/lib/api/orchestrator/workflow.service';
import { agentService } from '@/lib/api/orchestrator/agent.service';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';
import type { PendingToolAuthorization } from '@/contexts/StreamingContext';
import { useCanDriveRuns } from '@/lib/hooks/useCanDriveRuns';

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
  const tCommon = useTranslations('common');
  // Approving runs the held action with the workspace credentials, which the backend refuses
  // to a read-only VIEWER: the card offers them Deny only, and says why. The chat tab can
  // also open inside a public share page, where the share link decides, not the visitor role.
  const canMutate = useCanDriveRuns();
  const [isApproved, setIsApproved] = useState(false);
  const [isDenied, setIsDenied] = useState(false);
  const [pending, setPending] = useState(false);
  const [blanket, setBlanket] = useState(false);

  const rule = pendingAuthorization.rule;
  const isInstall = rule === 'application:acquire';

  // The subject of the card: what is about to go live, which cron is being armed, which
  // workflow / agent / app is about to run, who an email goes to. Absent for a rule that
  // names nothing, a call that omitted the target, and a backend older than this field.
  const subject = pendingAuthorization.subject;

  // acquire carries the publication id at the top level; application:execute names the same
  // kind of id in its subject. Either way the card previews the app instead of a raw id.
  const applicationId = pendingAuthorization.applicationId
    ?? (subject?.kind === 'application' ? subject.id : undefined);

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
  // Only for the kinds a name is looked up for (an API tool's id is a bare UUID that the
  // details below already show). An application is previewed instead, so it shows its id only
  // when that preview could not load: the anonymous publication fetch 404s on a private or
  // unlisted app, which is exactly the user's own app being run.
  const unresolvedId = subjectName || nameLoading ? null
    : kindToName === 'workflow' || kindToName === 'agent' ? subject?.id ?? null
    : null;

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
    // The run rules used to share the bare "Run this action? / a sensitive action" copy, which
    // told the user neither WHAT would run nor WHY they were being asked. Each now names its
    // target when the call carries one, and its subtitle says what the click costs.
    : rule === 'workflow:execute' ? (subjectName ? 'workflowExecuteTitleNamed' : 'workflowExecuteTitle')
    : rule === 'workflow:restart_from_node' ? 'restartFromNodeTitle'
    : rule === 'workflow:run_node' ? 'runNodeTitle'
    : rule === 'application:execute' ? 'applicationExecuteTitle'
    : rule === 'agent:execute' ? (subjectName ? 'agentExecuteTitleNamed' : 'agentExecuteTitle')
    : rule === 'catalog:execute' || rule === 'catalog:call' ? 'catalogExecuteTitle'
    // Removes the safeguard instead of spending anything, so the generic "can spend
    // credits" copy would describe the wrong risk.
    : rule === 'agent:disarm_tool_authorization' ? (subjectName ? 'disarmTitleNamed' : 'disarmTitle')
    : 'runTitle';
  const subtitleKey = isInstall ? 'installSubtitle'
    : rule === 'workflow:continue_interface' ? 'continueInterfaceSubtitle'
    : rule === 'workflow:resolve_approval' ? 'resolveApprovalSubtitle'
    : rule === 'workflow:pin' ? (subject?.version != null ? 'pinSubtitleVersioned' : 'pinSubtitle')
    : rule === 'workflow:unpin' ? 'unpinSubtitle'
    : rule === 'agent:schedule' ? (subject?.cron ? 'agentScheduleSubtitle' : 'agentScheduleSubtitleBare')
    : rule === 'mailbox:send' ? (subject?.to ? 'mailboxSendSubtitleTo' : 'mailboxSendSubtitle')
    : rule === 'mailbox:delete' ? 'mailboxDeleteSubtitle'
    : rule === 'workflow:execute' ? 'workflowExecuteSubtitle'
    : rule === 'workflow:restart_from_node' ? (subject?.node ? 'restartFromNodeSubtitleNamed' : 'restartFromNodeSubtitle')
    : rule === 'workflow:run_node' ? (subject?.type ? 'runNodeSubtitleNamed' : 'runNodeSubtitle')
    : rule === 'application:execute' ? 'applicationExecuteSubtitle'
    : rule === 'agent:execute' ? 'agentExecuteSubtitle'
    : rule === 'catalog:execute' || rule === 'catalog:call' ? 'catalogExecuteSubtitle'
    : rule === 'agent:disarm_tool_authorization' ? 'disarmSubtitle'
    : 'runSubtitle';

  // Values the two keys above may reference. next-intl throws on a missing placeholder,
  // so every one a selected key can name is always supplied.
  const copyValues = {
    name: subjectName ?? '',
    version: subject?.version ?? 0,
    cron: subject?.cron ?? '',
    timezone: subject?.timezone ?? 'UTC',
    node: subject?.node ?? '',
    nodeType: subject?.type ?? '',
    // Every recipient, copies included: naming only `to` would understate who gets the mail.
    to: [subject?.to, subject?.cc, subject?.bcc].filter(Boolean).join(', '),
  };

  // The raw call, behind a disclosure: the copy above says what and why, this says exactly
  // which tool, which action and with which arguments, for the user who wants to check.
  const toolName = pendingAuthorization.toolName || rule.split(':')[0];
  const actionName = pendingAuthorization.action || rule.split(':')[1];
  const paramsText = formatArgsSummary(pendingAuthorization.argsSummary);

  // Load the publication so we render its marketplace preview instead of raw args.
  // Public (marketplace-safe) fetch - the app may not be owned yet (we're acquiring it).
  const [publication, setPublication] = useState<WorkflowPublication | null>(null);
  const [pubLoading, setPubLoading] = useState(!!applicationId);
  // The preview failed (or returned nothing): say which app by id rather than an empty box.
  // Applies to acquire too, which carries its id at the top level rather than in a subject.
  const unpreviewedAppId = applicationId && !pubLoading && !publication ? applicationId : null;

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
            ) : rule === 'agent:disarm_tool_authorization' ? (
              <ShieldOff className="w-4 h-4 text-theme-primary" />
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
            {(unresolvedId || unpreviewedAppId) && (
              <p className="text-xs text-theme-muted mt-0.5 font-mono truncate"
                 data-testid="tool-authorization-subject-id">
                {unresolvedId || unpreviewedAppId}
              </p>
            )}
            {/* The email's subject line, verbatim: with the recipients above, it is what
                tells the user which message is about to leave. */}
            {rule === 'mailbox:send' && subject?.subject && (
              <p className="text-xs text-theme-muted mt-0.5 truncate" data-testid="tool-authorization-mail-subject">
                “{subject.subject}”
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
        {applicationId && (pubLoading || publication) && (
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

        {/* What exactly would run: tool, action, arguments. Collapsed by default so the
            card reads as a question, not a JSON dump. */}
        <details className="mt-3" data-testid="tool-authorization-details">
          <summary className="text-xs text-theme-muted cursor-pointer select-none hover:text-theme-secondary">
            {t('detailsToggle')}
          </summary>
          <div className="mt-2 rounded-xl bg-theme-primary border border-theme p-2.5 space-y-1">
            <p className="text-xs text-theme-secondary">
              {t('toolLabel')}: <span className="font-mono">{toolName}</span>
              {actionName && (
                <> · {t('actionLabel')}: <span className="font-mono">{actionName}</span></>
              )}
            </p>
            {paramsText && (
              <>
                <p className="text-xs font-medium text-theme-secondary">{t('paramsLabel')}</p>
                <pre className="text-xs font-mono text-theme-secondary whitespace-pre-wrap break-all max-h-40 overflow-auto"
                     data-testid="tool-authorization-params">
                  {paramsText}
                </pre>
              </>
            )}
          </div>
        </details>

        {/* Footer: "don't ask again" checkbox + actions */}
        <div className="mt-3 flex items-center justify-between gap-3">
          {!canMutate ? (
            <p className="text-xs text-theme-muted" data-testid="tool-authorization-viewer-read-only">
              {tCommon('viewerReadOnly')}
            </p>
          ) : (
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
          )}
          <div className="flex gap-2">
            <Button variant="ghost" size="sm" onClick={deny} disabled={pending}>
              {t('deny')}
            </Button>
            {/* Declining stays available while the name resolves - refusing needs no
                identification. Approving does, so it waits. */}
            {canMutate && (
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
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

/**
 * Pretty-prints the backend's argument summary. The backend truncates it at 240 characters, so
 * a long call arrives as invalid JSON: that is shown as-is rather than dropped. The `action`
 * key is left out because the line above already names it.
 */
export function formatArgsSummary(summary?: string): string | null {
  if (!summary) return null;
  try {
    const parsed: unknown = JSON.parse(summary);
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      const rest = Object.fromEntries(
        Object.entries(parsed as Record<string, unknown>).filter(([key]) => key !== 'action'),
      );
      return Object.keys(rest).length ? JSON.stringify(rest, null, 2) : null;
    }
    return JSON.stringify(parsed, null, 2);
  } catch {
    return summary;
  }
}

export default ToolAuthorizationCard;
