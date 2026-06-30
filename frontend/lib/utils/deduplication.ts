/**
 * Deduplication utilities
 * Eliminates duplication of ID-based deduplication logic
 *
 * DRY: Replaces 2+ occurrences of:
 *   const existingIds = new Set(prev.map(item => item.id));
 *   const unique = newItems.filter(item => !existingIds.has(item.id));
 */

/**
 * Remove items from newItems that already exist in existingItems based on ID
 *
 * @param newItems - Items to filter
 * @param existingItems - Items to check against
 * @returns Filtered items that don't exist in existingItems
 *
 * @example
 * const uniqueMessages = deduplicateById(newMessages, existingMessages);
 * setMessages(prev => [...prev, ...uniqueMessages]);
 */
export function deduplicateById<T extends { id: string | number }>(
  newItems: T[],
  existingItems: T[]
): T[] {
  const existingIds = new Set(existingItems.map(item => item.id));
  return newItems.filter(item => !existingIds.has(item.id));
}

/**
 * Deduplicate an array by ID (remove duplicates within the same array)
 *
 * @param items - Items to deduplicate
 * @returns Array with unique items (first occurrence kept)
 *
 * @example
 * const unique = uniqueById([...a, ...b, ...c]);
 */
export function uniqueById<T extends { id: string | number }>(items: T[]): T[] {
  const seen = new Set<string | number>();
  return items.filter(item => {
    if (seen.has(item.id)) {
      return false;
    }
    seen.add(item.id);
    return true;
  });
}
