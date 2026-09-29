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
import { calendarDayIn, dayEdgeInstant, formatCalendarDate, parseUtcAware } from '@/lib/utils/dateFormatters';
import { getClientTimeZone } from '@/lib/utils/timezone';

/**
 * A single day's bucket. Both folders AND files are bucketed into the day of their
 * {@code createdAt} (a folder's createdAt = its last activity = MAX child date), so a
 * folder appears in the same day section as the files added that day, folders first.
 */
export interface FileDayGroup {
  /** ISO instant of the day's first moment IN THE READER'S ZONE - the React key + collapse key. */
  dateFrom: string;
  /** ISO instant of the NEXT day's first moment, same zone - the half-open upper bound. */
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

/**
 * The day an entry belongs to, on the READER's calendar, as a bare `YYYY-MM-DD`.
 *
 * <p>Their calendar, not UTC, because that is the day they see on the row inside the bucket
 * (`formatUtcDate(entry.createdAt)` renders in their zone) and the day the date filter above the
 * list means (`dayEdgeInstant` bounds their midnight). Bucketing in UTC put all three on
 * different calendars: a file uploaded at 17:00 in Los Angeles is 01:00 the next UTC day, so it
 * sat under a header naming one day while its own row named the day before, and a filter for
 * that day did not return it.
 *
 * <p>An unreadable date answers the epoch day, which sorts last, as before.
 */
function displayDay(entry: StorageExplorerEntry): string {
  // parseUtcAware, not `new Date()`: it appends the Z a TZ-less payload lacks, which is the
  // whole reason it exists ("use everywhere the frontend does new Date(apiResponse)"). The rows
  // inside this bucket and the date filter above the list both parse that way, and a bucket that
  // parsed differently would put an entry under a header naming another day - the exact
  // disagreement this grouping was just changed to remove. It happens to be equivalent today
  // because the DTO carries a Java Instant, which is a fact one layer away that this code has no
  // way to defend.
  const parsed = entry.createdAt ? parseUtcAware(entry.createdAt) : new Date(0);
  const date = Number.isNaN(parsed.getTime()) ? new Date(0) : parsed;
  // The SHARED helper, not a local copy. The copy that used to live here built a fresh
  // Intl.DateTimeFormat per entry, which is the ~292 microseconds a call the formatter cache in
  // dateFormatters exists to remove - paid once per row of a file list.
  return calendarDayIn(date, getClientTimeZone());
}


/**
 * Group folders AND files into per-day buckets, newest day first. A folder is bucketed
 * by its last activity (the {@code MAX(child.created_at)} the backend stamps into its
 * {@code createdAt}), so "the folder whose last file was added Jun 18" lands in the Jun 18
 * section - rendered ABOVE that day's files (folders newest-activity first; files keep their
 * incoming newest-first order). Days are computed on the READER's calendar, so the header, the
 * date on each row inside it and the date filter above the list all name the same day - a 23:00
 * and a 01:00 entry are two days for the person looking, which is the only calendar that matters
 * here. Missing/invalid createdAt → bucketed under the epoch day (sorts last).
 * This is the one place the day grouping lives - every surface renders the same buckets.
 */
export function groupEntriesByDay(
  folders: StorageExplorerEntry[],
  files: StorageExplorerEntry[],
): FileDayGroup[] {
  const groups = new Map<string, { day: string; dateFrom: Date; dateTo: Date; folders: StorageExplorerEntry[]; entries: StorageExplorerEntry[] }>();
  const bucketFor = (entry: StorageExplorerEntry) => {
    const day = displayDay(entry);
    let g = groups.get(day);
    if (!g) {
      // The instants that bound that day IN THE READER'S ZONE: its first moment, and the first
      // moment of the next day.
      //
      // They are the stable identity of a group - a React key, and the key its collapsed state is
      // remembered under - which is why they are instants rather than the label. An earlier
      // comment said they were "handed to the same server-side filter the date pickers use". They
      // are handed to nobody: that filter builds its own bounds, and with the other convention
      // (an inclusive 23:59:59.999 rather than next midnight), so a reader who believed this
      // would have matched the two up wrongly.
      const from = dayEdgeInstant(day, 'start');
      const to = dayEdgeInstant(day, 'end');
      const dayStart = from ? new Date(from) : new Date(`${day}T00:00:00.000Z`);
      const dayEnd = to ? new Date(new Date(to).getTime() + 1) : new Date(dayStart.getTime() + 86400000);
      g = { day, dateFrom: dayStart, dateTo: dayEnd, folders: [], entries: [] };
      groups.set(day, g);
    }
    return g;
  };
  for (const folder of folders) bucketFor(folder).folders.push(folder);
  for (const file of files) bucketFor(file).entries.push(file);

  // Newest day first; within a day, folders newest-activity first, files keep incoming order.
  return Array.from(groups.values())
    .sort((a, b) => b.dateFrom.getTime() - a.dateFrom.getTime())
    .map((g) => ({
      // formatCalendarDate, not formatUtcDate: the bucket names a DAY, and it is already the
      // reader's day, so there is nothing left to translate and no zone worth labelling. Passing
      // the `Date` to a zone-aware formatter is what slid every header a day west of Greenwich,
      // and it was invisible because the grouping test mocks the formatter.
      label: formatCalendarDate(g.day),
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
