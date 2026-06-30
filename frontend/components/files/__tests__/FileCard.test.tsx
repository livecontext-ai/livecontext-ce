// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, cleanup, waitFor, act } from '@testing-library/react';
import type { StorageExplorerEntry } from '@/lib/api/storage-api';
import { FileCard } from '../FileCard';
import { useAuthedObjectUrl } from '@/hooks/useAuthedObjectUrl';

// Deterministic, dependency-free media URL so the test asserts the chosen element
// type per file kind without pulling the real api-client into the render.
vi.mock('@/lib/api/orchestrator/file.service', () => ({
  getFileUrlById: (id: string, opts?: { inline?: boolean }) => `/raw/${id}?inline=${opts?.inline ?? false}`,
}));

// Isolate FileCard from the real authenticated-blob fetch - the hook has its own test.
// Default: echo the requested URL back as the resolved blob src (null → null).
vi.mock('@/hooks/useAuthedObjectUrl', () => ({
  useAuthedObjectUrl: vi.fn(),
}));
const mockUseAuthed = vi.mocked(useAuthedObjectUrl);

beforeEach(() => {
  mockUseAuthed.mockImplementation((src: string | null | undefined) => ({
    url: src ?? null,
    loading: false,
    error: false,
  }));
});

afterEach(() => { cleanup(); mockUseAuthed.mockReset(); });

function makeEntry(overrides: Partial<StorageExplorerEntry>): StorageExplorerEntry {
  return {
    id: 'file-1',
    fileName: 'sample',
    contentType: null,
    mimeType: null,
    s3Key: null,
    sizeBytes: 1024,
    formattedSize: '1 KB',
    createdAt: '2026-06-01T00:00:00Z',
    sourceType: 'S3_FILE',
    ...overrides,
  } as unknown as StorageExplorerEntry;
}

const noop = () => {};

function renderCard(entry: StorageExplorerEntry) {
  return render(
    <FileCard
      entry={entry}
      selected={false}
      onToggleSelect={noop}
      onOpen={noop}
      onDownload={noop}
      downloadLabel="Download"
    />,
  );
}

describe('FileCard thumbnail', () => {
  it('renders an <img> preview for image files', () => {
    const { container } = renderCard(makeEntry({ mimeType: 'image/png', fileName: 'pic.png' }));
    const img = container.querySelector('img');
    expect(img).toBeTruthy();
    expect(img?.getAttribute('src')).toContain('/raw/file-1');
  });

  it('renders an <iframe> first-page preview for PDF files', async () => {
    // jsdom has no IntersectionObserver, so the in-view gate renders eagerly.
    const { container } = renderCard(makeEntry({ mimeType: 'application/pdf', fileName: 'doc.pdf' }));
    await waitFor(() => expect(container.querySelector('iframe')).toBeTruthy());
    expect(container.querySelector('iframe')?.getAttribute('src')).toContain('toolbar=0');
  });

  it('renders a <video> first-frame preview for video files', async () => {
    const { container } = renderCard(makeEntry({ mimeType: 'video/mp4', fileName: 'clip.mp4' }));
    await waitFor(() => expect(container.querySelector('video')).toBeTruthy());
  });

  it('falls back to the type icon (no media element) for non-previewable kinds', () => {
    const { container } = renderCard(makeEntry({ mimeType: 'application/zip', fileName: 'archive.zip' }));
    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('iframe')).toBeNull();
    expect(container.querySelector('video')).toBeNull();
    // The themed file-type icon renders as an inline svg.
    expect(container.querySelector('svg')).toBeTruthy();
  });

  it('falls back to the type icon when the entry has no storage id', () => {
    const { container } = renderCard(makeEntry({ id: '', mimeType: 'image/png', fileName: 'pic.png' }));
    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('svg')).toBeTruthy();
  });

  it('falls back to the type icon when the authenticated fetch errors', () => {
    // The blob fetch (403/404/network) surfaces as the hook's `error` - FileThumb then renders
    // the type icon instead of a broken <img>. (Error handling moved from <img onError> to the hook.)
    mockUseAuthed.mockReturnValue({ url: null, loading: false, error: true });
    const { container } = renderCard(makeEntry({ mimeType: 'image/png', fileName: 'pic.png' }));
    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('svg')).toBeTruthy();
  });

  it('is NOT draggable by default (side-panel explorer) - renders a plain tile', () => {
    // Backward compat: without `draggable`, FileCard must not opt into dnd-kit
    // listeners (the side-panel explorer renders it as a plain clickable tile).
    const onOpen = vi.fn();
    const { container } = render(
      <FileCard
        entry={makeEntry({ mimeType: 'image/png', fileName: 'pic.png' })}
        selected={false}
        onToggleSelect={noop}
        onOpen={onOpen}
        onDownload={noop}
        downloadLabel="Download"
      />,
    );
    const card = container.firstElementChild as HTMLElement;
    // dnd-kit draggable adds a role/aria-roledescription + a draggable attribute via
    // listeners; the default (non-draggable) tile carries none of that.
    expect(card.getAttribute('aria-roledescription')).toBeNull();
    expect(card.getAttribute('role')).not.toBe('button');
  });

  it('PDF thumbnail mounts lazily: icon until the tile scrolls into view, then the iframe', async () => {
    // Inject a controllable IntersectionObserver (jsdom has none → the hook would
    // otherwise render eagerly). This exercises the real lazy path: observe →
    // not intersecting (icon) → intersecting (media mounts) → disconnect.
    const observed: Array<(entries: Array<{ isIntersecting: boolean }>) => void> = [];
    const disconnect = vi.fn();
    class MockIO {
      cb: (entries: Array<{ isIntersecting: boolean }>) => void;
      constructor(cb: (entries: Array<{ isIntersecting: boolean }>) => void) {
        this.cb = cb;
        observed.push(cb);
      }
      observe() {}
      disconnect() { disconnect(); }
    }
    const real = (globalThis as { IntersectionObserver?: unknown }).IntersectionObserver;
    (globalThis as { IntersectionObserver?: unknown }).IntersectionObserver = MockIO as unknown;
    try {
      const { container } = renderCard(makeEntry({ mimeType: 'application/pdf', fileName: 'doc.pdf' }));
      // Not yet intersecting → icon fallback, no iframe.
      expect(container.querySelector('iframe')).toBeNull();
      expect(container.querySelector('svg')).toBeTruthy();
      // Scroll into view → the observer fires → iframe mounts and the observer disconnects.
      act(() => observed[observed.length - 1]([{ isIntersecting: true }]));
      await waitFor(() => expect(container.querySelector('iframe')).toBeTruthy());
      expect(disconnect).toHaveBeenCalled();
    } finally {
      (globalThis as { IntersectionObserver?: unknown }).IntersectionObserver = real;
    }
  });
});
