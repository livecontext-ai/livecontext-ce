'use client';

import {
  urlEnum,
  urlInt,
  urlPageIndex,
  useUrlSearchState,
  useUrlState,
} from '@/hooks/useUrlState';

/** The names every list spells its view with, so a link reads the same on each of them. */
export const LIST_URL_KEYS = {
  search: 'q',
  sort: 'sort',
  visibility: 'visibility',
  page: 'page',
  pageSize: 'size',
} as const;

const VISIBILITY_VALUES = ['all', 'public', 'private'] as const;

interface UrlListViewOptions<S extends string> {
  /** The sort keys this list offers. Anything else in the address falls back to the default. */
  sortKeys: readonly S[];
  defaultSort: S;
  defaultPageSize: number;
  /** False for a copy of the list embedded in another page, which must not own the address. */
  enabled?: boolean;
}

/**
 * The view of a resource list (search, sort, visibility, page, page size), kept in the
 * address so a reload, a dropped connection or a shared link reopens the list as it was.
 *
 * <p>It only holds the values. Going back to the first page when a filter changes stays with
 * the list, which also has to do it for things this hook does not know about (the open folder,
 * a workspace switch): see {@code useEffectOnChange}.
 */
export function useUrlListView<S extends string>({
  sortKeys,
  defaultSort,
  defaultPageSize,
  enabled = true,
}: UrlListViewOptions<S>) {
  const [searchQuery, setSearchQuery] = useUrlSearchState(LIST_URL_KEYS.search, enabled);
  const [sortBy, setSortBy] = useUrlState<S>(LIST_URL_KEYS.sort, defaultSort, {
    codec: urlEnum(sortKeys),
    enabled,
  });
  const [visibilityFilter, setVisibilityFilter] = useUrlState<(typeof VISIBILITY_VALUES)[number]>(
    LIST_URL_KEYS.visibility,
    'all',
    { codec: urlEnum(VISIBILITY_VALUES), enabled },
  );
  const [page, setPage] = useUrlState(LIST_URL_KEYS.page, 0, { codec: urlPageIndex, enabled });
  const [pageSize, setPageSize] = useUrlState(LIST_URL_KEYS.pageSize, defaultPageSize, {
    codec: urlInt(1, 100),
    enabled,
  });

  return {
    searchQuery,
    setSearchQuery,
    sortBy,
    setSortBy,
    visibilityFilter,
    setVisibilityFilter,
    page,
    setPage,
    pageSize,
    setPageSize,
  };
}
