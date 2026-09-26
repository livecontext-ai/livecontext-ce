'use client';

import React from 'react';
import Link from 'next/link';
import { useTranslations } from 'next-intl';
import { useQuery } from '@tanstack/react-query';
import { FileText, ArrowRight } from 'lucide-react';
import { storageApi } from '@/lib/api';
import type { StorageExplorerEntry } from '@/lib/api/storage-api';
import { formatUtcDate } from '@/lib/utils/dateFormatters';

/** How many of the biggest items the page lists. */
export const LARGEST_ITEMS_COUNT = 5;

/**
 * The biggest things this workspace stores, so a reader near the limit knows what to clean up
 * first instead of opening the Files browser and sorting it themselves.
 *
 * <p>One workspace at a time: the explorer lists a single workspace, so the "All workspaces" view
 * does not render this (a list from one workspace under an "All" label would be wrong).
 */
export function LargestItems({ orgId, formatBytes }: { orgId: string | null; formatBytes: (bytes: number) => string }) {
  const t = useTranslations('storage');
  const { data, isLoading, isError } = useQuery({
    queryKey: ['storage', 'largest-items', orgId ?? ''],
    queryFn: () => storageApi.getExplorerEntries(
      { page: 0, size: LARGEST_ITEMS_COUNT, sort: 'size', direction: 'desc' },
      orgId,
    ),
    staleTime: 60 * 1000,
  });

  const items = (data?.content ?? []).filter((e) => !e.isFolder && (e.sizeBytes ?? 0) > 0);
  // A secondary panel: on a failure or with nothing stored it steps aside rather than adding an
  // error to a page whose main figures loaded fine.
  if (isError || (!isLoading && items.length === 0)) return null;

  return (
    <div data-testid="storage-largest-items">
      <div className="flex items-center justify-between gap-3 mb-4">
        <div className="flex items-center gap-3 min-w-0">
          <div className="w-10 h-10 bg-theme-secondary rounded-xl flex items-center justify-center shrink-0">
            <FileText className="w-5 h-5 text-theme-primary" />
          </div>
          <div className="min-w-0">
            <h2 className="text-lg font-semibold text-theme-primary">{t('largest.title')}</h2>
            <p className="text-sm text-theme-secondary">{t('largest.subtitle')}</p>
          </div>
        </div>
        <Link
          href="/app/files"
          className="shrink-0 inline-flex items-center gap-1 text-sm text-theme-secondary hover:text-theme-primary"
        >
          {t('largest.openFiles')}
          <ArrowRight className="h-3.5 w-3.5" />
        </Link>
      </div>

      <div className="bg-theme-secondary rounded-xl border border-theme divide-y divide-[var(--border-color)]">
        {isLoading
          ? Array.from({ length: 3 }, (_, i) => (
              <div key={i} className="h-12 px-4 flex items-center">
                <div className="h-3 w-1/2 bg-theme-tertiary rounded animate-pulse" />
              </div>
            ))
          : items.map((item) => <LargestItemRow key={item.id} item={item} formatBytes={formatBytes} />)}
      </div>
    </div>
  );
}

function LargestItemRow({ item, formatBytes }: { item: StorageExplorerEntry; formatBytes: (bytes: number) => string }) {
  const t = useTranslations('storage');
  // A step output has no file name: it is named by the workflow and step that produced it.
  const name = item.fileName
    || [item.workflowName, item.stepKey].filter(Boolean).join(' / ')
    || t('largest.unnamed');
  const context = [
    item.fileName ? item.workflowName : null,
    item.createdAt ? formatUtcDate(item.createdAt) : null,
  ].filter(Boolean).join(' · ');

  return (
    <div className="h-12 px-4 flex items-center gap-3" data-testid="storage-largest-item">
      <div className="min-w-0 flex-1">
        <p className="text-sm text-theme-primary truncate" title={name}>{name}</p>
        {context && <p className="text-xs text-theme-tertiary truncate">{context}</p>}
      </div>
      <span className="text-sm font-medium text-theme-primary whitespace-nowrap">{formatBytes(item.sizeBytes ?? 0)}</span>
    </div>
  );
}
