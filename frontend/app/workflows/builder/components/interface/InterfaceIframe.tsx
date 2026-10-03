'use client';

import * as React from 'react';
import {
  renderInterfaceTemplate,
  sanitizeHtml,
  isOpenableNavigationUrl,
  RenderMode,
  RenderOptions,
} from '../../utils/interfaceHtmlUtils';
import { fileService, type FileRef } from '@/lib/api/orchestrator/file.service';
import { useInterfaceFileUrls } from './useInterfaceFileUrls';
import { OpenLinkConfirmModal } from './OpenLinkConfirmModal';

/** The interface-rendering shell route (LC-027): see app/interface-frame/route.ts. */
const INTERFACE_FRAME_SRC = '/interface-frame';

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
  /**
   * Trigger data to pre-fill the forms with REGARDLESS of `mode`, overriding
   * {@link triggerData}. Used by the application's "load the template values" action:
   * the app whose run has no data yet renders in `edit` mode (so `{{var|default}}`
   * pipes resolve), and `triggerData` is dropped in that mode - which is exactly the
   * case where showing the publisher's example inputs matters most. Pass `undefined`
   * to keep the standard mode-gated behavior.
   */
  prefillTriggerData?: Record<string, Record<string, unknown>>;
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
  /**
   * Mute state for the interface's own `<audio>`/`<video>`.
   *
   * `undefined` (the default) means "not managed": the page plays exactly as its
   * author wrote it, which is what every editing and running surface wants.
   * Passing a boolean hands this component the volume, and a preview grid should
   * pass `true` - a wall of cards that starts talking at the visitor is the
   * reason this exists.
   *
   * Flipping it does NOT re-render the document: the initial value is baked into
   * the HTML and later changes travel as a message, so unmuting cannot restart
   * the interface or lose its state.
   */
  mediaMuted?: boolean;
  /**
   * Fired with whether this interface contains any `<audio>`/`<video>` at all
   * (re-fired if that changes, e.g. media injected by the interface's own JS).
   *
   * The host cannot answer this itself - the iframe is sandboxed and
   * cross-origin - and it is what lets a sound control appear only on the
   * interfaces that can actually make a sound.
   */
  onMediaAudioPresence?: (hasAudio: boolean) => void;
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
      prefillTriggerData,
      jsTemplate,
      onAction,
      onPagination,
      onContinue,
      onVariablePagination,
      fileUploadContext,
      removeScripts = false,
      mediaMuted,
      onMediaAudioPresence,
    },
    ref
  ) => {
    const internalRef = React.useRef<HTMLIFrameElement>(null);
    const iframeRef = (ref as React.RefObject<HTMLIFrameElement>) || internalRef;

    // Interfaces that keep their scripts (removeScripts=false) render through the
    // `/interface-frame` shell instead of `srcDoc`: an `about:srcdoc` document inherits the
    // embedder's CSP, which is why the app's own CSP could never enforce a strict `script-src`
    // (LC-027 CASA E3 - see lib/security/securityHeaders.mjs's module header for the full
    // rationale). `removeScripts=true` surfaces (marketplace/showcase previews, thumbnails)
    // contain no script at all by construction, so `srcDoc` stays exactly as it was - there is
    // nothing there a strict script-src would need to exempt.
    const useShell = !removeScripts;

    // The mute state the document is BUILT with, captured once. See the memo below.
    const initialMediaMutedRef = React.useRef(mediaMuted);

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
        triggerData: prefillTriggerData ?? (mode === 'run' ? triggerData : undefined),
        jsTemplate: removeScripts ? undefined : jsTemplate,
        resolveFileUrl: mode === 'run' ? resolveFileUrl : undefined,
        // Only the FIRST value is baked in. `completeHtml` is a srcDoc: making it
        // depend on the live prop would reload the whole interface on every
        // mute toggle, throwing away its scroll position and any state its JS
        // built. Runtime changes go through postMessage instead (below).
        muteMedia: initialMediaMutedRef.current,
      };

      return renderInterfaceTemplate(safeHtml, options);
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [htmlTemplate, mode, resolvedData, customCss, autoFit, actionMapping, triggerData, prefillTriggerData, jsTemplate, resolveFileUrl, removeScripts]);

    // Fade out briefly when HTML content changes, fade in when iframe loads
    React.useEffect(() => {
      if (prevHtmlRef.current !== null && prevHtmlRef.current !== htmlTemplate) {
        setIframeOpacity(0);
      }
      prevHtmlRef.current = htmlTemplate;
    }, [htmlTemplate]);

    // Shell handshake (useShell only - see the flag above). The shell (always '/interface-frame')
    // receives the rendered HTML over postMessage and replaces its own document with
    // `document.open()/write()/close()`, instead of inheriting the bytes from the embedder as a
    // srcDoc would. That write erases the shell's listener, so each shell document takes exactly
    // ONE payload: a later `completeHtml` change reloads the shell (deliverToShell), which is what
    // a srcDoc change did too (a fresh document per content). `shellReadyRef` gates delivery
    // on the shell's own 'interface-frame-ready' announcement (its listener attaches during
    // parsing, before the shell's trivial document finishes loading, so this handshake - not
    // the iframe's native `load` event - is the reliable "safe to postMessage" signal).
    const shellReadyRef = React.useRef(false);
    const pendingShellHtmlRef = React.useRef<string | null>(null);
    // True once at least one content payload has been POSTED to the shell. Gates handleLoad
    // (below): the shell's OWN initial (empty) document also fires the iframe's native `load`
    // event, and that must NOT be mistaken for "the interface finished loading" (premature
    // fade-in / onLoad callback / mute re-arm).
    const shellContentDeliveredRef = React.useRef(false);

    const deliverToShell = React.useCallback((html: string) => {
      const win = iframeRef.current?.contentWindow;
      if (!win) {
        pendingShellHtmlRef.current = html;
        return;
      }
      if (!shellReadyRef.current) {
        pendingShellHtmlRef.current = html;
        return;
      }
      if (shellContentDeliveredRef.current) {
        // A document was already written, and the shell's document.open() erased every listener
        // on its window, its own 'interface-frame-html' one included: a second postMessage would
        // land in a document that no longer listens (the interface stayed on its first render,
        // e.g. "[title]" placeholders before the row data arrived). Re-setting `src` navigates the
        // shell to a FRESH document, exactly like a srcDoc change did, which also gives the
        // interface's own scripts a fresh global (a reused window would re-declare their
        // top-level let/const and throw). The new shell announces 'ready' and gets this html.
        pendingShellHtmlRef.current = html;
        shellReadyRef.current = false;
        shellContentDeliveredRef.current = false;
        iframeRef.current?.setAttribute('src', INTERFACE_FRAME_SRC);
        return;
      }
      shellContentDeliveredRef.current = true;
      win.postMessage({ type: 'interface-frame-html', html }, '*');
    }, [iframeRef]);

    React.useEffect(() => {
      if (!useShell) return;
      deliverToShell(completeHtml);
    }, [useShell, completeHtml, deliverToShell]);

    React.useEffect(() => {
      if (!useShell) return;
      const handler = (event: MessageEvent) => {
        if (event.source !== iframeRef.current?.contentWindow) return;
        if (event.data?.type === 'interface-frame-ready') {
          shellReadyRef.current = true;
          const pending = pendingShellHtmlRef.current ?? completeHtml;
          pendingShellHtmlRef.current = null;
          deliverToShell(pending);
        }
      };
      window.addEventListener('message', handler);
      return () => window.removeEventListener('message', handler);
      // completeHtml intentionally omitted: the ready handshake only needs the LATEST value at
      // the moment 'ready' fires, which the effect above already keeps pendingShellHtmlRef/the
      // live document in sync with. Re-subscribing on every content change would not change
      // behavior here (the listener body reads live refs), only churn the listener.
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [useShell, iframeRef, deliverToShell]);

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
        if (event.data?.type === 'navigation-request') {
          // Validate the SCHEME here, on the parent side (LC-076). The in-frame classifier is
          // bypassed by a hostile interface posting this message itself, and the parent is NOT
          // sandboxed, so a javascript:/data:/blob: URL opened from here would run on the app
          // origin. Rejected URLs never reach the confirmation modal.
          if (isOpenableNavigationUrl(event.data.url)) {
            setPendingNavUrl(event.data.url as string);
          }
        }
      };
      window.addEventListener('message', handler);
      return () => window.removeEventListener('message', handler);
    }, [iframeRef]);

    // Audio presence, reported by MEDIA_AUDIO_SCRIPT. Always listening (like the
    // navigation gate) so any surface can ask; only messages from THIS iframe count.
    React.useEffect(() => {
      if (!onMediaAudioPresence) return;
      const handler = (event: MessageEvent) => {
        if (event.source !== iframeRef.current?.contentWindow) return;
        if (event.data?.type === '__iframe_audio') {
          onMediaAudioPresence(!!event.data.hasAudio);
        }
      };
      window.addEventListener('message', handler);
      return () => window.removeEventListener('message', handler);
    }, [iframeRef, onMediaAudioPresence]);

    // Push the current mute state into the frame. Re-sent on every load as well
    // as on change: a reload (new htmlTemplate, epoch swap) rebuilds the document
    // from the INITIAL value, so without this the sound the visitor turned on
    // would silently turn itself back off.
    const [iframeLoadCount, setIframeLoadCount] = React.useState(0);
    React.useEffect(() => {
      if (mediaMuted === undefined) return;
      iframeRef.current?.contentWindow?.postMessage(
        { type: '__iframe_set_muted', muted: mediaMuted },
        '*',
      );
    }, [mediaMuted, iframeLoadCount, iframeRef]);

    // Confirm runs inside the button's click handler so the window.open stays a user gesture
    // (popup blockers allow it). The parent page is not sandboxed, so the new tab opens.
    const confirmNavigation = React.useCallback(() => {
      // Re-checked at the sink as well as at intake: window.open is what actually navigates.
      if (pendingNavUrl && isOpenableNavigationUrl(pendingNavUrl)) {
        window.open(pendingNavUrl, '_blank', 'noopener,noreferrer');
      }
      setPendingNavUrl(null);
    }, [pendingNavUrl]);

    // Handle iframe load - sizing is now done via postMessage (HEIGHT_REPORTER_SCRIPT)
    const handleLoad = React.useCallback(() => {
      const iframe = iframeRef.current;
      if (!iframe) return;
      // Shell mode fires a native `load` TWICE: once for the shell's own (empty) document, once
      // more after document.write() replaces it with real content. Only the second is "the
      // interface loaded" - the first must be ignored, or fade-in/onLoad/mute-rearm would fire
      // on a blank frame a moment before the real content lands.
      if (useShell && !shellContentDeliveredRef.current) return;
      // Fade in after new content has loaded
      setIframeOpacity(1);
      // Re-arms the mute push above: a fresh document starts from the baked-in value.
      setIframeLoadCount((n) => n + 1);
      onLoad?.(iframe);
    }, [iframeRef, onLoad, useShell]);

    return (
      <>
        <iframe
          ref={iframeRef}
          {...(useShell ? { src: INTERFACE_FRAME_SRC } : { srcDoc: completeHtml })}
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
