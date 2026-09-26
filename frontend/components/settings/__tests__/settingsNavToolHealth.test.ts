import { describe, expect, it } from 'vitest';
import { settingsNavItems } from '../settingsNavItems';

/**
 * Tool Health aggregates tool failures ACROSS TENANTS, so it exposes one
 * customer's error messages to whoever can open it. The adminOnly flag is the
 * only thing standing between that data and every signed-in user, and the
 * generic visibility test only proves the flag is honoured once it is set.
 * This pins that it stays set.
 */
describe('settingsNavItems - tool health', () => {
  const entry = settingsNavItems.find((i) => i.href === '/app/settings/tool-health');

  it('is present in the settings navigation', () => {
    expect(entry).toBeTruthy();
    expect(entry?.label).toBe('Tool Health');
  });

  it('is admin only, because it exposes cross-tenant failure data', () => {
    expect(entry?.adminOnly).toBe(true);
  });

  it('stays available on self-hosted, where an admin has the same failures to chase', () => {
    expect(entry?.hiddenInCE).toBeUndefined();
  });
});
