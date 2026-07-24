'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { ChevronDown, ChevronUp, File, FileText, FileType2, Film, Image, Music, PanelRight } from 'lucide-react';
import { fileRefToUrl, fileService } from '@/lib/api/orchestrator/file.service';
import { useAuthedObjectUrl } from '@/hooks/useAuthedObjectUrl';
import { detectPreviewKind, resolveMediaMimeType } from '@/lib/files/filePreview';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { openFilesPanel } from '@/lib/sidePanel/openFilesPanel';

import { useWorkflowLayoutDirectionSafe } from '@/contexts/WorkflowLayoutDirectionContext';
import { getStretchedAttachment } from './handleGeometry';
/** Lucide icon for a file's mime family (shared by the canvas file previews). */
export function getFileKindIcon(mimeType: string) {
  if (mimeType.startsWith('image/')) return Image;
  if (mimeType.startsWith('video/')) return Film;
  if (mimeType.startsWith('audio/')) return Music;
  // PDF gets its own glyph so the pill distinguishes it from plain documents.
  if (mimeType.includes('pdf')) return FileType2;
  if (mimeType.includes('document') || mimeType.includes('text')) return FileText;
  return File;
}

export interface FileStripFile {
  path: string;
  name: string;
  mimeType: string;
  size: number;
  id?: string;
}

/** Round icon-button used inside the pill / preview-card header. */
const PILL_BTN_CLS = 'inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-full text-slate-400 hover:text-slate-600 hover:bg-slate-100 dark:text-slate-500 dark:hover:text-slate-300 dark:hover:bg-slate-700 transition-colors';

/**
 * Compact one-line file pill: mime-family icon + truncated name + human size +
 * expand / open-in-side-panel buttons. Icon-only when collapsed (no thumbnail:
 * the inline preview lives in {@link FilePreviewCard}, shown on expand).
 * Position-agnostic: the caller wraps it (absolute below the node for the
 * run-time strip, in-flow for the data_input item list).
 */
export function FileRefPill({
  file,
  expanded,
  onToggleExpand,
  onOpenPanel,
}: {
  file: FileStripFile;
  expanded: boolean;
  onToggleExpand: () => void;
  onOpenPanel: () => void;
}) {
  const t = useTranslations('workflowBuilder.nodes.filePreview');
  const IconComp = getFileKindIcon(file.mimeType);
  const ChevronComp = expanded ? ChevronUp : ChevronDown;
  return (
    // nodrag/nopan: clicks on the pill must toggle the preview, never drag the
    // node or pan the canvas. A11y: the container is a PLAIN flex div - the
    // clickable name area is a real <button> and the chevron/panel controls are
    // its SIBLINGS, so no interactive element ever nests inside another.
    <div
      data-testid="file-ref-pill"
      className="flex items-center gap-1.5 rounded-lg border border-theme bg-white/95 dark:bg-gray-800/95 px-2 py-1 shadow-sm backdrop-blur nodrag nopan"
    >
      <button
        type="button"
        aria-expanded={expanded}
        title={expanded ? t('collapse') : t('expand')}
        className="flex min-w-0 flex-1 cursor-pointer items-center gap-1.5 text-left"
        onClick={(e) => { e.stopPropagation(); onToggleExpand(); }}
      >
        <IconComp className="h-3 w-3 shrink-0 text-slate-400 dark:text-slate-500" />
        <span className="min-w-0 flex-1 truncate text-xs text-slate-500 dark:text-slate-400">{file.name}</span>
        <span className="shrink-0 text-xs text-slate-400 dark:text-slate-500">{fileService.formatFileSize(file.size)}</span>
      </button>
      <button
        type="button"
        title={expanded ? t('collapse') : t('expand')}
        aria-label={expanded ? t('collapse') : t('expand')}
        className={PILL_BTN_CLS}
        onClick={(e) => { e.stopPropagation(); onToggleExpand(); }}
      >
        <ChevronComp className="h-3 w-3" />
      </button>
      <button
        type="button"
        title={t('openInPanel')}
        aria-label={t('openInPanel')}
        className={PILL_BTN_CLS}
        onClick={(e) => { e.stopPropagation(); onOpenPanel(); }}
      >
        <PanelRight className="h-3 w-3" />
      </button>
    </div>
  );
}

/** The preview kinds the card can render inline; everything else is 'other'. */
function inlineKindOf(file: FileStripFile): 'image' | 'video' | 'audio' | 'pdf' | 'other' {
  const kind = detectPreviewKind(file.mimeType, file.name);
  return kind === 'image' || kind === 'video' || kind === 'audio' || kind === 'pdf' ? kind : 'other';
}

/**
 * Expanded inline preview of a FileRef: header row (icon + name + size +
 * collapse / open-in-side-panel) above a real media preview for every
 * transiting S3 type - image, video, audio, PDF; anything else shows a
 * no-inline-preview hint (full inspection stays in the side panel).
 *
 * Bytes are fetched lazily by construction: the card is only MOUNTED while
 * expanded, so useAuthedObjectUrl never runs for a collapsed pill.
 * Position-agnostic like the pill; callers position it absolutely below the
 * node so it never adds in-flow height.
 */
export function FilePreviewCard({
  file,
  onCollapse,
  onOpenPanel,
}: {
  file: FileStripFile;
  onCollapse: () => void;
  onOpenPanel: () => void;
}) {
  const t = useTranslations('workflowBuilder.nodes.filePreview');
  const kind = inlineKindOf(file);
  // Header-authenticated fetch -> blob: URL (no session token in the URL).
  // 'other' kinds pass null: no media bytes are fetched for them at all.
  const mediaSrc = kind !== 'other' ? (fileRefToUrl(file, { inline: true }) || null) : null;
  const { url, loading, error } = useAuthedObjectUrl(mediaSrc, resolveMediaMimeType(file.mimeType, file.name));

  // While expanded, the card hangs below the node into the inter-node gap and
  // can overlap the NEIGHBORING node's box. ReactFlow stacks nodes by their own
  // per-node z-index (each .react-flow__node is a sibling stacking context), so
  // z-* classes inside this node cannot win against a sibling node. Bump the
  // HOST node's z-index for the lifetime of the card and restore it on
  // collapse/unmount so normal stacking resumes.
  // Known limitation (accepted): ReactFlow itself rewrites the node's inline
  // style.zIndex on some interactions (e.g. selection elevation). If that
  // happens WHILE the card is open, our bump is clobbered and the cleanup then
  // restores the pre-expand value captured here, which may be stale by then.
  // Harmless in practice: the value self-heals on the next collapse/expand or
  // the next ReactFlow re-stack, so we deliberately do not fight the library
  // with a MutationObserver.
  const rootRef = React.useRef<HTMLDivElement>(null);
  React.useEffect(() => {
    const nodeEl = rootRef.current?.closest('.react-flow__node') as HTMLElement | null;
    if (!nodeEl) return;
    const previous = nodeEl.style.zIndex;
    nodeEl.style.zIndex = '1200';
    return () => { nodeEl.style.zIndex = previous; };
  }, []);

  const IconComp = getFileKindIcon(file.mimeType);
  // Hint fallback covers: non-inline kinds, a failed blob fetch, AND a media
  // kind whose ref has no usable source (fileRefToUrl returns '' for a legacy
  // id-less FileRef) - without the !mediaSrc arm that last case showed the
  // loading skeleton forever (url stays null, loading never starts).
  const showHint = kind === 'other' || error || !mediaSrc;

  return (
    <div
      ref={rootRef}
      data-testid="file-preview-card"
      className="space-y-2 rounded-2xl border border-theme bg-white/95 dark:bg-gray-800/95 p-2.5 shadow-md backdrop-blur nodrag nopan"
      onClick={(e) => e.stopPropagation()}
    >
      {/* Header: name + size + collapse / open-in-panel */}
      <div className="flex items-center gap-1.5">
        <IconComp className="h-3 w-3 shrink-0 text-slate-400 dark:text-slate-500" />
        <span className="min-w-0 flex-1 truncate text-xs text-slate-500 dark:text-slate-400" title={file.name}>{file.name}</span>
        <span className="shrink-0 text-xs text-slate-400 dark:text-slate-500">{fileService.formatFileSize(file.size)}</span>
        <button
          type="button"
          title={t('collapse')}
          aria-label={t('collapse')}
          className={PILL_BTN_CLS}
          onClick={(e) => { e.stopPropagation(); onCollapse(); }}
        >
          <ChevronUp className="h-3 w-3" />
        </button>
        <button
          type="button"
          title={t('openInPanel')}
          aria-label={t('openInPanel')}
          className={PILL_BTN_CLS}
          onClick={(e) => { e.stopPropagation(); onOpenPanel(); }}
        >
          <PanelRight className="h-3 w-3" />
        </button>
      </div>

      {/* Inline preview body */}
      {showHint ? (
        <div className="flex items-center gap-1.5 text-xs text-slate-400 dark:text-slate-500">
          <IconComp className="h-3 w-3 shrink-0" />
          <span>{t('noPreview')}</span>
        </div>
      ) : loading || !url ? (
        <div className="h-16 w-full animate-pulse rounded bg-slate-100 dark:bg-slate-700/50" />
      ) : kind === 'image' ? (
        <img src={url} alt={file.name} className="max-h-60 w-full rounded object-contain" />
      ) : kind === 'video' ? (
        <video controls playsInline preload="metadata" src={url} className="max-h-60 w-full rounded" />
      ) : kind === 'audio' ? (
        <audio controls src={url} className="w-full" />
      ) : (
        <iframe src={url} title={file.name} className="h-64 w-full rounded" />
      )}
    </div>
  );
}

/**
 * Run-time file result strip - rendered by FileNodePreview when a node's
 * execution produced a FileRef.
 *
 * Absolutely positioned just BELOW the node border so it adds ZERO height to
 * the node itself: the run-time FileRef arrives AFTER auto-layout measured the
 * node, so any in-flow preview grew the node and made it overlap the node
 * placed underneath (fixed ReactFlow positions). Collapsed it is a one-line
 * {@link FileRefPill}; expanded it swaps to a {@link FilePreviewCard} with a
 * real inline preview.
 *
 * Sharing the row with the hover NodeBottomBar: the strip OWNS the bar's spot
 * (`calc(100% + 8px)`), hugging the node with no gap. The two never coexist
 * there - a node showing this strip drops its bottom-bar "Files" button (the
 * pill's own panel button does the same job, see useNodeContextualButtons) and,
 * when the bar still has other buttons to show, FlowNode lowers the whole bar a
 * row via `extraTopOffset`. In edit mode there is no strip and the bar keeps
 * both its spot and its Files button.
 */
export function FileResultStrip({ file }: { file: FileStripFile }) {
  // Node attachments hang off the edge the flow does not use.
  const { direction: layoutDirection } = useWorkflowLayoutDirectionSafe();

  const [expanded, setExpanded] = React.useState(false);
  const sidePanel = useSidePanelSafe();

  // Same opener as the bottom-bar "Files" button (useNodeContextualButtons):
  // opens the side-panel Files tab focused on THIS file.
  const openPanel = React.useCallback(() => {
    openFilesPanel(sidePanel, {
      path: file.path,
      id: file.id,
      name: file.name,
      mimeType: file.mimeType,
      size: file.size,
    });
  }, [sidePanel, file.path, file.id, file.name, file.mimeType, file.size]);

  // pointer-events-none on the wrapper so the strip never STEALS clicks from
  // whatever it happens to overlap (edges, handles, a close neighbor); the
  // pill/card re-enables its own pointer events (same pattern as NodeBottomBar).
  return (
    <div
      // z-20: the NodeBottomBar row is a LATER z-10 sibling, so at equal z it
      // would paint (and click) OVER the strip - the bar is lowered a row when
      // this strip is up, but a taller EXPANDED card still reaches its band.
      // Horizontal: spans the node's width just below it. Vertical: that band is the
      // source handle's, so it moves beside the node and takes the node's width as its
      // own. Both axes come from the helper - a leftover `left-3 right-3` class here
      // would survive the inline `left` and halve the strip.
      className="absolute z-20 pointer-events-none"
      style={getStretchedAttachment(layoutDirection, 8)}
    >
      <div className="pointer-events-auto">
        {expanded ? (
          <FilePreviewCard file={file} onCollapse={() => setExpanded(false)} onOpenPanel={openPanel} />
        ) : (
          <FileRefPill file={file} expanded={false} onToggleExpand={() => setExpanded(true)} onOpenPanel={openPanel} />
        )}
      </div>
    </div>
  );
}
