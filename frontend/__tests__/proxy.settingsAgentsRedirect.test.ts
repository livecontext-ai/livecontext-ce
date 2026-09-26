/**
 * Settings > Agents & Chat was removed: it rendered a second copy of the agent & Orbi
 * defaults editor, which lives only on the Agents page "Settings" tab. The old address
 * must land on that tab in the user's OWN locale. A page-level redirect() sent an
 * unprefixed path that the proxy then pinned to /en, so a French bookmark opened the
 * tab in English.
 */
import { describe, it, expect, vi } from 'vitest';
import { NextRequest } from 'next/server';

// next-intl's ESM middleware build does not resolve under the vitest node environment;
// the branch under test returns before reaching it.
vi.mock('next-intl/middleware', () => ({
  default: () => () => undefined,
}));

import { proxy } from '@/proxy';

function location(path: string): string | null {
  const response = proxy(new NextRequest(`https://livecontext.ai${path}`)) as Response | undefined;
  return response?.headers.get('location') ?? null;
}

describe('proxy /app/settings/agents redirect', () => {
  it('keeps the locale of a localized bookmark', () => {
    expect(location('/fr/app/settings/agents')).toBe(
      'https://livecontext.ai/fr/app/agent?view=settings',
    );
  });

  it('sends the English address to the English tab', () => {
    expect(location('/en/app/settings/agents')).toBe(
      'https://livecontext.ai/en/app/agent?view=settings',
    );
  });

  it('lands an unprefixed address on the tab, not on a 404', () => {
    expect(location('/app/settings/agents')).toBe(
      'https://livecontext.ai/app/agent?view=settings',
    );
  });

  it('always opens the Settings tab, whatever query the old link carried', () => {
    expect(location('/de/app/settings/agents?tab=x')).toBe(
      'https://livecontext.ai/de/app/agent?view=settings',
    );
  });

  it('leaves the sibling settings pages alone', () => {
    expect(location('/fr/app/settings/agent-debug') ?? '').not.toContain('/app/agent?view=settings');
  });
});
