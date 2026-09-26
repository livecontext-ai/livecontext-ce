'use client';

import React, { useEffect, useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import { Coins, Maximize2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogTitle } from '@/components/ui/dialog';
import { getClientLocale } from '@/lib/utils/locale';
import { formatUtcDateTime } from '@/lib/utils/dateFormatters';
import { CREDIT_SOURCE_LABEL_KEYS } from '@/lib/billing/creditSourceTypes';
import type { CreditHistoryPage } from '@/lib/api';
import { OwnKeyRowNote } from '../OwnKeyRowNote';
import type { ModelNameIndex } from '@/lib/ai-providers/modelDisplayName';
import { ProviderModelCell } from './modelLabels';

/**
 * The Usage History section: title, the caller's toolbar (filter), the table and the caller's
 * footer (error, pager), with a full-screen mode. Shared by the cloud and the CE page, which used
 * to carry two copies of the same table.
 *
 * <p><b>Every row is one line of a fixed height.</b> Rows used to grow with their content (the
 * cached-token count on its own line, a long model or type label wrapping), so the table height
 * changed from page to page and the pager under it moved: a reader clicking "next" repeatedly
 * missed the button. Cells now truncate (the full text stays in the tooltip), the column widths
 * are fixed, and a short last page is padded to the page size, so the pager never moves.
 *
 * <p><b>Full screen is the app's Dialog</b>, stretched to the viewport: it brings the focus trap,
 * the Escape layering with the filter's own list, the scroll lock and the inert page behind,
 * none of which a hand-rolled fixed box gets right.
 */
export function UsageHistoryPanel({
  history,
  busy = false,
  pageSize,
  amountHeader,
  formatAmount,
  modelNames,
  toolbar,
  footer,
}: {
  history: CreditHistoryPage | null;
  busy?: boolean;
  /** The size every page is requested at; a shorter page is padded to it. */
  pageSize: number;
  amountHeader: string;
  formatAmount: (amount: number) => string;
  modelNames: ModelNameIndex | null;
  toolbar?: React.ReactNode;
  footer?: React.ReactNode;
}) {
  const t = useTranslations('quota');
  const [fullscreen, setFullscreen] = useState(false);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const leftFullscreen = useRef(false);

  // Leaving full screen remounts the inline panel, so the element that had focus is gone: hand
  // it to the toggle that opened the overlay instead of dropping the keyboard user at the top.
  useEffect(() => {
    if (fullscreen || !leftFullscreen.current) return;
    leftFullscreen.current = false;
    toggleRef.current?.focus();
  }, [fullscreen]);

  const closeFullscreen = () => {
    leftFullscreen.current = true;
    setFullscreen(false);
  };

  const formatTokens = (count: number | null) => {
    if (count === null || count === undefined) return '-';
    return count.toLocaleString(getClientLocale());
  };

  const formatDate = (dateStr: string) => {
    try {
      return formatUtcDateTime(dateStr);
    } catch {
      return dateStr;
    }
  };

  // IMAGE_GENERATION rows reuse promptTokens to store actualImageCount and leave
  // completionTokens null - rendering "1 / -" in a column labeled "Tokens" is
  // misleading. Image-gen has no token concept; render "-".
  const isImageGenSourceType = (sourceType: string) =>
    sourceType === 'IMAGE_GENERATION' || sourceType === 'IMAGE_GENERATION_BYOK';

  const rows = history?.content ?? [];
  // Only a paged history pads: a single short page has no pager to keep in place.
  const fillerCount = history && history.totalPages > 1 ? Math.max(0, pageSize - rows.length) : 0;

  const cell = 'px-4 h-10 text-sm text-theme-primary whitespace-nowrap overflow-hidden text-ellipsis';
  const head = 'px-4 py-2.5 font-medium text-left text-theme-secondary text-sm whitespace-nowrap overflow-hidden text-ellipsis';

  const header = (
    <div className={`flex items-center justify-between mb-4 gap-3 ${fullscreen ? 'pr-10' : ''}`}>
      <div className="flex items-center gap-3 min-w-0">
        <div className="w-10 h-10 bg-theme-secondary rounded-xl flex items-center justify-center shrink-0">
          <Coins className="w-5 h-5 text-theme-primary" />
        </div>
        {fullscreen ? (
          <DialogTitle className="text-lg font-semibold text-theme-primary truncate">{t('history.title')}</DialogTitle>
        ) : (
          <h2 className="text-lg font-semibold text-theme-primary truncate">{t('history.title')}</h2>
        )}
      </div>
      <div className="flex items-center gap-2 min-w-0">
        {toolbar}
        {/* In full screen the dialog's own close button, in the corner, is the way out. */}
        {!fullscreen && (
          <Button
            ref={toggleRef}
            variant="ghost"
            size="icon"
            className="h-9 w-9 shrink-0"
            onClick={() => setFullscreen(true)}
            aria-label={t('history.fullscreen')}
            title={t('history.fullscreen')}
            data-testid="usage-history-fullscreen-toggle"
          >
            <Maximize2 className="h-4 w-4" />
          </Button>
        )}
      </div>
    </div>
  );

  const table = (
      <div
        data-testid="usage-history-table"
        aria-busy={busy}
        className={`rounded-xl border border-slate-200 dark:border-slate-700/50 transition-opacity ${busy ? 'opacity-60' : ''} ${fullscreen ? 'flex-1 min-h-0 overflow-auto' : 'overflow-x-auto'}`}
      >
        <table className="w-full min-w-[900px] table-fixed" style={{ borderSpacing: '0' }}>
          <colgroup>
            {/* Every column but the model is fixed, so the model (the one a reader scans for) takes
                all the width left: at least 180px, which lets the whole table fit the settings
                column (about 900px) without a horizontal scroll, and more in full screen. A cell
                that does not fit (a long locale's date, a badge) keeps its full text as a tooltip. */}
            <col className="w-[205px]" />
            <col className="w-[115px]" />
            <col />
            <col className="w-[140px]" />
            <col className="w-[130px]" />
            <col className="w-[130px]" />
          </colgroup>
          <thead className="bg-theme-secondary border-b border-slate-200 dark:border-slate-700/50 sticky top-0 z-[1]">
            <tr>
              <th className={head}>{t('history.date')}</th>
              <th className={head}>{t('history.type')}</th>
              <th className={head}>{t('history.providerModel')}</th>
              <th className={head}>{t('history.tokens')}</th>
              <th className={`${head} text-right`}>{amountHeader}</th>
              <th className={head}>{t('history.description')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.length > 0 ? (
              <>
                {rows.map((entry) => {
                  const typeLabel = CREDIT_SOURCE_LABEL_KEYS[entry.sourceType]
                    ? t(CREDIT_SOURCE_LABEL_KEYS[entry.sourceType])
                    : entry.sourceType;
                  const cached = entry.cachedTokens
                    ? t('history.cachedTokens', { count: formatTokens(entry.cachedTokens) })
                    : null;
                  const hasTokens = !isImageGenSourceType(entry.sourceType)
                    && (entry.promptTokens !== null || entry.completionTokens !== null);
                  const tokensText = hasTokens
                    ? `${formatTokens(entry.promptTokens)} / ${formatTokens(entry.completionTokens)}${cached ? ` (${cached})` : ''}`
                    : undefined;
                  return (
                    <tr
                      key={entry.id}
                      data-testid="usage-history-row"
                      className="border-b border-slate-200 dark:border-slate-700/50 last:border-b-0 hover:bg-theme-secondary/50 transition-colors duration-150"
                    >
                      <td className={cell} title={formatDate(entry.createdAt)}>{formatDate(entry.createdAt)}</td>
                      <td className={cell} title={typeLabel}>{typeLabel}</td>
                      {/* The stored ids as the cell's tooltip: the cell itself only adds one when a
                          display name replaced them, so a long raw id would truncate unexplained. */}
                      <td className={cell} title={[entry.provider, entry.model].filter(Boolean).join(' / ') || undefined}>
                        <ProviderModelCell provider={entry.provider} model={entry.model} index={modelNames} />
                      </td>
                      <td className={cell} title={tokensText}>
                        {hasTokens ? (
                          <>
                            {formatTokens(entry.promptTokens)}
                            <span className="text-theme-tertiary mx-1">/</span>
                            {formatTokens(entry.completionTokens)}
                            {cached ? <span className="text-theme-tertiary ml-1">({cached})</span> : null}
                          </>
                        ) : (
                          '-'
                        )}
                      </td>
                      <td
                        className={`${cell} text-right font-medium ${entry.amount > 0 ? 'text-emerald-500 dark:text-emerald-400' : ''}`}
                        title={`${entry.amount < 0 ? '' : '+'}${formatAmount(entry.amount)}`}
                      >
                        {entry.amount < 0 ? '' : '+'}{formatAmount(entry.amount)}
                        <OwnKeyRowNote entry={entry} />
                      </td>
                      <td className={cell} title={entry.description ?? undefined}>
                        {entry.description || '-'}
                      </td>
                    </tr>
                  );
                })}
                {Array.from({ length: fillerCount }, (_, i) => (
                  <tr
                    key={`filler-${i}`}
                    aria-hidden="true"
                    data-testid="usage-history-filler"
                    className="border-b border-transparent last:border-b-0"
                  >
                    <td colSpan={6} className="h-10" />
                  </tr>
                ))}
              </>
            ) : (
              <tr>
                <td
                  colSpan={6}
                  className="px-4 py-12 text-center text-sm text-theme-secondary"
                  // A paged history whose page came back empty keeps the height of a full page
                  // (rows of h-10 plus the 1px border between them), so the pager stays put.
                  style={history && history.totalPages > 1 ? { height: `calc(${pageSize * 2.5}rem + ${pageSize - 1}px)` } : undefined}
                >
                  {t('history.noHistory')}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
  );

  if (fullscreen) {
    return (
      <Dialog open onOpenChange={(open) => { if (!open) closeFullscreen(); }}>
        <DialogContent
          data-testid="usage-history-panel"
          data-fullscreen="true"
          closeLabel={t('history.exitFullscreen')}
          aria-describedby={undefined}
          onCloseAutoFocus={(e) => e.preventDefault()}
          className="left-0 top-0 translate-x-0 translate-y-0 w-screen max-w-none h-[100dvh] max-h-none rounded-none border-0 flex flex-col gap-0 overflow-hidden p-4 sm:p-6 max-[480px]:max-w-none"
        >
          {header}
          {table}
          {footer}
        </DialogContent>
      </Dialog>
    );
  }

  return (
    <div data-testid="usage-history-panel">
      {header}
      {table}
      {footer}
    </div>
  );
}
