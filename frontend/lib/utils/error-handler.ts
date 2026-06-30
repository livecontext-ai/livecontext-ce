/**
 * Gestionnaire d'erreurs unifie pour l'application
 */

export interface ApiError {
  message: string;
  status?: number;
  code?: string;
  details?: any;
}

export class ErrorHandler {
  /**
   * Normalise une erreur en ApiError
   */
  static normalizeError(error: unknown): ApiError {
    if (error instanceof Error) {
      return {
        message: error.message,
        status: undefined,
        code: undefined,
        details: error
      };
    }

    if (typeof error === 'string') {
      return {
        message: error,
        status: undefined,
        code: undefined,
        details: null
      };
    }

    if (error && typeof error === 'object' && 'message' in error) {
      return {
        message: (error as any).message,
        status: (error as any).status,
        code: (error as any).code,
        details: error
      };
    }

    return {
      message: 'An unexpected error occurred',
      status: undefined,
      code: 'UNKNOWN_ERROR',
      details: error
    };
  }

  /**
   * Determine si une erreur est recuperable
   */
  static isRecoverableError(error: ApiError): boolean {
    // Erreurs reseau ou temporaires
    if (error.status && error.status >= 500) {
      return true;
    }

    // Erreurs de timeout
    if (error.message.includes('timeout') || error.message.includes('TIMEOUT')) {
      return true;
    }

    // Erreurs de reseau
    if (error.message.includes('network') || error.message.includes('fetch')) {
      return true;
    }

    return false;
  }

  /**
   * Obtient un message d'erreur utilisateur-friendly
   */
  static getUserFriendlyMessage(error: ApiError): string {
    // Specific error messages
    if (error.status === 401) {
      return 'Your session has expired. Please log in again.';
    }

    if (error.status === 403) {
      return 'You do not have the necessary permissions to perform this action.';
    }

    if (error.status === 404) {
      return 'The requested resource was not found.';
    }

    if (error.status === 429) {
      return 'Too many requests. Please wait before trying again.';
    }

    if (error.status && error.status >= 500) {
      return 'The server is experiencing difficulties. Please try again later.';
    }

    // Generic error messages
    if (error.message.includes('timeout')) {
      return 'The request took too long. Please try again.';
    }

    if (error.message.includes('network')) {
      return 'Connection problem. Please check your internet connection.';
    }

    if (error.message.includes('fetch')) {
      return 'Unable to contact the server. Please check your connection.';
    }

    // Return the original error message if no pattern matches
    return error.message;
  }

  /**
   * Log une erreur avec le contexte approprie
   */
  static logError(error: ApiError, context?: string): void {
    // Skip logging 404 errors - they're expected "resource not found" cases
    // that are handled gracefully by the calling code
    if (error.status === 404) {
      return;
    }

    const logData = {
      message: error.message,
      status: error.status,
      code: error.code,
      context,
      timestamp: new Date().toISOString(),
      userAgent: typeof window !== 'undefined' ? window.navigator.userAgent : 'server',
      url: typeof window !== 'undefined' ? window.location.href : 'unknown'
    };

    if (error.status && error.status >= 500) {
      console.error('Server Error:', logData);
    } else if (error.status && error.status >= 400) {
      console.warn('Client Error:', logData);
    } else {
      console.error('Application Error:', logData);
    }
  }

}

export default ErrorHandler;
