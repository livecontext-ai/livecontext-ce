/**
 * Hook for standardized error handling
 * Eliminates duplication of error handling logic across the codebase
 *
 * DRY: Replaces 6+ occurrences of:
 *   const errorMessage = err instanceof Error ? err.message : 'Failed to...';
 *   setError(errorMessage);
 *   console.error('Error:', err);
 */

'use client';

import { useState, useCallback } from 'react';

export interface UseErrorHandlerReturn {
  error: string | null;
  setError: (error: string | null) => void;
  handleError: (err: unknown, defaultMessage: string, context?: string) => void;
  clearError: () => void;
}

/**
 * Standardized error handling hook
 *
 * @example
 * const { error, handleError, clearError } = useErrorHandler();
 *
 * try {
 *   await somethingRisky();
 * } catch (err) {
 *   handleError(err, 'Failed to do something', 'MyComponent');
 * }
 */
export function useErrorHandler(): UseErrorHandlerReturn {
  const [error, setError] = useState<string | null>(null);

  const handleError = useCallback((
    err: unknown,
    defaultMessage: string,
    context?: string
  ) => {
    const errorMessage = err instanceof Error ? err.message : defaultMessage;
    setError(errorMessage);

    const logPrefix = context ? `[${context}]` : '';
    console.error(`${logPrefix} ${defaultMessage}:`, err);
  }, []);

  const clearError = useCallback(() => {
    setError(null);
  }, []);

  return {
    error,
    setError,
    handleError,
    clearError,
  };
}
