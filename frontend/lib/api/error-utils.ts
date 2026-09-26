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
 * Detects the 409 plan-resource-limit refusal. apiClient already announces it with its own
 * global `plan-limit-exceeded` event (toast/upgrade surface), so callers must not stack a
 * second, generic error surface on top of it.
 */
export function isPlanLimitError(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false;
  const { status, code } = error as { status?: unknown; code?: unknown };
  return status === 409 && code === 'PLAN_RESOURCE_LIMIT_EXCEEDED';
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
  // An apiClient ApiError keeps the backend body's human `message` as its message and the
  // machine token (the body's `error`) in `code`: read both, or a CLOUD_LINK_* refusal whose
  // body carries a readable message would never be recognised.
  const code = typeof (error as { code?: unknown }).code === 'string' ? (error as { code: string }).code : '';
  if (error instanceof Error) return [error.message || '', code].filter(Boolean).join(' ');
  if (typeof error === 'object') {
    const o = error as { message?: unknown; error?: unknown };
    return [String(o.message ?? o.error ?? ''), code].filter(Boolean).join(' ');
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

/** Token the orchestrator's node credit gate stamps on the failed node's output. */
export const CREDIT_EXHAUSTED_CODE = 'CREDIT_EXHAUSTED';

/** Opening words of the gate's node error message (backend `CreditExhaustion.MESSAGE`). */
const CREDIT_EXHAUSTED_MESSAGE_PREFIX = 'Out of credits';

/**
 * A workflow NODE failed because the workspace ran out of credits.
 *
 * <p>An out-of-credit run is no longer refused up front: the node (the trigger
 * itself, for a scheduled or webhook fire) is executed, fails, and the rest of the
 * workflow is skipped. An AUTOMATIC fire is answered 202 before that happens, so
 * there is no 402 to key the modal on - this recognises the failure from the node
 * instead. Prefers the machine token; the message is the fallback for a payload
 * whose output was trimmed.
 */
export function isCreditExhaustedFailure(errorCode: unknown, message: unknown): boolean {
  if (typeof errorCode === 'string' && errorCode === CREDIT_EXHAUSTED_CODE) return true;
  const text = errorText(message);
  return text.includes(CREDIT_EXHAUSTED_CODE) || text.includes(CREDIT_EXHAUSTED_MESSAGE_PREFIX);
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

/** Machine token the cloud answers (HTTP 403 body or NDJSON error event) when a CE link's cloud account is not on a paid plan. */
export const CLOUD_LINK_PLAN_REQUIRED_CODE = 'CLOUD_LINK_PLAN_REQUIRED';

/**
 * CE cloud-relay: the linked cloud account is not on a paid plan, so the cloud refused a paid
 * relay call (LLM relay, catalog relay, web search relay), or the switch of a source to Cloud. The CE
 * surfaces the cloud body verbatim, so the token survives in every error shape, exactly like
 * {@code INSUFFICIENT_CREDITS} and {@code MODEL_NOT_SUPPORTED}.
 */
export function isCloudLinkPlanRequiredError(error: unknown): boolean {
  return errorText(error).includes(CLOUD_LINK_PLAN_REQUIRED_CODE);
}

/** Machine token the cloud answers when the linked cloud account has not finished its onboarding. */
export const CLOUD_LINK_ONBOARDING_REQUIRED_CODE = 'CLOUD_LINK_ONBOARDING_REQUIRED';

/**
 * CE: the linked cloud account has not completed the cloud onboarding (email code, profile), so
 * the cloud has not registered this install and a source cannot be switched to Cloud yet.
 */
export function isCloudLinkOnboardingRequiredError(error: unknown): boolean {
  return errorText(error).includes(CLOUD_LINK_ONBOARDING_REQUIRED_CODE);
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
