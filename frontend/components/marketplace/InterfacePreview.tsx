'use client';

import * as React from 'react';
import { InterfaceThumbnail } from '@/app/workflows/builder/components/interface/InterfaceThumbnail';
import { resolveInterfaceFormat } from '@/lib/interfaces/interfaceFormats';

export interface InterfaceSnapshotLike {
  htmlTemplate?: string | null;
  cssTemplate?: string | null;
  jsTemplate?: string | null;
  /**
   * The interface's declared format (preset name or "WIDTHxHEIGHT"), carried by the published
   * snapshot. Null/absent = no declared shape, so the preview keeps the classic 1280x800
   * virtual viewport.
   */
  format?: string | null;
  /**
   * Resolved variable_mapping values for this interface - typically populated by
   * the backend's `ShowcaseFileRefRewriter.rewriteLanding` on the
   * landing-snapshot endpoint. When present, the preview switches to `run`
   * mode so the rendered HTML substitutes `{{var}}` references with the
   * signed-URL strings inside `data` (FileRefs already converted by the
   * server). Without this, the preview shows `[label]` placeholders and the
   * user never sees the publisher's images. Typed as {@code unknown} because
   * the backend response carries the same shape; cast to record-of-unknown
   * only after the {@code typeof === 'object'} runtime guard.
   */
  data?: unknown;
}

interface Props {
  snapshot: InterfaceSnapshotLike | null | undefined;
  className?: string;
  emptyLabel?: string;
}

/**
 * Marketplace interface preview - thin wrapper around `InterfaceThumbnail`.
 * Parent CSS dictates the box (typically `absolute inset-0` in a grid cell, uniformly shaped
 * across the grid); the thumbnail self-measures and scales the interface's own virtual viewport
 * (its declared `format`, else the classic 1280x800) to fit, letterboxing inside the card. That
 * keeps a vertical interface's proportions intact rather than squashing it into the card's shape.
 *
 * Publisher-supplied JS runs so the preview matches what the buyer will see after
 * acquisition. Isolation is enforced by the iframe sandbox attribute (`allow-scripts`,
 * no `allow-same-origin`) - the script can manipulate its own DOM but cannot reach
 * the parent's storage, cookies, or DOM.
 *
 * <p>Render mode is `run` when the snapshot carries pre-resolved `data` (signed
 * URLs from `ShowcaseFileRefRewriter.rewriteLanding`), `edit` otherwise. The
 * `run` path is what makes user-supplied FileRefs visible on marketplace cards.
 */
export function InterfacePreview({ snapshot, className, emptyLabel = 'No preview' }: Props) {
  const resolvedData = (snapshot?.data && typeof snapshot.data === 'object'
      && !Array.isArray(snapshot.data)
      && Object.keys(snapshot.data as Record<string, unknown>).length > 0)
      ? (snapshot.data as Record<string, unknown>)
      : undefined;
  return (
    <div className={`pointer-events-none overflow-hidden w-full h-full ${className ?? ''}`}>
      <InterfaceThumbnail
        htmlTemplate={snapshot?.htmlTemplate ?? ''}
        customCss={snapshot?.cssTemplate || undefined}
        jsTemplate={snapshot?.jsTemplate || undefined}
        mode={resolvedData ? 'run' : 'edit'}
        resolvedData={resolvedData}
        fit="contain"
        viewport={resolveInterfaceFormat(snapshot?.format) ?? undefined}
        emptyLabel={emptyLabel}
      />
    </div>
  );
}
