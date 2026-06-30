import { describe, expect, it } from 'vitest';
import { settingsNavItems } from '../settingsNavItems';

describe('settingsNavItems - cloud-link merged into cloud-account', () => {
  const hrefs = settingsNavItems.map((i) => i.href);

  it('exposes the unified "Cloud" entry', () => {
    const cloud = settingsNavItems.find((i) => i.href === '/app/settings/cloud-account');
    expect(cloud).toBeTruthy();
    expect(cloud?.label).toBe('Cloud');
    expect(cloud?.adminOnly).toBe(true);
  });

  it('no longer carries a separate legacy cloud-link entry', () => {
    expect(hrefs).not.toContain('/app/settings/cloud-link');
  });

  it('has exactly one cloud-* settings entry (the fused page)', () => {
    expect(hrefs.filter((h) => h.startsWith('/app/settings/cloud')).length).toBe(1);
  });
});
