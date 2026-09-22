import { describe, expect, it, vi } from 'vitest';

// Self-hosted: the page configures the platform's providers, an admin matter; there is no
// own-keys panel to offer a member.
vi.mock('@/lib/edition', () => ({ IS_CE: true, IS_MANAGED_CLOUD: false }));

import { isSettingsNavItemVisible, settingsNavItems } from '../settingsNavItems';

describe('AI providers entry on a self-hosted install', () => {
  const entry = settingsNavItems.find((i) => i.href === '/app/settings/ai-providers');

  it('stays admin-only', () => {
    expect(entry).toBeDefined();
    expect(isSettingsNavItemVisible(entry!, { isAdmin: false })).toBe(false);
    expect(isSettingsNavItemVisible(entry!, { isAdmin: true })).toBe(true);
  });
});
