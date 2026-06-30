/**
 * Recency ordering for conversation-like items (sidebar list, shared context).
 *
 * Single source of truth so the conversation list is ordered identically
 * wherever it is assembled (UnifiedAppContext's cached list and the
 * ConversationSidebar merge of context + server DTOs). Ordering is by most
 * recent activity: `updatedAt`, falling back to `createdAt`, falling back to
 * the epoch (so missing/invalid timestamps sort last).
 */
export interface HasRecency {
  updatedAt?: string;
  createdAt?: string;
}

/**
 * Epoch-ms sort key for a conversation. `Date.parse` of a valid ISO string
 * equals `new Date(iso).getTime()`; missing/invalid values yield `NaN`, which
 * `|| 0` maps to the epoch (sorts last under descending order).
 */
export function recencyKey(item: HasRecency): number {
  return Date.parse(item.updatedAt || item.createdAt || '') || 0;
}

/**
 * Return a new array sorted by most-recent activity (descending). Uses a
 * decorate-sort-undecorate pass so each item's key is computed once (O(n))
 * instead of twice per comparison. The input array is not mutated.
 */
export function sortByRecency<T extends HasRecency>(items: T[]): T[] {
  return items
    .map((item) => [item, recencyKey(item)] as const)
    .sort((a, b) => b[1] - a[1])
    .map(([item]) => item);
}
