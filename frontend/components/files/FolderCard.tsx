'use client';

import * as React from 'react';
import { Folder } from 'lucide-react';
import { useDroppable, useDraggable } from '@dnd-kit/core';
import type { StorageExplorerEntry, StoragePreviewFile } from '@/lib/api/storage-api';
import { getFileUrlById } from '@/lib/api/orchestrator/file.service';
import { useAuthedObjectUrl } from '@/hooks/useAuthedObjectUrl';
import { getFileTypeIcon } from '@/lib/files/fileTypes';
import { detectPreviewKind } from '@/lib/files/filePreview';

/** Shared face + footer for both the manual and the virtual folder tile. */
interface FolderFaceProps {
  /** Up to 9 child files (any type) for the iOS-style 3×3 mosaic. */
  previewFiles: StoragePreviewFile[];
  /** Localized display label (parent passes it: manual = name, virtual = grouping). */
  label: string;
  /** Localized exact item count (built by the parent from {@code childCount}). */
  countLabel: string;
}

/**
 * The folder tile's visual body: an iOS-style 3×3 mosaic built from
 * {@code previewFiles} (the rest of the 9 cells are muted placeholders), plus a
 * footer with the folder icon, label and exact child-count badge. Shared by the
 * manual ({@link FolderCard}) and virtual ({@link VirtualFolderCard}) tiles.
 */
export function FolderFace({ previewFiles, label, countLabel }: FolderFaceProps) {
  return (
    <>
      {/* Folder face - iOS-style 3×3 mosaic (image thumbnails + file-type icons) */}
      <div className="aspect-[4/3] bg-theme-tertiary p-2">
        {previewFiles.length > 0 ? (
          <div className="grid grid-cols-3 grid-rows-3 gap-1 w-full h-full">
            {Array.from({ length: 9 }).map((_, i) => (
              <FolderTile key={i} file={previewFiles[i]} />
            ))}
          </div>
        ) : (
          <div className="flex items-center justify-center w-full h-full">
            <Folder className="h-10 w-10 text-theme-muted" />
          </div>
        )}
      </div>

      {/* Footer - folder icon + label + exact child count */}
      <div className="flex items-center gap-2 px-2.5 py-2 border-t border-theme">
        <span className="flex-shrink-0">
          <Folder className="h-4 w-4 text-[var(--accent-primary)]" />
        </span>
        <div className="min-w-0 flex-1">
          <div className="text-sm text-theme-primary truncate">{label}</div>
          <div className="text-xs text-theme-muted truncate">{countLabel}</div>
        </div>
      </div>
    </>
  );
}

interface FolderCardProps {
  entry: StorageExplorerEntry;
  selected: boolean;
  onToggleSelect: (id: string) => void;
  /** Open the folder (navigate into it). */
  onOpen: (entry: StorageExplorerEntry) => void;
  /** Localized display label (manual = the folder's name). */
  label: string;
  /** Localized exact item count (built by the parent from {@code childCount}). */
  countLabel: string;
}

/**
 * iOS-style MANUAL folder tile (V313). The face is a 3×3 grid built from the
 * folder's {@code previewFiles} (≤9 children of any type, newest first: image
 * thumbnails + file-type icons); the footer shows the folder {@code label} and an
 * EXACT child-count badge.
 *
 * <p>Interaction: clicking the body opens (navigates into) the folder; the
 * top-left checkbox selects it (survives pagination via the parent's map). The
 * tile is a dnd-kit <strong>droppable</strong> - dropping file/folder cards on it
 * moves them in - AND a <strong>draggable</strong>, so a folder can itself be
 * dropped into another folder (the backend rejects cycles). Use this only for
 * REAL rows (with an {@code id}); computed groupings use {@link VirtualFolderCard}.
 */
export const FolderCard = React.memo(function FolderCard({
  entry,
  selected,
  onToggleSelect,
  onOpen,
  label,
  countLabel,
}: FolderCardProps) {
  const previewFiles = entry.previewFiles ?? [];

  const { setNodeRef: setDropRef, isOver } = useDroppable({
    id: entry.id,
    data: { type: 'folder', entry },
  });
  const { setNodeRef: setDragRef, attributes, listeners, isDragging } = useDraggable({
    id: entry.id,
    data: { type: 'folder', entry },
  });

  // Combine the drop + drag refs onto the same node.
  const setRefs = React.useCallback(
    (node: HTMLElement | null) => {
      setDropRef(node);
      setDragRef(node);
    },
    [setDropRef, setDragRef],
  );

  return (
    <div
      ref={setRefs}
      {...attributes}
      {...listeners}
      className={`group relative rounded-xl border bg-theme-secondary overflow-hidden cursor-pointer transition-colors ${
        isOver
          ? 'border-[var(--accent-primary)] ring-2 ring-[var(--accent-primary)] ring-offset-1'
          : selected
            ? 'border-[var(--accent-primary)] ring-2 ring-[var(--accent-primary)]'
            : 'border-theme hover:border-[var(--accent-primary)]'
      } ${isDragging ? 'opacity-50' : ''}`}
      onClick={() => onOpen(entry)}
      title={label}
    >
      {/* Selection checkbox - always visible when selected, on hover otherwise */}
      <div
        className={`absolute top-2 left-2 z-10 transition-opacity ${selected ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'}`}
        onClick={(e) => e.stopPropagation()}
        onPointerDown={(e) => e.stopPropagation()}
      >
        <input
          type="checkbox"
          checked={selected}
          onChange={() => onToggleSelect(entry.id)}
          className="rounded border-slate-300 dark:border-slate-600 bg-white/80 dark:bg-slate-900/80"
        />
      </div>

      <FolderFace previewFiles={previewFiles} label={label} countLabel={countLabel} />
    </div>
  );
});

interface VirtualFolderCardProps {
  entry: StorageExplorerEntry;
  /** Open the folder (navigate into it via its {@code virtualId}). */
  onOpen: (entry: StorageExplorerEntry) => void;
  /** Localized grouping label (built by the parent from the virtual kind). */
  label: string;
  /** Localized exact item count (built by the parent from {@code childCount}). */
  countLabel: string;
}

/**
 * iOS-style VIRTUAL workflow-folder tile (Phase 2b). A computed grouping
 * (workflow → epoch → spawn → iteration) - it has NO real row, so it is
 * NAVIGATION-ONLY: not a drop target, not draggable, and not selectable (no
 * checkbox). Clicking it navigates deeper via its {@code virtualId}. The same 3×3
 * preview mosaic + footer as {@link FolderCard}.
 */
export const VirtualFolderCard = React.memo(function VirtualFolderCard({
  entry,
  onOpen,
  label,
  countLabel,
}: VirtualFolderCardProps) {
  const previewFiles = entry.previewFiles ?? [];

  return (
    <div
      className="group relative rounded-xl border border-theme bg-theme-secondary overflow-hidden cursor-pointer transition-colors hover:border-[var(--accent-primary)]"
      onClick={() => onOpen(entry)}
      title={label}
    >
      <FolderFace previewFiles={previewFiles} label={label} countLabel={countLabel} />
    </div>
  );
});

/**
 * One cell of the folder mosaic. An image file lazy-loads a real thumbnail (the
 * inline raw-by-id blob fetch - same opaque, org-scoped, header-authenticated path
 * as FileCard's thumbnail; the session token never lands in the URL). Every other
 * file type (pdf, csv, archives, ...) renders its file-type ICON instead - no byte
 * fetch - so a folder of non-image files still previews "what's inside" iOS-style.
 * An empty slot (beyond the available previews) is a muted placeholder.
 */
function FolderTile({ file }: { file: StoragePreviewFile | undefined }) {
  // MIME wins, filename extension is the fallback (same rule as the file thumbnail).
  const isImage = !!file && detectPreviewKind(file.mimeType, file.fileName) === 'image';
  // Only image cells fetch bytes; icon cells pass null so the hook does no request.
  const { url, error } = useAuthedObjectUrl(
    isImage && file ? getFileUrlById(file.id, { inline: true }) : null,
  );

  if (!file) {
    return <div className="rounded-sm bg-theme-secondary/60" />;
  }
  if (isImage && url && !error) {
    return (
      /* eslint-disable-next-line @next/next/no-img-element */
      <img src={url} alt="" loading="lazy" className="w-full h-full object-cover rounded-sm" />
    );
  }
  // Non-image (or an image whose bytes failed to load): show the file-type icon.
  return (
    <div className="flex items-center justify-center w-full h-full rounded-sm bg-theme-secondary/60">
      {getFileTypeIcon({ mimeType: file.mimeType, fileName: file.fileName }, 'h-5 w-5')}
    </div>
  );
}
