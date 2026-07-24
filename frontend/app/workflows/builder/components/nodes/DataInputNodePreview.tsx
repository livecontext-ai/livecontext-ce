'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { openFilesPanel } from '@/lib/sidePanel/openFilesPanel';
import type { BuilderNodeData } from '../../types';
import { FileRefPill, FilePreviewCard, type FileStripFile } from './FileResultStrip';

import { useWorkflowLayoutDirectionSafe } from '@/contexts/WorkflowLayoutDirectionContext';
import { getStretchedAttachment } from './handleGeometry';
// ============================================================================
// Data Input Node Preview (canvas)
// Edit-time content: the items exist when auto-layout measures the node, so
// in-flow pills are fine here - but the list must stay BOUNDED (max 3 items +
// a "+N more" line, text line-clamp) or a big upload/paste still turns the
// node into a poster that overlaps its neighbors. File items render as the
// SAME collapsed FileRefPill as the run-time strip; expanding one shows the
// shared FilePreviewCard absolutely below the node (zero in-flow growth).
// ============================================================================

interface DataInputItem {
  id: string;
  label: string;
  type: 'text' | 'file';
  text?: string;
  file?: FileStripFile | null;
}

/** Hard cap on in-flow item rows so the node height stays bounded. */
const MAX_VISIBLE_ITEMS = 3;

export function DataInputNodePreview({ data }: { data: BuilderNodeData }) {
  // The expanded card hangs off the edge the flow does not use.
  const { direction: layoutDirection } = useWorkflowLayoutDirectionSafe();

  const t = useTranslations('workflowBuilder.nodes.filePreview');
  const sidePanel = useSidePanelSafe();
  // Which file item's inline preview card is open (one at a time).
  const [expandedId, setExpandedId] = React.useState<string | null>(null);

  const items: DataInputItem[] = (data as any).dataInputItems ?? [];
  if (items.length === 0) return null;

  const hasContent = items.some((i) => (i.type === 'text' ? !!i.text : !!i.file));
  if (!hasContent) return null;

  const visibleItems = items.slice(0, MAX_VISIBLE_ITEMS);
  const hiddenCount = items.length - visibleItems.length;
  const expandedItem = items.find((i) => i.id === expandedId && i.type === 'file' && !!i.file) ?? null;

  const openPanel = (file: FileStripFile) => {
    // Same opener as the bottom-bar "Files" button, focused on this file.
    openFilesPanel(sidePanel, {
      path: file.path,
      id: file.id,
      name: file.name,
      mimeType: file.mimeType,
      size: file.size,
    });
  };

  return (
    <>
      <div className="mt-3 space-y-1.5 w-full">
        {visibleItems.map((item) => (
          <div key={item.id}>
            <span className="text-[10px] font-medium text-slate-400 dark:text-slate-500 uppercase tracking-wider">{item.label}</span>
            {item.type === 'text' && item.text && (
              // line-clamp keeps a long pasted text from growing the node past its neighbors
              <p className="text-xs text-slate-500 dark:text-slate-400 whitespace-pre-wrap break-words leading-relaxed overflow-hidden line-clamp-3">
                {item.text}
              </p>
            )}
            {item.type === 'file' && item.file && (
              <FileRefPill
                file={item.file}
                expanded={expandedId === item.id}
                onToggleExpand={() => setExpandedId((prev) => (prev === item.id ? null : item.id))}
                onOpenPanel={() => openPanel(item.file as FileStripFile)}
              />
            )}
          </div>
        ))}
        {hiddenCount > 0 && (
          <div className="text-xs text-slate-400 dark:text-slate-500">{t('more', { count: hiddenCount })}</div>
        )}
      </div>

      {/* Expanded inline preview - absolutely positioned below the node border
          (anchored to the node's relative root, NOT to the pill), so opening it
          never grows the node: ReactFlow positions are fixed and an in-flow
          card would push the node under its neighbor. Sits CLOSE to the node
          (12px, user-requested): while a preview is open the user interacts
          with the card, which deliberately paints over the hover bar's row
          (z-bumped node) until collapsed. The run-time strip sits 4px higher,
          right ON the bar's row: it is permanently visible, so it takes that row
          outright and the bar steps out a notch (FlowNode's extraOffset).
          Here the pills are IN the node and only this transient card hangs below,
          so there is nothing to hand the row to - it just paints over. */}
      {expandedItem?.file && (
        // z-20: paints over the later z-10 NodeBottomBar row (which would
        // otherwise float its Delete button over the open card's header band).
        <div className="absolute z-20 pointer-events-none" style={getStretchedAttachment(layoutDirection, 12)}>
          <div className="pointer-events-auto">
            <FilePreviewCard
              file={expandedItem.file}
              onCollapse={() => setExpandedId(null)}
              onOpenPanel={() => openPanel(expandedItem.file as FileStripFile)}
            />
          </div>
        </div>
      )}
    </>
  );
}
