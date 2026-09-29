// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi, beforeEach } from 'vitest';
import type { StorageExplorerEntry } from '@/lib/api/storage-api';

// Stable, locale-independent day labels (UTC getters - the real formatUtcDate is UTC).
// The day-bucket LABELS come from the real formatter: this suite asserts the body layout, and a
// mock of the whole dateFormatters module is what hid a day-shift in the grouping it renders
// (see lib/files/__tests__/filesGrouping.test.ts). Only the label shape is stubbed, by pinning the
// display zone, so the assertions below stay stable without being blind.
import { applyDisplayTimeZone, clearDisplayTimeZone } from '@/lib/utils/timezone';

vi.mock('@/lib/utils/dateFormatters', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/utils/dateFormatters')>()),
}));

// Stub the leaf tiles/rows so the test asserts the BODY's layout contract (folder/file
// split, ordering, day grouping, variant routing), not the children's internals.
vi.mock('../FileCard', () => ({
  FileCard: ({ entry, onOpen, onToggleSelect, onDownload, selected, draggable }: any) => (
    <button
      data-testid={`grid-file-${entry.id}`}
      data-selected={String(selected)}
      data-draggable={String(draggable)}
      data-has-select={String(!!onToggleSelect)}
      data-has-download={String(!!onDownload)}
      onClick={() => onOpen(entry)}
    >
      file:{entry.fileName}
      <span data-testid={`grid-file-sel-${entry.id}`} onClick={(e) => { e.stopPropagation(); onToggleSelect?.(entry.id); }} />
    </button>
  ),
}));
vi.mock('../FolderCard', () => ({
  FolderCard: ({ entry, label, onOpen }: any) => (
    <button data-testid={`grid-folder-${entry.id}`} onClick={() => onOpen(entry)}>folder:{label}</button>
  ),
  VirtualFolderCard: ({ entry, label, onOpen }: any) => (
    <button data-testid={`grid-vfolder-${entry.virtualId}`} onClick={() => onOpen(entry)}>vfolder:{label}</button>
  ),
}));
vi.mock('../StorageRows', () => ({
  StorageEntryRow: ({ entry, onNavigate, onSelect, highlight, rowRef }: any) => (
    <button
      ref={rowRef}
      data-testid={`row-file-${entry.id}`}
      data-mode={onSelect ? 'select' : onNavigate ? 'navigate' : 'none'}
      data-highlight={String(!!highlight)}
      onClick={() => (onSelect ?? onNavigate)?.(entry)}
    >
      row:{entry.fileName}
    </button>
  ),
  StorageFolderRow: ({ entry, label, onOpen }: any) => (
    <button data-testid={`row-folder-${entry.id}`} onClick={() => onOpen(entry)}>rowfolder:{label}</button>
  ),
}));

import { FilesExplorerBody } from '../FilesExplorerBody';

const tFiles = ((key: string, v?: Record<string, unknown>) => (v ? `${key}:${JSON.stringify(v)}` : key)) as any;

function file(id: string, createdAt: string, fileName = id): StorageExplorerEntry {
  return base(id, createdAt, fileName, false);
}
function folder(id: string, createdAt: string, fileName = id): StorageExplorerEntry {
  return base(id, createdAt, fileName, true);
}
function base(id: string, createdAt: string, fileName: string, isFolder: boolean): StorageExplorerEntry {
  return {
    id, storageType: 'S3_FILE', sourceType: 'S3_FILE', fileName, mimeType: null, sizeBytes: null,
    formattedSize: '0 B', createdAt, workflowId: null, workflowName: null, projectId: null, runId: null,
    stepKey: null, epoch: null, s3Key: `key/${id}`, contentType: null, isFolder, childCount: isFolder ? 2 : null,
  } as StorageExplorerEntry;
}
/** A Phase 2b VIRTUAL workflow folder: id null, navigates by virtualId. */
function vfolder(virtualId: string, createdAt: string): StorageExplorerEntry {
  return { ...base('vid', createdAt, 'wf', true), id: null, virtualId, virtualKind: 'WORKFLOW' } as unknown as StorageExplorerEntry;
}

const noop = () => {};
const baseProps = {
  tFiles,
  onOpenFolder: noop,
  onOpenFile: noop,
  selectable: true,
  selectedIds: new Set<string>(),
  onToggleSelect: noop,
};

afterEach(() => cleanup());

beforeEach(() => {
  clearDisplayTimeZone();
  // UTC, so the day labels below are the UTC days the fixtures name.
  applyDisplayTimeZone('UTC');
});
afterEach(() => clearDisplayTimeZone());

/** A day-section header as the real formatter renders it for a UTC reader: "Jun 17, 2026". */
const DAY_HEADER = /^[A-Z][a-z]{2} \d{2}, \d{4}$/;

describe('FilesExplorerBody - shared layout contract', () => {
  it('grid: each folder sits in the day section of its last activity, ABOVE that day\'s files', () => {
    const entries = [
      folder('fOld', '2026-01-01T00:00:00Z'),   // last activity Jan 1 → its own day
      folder('fNew', '2026-06-17T00:00:00Z'),   // last activity Jun 17 → same day as `today`
      file('today', '2026-06-17T12:00:00Z'),
      file('older', '2026-06-10T12:00:00Z'),
    ];
    render(<FilesExplorerBody variant="grid" entries={entries} enableFolders {...baseProps} />);

    // THREE day groups now (the Jan-1 folder makes its own section), newest day first.
    const headers = screen.getAllByText(DAY_HEADER).map((el) => el.textContent);
    expect(headers).toEqual(['Jun 17, 2026', 'Jun 10, 2026', 'Jan 01, 2026']);

    // Both folders render, each under its own day, newest-activity day first.
    const folders = screen.getAllByTestId(/^grid-folder-/).map((el) => el.getAttribute('data-testid'));
    expect(folders).toEqual(['grid-folder-fNew', 'grid-folder-fOld']);

    // Within the Jun-17 day, the folder tile precedes the file tile in DOM order.
    const tiles = screen.getAllByTestId(/^grid-(folder|file)-/).map((el) => el.getAttribute('data-testid'));
    expect(tiles.indexOf('grid-folder-fNew')).toBeLessThan(tiles.indexOf('grid-file-today'));

    expect(screen.getByTestId('grid-file-today')).toBeInTheDocument();
    expect(screen.getByTestId('grid-file-older')).toBeInTheDocument();
  });

  it('grid: a day section collapses when its header is clicked (its files hide)', () => {
    const entries = [file('a', '2026-06-17T12:00:00Z'), file('b', '2026-06-10T12:00:00Z')];
    render(<FilesExplorerBody variant="grid" entries={entries} enableFolders={false} {...baseProps} />);

    expect(screen.getByTestId('grid-file-a')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Jun 17, 2026'));
    // The collapsed day's file is gone; the other day still shows.
    expect(screen.queryByTestId('grid-file-a')).not.toBeInTheDocument();
    expect(screen.getByTestId('grid-file-b')).toBeInTheDocument();
  });

  it('grid: forwards gridDraggable to the file tiles', () => {
    render(<FilesExplorerBody variant="grid" entries={[file('a', '2026-06-17T12:00:00Z')]} enableFolders={false} gridDraggable {...baseProps} />);
    expect(screen.getByTestId('grid-file-a')).toHaveAttribute('data-draggable', 'true');
  });

  it('compact: renders folder + file ROWS (not grid tiles) and groups files by day', () => {
    const entries = [folder('fA', '2026-06-17T00:00:00Z'), file('x', '2026-06-17T12:00:00Z')];
    render(<FilesExplorerBody variant="compact" entries={entries} enableFolders {...baseProps} />);

    expect(screen.getByTestId('row-folder-fA')).toBeInTheDocument();
    expect(screen.getByTestId('row-file-x')).toBeInTheDocument();
    expect(screen.queryByTestId('grid-file-x')).not.toBeInTheDocument(); // never the grid tile in compact
    expect(screen.getByText('Jun 17, 2026')).toBeInTheDocument();
  });

  it('compact picker: routes file clicks to onSelectFile (not onOpenFile)', () => {
    const onSelectFile = vi.fn();
    const onOpenFile = vi.fn();
    render(
      <FilesExplorerBody
        variant="compact"
        entries={[file('x', '2026-06-17T12:00:00Z')]}
        enableFolders={false}
        {...baseProps}
        onOpenFile={onOpenFile}
        onSelectFile={onSelectFile}
      />,
    );
    fireEvent.click(screen.getByTestId('row-file-x'));
    expect(onSelectFile).toHaveBeenCalledTimes(1);
    expect(onOpenFile).not.toHaveBeenCalled();
  });

  it('wires onOpenFolder + onOpenFile from a click', () => {
    const onOpenFolder = vi.fn();
    const onOpenFile = vi.fn();
    const entries = [folder('fA', '2026-06-17T00:00:00Z'), file('x', '2026-06-17T12:00:00Z')];
    render(<FilesExplorerBody variant="grid" entries={entries} enableFolders {...baseProps} onOpenFolder={onOpenFolder} onOpenFile={onOpenFile} />);

    fireEvent.click(screen.getByTestId('grid-folder-fA'));
    expect(onOpenFolder).toHaveBeenCalledWith(expect.objectContaining({ id: 'fA' }));
    fireEvent.click(screen.getByTestId('grid-file-x'));
    expect(onOpenFile).toHaveBeenCalledWith(expect.objectContaining({ id: 'x' }));
  });

  it('enableFolders=false: folder-flagged rows are NOT split out (flat listing shows no folder section)', () => {
    const entries = [folder('fA', '2026-06-17T00:00:00Z'), file('x', '2026-06-17T12:00:00Z')];
    render(<FilesExplorerBody variant="grid" entries={entries} enableFolders={false} {...baseProps} />);
    expect(screen.queryByTestId('grid-folder-fA')).not.toBeInTheDocument();
  });

  it('grid: a virtual workflow folder renders as the navigation-only VirtualFolderCard (not a manual FolderCard)', () => {
    render(<FilesExplorerBody variant="grid" entries={[vfolder('wf:1', '2026-06-17T00:00:00Z')]} enableFolders {...baseProps} />);
    expect(screen.getByTestId('grid-vfolder-wf:1')).toBeInTheDocument();
    expect(screen.queryByTestId('grid-folder-null')).not.toBeInTheDocument();
  });

  it('compact explorer: file rows are in NAVIGATE mode - clicking calls onOpenFile, never select', () => {
    const onOpenFile = vi.fn();
    render(<FilesExplorerBody variant="compact" entries={[file('x', '2026-06-17T12:00:00Z')]} enableFolders={false} {...baseProps} onOpenFile={onOpenFile} />);
    const row = screen.getByTestId('row-file-x');
    expect(row).toHaveAttribute('data-mode', 'navigate'); // distinct from the picker's 'select'
    fireEvent.click(row);
    expect(onOpenFile).toHaveBeenCalledWith(expect.objectContaining({ id: 'x' }));
  });

  it('compact: focusS3Key highlights ONLY the matching row (deep-link focus)', () => {
    const entries = [file('a', '2026-06-17T12:00:00Z'), file('b', '2026-06-17T09:00:00Z')];
    // file() sets s3Key = `key/${id}` → the focus key matches row a's s3Key.
    render(<FilesExplorerBody variant="compact" entries={entries} enableFolders={false} {...baseProps} focusS3Key="key/a" />);
    expect(screen.getByTestId('row-file-a')).toHaveAttribute('data-highlight', 'true');
    expect(screen.getByTestId('row-file-b')).toHaveAttribute('data-highlight', 'false');
  });

  it('compact: focusEntryId highlights the matching row BY ID (back-from-detail; works with no s3Key)', () => {
    const entries = [file('a', '2026-06-17T12:00:00Z'), file('b', '2026-06-17T09:00:00Z')];
    render(<FilesExplorerBody variant="compact" entries={entries} enableFolders={false} {...baseProps} focusEntryId="a" />);
    expect(screen.getByTestId('row-file-a')).toHaveAttribute('data-highlight', 'true');
    expect(screen.getByTestId('row-file-b')).toHaveAttribute('data-highlight', 'false');
  });

  it('renders a per-day "select all" checkbox when onToggleDaySelection is given, firing it with that day\'s files', () => {
    const onToggleDaySelection = vi.fn();
    const entries = [file('a', '2026-06-17T12:00:00Z'), file('b', '2026-06-17T09:00:00Z')];
    const { container } = render(
      <FilesExplorerBody variant="compact" entries={entries} enableFolders={false} {...baseProps} onToggleDaySelection={onToggleDaySelection} />,
    );
    // The only checkbox is the day header's (the row stubs render none).
    const dayCheckbox = container.querySelector('input[type="checkbox"]') as HTMLInputElement;
    expect(dayCheckbox).toBeTruthy();
    fireEvent.click(dayCheckbox);
    expect(onToggleDaySelection).toHaveBeenCalledTimes(1);
    expect(onToggleDaySelection.mock.calls[0][0].map((e: StorageExplorerEntry) => e.id)).toEqual(['a', 'b']);
  });

  it('omits the per-day checkbox when onToggleDaySelection is not provided', () => {
    const { container } = render(
      <FilesExplorerBody variant="compact" entries={[file('a', '2026-06-17T12:00:00Z')]} enableFolders={false} {...baseProps} />,
    );
    expect(container.querySelector('input[type="checkbox"]')).toBeNull();
  });

  it('the per-day checkbox reflects the selection: CHECKED when all of the day is selected, INDETERMINATE when only some', () => {
    const entries = [file('a', '2026-06-17T12:00:00Z'), file('b', '2026-06-17T09:00:00Z')];
    // All of the day's files selected → checked, not indeterminate.
    const allSel = render(
      <FilesExplorerBody variant="compact" entries={entries} enableFolders={false} {...baseProps} selectedIds={new Set(['a', 'b'])} onToggleDaySelection={noop} />,
    );
    let cb = allSel.container.querySelector('input[type="checkbox"]') as HTMLInputElement;
    expect(cb.checked).toBe(true);
    expect(cb.indeterminate).toBe(false);
    cleanup();
    // Some-but-not-all selected → indeterminate, not checked.
    const someSel = render(
      <FilesExplorerBody variant="compact" entries={entries} enableFolders={false} {...baseProps} selectedIds={new Set(['a'])} onToggleDaySelection={noop} />,
    );
    cb = someSel.container.querySelector('input[type="checkbox"]') as HTMLInputElement;
    expect(cb.checked).toBe(false);
    expect(cb.indeterminate).toBe(true);
  });

  it('compact: focusS3Key matches a row by SUFFIX too (deep-link key carries a longer path)', () => {
    // entry s3Key = "key/x"; the deep-link key is a longer path ending in it.
    render(<FilesExplorerBody variant="compact" entries={[file('x', '2026-06-17T12:00:00Z')]} enableFolders={false} {...baseProps} focusS3Key="tenant/abc/key/x" />);
    expect(screen.getByTestId('row-file-x')).toHaveAttribute('data-highlight', 'true');
  });
});

describe('FilesExplorerBody - groupByDay', () => {
  // The day sections ARE a date ordering. When the caller sorts by name/size/type, keeping
  // them would chop the chosen order into date buckets and honour neither, so the body
  // renders one flat block in exactly the order it was handed.
  const mixedDays = [
    folder('fA', '2026-01-01T00:00:00Z', 'Alpha'),
    file('zzz', '2026-06-17T12:00:00Z'),
    file('aaa', '2026-06-10T12:00:00Z'),
  ];

  it('groups by day by DEFAULT, so every surface that omits the prop is unchanged', () => {
    render(<FilesExplorerBody variant="grid" entries={mixedDays} enableFolders {...baseProps} />);
    expect(screen.getAllByText(DAY_HEADER).length).toBeGreaterThan(1);
  });

  it('renders ONE flat block with no day headers when grouping is off', () => {
    render(<FilesExplorerBody variant="grid" entries={mixedDays} enableFolders groupByDay={false} {...baseProps} />);
    expect(screen.queryByText(DAY_HEADER)).toBeNull();
  });

  it('preserves the server order exactly - the chosen sort is not re-sorted client-side', () => {
    // 'zzz' precedes 'aaa' although it is the NEWER file: under a name/size/type sort the
    // server's order is the answer, and the body must not re-impose a date order on it.
    render(<FilesExplorerBody variant="grid" entries={mixedDays} enableFolders groupByDay={false} {...baseProps} />);
    // The tiles themselves, not the selection handles nested inside them.
    const tiles = screen.getAllByTestId(/^grid-(folder|file)-(?!sel-)/).map((el) => el.getAttribute('data-testid'));
    expect(tiles).toEqual(['grid-folder-fA', 'grid-file-zzz', 'grid-file-aaa']);
  });

  it('still renders every entry, folders included, in the flat block', () => {
    render(<FilesExplorerBody variant="grid" entries={mixedDays} enableFolders groupByDay={false} {...baseProps} />);
    expect(screen.getByTestId('grid-folder-fA')).toBeInTheDocument();
    expect(screen.getByTestId('grid-file-zzz')).toBeInTheDocument();
    expect(screen.getByTestId('grid-file-aaa')).toBeInTheDocument();
  });

  it('applies to the compact variant too (the side panel keeps its row layout)', () => {
    render(<FilesExplorerBody variant="compact" entries={mixedDays} enableFolders groupByDay={false} {...baseProps} />);
    expect(screen.queryByText(DAY_HEADER)).toBeNull();
    expect(screen.getByTestId('row-folder-fA')).toBeInTheDocument();
    expect(screen.getByTestId('row-file-zzz')).toBeInTheDocument();
  });
});

describe('grid tiles in picker mode', () => {
  const pickerEntries = [file('zzz', '2026-03-19T10:00:00Z', 'photo.png')];

  it('a tile click SELECTS when the caller is a picker, instead of doing nothing', () => {
    // A picker passes no onOpenFile - it has no detail view to open. The grid used to wire the
    // tile click to onOpenFile alone, so a grid picker looked interactive and swallowed clicks.
    const onSelectFile = vi.fn();
    render(
      <FilesExplorerBody
        variant="grid"
        entries={pickerEntries}
        enableFolders
        {...baseProps}
        onOpenFile={undefined}
        onSelectFile={onSelectFile}
      />,
    );

    fireEvent.click(screen.getByTestId('grid-file-zzz'));

    expect(onSelectFile).toHaveBeenCalledWith(expect.objectContaining({ id: 'zzz' }));
  });

  it('still opens the detail view when the caller is the explorer', () => {
    const onOpenFile = vi.fn();
    render(
      <FilesExplorerBody variant="grid" entries={pickerEntries} enableFolders {...baseProps} onOpenFile={onOpenFile} />,
    );

    fireEvent.click(screen.getByTestId('grid-file-zzz'));

    expect(onOpenFile).toHaveBeenCalledWith(expect.objectContaining({ id: 'zzz' }));
  });
});

describe('the picker grid draws no control it cannot honour', () => {
  const entries = [file('zzz', '2026-03-19T10:00:00Z', 'photo.png')];

  it('passes no select handler when the caller offers no selection', () => {
    // The picker has no selection bar, so a checkbox there could be ticked and acted on by
    // nothing. Asserted at the prop boundary because the tile mock is what the suite renders.
    const seen: Record<string, unknown> = {};
    render(
      <FilesExplorerBody
        variant="grid"
        entries={entries}
        enableFolders
        {...baseProps}
        selectable={false}
        onOpenFile={undefined}
        onSelectFile={(e) => { seen.selected = e; }}
      />,
    );

    expect(screen.getByTestId('grid-file-zzz')).toBeInTheDocument();
    expect(screen.getByTestId('grid-file-zzz').getAttribute('data-has-select')).toBe('false');
  });

  it('passes no download handler when the caller cannot download', () => {
    render(
      <FilesExplorerBody
        variant="grid"
        entries={entries}
        enableFolders
        {...baseProps}
        selectable={false}
        onOpenFile={undefined}
        onSelectFile={() => {}}
      />,
    );

    expect(screen.getByTestId('grid-file-zzz').getAttribute('data-has-download')).toBe('false');
  });

  it('still passes both to the explorer, which can act on them', () => {
    render(
      <FilesExplorerBody
        variant="grid"
        entries={entries}
        enableFolders
        {...baseProps}
        onDownloadFile={() => {}}
        downloadLabel="dl"
      />,
    );

    expect(screen.getByTestId('grid-file-zzz').getAttribute('data-has-select')).toBe('true');
    expect(screen.getByTestId('grid-file-zzz').getAttribute('data-has-download')).toBe('true');
  });
});
