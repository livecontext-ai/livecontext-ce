'use client';

import { useCallback, useState } from 'react';
import type { PaginationState } from '../types';

export interface UsePaginationParams {
  initialPageSize?: number;
}

export interface UsePaginationReturn {
  // State
  pagination: PaginationState;
  // Setters
  setPagination: React.Dispatch<React.SetStateAction<PaginationState>>;
  // Functions
  /**
   * Update the current page. Returns false if page is invalid.
   * Does NOT trigger data fetch - caller is responsible for fetching.
   */
  setCurrentPage: (page: number) => boolean;
  /**
   * Update page size and reset to page 1.
   * Does NOT trigger data fetch - caller is responsible for fetching.
   */
  setPageSize: (size: number) => void;
  /**
   * Reset pagination to initial state
   */
  resetPagination: () => void;
  /**
   * Update pagination after successful data fetch
   */
  updatePaginationFromResponse: (response: {
    totalItems: number;
    totalPages: number;
    hasMore: boolean;
    nextCursor?: string | null;
    currentPage?: number;
  }) => void;
}

const DEFAULT_PAGE_SIZE = 50;

/**
 * Hook for managing pagination state in the data table.
 * Follows Single Responsibility Principle - only handles pagination logic.
 *
 * Note: This hook only manages pagination state. Data fetching is handled
 * by the parent controller to maintain proper separation of concerns.
 */
export function usePagination({
  initialPageSize = DEFAULT_PAGE_SIZE
}: UsePaginationParams = {}): UsePaginationReturn {
  const [pagination, setPagination] = useState<PaginationState>({
    currentPage: 1,
    pageSize: initialPageSize,
    totalItems: 0,
    totalPages: 0,
    nextCursor: null,
    hasMore: false
  });

  /**
   * Set current page. Returns false if page is out of bounds.
   */
  const setCurrentPage = useCallback((page: number): boolean => {
    // Validate page bounds
    if (page < 1) return false;

    setPagination(prev => {
      // Additional validation using current state
      if (prev.totalPages > 0 && page > prev.totalPages) return prev;
      return { ...prev, currentPage: page };
    });

    return true;
  }, []);

  /**
   * Set page size and reset to first page
   */
  const setPageSize = useCallback((size: number) => {
    if (size < 1) return;

    setPagination(prev => ({
      ...prev,
      pageSize: size,
      currentPage: 1 // Reset to first page when changing page size
    }));
  }, []);

  /**
   * Reset pagination to initial state
   */
  const resetPagination = useCallback(() => {
    setPagination({
      currentPage: 1,
      pageSize: initialPageSize,
      totalItems: 0,
      totalPages: 0,
      nextCursor: null,
      hasMore: false
    });
  }, [initialPageSize]);

  /**
   * Update pagination state after a successful data fetch.
   * Normalizes response fields from both camelCase and snake_case formats.
   */
  const updatePaginationFromResponse = useCallback((response: {
    totalItems: number;
    totalPages: number;
    hasMore: boolean;
    nextCursor?: string | null;
    currentPage?: number;
  }) => {
    setPagination(prev => ({
      ...prev,
      currentPage: response.currentPage ?? prev.currentPage,
      totalItems: response.totalItems,
      totalPages: response.totalPages,
      hasMore: response.hasMore,
      nextCursor: response.nextCursor ?? null
    }));
  }, []);

  return {
    // State
    pagination,
    // Setters
    setPagination,
    // Functions
    setCurrentPage,
    setPageSize,
    resetPagination,
    updatePaginationFromResponse,
  };
}
