'use client';

import React, { useCallback, useMemo, useRef, useState } from 'react';
import { Info } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { cn } from '@/lib/utils';
import { InfoPopover } from '@/components/ui/info-popover';
import { PublisherAvatar } from '@/components/marketplace/PublisherAvatar';
import { formatRelativeDateI18n, formatUtcDateTime } from '@/lib/utils/dateFormatters';
import { useWorkspaceMembers, type WorkspaceRoster } from '@/hooks/useWorkspaceMembers';
import type { ResourceEditor } from '@/lib/api/orchestrator/types';

export interface ResourceInfoPopoverProps {
  /**
   * Identifies the resource being described. It is what tells this control that it is now
   * pointing at something else: the breadcrumb reuses ONE instance across in-app navigation
   * (its crumbs are keyed by position), so without this the editor list fetched for workflow
   * A stays on screen under workflow B's name. Omit it only where the control is mounted per
   * resource and never reused, such as a keyed card grid.
   */
  resourceKey?: string;
  /**
   * The resource's name, used ONLY to name this control for assistive tech. A list page paints
   * one of these per card, and without it a screen reader reads "Details, button" twenty-four
   * times over with nothing tying any of them to a resource - on a control whose whole purpose
   * is to say which resource it is about.
   */
  resourceName?: string | null;
  /**
   * The owner's user id - the resource row's `tenantId`, which every resource carries because
   * it is what the backend scopes reads on. Absent when the list DTO does not expose it, in
   * which case the "created by" line is omitted rather than guessed.
   */
  ownerId?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
  /**
   * Loads the people who edited this resource. Called at most ONCE per resource, the first
   * time the popover opens, so a grid of cards costs nothing until one is actually consulted.
   *
   * <p>Passed in rather than derived from a resource type: only workflows keep an edit history
   * today (their plan versions), and a generic component must not know that.
   */
  loadEditors?: () => Promise<ResourceEditor[]>;
  /** `card` sits in a card footer; `breadcrumb` sits beside the crumb's pencil/star. */
  variant?: 'card' | 'breadcrumb';
  /** Extra classes for the trigger button. */
  className?: string;
  /** Which side the popover opens on. Card footers open upward, breadcrumbs downward. */
  side?: 'top' | 'bottom' | 'left' | 'right';
  align?: 'start' | 'center' | 'end';
  /**
   * Notified when the popover opens or closes. A host that only reveals this control on hover
   * needs it: without it, moving the pointer into the popover (which is portalled out of the
   * host) reads as leaving, and the trigger disappears from under the panel.
   */
  onOpenChange?: (open: boolean) => void;
  'data-testid'?: string;
}

/**
 * "Who made this, and who has been working on it?" - a resource's attribution as a popover.
 *
 * <p>The same control in both places it is offered: at the end of a card's footer meta row,
 * and beside the breadcrumb's rename pencil while a resource is open. One component, so the
 * two can never drift into saying different things about the same resource.
 *
 * <p><b>Costs nothing until opened.</b> Timestamps and the owner id are already on the row the
 * caller rendered, so the button paints with no request. On first open it fetches the workspace
 * roster (shared by every other popover on the page thereafter) and the editor list, and each
 * person's avatar is one browser image request against an endpoint cached for a day - so a
 * panel showing an owner and a handful of editors settles into nothing on reopen.
 *
 * <p><b>Attribution is deliberately conservative.</b> "Created by" is the row's owner, which is
 * recorded. There is NO "modified by": `updated_at` is bumped by runs and row writes as well
 * as by edits, so naming whoever bumped it last would routinely credit the wrong person. Where
 * a real edit history exists it arrives through {@link ResourceInfoPopoverProps.loadEditors}
 * and is shown as its own section.
 */
export function ResourceInfoPopover({
  resourceKey,
  resourceName,
  ownerId,
  createdAt,
  updatedAt,
  loadEditors,
  variant = 'card',
  className,
  side,
  align = 'end',
  onOpenChange,
  'data-testid': testId,
}: ResourceInfoPopoverProps) {
  const t = useTranslations('resourceInfo');
  const tRuns = useTranslations('runs');
  const [open, setOpen] = useState(false);
  // The editor answer is STAMPED with the resource it belongs to, and read back only when
  // the stamp still matches. That is what makes a reused instance safe: the breadcrumb keys
  // its crumbs by position and lives in a header that survives in-app navigation, so ONE
  // instance describes workflow A and then workflow B. A mismatched stamp derives to "no
  // answer yet" - no reset effect, and therefore no window in which A's people are on screen
  // under B's name.
  const [editorsState, setEditorsState] = useState<EditorsState>({ list: null, loading: false });
  // The resource we have already asked about, BOXED: `resourceKey` is itself optional, so a
  // bare `undefined` ref could not tell "never asked" from "asked about the resource with no
  // key" - and every caller that omits the key would then never ask at all. Null is
  // "never asked". One request per resource, ever: a popover reopened after a failed load
  // would otherwise re-ask on every open, the worst possible retry cadence.
  const requestedKey = useRef<{ key?: string } | null>(null);

  const answersThisResource = editorsState.key === resourceKey;
  const editors = answersThisResource ? editorsState.list : null;
  const editorsLoading = answersThisResource && editorsState.loading;

  // The roster only starts loading once this popover has been opened - `enabled` is what keeps
  // a 40-card grid from fetching it 40 times over before anyone asks a question.
  const roster = useWorkspaceMembers(open);

  const handleOpenChange = useCallback((next: boolean) => {
    setOpen(next);
    onOpenChange?.(next);
    if (!next || !loadEditors) return;
    if (requestedKey.current !== null && requestedKey.current.key === resourceKey) return;
    const askedFor = resourceKey;
    requestedKey.current = { key: askedFor };
    setEditorsState({ key: askedFor, list: null, loading: true });

    /**
     * Land the answer only if it is still the answer to the question on screen.
     *
     * <p>A slow load for resource A can resolve AFTER the control has moved to B and B's own
     * list has painted. Writing it anyway would blank B - the stamp would no longer match, so
     * it derives to "no answer" - while `requestedKey` still named B, so the
     * one-request-per-resource rule would refuse to ask again and B's editors would be gone for
     * the life of this instance. Dropping the superseded answer instead leaves B's own request
     * (already in flight, or still to come) as the only thing that can land.
     */
    const settle = (list: ResourceEditor[]) => {
      if (requestedKey.current?.key !== askedFor) return;
      setEditorsState({ key: askedFor, list, loading: false });
    };
    loadEditors()
      .then((list) => settle(list ?? []))
      // Best-effort: an editor list that cannot be fetched leaves the section out. The owner
      // and the dates, which are already in hand, still render.
      .catch(() => settle([]));
  }, [loadEditors, onOpenChange, resourceKey]);

  const owner = useMemo(() => resolvePerson(ownerId, roster, null), [ownerId, roster]);

  const hasAnything = !!ownerId || !!createdAt || !!updatedAt || !!loadEditors;

  // "Details for Nightly report" when the caller knows the name, plain "Details" when it does
  // not. Both the trigger and the panel carry it: Radix gives the panel role="dialog", and an
  // unnamed dialog announces as "dialog" and nothing more.
  const accessibleName = resourceName
    ? t('labelFor', { name: resourceName })
    : t('label');

  // Hover colours are written as arbitrary values (`hover:text-[var(--text-primary)]`), NOT as
  // `hover:text-theme-primary`. The `*-theme-*` classes are hand-written CSS rather than
  // Tailwind theme colours, so v4 emits the base class and NO variant of it: the shorthand
  // reads as styled and paints nothing. The ground moves ONE step along the surface ladder
  // (`--bg-secondary`), which is the convention `ui/panel-tab.ts` documents.
  const triggerClass = useMemo(() => (
    variant === 'breadcrumb'
      // Sized and coloured like the crumb's own star/pencil, so the two read as one pair of
      // affordances rather than a button parked next to an icon.
      ? cn(
        'ml-1 inline-flex items-center justify-center rounded p-0.5 flex-shrink-0 transition-colors',
        open ? 'text-theme-primary' : 'text-theme-muted hover:text-[var(--text-primary)]',
        className,
      )
      // The card footer's other control (the relations menu) to the exact pixel: same square,
      // same radius, same hover, and the same 28px touch target. That target is taller than the
      // text-xs line it sits on, so the meta row grows to fit it - exactly as it already does
      // on any workflow card that has relations. Trying to claw the height back with a negative
      // margin only makes the row a THIRD height, which is worse than one honest one.
      : cn(
        'inline-flex items-center justify-center h-7 w-7 rounded-xl',
        'text-theme-secondary transition-colors hover:bg-[var(--bg-secondary)] hover:text-[var(--text-primary)]',
        className,
      )
  ), [variant, className, open]);

  // A card footer sits at the bottom of its card, a crumb at the top of the page: each opens
  // away from its own edge.
  const resolvedSide = side ?? (variant === 'card' ? 'top' : 'bottom');

  if (!hasAnything) return null;

  const showEditorsSection = !!loadEditors && (editorsLoading || (editors !== null && editors.length > 0));

  return (
    <InfoPopover
      label={accessibleName}
      open={open}
      onOpenChange={handleOpenChange}
      side={resolvedSide}
      align={align}
      trigger={
        <button
          type="button"
          data-testid={testId ?? 'resource-info-trigger'}
          aria-label={accessibleName}
          title={t('label')}
          className={triggerClass}
          // Both hosts are themselves clickable (a card opens the resource, a crumb opens the
          // rename modal): opening this must not also trigger them. stopPropagation ONLY -
          // never preventDefault. Radix composes the trigger's own toggle AFTER the child's
          // handler and skips it when the event is already defaultPrevented, so a
          // preventDefault here makes the button open nothing, silently.
          onClick={(e) => e.stopPropagation()}
          onMouseDown={(e) => e.stopPropagation()}
        >
          <Info className={cn('shrink-0', variant === 'breadcrumb' ? 'w-3 h-3' : 'h-3.5 w-3.5')} />
        </button>
      }
      // The shared "i" layer (z-[100001]), not the stock popover z-[64] this panel used to
      // keep: every info panel now opens on one layer, above any dialog it can be opened
      // from, so a breadcrumb or card "i" behaves like every other one. It is transient and
      // closes on the next outside press, so it never sits over a dialog opened after it.
      contentClassName="w-[280px] max-h-[320px] p-1.5 text-sm leading-normal text-theme-primary border-gray-300/70 dark:border-gray-600/70"
      contentProps={{ sideOffset: 6 }}
      contentTestId="resource-info-popover"
    >
      {ownerId && owner.state !== 'unresolvable' && (
        <PersonRow
          label={t('createdBy')}
          person={owner}
          detail={createdAt ? formatUtcDateTime(createdAt) : undefined}
          testId="resource-info-owner"
        />
      )}

      {/* No owner to name, or no workspace to name them in: state the fact we do have rather
          than an attribution we cannot stand behind. */}
      {(!ownerId || owner.state === 'unresolvable') && createdAt && (
        <FactRow
          label={t('created')}
          value={formatRelativeDateI18n(createdAt, tRuns)}
          detail={formatUtcDateTime(createdAt)}
          testId="resource-info-created"
        />
      )}

      {updatedAt && (
        <FactRow
          label={t('lastModified')}
          value={formatRelativeDateI18n(updatedAt, tRuns)}
          detail={formatUtcDateTime(updatedAt)}
          testId="resource-info-modified"
        />
      )}

      {showEditorsSection && (
        <>
          <div className="my-1 border-t border-gray-200 dark:border-gray-700" />
          <div className="flex items-center gap-1.5 px-2.5 pt-1 pb-0.5">
            <span className="text-sm font-medium text-theme-secondary">{t('editorsTitle')}</span>
            {editors && editors.length > 0 && (
              // The numeral for sighted readers, the words for everyone else. An `aria-label`
              // on a bare span is dropped by most screen readers (role=generic takes no name
              // from the author), so the text has to BE there rather than be described.
              <span className="ml-auto text-xs text-theme-muted tabular-nums">
                <span aria-hidden="true">{editors.length}</span>
                <span className="sr-only">{t('editorCount', { count: editors.length })}</span>
              </span>
            )}
          </div>
          {/* What counts as an edit, as text rather than a `title` that neither a touch
              screen nor a screen reader can reach. Worded without naming the resource: this
              component does not know what it is describing, and only the caller that has an
              edit history to offer decides that. */}
          <p className="px-2.5 pb-1 text-xs text-theme-muted">{t('editorsHint')}</p>
          {editorsLoading && (
            <div
              className="px-2.5 py-1.5 text-sm text-theme-muted"
              role="status"
              aria-live="polite"
              data-testid="resource-info-editors-loading"
            >
              {t('editorsLoading')}
            </div>
          )}
          {!editorsLoading && (editors ?? []).map((editor) => (
            <PersonRow
              key={editor.userId}
              person={resolvePerson(editor.userId, roster, editor.displayName ?? null)}
              detail={editor.editedAt ? formatRelativeDateI18n(editor.editedAt, tRuns) : undefined}
              badge={editor.editCount > 1 ? t('editCount', { count: editor.editCount }) : undefined}
              testId={`resource-info-editor-${editor.userId}`}
            />
          ))}
        </>
      )}
    </InfoPopover>
  );
}

/**
 * The editor answer, stamped with the resource it describes.
 *
 * <p>`key` is compared to the current `resourceKey` on every render: a mismatch means this
 * answer is about something else and reads as no answer at all. Deriving it beats resetting
 * it in an effect, which would paint the stale list for one frame first.
 */
interface EditorsState {
  key?: string;
  list: ResourceEditor[] | null;
  loading: boolean;
}

/** What the popover knows about one person, once the roster (and any fallback) is applied. */
interface ResolvedPerson {
  userId: string;
  /** Name to show. Meaningful only in the `resolved` state. */
  name: string;
  avatarUrl: string | null;
  /**
   * `resolved` - we have a name. `pending` - the roster has not answered yet, so we have no
   * grounds to say anything. `unknown` - the roster answered and this id is not in it.
   * `unresolvable` - the question could not be put at all: no active workspace, or the roster
   * request failed. Either way no answer is coming without a retry.
   *
   * <p>The four are kept apart because collapsing any two of them states something false:
   * `pending` read as `unknown` labels every owner a stranger for the split second before the
   * roster lands, and `unresolvable` read as `pending` leaves a skeleton pulsing forever on an
   * answer that is never coming.
   */
  state: 'resolved' | 'pending' | 'unknown' | 'unresolvable';
}

/**
 * Resolve a user id to a name and a face, preferring the workspace roster.
 *
 * <p>The roster is preferred because it is the workspace's own answer, and it is free once
 * loaded - but it only knows CURRENT members. `fallbackName` is what the backend resolved for
 * someone who has since left, the case where "who touched this" is most worth answering. The
 * FACE comes from neither: {@link PublisherAvatar} resolves it from the user id, so it works
 * for a departed editor as well as for a colleague.
 */
function resolvePerson(
  userId: string | null | undefined,
  roster: WorkspaceRoster,
  fallbackName: string | null,
): ResolvedPerson {
  const id = userId ? String(userId) : '';
  const member = id && roster.members ? roster.members.get(id) : undefined;
  if (member) {
    return { userId: id, name: member.displayName, avatarUrl: member.avatarUrl, state: 'resolved' };
  }
  if (fallbackName) {
    return { userId: id, name: fallbackName, avatarUrl: null, state: 'resolved' };
  }
  if (roster.members) {
    return { userId: id, name: '', avatarUrl: null, state: 'unknown' };
  }
  return { userId: id, name: '', avatarUrl: null, state: roster.answered ? 'unresolvable' : 'pending' };
}

function PersonRow({
  label,
  person,
  detail,
  badge,
  testId,
}: {
  label?: string;
  person: ResolvedPerson;
  detail?: string;
  badge?: string;
  testId: string;
}) {
  const t = useTranslations('resourceInfo');
  // The row is kept in every non-resolved state rather than dropped: the resource WAS made or
  // edited by someone, and hiding the line would read as "nobody", which is a different claim.
  const pending = person.state === 'pending';
  // Each state gets its own words, because each is a different fact:
  //  - `unknown`: the roster answered and this id is not in it. "Not in this workspace", NOT
  //    "former member" - the roster answering no can also mean the id is recorded in a form it
  //    is not keyed by, and calling a present colleague a departed one is the worse error.
  //  - `unresolvable`: nobody could be asked at all (no workspace, or the roster failed). The
  //    owner row is dropped in that case, but an editor row cannot be: they did edit it. So it
  //    names the id, which is the only true thing left to say, rather than rendering a blank
  //    name next to a blank avatar.
  const name = person.state === 'unknown'
    ? t('unknownPerson')
    : person.state === 'unresolvable'
      ? (person.userId || t('unknownPerson'))
      : person.name;

  return (
    <div className="px-2.5 py-1.5" data-testid={testId} data-state={person.state}>
      {label && <div className="text-sm font-medium text-theme-secondary mb-1">{label}</div>}
      <div className="flex items-center gap-2 min-w-0">
        {pending ? (
          <span className="h-6 w-6 shrink-0 rounded-full bg-theme-secondary animate-pulse" aria-hidden="true" />
        ) : (
          // The app's PERSON avatar, which resolves from the user id alone and falls back to
          // initials then to a generic icon. Reaching for it rather than the roster's
          // `avatarUrl` is what gives a face to someone who has LEFT the workspace - the very
          // case where "who touched this" is worth asking, and the one the roster cannot
          // answer. (The workspace avatar component next door is for ORGANISATIONS.)
          <PublisherAvatar userId={person.userId} name={name} size={24} variant="neutral" />
        )}
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-1.5 min-w-0">
            {pending ? (
              <span className="h-3.5 w-24 rounded bg-theme-secondary animate-pulse" aria-hidden="true">
                {/* The words, not a label: a live region announces its CONTENT, so a named but
                    empty one announces nothing at all. */}
                <span className="sr-only" role="status" aria-live="polite">{t('resolvingPerson')}</span>
              </span>
            ) : (
              <span className={cn(
                'text-sm truncate',
                person.state === 'resolved' ? 'text-theme-primary' : 'text-theme-muted italic',
              )}>
                {name}
              </span>
            )}
            {badge && <span className="text-xs text-theme-muted shrink-0 tabular-nums">{badge}</span>}
          </div>
          {detail && <div className="text-xs text-theme-muted truncate">{detail}</div>}
        </div>
      </div>
    </div>
  );
}

function FactRow({
  label,
  value,
  detail,
  testId,
}: {
  label: string;
  value: string;
  detail?: string;
  testId: string;
}) {
  return (
    <div className="px-2.5 py-1.5" data-testid={testId}>
      <div className="text-sm font-medium text-theme-secondary mb-0.5">{label}</div>
      <div className="text-sm text-theme-primary">{value}</div>
      {detail && <div className="text-xs text-theme-muted">{detail}</div>}
    </div>
  );
}
