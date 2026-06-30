'use client';

import { useState, useCallback } from 'react';
import { ErrorHandler, ApiError } from '@/lib/utils/error-handler';

/**
 * Hook pour la gestion d'erreurs unifiee
 */
export function useErrorHandler() {
  const [errors, setErrors] = useState<Map<string, ApiError>>(new Map());

  const handleError = useCallback((error: unknown, context?: string): ApiError => {
    const normalizedError = ErrorHandler.normalizeError(error);
    
    // Log l'erreur
    ErrorHandler.logError(normalizedError, context);
    
    // Stocker l'erreur avec un contexte
    const errorKey = context || 'general';
    setErrors(prev => new Map(prev.set(errorKey, normalizedError)));
    
    return normalizedError;
  }, []);

  const clearError = useCallback((context?: string) => {
    if (context) {
      setErrors(prev => {
        const newMap = new Map(prev);
        newMap.delete(context);
        return newMap;
      });
    } else {
      setErrors(new Map());
    }
  }, []);

  const getError = useCallback((context?: string): ApiError | null => {
    if (context) {
      return errors.get(context) || null;
    }
    
    // Retourner la premiere erreur trouvee
    const firstError = errors.values().next().value;
    return firstError || null;
  }, [errors]);

  const hasError = useCallback((context?: string): boolean => {
    if (context) {
      return errors.has(context);
    }
    return errors.size > 0;
  }, [errors]);

  const getUserFriendlyMessage = useCallback((context?: string): string | null => {
    const error = getError(context);
    return error ? ErrorHandler.getUserFriendlyMessage(error) : null;
  }, [getError]);

  const isRecoverable = useCallback((context?: string): boolean => {
    const error = getError(context);
    return error ? ErrorHandler.isRecoverableError(error) : false;
  }, [getError]);

  return {
    handleError,
    clearError,
    getError,
    hasError,
    getUserFriendlyMessage,
    isRecoverable,
    errors: Array.from(errors.values()),
  };
}

export default useErrorHandler;
