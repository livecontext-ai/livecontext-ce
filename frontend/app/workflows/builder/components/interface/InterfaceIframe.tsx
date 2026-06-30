'use client';

import * as React from 'react';
import {
  renderInterfaceTemplate,
  sanitizeHtml,
  RenderMode,
  RenderOptions,
} from '../../utils/interfaceHtmlUtils';
import { fileService, type FileRef } from '@/lib/api/orchestrator/file.service';
import { useInterfaceFileUrls } from './useInterfaceFileUrls';
import { OpenLinkConfirmModal } from './OpenLinkConfirmModal';

export interface ContentSize {
  width: number;
  height: number;
}

export interface InterfaceIframeProps {
  /** HTML template with {{variable}} syntax */
  htmlTemplate: string;
  /** Rendering mode */
  mode: RenderMode;
  /** Resolved data for run mode */
  resolvedData?: Record<string, unknown>;
  /** Custom CSS to inject */
  customCss?: string;
  /** Iframe className */
  className?: string;
  /** Iframe style */
  style?: React.CSSProperties;
  /** Sandbox attribute */
  sandbox?: string;
  /** Called when iframe content loads */
  onLoad?: (iframe: HTMLIFrameElement) => void;
  /** Called when content size is measured via postMessage from iframe */
  onSizeChange?: (size: ContentSize) => void;
  /** Enable auto-fit scaling inside the iframe to fit content within container */
  autoFit?: boolean;
  /** Action mapping for bridge script (CSS selector -> trigger ref) */
  actionMapping?: Record<string, string>;
  /** Previous trigger data for form pre-fill (trigger ref -> field values) */
  triggerData?: Record<string, Record<string, unknown>>;
  /** JavaScript template to inject as a script tag */
  jsTemplate?: string;
  /** Called when an action is triggered from the iframe via postMessage */
  onAction?: (triggerRef: string, data: Record<string, unknown>) => void;
  /** Called when a pagination action is triggered from the iframe (prev/next) */
  onPagination?: (direction: 'prev' | 'next') => void;
  /** Called when __continue action is triggered - resolves the interface signal */
  onContinue?: (actionKey: string, data: Record<string, unknown>) => void;
  /** Called when a variable pagination action is triggered from the iframe */
  onVariablePagination?: (variableName: string, page: number) => void;
  /** File upload context for interface file inputs (workflowId + runId needed) */
  fileUploadContext?: { workflowId: string; runId: string };
  /** Strip <script> tags + on* handlers from htmlTemplate (untrusted publisher contexts). */
  removeScripts?: boolean;
}

/**
 * Centralized iframe component for rendering interface HTML templates.
 *
 * Supports three modes:
 * - edit: Variables become [lastPart] placeholders
 * - preview: Same as edit, for preview panels
 * - run: Variables are replaced with actual data or pending placeholders
 */
export const InterfaceIframe = React.forwardRef<HTMLIFrameElement, InterfaceIframeProps>(
  (
    {
      htmlTemplate,
      mode,
      resolvedData,
      customCss,
      className = '',
      style,
      sandbox = 'allow-scripts',
      onLoad,
      onSizeChange,
      autoFit = false,
      actionMapping,
      triggerData,
      jsTemplate,
      onAction,
      onPagination,
      onContinue,
      onVariablePagination,
      fileUploadContext,
      removeScripts = false,
    },
    ref
  ) => {
    const internalRef = React.useRef<HTMLIFrameElement>(null);
    const iframeRef = (ref as React.RefObject<HTMLIFrameElement>) || internalRef;

    // Pre-fetch every FileRef in the data as a base64 data: URI (auth in the header,
    // never the URL) so the iframe renders files without the session token in its HTML.
    // data: (not blob:) because these iframes are sandboxed without allow-same-origin -
    // a parent blob: URL is unreadable there, a data: URI renders anywhere. Run mode only.
    // See useInterfaceFileUrls.
    const { resolveFileUrl } = useInterfaceFileUrls(resolvedData, mode === 'run');

    // Track content transitions: fade out on change, fade in on iframe load.
    // Gated on `htmlTemplate` (user-authored body) rather than the computed
    // `completeHtml`. The latter recomputes whenever any volatile prop
    // changes - `actionMapping` flipping `{}` ↔ `undefined`, `resolvedData`
    // refetch identity churn, blob URLs resolving async - even when the
    // visible body is identical. Tying the fade to htmlTemplate keeps the
    // smooth transition on real interface swaps (carousel nav, epoch change,
    // pagination producing a different template) while killing the
    // marketplace-preview flicker storm.
    const [iframeOpacity, setIframeOpacity] = React.useState(1);
    const prevHtmlRef = React.useRef<string | null>(null);

    // External-link gate: the injected NAVIGATION_GATE_SCRIPT posts a 'navigation-request'
    // when a click inside the sandboxed iframe would open a real external URL (or publisher JS
    // calls window.open). The sandbox blocks the navigation itself, so without this the link
    // looks dead; we confirm with the user and open it in a new tab. When non-null, the
    // confirmation modal is shown for this URL.
    const [pendingNavUrl, setPendingNavUrl] = React.useState<string | null>(null);

    // Render HTML with appropriate mode. When `removeScripts` is true, also strip inline event
    // handlers (onclick=, onerror=, …) by pre-sanitizing - `removeScripts` alone only strips
    // <script> tags, which leaves DOM-level event handlers exploitable in untrusted contexts.
    const completeHtml = React.useMemo(() => {
      const safeHtml = removeScripts ? sanitizeHtml(htmlTemplate) : htmlTemplate;
      console.log('[InterfaceIframe] render', {
        mode,
        htmlLen: htmlTemplate?.length || 0,
        actionMappingKeys: actionMapping ? Object.keys(actionMapping) : 'undefined',
        triggerDataKeys: triggerData ? Object.keys(triggerData) : 'undefined',
        resolvedDataKeys: resolvedData ? Object.keys(resolvedData) : 'undefined',
        removeScripts,
      });
      const options: RenderOptions = {
        mode,
        resolvedData: mode === 'run' ? resolvedData : undefined,
        removeScripts,
        wrapInDocument: true,
        customCss,
        autoFit,
        actionMapping,
        triggerData: mode === 'run' ? triggerData : undefined,
        jsTemplate: removeScripts ? undefined : jsTemplate,
        resolveFileUrl: mode === 'run' ? resolveFileUrl : undefined,
      };

      return renderInterfaceTemplate(safeHtml, options);
    }, [htmlTemplate, mode, resolvedData, customCss, autoFit, actionMapping, triggerData, jsTemplate, resolveFileUrl, removeScripts]);

    // Fade out briefly when HTML content changes, fade in when iframe loads
    React.useEffect(() => {
      if (prevHtmlRef.current !== null && prevHtmlRef.current !== htmlTemplate) {
        setIframeOpacity(0);
      }
      prevHtmlRef.current = htmlTemplate;
    }, [htmlTemplate]);

    // Listen for postMessage events from the iframe (bridge script + height reporter + file uploads)
    React.useEffect(() => {
      const hasActions = onAction && actionMapping && Object.keys(actionMapping).length > 0;
      if (!hasActions && !onPagination && !onContinue && !onVariablePagination && !onSizeChange && !fileUploadContext) return;

      const handler = async (event: MessageEvent) => {
        // Verify the message comes from our iframe, not a foreign source
        if (event.source !== iframeRef.current?.contentWindow) return;

        if (event.data?.type === '__iframe_size' && onSizeChange) {
          onSizeChange({ width: event.data.width, height: event.data.height });
        }
        if (event.data?.type === 'action-trigger' && event.data.triggerRef && onAction) {
          onAction(event.data.triggerRef, event.data.data || {});
        }
        if (event.data?.type === 'pagination' && event.data.direction && onPagination) {
          onPagination(event.data.direction);
        }
        if (event.data?.type === 'variable-pagination' && event.data.variable && onVariablePagination) {
          onVariablePagination(event.data.variable, event.data.page ?? 0);
        }
        if (event.data?.type === 'continue') {
          console.log('[InterfaceIframe] Received continue message:', event.data, 'onContinue:', !!onContinue);
          if (onContinue) {
            onContinue(event.data.actionKey || '', event.data.data || {});
          }
        }
        // Handle file upload delegation from iframe bridge script
        if (event.data?.type === 'file-upload-request' && fileUploadContext) {
          const { uploadId, fieldName, fileName, mimeType, fileData } = event.data;
          try {
            const file = new File([fileData], fileName, { type: mimeType });
            const fileRef: FileRef = await fileService.uploadFile(file, {
              workflowId: fileUploadContext.workflowId,
              runId: fileUploadContext.runId,
              stepAlias: 'interface',
            });
            // Send FileRef back to the iframe
            iframeRef.current?.contentWindow?.postMessage(
              { type: 'file-upload-response', uploadId, fieldName, fileRef },
              '*'
            );
          } catch (err) {
            console.error('Interface file upload failed:', err);
            iframeRef.current?.contentWindow?.postMessage(
              { type: 'file-upload-response', uploadId, fieldName, fileRef: null, error: String(err) },
              '*'
            );
          }
        }
      };

      window.addEventListener('message', handler);
      return () => window.removeEventListener('message', handler);
    }, [onAction, onPagination, onContinue, onVariablePagination, onSizeChange, actionMapping, iframeRef, fileUploadContext]);

    // External-link gate listener. Always on (independent of the action callbacks above) so
    // every interface surface - chat app, builder preview, detail pages, marketplace - asks for
    // confirmation before opening a link. Only acts on messages from THIS iframe.
    React.useEffect(() => {
      const handler = (event: MessageEvent) => {
        if (event.source !== iframeRef.current?.contentWindow) return;
        if (event.data?.type === 'navigation-request' && typeof event.data.url === 'string' && event.data.url) {
          setPendingNavUrl(event.data.url);
        }
      };
      window.addEventListener('message', handler);
      return () => window.removeEventListener('message', handler);
    }, [iframeRef]);

    // Confirm runs inside the button's click handler so the window.open stays a user gesture
    // (popup blockers allow it). The parent page is not sandboxed, so the new tab opens.
    const confirmNavigation = React.useCallback(() => {
      if (pendingNavUrl) {
        window.open(pendingNavUrl, '_blank', 'noopener,noreferrer');
      }
      setPendingNavUrl(null);
    }, [pendingNavUrl]);

    // Handle iframe load - sizing is now done via postMessage (HEIGHT_REPORTER_SCRIPT)
    const handleLoad = React.useCallback(() => {
      const iframe = iframeRef.current;
      if (!iframe) return;
      // Fade in after new content has loaded
      setIframeOpacity(1);
      onLoad?.(iframe);
    }, [iframeRef, onLoad]);

    return (
      <>
        <iframe
          ref={iframeRef}
          srcDoc={completeHtml}
          sandbox={sandbox}
          className={className}
          style={{
            border: 'none',
            width: '100%',
            opacity: iframeOpacity,
            transition: 'opacity 150ms ease-in-out',
            ...style,
          }}
          onLoad={handleLoad}
        />
        <OpenLinkConfirmModal
          url={pendingNavUrl}
          onConfirm={confirmNavigation}
          onCancel={() => setPendingNavUrl(null)}
        />
      </>
    );
  }
);

InterfaceIframe.displayName = 'InterfaceIframe';

export default InterfaceIframe;
