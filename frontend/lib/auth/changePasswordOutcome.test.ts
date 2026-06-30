import { describe, it, expect, vi } from 'vitest';
import { evaluatePasswordChange } from './changePasswordOutcome';

describe('evaluatePasswordChange', () => {
  const validForm = {
    currentPassword: 'current-pass',
    newPassword: 'newpassword1',
    confirmPassword: 'newpassword1',
  };

  it('returns "mismatch" and never calls the backend when new !== confirm', async () => {
    const changePassword = vi.fn().mockResolvedValue({ success: true });

    const outcome = await evaluatePasswordChange(
      { ...validForm, confirmPassword: 'different-value' },
      changePassword,
    );

    expect(outcome).toBe('mismatch');
    expect(changePassword).not.toHaveBeenCalled();
  });

  it('checks mismatch before length - a short AND mismatched password is "mismatch"', async () => {
    const changePassword = vi.fn();

    const outcome = await evaluatePasswordChange(
      { currentPassword: 'cur', newPassword: 'ab', confirmPassword: 'xy' },
      changePassword,
    );

    expect(outcome).toBe('mismatch');
    expect(changePassword).not.toHaveBeenCalled();
  });

  it('returns "too_short" (and no backend call) when matching but under 8 chars', async () => {
    const changePassword = vi.fn();

    const outcome = await evaluatePasswordChange(
      { currentPassword: 'cur', newPassword: 'short', confirmPassword: 'short' },
      changePassword,
    );

    expect(outcome).toBe('too_short');
    expect(changePassword).not.toHaveBeenCalled();
  });

  it('passes currentPassword + newPassword to the backend and returns "success"', async () => {
    const changePassword = vi.fn().mockResolvedValue({ success: true });

    const outcome = await evaluatePasswordChange(validForm, changePassword);

    expect(outcome).toBe('success');
    expect(changePassword).toHaveBeenCalledWith('current-pass', 'newpassword1');
  });

  it('maps a 401 failure to "wrong_current"', async () => {
    const changePassword = vi.fn().mockResolvedValue({ success: false, status: 401 });

    const outcome = await evaluatePasswordChange(validForm, changePassword);

    expect(outcome).toBe('wrong_current');
  });

  it('maps any non-401 failure (e.g. 500 or status-less) to "error"', async () => {
    const change500 = vi.fn().mockResolvedValue({ success: false, status: 500 });
    const changeNoStatus = vi.fn().mockResolvedValue({ success: false });

    expect(await evaluatePasswordChange(validForm, change500)).toBe('error');
    expect(await evaluatePasswordChange(validForm, changeNoStatus)).toBe('error');
  });
});
