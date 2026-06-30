// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, cleanup, screen } from '@testing-library/react';
import type { StorageExplorerEntry } from '@/lib/api/storage-api';
import { DragPreviewCard } from '../DragPreviewCard';
import { useAuthedObjectUrl } from '@/hooks/useAuthedObjectUrl';

// Dependency-free media URL (the real api-client never loads in the render).
vi.mock('@/lib/api/orchestrator/file.service', () => ({
  getFileUrlById: (id: string, opts?: { inline?: boolean }) => `/raw/${id}?inline=${opts?.inline ?? false}`,
}));

vi.mock('@/hooks/useAuthedObjectUrl', () => ({ useAuthedObjectUrl: vi.fn() }));
const mockUseAuthed = vi.mocked(useAuthedObjectUrl);

beforeEach(() => {
  mockUseAuthed.mockImplementation((src: string | null | undefined) => ({
    url: src ?? null,
    loading: false,
    error: false,
  }));
});
afterEach(() => { cleanup(); mockUseAuthed.mockReset(); });

function entry(overrides: Partial<StorageExplorerEntry>): StorageExplorerEntry {
  return {
    id: 'e-1',
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

describe('DragPreviewCard', () => {
  it('renders a file: its name + an image thumbnail for an image entry', () => {
    const { container } = render(
      <DragPreviewCard entry={entry({ mimeType: 'image/png', fileName: 'pic.png' })} label="pic.png" />,
    );
    expect(screen.getByText('pic.png')).toBeTruthy();
    // Image thumbnail renders an <img> via the (mocked) authed url.
    expect(container.querySelector('img')?.getAttribute('src')).toContain('/raw/e-1');
  });

  it('renders a folder: the 3×3 mosaic when preview files are present', () => {
    const { container } = render(
      <DragPreviewCard
        entry={entry({
          isFolder: true,
          fileName: 'Reports',
          previewFiles: [
            { id: 'p1', mimeType: 'image/png', fileName: 'p1.png' },
            { id: 'p2', mimeType: 'image/png', fileName: 'p2.png' },
          ],
        })}
        label="Reports"
      />,
    );
    expect(screen.getByText('Reports')).toBeTruthy();
    // FolderFace renders one <img> per image mosaic cell (2 image previews here).
    expect(container.querySelectorAll('img').length).toBe(2);
  });

  it('shows the count badge only when multi > 1', () => {
    const single = render(<DragPreviewCard entry={entry({ fileName: 'a' })} label="a" multi={1} />);
    expect(single.queryByText('1')).toBeNull();
    cleanup();
    render(<DragPreviewCard entry={entry({ fileName: 'a' })} label="a" multi={4} />);
    expect(screen.getByText('4')).toBeTruthy();
  });
});
