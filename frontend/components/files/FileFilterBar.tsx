'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Search, RefreshCw, LayoutGrid, List } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { type FileTypeCategory, FILE_TYPE_CATEGORIES } from '@/lib/files/fileTypes';

/** Maps a file-type category to its `storageExplorer.*` i18n label key. */
const FILE_TYPE_LABEL_KEYS: Record<FileTypeCategory, string> = {
  _all: 'allFileTypes',
  images: 'fileTypeImages',
  pdf: 'fileTypePdf',
  documents: 'fileTypeDocuments',
  spreadsheets: 'fileTypeSpreadsheets',
  presentations: 'fileTypePresentations',
  video: 'fileTypeVideo',
  audio: 'fileTypeAudio',
  archives: 'fileTypeArchives',
  text: 'fileTypeText',
  code: 'fileTypeCode',
};

/** Selectable origin filters + their `storageExplorer.*` label keys. */
const SOURCE_OPTIONS = ['S3_FILE', 'CHAT_ATTACHMENT', 'STEP_OUTPUT', 'INTERFACE_ACTION', 'DECISION', 'SIGNAL', 'SKIPPED_NODE'] as const;
const SOURCE_LABEL_KEYS: Record<string, string> = {
  S3_FILE: 'sourceS3File',
  CHAT_ATTACHMENT: 'sourceChatAttachment',
  STEP_OUTPUT: 'sourceStepOutput',
  INTERFACE_ACTION: 'sourceInterfaceAction',
  DECISION: 'sourceDecision',
  SIGNAL: 'sourceSignal',
  SKIPPED_NODE: 'sourceSkippedNode',
};

/** Radix Select can't hold an empty value - '_all' is the sentinel for "no filter". */
const ALL = '_all';

interface FileFilterBarProps {
  searchInput: string;
  onSearchChange: (v: string) => void;
  fileType: FileTypeCategory;
  onFileTypeChange: (v: FileTypeCategory) => void;
  /** '' = all sources. */
  sourceType: string;
  onSourceTypeChange: (v: string) => void;
  /** yyyy-mm-dd input values ('' = unset). */
  dateFrom: string;
  dateTo: string;
  onDateFromChange: (v: string) => void;
  onDateToChange: (v: string) => void;
  viewMode: 'grid' | 'list';
  onViewModeChange: (v: 'grid' | 'list') => void;
  onRefresh: () => void;
  loading: boolean;
}

export function FileFilterBar({
  searchInput,
  onSearchChange,
  fileType,
  onFileTypeChange,
  sourceType,
  onSourceTypeChange,
  dateFrom,
  dateTo,
  onDateFromChange,
  onDateToChange,
  viewMode,
  onViewModeChange,
  onRefresh,
  loading,
}: FileFilterBarProps) {
  const tExp = useTranslations('storageExplorer');
  const tFiles = useTranslations('files');

  return (
    <div className="flex flex-col gap-2">
      {/* Row 1 - search + view toggle + refresh */}
      <div className="flex items-center gap-2">
        <div className="relative flex-1 min-w-0">
          <Search className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-[var(--text-secondary)]" />
          <Input
            value={searchInput}
            onChange={(e) => onSearchChange(e.target.value)}
            placeholder={tExp('searchPlaceholder')}
            className="pl-8"
          />
        </div>

        <div className="flex items-center rounded-lg border border-theme overflow-hidden flex-shrink-0">
          <button
            type="button"
            onClick={() => onViewModeChange('grid')}
            aria-pressed={viewMode === 'grid'}
            title={tFiles('viewGrid')}
            className={`h-9 px-3 flex items-center justify-center transition-colors ${viewMode === 'grid' ? 'bg-[var(--bg-tertiary)] text-[var(--text-primary)]' : 'text-[var(--text-secondary)] hover:bg-[var(--bg-tertiary)]/50'}`}
          >
            <LayoutGrid className="h-4 w-4" />
          </button>
          <button
            type="button"
            onClick={() => onViewModeChange('list')}
            aria-pressed={viewMode === 'list'}
            title={tFiles('viewList')}
            className={`h-9 px-3 flex items-center justify-center transition-colors ${viewMode === 'list' ? 'bg-[var(--bg-tertiary)] text-[var(--text-primary)]' : 'text-[var(--text-secondary)] hover:bg-[var(--bg-tertiary)]/50'}`}
          >
            <List className="h-4 w-4" />
          </button>
        </div>

        <button
          type="button"
          onClick={onRefresh}
          disabled={loading}
          title={tExp('refresh')}
          className="h-9 px-3 flex items-center justify-center rounded-lg border border-theme text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-tertiary)]/50 transition-colors flex-shrink-0"
        >
          <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
        </button>
      </div>

      {/* Row 2 - type / source / date-range filters */}
      <div className="flex flex-wrap items-center gap-2">
        <Select value={fileType} onValueChange={(v) => onFileTypeChange(v as FileTypeCategory)}>
          <SelectTrigger className="w-auto min-w-[140px]">
            <SelectValue placeholder={tExp('allFileTypes')} />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="_all">{tExp('allFileTypes')}</SelectItem>
            {FILE_TYPE_CATEGORIES.map((c) => (
              <SelectItem key={c} value={c}>{tExp(FILE_TYPE_LABEL_KEYS[c])}</SelectItem>
            ))}
          </SelectContent>
        </Select>

        <Select
          value={sourceType === '' ? ALL : sourceType}
          onValueChange={(v) => onSourceTypeChange(v === ALL ? '' : v)}
        >
          <SelectTrigger className="w-auto min-w-[140px]">
            <SelectValue placeholder={tExp('allSources')} />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>{tExp('allSources')}</SelectItem>
            {SOURCE_OPTIONS.map((s) => (
              <SelectItem key={s} value={s}>{tExp(SOURCE_LABEL_KEYS[s])}</SelectItem>
            ))}
          </SelectContent>
        </Select>

        <div className="flex items-center gap-1.5 text-xs text-[var(--text-secondary)]">
          <span>{tFiles('dateFrom')}</span>
          <Input
            type="date"
            value={dateFrom}
            max={dateTo || undefined}
            onChange={(e) => onDateFromChange(e.target.value)}
            className="w-auto px-3 text-sm"
          />
          <span>{tFiles('dateTo')}</span>
          <Input
            type="date"
            value={dateTo}
            min={dateFrom || undefined}
            onChange={(e) => onDateToChange(e.target.value)}
            className="w-auto px-3 text-sm"
          />
        </div>
      </div>
    </div>
  );
}
