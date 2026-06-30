/**
 * Centralized error detection utilities
 * Single source of truth for 404 and client error detection
 */

import { ApiException } from './apiError';

/**
 * Detects if an error is a 404 Not Found
 * Supports: ApiException, ApiError, Error with message, objects with status
 */
export function is404Error(error: unknown): boolean {
  if (!error) return false;

  // ApiException or object with status property
  if (typeof error === 'object' && 'status' in error) {
    return (error as { status: number }).status === 404;
  }

  // Error with message containing 404 or "not found"
  if (error instanceof Error) {
    const msg = error.message.toLowerCase();
    return msg.includes('404') || msg.includes('not found');
  }

  return false;
}

/**
 * Detects if an error is a client error (4xx)
 */
export function isClientError(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false;

  if ('status' in error) {
    const status = (error as { status: number }).status;
    return status >= 400 && status < 500;
  }

  return false;
}

/**
 * Detects if an error is a payment required error (402 - insufficient credits)
 */
export function is402Error(error: unknown): boolean {
  if (!error) return false;

  if (typeof error === 'object' && 'status' in error) {
    return (error as { status: number }).status === 402;
  }

  if (error instanceof Error) {
    const msg = error.message.toLowerCase();
    return msg.includes('402') || msg.includes('payment required') || msg.includes('insufficient credits');
  }

  return false;
}

/**
 * Detects if an error is a storage quota exceeded error (413 - storage full)
 */
export function is413StorageError(error: unknown): boolean {
  if (!error) return false;

  if (typeof error === 'object' && 'status' in error) {
    return (error as { status: number }).status === 413;
  }

  if (error instanceof Error) {
    const msg = error.message.toLowerCase();
    return msg.includes('413') || msg.includes('storage quota exceeded');
  }

  return false;
}

/**
 * Pull a human/machine-readable string out of any error shape: a raw string
 * (chat stream `mapped.error`, a workflow `step.errorMessage`), an `Error`, or
 * an object carrying `message` / `error`. Used by the CE cloud-relay detectors
 * below, which match on a stable machine token the cloud relay emits and which
 * survives verbatim end-to-end (chat stream, workflow step error, agent run).
 */
function errorText(error: unknown): string {
  if (!error) return '';
  if (typeof error === 'string') return error;
  if (error instanceof Error) return error.message || '';
  if (typeof error === 'object') {
    const o = error as { message?: unknown; error?: unknown };
    return String(o.message ?? o.error ?? '');
  }
  return '';
}

/**
 * The linked cloud account is out of credit. The cloud relay fails the call with
 * the {@code INSUFFICIENT_CREDITS} token (HTTP 402 body or NDJSON error event),
 * which the CE surfaces verbatim; the local step-by-step credit pre-check emits the
 * same token. In CE both mean the same thing - all CE credit is the linked cloud
 * account's - so either correctly routes to the CE "top up on cloud" modal (the
 * {@link handleCeRelayError} dispatcher only acts in CE; Cloud keeps its Stripe flow
 * via {@link is402Error}).
 */
export function isInsufficientCloudCreditError(error: unknown): boolean {
  return errorText(error).includes('INSUFFICIENT_CREDITS');
}

/**
 * CE cloud-relay: the requested model is not one the linked cloud account
 * curates (no catalog row, i.e. not in any bundle the install could hold - a
 * stale or foreign catalog). The cloud relay emits the {@code MODEL_NOT_SUPPORTED}
 * token; the CE surfaces it verbatim and prompts the user to refresh the model bundle.
 */
export function isModelNotSupportedError(error: unknown): boolean {
  return errorText(error).includes('MODEL_NOT_SUPPORTED');
}

/**
 * Detects if an error is an authentication error (401)
 */
export function isAuthError(error: unknown): boolean {
  if (!error) return false;

  if (typeof error === 'object' && 'status' in error) {
    return (error as { status: number }).status === 401;
  }

  if (error instanceof Error) {
    const msg = error.message.toLowerCase();
    return msg.includes('401') || msg.includes('authentication') || msg.includes('unauthorized');
  }

  return false;
}
