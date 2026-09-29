/**
 * @vitest-environment jsdom
 *
 * Day bucketing, on the READER's calendar.
 *
 * <p>The REAL formatter, deliberately. This file used to mock the whole `dateFormatters` module
 * with a UTC-getter stub, which made the labels "host-timezone-stable" by making them unable to
 * observe the zone at all - so when the grouping started handing a `Date` to a zone-aware
 * formatter and every header slid a day west of Greenwich, nothing here could see it. A mock that
 * cannot reproduce the bug is worse than no test.
 *
 * <p>Stability comes from pinning the DISPLAY zone instead, which is also what lets the
 * interesting case be written at all: the same instant belongs to different days for readers in
 * different places, and that is the behaviour under test.
 *
 * <p>jsdom because the display zone lives behind a `window` check; under the suite default
 * (`node`) it is always UTC and half of what follows would be unreachable.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import type { StorageExplorerEntry } from '@/lib/api/storage-api';
import { splitFoldersAndFiles, sortFoldersByActivity, groupFilesByDay, groupEntriesByDay } from '../filesGrouping';
import { applyDisplayTimeZone, clearDisplayTimeZone } from '@/lib/utils/timezone';

/** Every bucket label is produced for a reader on UTC unless a test says otherwise. */
beforeEach(() => {
  clearDisplayTimeZone();
  applyDisplayTimeZone('UTC');
});
afterEach(() => clearDisplayTimeZone());

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

  it('buckets files into per-day groups, newest day first, preserving in-day order', () => {
    // "(UTC)" used to be in this name and in the two below. Bucketing follows the READER now, and
    // these assertions only read as UTC because `beforeEach` pins the display zone to it. A name
    // that states the old behaviour is worse than none: the next person changes the reader-zone
    // rule, sees three green tests titled UTC, and concludes the rule was never in force.
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
    // With the display zone pinned to UTC, the day boundary IS UTC midnight: the half-open
    // [dateFrom, dateTo) spans one day and the label names it. The zone-sensitive cases live in the
    // suite below, which pins a zone that is not UTC.
    expect(groups[0].dateFrom).toBe('2026-06-17T00:00:00.000Z');
    expect(groups[0].dateTo).toBe('2026-06-18T00:00:00.000Z');
    expect(groups[0].label).toBe('Jun 17, 2026');
  });

  it('splits files that straddle the day boundary into TWO days, never on the HOST zone', () => {
    const files = [
      entry({ id: 'lateNight', createdAt: '2026-06-17T23:30:00Z' }),
      entry({ id: 'earlyNext', createdAt: '2026-06-18T00:30:00Z' }),
    ];
    const groups = groupFilesByDay(files);
    // Two distinct days, newest first, and the boundary is the one the DISPLAY zone draws (UTC
    // here) - never the one the machine running the test happens to be in.
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
    expect(groups.map((g) => g.label)).toEqual(['Jun 18, 2026', 'Jun 17, 2026']);
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
    expect(groups.map((g) => g.label)).toEqual(['Jun 18, 2026', 'Jun 10, 2026']);
    expect(groups[1].folders.map((e) => e.id)).toEqual(['archive']);
    expect(groups[1].entries).toEqual([]);
  });
});

describe('bucketing follows the reader, not UTC', () => {
  it('puts an entry in the day the READER is in, not the UTC day', () => {
    // 01:00 UTC on the 18th is 17:00 on the 17th in Los Angeles. The row inside the bucket renders
    // "Jun 17" (formatUtcDate on a timestamp uses the display zone), so a UTC bucket put it under a
    // header saying "Jun 18" - one screen, two days, for the same file.
    applyDisplayTimeZone('America/Los_Angeles');

    const groups = groupEntriesByDay([], [entry({ id: 'f', createdAt: '2026-06-18T01:00:00Z' })]);

    expect(groups).toHaveLength(1);
    expect(groups[0].label).toBe('Jun 17, 2026');
  });

  it('splits one UTC day into two when the reader is east of it', () => {
    // 22:00 and 23:00 UTC on the 17th are the 18th in Tokyo; 10:00 is still the 17th.
    applyDisplayTimeZone('Asia/Tokyo');

    const groups = groupEntriesByDay([], [
      entry({ id: 'late', createdAt: '2026-06-17T22:00:00Z' }),
      entry({ id: 'early', createdAt: '2026-06-17T10:00:00Z' }),
    ]);

    expect(groups.map((g) => g.label)).toEqual(['Jun 18, 2026', 'Jun 17, 2026']);
    expect(groups[0].entries.map((e) => e.id)).toEqual(['late']);
    expect(groups[1].entries.map((e) => e.id)).toEqual(['early']);
  });

  it('carries the instants that bound that day IN THAT ZONE, for the server-side filter', () => {
    // dateFrom/dateTo are handed to the same query the date pickers drive, so they have to be the
    // reader's midnight too - otherwise "the files under this header" and "the files this filter
    // returns" are different sets.
    applyDisplayTimeZone('America/Los_Angeles');

    const groups = groupEntriesByDay([], [entry({ id: 'f', createdAt: '2026-06-18T01:00:00Z' })]);

    expect(groups[0].dateFrom).toBe('2026-06-17T07:00:00.000Z');
    expect(groups[0].dateTo).toBe('2026-06-18T07:00:00.000Z');
  });

  it('labels the bucket with the READER\'s day and no zone suffix, because a day has no zone', () => {
    // 2026-06-18T01:00Z is the 18th in Tokyo and the 17th in UTC, so the label is the assertion
    // that the bucket followed the reader. The previous version pinned only the ABSENCE of a
    // UTC/GMT suffix, which `formatCalendarDate` never emits under any zone - it cannot fail
    // without a different helper being substituted, and it said nothing about the day.
    applyDisplayTimeZone('Asia/Tokyo');

    const groups = groupEntriesByDay([], [entry({ id: 'f', createdAt: '2026-06-18T01:00:00Z' })]);

    expect(groups[0].label).toContain('18');
    expect(groups[0].label).not.toContain('17');
    expect(groups[0].label).not.toMatch(/UTC|GMT/);
  });
});
