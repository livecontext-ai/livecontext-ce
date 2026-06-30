// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getExplorerEntries } = vi.hoisted(() => ({ getExplorerEntries: vi.fn() }));
vi.mock('@/lib/api/storage-api', () => ({ storageApi: { getExplorerEntries }, S3_FILES_FILTER: { filesOnly: true, s3Only: true } }));

// Capture the IntersectionObserver callback so a test can simulate "sentinel scrolled into view".
let ioCallback: ((entries: { isIntersecting: boolean }[]) => void) | null = null;

beforeEach(() => {
  ioCallback = null;
  (globalThis as unknown as { IntersectionObserver: unknown }).IntersectionObserver = class {
    constructor(cb: (entries: { isIntersecting: boolean }[]) => void) {
      ioCallback = cb;
    }
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  getExplorerEntries.mockReset();
});
afterEach(() => cleanup());

import FileAccessSection from '../FileAccessSection';

const baseProps = {
  label: 'Files',
  expanded: true,
  onToggleExpanded: () => {},
  restrictedCount: 0,
  restrictionCountLabel: (c: number) => `${c} restricted`,
  allAccessLabel: 'Full access',
  searchPlaceholder: 'Search…',
  emptyLabel: 'No files',
  accessFull: 'Full',
  accessRead: 'Read',
  accessNone: 'None',
  allowAllLabel: 'Allow all',
  blockAllLabel: 'Block all',
  getLevel: () => 'full' as const,
  onSetLevel: vi.fn(),
  onAllowAll: vi.fn(),
  onBlockAll: vi.fn(),
};

function pageRes(ids: number[], totalPages: number, totalElements: number) {
  return { content: ids.map((n) => ({ id: `f${n}`, fileName: `file-${n}.txt` })), totalPages, totalElements };
}

describe('FileAccessSection - paginated infinite scroll + search (s3Only)', () => {
  it('loads ONLY the first page (size 30, s3Only, filesOnly) on open and shows the TOTAL count', async () => {
    getExplorerEntries.mockResolvedValue(pageRes([0, 1], 5, 150));
    render(<FileAccessSection {...baseProps} />);

    await screen.findByText('file-0.txt');
    expect(getExplorerEntries).toHaveBeenCalledTimes(1);
    expect(getExplorerEntries).toHaveBeenCalledWith(
      expect.objectContaining({ page: 0, size: 30, filesOnly: true, s3Only: true }),
    );
    // The header shows the full total (150), not just the 2 loaded rows - proving it's not loading all.
    expect(screen.getByText('(150)')).toBeInTheDocument();
  });

  it('appends the next page when the sentinel scrolls into view (infinite scroll)', async () => {
    getExplorerEntries
      .mockResolvedValueOnce(pageRes([0, 1], 2, 4))
      .mockResolvedValueOnce(pageRes([2, 3], 2, 4));
    render(<FileAccessSection {...baseProps} />);

    await screen.findByText('file-0.txt');
    // Simulate the infinite-scroll sentinel entering the viewport.
    await act(async () => {
      ioCallback?.([{ isIntersecting: true }]);
    });

    await screen.findByText('file-2.txt');
    expect(getExplorerEntries).toHaveBeenNthCalledWith(2, expect.objectContaining({ page: 1, size: 30 }));
    // Page 0 rows are still present (appended, not replaced).
    expect(screen.getByText('file-0.txt')).toBeInTheDocument();
  });

  it('re-queries from page 0 with the search term (server-side, debounced)', async () => {
    getExplorerEntries.mockResolvedValue(pageRes([0], 1, 1));
    render(<FileAccessSection {...baseProps} />);

    await screen.findByText('file-0.txt');
    getExplorerEntries.mockClear();

    fireEvent.change(screen.getByPlaceholderText('Search…'), { target: { value: 'report' } });
    await waitFor(() =>
      expect(getExplorerEntries).toHaveBeenCalledWith(
        expect.objectContaining({ page: 0, search: 'report', s3Only: true }),
      ),
    );
  });
});
