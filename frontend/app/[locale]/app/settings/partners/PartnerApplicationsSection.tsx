'use client';

import React, { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useLocale, useTranslations } from 'next-intl';
import { Check, ExternalLink, Inbox, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import type { ToastData } from '@/components/Toast';
import { formatUtcDate } from '@/lib/utils/dateFormatters';
import { parsePercent } from '@/lib/partners/parsePercent';
import {
  partnerAdminApi,
  type PartnerApplicationRow,
} from '@/lib/api/services/partner-admin-api.service';

const APPLICATIONS_KEY = ['admin', 'partner-program', 'applications'];

/** Only an http(s) address becomes a link: the value is typed by the applicant. */
function safeHref(website: string | null): string | null {
  return website && /^https?:\/\//i.test(website) ? website : null;
}

/**
 * The queue of partner applications sent from the public /partners page. Approving creates
 * the applicant's partner code (program defaults unless overridden here) and e-mails them;
 * rejecting records an optional note shown to the applicant. Both refresh the code report
 * below, since an approval adds a row to it.
 */
export function PartnerApplicationsSection({
  onDecided,
  onError,
  notify,
  founderOpen = false,
}: {
  onDecided: () => void;
  onError: (err: unknown) => void;
  notify: (toast: Omit<ToastData, 'id'>) => void;
  /** Whether founding partners can still be named: shows the founder choice on approval. */
  founderOpen?: boolean;
}) {
  const t = useTranslations('partnerProgram.applications');
  const locale = useLocale();
  const queryClient = useQueryClient();
  const [showDecided, setShowDecided] = useState(false);

  const status = showDecided ? 'all' : 'pending';
  const applications = useQuery({
    queryKey: [...APPLICATIONS_KEY, status],
    queryFn: () => partnerAdminApi.applications(status),
    retry: false,
  });

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: APPLICATIONS_KEY });
    onDecided();
  };

  const approve = useMutation({
    mutationFn: ({ id, code, percent, founder }: { id: number; code: string; percent: number | undefined; founder: boolean }) =>
      partnerAdminApi.approveApplication(id, {
        code: code.trim() || undefined,
        commission_percent: percent,
        founder: founder || undefined,
      }),
    onSuccess: (res) => {
      notify({
        type: 'success',
        title: t('toasts.approvedTitle'),
        message: `${t('toasts.approvedCode', { code: res.code ?? '' })} ${res.mailed ? t('toasts.mailed') : t('toasts.notMailed')}`,
        duration: 8000,
      });
      refresh();
    },
    onError: (err) => {
      // A refused decision usually means the list is stale (another admin decided first):
      // reload it, and the code report below (the winner may have created a code), so the row
      // shows its real state instead of inviting the same retry.
      refresh();
      onError(err);
    },
  });

  const reject = useMutation({
    mutationFn: ({ id, note }: { id: number; note: string }) =>
      partnerAdminApi.rejectApplication(id, note.trim() || undefined),
    onSuccess: (res) => {
      notify({
        type: 'success',
        title: t('toasts.rejectedTitle'),
        message: res.mailed ? t('toasts.mailed') : t('toasts.notMailed'),
        duration: 6000,
      });
      refresh();
    },
    onError: (err) => {
      refresh();
      onError(err);
    },
  });

  const rows = applications.data?.applications ?? [];
  const busy = approve.isPending || reject.isPending;

  return (
    <section className="bg-theme-secondary rounded-xl p-6" aria-labelledby="partner-applications-title">
      <div className="flex flex-wrap items-start justify-between gap-3 mb-4">
        <div>
          <h3 id="partner-applications-title" className="text-base font-semibold text-theme-primary">{t('title')}</h3>
          <p className="text-sm text-theme-secondary">{t('subtitle')}</p>
        </div>
        <Button size="sm" variant="outline" onClick={() => setShowDecided((v) => !v)}>
          {showDecided ? t('showPending') : t('showDecided')}
        </Button>
      </div>

      {applications.isLoading && <p className="text-sm text-theme-secondary">{t('loading')}</p>}
      {applications.isError && <p className="text-sm text-red-500">{t('error')}</p>}
      {applications.data && rows.length === 0 && (
        <p className="flex items-center gap-2 text-sm text-theme-secondary">
          <Inbox className="h-3.5 w-3.5" aria-hidden />
          {showDecided ? t('emptyAll') : t('empty')}
        </p>
      )}

      <ul className="space-y-3">
        {rows.map((row) => (
          <ApplicationRow
            key={row.id}
            row={row}
            locale={locale}
            busy={busy}
            founderOpen={founderOpen}
            onApprove={(code, percent, founder) => approve.mutate({ id: row.id, code, percent, founder })}
            onReject={(note) => reject.mutate({ id: row.id, note })}
          />
        ))}
      </ul>
    </section>
  );
}

function ApplicationRow({
  row, locale, busy, founderOpen, onApprove, onReject,
}: {
  row: PartnerApplicationRow;
  locale: string;
  busy: boolean;
  founderOpen: boolean;
  onApprove: (code: string, percent: number | undefined, founder: boolean) => void;
  onReject: (note: string) => void;
}) {
  const t = useTranslations('partnerProgram.applications');
  const [code, setCode] = useState('');
  const [percent, setPercent] = useState('');
  const [founder, setFounder] = useState(false);
  const [note, setNote] = useState('');
  const [rejecting, setRejecting] = useState(false);
  const href = safeHref(row.website);
  const pending = row.status === 'pending';
  // A mistyped rate is refused here: sent as NaN it would reach the backend as "no override"
  // and approve the partner at the default rate.
  const parsedPercent = parsePercent(percent);

  return (
    <li className="rounded-lg border border-theme p-4" data-testid="partner-application">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-sm font-semibold text-theme-primary">{row.company_name}</span>
        <span className="text-xs rounded-full bg-theme-tertiary px-2 py-0.5 text-theme-secondary">
          {t(`status.${row.status}`)}
        </span>
        {row.created_at && (
          <span className="text-xs text-theme-secondary">
            {t('submitted', { date: formatUtcDate(row.created_at, { locale }) })}
          </span>
        )}
      </div>
      <div className="mt-1 text-sm text-theme-secondary">
        {row.email ?? t('noEmail')}
        {href && (
          <>
            {' · '}
            <a href={href} target="_blank" rel="noopener noreferrer nofollow" className="inline-flex items-center gap-1 underline">
              {row.website}
              <ExternalLink className="h-3 w-3" aria-hidden />
            </a>
          </>
        )}
      </div>
      {row.audience && (
        <p className="mt-2 text-sm text-theme-primary"><span className="text-theme-secondary">{t('audience')}: </span>{row.audience}</p>
      )}
      {row.message && (
        <p className="mt-1 whitespace-pre-line text-sm text-theme-primary"><span className="text-theme-secondary">{t('message')}: </span>{row.message}</p>
      )}
      {!pending && row.decision_note && (
        <p className="mt-1 text-sm text-theme-secondary">{t('note')}: {row.decision_note}</p>
      )}

      {pending && !rejecting && (
        <div className="mt-3 flex flex-wrap items-end gap-2">
          <Input
            aria-label={t('codeOverride')}
            placeholder={t('codeOverride')}
            value={code}
            onChange={(e) => setCode(e.target.value)}
            className="h-9 w-40 text-sm"
          />
          <Input
            aria-label={t('percentOverride')}
            placeholder={t('percentOverride')}
            inputMode="decimal"
            value={percent}
            onChange={(e) => setPercent(e.target.value)}
            className="h-9 w-40 text-sm"
          />
          {founderOpen && (
            <label className="flex h-9 items-center gap-2 text-sm text-theme-primary">
              <input
                type="checkbox"
                checked={founder}
                onChange={(e) => setFounder(e.target.checked)}
                className="h-3.5 w-3.5"
              />
              {t('founder')}
            </label>
          )}
          <Button
            size="sm"
            className="gap-1"
            disabled={busy || !parsedPercent.ok}
            onClick={() => { if (parsedPercent.ok) onApprove(code, parsedPercent.value, founderOpen && founder); }}
          >
            <Check className="h-3.5 w-3.5" />
            {t('approve')}
          </Button>
          <Button size="sm" variant="outline" className="gap-1" disabled={busy} onClick={() => setRejecting(true)}>
            <X className="h-3.5 w-3.5" />
            {t('reject')}
          </Button>
          {!parsedPercent.ok && (
            <p role="alert" className="w-full text-xs text-red-500">{t('invalidPercent')}</p>
          )}
        </div>
      )}
      {pending && rejecting && (
        <div className="mt-3 flex flex-wrap items-end gap-2">
          <Input
            aria-label={t('notePlaceholder')}
            placeholder={t('notePlaceholder')}
            value={note}
            maxLength={500}
            onChange={(e) => setNote(e.target.value)}
            className="h-9 min-w-[16rem] flex-1 text-sm"
          />
          <Button size="sm" variant="outline" disabled={busy} onClick={() => onReject(note)}>
            {t('confirmReject')}
          </Button>
          <Button size="sm" variant="ghost" disabled={busy} onClick={() => setRejecting(false)}>
            {t('cancel')}
          </Button>
        </div>
      )}
    </li>
  );
}
