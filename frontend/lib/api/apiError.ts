/**
 * Standardized API Error handling
 * Single source of truth for error types and handling
 */

export interface ApiError {
  message: string;
  code?: string;
  status?: number;
  retryable: boolean;
  originalError?: Error;
}

export class ApiException extends Error {
  public readonly code?: string;
  public readonly status?: number;
  public readonly retryable: boolean;

  constructor(error: ApiError) {
    super(error.message);
    this.name = 'ApiException';
    this.code = error.code;
    this.status = error.status;
    this.retryable = error.retryable;
  }

  toJSON(): ApiError {
    return {
      message: this.message,
      code: this.code,
      status: this.status,
      retryable: this.retryable,
    };
  }
}
