import { IS_CE } from '@/lib/edition';
import { isInsufficientCloudCreditError, isModelNotSupportedError } from '@/lib/api/error-utils';
import { showCeCloudCreditModal } from '@/components/billing/CeCloudCreditModal';
import { showModelNotManagedModal } from '@/components/billing/ModelNotManagedModal';

/**
 * CE only: recognize a cloud-relay failure - insufficient cloud credit
 * ({@code INSUFFICIENT_CREDITS}) or an unmanaged model ({@code MODEL_NOT_SUPPORTED}) -
 * from any error shape (an HTTP error object, an `Error`, a chat stream message string,
 * or a workflow `step.errorMessage`) and pop the matching CE modal.
 *
 * Returns true when handled, so callers can short-circuit their generic error path -
 * notably the Cloud Stripe {@code is402Error} modal, which is a no-op in CE anyway but
 * would otherwise swallow the more specific signal. No-op (returns false) in the Cloud
 * edition, so existing cloud flows are unchanged.
 */
export function handleCeRelayError(error: unknown): boolean {
  if (!IS_CE) return false;
  if (isModelNotSupportedError(error)) {
    showModelNotManagedModal();
    return true;
  }
  if (isInsufficientCloudCreditError(error)) {
    showCeCloudCreditModal();
    return true;
  }
  return false;
}
