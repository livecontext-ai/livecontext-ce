/**
 * @vitest-environment jsdom
 *
 * The account-wide display time zone setting.
 *
 * What matters here is the THREE-WAY relationship the row has to get right: the value it shows
 * (pinned zone vs "follow this device"), what it sends (pin vs release), and the fact that a
 * refused save must not leave a select claiming a zone the server never stored - this one
 * setting decides how every date in the product reads.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';

const api = vi.hoisted(() => ({
  reportExplicitTimeZone: vi.fn(),
  reportDeviceTimeZone: vi.fn(),
}));
vi.mock('@/lib/api/unified-api-service', () => ({ unifiedApiService: api }));

const profileMock = vi.hoisted(() => ({
  profile: null as null | { timeZone?: string | null; timeZoneExplicit?: boolean },
  fetchUserProfile: vi.fn(),
}));
// The gate below applies the account's preferences on mount; this suite is about what a PICK
// does, so the hook must not also be fetching a profile.
vi.mock('@/hooks/useDisplayPreferences', () => ({ useDisplayPreferences: () => {} }));

vi.mock('@/hooks/useUserProfile', () => ({
  useUserProfile: () => profileMock,
}));

/**
 * `fetchUserProfile` is react-query's `refetch`: it answers a RESULT whose `.data` is the profile.
 * The mock has the same shape on purpose - reading the result directly instead of `.data` is a
 * mistake this component made once, and it turned the acceptance check below into a permanent
 * silent "refused".
 */
function serverAnswers(saved: { timeZone?: string | null; timeZoneExplicit?: boolean } | null) {
  profileMock.fetchUserProfile.mockImplementation(async () => {
    profileMock.profile = saved;
    return { data: saved };
  });
}

vi.mock('next-intl', () => ({
  useLocale: () => 'en',
  // Echo the key plus its params, so an assertion names the key it depends on and a renamed
  // key fails loudly instead of matching some other translated sentence.
  useTranslations: () => (key: string, params?: Record<string, unknown>) =>
    params ? `${key}:${Object.values(params).join(',')}` : key,
}));

// A native <select> jsdom can drive, same shim as the sibling settings tests.
vi.mock('@/components/ui/select', () => ({
  Select: ({ value, onValueChange, disabled, children }: {
    value?: string; onValueChange?: (v: string) => void; disabled?: boolean; children: React.ReactNode;
  }) => (
    <select value={value} disabled={disabled} onChange={(e) => onValueChange?.(e.target.value)}>
      {children}
    </select>
  ),
  SelectTrigger: () => null,
  SelectValue: () => null,
  SelectContent: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  SelectItem: ({ value, children }: { value: string; children: React.ReactNode }) => (
    <option value={value}>{children}</option>
  ),
}));

import { TimeZonePreferenceRow, DEVICE_VALUE } from '../TimeZonePreferenceRow';
import DisplayPreferencesGate from '@/components/lifecycle/DisplayPreferencesGate';
import {
  applyDisplayTimeZone,
  clearDisplayTimeZone,
  getClientTimeZone,
  getBrowserTimeZone,
} from '@/lib/utils/timezone';

function zoneSelect(): HTMLSelectElement {
  return screen.getByRole('combobox') as HTMLSelectElement;
}

/**
 * A spy on window.location.reload, asserted to stay UNCALLED.
 *
 * An earlier version of the row reloaded the page so already-painted dates would follow the new
 * zone. DisplayPreferencesGate now wraps this page and re-renders on a real change, so the reload
 * became a second mechanism for one job - and the destructive one, since it also threw away every
 * unsaved field here, including the password form.
 */
const reload = vi.fn();

beforeEach(() => {
  // Frozen, because two tests below compare a time the component rendered against the same time
  // recomputed in the assertion. A minute rollover between the two is a flake nobody can
  // reproduce; the sibling `formatStartedAt.zone.test.ts` pins its clock for exactly this and this
  // file did not. The instant is arbitrary and deliberately not on a minute boundary.
  vi.useFakeTimers({ now: new Date('2026-01-15T10:37:24.000Z'), shouldAdvanceTime: true });
  api.reportExplicitTimeZone.mockReset().mockResolvedValue(undefined);
  api.reportDeviceTimeZone.mockReset().mockResolvedValue(undefined);
  profileMock.fetchUserProfile.mockReset();
  serverAnswers(null);
  profileMock.profile = null;
  reload.mockReset();
  Object.defineProperty(window, 'location', {
    configurable: true,
    value: { ...window.location, reload },
  });
  clearDisplayTimeZone();
});

afterEach(() => {
  vi.useRealTimers();
  cleanup();
  clearDisplayTimeZone();
});

describe('what the row shows', () => {
  it('shows "follow this device" when no zone has been picked', () => {
    profileMock.profile = { timeZone: null, timeZoneExplicit: false };

    render(<TimeZonePreferenceRow />);

    expect(zoneSelect().value).toBe(DEVICE_VALUE);
  });

  it('still shows "follow this device" for a zone the BROWSER reported, which is what it is', () => {
    // An implicitly stored zone is this device's: presenting it as a pick would tell the person
    // they chose something they never chose, and hide the fact that it follows the device.
    profileMock.profile = { timeZone: 'Europe/Paris', timeZoneExplicit: false };

    render(<TimeZonePreferenceRow />);

    expect(zoneSelect().value).toBe(DEVICE_VALUE);
  });

  it('shows the zone itself once it was picked in Settings', () => {
    profileMock.profile = { timeZone: 'Asia/Tokyo', timeZoneExplicit: true };

    render(<TimeZonePreferenceRow />);

    expect(zoneSelect().value).toBe('Asia/Tokyo');
  });

  it('offers a pinned zone this runtime may not list, so the select is never blank over a real setting', () => {
    profileMock.profile = { timeZone: 'Antarctica/South_Pole', timeZoneExplicit: true };

    render(<TimeZonePreferenceRow />);

    // getByRole throws when the option is absent, which is the assertion.
    expect(screen.getByRole('option', { name: 'Antarctica/South_Pole' }).getAttribute('value'))
      .toBe('Antarctica/South_Pole');
  });
});

describe('picking a zone', () => {
  it('pins it, applies what the server stored, and leaves the re-render to the gate', async () => {
    profileMock.profile = { timeZone: null, timeZoneExplicit: false };
    serverAnswers({ timeZone: 'Asia/Tokyo', timeZoneExplicit: true });
    render(<TimeZonePreferenceRow />);

    fireEvent.change(zoneSelect(), { target: { value: 'Asia/Tokyo' } });

    await waitFor(() => expect(api.reportExplicitTimeZone).toHaveBeenCalledWith('Asia/Tokyo'));
    expect(api.reportDeviceTimeZone).not.toHaveBeenCalled();
    await waitFor(() => expect(getClientTimeZone()).toBe('Asia/Tokyo'));
    // The gate above this page re-renders the shell; this row does not reach for a reload.
    expect(reload).not.toHaveBeenCalled();
  });

  it('drops the agenda\'s own copy of the zone, or the agenda never follows', async () => {
    // The agenda keeps the zone in its localStorage preference blob and persists the WHOLE blob on
    // any change, so anybody who had once switched to month view froze the zone that was current
    // then; its hydrate prefers a stored valid zone over the account default. Pinning Tokyo here
    // moved every date in the product except the agenda, permanently, with nothing on screen to
    // explain the one surface that disagreed.
    //
    // The helper was written for this and its own docblock said so ("an account that later changes
    // its zone would never see the agenda follow") - then it was wired only into sign-out and
    // session expiry. Half the reason it exists was not connected to anything.
    window.localStorage.setItem('lc.agenda.preferences.v1', JSON.stringify({
      timezone: 'Europe/Paris',
      viewMode: 'month',
    }));
    profileMock.profile = { timeZone: null, timeZoneExplicit: false };
    serverAnswers({ timeZone: 'Asia/Tokyo', timeZoneExplicit: true });
    render(<TimeZonePreferenceRow />);

    fireEvent.change(zoneSelect(), { target: { value: 'Asia/Tokyo' } });

    await waitFor(() => expect(api.reportExplicitTimeZone).toHaveBeenCalledWith('Asia/Tokyo'));
    await waitFor(() => {
      const stored = JSON.parse(window.localStorage.getItem('lc.agenda.preferences.v1') ?? '{}');
      expect(stored, 'the stale zone must be gone').not.toHaveProperty('timezone');
      // And ONLY the zone: the view mode is a per-device habit, not an account preference.
      expect(stored.viewMode).toBe('month');
    });
  });

  it('releases the pick when going back to this device', async () => {
    profileMock.profile = { timeZone: 'Asia/Tokyo', timeZoneExplicit: true };
    serverAnswers({ timeZone: getBrowserTimeZone(), timeZoneExplicit: false });
    // Pinned FIRST, so the final assertion is a change rather than a restatement of the
    // starting state: with nothing applied and no cookie, the resolver already answers the
    // browser zone, so it held before this component did anything at all.
    applyDisplayTimeZone('Asia/Tokyo');
    expect(getClientTimeZone()).toBe('Asia/Tokyo');
    render(<TimeZonePreferenceRow />);

    fireEvent.change(zoneSelect(), { target: { value: DEVICE_VALUE } });

    await waitFor(() => expect(api.reportDeviceTimeZone).toHaveBeenCalledWith(getBrowserTimeZone()));
    expect(api.reportExplicitTimeZone).not.toHaveBeenCalled();
    await waitFor(() => expect(getClientTimeZone()).toBe(getBrowserTimeZone()));
  });
});

describe('when the save does not take', () => {
  it('says so when the request itself fails', async () => {
    profileMock.profile = { timeZone: null, timeZoneExplicit: false };
    api.reportExplicitTimeZone.mockRejectedValue(new Error('offline'));
    render(<TimeZonePreferenceRow />);

    fireEvent.change(zoneSelect(), { target: { value: 'Asia/Tokyo' } });

    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('timezoneSaveError'));
    // The row keeps reading from the profile, which still says nothing was pinned.
    expect(zoneSelect().value).toBe(DEVICE_VALUE);
    expect(reload).not.toHaveBeenCalled();
  });

  it('says so when the server ACCEPTS the request and stores nothing', async () => {
    // The harder half, and the one the first version got wrong: the endpoint drops a zone it
    // cannot parse and still answers 200, because a bad field must not fail a context report that
    // carries other fields. Believing the request would have pinned a phantom zone into a
    // year-long cookie while the account kept the old one, with no error anywhere.
    profileMock.profile = { timeZone: null, timeZoneExplicit: false };
    serverAnswers({ timeZone: null, timeZoneExplicit: false });
    render(<TimeZonePreferenceRow />);

    fireEvent.change(zoneSelect(), { target: { value: 'Antarctica/South_Pole' } });

    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('timezoneSaveError'));
    expect(getClientTimeZone()).not.toBe('Antarctica/South_Pole');
    expect(reload).not.toHaveBeenCalled();
  });

  it('says so when releasing the pin leaves the account still pinned', async () => {
    profileMock.profile = { timeZone: 'Asia/Tokyo', timeZoneExplicit: true };
    serverAnswers({ timeZone: 'Asia/Tokyo', timeZoneExplicit: true });
    render(<TimeZonePreferenceRow />);

    fireEvent.change(zoneSelect(), { target: { value: DEVICE_VALUE } });

    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('timezoneSaveError'));
    expect(reload).not.toHaveBeenCalled();
  });
});

describe('what a pick costs the page it is made on', () => {
  /** Holds state a person would be upset to lose, and reports whether it survived. */
  function UnsavedWork() {
    const [draft, setDraft] = React.useState('');
    return (
      <input
        aria-label="draft"
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
      />
    );
  }

  it('does NOT throw away the unsaved fields beside it', async () => {
    // This row lives inside the /app shell, and DisplayPreferencesGate wraps that shell and
    // REMOUNTS it whenever an applied zone differs from the one in use. Notifying from here
    // therefore destroyed the password form next to this row, the unsaved display name above it,
    // and any stream running in another panel - to redraw dates the person was not looking at.
    //
    // Neither existing suite could see it: the gate's own tests render it around a plain child,
    // and this one used to render the row with no gate at all. The bug lived exactly in the gap.
    profileMock.profile = { timeZone: null, timeZoneExplicit: false };
    serverAnswers({ timeZone: 'Asia/Tokyo', timeZoneExplicit: true });
    render(
      <DisplayPreferencesGate>
        <UnsavedWork />
        <TimeZonePreferenceRow />
      </DisplayPreferencesGate>
    );
    const draft = screen.getByLabelText('draft') as HTMLInputElement;
    fireEvent.change(draft, { target: { value: 'half-typed password' } });

    fireEvent.change(zoneSelect(), { target: { value: 'Asia/Tokyo' } });

    await waitFor(() => expect(api.reportExplicitTimeZone).toHaveBeenCalledWith('Asia/Tokyo'));
    await waitFor(() => expect(getClientTimeZone()).toBe('Asia/Tokyo'));
    expect((screen.getByLabelText('draft') as HTMLInputElement).value)
      .toBe('half-typed password');
  });

  it('still applies the zone, so the row can show the time there', async () => {
    // The quiet apply must stay an APPLY: skipping it to protect the page would leave the
    // account saying Tokyo and this browser reading somewhere else until the next reload.
    profileMock.profile = { timeZone: null, timeZoneExplicit: false };
    serverAnswers({ timeZone: 'America/Denver', timeZoneExplicit: true });
    render(<TimeZonePreferenceRow />);

    fireEvent.change(zoneSelect(), { target: { value: 'America/Denver' } });

    await waitFor(() => expect(getClientTimeZone()).toBe('America/Denver'));
  });
});

describe('the feedback that replaces the re-render', () => {
  it('shows the chosen zone\'s current time, which is the whole argument for applying quietly', () => {
    // The row deliberately does NOT notify the gate (it would tear down the page it sits on), and
    // the comment justifying that says "the row shows the new zone's current time instead". If that
    // line ever stopped moving, a person would change their zone and see nothing happen at all -
    // so the compensation has to be pinned, not just asserted in prose.
    profileMock.profile = { timeZone: 'Asia/Tokyo', timeZoneExplicit: true };

    render(<TimeZonePreferenceRow />);

    // The echo translator renders "timezoneNow:<time>", so the parameter is readable here.
    const shown = screen.getByText(/timezoneNow:/).textContent ?? '';
    const inTokyo = new Intl.DateTimeFormat('en', {
      timeZone: 'Asia/Tokyo', hour: '2-digit', minute: '2-digit', hour12: false,
    }).format(new Date());
    expect(shown).toContain(inTokyo);
    // `toContain` alone passed on the pre-change component too, which printed "09:30 GMT+9" and
    // contains "09:30". Without this line nothing in the suite would fail if the offset came back.
    expect(shown).not.toMatch(/GMT|UTC|[A-Z]{3,4}$/);
    // The clock is frozen for this file (see the fake timers in beforeEach): the component reads
    // the time at render and this assertion reads it again, so without that freeze a render that
    // straddles a minute rollover fails for no reason anybody could reproduce.
  });

  it('shows THIS DEVICE time when nothing is pinned, so the two states are distinguishable', () => {
    // The device zone this asserts against is NOT this machine.
    //
    // The assertion used to recompute the expected string with `getBrowserTimeZone()`, the same
    // helper the component reads, so a component that hardcoded UTC passed on a UTC runner - which
    // every CI runner is, as `lib/utils/__tests__/timezone.test.ts` says in its own docblock before
    // stubbing the zone for exactly this reason. A zone with a non-zero offset is the only version
    // of this test that can fail.
    //
    // Asserted as a DIFFERENCE from UTC rather than against a fixed string: the hour shown is
    // the behaviour, and it is the only thing the row prints now that the offset label is gone.
    profileMock.profile = { timeZone: null, timeZoneExplicit: false };

    render(<TimeZonePreferenceRow />);

    const shown = screen.getByText(/timezoneNow:/).textContent ?? '';
    const device = getBrowserTimeZone() ?? 'UTC';
    const here = new Intl.DateTimeFormat('en', {
      timeZone: device,
      hour: '2-digit', minute: '2-digit', hour12: false,
    }).format(new Date());

    expect(shown).toContain(here);
    expect(shown).not.toMatch(/GMT|UTC|[A-Z]{3,4}$/);
    // And that it is the DEVICE zone rather than a hardcoded one: pinning a zone the device is not
    // in must change what this row shows. The assertion above cannot tell those apart on a UTC
    // runner, and this one can.
    cleanup();
    profileMock.profile = { timeZone: 'Asia/Tokyo', timeZoneExplicit: true };
    render(<TimeZonePreferenceRow />);
    const pinned = screen.getByText(/timezoneNow:/).textContent ?? '';

    expect(pinned).not.toBe(shown);
  });
});
