import { describe, it, expect, vi } from 'vitest';
import type { StorageExplorerEntry } from '@/lib/api/storage-api';
import { splitFoldersAndFiles, sortFoldersByActivity, groupFilesByDay, groupEntriesByDay } from '../filesGrouping';

// formatUtcDate renders in UTC; stub it with UTC getters so the day-group label
// assertions are host-timezone-stable AND faithful to the real UTC behaviour.
vi.mock('@/lib/utils/dateFormatters', () => ({
  formatUtcDate: (d: Date) => `D:${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}-${String(d.getUTCDate()).padStart(2, '0')}`,
}));

function entry(over: Partial<StorageExplorerEntry>): StorageExplorerEntry {
  return {
    id: over.id ?? 'x',
    storageType: 'S3_FILE',
    sourceType: 'S3_FILE',
    fileName: over.fileName ?? 'f',
    mimeType: null,
    sizeBytes: null,
    formattedSize: '0 B',
    createdAt: over.createdAt ?? '2026-06-17T10:00:00Z',
    workflowId: null,
    workflowName: null,
    projectId: null,
    runId: null,
    stepKey: null,
    epoch: null,
    s3Key: null,
    contentType: null,
    isFolder: over.isFolder ?? false,
    ...over,
  } as StorageExplorerEntry;
}

describe('splitFoldersAndFiles', () => {
  it('splits folder rows out and keeps files when enableFolders=true', () => {
    const rows = [
      entry({ id: 'folderA', isFolder: true }),
      entry({ id: 'fileA', isFolder: false }),
      entry({ id: 'folderB', isFolder: true }),
    ];
    const { folders, files } = splitFoldersAndFiles(rows, true);
    expect(folders.map((e) => e.id)).toEqual(['folderA', 'folderB']);
    expect(files.map((e) => e.id)).toEqual(['fileA']);
  });

  it('treats every entry as a file when enableFolders=false (flat listing - no folders)', () => {
    const rows = [entry({ id: 'a', isFolder: true }), entry({ id: 'b', isFolder: false })];
    const { folders, files } = splitFoldersAndFiles(rows, false);
    expect(folders).toEqual([]);
    expect(files.map((e) => e.id)).toEqual(['a', 'b']);
  });
});

describe('sortFoldersByActivity', () => {
  it('orders folders by createdAt (last activity) descending, newest first', () => {
    const folders = [
      entry({ id: 'old', createdAt: '2026-01-01T00:00:00Z' }),
      entry({ id: 'new', createdAt: '2026-06-17T00:00:00Z' }),
      entry({ id: 'mid', createdAt: '2026-03-15T00:00:00Z' }),
    ];
    expect(sortFoldersByActivity(folders).map((e) => e.id)).toEqual(['new', 'mid', 'old']);
  });

  it('is a pure copy - does not mutate the input array', () => {
    const folders = [entry({ id: 'a', createdAt: '2026-01-01T00:00:00Z' }), entry({ id: 'b', createdAt: '2026-06-01T00:00:00Z' })];
    const before = folders.map((e) => e.id);
    sortFoldersByActivity(folders);
    expect(folders.map((e) => e.id)).toEqual(before);
  });

  it('sorts a folder with a missing/invalid date last', () => {
    const folders = [
      entry({ id: 'noDate', createdAt: undefined as unknown as string }),
      entry({ id: 'dated', createdAt: '2026-06-01T00:00:00Z' }),
    ];
    expect(sortFoldersByActivity(folders).map((e) => e.id)).toEqual(['dated', 'noDate']);
  });
});

describe('groupFilesByDay', () => {
  it('returns an empty array for no files', () => {
    expect(groupFilesByDay([])).toEqual([]);
  });

  it('buckets files into per-day groups (UTC), newest day first, preserving in-day order', () => {
    const files = [
      entry({ id: 'today1', createdAt: '2026-06-17T18:00:00Z' }),
      entry({ id: 'today2', createdAt: '2026-06-17T09:00:00Z' }),
      entry({ id: 'older', createdAt: '2026-06-10T12:00:00Z' }),
    ];
    const groups = groupFilesByDay(files);
    expect(groups).toHaveLength(2);
    // Newest day first.
    expect(groups[0].entries.map((e) => e.id)).toEqual(['today1', 'today2']);
    expect(groups[1].entries.map((e) => e.id)).toEqual(['older']);
    // The bucket key is the UTC midnight of the day; the half-open [dateFrom, dateTo)
    // spans exactly one UTC day; the label matches that UTC day.
    expect(groups[0].dateFrom).toBe('2026-06-17T00:00:00.000Z');
    expect(groups[0].dateTo).toBe('2026-06-18T00:00:00.000Z');
    expect(groups[0].label).toBe('D:2026-06-17');
  });

  it('splits files that straddle a UTC midnight into TWO days (UTC bucketing, not host-local)', () => {
    const files = [
      entry({ id: 'lateNight', createdAt: '2026-06-17T23:30:00Z' }),
      entry({ id: 'earlyNext', createdAt: '2026-06-18T00:30:00Z' }),
    ];
    const groups = groupFilesByDay(files);
    // Two distinct UTC days, newest first - regardless of the host machine's timezone.
    expect(groups.map((g) => g.dateFrom)).toEqual(['2026-06-18T00:00:00.000Z', '2026-06-17T00:00:00.000Z']);
    expect(groups[0].entries.map((e) => e.id)).toEqual(['earlyNext']);
    expect(groups[1].entries.map((e) => e.id)).toEqual(['lateNight']);
  });

  it('does not crash on a missing createdAt (buckets it under the UTC epoch)', () => {
    const files = [entry({ id: 'nodate', createdAt: undefined as unknown as string })];
    const groups = groupFilesByDay(files);
    expect(groups).toHaveLength(1);
    expect(groups[0].entries[0].id).toBe('nodate');
    expect(groups[0].dateFrom).toBe('1970-01-01T00:00:00.000Z');
  });

  it('emits empty folders[] (files-only wrapper)', () => {
    const groups = groupFilesByDay([entry({ id: 'f', createdAt: '2026-06-17T10:00:00Z' })]);
    expect(groups[0].folders).toEqual([]);
  });
});

describe('groupEntriesByDay', () => {
  it('buckets a folder into the day of its last activity (createdAt), above that day\'s files', () => {
    // The folder's last file was added Jun 18, so the folder lands in the Jun 18 section
    // - the exact "le dossier est dans 18 juin" behaviour.
    const folders = [entry({ id: 'reports', isFolder: true, createdAt: '2026-06-18T15:00:00Z' })];
    const files = [
      entry({ id: 'photo', createdAt: '2026-06-18T09:00:00Z' }),
      entry({ id: 'old', createdAt: '2026-06-17T09:00:00Z' }),
    ];
    const groups = groupEntriesByDay(folders, files);
    expect(groups.map((g) => g.label)).toEqual(['D:2026-06-18', 'D:2026-06-17']);
    // Jun 18 group: the folder is present (above) and the file is in entries.
    expect(groups[0].folders.map((e) => e.id)).toEqual(['reports']);
    expect(groups[0].entries.map((e) => e.id)).toEqual(['photo']);
    // Jun 17 group: no folder, just the older file.
    expect(groups[1].folders).toEqual([]);
    expect(groups[1].entries.map((e) => e.id)).toEqual(['old']);
  });

  it('orders folders within a day by last activity, newest first', () => {
    const folders = [
      entry({ id: 'b', isFolder: true, createdAt: '2026-06-18T08:00:00Z' }),
      entry({ id: 'a', isFolder: true, createdAt: '2026-06-18T20:00:00Z' }),
    ];
    const groups = groupEntriesByDay(folders, []);
    expect(groups[0].folders.map((e) => e.id)).toEqual(['a', 'b']);
  });

  it('keeps a folder-only day (a folder whose day has no loose files)', () => {
    const folders = [entry({ id: 'archive', isFolder: true, createdAt: '2026-06-10T00:00:00Z' })];
    const files = [entry({ id: 'recent', createdAt: '2026-06-18T00:00:00Z' })];
    const groups = groupEntriesByDay(folders, files);
    expect(groups.map((g) => g.label)).toEqual(['D:2026-06-18', 'D:2026-06-10']);
    expect(groups[1].folders.map((e) => e.id)).toEqual(['archive']);
    expect(groups[1].entries).toEqual([]);
  });
});
