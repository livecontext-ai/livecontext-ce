'use client';

import * as React from 'react';

import { useDisplayPreferences } from '@/hooks/useDisplayPreferences';
import { subscribeToDisplayTimeZone } from '@/lib/utils/timezone';

/**
 * Applies the signed-in person's stored display preferences, and re-renders what it wraps when
 * the zone actually changes.
 *
 * <p>The re-render is the part that is easy to leave out and impossible to notice in a test that
 * only checks the resolver. Timestamps are formatted by plain functions at ~250 call sites, none
 * of which subscribe to anything, so applying a zone is invisible to a tree that has already
 * painted. The case that matters is a cold browser: the first paint uses the device's zone (no
 * cookie yet), the profile lands a moment later saying the person pinned another one, and without
 * this the screen keeps showing the wrong zone until something unrelated happens to re-render.
 *
 * <p>Keyed on the zone rather than made reactive per call site: it fires only on a real change.
 * `applyDisplayTimeZone` does not return early - it always records the zone and the cookie - it
 * skips the NOTIFICATION when the zone already matches the one in use, and a caller that can
 * show the result itself (the Settings picker) skips it outright. In practice that leaves one
 * remount, early, on a device the person has not used before.
 *
 * <p>Mounted on BOTH shells - `/app` and the standalone `/workflows` builder. The builder renders
 * long lists of timestamps and is reachable directly, so leaving it out would make the account
 * preference silently not apply on the one route that shows the most dates.
 */
export default function DisplayPreferencesGate({ children }: { children?: React.ReactNode }) {
  useDisplayPreferences();

  // Keyed on a post-mount COUNTER, not on the zone itself.
  //
  // Re-keying is a remount, and this wraps AppShell - the same subtree whose comment in
  // `app/[locale]/app/layout.tsx` explains that IncidentStrip was moved OUT of it precisely
  // because remounting destroys "a running canvas, an SSE stream". So the remount has to happen
  // as rarely as it possibly can:
  //   - it never fires on the initial mount, whatever the first render resolved the zone to;
  //   - it fires only when `applyDisplayTimeZone` is handed a zone that differs from the one the
  //     page is ALREADY drawing dates in. A returning person carries the `LC_TZ` cookie, so their
  //     profile confirms the zone the first paint already used and nothing is notified;
  //   - which leaves one case: a browser with no cookie yet whose owner pinned a zone other than
  //     this device's. Everyone else never sees it.
  // The alternative to a remount is leaving already-painted dates in the wrong zone, because the
  // ~250 call sites are plain functions that subscribe to nothing.
  //
  // One ordering caveat, so the list above is not read as stronger than it is: the hook above runs
  // before this effect registers, so on a mount whose FIRST render already applies a differing
  // zone the notification is emitted with nobody subscribed and the remount is lost. Unreachable
  // as written - a warm cache means the module applied that zone earlier and the compare is then
  // false - but it is an ordering accident rather than a property, and worth seeing before the
  // hook is ever moved or made synchronous.
  const [epoch, setEpoch] = React.useState(0);
  React.useEffect(() => subscribeToDisplayTimeZone(() => setEpoch((n) => n + 1)), []);

  return <React.Fragment key={epoch}>{children}</React.Fragment>;
}
