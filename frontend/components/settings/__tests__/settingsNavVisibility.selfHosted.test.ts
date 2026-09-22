import { describe, expect, it, vi } from 'vitest';

/**
 * SELF-HOSTED ENTERPRISE: `IS_CE` is FALSE there (the edition resolver is binary and
 * that deployment reads as "cloud"), while `IS_MANAGED_CLOUD` is false because the
 * backend's `AppEditionProvider.isManagedCloud()` covers only CLOUD and DEDICATED_CLOUD.
 *
 * This combination is the whole reason `managedCloudOnly` exists, and it is the one an
 * `IS_CE`-based rule gets wrong: it would advertise a settings page whose every submit
 * answers 503. Neither the CE Playwright spec nor the managed-cloud sibling file can
 * cover it, because both pin the other two editions.
 */
vi.mock('@/lib/edition', () => ({ IS_CE: false, IS_MANAGED_CLOUD: false }));

import { isSettingsNavItemVisible, settingsNavItems } from '../settingsNavItems';

describe('settings nav on a self-hosted ENTERPRISE install', () => {
  it('hides a managedCloudOnly entry, which IS_CE alone would have shown', () => {
    const entry = settingsNavItems.find((i) => i.href === '/app/settings/verified-accounts');

    expect(entry).toBeTruthy();
    expect(isSettingsNavItemVisible(entry!, { isAdmin: true })).toBe(false);
  });

  it('still shows a hiddenInCE entry, because this is not CE', () => {
    // The two flags are genuinely different rules, not synonyms. Credits & Plans is
    // hiddenInCE and remains available to a self-hosted-enterprise admin.
    const credits = settingsNavItems.find((i) => i.href === '/app/settings/admin-credits');

    expect(credits?.hiddenInCE).toBe(true);
    expect(isSettingsNavItemVisible(credits!, { isAdmin: true })).toBe(true);
  });
});
