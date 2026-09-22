import { describe, expect, it, vi } from 'vitest';

// Managed cloud. The edition constants are resolved at module load, so the self-hosted
// halves of these rules live in the .ce sibling file.
vi.mock('@/lib/edition', () => ({ IS_CE: false, IS_MANAGED_CLOUD: true }));

import {
  isSettingsNavItemVisible,
  settingsNavItems,
  type SettingsNavItem,
} from '../settingsNavItems';
import { BadgeCheck } from 'lucide-react';

/**
 * `isSettingsNavItemVisible` exists because the settings nav and the global search bar
 * used to carry the same four-clause expression, and a gating flag added to one would
 * have silently left the other advertising a page it must not. That anti-drift claim is
 * worth exactly what this file asserts, so every clause is pinned here rather than only
 * through a Playwright spec that needs a whole CE stack to run.
 */
function item(overrides: Partial<SettingsNavItem> = {}): SettingsNavItem {
  return { href: '/app/settings/x', label: 'X', icon: BadgeCheck, ...overrides };
}

describe('isSettingsNavItemVisible on managed cloud', () => {
  const asAdmin = { isAdmin: true };
  const asUser = { isAdmin: false };

  it('shows an ordinary entry to everyone', () => {
    expect(isSettingsNavItemVisible(item(), asUser)).toBe(true);
    expect(isSettingsNavItemVisible(item(), asAdmin)).toBe(true);
  });

  it('hides a `hidden` entry from everyone, admin included', () => {
    expect(isSettingsNavItemVisible(item({ hidden: true }), asAdmin)).toBe(false);
  });

  it('shows an adminOnly entry to admins only', () => {
    expect(isSettingsNavItemVisible(item({ adminOnly: true }), asUser)).toBe(false);
    expect(isSettingsNavItemVisible(item({ adminOnly: true }), asAdmin)).toBe(true);
  });

  it('shows a hiddenInCE entry here, and hides a ceOnly one', () => {
    expect(isSettingsNavItemVisible(item({ hiddenInCE: true }), asAdmin)).toBe(true);
    expect(isSettingsNavItemVisible(item({ ceOnly: true }), asAdmin)).toBe(false);
  });

  it('shows a managedCloudOnly entry here', () => {
    expect(isSettingsNavItemVisible(item({ managedCloudOnly: true }), asAdmin)).toBe(true);
  });

  it('applies every clause, not just the first that matches', () => {
    // adminOnly AND managedCloudOnly: passing the edition must not excuse the role.
    expect(isSettingsNavItemVisible(
      item({ adminOnly: true, managedCloudOnly: true }), asUser)).toBe(false);
  });
});

describe('the Verified Accounts entry', () => {
  const entry = settingsNavItems.find((i) => i.href === '/app/settings/verified-accounts');

  it('is registered in the nav', () => {
    expect(entry).toBeTruthy();
    // The label the CE nav-gating e2e asserts absent (HIDDEN_IN_CE_LABELS in
    // e2e/ce/ce-settings-nav-gating-ui.spec.ts): renaming it here without renaming it
    // there reddens that spec.
    expect(entry?.label).toBe('Verified Accounts');
  });

  it('is gated adminOnly AND managedCloudOnly', () => {
    expect(entry?.adminOnly).toBe(true);
    expect(entry?.managedCloudOnly).toBe(true);
  });

  it('is NOT gated with the binary hiddenInCE', () => {
    // hiddenInCE reads IS_CE, which is false on a SELF-HOSTED ENTERPRISE install - where
    // the backend gate (isManagedCloud()) is false and every submit answers 503. Using
    // it here would advertise a dead page to those customers.
    expect(entry?.hiddenInCE).toBeUndefined();
  });

  it('is visible to a cloud admin, and to nobody else', () => {
    expect(isSettingsNavItemVisible(entry!, { isAdmin: true })).toBe(true);
    expect(isSettingsNavItemVisible(entry!, { isAdmin: false })).toBe(false);
  });
});
