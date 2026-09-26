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

describe('settingsNavItems - no Agents & Chat entry', () => {
  // The agent & Orbi defaults editor lives only on the Agents page "Settings" tab
  // (/app/agent?view=settings). A settings entry rendered the very same editor a second
  // time, so users met two places to configure one thing and wondered which one won.
  it('does not link a second copy of the agent defaults editor', () => {
    expect(settingsNavItems.map((i) => i.href)).not.toContain('/app/settings/agents');
    expect(settingsNavItems.map((i) => i.label)).not.toContain('Agents & Chat');
  });
});
