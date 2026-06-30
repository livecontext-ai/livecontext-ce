// @vitest-environment jsdom
/**
 * Tests for the Files branch of {@link useBreadcrumbs} - the headline of the
 * Files-page header work. Before this branch existed the hook produced no items
 * for the files view, so `shouldShowBreadcrumb` was false and AppHeader fell back
 * to the model selector. These assert the breadcrumb (Home / Files / [filename]),
 * the bus-driven tail, the "Files" → close-viewer command, and the reset on leave.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act, cleanup } from '@testing-library/react';

// useCurrentView is reconfigurable per test (to simulate navigating away).
let mockView: { view: string; workflowId: string | null; dataSourceId: string | null; interfaceId: string | null; publicationId: string | null } = {
  view: 'files', workflowId: null, dataSourceId: null, interfaceId: null, publicationId: null,
};

vi.mock('next/navigation', () => ({
  usePathname: () => '/en/app/files',
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock('@/hooks/useCurrentView', () => ({
  useCurrentView: () => mockView,
}));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isLoading: false }),
}));
const navigate = vi.fn();
vi.mock('@/contexts/NavigationGuardContext', () => ({
  useSafeNavigate: () => navigate,
}));
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getDataSources: vi.fn().mockResolvedValue([]),
    getWorkflow: vi.fn().mockResolvedValue({}),
    getInterface: vi.fn().mockResolvedValue({}),
  },
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getPublicationById: vi.fn().mockResolvedValue({}) },
}));
vi.mock('@/lib/api/orchestrator/project.service', () => ({
  projectService: { getProject: vi.fn().mockResolvedValue({}) },
}));
vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: { getApiById: vi.fn().mockResolvedValue({}), getToolById: vi.fn().mockResolvedValue({}) },
}));

import { useBreadcrumbs } from '../useBreadcrumbs';
import {
  emitFilesDetailState,
  onFilesDetailCommand,
  onFilesFolderNavigate,
  FILES_DETAIL_BACK,
} from '@/lib/files/filesHeaderBus';

beforeEach(() => {
  mockView = { view: 'files', workflowId: null, dataSourceId: null, interfaceId: null, publicationId: null };
  navigate.mockClear();
});
afterEach(() => cleanup());

describe('useBreadcrumbs - files view', () => {
  it('shows Home / Files and marks the view as breadcrumb-bearing (so the model selector is hidden)', () => {
    const { result } = renderHook(() => useBreadcrumbs());
    expect(result.current.isFilesView).toBe(true);
    expect(result.current.shouldShowBreadcrumb).toBe(true);
    const items = result.current.breadcrumbItems;
    expect(items).toHaveLength(2);
    expect(items[1].label).toBe('Files');
    // No file open → "Files" is the current page, not clickable.
    expect(items[1].onClick).toBeUndefined();
  });

  it('appends the open file as a truncating tail when a file is open', () => {
    const { result } = renderHook(() => useBreadcrumbs());
    act(() => emitFilesDetailState({ open: true, fileName: 'report.pdf', canPrev: false, canNext: true }));
    const items = result.current.breadcrumbItems;
    expect(items).toHaveLength(3);
    expect(items[2].label).toBe('report.pdf');
    expect(items[2].truncate).toBe(true);
    // With a file open, "Files" becomes clickable (to close the viewer).
    expect(typeof items[1].onClick).toBe('function');
    expect(result.current.filesDetail?.canNext).toBe(true);
  });

  it('clicking "Files" while a file is open emits BACK (closes the viewer) - it does not navigate', () => {
    const onBack = vi.fn();
    const off = onFilesDetailCommand(FILES_DETAIL_BACK, onBack);
    const { result } = renderHook(() => useBreadcrumbs());
    act(() => emitFilesDetailState({ open: true, fileName: 'a.png' }));
    act(() => result.current.breadcrumbItems[1].onClick!());
    expect(onBack).toHaveBeenCalledTimes(1);
    expect(navigate).not.toHaveBeenCalled();
    off();
  });

  it('drops the files detail state when navigating away from the files view', () => {
    const { result, rerender } = renderHook(() => useBreadcrumbs());
    act(() => emitFilesDetailState({ open: true, fileName: 'a.png' }));
    expect(result.current.filesDetail?.open).toBe(true);

    mockView = { view: 'chat', workflowId: null, dataSourceId: null, interfaceId: null, publicationId: null };
    rerender();
    expect(result.current.isFilesView).toBe(false);
    expect(result.current.filesDetail).toBeNull();
  });

  it('makes the open-file tail editable and dispatches openMetadataEditModal{file} on click (rename, like workflow)', () => {
    const { result } = renderHook(() => useBreadcrumbs());
    act(() => emitFilesDetailState({ open: true, fileId: 'file-123', fileName: 'report.pdf' }));
    const tail = result.current.breadcrumbItems[2];
    expect(tail.editable).toBe(true);

    const events: any[] = [];
    const handler = (e: Event) => events.push((e as CustomEvent).detail);
    window.addEventListener('openMetadataEditModal', handler);
    act(() => tail.onClick!());
    window.removeEventListener('openMetadataEditModal', handler);

    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ resourceType: 'file', id: 'file-123', name: 'report.pdf' });
  });

  it('keeps the file tail NON-editable when no fileId is present (defensive - cannot rename without an id)', () => {
    const { result } = renderHook(() => useBreadcrumbs());
    act(() => emitFilesDetailState({ open: true, fileName: 'report.pdf' }));
    const tail = result.current.breadcrumbItems[2];
    expect(tail.editable).toBeFalsy();
    expect(tail.onClick).toBeUndefined();
  });

  // ---- V313 manual-folder trail ----
  it('renders the manual-folder trail (Home / Files / FolderA / SubB) with the last folder as the current page', () => {
    const { result } = renderHook(() => useBreadcrumbs());
    act(() => emitFilesDetailState({
      open: false,
      folderTrail: [{ id: 'a', name: 'FolderA' }, { id: 'b', name: 'SubB' }],
    }));
    const items = result.current.breadcrumbItems;
    expect(items.map((i) => i.label)).toEqual(['', 'Files', 'FolderA', 'SubB']);
    // "Files" navigates to root (we're inside a folder), FolderA navigates into A,
    // SubB is the current page (no file open) → not clickable.
    expect(typeof items[1].onClick).toBe('function');
    expect(typeof items[2].onClick).toBe('function');
    expect(items[3].onClick).toBeUndefined();
  });

  it('clicking an intermediate folder crumb emits a folder-navigate to that folder', () => {
    const target: Array<string | null> = [];
    const off = onFilesFolderNavigate((id) => target.push(id));
    const { result } = renderHook(() => useBreadcrumbs());
    act(() => emitFilesDetailState({
      open: false,
      folderTrail: [{ id: 'a', name: 'FolderA' }, { id: 'b', name: 'SubB' }],
    }));
    act(() => result.current.breadcrumbItems[2].onClick!()); // FolderA
    expect(target).toEqual(['a']);
    off();
  });

  it('clicking "Files" while inside a folder navigates to root (null)', () => {
    const target: Array<string | null> = [];
    const off = onFilesFolderNavigate((id) => target.push(id));
    const { result } = renderHook(() => useBreadcrumbs());
    act(() => emitFilesDetailState({ open: false, folderTrail: [{ id: 'a', name: 'FolderA' }] }));
    act(() => result.current.breadcrumbItems[1].onClick!()); // Files
    expect(target).toEqual([null]);
    off();
  });

  it('with a file open inside a folder, the trail folders precede the editable file tail', () => {
    const { result } = renderHook(() => useBreadcrumbs());
    act(() => emitFilesDetailState({
      open: true,
      fileId: 'file-9',
      fileName: 'pic.png',
      folderTrail: [{ id: 'a', name: 'FolderA' }],
    }));
    const items = result.current.breadcrumbItems;
    // Home / Files / FolderA / pic.png - and FolderA is clickable (we can navigate
    // back to it even with a file open).
    expect(items.map((i) => i.label)).toEqual(['', 'Files', 'FolderA', 'pic.png']);
    expect(typeof items[2].onClick).toBe('function');
    expect(items[3].editable).toBe(true);
  });
});
