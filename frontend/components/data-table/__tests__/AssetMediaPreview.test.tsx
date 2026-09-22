// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useAuthedObjectUrl } from '@/hooks/useAuthedObjectUrl';

// The authenticated blob fetch has its own test. Standing it in as a spy is what lets these
// tests assert the thing that actually costs the user something: WHEN the bytes are asked for.
vi.mock('@/hooks/useAuthedObjectUrl', () => ({ useAuthedObjectUrl: vi.fn() }));
const mockUseAuthed = vi.mocked(useAuthedObjectUrl);

import { AssetMediaPreview } from '../cells/AssetMediaPreview';

/** The src the hook was asked for on the latest render - null means "no bytes wanted". */
const lastRequestedSrc = () => mockUseAuthed.mock.calls[mockUseAuthed.mock.calls.length - 1][0];

type Observer = {
  callback: (entries: { isIntersecting: boolean }[]) => void;
  targets: Element[];
};
let observers: Observer[] = [];

/**
 * Install a controllable IntersectionObserver. jsdom has none, and the hook then fails OPEN
 * (reveals immediately) - so a test that does not install one is testing the gate switched off.
 * The observed elements are recorded because the browser bug this suite exists to catch is about
 * the TARGET, not the callback: a target the CSS hides is never reported as intersecting.
 */
function withObserver() {
  class FakeObserver {
    private record: Observer;
    constructor(callback: (entries: { isIntersecting: boolean }[]) => void) {
      this.record = { callback, targets: [] };
      observers.push(this.record);
    }
    observe(target: Element) { this.record.targets.push(target); }
    unobserve() {}
    disconnect() {}
  }
  vi.stubGlobal('IntersectionObserver', FakeObserver);
}

/** The element the latch is watching - the one that has to stay measurable. */
const observedTarget = () => observers[observers.length - 1].targets[0];

const scrollIntoView = () =>
  act(() => observers[observers.length - 1].callback([{ isIntersecting: true }]));

beforeEach(() => {
  observers = [];
  mockUseAuthed.mockImplementation((src) => ({ url: src ?? null, loading: false, error: false }));
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  mockUseAuthed.mockReset();
});

const ICON = <svg data-testid="type-icon" />;

describe('AssetMediaPreview', () => {
  it('plays a clip in place on a card column', () => {
    const { container } = render(
      <AssetMediaPreview kind="video" src="/api/f/clip" name="clip.mp4" interactive />,
    );

    const video = container.querySelector('video');
    expect(video).toBeInTheDocument();
    expect(video).toHaveAttribute('controls');
  });

  it('plays a sound in place on a card column', () => {
    const { container } = render(
      <AssetMediaPreview kind="audio" src="/api/f/voice" name="voice.mp3" interactive />,
    );

    expect(container.querySelector('audio')).toHaveAttribute('controls');
  });

  it('shows the first page of a document, with no reader chrome', () => {
    const { container } = render(
      <AssetMediaPreview kind="pdf" src="/api/f/doc" name="invoice.pdf" interactive />,
    );

    const frame = container.querySelector('iframe');
    expect(frame).toHaveAttribute('title', 'invoice.pdf');
    expect(frame?.getAttribute('src')).toContain('toolbar=0');
    // Both keep the frame out of the way of the table: without them the scroll wheel lands
    // inside the document, and every row adds a tab stop.
    expect(frame?.className).toContain('pointer-events-none');
    expect(frame).toHaveAttribute('tabindex', '-1');
  });

  it('shows a clip as a silent frame on a thumbnail column, never a control bar', () => {
    // 56px of box: a control bar would cover the picture it is meant to preview.
    const { container } = render(
      <AssetMediaPreview kind="video" src="/api/f/clip" name="clip.mp4" interactive={false} />,
    );

    const video = container.querySelector('video');
    expect(video).not.toHaveAttribute('controls');
    // Past frame zero: the first frame of a fade-in is black, which reads as a broken preview.
    expect(video?.getAttribute('src')).toContain('#t=0.1');
  });

  it('fetches an image on sight - it IS its own thumbnail', () => {
    withObserver();
    render(<AssetMediaPreview kind="image" src="/api/f/pic" name="pic.png" interactive />);

    expect(lastRequestedSrc()).toBe('/api/f/pic');
  });

  it('asks for no bytes until a clip is scrolled to, then asks once', () => {
    // The whole point of the gate: a media blob is the WHOLE file, and a page of 50 video rows
    // would download 50 files nobody looked at.
    withObserver();
    const { container } = render(
      <AssetMediaPreview kind="video" src="/api/f/clip" name="clip.mp4" interactive />,
    );

    expect(lastRequestedSrc()).toBeNull();
    expect(container.querySelector('video')).toBeNull();

    scrollIntoView();

    expect(lastRequestedSrc()).toBe('/api/f/clip');
    expect(container.querySelector('video')).toBeInTheDocument();
  });

  it('asks for no bytes at all for what a thumbnail column cannot show', () => {
    // A sound has no frame and a page is a smudge at 56px, so both fall back to the type icon.
    // Downloading them to render that icon would be paying for nothing.
    withObserver();
    render(
      <AssetMediaPreview
        kind="audio" src="/api/f/voice" name="voice.mp3" interactive={false} fallback={ICON}
      />,
    );

    expect(lastRequestedSrc()).toBeNull();
    expect(observers).toHaveLength(0);
    expect(screen.getByTestId('type-icon')).toBeInTheDocument();
  });

  it('falls back to the icon when the bytes never resolve', () => {
    mockUseAuthed.mockReturnValue({ url: null, loading: false, error: true });
    const { container } = render(
      <AssetMediaPreview kind="video" src="/api/f/clip" name="clip.mp4" interactive fallback={ICON} />,
    );

    expect(container.querySelector('video')).toBeNull();
    expect(screen.getByTestId('type-icon')).toBeInTheDocument();
  });

  it('keeps the element it watches measurable while it is still empty', () => {
    // THE regression this file exists for. The observer's target was briefly given `empty:hidden`
    // so a card would reserve no strip for media that never arrived. A `display:none` element
    // never intersects, so the latch could never fire, so the bytes were never requested, so the
    // element stayed empty: a self-locking loop that made every clip, sound and page in a card
    // column permanently blank at any scroll position. Nothing may hide this element, and it must
    // always carry a box - hence the zero-height placeholder.
    withObserver();
    const { container } = render(
      <AssetMediaPreview kind="video" src="/api/f/clip" name="clip.mp4" interactive />,
    );

    const target = observedTarget();
    expect(target).toBe(container.firstElementChild);
    // The invariant is the child, not the class list: measured in Chromium, this element is
    // reported as intersecting even under `:empty{display:none}` as long as it is not `:empty`.
    // Asserting on class NAMES instead would fail on an innocent `overflow-hidden` and would
    // still miss `invisible`, a hiding ancestor, or `content-visibility`.
    expect(target.childElementCount).toBeGreaterThan(0);
  });

  it('shows a card no strip for media that never arrived', () => {
    // The placeholder above must not become a visible band: it is zero-height on purpose, and a
    // card column passes no fallback precisely so a failed file leaves the row as it was.
    mockUseAuthed.mockReturnValue({ url: null, loading: false, error: true });
    const { container } = render(
      <AssetMediaPreview kind="video" src="/api/f/clip" name="clip.mp4" interactive />,
    );

    const placeholder = container.firstElementChild?.firstElementChild as HTMLElement;
    expect(placeholder.tagName).toBe('SPAN');
    expect(placeholder.className).toContain('h-0');
    expect(placeholder).toHaveAttribute('aria-hidden');
  });

  it('asks the blob to be re-typed from the file name, or a clip decodes as nothing', () => {
    // Our own raw serve answers application/octet-stream for a row with no stored mime, and a
    // <video> cannot decode that. The hint is the second argument and it has no other witness.
    render(<AssetMediaPreview kind="video" src="/api/f/clip" name="clip.mp4" interactive />);

    expect(mockUseAuthed).toHaveBeenLastCalledWith('/api/f/clip', 'video/mp4', undefined);
  });

  it('forces the type of the bytes it is about to FRAME, whatever the row claims', () => {
    // The one kind rendered as a document rather than as media, so the one kind where being
    // wrong executes something: a blob URL inherits this app's origin, and a file stored as
    // text/html under a `.pdf` name would run its own script here, in a row nobody clicked.
    // Neither the cell's copy of the type nor the served one can settle what the bytes are -
    // both are written by whoever wrote the file. Forcing makes anything else a broken PDF.
    render(
      <AssetMediaPreview
        kind="pdf" src="/api/f/x" name="invoice.pdf" mimeType="text/html" interactive
      />,
    );

    // The hint still carries the row's own type; the third argument is the one that decides.
    expect(mockUseAuthed).toHaveBeenLastCalledWith('/api/f/x', 'text/html', 'application/pdf');
    cleanup();

    // The honest case takes the same path: same forced type, and it still frames.
    render(
      <AssetMediaPreview
        kind="pdf" src="/api/f/a" name="a.pdf" mimeType="application/pdf" interactive
      />,
    );
    expect(mockUseAuthed).toHaveBeenLastCalledWith('/api/f/a', 'application/pdf', 'application/pdf');
    expect(screen.getByTitle('a.pdf')).toBeInTheDocument();
  });

  it('only HINTS the type of what it plays, since media executes nothing', () => {
    // Forcing here would be wrong, not merely useless: a clip stored as video/webm under an
    // `.mp4` name would stop decoding. The hint exists only to rescue a missing type.
    render(
      <AssetMediaPreview
        kind="video" src="/api/f/v" name="clip.mp4" mimeType="video/webm" interactive
      />,
    );

    expect(mockUseAuthed).toHaveBeenLastCalledWith('/api/f/v', 'video/webm', undefined);
  });

  it('shows the next file after one that failed to decode, instead of latching', () => {
    // The failure is keyed by the source, not by a boolean: the grid re-renders this instance
    // with the next row's value rather than remounting it, so a latch would hide every later
    // file in that cell until the page was reloaded.
    const { container, rerender } = render(
      <AssetMediaPreview kind="video" src="/api/f/broken" name="broken.mp4" interactive fallback={ICON} />,
    );

    fireEvent.error(container.querySelector('video')!);
    expect(container.querySelector('video')).toBeNull();
    expect(screen.getByTestId('type-icon')).toBeInTheDocument();

    rerender(
      <AssetMediaPreview kind="video" src="/api/f/fixed" name="fixed.mp4" interactive fallback={ICON} />,
    );

    expect(container.querySelector('video')).toBeInTheDocument();
  });

  it('stops asking for the bytes of a file that failed', () => {
    // A file that cannot be decoded will not decode on the next render either; re-fetching it
    // would download it again on every keystroke in the grid.
    const { container } = render(
      <AssetMediaPreview kind="video" src="/api/f/broken" name="broken.mp4" interactive fallback={ICON} />,
    );

    fireEvent.error(container.querySelector('video')!);

    expect(lastRequestedSrc()).toBeNull();
  });
});
