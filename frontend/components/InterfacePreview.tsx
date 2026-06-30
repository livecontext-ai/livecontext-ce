'use client';

import * as React from 'react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { InterfaceShadowPreview } from '@/app/workflows/builder/components/interface/InterfaceShadowPreview';

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
}: InterfacePreviewProps) {
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
