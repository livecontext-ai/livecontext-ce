'use client';

import * as React from 'react';
import { InterfaceShadowPreview } from './InterfaceShadowPreview';
import type { RenderMode } from '../../utils/interfaceHtmlUtils';

/**
 * Virtual viewport used for thumbnail rendering.
 * The interface is rendered at full page size (1280×800), then CSS-scaled
 * down to fit the card container - giving a consistent, predictable miniature.
 */
const VIRTUAL_VIEWPORT = { width: 1280, height: 800 } as const;

/**
 * How the thumbnail fills its container:
 *   - `'width'` (default): observe own width, height = `width × 800/1280`, optionally clipped by `maxHeight`. Best for cards with flexible height.
 *   - `'contain'`: observe both width AND height, scale = `min(w/1280, h/800)`, letterboxed inside the box. Best when the parent dictates a fixed shape (canvas nodes, marketplace tiles, fixed `aspectRatio` wrappers).
 *
 * In both modes the component fills its parent (`width: 100%`, `height: 100%` in contain), so callers control the box via CSS - no `containerWidth`/`Height` plumbing needed.
 */
export type ThumbnailFit = 'width' | 'contain';

export interface InterfaceThumbnailProps {
  htmlTemplate: string;
  mode?: RenderMode;
  resolvedData?: Record<string, unknown>;
  customCss?: string;
  jsTemplate?: string;
  className?: string;
  /** Height cap (px) in width-fit mode. Ignored in contain mode (the parent's height wins). */
  maxHeight?: number;
  /** Layout strategy. See {@link ThumbnailFit}. Defaults to `'width'`. */
  fit?: ThumbnailFit;
  /** Drop publisher-supplied JS (security: marketplace/showcase viewers must not run arbitrary scripts). When true, also strips inline `on*=` handlers via `sanitizeHtml` inside `InterfaceIframe`. */
  dropJs?: boolean;
  /** Branded empty state rendered when `htmlTemplate` is blank. Iframe is not mounted. */
  emptyLabel?: string;
  /**
   * Optional action mapping (CSS selector → trigger ref) forwarded to the
   * iframe's bridge script. When present together with `triggerData`, the
   * bridge's {@code prefillForms()} populates the corresponding form fields
   * with the previous trigger's submitted values - the difference between
   * "marketplace card shows the form blank" and "marketplace card shows the
   * exact prompt and dropdowns the publisher chose".
   */
  actionMapping?: Record<string, string>;
  /** Previous trigger data (trigger ref → field values) used by the bridge for form pre-fill. Pass with {@code actionMapping}. */
  triggerData?: Record<string, Record<string, unknown>>;
}

/**
 * **The single thumbnail primitive for interfaces.** Renders any interface HTML in a
 * 1280×800 virtual viewport and CSS-scales it down to fit the parent - Figma-style frame
 * thumbnail with predictable, consistent miniatures.
 *
 * Used by every preview surface in the app:
 *   - Interface list cards (`/app/interface`)
 *   - Marketplace cards / showcase
 *   - Chat inline preview blocks
 *   - Workflow canvas nodes (`FlowNode`, `InterfacePreviewNode`)
 *   - Any other place that needs a clickable interface thumbnail
 *
 * Callers control the box dimensions via CSS on the parent (`h-[300px]`, `aspectRatio: '16 / 10'`,
 * `w-full h-full`, …). This component self-measures and scales accordingly - no `ResizeObserver`,
 * no manual width/height plumbing in call sites.
 *
 * The fullscreen native rendering path (`/app/interface/[id]`) intentionally does NOT use this
 * primitive - it goes straight through `<InterfacePreview autoFit={false}>` for native page sizing.
 */
export function InterfaceThumbnail({
  htmlTemplate,
  mode = 'edit',
  resolvedData,
  customCss,
  jsTemplate,
  className,
  maxHeight,
  fit = 'width',
  dropJs,
  emptyLabel,
  actionMapping,
  triggerData,
}: InterfaceThumbnailProps) {
  const ref = React.useRef<HTMLDivElement>(null);
  const [box, setBox] = React.useState<{ width: number; height: number }>({ width: 0, height: 0 });

  // Self-measure both dimensions. Width is always needed; height is only used in contain mode
  // but we observe it unconditionally so a caller can switch `fit` at runtime without remount.
  React.useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const r = el.getBoundingClientRect();
    setBox({ width: r.width, height: r.height });
    const obs = new ResizeObserver(([entry]) => {
      const cr = entry.contentRect;
      setBox({ width: cr.width, height: cr.height });
    });
    obs.observe(el);
    return () => obs.disconnect();
  }, []);

  // Branded empty state - do not mount the iframe for a blank template.
  if (!htmlTemplate || !htmlTemplate.trim()) {
    if (emptyLabel) {
      return (
        <div
          ref={ref}
          className={`flex items-center justify-center text-xs text-theme-muted ${className || ''}`}
          style={fit === 'contain' ? { width: '100%', height: '100%' } : undefined}
        >
          {emptyLabel}
        </div>
      );
    }
    return null;
  }

  const effectiveJs = dropJs ? undefined : jsTemplate;
  const mergedCss = `body { overflow: hidden !important; }${customCss ? '\n' + customCss : ''}`;

  // Contain mode: parent-driven box (w×h), letterboxed scale = min(w/1280, h/800).
  if (fit === 'contain') {
    if (box.width <= 0 || box.height <= 0) {
      return <div ref={ref} className={className} style={{ width: '100%', height: '100%' }} />;
    }
    const scale = Math.min(box.width / VIRTUAL_VIEWPORT.width, box.height / VIRTUAL_VIEWPORT.height);
    return (
      <div
        ref={ref}
        className={`flex items-start justify-center overflow-hidden ${className || ''}`}
        style={{ width: '100%', height: '100%' }}
      >
        <div
          style={{
            width: VIRTUAL_VIEWPORT.width * scale,
            height: VIRTUAL_VIEWPORT.height * scale,
            position: 'relative',
          }}
        >
          <div
            style={{
              width: VIRTUAL_VIEWPORT.width,
              height: VIRTUAL_VIEWPORT.height,
              transform: `scale(${scale})`,
              transformOrigin: '0 0',
            }}
          >
            <InterfaceShadowPreview
              htmlTemplate={htmlTemplate}
              mode={mode}
              resolvedData={resolvedData}
              customCss={mergedCss}
              jsTemplate={effectiveJs}
              style={{ width: VIRTUAL_VIEWPORT.width, height: VIRTUAL_VIEWPORT.height }}
              removeScripts={dropJs}
              actionMapping={actionMapping}
              triggerData={triggerData}
            />
          </div>
        </div>
      </div>
    );
  }

  // Width-fit mode: fill width, height derives from 16:10 ratio (clipped by maxHeight).
  if (box.width <= 0) {
    return <div ref={ref} className={className} />;
  }
  const scale = box.width / VIRTUAL_VIEWPORT.width;
  const naturalHeight = VIRTUAL_VIEWPORT.height * scale;
  const displayHeight = maxHeight != null ? Math.min(naturalHeight, maxHeight) : naturalHeight;

  return (
    <div
      ref={ref}
      className={`overflow-hidden relative ${className || ''}`}
      style={{ height: displayHeight }}
    >
      <div
        style={{
          width: VIRTUAL_VIEWPORT.width,
          height: VIRTUAL_VIEWPORT.height,
          transform: `scale(${scale})`,
          transformOrigin: '0 0',
        }}
      >
        <InterfaceShadowPreview
          htmlTemplate={htmlTemplate}
          mode={mode}
          resolvedData={resolvedData}
          customCss={mergedCss}
          jsTemplate={effectiveJs}
          style={{ width: VIRTUAL_VIEWPORT.width, height: VIRTUAL_VIEWPORT.height }}
          removeScripts={dropJs}
          actionMapping={actionMapping}
          triggerData={triggerData}
        />
      </div>
    </div>
  );
}
