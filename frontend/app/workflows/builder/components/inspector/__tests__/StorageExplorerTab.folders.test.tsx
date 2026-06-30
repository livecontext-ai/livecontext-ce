// @vitest-environment jsdom
/**
 * Folder-tree navigation in the side-panel {@link StorageExplorerTab}.
 *
 * Asserts the gated folder mode: in explorer mode (no {@code onSelect}, no
 * {@code focusS3Key}) the tab opts the hook into the folder-aware listing,
 * renders compact folder rows (manual + virtual) ahead of file rows, navigates
 * by the correct key (virtualId for virtual, id for manual), keeps file rows
 * opening the detail view, and shows the breadcrumb. With a {@code focusS3Key}
 * deep-link OR in picker mode ({@code onSelect}), folder mode is OFF - the hook
 * is NOT folder-aware and no folder rows render (legacy FLAT path preserved).
 *
 * The heavy {@code useStorageExplorer} hook is mocked so the test drives the
 * entries + captures {@code navigateToFolder} and the folder options it was
 * called with. {@code storageApi} and the auth/preview side-channels are stubbed.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, cleanup, fireEvent } from '@testing-library/react';
import type { StorageExplorerEntry } from '@/lib/api/storage-api';

// ---- i18n: echo the key (assert on stable strings). ICU count → "key:N". ----
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars && 'count' in vars ? `${key}:${vars.count}` : key,
}));

// ---- the storage hook: controllable entries + captured nav + captured options ----
const navigateToFolder = vi.fn();
const refresh = vi.fn();
const setPage = vi.fn();
let lastHookOptions: Record<string, unknown> | undefined;
let hookState: { entries: StorageExplorerEntry[]; parentFolderId: string | null | undefined } = {
  entries: [],
  parentFolderId: undefined,
};
vi.mock('@/app/workflows/builder/components/inspector/useStorageExplorer', () => ({
  useStorageExplorer: (
    _workflowId?: string,
    _storageTypeDefault?: string,
    _sourceTypeDefault?: string,
    options?: Record<string, unknown>,
  ) => {
    lastHookOptions = options;
    return {
      entries: hookState.entries,
      totalElements: hookState.entries.length,
      totalPages: 1,
      currentPage: 0,
      pageSize: 50,
      loading: false,
      error: null,
      search: '',
      sourceTypeFilter: '',
      storageTypeFilter: '',
      dateFrom: '',
      dateTo: '',
      fileType: '_all',
      parentFolderId: hookState.parentFolderId,
      setSearch: vi.fn(),
      setSourceTypeFilter: vi.fn(),
      setStorageTypeFilter: vi.fn(),
      setDateFrom: vi.fn(),
      setDateTo: vi.fn(),
      setFileType: vi.fn(),
      navigateToFolder,
      setPage,
      setPageSize: vi.fn(),
      refresh,
    };
  },
}));

// ---- storage API + side-channels: stub so nothing hits the network ----
vi.mock('@/lib/api/storage-api', () => ({
  storageApi: {
    deleteEntries: vi.fn().mockResolvedValue({ deletedCount: 0 }),
    getEntryPreview: vi.fn().mockResolvedValue(null),
  },
  S3_FILES_FILTER: { filesOnly: true, s3Only: true },
}));
vi.mock('@/hooks/useAuthToken', () => ({ useAuthToken: () => 'token' }));
vi.mock('@/hooks/useAuthedObjectUrl', () => ({ useAuthedObjectUrl: () => ({ url: null, error: false }) }));
vi.mock('@/lib/stores/current-org-store', () => ({ getActiveOrgHeaderForRequest: () => ({}) }));
vi.mock('@/lib/api/orchestrator/file.service', () => ({
  fileService: { downloadAndSave: vi.fn() },
  getFileUrlById: () => 'url',
}));
vi.mock('@/lib/utils/url-auth', () => ({ openAuthedFileInNewTab: vi.fn() }));
// FileDetailView is rendered when a file row opens the inline detail - stub it
// to a stable testid so we can assert "file row opened the detail".
vi.mock('@/components/app/FileDetailView', () => ({
  FileDetailView: () => <div data-testid="file-detail" />,
}));

import { StorageExplorerTab } from '../StorageExplorerTab';

function folder(id: string, name: string, extra: Partial<StorageExplorerEntry> = {}): StorageExplorerEntry {
  return {
    id, fileName: name, isFolder: true, parentFolderId: null, childCount: 0, previewFiles: [],
    storageType: 'S3_FILE', sourceType: null, mimeType: null, sizeBytes: null, formattedSize: '0 B',
    createdAt: '2026-06-01T00:00:00Z', s3Key: null, contentType: null, workflowId: null, workflowName: null,
    projectId: null, runId: null, stepKey: null, epoch: null,
    ...extra,
  } as unknown as StorageExplorerEntry;
}
function file(id: string, name: string): StorageExplorerEntry {
  return {
    id, fileName: name, isFolder: false, parentFolderId: null,
    storageType: 'S3_FILE', sourceType: 'S3_FILE', mimeType: 'image/png', sizeBytes: 10, formattedSize: '10 B',
    createdAt: '2026-06-01T00:00:00Z', s3Key: `k/${id}`, contentType: null, workflowId: null, workflowName: null,
    projectId: null, runId: null, stepKey: null, epoch: null, childCount: null,
  } as unknown as StorageExplorerEntry;
}
/** A Phase 2b VIRTUAL workflow folder (id null, navigates by virtualId). */
function vfolder(virtualId: string, extra: Partial<StorageExplorerEntry> = {}): StorageExplorerEntry {
  return {
    id: null, virtualId, virtualKind: 'WORKFLOW', workflowId: 'wf1', workflowName: 'Daily Report',
    isFolder: true, parentFolderId: null, childCount: 4, previewFiles: [],
    storageType: 'S3_FILE', sourceType: null, fileName: null, mimeType: null, sizeBytes: null, formattedSize: '0 B',
    createdAt: '2026-06-01T00:00:00Z', s3Key: null, contentType: null,
    projectId: null, runId: null, stepKey: null, epoch: null, spawn: null, itemIndex: null,
    ...extra,
  } as unknown as StorageExplorerEntry;
}

beforeEach(() => {
  hookState = { entries: [], parentFolderId: undefined };
  lastHookOptions = undefined;
  navigateToFolder.mockClear();
  refresh.mockClear();
  setPage.mockClear();
});
afterEach(() => cleanup());

describe('StorageExplorerTab - folder mode (explorer, no deep-link)', () => {
  it('opts the hook into folder-aware + virtual-workflow-folder listing', () => {
    hookState.parentFolderId = null;
    render(<StorageExplorerTab />);
    expect(lastHookOptions?.folderAware).toBe(true);
    expect(lastHookOptions?.virtualWorkflowFolders).toBe(true);
  });

  it('renders a compact manual folder row with its label, count and a chevron', () => {
    hookState.parentFolderId = null;
    hookState.entries = [folder('f1', 'Reports', { childCount: 3 }), file('a', 'a.png')];
    const { getByText, container } = render(<StorageExplorerTab />);
    // Folder label + exact count (echoed ICU key).
    expect(getByText('Reports')).toBeTruthy();
    expect(getByText('itemCount:3')).toBeTruthy();
    // The folder row carries a Folder icon (accent) + a right chevron (muted) -
    // assert at least the accent folder glyph is present (lucide → svg).
    expect(container.querySelector('svg.text-\\[var\\(--accent-primary\\)\\]')).toBeTruthy();
  });

  it('clicking a MANUAL folder row navigates by its id', () => {
    hookState.parentFolderId = null;
    hookState.entries = [folder('f1', 'Reports')];
    const { getByText } = render(<StorageExplorerTab />);
    fireEvent.click(getByText('Reports'));
    expect(navigateToFolder).toHaveBeenCalledWith('f1');
  });

  it('clicking a VIRTUAL folder row navigates by its virtualId (not by null id)', () => {
    hookState.parentFolderId = null;
    hookState.entries = [vfolder('wf:1', { childCount: 4 })];
    const { getByText } = render(<StorageExplorerTab />);
    // Virtual WORKFLOW folder label = workflowName.
    fireEvent.click(getByText('Daily Report'));
    expect(navigateToFolder).toHaveBeenCalledWith('wf:1');
  });

  it('a file row still opens the inline detail view (FileDetailView)', () => {
    hookState.parentFolderId = null;
    hookState.entries = [file('a', 'a.png')];
    const { getByText, getByTestId } = render(<StorageExplorerTab />);
    fireEvent.click(getByText('a.png'));
    expect(getByTestId('file-detail')).toBeTruthy();
  });

  it('renders folder rows BEFORE file rows in one list', () => {
    hookState.parentFolderId = null;
    hookState.entries = [file('a', 'zzz.png'), folder('f1', 'AAA')];
    const { getByText } = render(<StorageExplorerTab />);
    const folderEl = getByText('AAA');
    const fileEl = getByText('zzz.png');
    // Folder appears earlier in document order than the file.
    expect(folderEl.compareDocumentPosition(fileEl) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('a virtual folder (id null) is never selectable - a folder-only listing shows no checkbox; '
     + 'a file is still selectable', () => {
    hookState.parentFolderId = null;
    // Only a virtual workflow folder (id===null): nothing selectable → no select-all bar,
    // and the folder row itself has no checkbox. Zero checkboxes total.
    hookState.entries = [vfolder('wf:1')];
    const { container, rerender } = render(<StorageExplorerTab />);
    expect(container.querySelectorAll('input[type="checkbox"]').length).toBe(0);

    // Add a real file → it IS selectable (its row checkbox + the select-all bar appear).
    hookState.entries = [vfolder('wf:1'), file('a', 'a.png')];
    rerender(<StorageExplorerTab />);
    expect(container.querySelectorAll('input[type="checkbox"]').length).toBeGreaterThan(0);
  });

  it('navigates back from depth >=2: the back button targets the parent (backTarget) and a '
     + 'mid-trail crumb jumps to that level', () => {
    hookState.parentFolderId = null;
    hookState.entries = [folder('f1', 'Alpha'), folder('f2', 'Beta')];
    const { getByText, getAllByText, getByLabelText } = render(<StorageExplorerTab />);

    // Descend two levels: Alpha then Beta (mock entries are static, both rows stay).
    fireEvent.click(getByText('Alpha'));        // trail = [Alpha]
    fireEvent.click(getByText('Beta'));         // trail = [Alpha, Beta]

    // Back button from depth 2 → parent = Alpha's id (backTarget([Alpha,Beta]) = 'f1').
    navigateToFolder.mockClear();
    fireEvent.click(getByLabelText('back'));
    expect(navigateToFolder).toHaveBeenCalledWith('f1');

    // A mid-trail crumb click jumps straight to that level. "Alpha" is now both a row
    // (span) and a crumb (button) - click the BUTTON crumb.
    navigateToFolder.mockClear();
    const alphaCrumb = getAllByText('Alpha').find((el) => el.tagName === 'BUTTON');
    expect(alphaCrumb).toBeTruthy();
    fireEvent.click(alphaCrumb as HTMLElement);
    expect(navigateToFolder).toHaveBeenCalledWith('f1');
  });

  it('shows a breadcrumb with the Files root crumb + a back button (disabled at root)', () => {
    hookState.parentFolderId = null;
    hookState.entries = [folder('f1', 'Reports')];
    const { getByText, getByLabelText, getAllByText } = render(<StorageExplorerTab />);
    // Root crumb is the files namespace title; back button present (disabled at root).
    expect(getByText('title')).toBeTruthy();
    expect((getByLabelText('back') as HTMLButtonElement).disabled).toBe(true);
    // At root only the folder ROW shows "Reports" (no crumb yet).
    expect(getAllByText('Reports')).toHaveLength(1);
    // Enter the folder → a crumb for it is pushed, so "Reports" now appears TWICE
    // (the folder row, still in the mock entries, + the new breadcrumb crumb).
    fireEvent.click(getAllByText('Reports')[0]);
    expect(navigateToFolder).toHaveBeenCalledWith('f1');
    expect(getAllByText('Reports')).toHaveLength(2);
  });
});

describe('StorageExplorerTab - flat mode (deep-link or picker)', () => {
  it('with focusS3Key set, folder mode STAYS ON: the list keeps its folders (focus highlights, never flattens)', () => {
    // A focus key no longer flattens the list - folders + files share one per-day timeline,
    // so the focused file is highlighted AND the folders stay visible (the back-from-detail fix).
    hookState.parentFolderId = null;
    hookState.entries = [folder('f1', 'Reports'), file('a', 'a.png')];
    const { getByText } = render(<StorageExplorerTab focusS3Key="k/a" />);
    expect(lastHookOptions?.folderAware).toBe(true);
    expect(lastHookOptions?.virtualWorkflowFolders).toBe(true);
    expect(getByText('Reports')).toBeTruthy(); // the folder row still renders
  });

  it('with flat set, folder mode is OFF: hook is NOT folder-aware and no folder row renders (image-gen deep-link)', () => {
    // The image-gen card forces flat so its generated image (nested under a virtual workflow
    // folder) is findable at root; the folder-aware grouping would only list the current level.
    hookState.parentFolderId = undefined;
    hookState.entries = [folder('f1', 'Reports'), file('a', 'a.png')];
    const { queryByText } = render(<StorageExplorerTab focusS3Key="k/a" flat />);
    expect(lastHookOptions?.folderAware).toBe(false);
    expect(lastHookOptions?.virtualWorkflowFolders).toBe(false);
    expect(queryByText('itemCount:0')).toBeNull();
  });

  it('in picker mode (onSelect provided), folder mode is OFF and no folder rows render', () => {
    hookState.parentFolderId = undefined;
    hookState.entries = [folder('f1', 'Reports'), file('a', 'a.png')];
    const onSelect = vi.fn();
    const { queryByText } = render(<StorageExplorerTab onSelect={onSelect} />);
    expect(lastHookOptions?.folderAware).toBe(false);
    expect(lastHookOptions?.virtualWorkflowFolders).toBe(false);
    expect(queryByText('itemCount:0')).toBeNull();
  });

  it('picker mode lists S3 files only - the hook gets S3_FILES_FILTER (filesOnly + s3Only), '
     + 'matching the Files page (app/file)', () => {
    hookState.parentFolderId = undefined;
    const onSelect = vi.fn();
    render(<StorageExplorerTab onSelect={onSelect} />);
    expect(lastHookOptions?.filesOnly).toBe(true);
    expect(lastHookOptions?.s3Only).toBe(true);
  });

  it('picker mode shows NO source-type dropdown (only S3 files exist, so a source '
     + 'selector would offer empty non-S3 buckets) - only the file-type filter remains', () => {
    hookState.parentFolderId = undefined;
    const onSelect = vi.fn();
    const { queryByText } = render(<StorageExplorerTab onSelect={onSelect} />);
    // The removed dropdown's options were rendered via these ICU keys.
    expect(queryByText('allSources')).toBeNull();
    expect(queryByText('sourceChatAttachment')).toBeNull();
    expect(queryByText('sourceStepOutput')).toBeNull();
    // The file-type filter (kept, like app/file) is still present.
    expect(queryByText('allFileTypes')).toBeTruthy();
  });

  it('explorer mode lists S3-BACKED files via S3_FILES_FILTER (s3Only), the SAME set as /app/files - '
     + 'NOT sourceType=S3_FILE, which dropped workflow-output folders (STEP_OUTPUT / screenshots)', () => {
    hookState.parentFolderId = null;
    render(<StorageExplorerTab />);
    // The fix: the side panel filters by s3Only (S3-backed) exactly like the Files page, so a
    // workflow whose outputs are STEP_OUTPUT/INTERFACE_SCREENSHOT (S3-backed but not 'S3_FILE'-
    // sourced) still surfaces its folder - instead of being filtered out by sourceType='S3_FILE'.
    expect(lastHookOptions?.filesOnly).toBe(true);
    expect(lastHookOptions?.s3Only).toBe(true);
  });
});

describe('backTarget breadcrumb helper', () => {
  it('returns null at root and for a single-segment trail, else the parent crumb id', async () => {
    const { backTarget } = await import('@/lib/files/folderBreadcrumb');
    expect(backTarget([])).toBeNull();
    expect(backTarget([{ id: 'a', name: 'A' }])).toBeNull();
    expect(backTarget([{ id: 'a', name: 'A' }, { id: 'b', name: 'B' }])).toBe('a');
    expect(backTarget([{ id: 'a', name: 'A' }, { id: 'b', name: 'B' }, { id: 'c', name: 'C' }])).toBe('b');
  });
});
