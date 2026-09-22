import { describe, expect, it, vi } from 'vitest';

// Cloud: every user has a reason to open AI providers (their own keys live there).
vi.mock('@/lib/edition', () => ({ IS_CE: false, IS_MANAGED_CLOUD: true }));

import { isSettingsNavItemVisible, settingsNavItems } from '../settingsNavItems';

describe('AI providers entry on cloud', () => {
  const entry = settingsNavItems.find((i) => i.href === '/app/settings/ai-providers');

  it('is listed for a non-admin, who gets the own-keys panel there', () => {
    expect(entry).toBeDefined();
    expect(isSettingsNavItemVisible(entry!, { isAdmin: false })).toBe(true);
    expect(isSettingsNavItemVisible(entry!, { isAdmin: true })).toBe(true);
  });

  it('keeps the platform keys entry admin-only: the own-keys panel is the only non-admin surface', () => {
    const platformKeys = settingsNavItems.find((i) => i.href === '/app/settings/platform-credentials');
    expect(isSettingsNavItemVisible(platformKeys!, { isAdmin: false })).toBe(false);
  });
});
