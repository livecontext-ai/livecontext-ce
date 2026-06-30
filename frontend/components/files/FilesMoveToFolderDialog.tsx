'use client';

import { useState, useMemo } from 'react';
import { Button } from '@/components/ui/button';
import { Folder, FolderInput, Home, ChevronRight } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useTranslations } from 'next-intl';
import {
  buildFolderTree,
  collectExcludedSubtree,
  flattenVisibleTree,
  type MoveFolderRow,
} from '@/lib/files/moveFolderTree';

interface FilesMoveToFolderDialogProps {
  isOpen: boolean;
  /** Every manual folder in the workspace ({id, name, parentFolderId}). */
  allFolders: MoveFolderRow[];
  /**
   * Folders being moved - excluded from the picker along with their whole subtree
   * (the dialog expands these to descendants itself), so a folder is never offered
   * as a destination for itself or one of its descendants.
   */
  excludeFolderIds: Set<string>;
  /** Whether the folder list is still loading (shows a spinner in place of the tree). */
  loading?: boolean;
  onClose: () => void;
  /** Commit the move. {@code null} target = move to the top level (root). */
  onMove: (targetFolderId: string | null) => Promise<void>;
  /** Number of selected items - drives the localized title. */
  itemCount: number;
}

/**
 * "Move to…" destination picker for the Files grid, mirroring the Skills
 * {@code MoveToFolderDialog}: a backdrop + {@code rounded-3xl} card with a recursive
 * folder arborescence (chevron expand/collapse), a "Top level (root)" option, a
 * single highlighted target, and Cancel / Move buttons (Move spins while the
 * promise runs and is disabled until a destination - a folder or root - is chosen).
 * The tree is built client-side from {@code parentFolderId}; folders being moved
 * (and their subtrees) are hidden so an invalid destination can't be selected.
 */
export function FilesMoveToFolderDialog({
  isOpen,
  allFolders,
  excludeFolderIds,
  loading = false,
  onClose,
  onMove,
  itemCount,
}: FilesMoveToFolderDialogProps) {
  const t = useTranslations('files');
  const tCommon = useTranslations('common');
  // null = root; undefined = nothing chosen yet (Move stays disabled).
  const [selectedId, setSelectedId] = useState<string | null | undefined>(undefined);
  const [moving, setMoving] = useState(false);
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());

  // Excluded set = the moved folders + their whole subtrees.
  const excludeSet = useMemo(
    () => collectExcludedSubtree(allFolders, excludeFolderIds),
    [allFolders, excludeFolderIds],
  );

  const tree = useMemo(() => buildFolderTree(allFolders, excludeSet), [allFolders, excludeSet]);
  const flatList = useMemo(() => flattenVisibleTree(tree, expandedIds), [tree, expandedIds]);

  if (!isOpen) return null;

  const toggleExpand = (id: string) => {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const handleMove = async () => {
    if (selectedId === undefined) return; // nothing chosen
    setMoving(true);
    try {
      await onMove(selectedId);
      onClose();
    } catch (err) {
      console.error('Error moving:', err);
    } finally {
      setMoving(false);
    }
  };

  return (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        className="bg-theme-primary rounded-3xl p-8 shadow-2xl max-w-md w-full max-h-[80vh] flex flex-col animate-in fade-in-0 zoom-in-95 duration-300 border border-theme"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center gap-3 mb-4">
          <div className="w-10 h-10 bg-blue-100 dark:bg-blue-900/50 rounded-full flex items-center justify-center">
            <FolderInput className="w-5 h-5 text-blue-600 dark:text-blue-400" />
          </div>
          <h3 className="text-lg font-semibold text-theme-primary">
            {t('moveToFolderTitle', { count: itemCount })}
          </h3>
        </div>

        {/* Folder tree */}
        <div className="flex-1 overflow-y-auto border border-theme rounded-lg mb-4 min-h-[200px] max-h-[400px]">
          {loading ? (
            <div className="flex items-center justify-center py-8">
              <LoadingSpinner size="sm" />
            </div>
          ) : (
            <>
              {/* Root option */}
              <button
                onClick={() => setSelectedId(null)}
                className={`w-full flex items-center gap-2 px-3 py-2.5 text-sm transition-colors ${
                  selectedId === null
                    ? 'bg-[var(--accent-primary)]/10 text-[var(--accent-primary)]'
                    : 'text-theme-primary hover:bg-theme-secondary'
                }`}
              >
                <Home className="h-4 w-4 flex-shrink-0" />
                <span className="font-medium">{t('moveToRoot')}</span>
              </button>

              {/* Folder items */}
              {flatList.map((node) => {
                const hasChildren = node.children.length > 0;
                const isExpanded = expandedIds.has(node.folder.id);
                const isSelected = selectedId === node.folder.id;

                return (
                  <div key={node.folder.id}>
                    <button
                      onClick={() => setSelectedId(node.folder.id)}
                      className={`w-full flex items-center gap-2 px-3 py-2.5 text-sm transition-colors ${
                        isSelected
                          ? 'bg-[var(--accent-primary)]/10 text-[var(--accent-primary)]'
                          : 'text-theme-primary hover:bg-theme-secondary'
                      }`}
                      style={{ paddingLeft: `${(node.depth + 1) * 16 + 12}px` }}
                    >
                      {hasChildren ? (
                        <button
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation();
                            toggleExpand(node.folder.id);
                          }}
                          className="p-0.5 hover:bg-theme-tertiary rounded"
                        >
                          <ChevronRight
                            className={`h-3.5 w-3.5 transition-transform ${isExpanded ? 'rotate-90' : ''}`}
                          />
                        </button>
                      ) : (
                        <span className="w-[22px]" />
                      )}
                      <Folder className="h-4 w-4 text-amber-600 dark:text-amber-400 flex-shrink-0" />
                      <span className="truncate">{node.folder.name}</span>
                    </button>
                  </div>
                );
              })}

              {flatList.length === 0 && (
                <div className="flex items-center justify-center py-8 text-sm text-theme-secondary text-center px-4">
                  {t('noFoldersYet')}
                </div>
              )}
            </>
          )}
        </div>

        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose} disabled={moving}>
            {tCommon('cancel')}
          </Button>
          <Button onClick={handleMove} disabled={moving || selectedId === undefined}>
            {moving ? (
              <LoadingSpinner size="xs" className="mr-2" />
            ) : (
              <FolderInput className="h-4 w-4 mr-2" />
            )}
            {t('moveHere')}
          </Button>
        </div>
      </div>
    </div>
  );
}
