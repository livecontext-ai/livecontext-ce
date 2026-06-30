'use client';

import * as React from 'react';
import type { StorageExplorerEntry } from '@/lib/api/storage-api';
import { getFileTypeIcon } from '@/lib/files/fileTypes';
import { FileThumb } from './FileCard';
import { FolderFace } from './FolderCard';

interface DragPreviewCardProps {
  entry: StorageExplorerEntry;
  /** Display label (the caller resolves it via {@code folderLabel} / filename). */
  label: string;
  /**
   * Number of items being moved. When &gt;1 a small "N" badge is shown so a
   * multi-selection drag reads as "move N items". Defaults to 1.
   */
  multi?: number;
}

/**
 * The floating thumbnail shown under the pointer while dragging a Files card
 * (rendered inside the {@code DndContext}'s {@code DragOverlay}). A compact tile
 * that reuses the very thumbnail the grid shows - {@link FolderFace}'s 3×3 mosaic
 * for a folder, {@link FileThumb} (image/video/pdf preview, else a themed icon) for
 * a file - plus the truncated name. Tilted + shadowed so it reads as "picked up";
 * {@code pointer-events-none} so it never intercepts the drop. When {@code multi}
 * &gt; 1 a badge shows the count being moved.
 */
export const DragPreviewCard = React.memo(function DragPreviewCard({
  entry,
  label,
  multi = 1,
}: DragPreviewCardProps) {
  const isFolder = !!entry.isFolder;
  const count = Math.max(1, multi);

  return (
    <div className="relative w-28 pointer-events-none rotate-2 opacity-95">
      <div className="rounded-xl border border-[var(--accent-primary)] bg-theme-secondary overflow-hidden shadow-2xl ring-2 ring-[var(--accent-primary)]">
        {isFolder ? (
          // Reuse the grid's folder mosaic (label/count rendered in the footer below
          // the face - FolderFace already includes a footer, so keep it as the body).
          <FolderFace previewFiles={entry.previewFiles ?? []} label={label} countLabel="" />
        ) : (
          <>
            <div className="aspect-[4/3] bg-theme-tertiary flex items-center justify-center overflow-hidden">
              <FileThumb entry={entry} />
            </div>
            <div className="flex items-center gap-2 px-2.5 py-2 border-t border-theme">
              <span className="flex-shrink-0">{getFileTypeIcon(entry, 'h-4 w-4')}</span>
              <div className="min-w-0 flex-1">
                <div className="text-sm text-theme-primary truncate">{label}</div>
              </div>
            </div>
          </>
        )}
      </div>

      {count > 1 && (
        <span className="absolute -top-2 -right-2 min-w-[1.5rem] h-6 px-1.5 rounded-full bg-[var(--accent-primary)] text-white text-xs font-semibold flex items-center justify-center shadow-lg">
          {count}
        </span>
      )}
    </div>
  );
});
