// @vitest-environment jsdom
/**
 * A manual upload refused for storage quota must open the storage modal (quota figures +
 * upgrade path), not the generic "upload failed" toast. The modal already existed and was
 * wired to the workflow/chat execution paths only, so the Files browser, the one place a
 * user uploads on purpose, was the surface most likely to hit the wall and the only one
 * that said nothing useful about it. Reported from production: a TEAM workspace sat at
 * exactly its 100 MB ceiling and the user read it as "uploads are broken".
 *
 * The discrimination matters as much as the dispatch: the upload endpoint answers 413 for
 * quota AND for a single oversized file, and only the first one means "you are out of
 * space". Both are pinned below.
 *
 * Mock scaffolding mirrors FileBrowser.viewerReadOnly.test.tsx.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, cleanup, fireEvent, act } from '@testing-library/react';
import type { StorageExplorerEntry } from '@/lib/api/storage-api';

const spies = vi.hoisted(() => ({
  showStorageModal: vi.fn(),
  addToast: vi.fn(),
}));

vi.mock('@/i18n/navigation', () => ({
  useRouter: () => ({ push: () => undefined, replace: () => undefined, prefetch: () => undefined }),
  usePathname: () => '/app',
  Link: ({ children }: { children?: React.ReactNode }) => <a>{children}</a>,
}));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars && 'count' in vars ? `${key}:${vars.count}` : key,
}));

const refresh = vi.fn();
let hookState: { entries: StorageExplorerEntry[]; parentFolderId: string | null | undefined } = {
  entries: [],
  parentFolderId: null,
};
vi.mock('@/app/workflows/builder/components/inspector/useStorageExplorer', () => ({
  useStorageExplorer: () => ({
    sort: 'date' as const,
    direction: 'desc' as const,
    setSort: vi.fn(),
    entries: hookState.entries,
    totalElements: hookState.entries.length,
    totalPages: 1,
    currentPage: 0,
    pageSize: 50,
    loading: false,
    error: null,
    search: '',
    sourceTypeFilter: '',
    dateFrom: '',
    dateTo: '',
    fileType: '_all',
    parentFolderId: hookState.parentFolderId,
    setSearch: vi.fn(),
    setSourceTypeFilter: vi.fn(),
    setDateFrom: vi.fn(),
    setDateTo: vi.fn(),
    setFileType: vi.fn(),
    navigateToFolder: vi.fn(),
    setPage: vi.fn(),
    setPageSize: vi.fn(),
    refresh,
  }),
}));
vi.mock('@/lib/api/storage-api', () => ({
  storageApi: {
    getFolderTrail: vi.fn().mockResolvedValue([]),
    createFolder: vi.fn(),
    moveEntries: vi.fn(),
    deleteEntries: vi.fn().mockResolvedValue({ deletedCount: 0 }),
    renameEntry: vi.fn(),
  },
  S3_FILES_FILTER: { filesOnly: true, s3Only: true },
}));
vi.mock('@dnd-kit/core', () => ({
  DndContext: ({ children }: { children: React.ReactNode }) => <div data-testid="dnd">{children}</div>,
  DragOverlay: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  MouseSensor: class {},
  TouchSensor: class {},
  pointerWithin: () => [],
  rectIntersection: () => [],
  useSensor: () => ({}),
  useSensors: () => [],
}));
vi.mock('../FolderCard', () => ({
  FolderCard: () => <div />,
  VirtualFolderCard: () => <div />,
}));
vi.mock('../FileCard', () => ({ FileCard: () => <div /> }));
vi.mock('@/components/app/FileDetailView', () => ({ FileDetailView: () => <div data-testid="detail" /> }));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => '/en/app/files',
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock('../FileFilterBar', () => ({ FileFilterBar: () => <div /> }));
vi.mock('@/components/ui/PaginationBar', () => ({ PaginationBar: () => <div /> }));
vi.mock('@/components/ui/BulkDeleteModal', () => ({ BulkDeleteModal: () => null }));
vi.mock('@/components/ToastContainer', () => ({ default: () => null }));
vi.mock('@/components/Toast', () => ({
  useToast: () => ({ toasts: [], addToast: spies.addToast, removeToast: vi.fn() }),
}));
vi.mock('@/hooks/useAuthToken', () => ({ useAuthToken: () => 'token' }));
vi.mock('@/hooks/useGenerationModels', () => ({
  useGenerationModels: () => ({ models: [], isLoading: false, availability: 'ready' }),
}));
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (v: unknown) => v }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => {} }));
vi.mock('@/lib/stores/current-org-store', () => ({
  getActiveOrgHeaderForRequest: () => ({}),
  useCanMutateInCurrentOrg: () => true,
}));
vi.mock('@/lib/api/orchestrator/file.service', () => ({
  fileService: { downloadAndSave: vi.fn(), uploadGeneric: vi.fn() },
}));
vi.mock('@/components/billing/InsufficientStorageModal', () => ({
  showInsufficientStorageModal: spies.showStorageModal,
  InsufficientStorageModal: () => null,
}));

import { FileBrowser } from '../FileBrowser';
import { fileService } from '@/lib/api/orchestrator/file.service';

/** What fileService.uploadGeneric throws: a plain Error carrying the response body, no status. */
function uploadError(body: string) {
  return new Error(`Upload failed: ${body}`);
}
const QUOTA_BODY = '{"error":"Storage quota exceeded"}';
const TOO_LARGE_BODY = '{"error":"File too large. Maximum size: 100 MB"}';

async function uploadOne(container: HTMLElement, name = 'up.txt') {
  const input = container.querySelector('input[type="file"]') as HTMLInputElement;
  const f = new File(['x'], name, { type: 'text/plain' });
  await act(async () => { fireEvent.change(input, { target: { files: [f] } }); });
}

beforeEach(() => {
  hookState = { entries: [], parentFolderId: null };
  spies.showStorageModal.mockClear();
  spies.addToast.mockClear();
  refresh.mockClear();
  vi.mocked(fileService.uploadGeneric).mockReset();
});
afterEach(() => cleanup());

describe('FileBrowser - a quota-refused upload explains itself', () => {
  it('opens the storage modal when the upload is refused for quota', async () => {
    vi.mocked(fileService.uploadGeneric).mockRejectedValue(uploadError(QUOTA_BODY));
    const { container } = render(<FileBrowser />);

    await uploadOne(container);

    expect(spies.showStorageModal).toHaveBeenCalledTimes(1);
  });

  it('does not also fire the vague "upload failed" toast for a quota refusal', async () => {
    vi.mocked(fileService.uploadGeneric).mockRejectedValue(uploadError(QUOTA_BODY));
    const { container } = render(<FileBrowser />);

    await uploadOne(container);

    // The modal carries the quota figures and the upgrade path; a second, vaguer message
    // next to it would only muddy what the user is being told.
    const errorToasts = spies.addToast.mock.calls.filter(([t]) => t?.type === 'error');
    expect(errorToasts).toHaveLength(0);
  });

  it('keeps the generic toast, and stays silent on storage, for an unrelated failure', async () => {
    vi.mocked(fileService.uploadGeneric).mockRejectedValue(new Error('Upload failed: network down'));
    const { container } = render(<FileBrowser />);

    await uploadOne(container);

    expect(spies.showStorageModal).not.toHaveBeenCalled();
    const errorToasts = spies.addToast.mock.calls.filter(([t]) => t?.type === 'error');
    expect(errorToasts).toHaveLength(1);
    expect(errorToasts[0][0].message).toBe('uploadFailedMessage:1');
  });

  it('does NOT claim the account is out of space when one file is simply too large', async () => {
    // The endpoint answers 413 here too. Detecting on the status rather than the body would
    // tell someone with 99 GB free to go buy more storage.
    vi.mocked(fileService.uploadGeneric).mockRejectedValue(uploadError(TOO_LARGE_BODY));
    const { container } = render(<FileBrowser />);

    await uploadOne(container);

    expect(spies.showStorageModal).not.toHaveBeenCalled();
    const errorToasts = spies.addToast.mock.calls.filter(([t]) => t?.type === 'error');
    expect(errorToasts).toHaveLength(1);
  });

  it('a partly-refused batch still reports what landed and still explains the refusal', async () => {
    vi.mocked(fileService.uploadGeneric)
      .mockResolvedValueOnce({} as never)
      .mockRejectedValueOnce(uploadError(QUOTA_BODY));
    const { container } = render(<FileBrowser />);
    const input = container.querySelector('input[type="file"]') as HTMLInputElement;
    const files = [
      new File(['a'], 'a.txt', { type: 'text/plain' }),
      new File(['b'], 'b.txt', { type: 'text/plain' }),
    ];

    await act(async () => { fireEvent.change(input, { target: { files } }); });

    expect(spies.showStorageModal).toHaveBeenCalledTimes(1);
    expect(refresh).toHaveBeenCalled();
    const successToasts = spies.addToast.mock.calls.filter(([t]) => t?.type === 'success');
    expect(successToasts).toHaveLength(1);
    expect(successToasts[0][0].message).toBe('uploadedMessage:1');
  });
});
