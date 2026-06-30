import { describe, it, expect, vi } from 'vitest';

vi.mock('@/components/app/FileDetailView', () => ({
  FileDetailView: () => null,
}));

vi.mock('@/app/workflows/builder/components/inspector/StorageExplorerTab', () => ({
  StorageExplorerTab: () => null,
}));

import { openFilesPanel, FILES_TAB_ID } from '../openFilesPanel';

function makeSidePanel() {
  return { openTab: vi.fn() };
}

describe('openFilesPanel', () => {
  it('is a no-op when sidePanel is null/undefined', () => {
    expect(() => openFilesPanel(null)).not.toThrow();
    expect(() => openFilesPanel(undefined)).not.toThrow();
  });

  it('opens the canonical "files-panel" tab labeled "Files" when no file is provided', () => {
    const sp = makeSidePanel();
    openFilesPanel(sp);

    expect(sp.openTab).toHaveBeenCalledTimes(1);
    const tab = sp.openTab.mock.calls[0][0];
    expect(tab.id).toBe(FILES_TAB_ID);
    expect(tab.label).toBe('Files');
    expect(tab.preferredWidth).toBe(0.4);
  });

  it('opens the canonical "files-panel" tab when an empty path is given (treated as no file)', () => {
    const sp = makeSidePanel();
    openFilesPanel(sp, { path: '' });

    expect(sp.openTab).toHaveBeenCalledTimes(1);
    expect(sp.openTab.mock.calls[0][0].label).toBe('Files');
  });

  it('opens with the file name as the tab label when a file is provided', () => {
    const sp = makeSidePanel();
    openFilesPanel(sp, { path: 'tenant/run/abc.png', name: 'abc.png', mimeType: 'image/png', size: 42 });

    expect(sp.openTab).toHaveBeenCalledTimes(1);
    const tab = sp.openTab.mock.calls[0][0];
    expect(tab.id).toBe(FILES_TAB_ID);
    expect(tab.label).toBe('abc.png');
  });

  it('falls back to the path basename when no name is provided', () => {
    const sp = makeSidePanel();
    openFilesPanel(sp, { path: 'tenant/run/no-name.zip' });

    expect(sp.openTab.mock.calls[0][0].label).toBe('no-name.zip');
  });

  it('detail-view onBack re-opens the same tab focused on the file path', () => {
    // The onBack chevron MUST swap content back to the focused list - same
    // tab id ('files-panel'), no parallel tabs, focus preserved on the file
    // the user was just viewing. Regression guard against future refactors.
    const sp = makeSidePanel();
    const path = 'tenant/run/abc.png';
    openFilesPanel(sp, { path, name: 'abc.png' });

    const detailTab = sp.openTab.mock.calls[0][0];
    const onBack = detailTab.content?.props?.onBack as (() => void) | undefined;
    expect(typeof onBack).toBe('function');

    onBack!();
    expect(sp.openTab).toHaveBeenCalledTimes(2);
    const listTab = sp.openTab.mock.calls[1][0];
    expect(listTab.id).toBe(FILES_TAB_ID);
    expect(listTab.label).toBe('Files');
    expect(listTab.content?.props?.focusS3Key).toBe(path);
  });

  it('opens the detail view for an id-only file with no s3 path (project Files tab)', () => {
    // A project file exposes no s3 key (toStorageEntry nulls it); FileDetailView serves
    // media by entryId, so it MUST still open the detail - not fall back to the list.
    const sp = makeSidePanel();
    openFilesPanel(sp, { id: 'row-uuid-1', name: 'report.pdf', mimeType: 'application/pdf' });

    expect(sp.openTab).toHaveBeenCalledTimes(1);
    const tab = sp.openTab.mock.calls[0][0];
    expect(tab.id).toBe(FILES_TAB_ID);
    expect(tab.label).toBe('report.pdf');
    expect(tab.content?.props?.entryId).toBe('row-uuid-1');
    expect(tab.content?.props?.s3Key).toBeUndefined();
  });

  it('id-only file onBack returns to the files LIST (never closes the panel)', () => {
    // The reported project bug: onBack used to removeTab (closing everything). It MUST
    // re-open the same Files tab as the list - unfocused here, since there is no path.
    const sp = makeSidePanel();
    openFilesPanel(sp, { id: 'row-uuid-2', name: 'note.txt' });

    const onBack = sp.openTab.mock.calls[0][0].content?.props?.onBack as (() => void) | undefined;
    expect(typeof onBack).toBe('function');

    onBack!();
    expect(sp.openTab).toHaveBeenCalledTimes(2);
    const listTab = sp.openTab.mock.calls[1][0];
    expect(listTab.id).toBe(FILES_TAB_ID);
    expect(listTab.label).toBe('Files');
    // No s3 path, but the id is forwarded so the list still highlights the file you came from.
    expect(listTab.content?.props?.focusS3Key).toBeUndefined();
    expect(listTab.content?.props?.focusEntryId).toBe('row-uuid-2');
    // Folders stay visible on back - the list is NOT forced flat.
    expect(listTab.content?.props?.flat).toBeFalsy();
  });

  it('falls back to the list when the file has neither path nor id', () => {
    const sp = makeSidePanel();
    openFilesPanel(sp, { name: 'orphan' });

    expect(sp.openTab).toHaveBeenCalledTimes(1);
    expect(sp.openTab.mock.calls[0][0].label).toBe('Files');
  });

  it('threads createdAt into the detail view for the metadata grid', () => {
    const sp = makeSidePanel();
    openFilesPanel(sp, { id: 'row-uuid-3', name: 'x.png', createdAt: '2026-06-18T00:00:00Z' });

    expect(sp.openTab.mock.calls[0][0].content?.props?.createdAt).toBe('2026-06-18T00:00:00Z');
  });
});
