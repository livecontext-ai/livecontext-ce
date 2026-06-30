"use client";

import React, { useState, useEffect, useCallback, useRef } from "react";
import { ChevronDown, ChevronRight, Search } from "lucide-react";
import { cn } from "@/lib/utils";
import { Input } from "@/components/ui/input";
import { storageApi, S3_FILES_FILTER } from "@/lib/api/storage-api";
import { useDebouncedValue } from "@/hooks/useDebouncedValue";
import ResourceAccessTriState, { type AccessLevel } from "./ResourceAccessTriState";

const PAGE_SIZE = 30;
export type FileLevel = AccessLevel;

interface FileAccessSectionProps {
  label: string;
  expanded: boolean;
  onToggleExpanded: () => void;
  /** Count of files this member is currently restricted from (drives the header badge). */
  restrictedCount: number;
  restrictionCountLabel: (count: number) => string;
  allAccessLabel: string;
  searchPlaceholder: string;
  emptyLabel: string;
  accessFull: string;
  accessRead: string;
  accessNone: string;
  allowAllLabel: string;
  blockAllLabel: string;
  getLevel: (fileId: string) => FileLevel;
  onSetLevel: (fileId: string, level: FileLevel) => void;
  onAllowAll: () => void;
  onBlockAll: () => void;
}

/**
 * Files sub-section of the member Resource Access modal. Unlike the other (small, bounded)
 * resource types, a workspace can hold thousands of files, so this uses REAL server-side
 * pagination: it shows the total count + the first page, infinite-scrolls the next pages on
 * demand (IntersectionObserver), and searches server-side (debounced). It also passes
 * `s3Only` so observability TEXT blobs (tool_call_result.txt, agent_message.txt) never show -
 * exactly like the full-page Files browser.
 */
export default function FileAccessSection(props: FileAccessSectionProps) {
  const { label, expanded, onToggleExpanded, restrictedCount } = props;
  const [items, setItems] = useState<{ id: string; name: string }[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [fetching, setFetching] = useState(false);
  const [searchInput, setSearchInput] = useState("");
  const search = useDebouncedValue(searchInput, 300);
  const loadedRef = useRef(false);
  const fetchingRef = useRef(false);
  const sentinelRef = useRef<HTMLDivElement | null>(null);

  const loadPage = useCallback(async (pageToLoad: number, q: string, append: boolean) => {
    if (fetchingRef.current) return;
    fetchingRef.current = true;
    setFetching(true);
    try {
      const res = await storageApi.getExplorerEntries({
        page: pageToLoad,
        size: PAGE_SIZE,
        ...S3_FILES_FILTER,
        ...(q ? { search: q } : {}),
      });
      const next = (res.content || []).map((f) => ({ id: f.id, name: f.fileName || f.contentType || f.id }));
      setItems((prev) => (append ? [...prev, ...next] : next));
      setTotal(res.totalElements ?? 0);
      setPage(pageToLoad);
      setHasMore(pageToLoad + 1 < (res.totalPages ?? 0));
    } catch (e) {
      console.error("Failed to load files page:", e);
    } finally {
      fetchingRef.current = false;
      setFetching(false);
    }
  }, []);

  // First open: load page 0.
  useEffect(() => {
    if (expanded && !loadedRef.current) {
      loadedRef.current = true;
      loadPage(0, "", false);
    }
  }, [expanded, loadPage]);

  // Debounced search re-queries from page 0 (only after the section has opened once).
  useEffect(() => {
    if (!loadedRef.current) return;
    loadPage(0, search, false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search]);

  // Infinite scroll: load the next page when the sentinel scrolls into view.
  useEffect(() => {
    const el = sentinelRef.current;
    if (!el || !expanded) return;
    const obs = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting && hasMore && !fetchingRef.current) {
          loadPage(page + 1, search, true);
        }
      },
      { rootMargin: "120px" },
    );
    obs.observe(el);
    return () => obs.disconnect();
  }, [expanded, hasMore, page, search, loadPage]);

  return (
    <div className="border border-theme rounded-lg overflow-hidden">
      <button
        onClick={onToggleExpanded}
        className="w-full flex items-center justify-between px-3 py-2.5 hover:bg-black/[0.02] dark:hover:bg-white/[0.02] transition-colors"
      >
        <div className="flex items-center gap-2">
          {expanded ? (
            <ChevronDown className="h-3.5 w-3.5 text-theme-secondary" />
          ) : (
            <ChevronRight className="h-3.5 w-3.5 text-theme-secondary" />
          )}
          <span className="text-sm font-medium text-theme-primary">{label}</span>
          <span className="text-xs text-theme-secondary">({total})</span>
        </div>
        {restrictedCount > 0 ? (
          <span className="text-xs px-2 py-0.5 bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400 rounded-full">
            {props.restrictionCountLabel(restrictedCount)}
          </span>
        ) : (
          <span className="text-xs text-emerald-600 dark:text-emerald-400">{props.allAccessLabel}</span>
        )}
      </button>

      {expanded && (
        <div className="border-t border-theme">
          {/* Bulk controls */}
          <div className="flex items-center justify-end gap-2 px-3 py-1.5 border-b border-theme bg-black/[0.01] dark:bg-white/[0.01]">
            <button
              type="button"
              onClick={props.onAllowAll}
              className="text-xs px-2 py-0.5 rounded-md text-emerald-700 dark:text-emerald-400 hover:bg-emerald-50 dark:hover:bg-emerald-900/20 transition-colors"
            >
              {props.allowAllLabel}
            </button>
            <button
              type="button"
              onClick={props.onBlockAll}
              className="text-xs px-2 py-0.5 rounded-md text-red-700 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors"
            >
              {props.blockAllLabel}
            </button>
          </div>
          {/* Server-side search (debounced) */}
          <div className="px-3 py-2 border-b border-theme">
            <div className="relative">
              <Search className="absolute left-2 top-1/2 -translate-y-1/2 h-3 w-3 text-theme-secondary" />
              <Input
                value={searchInput}
                onChange={(e) => setSearchInput(e.target.value)}
                placeholder={props.searchPlaceholder}
                className="h-7 pl-7 text-xs"
              />
            </div>
          </div>
          {/* Paginated list + infinite scroll */}
          <div className="max-h-48 overflow-y-auto">
            {items.length === 0 && !fetching ? (
              <p className="text-xs text-theme-secondary px-3 py-3 text-center">{props.emptyLabel}</p>
            ) : (
              <>
                {items.map((file) => {
                  const level = props.getLevel(file.id);
                  const nameClass = cn(
                    "text-sm truncate max-w-[150px]",
                    level === "deny"
                      ? "text-theme-secondary line-through"
                      : level === "read"
                        ? "text-theme-secondary italic"
                        : "text-theme-primary",
                  );
                  return (
                    <div
                      key={file.id}
                      className="flex items-center justify-between px-3 py-2 hover:bg-black/[0.02] dark:hover:bg-white/[0.02]"
                    >
                      <span className={nameClass}>{file.name}</span>
                      <ResourceAccessTriState
                        level={level}
                        onSetLevel={(lvl) => props.onSetLevel(file.id, lvl)}
                        fullLabel={props.accessFull}
                        readLabel={props.accessRead}
                        denyLabel={props.accessNone}
                      />
                    </div>
                  );
                })}
                {/* Infinite-scroll sentinel + loading spinner for the next page. */}
                <div ref={sentinelRef} className="h-px" />
                {fetching && (
                  <div className="flex items-center justify-center py-3">
                    <div className="animate-spin rounded-full h-4 w-4 border-b-2 border-[var(--accent-primary)]" />
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
