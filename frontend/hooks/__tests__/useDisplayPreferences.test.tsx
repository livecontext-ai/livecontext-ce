/**
 * @vitest-environment jsdom
 *
 * useDisplayPreferences: the account, not the browser, decides which zone the app displays.
 *
 * Every case below is one an audit found reachable in the first version of this hook, so each is
 * written as the situation rather than as the code path:
 *  - the previous person's pinned zone must not survive into the next person's session;
 *  - "follow this device" must keep following the device, on a self-hosted install too;
 *  - a profile that has not arrived is not an answer, and must not be reported as one.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, waitFor, cleanup } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const api = vi.hoisted(() => ({ reportProfileContext: vi.fn() }));
vi.mock('@/lib/api/unified-api-service', () => ({ unifiedApiService: api }));

const profileMock = vi.hoisted(() => ({
  profile: null as null | { timeZone?: string | null; timeZoneExplicit?: boolean },
}));
vi.mock('@/hooks/useUserProfile', () => ({ useUserProfile: () => profileMock }));

const authMock = vi.hoisted(() => ({ user: { sub: 'user-1' } as { sub: string } | null }));
vi.mock('@/hooks/useAuthGuard', () => ({ useAuthGuard: () => authMock }));

// The device this test pretends to run on. The real reader is exercised by timezone.test.ts;
// pinning it here keeps these cases independent of the machine the suite runs on.
const deviceMock = vi.hoisted(() => ({ zone: 'Europe/Paris' as string | undefined }));
vi.mock('@/lib/utils/timezone', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/utils/timezone')>();
  return { ...actual, getBrowserTimeZone: () => deviceMock.zone };
});

import { useDisplayPreferences } from '../useDisplayPreferences';
import {
  clearDisplayTimeZone,
  getClientTimeZone,
  applyDisplayTimeZone,
  DISPLAY_TIME_ZONE_COOKIE,
} from '@/lib/utils/timezone';

function Harness() {
  useDisplayPreferences();
  return null;
}

function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <Harness />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  api.reportProfileContext.mockReset().mockResolvedValue(undefined);
  profileMock.profile = null;
  authMock.user = { sub: 'user-1' };
  deviceMock.zone = 'Europe/Paris';
  clearDisplayTimeZone();
});

afterEach(() => {
  cleanup();
  clearDisplayTimeZone();
});

describe('applying the account answer', () => {
  it('applies a pinned zone, so it follows the person to this device', async () => {
    profileMock.profile = { timeZone: 'Asia/Tokyo', timeZoneExplicit: true };

    mount();

    await waitFor(() => expect(getClientTimeZone()).toBe('Asia/Tokyo'));
  });

  it('applies THIS DEVICE when nothing is pinned: "no pick" is an answer too', async () => {
    profileMock.profile = { timeZone: null, timeZoneExplicit: false };

    mount();

    await waitFor(() => expect(getClientTimeZone()).toBe('Europe/Paris'));
  });

  it('overrides a cookie left by the PREVIOUS person, who pinned another zone', async () => {
    // A signs out having pinned Tokyo; the LC_TZ cookie lives a year and belongs to the browser,
    // not the account. B signs in here with no preference of their own. Before this, B read every
    // date in A's zone for their whole first session.
    applyDisplayTimeZone('Asia/Tokyo');
    profileMock.profile = { timeZone: null, timeZoneExplicit: false };

    mount();

    await waitFor(() => expect(getClientTimeZone()).toBe('Europe/Paris'));
  });

  it('applies nothing while the profile has not arrived: not loaded is not an answer', () => {
    applyDisplayTimeZone('Asia/Tokyo');
    profileMock.profile = null;

    mount();

    // Still the cookie's value, because nothing authoritative has been said yet.
    expect(getClientTimeZone()).toBe('Asia/Tokyo');
  });
});

describe('what the apply leaves behind for the next page load', () => {
  it('mirrors it into the cookie, which is what makes the NEXT first paint right', async () => {
    // The apply only fixes this page load. The cookie is what a cold browser reads before any
    // profile arrives, so without it every visit starts in the device zone and flips once the
    // account answers - a visible one-render flash on every route outside the /app shell.
    profileMock.profile = { timeZone: 'Asia/Tokyo', timeZoneExplicit: true };

    mount();

    await waitFor(() =>
      expect(document.cookie).toContain(`${DISPLAY_TIME_ZONE_COOKIE}=Asia%2FTokyo`)
    );
  });

  it('applies the STORED zone when this device will not name one', async () => {
    // Intl reports no zone (a stripped runtime, a locked-down browser) and nothing is pinned.
    // The hook used to apply nothing at all here, which left a cookie from the previous person
    // on a shared browser in charge - the exact leak it exists to close.
    deviceMock.zone = undefined;
    profileMock.profile = { timeZone: 'America/Denver', timeZoneExplicit: false };

    mount();

    await waitFor(() => expect(getClientTimeZone()).toBe('America/Denver'));
  });

  it('falls all the way to UTC when there is no device zone and nothing stored', async () => {
    deviceMock.zone = undefined;
    profileMock.profile = { timeZone: null, timeZoneExplicit: false };

    mount();

    await waitFor(() => expect(getClientTimeZone()).toBe('UTC'));
  });
});

describe('keeping the stored zone in step with the device', () => {
  it('reports the device zone when the account has none stored', async () => {
    profileMock.profile = { timeZone: null, timeZoneExplicit: false };

    mount();

    await waitFor(() =>
      expect(api.reportProfileContext).toHaveBeenCalledWith({ timeZone: 'Europe/Paris' })
    );
  });

  it('reports again when the person has MOVED, which is what makes "follow the device" true', async () => {
    // The self-hosted case the first version got wrong: it captured once and never again, so an
    // account was frozen in the zone of whichever device signed in first, forever.
    profileMock.profile = { timeZone: 'Europe/Paris', timeZoneExplicit: false };
    deviceMock.zone = 'America/New_York';

    mount();

    await waitFor(() =>
      expect(api.reportProfileContext).toHaveBeenCalledWith({ timeZone: 'America/New_York' })
    );
  });

  it('reports nothing when the stored zone already matches the device', async () => {
    profileMock.profile = { timeZone: 'Europe/Paris', timeZoneExplicit: false };

    mount();

    await waitFor(() => expect(getClientTimeZone()).toBe('Europe/Paris'));
    expect(api.reportProfileContext).not.toHaveBeenCalled();
  });

  it('never reports over a PINNED zone, however far the device has travelled', async () => {
    profileMock.profile = { timeZone: 'Asia/Tokyo', timeZoneExplicit: true };
    deviceMock.zone = 'America/New_York';

    mount();

    await waitFor(() => expect(getClientTimeZone()).toBe('Asia/Tokyo'));
    expect(api.reportProfileContext).not.toHaveBeenCalled();
  });

  it('reports nothing before the profile arrives, so a page load is not a write', () => {
    profileMock.profile = null;

    mount();

    expect(api.reportProfileContext).not.toHaveBeenCalled();
  });

  it('says nothing when the browser will not name a zone', async () => {
    deviceMock.zone = undefined;
    profileMock.profile = { timeZone: null, timeZoneExplicit: false };

    mount();

    await waitFor(() => expect(api.reportProfileContext).not.toHaveBeenCalled());
  });
});
