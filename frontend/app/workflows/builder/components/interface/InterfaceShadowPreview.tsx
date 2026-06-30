'use client';

import * as React from 'react';
import { InterfaceIframe } from './InterfaceIframe';
import type { ContentSize } from './InterfaceIframe';
import type { RenderMode } from '../../utils/interfaceHtmlUtils';
import { SAFE_CENTERING_CSS } from '../../utils/safeCenteringCss';

export interface InterfaceShadowPreviewProps {
  /** HTML template with {{variable}} syntax */
  htmlTemplate: string;
  /** Rendering mode */
  mode: RenderMode;
  /** Resolved data for run mode */
  resolvedData?: Record<string, unknown>;
  /** Custom CSS to inject */
  customCss?: string;
  /** Container className */
  className?: string;
  /** Container style */
  style?: React.CSSProperties;
  /** Scale content to fit within the container (useful for fixed-height cards) */
  autoFit?: boolean;
  /** JavaScript template to inject */
  jsTemplate?: string;
  /** Action mapping for bridge script (CSS selector -> trigger ref) */
  actionMapping?: Record<string, string>;
  /** Previous trigger data for form pre-fill */
  triggerData?: Record<string, Record<string, unknown>>;
  /** Called when an action is triggered from the iframe */
  onAction?: (triggerRef: string, data: Record<string, unknown>) => void;
  /** Called when a pagination action is triggered */
  onPagination?: (direction: 'prev' | 'next') => void;
  /** Called when __continue action is triggered - resolves the interface signal */
  onContinue?: (actionKey: string, data: Record<string, unknown>) => void;
  /** File upload context for interface file inputs */
  fileUploadContext?: { workflowId: string; runId: string };
  /** Called when content size is measured (forwarded from iframe height reporter) */
  onSizeChange?: (size: ContentSize) => void;
  /** Strip <script> + on* handlers from htmlTemplate (untrusted publisher contexts). */
  removeScripts?: boolean;
}

/**
 * Renders an interface HTML template inside a sandboxed iframe.
 *
 * Delegates to the centralized InterfaceIframe component for full CSS + JS fidelity.
 * Auto-sizes height to content unless the consumer provides an explicit height
 * (via h-full/h-[...]/h-screen className or style.height).
 *
 * Note: Previously used Shadow DOM (hence the name). Switched to iframe
 * to support JS execution (charts, animations) and ensure CSS fidelity.
 */
export const InterfaceShadowPreview = React.memo(function InterfaceShadowPreview({
  htmlTemplate,
  mode,
  resolvedData,
  customCss,
  className = '',
  style,
  autoFit = true,
  jsTemplate,
  actionMapping,
  triggerData,
  onAction,
  onPagination,
  onContinue,
  fileUploadContext,
  onSizeChange: externalSizeChange,
  removeScripts,
}: InterfaceShadowPreviewProps) {
  const [contentHeight, setContentHeight] = React.useState(300);

  // Detect if consumer provides explicit height via className or style
  const hasExplicitHeight = /\b(?:h-(?:full|\[|screen)|flex-1|min-h-0)\b/.test(className) || style?.height != null;

  const handleSizeChange = React.useCallback((size: ContentSize) => {
    if (size.height > 0) {
      setContentHeight(size.height);
      externalSizeChange?.(size);
    }
  }, [externalSizeChange]);

  // Compatibility: .shadow-root CSS was used with Shadow DOM, map to body for iframe
  const adjustedCss = customCss?.replace(/\.shadow-root\b/g, 'body');

  // Prepend safe-centering CSS so small interfaces stay centered while tall ones
  // (marketplace dashboards, full app pages) remain scrollable from their top.
  // Publisher rules come AFTER and can still override anything they redeclare.
  const finalCss = SAFE_CENTERING_CSS + (adjustedCss ?? '');

  // Build style: apply auto-height only when consumer doesn't set explicit height
  const iframeStyle: React.CSSProperties = {
    ...(hasExplicitHeight ? {} : { height: `${contentHeight}px` }),
    ...style,
  };

  return (
    <InterfaceIframe
      htmlTemplate={htmlTemplate}
      mode={mode}
      resolvedData={resolvedData}
      customCss={finalCss}
      jsTemplate={jsTemplate}
      autoFit={autoFit}
      actionMapping={actionMapping}
      triggerData={triggerData}
      onAction={onAction}
      onPagination={onPagination}
      onContinue={onContinue}
      onSizeChange={!hasExplicitHeight ? handleSizeChange : undefined}
      className={className}
      style={iframeStyle}
      fileUploadContext={fileUploadContext}
      removeScripts={removeScripts}
    />
  );
});

InterfaceShadowPreview.displayName = 'InterfaceShadowPreview';

export default InterfaceShadowPreview;
