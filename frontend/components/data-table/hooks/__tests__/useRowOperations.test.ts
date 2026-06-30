import { describe, it, expect } from 'vitest';
import { computeTargetPageAfterDelete } from '../useRowOperations';
import type { PaginationState } from '../../types';

function pagination(currentPage: number, pageSize: number, totalItems: number): PaginationState {
  return {
    currentPage,
    pageSize,
    totalItems,
    totalPages: Math.max(1, Math.ceil(totalItems / pageSize)),
    nextCursor: null,
    hasMore: false,
  };
}

describe('computeTargetPageAfterDelete', () => {
  // Regression: deleting every row of page 1 used to land the user on an empty
  // page even when later pages still had data, because the delete handler did
  // not refetch and pagination state was never recomputed.
  it('clamps current page back to last populated page when all rows of page 1 are deleted', () => {
    // 50 rows total, page size 20 → 3 pages. User on page 1, deletes all 20
    // rows → 30 left → 2 pages. Page 1 is the last populated page; staying on
    // page 1 is correct (server now returns what was page 2's data).
    const target = computeTargetPageAfterDelete(pagination(1, 20, 50), 20);
    expect(target).toBe(1);
  });

  it('clamps to the new last page when the current page disappears entirely', () => {
    // 50 rows, page size 20 → 3 pages. User on page 3 (10 rows there).
    // Deletes all 10 rows on page 3 → 40 left → 2 pages. Stays on the new
    // last page (2) instead of refetching an empty page 3.
    const target = computeTargetPageAfterDelete(pagination(3, 20, 50), 10);
    expect(target).toBe(2);
  });

  it('keeps the current page when the deletion does not collapse pages', () => {
    // 50 rows, page size 20, user on page 2. Delete 5 rows → 45 left, still
    // 3 pages. User stays on page 2.
    const target = computeTargetPageAfterDelete(pagination(2, 20, 50), 5);
    expect(target).toBe(2);
  });

  it('returns page 1 when every row in the table is deleted', () => {
    const target = computeTargetPageAfterDelete(pagination(2, 20, 50), 50);
    expect(target).toBe(1);
  });

  it('treats over-counted deletions as a full wipe (page 1)', () => {
    // Defensive: if the caller miscounts and reports more deletions than total
    // items, we still return a valid page rather than 0 or a negative number.
    const target = computeTargetPageAfterDelete(pagination(2, 20, 50), 999);
    expect(target).toBe(1);
  });

  it('never returns a page below 1 even when starting state is malformed', () => {
    const target = computeTargetPageAfterDelete(pagination(0, 20, 0), 0);
    expect(target).toBe(1);
  });
});
