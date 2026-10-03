// @vitest-environment jsdom
/**
 * The app keeps the docs subdomain's "signed in" hint in step with the account: written while
 * signed in, cleared once nobody is, and never touched by the docs host itself, which cannot see
 * the session and would otherwise erase the hint it exists to read.
 */
import React from 'react';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { cleanup, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

type FakeAuth = { isAuthenticated: boolean; isLoading: boolean; user?: Record<string, unknown> | null };
const state = vi.hoisted(() => ({ auth: undefined as FakeAuth | undefined, host: 'livecontext.ai' }));
const hint = vi.hoisted(() => ({ write: vi.fn(), clear: vi.fn() }));
vi.mock('@/lib/providers/smart-providers', () => ({ useOptionalAuth: () => state.auth }));
vi.mock('@/lib/auth/siteSessionHint', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/auth/siteSessionHint')>()),
  writeSiteSessionHint: hint.write,
  clearSiteSessionHint: hint.clear,
}));

import SiteSessionHintWriter from '../SiteSessionHintWriter';

const originalLocation = window.location;
beforeEach(() => {
  hint.write.mockClear();
  hint.clear.mockClear();
  state.host = 'livecontext.ai';
  Object.defineProperty(window, 'location', {
    configurable: true,
    value: { ...originalLocation, get host() { return state.host; } },
  });
});
afterEach(() => {
  cleanup();
  Object.defineProperty(window, 'location', { configurable: true, value: originalLocation });
});

describe('SiteSessionHintWriter', () => {
  it('writes the account\'s initials while it is signed in', () => {
    state.auth = { isAuthenticated: true, isLoading: false, user: { name: 'Lucas Martin' } };
    render(<SiteSessionHintWriter />);

    expect(hint.write).toHaveBeenCalledWith('LM');
    expect(hint.clear).not.toHaveBeenCalled();
  });

  it('clears it once the app knows nobody is signed in', () => {
    state.auth = { isAuthenticated: false, isLoading: false };
    render(<SiteSessionHintWriter />);

    expect(hint.clear).toHaveBeenCalledTimes(1);
    expect(hint.write).not.toHaveBeenCalled();
  });

  it('decides nothing while the account is still being read', () => {
    state.auth = { isAuthenticated: false, isLoading: true };
    render(<SiteSessionHintWriter />);
    state.auth = undefined;
    render(<SiteSessionHintWriter />);

    expect(hint.write).not.toHaveBeenCalled();
    expect(hint.clear).not.toHaveBeenCalled();
  });

  it('is mounted for the whole cloud app, and only there (removing it would leave the docs header silently signed out)', () => {
    const providers = readFileSync(join(process.cwd(), 'app', 'providers.tsx'), 'utf8');

    expect(providers).toContain("import SiteSessionHintWriter from '../components/auth/SiteSessionHintWriter'");
    expect(providers).toMatch(/\{!IS_CE && <SiteSessionHintWriter \/>\}/);
  });

  it('regression: on the docs host, where the session is never visible, it never clears the hint', () => {
    state.host = 'docs.livecontext.ai';
    state.auth = { isAuthenticated: false, isLoading: false };
    render(<SiteSessionHintWriter />);

    expect(hint.clear).not.toHaveBeenCalled();
    expect(hint.write).not.toHaveBeenCalled();
  });
});
