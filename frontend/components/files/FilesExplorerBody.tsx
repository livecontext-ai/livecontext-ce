'use client';

import * as React from 'react';
import { ChevronDown } from 'lucide-react';
import type { StorageExplorerEntry } from '@/lib/api/storage-api';
import { FileCard } from './FileCard';
import { FolderCard, VirtualFolderCard } from './FolderCard';
import { StorageEntryRow, StorageFolderRow } from './StorageRows';
import {
  splitFoldersAndFiles,
  groupEntriesByDay,
  type FileDayGroup,
} from '@/lib/files/filesGrouping';
import { isVirtualEntry, entryKey, folderLabel } from '@/lib/files/virtualFolders';

/** A next-intl translator (the {@code files} namespace) with optional ICU values. */
type Translator = (key: string, values?: Record<string, unknown>) => string;

export interface FilesExplorerBodyProps {
  /** {@code grid} = iOS-style tiles (Files page, project tab); {@code compact} = narrow rows (side panel). */
  variant: 'grid' | 'compact';
  /** Folders + files in backend order (folders first, each sorted by last activity). */
  entries: StorageExplorerEntry[];
  /** Split folder rows out and show them first. Off → every entry is a file (flat listing). */
  enableFolders: boolean;
  /** next-intl {@code files} translator - resolves folder labels + the "N items" count. */
  tFiles: Translator;

  /** Navigate into a folder (manual or virtual). */
  onOpenFolder: (entry: StorageExplorerEntry) => void;
  /** Open a file's detail view (grid click + compact explorer row click). */
  onOpenFile?: (entry: StorageExplorerEntry) => void;
  /** Picker density (compact): select a file instead of opening it (shows an expand-preview chevron). */
  onSelectFile?: (entry: StorageExplorerEntry) => void;
  /** Grid hover-download a single file. */
  onDownloadFile?: (entry: StorageExplorerEntry) => void;
  /** Localised label for the grid download button. */
  downloadLabel?: string;

  /** Whether file rows/tiles show a selection checkbox. */
  selectable: boolean;
  selectedIds: Set<string>;
  onToggleSelect: (id: string) => void;
  /** Optional "select every file in this day" - omit to hide the per-day checkbox. */
  onToggleDaySelection?: (dayEntries: StorageExplorerEntry[]) => void;

  /** Grid only: make the tiles dnd-kit draggable (the full Files page wraps this in a DndContext). */
  gridDraggable?: boolean;

  /** Compact deep-link: the s3Key to highlight + scroll into view (image-gen card click-through). */
  focusS3Key?: string | null;
  /** Compact deep-link by opaque id - highlights a file that has no s3Key (back-from-detail). */
  focusEntryId?: string | null;
  /** Ref set on the focused row so the parent can scroll it into view. */
  focusRef?: React.RefObject<HTMLDivElement | null>;

  /** Compact picker: which file row's inline preview is expanded. */
  expandedId?: string | null;
  onToggleExpand?: (id: string) => void;
}

/**
 * The single, shared body for EVERY files surface: the full-page Files browser
 * ({@code variant="grid"}), the right side-panel storage explorer
 * ({@code variant="compact"}), and the project Files tab ({@code variant="grid"}). It
 * owns the one layout contract - folders first (sorted by last activity, newest first),
 * then files grouped into collapsible per-day sections (newest day first) - so the three
 * surfaces never drift. The day-collapse state is internal (purely presentational);
 * selection + folder/file actions are driven by the parent through props.
 */
export function FilesExplorerBody({
  variant,
  entries,
  enableFolders,
  tFiles,
  onOpenFolder,
  onOpenFile,
  onSelectFile,
  onDownloadFile,
  downloadLabel,
  selectable,
  selectedIds,
  onToggleSelect,
  onToggleDaySelection,
  gridDraggable = false,
  focusS3Key,
  focusEntryId,
  focusRef,
  expandedId,
  onToggleExpand,
}: FilesExplorerBodyProps) {
  const isGrid = variant === 'grid';

  const { folders, files } = React.useMemo(
    () => splitFoldersAndFiles(entries, enableFolders),
    [entries, enableFolders],
  );
  // Folders + files share ONE per-day timeline: a folder lands in the day of its last
  // activity (createdAt = MAX child date), rendered above that day's files.
  const dayGroups = React.useMemo(() => groupEntriesByDay(folders, files), [folders, files]);

  // Day-collapse is purely presentational → owned here so every surface gets it for free.
  const [collapsedDays, setCollapsedDays] = React.useState<Set<string>>(new Set());
  const toggleDayCollapse = React.useCallback((dateFrom: string) => {
    setCollapsedDays((prev) => {
      const next = new Set(prev);
      if (next.has(dateFrom)) next.delete(dateFrom); else next.add(dateFrom);
      return next;
    });
  }, []);

  const isFocused = React.useCallback(
    (entry: StorageExplorerEntry) =>
      (!!focusEntryId && entry.id === focusEntryId)
      || (!!focusS3Key && !!entry.s3Key && (
        entry.s3Key === focusS3Key
        || focusS3Key.endsWith(entry.s3Key)
        || entry.s3Key.endsWith(focusS3Key)
      )),
    [focusS3Key, focusEntryId],
  );

  const gridClass = 'grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-3';

  const renderFolder = (entry: StorageExplorerEntry) => {
    const label = folderLabel(entry, tFiles);
    const countLabel = tFiles('itemCount', { count: entry.childCount ?? 0 });
    if (!isGrid) {
      return (
        <StorageFolderRow key={entryKey(entry)} entry={entry} label={label} countLabel={countLabel} onOpen={onOpenFolder} />
      );
    }
    // Virtual workflow folders are navigation-only (no dnd, no select); manual folders are full tiles.
    return isVirtualEntry(entry) ? (
      <VirtualFolderCard key={entryKey(entry)} entry={entry} label={label} countLabel={countLabel} onOpen={onOpenFolder} />
    ) : (
      <FolderCard
        key={entryKey(entry)}
        entry={entry}
        selected={selectedIds.has(entry.id)}
        onToggleSelect={onToggleSelect}
        onOpen={onOpenFolder}
        label={label}
        countLabel={countLabel}
      />
    );
  };

  const renderFile = (entry: StorageExplorerEntry) => {
    if (isGrid) {
      return (
        <FileCard
          key={entryKey(entry)}
          entry={entry}
          selected={selectedIds.has(entry.id)}
          onToggleSelect={onToggleSelect}
          onOpen={(e) => onOpenFile?.(e)}
          onDownload={(e) => onDownloadFile?.(e)}
          downloadLabel={downloadLabel ?? ''}
          draggable={gridDraggable}
        />
      );
    }
    // Compact: picker selects (onSelectFile), explorer navigates (onOpenFile). A focused
    // deep-link row gets the ref + highlight so the parent can scroll it into view.
    const focused = isFocused(entry);
    return (
      <StorageEntryRow
        key={entryKey(entry)}
        entry={entry}
        onSelect={onSelectFile ? (e) => onSelectFile(e) : undefined}
        onNavigate={!onSelectFile && onOpenFile ? (e) => onOpenFile(e) : undefined}
        selectable={selectable}
        selected={selectedIds.has(entry.id)}
        onToggleSelect={onToggleSelect}
        expanded={expandedId === entry.id}
        onToggleExpand={(id) => onToggleExpand?.(id)}
        rowRef={focused ? focusRef : undefined}
        highlight={focused}
      />
    );
  };

  return (
    <div className={isGrid ? 'space-y-3 pb-2' : ''}>
      {/* One per-day timeline (newest day first), each section collapsible: that day's
          folders first (by last activity), then its files. */}
      {dayGroups.map((group) => {
        const collapsed = collapsedDays.has(group.dateFrom);
        return (
          <div key={group.dateFrom}>
            <DayHeader
              group={group}
              collapsed={collapsed}
              onToggleCollapse={() => toggleDayCollapse(group.dateFrom)}
              sticky={!isGrid}
              selectedIds={selectedIds}
              onToggleDaySelection={onToggleDaySelection}
            />
            {!collapsed && (
              <div className={isGrid ? `${gridClass} pt-2` : 'divide-y divide-theme'}>
                {group.folders.map(renderFolder)}
                {group.entries.map(renderFile)}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

interface DayHeaderProps {
  group: FileDayGroup;
  collapsed: boolean;
  onToggleCollapse: () => void;
  sticky: boolean;
  selectedIds: Set<string>;
  onToggleDaySelection?: (dayEntries: StorageExplorerEntry[]) => void;
}

/** Collapsible per-day section header, shared by both densities (sticky only in the side panel). */
function DayHeader({ group, collapsed, onToggleCollapse, sticky, selectedIds, onToggleDaySelection }: DayHeaderProps) {
  const dayIds = group.entries.map((e) => e.id);
  const allDaySelected = dayIds.length > 0 && dayIds.every((id) => selectedIds.has(id));
  const someDaySelected = dayIds.some((id) => selectedIds.has(id));

  return (
    <div
      data-testid="files-day-header"
      className={`${sticky ? 'sticky top-0 z-10' : ''} px-3 py-1.5 bg-[var(--bg-secondary)]/95 backdrop-blur-sm border-b border-theme flex items-center gap-2 cursor-pointer select-none`}
      onClick={onToggleCollapse}
    >
      {/* Day "select all" toggles the day's FILES only (folders aren't selectable); hidden on a folder-only day. */}
      {onToggleDaySelection && group.entries.length > 0 && (
        <input
          type="checkbox"
          checked={allDaySelected}
          ref={(el) => { if (el) el.indeterminate = !allDaySelected && someDaySelected; }}
          onChange={() => onToggleDaySelection(group.entries)}
          onClick={(e) => e.stopPropagation()}
          className="rounded border-slate-300 dark:border-slate-600 flex-shrink-0"
        />
      )}
      <ChevronDown className={`h-3.5 w-3.5 text-[var(--text-secondary)] transition-transform ${collapsed ? '-rotate-90' : ''}`} />
      <span className="text-xs font-semibold text-[var(--text-secondary)] uppercase tracking-wider flex-1">
        {group.label}
      </span>
      <span className="text-xs text-[var(--text-secondary)] tabular-nums">
        {group.folders.length + group.entries.length}
      </span>
    </div>
  );
}
