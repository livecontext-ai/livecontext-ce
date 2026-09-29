// @vitest-environment jsdom
//
// After a redeem the person is told what the code actually gave: credits, a complimentary plan
// with its end date, or both; the older code kinds keep their generic lines.
import { renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${JSON.stringify(vars)}` : key,
  useLocale: () => 'en',
}));

import { redeemErrorKey, useRedeemSuccessMessage } from '../RewardRedeemCard';

function describeWith(result: Parameters<ReturnType<typeof useRedeemSuccessMessage>>[0]) {
  const { result: hook } = renderHook(() => useRedeemSuccessMessage());
  return hook.current(result);
}

describe('useRedeemSuccessMessage', () => {
  it('credits and plan: names both, with the credits in the app locale and the end date', () => {
    const text = describeWith({
      success: true, code: 'REDEEMED', grantedCredits: 50000, grantedPlan: 'PRO', planEndsAt: '2026-12-27T10:00:00',
    });
    expect(text).toContain('successCreditsAndPlan');
    expect(text).toContain('"credits":"50,000"');
    expect(text).toContain('"plan":"PRO"');
    expect(text).toContain('2026');
  });

  it('credits only (a paying customer keeps their plan): says only the credits', () => {
    expect(describeWith({ success: true, code: 'REDEEMED', grantedCredits: 50000, grantedPlan: null }))
      .toContain('successCredits:');
  });

  it('plan only: says the plan and its end', () => {
    expect(describeWith({ success: true, code: 'REDEEMED', grantedCredits: 0, grantedPlan: 'PRO', planEndsAt: '2026-12-27T10:00:00' }))
      .toContain('successPlan:');
  });

  it('older codes keep their lines: immediate benefit, or attribution pending a subscription', () => {
    expect(describeWith({ success: true, code: 'REDEEMED' })).toBe('successGranted');
    expect(describeWith({ success: true, code: 'PENDING_CONVERSION' })).toBe('successPending');
  });
});

describe('redeemErrorKey', () => {
  it('maps the new ALREADY_ATTRIBUTED refusal and falls back to generic', () => {
    expect(redeemErrorKey('ALREADY_ATTRIBUTED')).toBe('errors.alreadyAttributed');
    expect(redeemErrorKey('SOMETHING_NEW')).toBe('errors.generic');
    expect(redeemErrorKey(undefined)).toBe('errors.generic');
  });
});
