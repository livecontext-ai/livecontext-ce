/**
 * Pure decision logic for the account "Change password" form (CE embedded auth).
 *
 * Kept free of React / i18n / network so the validation precedence and the
 * backend-status → outcome mapping can be unit-tested in isolation. The Security
 * tab maps each outcome to a localized message and the matching side effects
 * (clear form + sign out on success, etc.).
 */

export type ChangePasswordOutcome =
  | 'mismatch'      // newPassword !== confirmPassword
  | 'too_short'     // newPassword shorter than the 8-char minimum
  | 'success'       // backend accepted the change
  | 'wrong_current' // backend rejected the current password (HTTP 401)
  | 'error';        // any other failure (network, 4xx/5xx)

export interface PasswordChangeForm {
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}

/** Mirrors the backend's PasswordAuthService minimum (>= 8 characters). */
export const MIN_PASSWORD_LENGTH = 8;

/**
 * Validate the form, then (only if valid) call the backend and classify the result.
 * Client validation short-circuits before any network call so a mismatched or
 * too-short password never reaches the server.
 */
export async function evaluatePasswordChange(
  form: PasswordChangeForm,
  changePassword: (current: string, next: string) => Promise<{ success: boolean; status?: number }>,
): Promise<ChangePasswordOutcome> {
  if (form.newPassword !== form.confirmPassword) {
    return 'mismatch';
  }
  if (form.newPassword.length < MIN_PASSWORD_LENGTH) {
    return 'too_short';
  }

  const result = await changePassword(form.currentPassword, form.newPassword);
  if (result.success) {
    return 'success';
  }
  return result.status === 401 ? 'wrong_current' : 'error';
}
