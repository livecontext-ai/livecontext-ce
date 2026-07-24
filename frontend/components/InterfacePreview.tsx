'use client';

import * as React from 'react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { InterfaceShadowPreview } from '@/app/workflows/builder/components/interface/InterfaceShadowPreview';
import { resolveInterfaceFormat } from '@/lib/interfaces/interfaceFormats';
import { fitScale, useMeasuredBox } from '@/lib/interfaces/useFitScale';

interface InterfacePreviewProps {
  htmlTemplate: string | null | undefined;
  /** Optional CSS template for styling */
  cssTemplate?: string | null;
  /** Optional JS template for dynamic behavior */
  jsTemplate?: string | null;
  className?: string;
  style?: React.CSSProperties;
  isLoading?: boolean;
  /** Optional resolved data for run mode */
  resolvedData?: Record<string, unknown>;
  /** Scale content to fit container (default true for cards, set false for fullscreen) */
  autoFit?: boolean;
  /** Drop publisher-supplied JS (used by marketplace previews where authors are untrusted). */
  dropJs?: boolean;
  /** Label rendered when htmlTemplate is empty. */
  emptyLabel?: string;
  /** Force render mode. When omitted, deduced from resolvedData (run if non-empty, else edit). */
  mode?: 'edit' | 'run';
  /**
   * The interface's declared format (preset name or "WIDTHxHEIGHT"). When set, the interface is
   * rendered at the format's exact pixel viewport and letterboxed into the container, so a
   * 1080x1920 page keeps its shape instead of laying out at the container's width. Null/omitted
   * keeps the natural, container-width rendering.
   */
  format?: string | null;
}

/**
 * Reusable component to display an HTML template in a sandboxed iframe.
 * Used wherever we need to preview an interface (modal, detail page, etc.)
 */
export function InterfacePreview({
  htmlTemplate,
  cssTemplate,
  jsTemplate,
  className = '',
  style,
  isLoading = false,
  resolvedData,
  autoFit,
  dropJs = false,
  emptyLabel = 'No HTML template available',
  mode: modeOverride,
  format,
}: InterfacePreviewProps) {
  const formatViewport = resolveInterfaceFormat(format);
  // Hooks must run unconditionally, so measure before the empty-template early return.
  const [boxRef, box] = useMeasuredBox<HTMLDivElement>();

  if (!htmlTemplate || !htmlTemplate.trim()) {
    return (
      <div className={`flex items-center justify-center h-full text-theme-secondary text-sm ${className}`} style={style}>
        {emptyLabel}
      </div>
    );
  }

  // Determine mode: explicit override > deduced from resolvedData
  const mode = modeOverride ?? (resolvedData && Object.keys(resolvedData).length > 0 ? 'run' : 'edit');

  // Use flex layout when consumer uses flex-1 (embedded preview panel),
  // otherwise use h-full (standalone/modal contexts where percentage height resolves).
  const usesFlex = /\bflex-1\b/.test(className);

  // Declared format: render at its exact viewport and letterbox it into the container, so the
  // interface keeps the shape it was authored for. Downscale only - blowing a small interface up
  // past its natural size just makes it blurry.
  if (formatViewport) {
    const scale = fitScale(box, formatViewport, 'contain', false);
    return (
      <div
        ref={boxRef}
        // ALWAYS a flex container: `items-center justify-center` are no-ops otherwise, and the
        // letterboxed frame pins to the top-left instead of sitting centred in the panel - very
        // visible on a vertical interface, which is narrow and leaves wide margins.
        className={`relative w-full flex items-center justify-center overflow-auto ${
          usesFlex ? 'flex-1 min-h-0' : 'h-full'
        }`}
        style={style}
      >
        {scale > 0 && (
          <div style={{ width: formatViewport.width * scale, height: formatViewport.height * scale }}>
            <div
              style={{
                width: formatViewport.width,
                height: formatViewport.height,
                transform: `scale(${scale})`,
                transformOrigin: '0 0',
              }}
            >
              <InterfaceShadowPreview
                htmlTemplate={htmlTemplate}
                mode={mode}
                resolvedData={resolvedData}
                customCss={cssTemplate || undefined}
                jsTemplate={dropJs ? undefined : (jsTemplate || undefined)}
                className={`transition-all duration-300 ${isLoading ? 'blur-sm' : ''}`}
                style={{ width: formatViewport.width, height: formatViewport.height }}
                // Forward it: InterfaceShadowPreview defaults autoFit to TRUE, which injects its
                // own in-iframe scaling on top of the transform above. Every caller of this
                // branch passes autoFit={false}, so dropping it would stack two scalers.
                autoFit={autoFit}
                removeScripts={dropJs}
              />
            </div>
          </div>
        )}
        {isLoading && (
          <div className="absolute inset-0 flex items-center justify-center bg-white/50">
            <LoadingSpinner size="xl" />
          </div>
        )}
      </div>
    );
  }

  return (
    <div className={`relative w-full ${usesFlex ? 'flex-1 flex flex-col min-h-0' : 'h-full'}`}>
      <InterfaceShadowPreview
        htmlTemplate={htmlTemplate}
        mode={mode}
        resolvedData={resolvedData}
        customCss={cssTemplate || undefined}
        jsTemplate={dropJs ? undefined : (jsTemplate || undefined)}
        className={`w-full transition-all duration-300 ${isLoading ? 'blur-sm' : ''} ${className}`}
        style={style}
        autoFit={autoFit}
        removeScripts={dropJs}
      />
      {/* Loading overlay */}
      {isLoading && (
        <div className="absolute inset-0 flex items-center justify-center bg-white/50">
          <LoadingSpinner size="xl" />
        </div>
      )}
    </div>
  );
}
