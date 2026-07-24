'use client';

import * as React from 'react';
import { InterfaceShadowPreview } from './InterfaceShadowPreview';
import { fitScale, useMeasuredBox } from '@/lib/interfaces/useFitScale';
import type { RenderMode } from '../../utils/interfaceHtmlUtils';

/**
 * Default virtual viewport used for thumbnail rendering when the caller does
 * not pass a `viewport` prop. The interface is rendered at full page size,
 * then CSS-scaled down to fit the card container - giving a consistent,
 * predictable miniature. Callers that know the interface's declared `format`
 * pass its resolved dimensions via `viewport` instead, so the thumbnail has
 * the shape the interface was authored for.
 */
const VIRTUAL_VIEWPORT = { width: 1280, height: 800 } as const;

/**
 * How the thumbnail fills its container (`vw`/`vh` = the virtual viewport, default 1280/800):
 *   - `'width'` (default): observe own width, height = `width × vh/vw`, optionally clipped by `maxHeight`. Best for cards with flexible height.
 *   - `'contain'`: observe both width AND height, scale = `min(w/vw, h/vh)`, letterboxed inside the box. Best when the parent dictates a fixed shape (canvas nodes, marketplace tiles, fixed `aspectRatio` wrappers).
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
  /**
   * Virtual viewport (px) the interface renders at before CSS-scaling. Defaults to the
   * classic {@code {width: 1280, height: 800}}. Pass the resolved dimensions of the
   * interface's own {@code format} so the thumbnail matches the shape it was authored for
   * (and the screenshot/video capture).
   */
  viewport?: { width: number; height: number };
  /**
   * Extra className for the letterboxed inner frame - the box that is EXACTLY the
   * viewport's aspect ratio (`vp * scale`). Lets a caller decorate the interface's real
   * format (rounded clipping, a status ring that hugs the content) instead of the outer
   * container, which may be letterboxed. Contain mode only; ignored in width-fit mode
   * (there the whole component already is the format frame).
   */
  frameClassName?: string;
  /**
   * Style painted on a dedicated OVERLAY above the frame's content (absolute inset-0,
   * pointer-events none, borderRadius inherited from the frame). Use an INSET box-shadow
   * ring here: on the frame element itself it would paint BELOW the iframe content
   * (CSS paint order) and stay invisible over any opaque interface background, and an
   * outward shadow gets clipped by the overflow-hidden ancestors. Contain mode only.
   */
  frameStyle?: React.CSSProperties;
}

/**
 * **The single thumbnail primitive for interfaces.** Renders any interface HTML in a
 * virtual viewport (1280×800 by default, or the caller-supplied `viewport`) and CSS-scales
 * it down to fit the parent - Figma-style frame thumbnail with predictable, consistent
 * miniatures.
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
  viewport,
  frameClassName,
  frameStyle,
}: InterfaceThumbnailProps) {
  const vp = viewport ?? VIRTUAL_VIEWPORT;
  // Measuring + fit maths are shared with the other surfaces that render an interface at its own
  // fixed viewport (see lib/interfaces/useFitScale).
  const [ref, box] = useMeasuredBox<HTMLDivElement>();

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

  // Contain mode: parent-driven box (w×h), letterboxed scale = min(w/vw, h/vh).
  if (fit === 'contain') {
    if (box.width <= 0 || box.height <= 0) {
      return <div ref={ref} className={className} style={{ width: '100%', height: '100%' }} />;
    }
    const scale = fitScale(box, vp, 'contain');
    return (
      <div
        ref={ref}
        // Centred on both axes: in contain mode the whole interface fits by construction, so
        // top-aligning it just leaves the letterbox margin all at the bottom - most visible on a
        // vertical interface, which is narrow and leaves a lot of empty box around it.
        className={`flex items-center justify-center overflow-hidden ${className || ''}`}
        style={{ width: '100%', height: '100%' }}
      >
        <div
          className={frameClassName}
          style={{
            width: vp.width * scale,
            height: vp.height * scale,
            position: 'relative',
          }}
        >
          <div
            style={{
              width: vp.width,
              height: vp.height,
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
              style={{ width: vp.width, height: vp.height }}
              removeScripts={dropJs}
              actionMapping={actionMapping}
              triggerData={triggerData}
            />
          </div>
          {frameStyle && (
            <div
              aria-hidden
              data-testid="frame-ring-overlay"
              style={{
                position: 'absolute',
                inset: 0,
                pointerEvents: 'none',
                borderRadius: 'inherit',
                ...frameStyle,
              }}
            />
          )}
        </div>
      </div>
    );
  }

  // Width-fit mode: fill width, height derives from the viewport ratio (clipped by maxHeight).
  if (box.width <= 0) {
    return <div ref={ref} className={className} />;
  }
  const scale = fitScale(box, vp, 'width');
  const naturalHeight = vp.height * scale;
  const displayHeight = maxHeight != null ? Math.min(naturalHeight, maxHeight) : naturalHeight;

  return (
    <div
      ref={ref}
      className={`overflow-hidden relative ${className || ''}`}
      style={{ height: displayHeight }}
    >
      <div
        style={{
          width: vp.width,
          height: vp.height,
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
          style={{ width: vp.width, height: vp.height }}
          removeScripts={dropJs}
          actionMapping={actionMapping}
          triggerData={triggerData}
        />
      </div>
    </div>
  );
}
