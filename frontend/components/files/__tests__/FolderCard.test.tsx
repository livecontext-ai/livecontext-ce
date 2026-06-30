// @vitest-environment jsdom
/**
 * Tests for {@link FolderCard} (V313 manual folders) - the iOS-style folder tile.
 * Asserts the 3×3 preview mosaic from {@code previewFiles}: an image child renders an
 * <img> thumbnail, a non-image child (pdf/csv/...) renders its file-type ICON, and
 * empty slots stay muted placeholders. Plus the exact child-count label and that
 * clicking opens the folder. dnd-kit is mocked to inert refs so the tile renders
 * without a DndContext (the drag/drop wiring is exercised in the FileBrowser test).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, cleanup, fireEvent } from '@testing-library/react';
import type { StorageExplorerEntry, StoragePreviewFile } from '@/lib/api/storage-api';
import { FolderCard, VirtualFolderCard } from '../FolderCard';
import { useAuthedObjectUrl } from '@/hooks/useAuthedObjectUrl';

vi.mock('@/lib/api/orchestrator/file.service', () => ({
  getFileUrlById: (id: string, opts?: { inline?: boolean }) => `/raw/${id}?inline=${opts?.inline ?? false}`,
}));

// dnd-kit droppable/draggable need a DndContext; stub them to inert no-ops so the
// tile renders standalone. (Real drop behaviour is covered by the FileBrowser test.)
vi.mock('@dnd-kit/core', () => ({
  useDroppable: () => ({ setNodeRef: () => {}, isOver: false }),
  useDraggable: () => ({ setNodeRef: () => {}, attributes: {}, listeners: {}, isDragging: false }),
}));

vi.mock('@/hooks/useAuthedObjectUrl', () => ({ useAuthedObjectUrl: vi.fn() }));
const mockUseAuthed = vi.mocked(useAuthedObjectUrl);

beforeEach(() => {
  // Echo the requested URL back as the resolved blob (null → null): an image cell
  // (non-null src) renders an <img>; an icon/empty cell passes null → no <img>.
  mockUseAuthed.mockImplementation((src: string | null | undefined) => ({
    url: src ?? null,
    loading: false,
    error: false,
  }));
});
afterEach(() => { cleanup(); mockUseAuthed.mockReset(); });

/** A typed image preview (renders a thumbnail). */
const img = (id: string): StoragePreviewFile => ({ id, mimeType: 'image/png', fileName: `${id}.png` });
/** A typed non-image preview (renders a file-type icon, no byte fetch). */
const csv = (id: string): StoragePreviewFile => ({ id, mimeType: 'text/csv', fileName: `${id}.csv` });

function makeFolder(overrides: Partial<StorageExplorerEntry>): StorageExplorerEntry {
  return {
    id: 'folder-1',
    fileName: 'Reports',
    contentType: null,
    mimeType: null,
    s3Key: null,
    sizeBytes: null,
    formattedSize: '0 B',
    createdAt: '2026-06-01T00:00:00Z',
    sourceType: null,
    isFolder: true,
    parentFolderId: null,
    childCount: 0,
    previewFiles: [],
    ...overrides,
  } as unknown as StorageExplorerEntry;
}

function renderFolder(entry: StorageExplorerEntry, onOpen = vi.fn(), countLabel = '0 items') {
  // The parent now supplies the display label; mirror the folder's stored name so
  // the existing footer/name assertions keep meaning.
  const utils = render(
    <FolderCard
      entry={entry}
      selected={false}
      onToggleSelect={vi.fn()}
      onOpen={onOpen}
      label={entry.fileName ?? 'Folder'}
      countLabel={countLabel}
    />,
  );
  return { ...utils, onOpen };
}

describe('FolderCard', () => {
  it('renders one <img> tile per IMAGE preview (capped at 9) and muted placeholders for the rest', () => {
    // 4 image previews → 4 image tiles + 5 empty slots = 9 cells total.
    const files = ['a', 'b', 'c', 'd'].map(img);
    const { container } = renderFolder(makeFolder({ previewFiles: files, childCount: 12 }));
    const imgs = container.querySelectorAll('img');
    expect(imgs.length).toBe(4);
    // The mosaic always lays out 9 cells (img + placeholder divs).
    const mosaic = container.querySelector('.grid-cols-3');
    expect(mosaic?.children.length).toBe(9);
  });

  it('caps the mosaic at 9 tiles even when more preview files are provided', () => {
    const files = Array.from({ length: 15 }, (_, i) => img(`id-${i}`));
    const { container } = renderFolder(makeFolder({ previewFiles: files, childCount: 30 }));
    expect(container.querySelectorAll('img').length).toBe(9);
    expect(container.querySelector('.grid-cols-3')?.children.length).toBe(9);
  });

  it('renders a file-type ICON (no <img>) for NON-image previews like csv/pdf', () => {
    // The whole point of the all-types preview: a folder of csv files still shows a
    // mosaic - file-type icons, not a single folder glyph - and fetches NO bytes.
    const files = ['a', 'b', 'c'].map(csv);
    const { container } = renderFolder(makeFolder({ previewFiles: files, childCount: 3 }));
    // Non-image → no thumbnails are fetched/rendered.
    expect(container.querySelectorAll('img').length).toBe(0);
    // ...but the mosaic IS present (3 icon cells + 6 muted placeholders = 9).
    const mosaic = container.querySelector('.grid-cols-3');
    expect(mosaic?.children.length).toBe(9);
    // The icon tiles render an svg (the file-type icon) inside the mosaic.
    expect(mosaic?.querySelectorAll('svg').length).toBe(3);
  });

  it('shows a single folder icon (no mosaic) when there are no previews', () => {
    const { container } = renderFolder(makeFolder({ previewFiles: [], childCount: 0 }));
    // No 3×3 mosaic, no image tiles - just the placeholder folder icon (an svg).
    expect(container.querySelector('.grid-cols-3')).toBeNull();
    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('svg')).toBeTruthy();
  });

  it('renders the exact child-count label passed by the parent', () => {
    const { getByText } = renderFolder(makeFolder({ childCount: 7 }), vi.fn(), '7 items');
    expect(getByText('7 items')).toBeTruthy();
    expect(getByText('Reports')).toBeTruthy();
  });

  it('opens (navigates into) the folder when the tile is clicked', () => {
    const onOpen = vi.fn();
    const { getByText } = renderFolder(makeFolder({ fileName: 'Invoices' }), onOpen);
    fireEvent.click(getByText('Invoices'));
    expect(onOpen).toHaveBeenCalledTimes(1);
    expect(onOpen.mock.calls[0][0].fileName).toBe('Invoices');
  });

  it('renders the parent-supplied label (not entry.fileName) in the footer', () => {
    // A virtual folder has no fileName - the parent passes a localised label.
    const { getByText, queryByText } = render(
      <FolderCard
        entry={makeFolder({ fileName: null })}
        selected={false}
        onToggleSelect={vi.fn()}
        onOpen={vi.fn()}
        label="Epoch 1"
        countLabel="3 items"
      />,
    );
    expect(getByText('Epoch 1')).toBeTruthy();
    expect(queryByText('Folder')).toBeNull();
  });
});

describe('VirtualFolderCard', () => {
  // A virtual workflow folder navigates by virtualId; it can't be moved/deleted, so
  // it has no checkbox and isn't a drop target/draggable (no dnd wiring at all).
  function makeVirtual(overrides: Partial<StorageExplorerEntry> = {}): StorageExplorerEntry {
    return {
      id: null,
      virtualId: 'wf:42/e0',
      virtualKind: 'EPOCH',
      epoch: 0,
      fileName: null,
      contentType: null,
      mimeType: null,
      s3Key: null,
      sizeBytes: null,
      formattedSize: '0 B',
      createdAt: '2026-06-01T00:00:00Z',
      sourceType: null,
      isFolder: true,
      parentFolderId: null,
      childCount: 5,
      previewFiles: [],
      ...overrides,
    } as unknown as StorageExplorerEntry;
  }

  it('shows the localised label + count and renders NO selection checkbox', () => {
    const { getByText, container } = render(
      <VirtualFolderCard entry={makeVirtual()} onOpen={vi.fn()} label="Epoch 1" countLabel="5 items" />,
    );
    expect(getByText('Epoch 1')).toBeTruthy();
    expect(getByText('5 items')).toBeTruthy();
    // Navigation-only: no checkbox to select it.
    expect(container.querySelector('input[type="checkbox"]')).toBeNull();
  });

  it('opens (navigates deeper) when the tile is clicked, passing the virtual entry', () => {
    const onOpen = vi.fn();
    const { getByText } = render(
      <VirtualFolderCard entry={makeVirtual()} onOpen={onOpen} label="Epoch 1" countLabel="5 items" />,
    );
    fireEvent.click(getByText('Epoch 1'));
    expect(onOpen).toHaveBeenCalledTimes(1);
    expect(onOpen.mock.calls[0][0].virtualId).toBe('wf:42/e0');
  });

  it('renders a preview mosaic from previewFiles just like the manual tile', () => {
    const { container } = render(
      <VirtualFolderCard
        entry={makeVirtual({ previewFiles: [img('a'), img('b')] })}
        onOpen={vi.fn()}
        label="Epoch 1"
        countLabel="5 items"
      />,
    );
    expect(container.querySelectorAll('img').length).toBe(2);
    expect(container.querySelector('.grid-cols-3')?.children.length).toBe(9);
  });
});
