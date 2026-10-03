'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { usePathname, useSearchParams } from 'next/navigation';
import { showSamePageUrl } from '@/lib/navigation/showSamePageUrl';

/**
 * How one piece of view state is spelled in the address.
 *
 * <p>`parse` answers `undefined` for a value it does not recognise (a tab that no longer
 * exists, a hand-edited number), and the state then falls back to its default rather than
 * putting the page in a state it has no rendering for.
 */
export interface UrlStateCodec<T> {
  parse: (raw: string) => T | undefined;
  serialize: (value: T) => string;
}

export const urlString: UrlStateCodec<string> = {
  parse: (raw) => raw,
  serialize: (value) => value,
};

export const urlNumber: UrlStateCodec<number> = {
  parse: (raw) => {
    if (raw.trim() === '') return undefined;
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : undefined;
  },
  serialize: (value) => String(value),
};

/** A whole number within bounds (a page size, a period in days). */
export function urlInt(min: number, max: number): UrlStateCodec<number> {
  return {
    parse: (raw) => {
      if (!/^-?\d+$/.test(raw)) return undefined;
      const parsed = Number(raw);
      return parsed >= min && parsed <= max ? parsed : undefined;
    },
    serialize: (value) => String(value),
  };
}

/**
 * A ZERO-based page index, spelled ONE-based in the address: the pagination bar prints
 * "page 3" for index 2, and an address that says `page=2` under it would read as a bug.
 */
export const urlPageIndex: UrlStateCodec<number> = {
  parse: (raw) => {
    if (!/^\d+$/.test(raw)) return undefined;
    const parsed = Number(raw);
    return parsed >= 1 ? parsed - 1 : undefined;
  },
  serialize: (value) => String(value + 1),
};

export const urlBoolean: UrlStateCodec<boolean> = {
  parse: (raw) => (raw === '1' ? true : raw === '0' ? false : undefined),
  serialize: (value) => (value ? '1' : '0'),
};

/** One of a closed set of values (a tab, a sort field, a view mode). */
export function urlEnum<T extends string>(values: readonly T[]): UrlStateCodec<T> {
  return {
    parse: (raw) => (values.includes(raw as T) ? (raw as T) : undefined),
    serialize: (value) => value,
  };
}

/** A value that may be absent (a selected id, an optional filter). `null` is "not set". */
export function urlNullable<T>(codec: UrlStateCodec<T>): UrlStateCodec<T | null> {
  return {
    parse: (raw) => codec.parse(raw),
    // Never reached for null when null is the default (a default is spelled by absence).
    serialize: (value) => (value === null ? '' : codec.serialize(value)),
  };
}

/**
 * A list of plain values, comma separated. Only the comma (and the escape character itself)
 * is escaped inside a value: the address escapes everything else by itself, and escaping it
 * here too would print `%253A` for a colon.
 */
export function urlList<T extends string = string>(
  values?: readonly T[],
): UrlStateCodec<T[]> {
  return {
    parse: (raw) => {
      if (raw === '') return [];
      const items = raw
        .split(',')
        .map((item) => item.replace(/%2C/gi, ',').replace(/%25/g, '%')) as T[];
      return values ? items.filter((item) => values.includes(item)) : items;
    },
    serialize: (value) =>
      value.map((item) => item.replace(/%/g, '%25').replace(/,/g, '%2C')).join(','),
  };
}

/**
 * A structured value (a filter tree, a sort list) as JSON. `isValid` is the shape check:
 * the address is user input, so what comes out of it is never trusted to have the right shape.
 */
export function urlJson<T>(isValid?: (value: unknown) => boolean): UrlStateCodec<T> {
  return {
    parse: (raw) => {
      try {
        const parsed: unknown = JSON.parse(raw);
        return !isValid || isValid(parsed) ? (parsed as T) : undefined;
      } catch {
        return undefined;
      }
    },
    serialize: (value) => JSON.stringify(value),
  };
}

export interface UrlStateOptions<T> {
  /** Defaults to the codec matching the default value's type (string, number or boolean). */
  codec?: UrlStateCodec<T>;
  /**
   * `replace` (default) for a refinement of the view (search, filter, sort, page): Back leaves
   * the page. `push` for a step the user would expect Back to undo (a tab).
   */
  history?: 'replace' | 'push';
  /** Delay before the address follows the state. For text typed a character at a time. */
  debounceMs?: number;
  /**
   * Other parameters to drop from the address when this value changes. For a tab: the search,
   * page and filters under it describe the tab being left, and carried over they would filter
   * the next one by a text its own search box may not even show.
   */
  clears?: readonly string[];
  /** False keeps the state local (an embedded copy of a view that must not own the address). */
  enabled?: boolean;
}

type SetUrlState<T> = (next: T | ((previous: T) => T)) => void;

/**
 * The address after the last write, until `useSearchParams` has caught up with it.
 *
 * <p>Two states set in the same handler (`setSearch('')` then `setPage(1)`) both see the query
 * of the last RENDER. Without this, each would build its url from that same stale query and the
 * second write would silently undo the first.
 */
let lastWrite: { path: string; from: string; to: string } | null = null;

/**
 * The query to build a write on.
 *
 * <p>The browser's own address when it is this page's, because it is the only thing that is
 * never behind: `useSearchParams` catches up in a transition, so a write built from the last
 * RENDER undoes whatever changed the address since, whoever made the change (this hook a moment
 * ago, the folder navigation, a one-shot parameter being stripped). The rendered query is the
 * fallback for when the address is not this page's, which in practice is a test with a fixed
 * `usePathname`.
 */
function liveQueryFor(path: string, rendered: string): string {
  if (typeof window === 'undefined' || window.location.pathname !== path) return rendered;
  return window.location.search.replace(/^[?]/, '');
}

/**
 * The query the last write left in the address, if `rendered` (what `useSearchParams` shows)
 * has not caught up with it yet.
 *
 * <p>The write is only believed while the browser's own address still shows it. Otherwise a
 * write whose component went away before the catch-up (a handler that sets a value and closes
 * the view) would sit here for good, and the next visit to the same page on the same query
 * would start on a state the user had left.
 */
function pendingQueryFor(path: string | null, rendered: string): string | null {
  if (!lastWrite || lastWrite.path !== path || lastWrite.from !== rendered) return null;
  if (typeof window === 'undefined') return null;
  if (window.location.pathname !== path) return null;
  return window.location.search.replace(/^\?/, '') === lastWrite.to ? lastWrite.to : null;
}

/**
 * The hooks mounted right now, by parameter, each with a way to put it back to its default.
 *
 * <p>For `clears`. Removing a parameter from the address is enough for a value that is IN the
 * address: its hook sees the change and follows. It is not enough for a search still inside its
 * debounce, which was never written: nothing changes for that hook, and its timer then writes
 * the text onto the tab the user just switched to. So a clear also tells the hooks themselves.
 */
const mounted = new Map<string, Set<() => void>>();

function resetMounted(keys: readonly string[]): void {
  for (const key of keys) mounted.get(key)?.forEach((reset) => reset());
}

function defaultCodecFor<T>(defaultValue: T): UrlStateCodec<T> {
  if (typeof defaultValue === 'number') return urlNumber as unknown as UrlStateCodec<T>;
  if (typeof defaultValue === 'boolean') return urlBoolean as unknown as UrlStateCodec<T>;
  return urlString as unknown as UrlStateCodec<T>;
}

/**
 * `useState`, with the value mirrored in the page's query string under `key`.
 *
 * <p>That is what makes a search, a filter, a sort or a tab survive a reload, a dropped
 * connection, a shared link and the browser's Back and Forward buttons. The default value is
 * spelled by ABSENCE, so a page nobody touched keeps a clean address.
 *
 * <p>The state is held locally and the address follows it, rather than the reverse, so typing
 * stays instant under `debounceMs`. When the address changes for a reason other than this
 * hook's own write (Back, Forward, a link to the same page) the state follows the address.
 *
 * <p>Every other parameter of the page is preserved on a write.
 */
export function useUrlState<T>(
  key: string,
  defaultValue: T,
  options: UrlStateOptions<T> = {},
): [T, SetUrlState<T>] {
  const { history = 'replace', debounceMs = 0, enabled = true } = options;
  const clearsKey = (options.clears ?? []).join(',');
  const pathname = usePathname();
  const searchParams = useSearchParams();

  // Callers pass literals (`[]`, a fresh codec): only the first ones are kept, so an inline
  // default cannot make the setter, or anything depending on it, change on every render.
  const [{ initial, codec }] = useState(() => ({
    initial: defaultValue,
    codec: options.codec ?? defaultCodecFor(defaultValue),
  }));

  const query = searchParams?.toString() ?? '';
  // A write this page has made that `useSearchParams` has not caught up with yet. A component
  // mounting in that window (the content under a tab that was just switched, whose parameters
  // the switch cleared) must read the address as written, not the one being left: otherwise it
  // starts on the previous tab's search and fetches with it before correcting itself.
  const pendingQuery = pendingQueryFor(pathname, query);
  const raw = !enabled
    ? null
    : pendingQuery !== null
      ? new URLSearchParams(pendingQuery).get(key)
      : (searchParams?.get(key) ?? null);

  const decode = useCallback((value: string | null): T => {
    if (value === null) return initial;
    const parsed = codec.parse(value);
    return parsed === undefined ? initial : parsed;
  }, [initial, codec]);

  const [value, setValue] = useState<T>(() => decode(raw));
  const valueRef = useRef(value);

  /**
   * What this hook put in the address and has not yet seen come back, oldest first, starting
   * with the value the address is known to hold. It tells this hook's own echo from someone
   * else's change. It is a list rather than one value because the address is re-read in a
   * transition: after two quick writes the echo of the FIRST can still arrive, and taking it
   * for an outside change would put the state back one step.
   */
  const ownWritesRef = useRef<Array<string | null>>([raw]);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const queryRef = useRef(query);
  const pathnameRef = useRef(pathname);

  // What the setter reads, kept in step with the last render. The setter only ever runs from a
  // handler or a timer, both of which come after this.
  useEffect(() => {
    valueRef.current = value;
    queryRef.current = query;
    pathnameRef.current = pathname;
    // The address moved on from the one the last write started at: it caught up, or the user
    // went somewhere else. Either way that write no longer says anything about this address,
    // and keeping it would graft its parameters onto the next page that renders the same query.
    if (lastWrite && (lastWrite.path !== pathname || lastWrite.from !== query)) {
      lastWrite = null;
    }
  });

  const cancelPendingWrite = useCallback(() => {
    if (timerRef.current !== null) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  }, []);

  useEffect(() => {
    if (!enabled) return;
    const reset = () => {
      cancelPendingWrite();
      valueRef.current = initial;
      setValue(initial);
    };
    const resets = mounted.get(key) ?? new Set<() => void>();
    mounted.set(key, resets);
    resets.add(reset);
    return () => {
      resets.delete(reset);
      if (resets.size === 0) mounted.delete(key);
    };
  }, [enabled, key, initial, cancelPendingWrite]);

  // The address changed and it was not us: Back, Forward, a link to this same page.
  // The LAST matching entry: a value written, replaced and written again leaves two entries
  // for it, and stopping at the first would keep the one in between as a pending echo, so a
  // later Back to that value would be taken for our own write and ignored.
  useEffect(() => {
    const echoed = ownWritesRef.current.lastIndexOf(raw);
    if (echoed >= 0) {
      ownWritesRef.current.splice(0, echoed);
      return;
    }
    cancelPendingWrite();
    ownWritesRef.current = [raw];
    setValue(decode(raw));
  }, [raw, decode, cancelPendingWrite]);

  // A write still waiting when the component goes away is dropped: by then the address may
  // belong to another page, and the parameter must not follow the user there.
  useEffect(() => cancelPendingWrite, [cancelPendingWrite]);

  const write = useCallback((nextRaw: string | null) => {
    const path = pathnameRef.current;
    if (!path) return;
    const rendered = queryRef.current;
    const params = new URLSearchParams(liveQueryFor(path, rendered));
    // Compared in the spelling `URLSearchParams` writes, not the one the address happens to
    // use: a hand-typed `sort=name:asc` and the `sort=name%3Aasc` this would write back are the
    // same query, and rewriting one into the other on a mere restore is a change nobody made.
    const base = params.toString();
    if ((params.get(key) ?? null) !== nextRaw && clearsKey) {
      const cleared = clearsKey.split(',');
      for (const name of cleared) params.delete(name);
      resetMounted(cleared);
    }
    if (nextRaw === null) params.delete(key);
    else params.set(key, nextRaw);
    const next = params.toString();
    if (next === base) return;
    ownWritesRef.current.push(nextRaw);
    lastWrite = { path, from: rendered, to: next };
    showSamePageUrl(
      next ? `${path}?${next}` : path,
      base ? `${path}?${base}` : path,
      history,
    );
  }, [key, history, clearsKey]);

  const set = useCallback<SetUrlState<T>>((next) => {
    const resolved = typeof next === 'function'
      ? (next as (previous: T) => T)(valueRef.current)
      : next;
    valueRef.current = resolved;
    setValue(resolved);
    if (!enabled) return;

    const serialized = codec.serialize(resolved);
    const nextRaw = serialized === codec.serialize(initial) ? null : serialized;

    cancelPendingWrite();
    if (debounceMs > 0) {
      timerRef.current = setTimeout(() => {
        timerRef.current = null;
        write(nextRaw);
      }, debounceMs);
    } else {
      write(nextRaw);
    }
  }, [enabled, debounceMs, write, cancelPendingWrite, codec, initial]);

  return [value, set];
}

/** The delay every search box uses before its text reaches the address. */
export const URL_SEARCH_DEBOUNCE_MS = 300;

/** A search box's text, under `key` (default `q`), written once the typing pauses. */
export function useUrlSearchState(key = 'q', enabled = true): [string, SetUrlState<string>] {
  return useUrlState(key, '', { debounceMs: URL_SEARCH_DEBOUNCE_MS, enabled });
}
