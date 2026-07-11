// @vitest-environment jsdom
/**
 * RBAC hardening (2026-07-02): the VIEWER org role is read-only, so the Files
 * browser must hide every write affordance (upload, new folder, rename, move,
 * delete, drag-to-move) while keeping browsing + download available. MEMBER
 * (and the personal workspace) keeps the full surface. The gate is
 * {@link useCanMutateInCurrentOrg}, mocked here as a mutable flag.
 *
 * Same mock scaffolding as FileBrowser.folders.test.tsx (heavy hook + cards
 * stubbed); pre-fix all VIEWER assertions fail (buttons render regardless).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, cleanup, fireEvent, act } from '@testing-library/react';
import type { StorageExplorerEntry } from '@/lib/api/storage-api';

// ---- i18n: echo the key (so we assert on stable strings) ----
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars && 'count' in vars ? `${key}:${vars.count}` : key,
}));

// ---- the storage hook: controllable entries ----
const navigateToFolder = vi.fn();
const refresh = vi.fn();
let hookState: { entries: StorageExplorerEntry[]; parentFolderId: string | null | undefined } = {
  entries: [],
  parentFolderId: null,
};
vi.mock('@/app/workflows/builder/components/inspector/useStorageExplorer', () => ({
  useStorageExplorer: () => ({
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
    navigateToFolder,
    setPage: vi.fn(),
    setPageSize: vi.fn(),
    refresh,
  }),
}));

vi.mock('@/lib/api/storage-api', () => ({
  storageApi: {
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
  PointerSensor: class {},
  useSensor: () => ({}),
  useSensors: () => [],
}));

vi.mock('../FolderCard', () => ({
  FolderCard: ({ entry, onToggleSelect, label }: { entry: StorageExplorerEntry; onToggleSelect: (id: string) => void; label: string }) => (
    <div data-testid={`folder-${entry.id}`}>
      <span>{label}</span>
      <input type="checkbox" data-testid={`select-folder-${entry.id}`} onChange={() => onToggleSelect(entry.id)} />
    </div>
  ),
  // VirtualFolderCard exposes whether it joined the bulk selection (the Files
  // page routes virtual-folder delete through the selection bar, so there is no
  // per-card delete affordance anymore) and whether a per-card delete was passed.
  VirtualFolderCard: ({ entry, onToggleSelect, onDelete }: { entry: StorageExplorerEntry; onToggleSelect?: (key: string) => void; onDelete?: (e: StorageExplorerEntry) => void }) => (
    <div data-testid={`vfolder-${entry.virtualId}`} data-selectable={String(!!onToggleSelect)} data-has-delete={String(!!onDelete)} />
  ),
}));
vi.mock('../FileCard', () => ({
  FileCard: ({ entry, onToggleSelect, draggable }: { entry: StorageExplorerEntry; onToggleSelect: (id: string) => void; draggable?: boolean }) => (
    <div data-testid={`file-${entry.id}`} data-draggable={String(!!draggable)}>
      <input type="checkbox" data-testid={`select-file-${entry.id}`} onChange={() => onToggleSelect(entry.id)} />
    </div>
  ),
}));
vi.mock('@/components/app/FileDetailView', () => ({ FileDetailView: () => <div data-testid="detail" /> }));
vi.mock('../FileFilterBar', () => ({ FileFilterBar: () => <div /> }));
vi.mock('@/components/ui/PaginationBar', () => ({ PaginationBar: () => <div /> }));
vi.mock('@/components/ui/BulkDeleteModal', () => ({ BulkDeleteModal: () => null }));
vi.mock('@/components/ToastContainer', () => ({ default: () => null }));
vi.mock('@/components/Toast', () => ({ useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }) }));
vi.mock('@/hooks/useAuthToken', () => ({ useAuthToken: () => 'token' }));
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (v: unknown) => v }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => {} }));

// ---- the gate under test: mutable per test ----
const orgMutationGate = vi.hoisted(() => ({ canMutate: true }));
vi.mock('@/lib/stores/current-org-store', () => ({
  getActiveOrgHeaderForRequest: () => ({}),
  useCanMutateInCurrentOrg: () => orgMutationGate.canMutate,
}));
vi.mock('@/lib/api/orchestrator/file.service', () => ({
  fileService: { downloadAndSave: vi.fn(), uploadGeneric: vi.fn() },
}));

import { FileBrowser } from '../FileBrowser';
import { fileService } from '@/lib/api/orchestrator/file.service';

function folder(id: string, name: string): StorageExplorerEntry {
  return {
    id, fileName: name, isFolder: true, parentFolderId: null, childCount: 0, previewFiles: [],
    storageType: 'S3_FILE', sourceType: null, mimeType: null, sizeBytes: null, formattedSize: '0 B',
    createdAt: '2026-06-01T00:00:00Z', s3Key: null, contentType: null,
  } as unknown as StorageExplorerEntry;
}
function file(id: string, name: string): StorageExplorerEntry {
  return {
    id, fileName: name, isFolder: false, parentFolderId: null,
    storageType: 'S3_FILE', sourceType: 'S3_FILE', mimeType: 'image/png', sizeBytes: 10, formattedSize: '10 B',
    createdAt: '2026-06-01T00:00:00Z', s3Key: `k/${id}`, contentType: null,
  } as unknown as StorageExplorerEntry;
}
function vfolder(virtualId: string): StorageExplorerEntry {
  return {
    id: null, virtualId, virtualKind: 'WORKFLOW', workflowId: 'wf1', workflowName: 'Daily Report',
    isFolder: true, parentFolderId: null, childCount: 4, previewFiles: [],
    storageType: 'S3_FILE', sourceType: null, fileName: null, mimeType: null, sizeBytes: null, formattedSize: '0 B',
    createdAt: '2026-06-01T00:00:00Z', s3Key: null, contentType: null,
  } as unknown as StorageExplorerEntry;
}

beforeEach(() => {
  hookState = { entries: [], parentFolderId: null };
  orgMutationGate.canMutate = true;
  vi.mocked(fileService.uploadGeneric).mockClear();
});
afterEach(() => cleanup());

describe('FileBrowser - MEMBER (canMutate) keeps the write surface', () => {
  it('shows upload + new folder, and delete/move/rename for a selection', () => {
    hookState.entries = [folder('f1', 'Reports'), file('a', 'a.png')];
    const { getByText, getByTestId, queryByText } = render(<FileBrowser />);
    expect(getByText('upload')).toBeTruthy();
    expect(getByText('newFolder')).toBeTruthy();
    fireEvent.click(getByTestId('select-file-a'));
    expect(getByText('deleteSelected')).toBeTruthy();
    expect(getByText('moveTo')).toBeTruthy();
    // Rename appears only for a single-folder selection - swap the selection.
    fireEvent.click(getByTestId('select-file-a'));
    fireEvent.click(getByTestId('select-folder-f1'));
    expect(queryByText('renameFolder')).toBeTruthy();
  });

  it('keeps grid cards draggable; virtual folders join the selection (no per-card delete)', () => {
    hookState.entries = [vfolder('wf:1'), file('a', 'a.png')];
    const { getByTestId } = render(<FileBrowser />);
    expect(getByTestId('file-a').getAttribute('data-draggable')).toBe('true');
    // Deletion is unified through the floating selection bar - the virtual tile
    // gets the same checkbox as every row instead of its own trash button.
    expect(getByTestId('vfolder-wf:1').getAttribute('data-selectable')).toBe('true');
    expect(getByTestId('vfolder-wf:1').getAttribute('data-has-delete')).toBe('false');
  });
});

describe('FileBrowser - VIEWER (org workspace) is read-only', () => {
  beforeEach(() => {
    orgMutationGate.canMutate = false;
  });

  it('hides upload and new folder', () => {
    hookState.entries = [folder('f1', 'Reports'), file('a', 'a.png')];
    const { queryByText } = render(<FileBrowser />);
    expect(queryByText('upload')).toBeNull();
    expect(queryByText('newFolder')).toBeNull();
  });

  it('hides delete / move / rename on a selection but keeps download', () => {
    hookState.entries = [folder('f1', 'Reports'), file('a', 'a.png')];
    const { getByTestId, queryByText, getByText } = render(<FileBrowser />);
    fireEvent.click(getByTestId('select-file-a'));
    expect(queryByText('deleteSelected')).toBeNull();
    expect(queryByText('moveTo')).toBeNull();
    expect(getByText('downloadSelected')).toBeTruthy();
    // Single-folder selection: rename stays hidden too.
    fireEvent.click(getByTestId('select-file-a'));
    fireEvent.click(getByTestId('select-folder-f1'));
    expect(queryByText('renameFolder')).toBeNull();
  });

  it('disables drag-to-move; virtual folders stay selectable (mutations gated in the bar)', () => {
    hookState.entries = [vfolder('wf:1'), file('a', 'a.png')];
    const { getByTestId } = render(<FileBrowser />);
    expect(getByTestId('file-a').getAttribute('data-draggable')).toBe('false');
    // Selection stays available to a VIEWER (bulk download is read-only); the
    // delete/move actions in the floating bar are canMutate-gated separately.
    expect(getByTestId('vfolder-wf:1').getAttribute('data-selectable')).toBe('true');
    expect(getByTestId('vfolder-wf:1').getAttribute('data-has-delete')).toBe('false');
  });

  it('never uploads, even through the hidden file input (drag-and-drop path)', async () => {
    hookState.entries = [file('a', 'a.png')];
    const { container } = render(<FileBrowser />);
    const input = container.querySelector('input[type="file"]') as HTMLInputElement;
    const f = new File(['x'], 'up.txt', { type: 'text/plain' });
    await act(async () => { fireEvent.change(input, { target: { files: [f] } }); });
    expect(fileService.uploadGeneric).not.toHaveBeenCalled();
  });

  it('hides the empty-state upload CTA', () => {
    hookState.entries = [];
    const { queryByText, getByText } = render(<FileBrowser />);
    expect(getByText('empty')).toBeTruthy();
    expect(queryByText('upload')).toBeNull();
  });
});
