'use client';

import { useMemo, useState } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import { useUserProfile } from '@/hooks/useUserProfile';
import { allTimezoneOptions } from '@/lib/schedule/timezoneOptions';
import { applyDisplayTimeZone, getBrowserTimeZone } from '@/lib/utils/timezone';
import { clearStoredAgendaTimezone } from '@/hooks/useAgendaPreferences';
import { SettingRow } from './SettingRow';

/**
 * The account-wide display time zone, in the General Preferences list next to Language and
 * Theme. It decides how EVERY absolute timestamp in the product reads (runs, agenda, tasks,
 * notifications) and how the emails the backend sends are timed, so it is stored on the
 * account rather than in this browser: it has to follow the person to their phone, and
 * auth-service has to be able to read it hours later when it writes them an email.
 *
 * <p>Two kinds of value:
 * <ul>
 *   <li>{@link DEVICE_VALUE} - follow whichever device is being used. The state an account
 *       starts in, and the one a person can always come back to.</li>
 *   <li>a zone id - a deliberate pick, which then survives every later session on any device
 *       (the browser report of a new session no longer overwrites it).</li>
 * </ul>
 */

/** Sentinel for "follow this device", which is the absence of a pick, not a zone. */
export const DEVICE_VALUE = '__device__';

export function TimeZonePreferenceRow() {
  const t = useTranslations('settings.preferences');
  const locale = useLocale();
  const { profile, fetchUserProfile } = useUserProfile();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(false);

  const stored = profile?.timeZone ?? null;
  const pinned = profile?.timeZoneExplicit === true;
  const deviceZone = getBrowserTimeZone() ?? 'UTC';

  // A pinned zone shows itself; anything else shows "follow this device", which is what the
  // app is then actually doing - an implicitly stored zone IS the device's.
  const value = pinned && stored ? stored : DEVICE_VALUE;
  const effective = pinned && stored ? stored : deviceZone;

  const options = useMemo(() => allTimezoneOptions(stored), [stored]);

  /**
   * The current time where the effective zone is, which is what makes a zone id concrete.
   *
   * <p>The time only. The offset is not printed because the `Select` in this same row already
   * names the zone in full, so it was the same fact twice. It was never STALE - it came from
   * `new Date()` and tracked daylight saving correctly - it was simply redundant, and an offset
   * is the form of that fact a reader cannot act on.
   */
  const nowThere = useMemo(() => {
    try {
      return new Intl.DateTimeFormat(locale, {
        timeZone: effective,
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
      }).format(new Date());
    } catch {
      return null;
    }
  }, [locale, effective]);

  const onChange = async (next: string) => {
    setSaving(true);
    setError(false);
    try {
      if (next === DEVICE_VALUE) {
        await unifiedApiService.reportDeviceTimeZone(deviceZone);
      } else {
        await unifiedApiService.reportExplicitTimeZone(next);
      }

      // Re-read, and believe the ANSWER rather than the request. The endpoint does not fail on a
      // zone it cannot parse: it drops the value and still answers 200 (a bad field must not fail
      // a context report that carries other fields). So a request that "succeeded" proves nothing,
      // and applying what we SENT would pin a phantom zone into a year-long cookie while the
      // account kept the old one.
      // `fetchUserProfile` is react-query's `refetch`, so the profile is under `.data`, not the
      // result itself. Reading the result directly would make `timeZone` undefined every time and
      // turn this check into a permanent, silent "refused".
      const refreshed = await fetchUserProfile();
      const saved = (refreshed as { data?: { timeZone?: string | null; timeZoneExplicit?: boolean } })
        ?.data;
      const storedNow = saved?.timeZone ?? null;
      const pinnedNow = saved?.timeZoneExplicit === true;
      const accepted = next === DEVICE_VALUE ? !pinnedNow : pinnedNow && storedNow === next;

      if (!accepted) {
        setError(true);
        return;
      }
      // Apply the zone the server actually stored, and QUIETLY.
      //
      // DisplayPreferencesGate wraps the /app shell so that a zone arriving late redraws the dates
      // already painted. That remount is the right answer when the preference turns up on its own,
      // and the wrong one here: this page is inside that shell, so notifying would tear down the
      // password form beside this row, the unsaved display name above it, and any stream running in
      // another panel, to redraw dates the person is not looking at. They ARE looking at this row,
      // which shows the chosen zone's current time; everywhere else is correct from the next
      // navigation. (An earlier version called window.location.reload() here, which did the same
      // damage more bluntly.)
      applyDisplayTimeZone(next === DEVICE_VALUE ? deviceZone : next, { notify: false });
      // And drop the agenda's own copy of the zone, or this row is the one place the preference
      // does not reach.
      //
      // The agenda persists its whole preference blob to localStorage on any change, so anybody
      // who had ever switched to month view carried the zone that was current at that moment,
      // and its hydrate prefers a stored valid zone over the account default - for ever. Pinning
      // Tokyo here moved every date in the product except the agenda, with nothing on screen to
      // explain the one surface that disagreed. Clearing the copy makes it fall back to the
      // account zone on its next mount. The helper was written for exactly this and was wired
      // only into sign-out and session expiry, which is the half of its own docblock that was done.
      clearStoredAgendaTimezone();
    } catch {
      // Say so instead of leaving a select showing a value the server never accepted: this
      // setting changes every date on screen, so a silent failure is a lie about all of them.
      setError(true);
    } finally {
      setSaving(false);
    }
  };

  return (
    <SettingRow
      title={t('timezone')}
      description={(
        <>
          <p className="text-sm text-theme-secondary">
            {t('timezoneDescription')}
            {nowThere ? ` ${t('timezoneNow', { time: nowThere })}` : ''}
          </p>
          {error && (
            <p className="text-sm text-red-500" role="alert">
              {t('timezoneSaveError')}
            </p>
          )}
        </>
      )}
    >
      <Select value={value} onValueChange={onChange} disabled={saving}>
        <SelectTrigger className="w-full" data-testid="timezone-select">
          <SelectValue placeholder={t('selectTimezone')} />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={DEVICE_VALUE}>{t('timezoneDevice', { zone: deviceZone })}</SelectItem>
          {options.map((zone) => (
            <SelectItem key={zone} value={zone}>
              {zone}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </SettingRow>
  );
}

export default TimeZonePreferenceRow;
