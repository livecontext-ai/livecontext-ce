'use client';

import * as React from 'react';
import { useAuthedObjectUrl } from '@/hooks/useAuthedObjectUrl';
import { useOnVisibleOnce } from '@/hooks/useOnVisibleOnce';
import {
  resolveMediaMimeType, PDF_FIRST_PAGE_FRAGMENT, VIDEO_POSTER_FRAGMENT,
} from '@/lib/files/filePreview';
import type { AssetPreviewKind } from '@/lib/datatable/assetValue';

export interface AssetMediaPreviewProps {
  /** The kind to render. `none` never reaches here - the cell shows its type icon instead. */
  kind: Exclude<AssetPreviewKind, 'none'>;
  /** The asset's own URL. Internal `/api/...` sources are fetched with the session header. */
  src: string;
  /** File name: alt text, iframe title, and the extension the blob is re-typed from. */
  name: string;
  mimeType?: string;
  /**
   * `true` (card column): the media is playable in place - video and audio carry controls.
   * `false` (thumbnail column): a silent poster frame, because a 56px box has no room for a
   * control bar and the cell's own View action opens the file full size.
   */
  interactive: boolean;
  /** Classes for the wrapper the visibility observer watches. Sizing of the media is per kind. */
  containerClassName?: string;
  /** Shown until the bytes resolve, and instead of a broken element if they never do. */
  fallback?: React.ReactNode;
}

/**
 * The inline media preview of a table media cell: a picture, a clip, a sound or a page, whichever
 * the cell happens to hold. Before this, only images were previewed and every other file was a
 * name next to a grey icon, which is exactly the information the row already carried.
 *
 * <p>Bytes are fetched with the Bearer header and rendered from an in-memory blob URL - the
 * session token is never placed in a URL (see {@link useAuthedObjectUrl}). An external URL is
 * loaded by the element directly, with no token attached to it.
 *
 * <p>Deliberately NOT shared with the other surfaces that preview a stored file (the Files tile
 * {@code FileThumb}, the node-canvas {@code FilePreviewCard}, the full {@code FileDetailView}):
 * all four agree on the classification, on the media fragments and on forcing a framed type (all
 * read {@code lib/files/filePreview} and {@code useAuthedObjectUrl}) and disagree on everything
 * else - size caps, controls, lazy policy. A component parameterised over all of that would be an
 * abstraction, not reuse.
 */
export function AssetMediaPreview({
  kind,
  src,
  name,
  mimeType,
  interactive,
  containerClassName,
  fallback = null,
}: AssetMediaPreviewProps) {
  // A thumbnail column has nothing to show for a sound or a page (see renderMedia), so it must
  // not pay for their bytes either.
  const renderable = interactive || kind === 'image' || kind === 'video';
  // Only an image is worth fetching for every row on sight: an image IS its own thumbnail, while
  // a video/PDF/sound blob is the WHOLE file (the bytes need the session header, so there is no
  // range request to be had). A page of 50 media rows would otherwise download 50 files nobody
  // scrolled to. Same policy as the Files browser's tiles.
  //
  // Known limit, shared with that browser and NOT solved here: the gate defers the first fetch,
  // it releases nothing. useAuthedObjectUrl revokes on unmount or on a changed src, so a row
  // scrolled PAST keeps its whole file in memory until the grid unmounts. Fine for the page
  // sizes a table serves today; the fix, if a bigger one arrives, is releasing on scroll-out,
  // which needs a hook that keeps observing instead of latching once.
  const eager = kind === 'image';
  const [visibilityRef, seen] = useOnVisibleOnce(renderable && !eager);
  const wanted = renderable && (eager || seen);

  // Keyed by the SOURCE, never a boolean: the grid re-renders this instance with the next row's
  // value rather than remounting it, so a boolean latch would keep hiding every later file in
  // that cell. Not the blob URL either - that one is re-created on each fetch.
  const [failedSrc, setFailedSrc] = React.useState<string | null>(null);
  const failed = failedSrc === src;
  const onError = React.useCallback(() => setFailedSrc(src), [src]);

  // A page is FRAMED, so its bytes become a document on this app's origin: its type is forced,
  // never merely hinted. The stored type is not evidence here - the cell's copy of it is written
  // by whoever wrote the row, and the served one comes from the storage row, so neither can say
  // whether the bytes are a PDF. Forcing makes anything else render as a broken PDF instead of
  // running, INCLUDING an honestly mislabelled one (a PNG named `report.pdf` used to show as an
  // image in the frame and now shows as a broken page): from here that is indistinguishable from
  // the attack, and a broken preview is the cheaper of the two mistakes. Every other kind decodes
  // by content and executes nothing, so a hint is enough there - it only rescues a clip whose
  // stored type is missing, which would otherwise be a black box.
  //
  // Only OUR bytes can be forced: an external URL is loaded by the element directly, with no blob
  // in between (see useAuthedObjectUrl), so a cell holding someone else's `.pdf` link frames
  // whatever that host serves. It lands on a foreign origin, so it cannot touch this session, and
  // `pointer-events-none` keeps it from being interacted with inside the row.
  const framed = kind === 'pdf';
  const { url, error } = useAuthedObjectUrl(
    wanted && !failed ? src : null,
    resolveMediaMimeType(mimeType, name),
    framed ? 'application/pdf' : undefined,
  );

  const media = !url || failed || error
    ? null
    : renderMedia({ kind, url, name, interactive, onError });

  return (
    // The placeholder is not decoration. This div is the observer's target, and a target the
    // CSS hides is never reported as intersecting: an earlier version carried `empty:hidden`
    // here so a card would reserve no strip, and the bytes were then never asked for, so the
    // element stayed empty, so it stayed hidden - a lazy kind was blank at every scroll
    // position, for good. Measured in Chromium: an empty box under `:empty{display:none}`
    // reports isIntersecting=false; the same box unhidden reports true at 0px tall; and with
    // this placeholder it reports true at 0px tall EVEN under that rule, because it is then
    // never `:empty`. So the strip still costs no height, and hiding it cannot come back.
    <div ref={visibilityRef} className={containerClassName}>
      {media ?? fallback ?? <span aria-hidden className="block h-0 w-full" />}
    </div>
  );
}

function renderMedia({
  kind,
  url,
  name,
  interactive,
  onError,
}: {
  kind: Exclude<AssetPreviewKind, 'none'>;
  url: string;
  name: string;
  interactive: boolean;
  onError: () => void;
}): React.ReactNode {
  // Thumbnail column: fill the square, show nothing that can be clicked - the tile IS the preview.
  if (!interactive) {
    if (kind === 'image') {
      /* eslint-disable-next-line @next/next/no-img-element */
      return <img src={url} alt={name} className="h-full w-full object-cover" onError={onError} />;
    }
    if (kind === 'video') {
      return (
        <video
          src={`${url}${VIDEO_POSTER_FRAGMENT}`}
          aria-label={name}
          muted
          playsInline
          preload="metadata"
          onError={onError}
          className="h-full w-full object-cover pointer-events-none"
        />
      );
    }
    // A sound has no frame to show and a PDF page is a grey smudge at 56px: the type icon says
    // more than either would. Both stay previewable through the cell's View action.
    return null;
  }

  // Card column. The grid gives a cell 128px minus its own 8px padding rows, and the name row
  // under the media takes ~36 of what is left: 64px is what a preview can have before the cell
  // starts to scroll. It is also the height the image strip has always had, so adding three
  // kinds does not move the rows that were already there.
  if (kind === 'image') {
    /* eslint-disable-next-line @next/next/no-img-element */
    return <img src={url} alt={name} className="max-h-16 w-full object-contain" onError={onError} />;
  }
  if (kind === 'video') {
    return (
      <video
        src={url}
        aria-label={name}
        controls
        playsInline
        preload="metadata"
        onError={onError}
        className="max-h-16 w-full"
      />
    );
  }
  if (kind === 'audio') {
    // No height cap: the native control bar has a fixed height of its own, and squashing it is
    // how you get a play button nobody can hit.
    return <audio src={url} aria-label={name} controls preload="metadata" onError={onError} className="w-full" />;
  }
  // A page, in a frame whose blob was forced to application/pdf upstream.
  return (
    <iframe
      // First page, no reader chrome. `pointer-events-none` keeps the scroll wheel on the table
      // instead of inside the document.
      src={`${url}${PDF_FIRST_PAGE_FRAGMENT}`}
      title={name}
      tabIndex={-1}
      className="h-16 w-full pointer-events-none bg-white"
    />
  );
}

