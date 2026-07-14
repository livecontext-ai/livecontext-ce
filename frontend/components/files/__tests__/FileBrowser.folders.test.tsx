// @vitest-environment jsdom
/**
 * Integration tests for the V313 manual-folder behaviour wired into
 * {@link FileBrowser}: entering a folder + stepping back, creating a folder
 * (API + refresh), dropping a card onto a folder (moveEntries), and the Rename
 * action being enabled ONLY when exactly one folder is selected.
 *
 * The heavy hook {@code useStorageExplorer} is mocked so the test drives the
 * listing + captures {@code navigateToFolder}; {@code storageApi} is mocked to
 * assert create/move. dnd-kit's DndContext is stubbed to expose {@code onDragEnd}
 * so a drop can be simulated without a real pointer drag. FolderCard/FileCard are
 * replaced with minimal buttons that expose the props under test.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, cleanup, fireEvent, waitFor, act } from '@testing-library/react';
import type { StorageExplorerEntry } from '@/lib/api/storage-api';

// ---- i18n: echo the key (so we assert on stable strings) ----
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars && 'count' in vars ? `${key}:${vars.count}` : key,
}));

// ---- the storage hook: controllable entries + captured navigateToFolder/refresh ----
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

// ---- storage API: assert create/move ----
const createFolder = vi.fn().mockResolvedValue({ id: 'new-folder', name: 'Docs', isFolder: true, parentFolderId: null });
const moveEntries = vi.fn().mockResolvedValue({ movedCount: 1, failed: [] });
const deleteEntries = vi.fn().mockResolvedValue({ deletedCount: 1 });
const deleteVirtualFolder = vi.fn().mockResolvedValue({ deletedCount: 3 });
vi.mock('@/lib/api/storage-api', () => ({
  storageApi: {
    createFolder: (...a: unknown[]) => createFolder(...a),
    moveEntries: (...a: unknown[]) => moveEntries(...a),
    deleteEntries: (...a: unknown[]) => deleteEntries(...a),
    deleteVirtualFolder: (...a: unknown[]) => deleteVirtualFolder(...a),
    renameEntry: vi.fn().mockResolvedValue({ id: 'x', fileName: 'y' }),
  },
  // FileBrowser imports this filter constant; mirror the real export so the
  // module mock is complete (it was added to storage-api in c1fdc199d).
  S3_FILES_FILTER: { filesOnly: true, s3Only: true },
}));

// ---- dnd-kit DndContext: stub that captures onDragEnd so we can fire a drop ----
let capturedOnDragEnd: ((e: { active: { id: string }; over: { id: string } | null }) => void) | null = null;
vi.mock('@dnd-kit/core', () => ({
  DndContext: ({ children, onDragEnd }: { children: React.ReactNode; onDragEnd: (e: { active: { id: string }; over: { id: string } | null }) => void }) => {
    capturedOnDragEnd = onDragEnd;
    return <div data-testid="dnd">{children}</div>;
  },
  // DragOverlay (drag-preview thumbnail host) - passthrough so the grid still renders.
  DragOverlay: ({ children }: { children: React.ReactNode }) => <div data-testid="drag-overlay">{children}</div>,
  PointerSensor: class {},
  useSensor: () => ({}),
  useSensors: () => [],
}));

// ---- minimal child stubs: expose just the props under test ----
vi.mock('../FolderCard', () => ({
  // Manual folder stub: exposes label + the select checkbox (it IS selectable).
  FolderCard: ({ entry, onOpen, onToggleSelect, countLabel, label }: { entry: StorageExplorerEntry; onOpen: (e: StorageExplorerEntry) => void; onToggleSelect: (id: string) => void; countLabel: string; label: string }) => (
    <div data-testid={`folder-${entry.id}`}>
      <button data-testid={`open-folder-${entry.id}`} onClick={() => onOpen(entry)}>{label}</button>
      <span data-testid={`count-${entry.id}`}>{countLabel}</span>
      <input type="checkbox" data-testid={`select-folder-${entry.id}`} onChange={() => onToggleSelect(entry.id)} />
    </div>
  ),
  // Virtual workflow folder stub: keyed by virtualId (its id is null). It joins
  // the bulk selection (checkbox toggling by virtualId) like every other row.
  VirtualFolderCard: ({ entry, onOpen, onToggleSelect, countLabel, label }: { entry: StorageExplorerEntry; onOpen: (e: StorageExplorerEntry) => void; onToggleSelect?: (key: string) => void; countLabel: string; label: string }) => (
    <div data-testid={`vfolder-${entry.virtualId}`}>
      <button data-testid={`open-vfolder-${entry.virtualId}`} onClick={() => onOpen(entry)}>{label}</button>
      <span data-testid={`vcount-${entry.virtualId}`}>{countLabel}</span>
      {onToggleSelect && (
        <input type="checkbox" data-testid={`select-vfolder-${entry.virtualId}`} onChange={() => onToggleSelect(entry.virtualId!)} />
      )}
    </div>
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
vi.mock('@/components/ui/BulkDeleteModal', () => ({
  // Expose the confirm callback so the unified-delete test can drive it.
  BulkDeleteModal: ({ isOpen, onConfirm }: { isOpen: boolean; onConfirm: () => void }) =>
    isOpen ? <button data-testid="confirm-bulk-delete" onClick={onConfirm} /> : null,
}));
vi.mock('@/components/ToastContainer', () => ({ default: () => null }));
vi.mock('@/components/Toast', () => ({ useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }) }));
vi.mock('@/hooks/useAuthToken', () => ({ useAuthToken: () => 'token' }));
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (v: unknown) => v }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => {} }));
vi.mock('@/lib/stores/current-org-store', () => ({
  getActiveOrgHeaderForRequest: () => ({}),
  // Folder tests exercise the write paths - run them as a non-VIEWER.
  useCanMutateInCurrentOrg: () => true,
}));
vi.mock('@/lib/api/orchestrator/file.service', () => ({ fileService: { downloadAndSave: vi.fn(), uploadGeneric: vi.fn() } }));

import { FileBrowser } from '../FileBrowser';
import { fileService } from '@/lib/api/orchestrator/file.service';
import {
  onFilesDetailState,
  emitFilesFolderNavigate,
  type FilesDetailState,
} from '@/lib/files/filesHeaderBus';

function folder(id: string, name: string, extra: Partial<StorageExplorerEntry> = {}): StorageExplorerEntry {
  return {
    id, fileName: name, isFolder: true, parentFolderId: null, childCount: 0, previewFiles: [],
    storageType: 'S3_FILE', sourceType: null, mimeType: null, sizeBytes: null, formattedSize: '0 B',
    createdAt: '2026-06-01T00:00:00Z', s3Key: null, contentType: null,
  } as unknown as StorageExplorerEntry as StorageExplorerEntry & typeof extra;
}
function file(id: string, name: string): StorageExplorerEntry {
  return {
    id, fileName: name, isFolder: false, parentFolderId: null,
    storageType: 'S3_FILE', sourceType: 'S3_FILE', mimeType: 'image/png', sizeBytes: 10, formattedSize: '10 B',
    createdAt: '2026-06-01T00:00:00Z', s3Key: `k/${id}`, contentType: null,
  } as unknown as StorageExplorerEntry;
}
/** A Phase 2b VIRTUAL workflow folder (id null, navigates by virtualId). */
function vfolder(virtualId: string, extra: Partial<StorageExplorerEntry> = {}): StorageExplorerEntry {
  return {
    id: null, virtualId, virtualKind: 'WORKFLOW', workflowId: 'wf1', workflowName: 'Daily Report',
    isFolder: true, parentFolderId: null, childCount: 4, previewFiles: [],
    storageType: 'S3_FILE', sourceType: null, fileName: null, mimeType: null, sizeBytes: null, formattedSize: '0 B',
    createdAt: '2026-06-01T00:00:00Z', s3Key: null, contentType: null,
    ...extra,
  } as unknown as StorageExplorerEntry;
}

beforeEach(() => {
  hookState = { entries: [], parentFolderId: null };
  navigateToFolder.mockClear();
  refresh.mockClear();
  createFolder.mockClear();
  moveEntries.mockClear();
  capturedOnDragEnd = null;
});
afterEach(() => cleanup());

describe('FileBrowser - manual folders (V313)', () => {
  it('renders FolderCards (with exact count label) ahead of FileCards, and files are draggable', () => {
    hookState.entries = [folder('f1', 'Reports', { childCount: 3 }), file('a', 'a.png')];
    hookState.entries[0] = { ...hookState.entries[0], childCount: 3 };
    const { getByTestId } = render(<FileBrowser />);
    expect(getByTestId('folder-f1')).toBeTruthy();
    expect(getByTestId('count-f1').textContent).toBe('itemCount:3');
    // FileCard is rendered draggable in the folder-aware browser.
    expect(getByTestId('file-a').getAttribute('data-draggable')).toBe('true');
  });

  it('entering a folder calls navigateToFolder(id) and broadcasts the folder trail on the bus', () => {
    hookState.entries = [folder('f1', 'Reports')];
    const states: FilesDetailState[] = [];
    const off = onFilesDetailState((s) => states.push(s));
    const { getByTestId } = render(<FileBrowser />);
    act(() => fireEvent.click(getByTestId('open-folder-f1')));
    expect(navigateToFolder).toHaveBeenCalledWith('f1');
    // The latest broadcast carries the trail [Reports].
    const last = states[states.length - 1];
    expect(last.folderTrail).toEqual([{ id: 'f1', name: 'Reports' }]);
    off();
  });

  it('a folder-navigate bus event (breadcrumb/header back) returns to root via navigateToFolder(null)', () => {
    hookState.entries = [folder('f1', 'Reports')];
    const { getByTestId } = render(<FileBrowser />);
    act(() => fireEvent.click(getByTestId('open-folder-f1')));
    navigateToFolder.mockClear();
    act(() => emitFilesFolderNavigate(null));
    expect(navigateToFolder).toHaveBeenCalledWith(null);
  });

  it('creating a folder calls createFolder(name, currentFolderId) then refreshes', async () => {
    hookState.entries = [folder('f1', 'Reports')];
    const { getByText, getByPlaceholderText } = render(<FileBrowser />);
    fireEvent.click(getByText('newFolder'));
    const input = getByPlaceholderText('folderNamePlaceholder');
    fireEvent.change(input, { target: { value: 'Docs' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    await waitFor(() => expect(createFolder).toHaveBeenCalledWith('Docs', null));
    expect(refresh).toHaveBeenCalled();
  });

  it('creates the folder under the OPEN folder (currentFolderId = parentFolderId)', async () => {
    hookState.entries = [file('a', 'a.png')];
    hookState.parentFolderId = 'f1'; // we are inside folder f1
    const { getByText, getByPlaceholderText } = render(<FileBrowser />);
    fireEvent.click(getByText('newFolder'));
    const input = getByPlaceholderText('folderNamePlaceholder');
    fireEvent.change(input, { target: { value: 'Sub' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    await waitFor(() => expect(createFolder).toHaveBeenCalledWith('Sub', 'f1'));
  });

  it('uploading while inside a manual folder files the upload INTO that folder (uploadGeneric currentManualFolderId)', async () => {
    hookState.entries = [file('a', 'a.png')];
    hookState.parentFolderId = 'f1'; // we are inside manual folder f1
    vi.mocked(fileService.uploadGeneric).mockClear();
    vi.mocked(fileService.uploadGeneric).mockResolvedValue({ id: 'up1' } as never);
    const { container } = render(<FileBrowser />);
    const input = container.querySelector('input[type="file"]') as HTMLInputElement;
    const f = new File(['x'], 'up.txt', { type: 'text/plain' });
    await act(async () => { fireEvent.change(input, { target: { files: [f] } }); });
    await waitFor(() => expect(fileService.uploadGeneric).toHaveBeenCalledWith(f, 'files', 'f1'));
  });

  it('uploading inside a VIRTUAL workflow folder files at ROOT (never targets a wf: folder)', async () => {
    hookState.entries = [];
    hookState.parentFolderId = 'wf:wf1'; // computed virtual folder - cannot hold uploads
    vi.mocked(fileService.uploadGeneric).mockClear();
    vi.mocked(fileService.uploadGeneric).mockResolvedValue({ id: 'up2' } as never);
    const { container } = render(<FileBrowser />);
    const input = container.querySelector('input[type="file"]') as HTMLInputElement;
    const f = new File(['x'], 'up.txt', { type: 'text/plain' });
    await act(async () => { fireEvent.change(input, { target: { files: [f] } }); });
    await waitFor(() => expect(fileService.uploadGeneric).toHaveBeenCalledWith(f, 'files', null));
  });

  it('shows an in-page breadcrumb (current folder) + an up-one-level back button once inside a folder', () => {
    hookState.entries = [folder('f1', 'Reports')];
    const { getByTestId, queryByLabelText, getByLabelText, getByRole } = render(<FileBrowser />);
    // At root: header is just the title, no in-page back button.
    expect(queryByLabelText('backToParent')).toBeNull();

    act(() => fireEvent.click(getByTestId('open-folder-f1')));

    // Inside the folder: the current folder name is in the in-page heading and a back button appears.
    expect(getByRole('heading', { level: 1 }).textContent).toContain('Reports');
    const back = getByLabelText('backToParent');
    expect(back).toBeTruthy();

    // Clicking back at depth 1 returns to root (goToFolder(null)).
    navigateToFolder.mockClear();
    act(() => fireEvent.click(back));
    expect(navigateToFolder).toHaveBeenCalledWith(null);
  });

  it('dropping a card on a folder calls moveEntries([draggedId], folderId) and refreshes', async () => {
    hookState.entries = [folder('f1', 'Reports'), file('a', 'a.png')];
    render(<FileBrowser />);
    // dnd-kit is stubbed → fire its onDragEnd directly (file a dropped on folder f1).
    expect(capturedOnDragEnd).toBeTruthy();
    await act(async () => { capturedOnDragEnd!({ active: { id: 'a' }, over: { id: 'f1' } }); });
    await waitFor(() => expect(moveEntries).toHaveBeenCalledWith(['a'], 'f1'));
    expect(refresh).toHaveBeenCalled();
  });

  it('dropping moves the WHOLE selection when the dragged card is part of a multi-selection', async () => {
    hookState.entries = [folder('f1', 'Reports'), file('a', 'a.png'), file('b', 'b.png')];
    const { getByTestId } = render(<FileBrowser />);
    fireEvent.click(getByTestId('select-file-a'));
    fireEvent.click(getByTestId('select-file-b'));
    await act(async () => { capturedOnDragEnd!({ active: { id: 'a' }, over: { id: 'f1' } }); });
    await waitFor(() => expect(moveEntries).toHaveBeenCalled());
    const movedIds = moveEntries.mock.calls[0][0] as string[];
    expect(new Set(movedIds)).toEqual(new Set(['a', 'b']));
    expect(moveEntries.mock.calls[0][1]).toBe('f1');
  });

  it('a no-op drop (onto itself) does not call moveEntries', async () => {
    hookState.entries = [folder('f1', 'Reports')];
    render(<FileBrowser />);
    await act(async () => { capturedOnDragEnd!({ active: { id: 'f1' }, over: { id: 'f1' } }); });
    expect(moveEntries).not.toHaveBeenCalled();
  });

  it('Rename action shows only when exactly one FOLDER is selected', () => {
    hookState.entries = [folder('f1', 'Reports'), file('a', 'a.png')];
    const { queryByText, getByTestId } = render(<FileBrowser />);
    // Nothing selected → no Rename.
    expect(queryByText('renameFolder')).toBeNull();
    // Select one folder → Rename appears.
    fireEvent.click(getByTestId('select-folder-f1'));
    expect(queryByText('renameFolder')).toBeTruthy();
  });

  it('Rename does NOT show when a single FILE is selected, nor for a multi-selection', () => {
    hookState.entries = [folder('f1', 'Reports'), file('a', 'a.png')];
    const { queryByText, getByTestId } = render(<FileBrowser />);
    // Single file → no Rename (rename is folder-only).
    fireEvent.click(getByTestId('select-file-a'));
    expect(queryByText('renameFolder')).toBeNull();
    // Add the folder too (2 selected) → still no Rename (needs exactly one).
    fireEvent.click(getByTestId('select-folder-f1'));
    expect(queryByText('renameFolder')).toBeNull();
  });
});

describe('FileBrowser - floating selection pill positioning (regression)', () => {
  // Bug: the SelectionActionBar (`absolute bottom-6`) was rendered INSIDE the
  // browser's `relative min-h-…` root, which spans the whole page-scrolled list -
  // so the pill landed at the bottom of the CONTENT (visible only after scrolling
  // to the very end, at a height that varied with the list). It must be a SIBLING
  // of that root so it anchors to the viewport-sized app <main> instead.
  it('renders the pill OUTSIDE the relative page root (anchors to the app <main>)', () => {
    hookState.entries = [file('a', 'a.png')];
    const { container, getByTestId, queryByTestId } = render(<FileBrowser />);
    // No selection → no pill.
    expect(queryByTestId('selection-action-bar')).toBeNull();
    fireEvent.click(getByTestId('select-file-a'));
    const bar = getByTestId('selection-action-bar');
    const pageRoot = container.querySelector('.relative.flex.flex-col');
    expect(pageRoot).toBeTruthy();
    expect(pageRoot!.contains(bar)).toBe(false);
    // And no OTHER positioned ancestor may capture the pill inside this component's
    // output - every ancestor up to the render container must be non-positioned.
    for (let el = bar.parentElement; el && el !== container; el = el.parentElement) {
      expect(/(^|\s)(relative|absolute|fixed|sticky)(\s|$)/.test(el.className)).toBe(false);
    }
  });

  it('the pill clears the selection via its built-in × button', () => {
    hookState.entries = [file('a', 'a.png'), file('b', 'b.png')];
    const { getByTestId, queryByTestId } = render(<FileBrowser />);
    fireEvent.click(getByTestId('select-file-a'));
    fireEvent.click(getByTestId('select-file-b'));
    expect(getByTestId('selection-action-bar')).toBeTruthy();
    fireEvent.click(getByTestId('selection-action-bar-clear'));
    expect(queryByTestId('selection-action-bar')).toBeNull();
  });

  it('an OS file dropped ON the pill still uploads (the pill left the root drop zone)', async () => {
    // Moving the pill out of the root div also moved it out of the root's
    // onDragOver/onDrop - a wrapper re-attaches them so a drop released over the
    // pill uploads instead of falling through to the browser default.
    hookState.entries = [file('a', 'a.png')];
    const { getByTestId } = render(<FileBrowser />);
    fireEvent.click(getByTestId('select-file-a'));
    const bar = getByTestId('selection-action-bar');
    const dropped = new File(['x'], 'dropped.txt', { type: 'text/plain' });
    await act(async () => {
      fireEvent.drop(bar, { dataTransfer: { files: [dropped] } });
    });
    expect(fileService.uploadGeneric).toHaveBeenCalledWith(dropped, 'files', null);
  });
});

describe('FileBrowser - virtual workflow folders (Phase 2b)', () => {
  it('renders a VirtualFolderCard (not a FolderCard) for a virtual entry, with its label + count', () => {
    hookState.entries = [vfolder('wf:1', { childCount: 4 })];
    const { getByTestId, queryByTestId } = render(<FileBrowser />);
    // Rendered via the navigation-only card, keyed by virtualId.
    expect(getByTestId('vfolder-wf:1')).toBeTruthy();
    expect(getByTestId('vcount-wf:1').textContent).toBe('itemCount:4');
    // NOT rendered as a manual FolderCard (no folder-null testid).
    expect(queryByTestId('folder-null')).toBeNull();
  });

  it('opening a virtual folder navigates by its virtualId (not by id)', () => {
    hookState.entries = [vfolder('wf:1/e0', { virtualKind: 'EPOCH', epoch: 0 })];
    const { getByTestId } = render(<FileBrowser />);
    act(() => fireEvent.click(getByTestId('open-vfolder-wf:1/e0')));
    expect(navigateToFolder).toHaveBeenCalledWith('wf:1/e0');
  });

  it('a virtual folder IS selectable - it joins the same selection as the file rows', () => {
    // 1 virtual folder + 1 file. Both rows carry a checkbox and count together.
    hookState.entries = [vfolder('wf:1'), file('a', 'a.png')];
    const { getByTestId, getAllByText } = render(<FileBrowser />);
    fireEvent.click(getByTestId('select-file-a'));
    fireEvent.click(getByTestId('select-vfolder-wf:1'));
    // "2 selected" appears in the select-all label AND the floating action bar.
    expect(getAllByText('selectedCount:2').length).toBeGreaterThan(0);
  });

  it('deletes a MIXED selection through one flow: bulk endpoint for real rows + per-ref endpoint for each workflow folder', async () => {
    hookState.entries = [vfolder('wf:1'), file('a', 'a.png')];
    const { getByTestId, getAllByText } = render(<FileBrowser />);
    fireEvent.click(getByTestId('select-file-a'));
    fireEvent.click(getByTestId('select-vfolder-wf:1'));
    // Open the confirm from the floating bar, then confirm.
    fireEvent.click(getAllByText('deleteSelected')[0]);
    await act(async () => { fireEvent.click(getByTestId('confirm-bulk-delete')); });
    await waitFor(() => {
      expect(deleteEntries).toHaveBeenCalledWith(['a']);
      expect(deleteVirtualFolder).toHaveBeenCalledWith('wf:1');
    });
    expect(refresh).toHaveBeenCalled();
  });

  it('disables download and move while a workflow folder is selected (delete stays available)', () => {
    hookState.entries = [vfolder('wf:1'), file('a', 'a.png')];
    const { getByTestId, getAllByText } = render(<FileBrowser />);
    fireEvent.click(getByTestId('select-vfolder-wf:1'));
    const download = getAllByText('downloadSelected')[0].closest('button')!;
    const move = getAllByText('moveTo')[0].closest('button')!;
    const del = getAllByText('deleteSelected')[0].closest('button')!;
    expect(download.disabled).toBe(true);
    expect(move.disabled).toBe(true);
    expect(del.disabled).toBe(false);
  });

  it('hides the New folder button while inside a virtual folder (no real parent to attach to)', () => {
    hookState.entries = [file('a', 'a.png')];
    hookState.parentFolderId = 'wf:1/e0'; // navigated INTO a virtual grouping
    const { queryByText } = render(<FileBrowser />);
    expect(queryByText('newFolder')).toBeNull();
  });

  it('shows the New folder button at root and inside a MANUAL folder', () => {
    hookState.entries = [file('a', 'a.png')];
    hookState.parentFolderId = 'manual-uuid'; // a real manual folder
    const { getByText } = render(<FileBrowser />);
    expect(getByText('newFolder')).toBeTruthy();
  });
});
