/**
 * Pure helpers that centralise how EVERY files surface (the full-page Files browser,
 * the right side-panel storage explorer, the project Files tab) lays out a storage
 * listing: folders first (sorted by last activity, newest first), then the files
 * grouped into per-day buckets (newest day first). Extracted here so the folder
 * ordering and the date grouping never drift between surfaces.
 *
 * <p>The backend already returns folders ahead of files and orders folders by their
 * last activity (the {@code MAX(child.created_at)} stamped into {@code createdAt} -
 * see {@code StorageExplorerService}), so these helpers are mostly a stable,
 * defensive re-affirmation of that contract on the client - and the single place the
 * day-grouping lives.</p>
 */
import type { StorageExplorerEntry } from '@/lib/api/storage-api';
import { formatUtcDate } from '@/lib/utils/dateFormatters';

/**
 * A single day's bucket. Both folders AND files are bucketed into the day of their
 * {@code createdAt} (a folder's createdAt = its last activity = MAX child date), so a
 * folder appears in the same day section as the files added that day, folders first.
 */
export interface FileDayGroup {
  /** ISO instant of the day's 00:00 (UTC) - the stable React key + collapse key. */
  dateFrom: string;
  /** ISO instant of the next day's 00:00 (UTC) - the half-open upper bound. */
  dateTo: string;
  /** Localised day label (e.g. "Jun 17, 2026"). */
  label: string;
  /** Folders whose last activity falls on this day, newest activity first (rendered above the files). */
  folders: StorageExplorerEntry[];
  /** The files that fall on this day, kept in their incoming (newest-first) order. */
  entries: StorageExplorerEntry[];
}

/** Parse an entry's createdAt to epoch millis; a missing/invalid date sorts last. */
function createdAtMillis(e: StorageExplorerEntry): number {
  const t = e.createdAt ? new Date(e.createdAt).getTime() : NaN;
  return Number.isNaN(t) ? -Infinity : t;
}

/**
 * Split a mixed listing into folders and files. When {@code enableFolders} is false
 * (a flat listing - e.g. a form picker, or the project tab which has no folders)
 * every entry is treated as a file, so {@code folders} is empty. {@code isFolder} is
 * absent on legacy flat rows, so the file partition is byte-identical there.
 */
export function splitFoldersAndFiles(
  entries: StorageExplorerEntry[],
  enableFolders: boolean,
): { folders: StorageExplorerEntry[]; files: StorageExplorerEntry[] } {
  if (!enableFolders) return { folders: [], files: entries };
  const folders: StorageExplorerEntry[] = [];
  const files: StorageExplorerEntry[] = [];
  for (const e of entries) (e.isFolder ? folders : files).push(e);
  return { folders, files };
}

/**
 * Folders sorted by last activity, newest first (the date the last element was added,
 * which the backend stamps into {@code createdAt}). A STABLE sort, so the backend's
 * tie-break order is preserved on equal dates. Returns a new array - never mutates the
 * input. Defensive: the backend already orders this way, so this only guarantees the
 * invariant holds uniformly across surfaces even if one fetched the rows differently.
 */
export function sortFoldersByActivity(folders: StorageExplorerEntry[]): StorageExplorerEntry[] {
  return [...folders].sort((a, b) => createdAtMillis(b) - createdAtMillis(a));
}

/** UTC midnight of an entry's createdAt day (epoch for missing/invalid) - host-timezone-stable. */
function utcDayStart(entry: StorageExplorerEntry): Date {
  const parsed = entry.createdAt ? new Date(entry.createdAt) : new Date(0);
  const date = Number.isNaN(parsed.getTime()) ? new Date(0) : parsed;
  return new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate()));
}

/**
 * Group folders AND files into per-day buckets, newest day first. A folder is bucketed
 * by its last activity (the {@code MAX(child.created_at)} the backend stamps into its
 * {@code createdAt}), so "the folder whose last file was added Jun 18" lands in the Jun 18
 * section - rendered ABOVE that day's files (folders newest-activity first; files keep their
 * incoming newest-first order). Days are computed in **UTC** so the bucket always matches the
 * UTC day {@link formatUtcDate} renders on the header - a 23:00 UTC entry and a 01:00 UTC one
 * the next day are two days. Missing/invalid createdAt → bucketed under the epoch (sorts last).
 * This is the one place the day grouping lives - every surface renders the same buckets.
 */
export function groupEntriesByDay(
  folders: StorageExplorerEntry[],
  files: StorageExplorerEntry[],
): FileDayGroup[] {
  const groups = new Map<string, { dateFrom: Date; dateTo: Date; folders: StorageExplorerEntry[]; entries: StorageExplorerEntry[] }>();
  const bucketFor = (entry: StorageExplorerEntry) => {
    const dayStart = utcDayStart(entry);
    const key = dayStart.toISOString();
    let g = groups.get(key);
    if (!g) {
      const dayEnd = new Date(dayStart);
      dayEnd.setUTCDate(dayEnd.getUTCDate() + 1);
      g = { dateFrom: dayStart, dateTo: dayEnd, folders: [], entries: [] };
      groups.set(key, g);
    }
    return g;
  };
  for (const folder of folders) bucketFor(folder).folders.push(folder);
  for (const file of files) bucketFor(file).entries.push(file);

  // Newest day first; within a day, folders newest-activity first, files keep incoming order.
  return Array.from(groups.values())
    .sort((a, b) => b.dateFrom.getTime() - a.dateFrom.getTime())
    .map((g) => ({
      label: formatUtcDate(g.dateFrom),
      dateFrom: g.dateFrom.toISOString(),
      dateTo: g.dateTo.toISOString(),
      folders: sortFoldersByActivity(g.folders),
      entries: g.entries,
    }));
}

/**
 * Files-only day grouping (no folders) - a thin wrapper over {@link groupEntriesByDay} for
 * flat surfaces (the project Files tab, the form-field picker) that never show folders.
 */
export function groupFilesByDay(files: StorageExplorerEntry[]): FileDayGroup[] {
  return groupEntriesByDay([], files);
}
