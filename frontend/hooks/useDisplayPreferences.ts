'use client';

import { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useUserProfile } from '@/hooks/useUserProfile';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import { applyDisplayTimeZone, getBrowserTimeZone, isValidTimeZone } from '@/lib/utils/timezone';

/**
 * Makes the signed-in person's stored display preferences take effect, and keeps the stored zone
 * in step with the device when they have not pinned one.
 *
 * <p>Two jobs, both needed for the same reason - the stored zone is the ONLY copy that follows
 * someone across devices, while a browser only ever knows where THIS device is:
 *
 * <ol>
 *   <li>APPLY, on every profile arrival: the account's answer becomes the zone every timestamp in
 *       the app is formatted in. A pinned zone applies itself; NO pinned zone applies this
 *       device's, because "no pick" means "follow the device" and that is an answer too. Applying
 *       in both cases is what makes the profile AUTHORITATIVE the moment it lands - otherwise the
 *       `LC_TZ` cookie, which lives a year and is per browser rather than per account, would keep
 *       serving the PREVIOUS person's pinned zone to the next one who signs in here.</li>
 *   <li>REPORT, when the device disagrees with what is stored implicitly: so the emails this
 *       account receives are timed where its owner actually is. Guarded on
 *       {@code timeZoneExplicit}, and implicit on the wire, so a pinned zone is never touched by
 *       either side.</li>
 * </ol>
 *
 * <p>Runs in BOTH editions, unlike the cloud-only lifecycle context report. That report also
 * refreshes the implicit zone, but only on cloud; without the report below, a self-hosted account
 * would be frozen in the zone of whichever device signed in first, forever, including after its
 * owner moved. The two overlap on cloud, which is harmless: the write is one conditional UPDATE
 * that reports whether it changed anything.
 *
 * <p>Deliberately NOT responsible for the language: next-intl renders the UI from the route and
 * the `NEXT_LOCALE` cookie, and the pickers push an explicit choice through
 * {@code reportExplicitLocaleChoice}. Re-deriving the language here would fight the router.
 *
 * <p>Reads the profile through {@link useUserProfile}, whose query key is shared, so mounting this
 * costs no extra request on a screen that already shows the profile.
 */
export function useDisplayPreferences(): void {
  const { user } = useAuthGuard();
  const { profile } = useUserProfile();

  const stored = (profile as { timeZone?: string | null; timeZoneExplicit?: boolean } | null) ?? null;
  const storedTimeZone = stored?.timeZone ?? null;
  const pinned = stored?.timeZoneExplicit === true;
  const deviceTimeZone = getBrowserTimeZone();

  // What this account says the zone is. Null until the profile arrives: "not loaded yet" must
  // never be read as an answer, or the cookie would be overwritten before the account has spoken.
  //
  // With no pick, it falls through the DEVICE zone to the stored one and finally to UTC, rather
  // than stopping at undefined. `getBrowserTimeZone()` answers undefined when Intl reports no zone,
  // and an unpinned account then had nothing to apply, so the apply below was skipped and an LC_TZ
  // cookie left by the PREVIOUS person on a shared browser kept serving this one, which is the leak
  // this hook exists to close. The stored zone is the better guess when the device has none (it is
  // at least this account's), and UTC is the honest last resort.
  // A pinned zone the RUNTIME cannot use falls through to the device zone rather than being
  // applied and silently ignored. `applyDisplayTimeZone` returns early on an unusable id, so the
  // pinned branch could hand it a legacy alias, nothing would be applied, and the year-long cookie
  // from the last person on this browser kept serving - which is the same leak as an unpinned
  // account with no device zone, already handled on the line below. Covered there and not here.
  const usablePin = pinned && storedTimeZone && isValidTimeZone(storedTimeZone) ? storedTimeZone : null;
  const effectiveTimeZone = !stored
    ? null
    : (usablePin ?? deviceTimeZone ?? storedTimeZone ?? 'UTC');

  useEffect(() => {
    if (effectiveTimeZone) applyDisplayTimeZone(effectiveTimeZone);
  }, [effectiveTimeZone]);

  // Report only what would actually change: no pick, a device zone to report, and a stored value
  // that differs from it. The query key carries the account AND the zone, so moving device or
  // signing a different person in re-reports, while a reload of the same pair does not.
  const shouldReport = !!stored && !pinned && !!deviceTimeZone && storedTimeZone !== deviceTimeZone;

  useQuery({
    queryKey: ['user', 'device-time-zone', user?.sub ?? '', deviceTimeZone ?? ''],
    queryFn: async () => {
      await unifiedApiService.reportProfileContext({ timeZone: deviceTimeZone });
      return true;
    },
    enabled: shouldReport,
    staleTime: Infinity,
    gcTime: Infinity,
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    refetchOnMount: false,
  });
}
