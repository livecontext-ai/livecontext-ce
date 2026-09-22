'use client';

import { useSyncExternalStore } from 'react';
import { useOptionalAuth } from '@/lib/providers/smart-providers';

/**
 * Marketplace demo-install mode: platform ADMIN only, one browser at a time.
 *
 * Filming or demoing the marketplace means showing an install, and the person
 * doing the demo is normally the publisher, who cannot install their own work:
 * `PublicationAcquisitionHelper.isOwnPublication` refuses on
 * `tenantId.equals(publisherId)`, and that tenantId is the USER id, so switching
 * organization does not get around it either. The only other route is a second
 * account, which means real rows, real quota and real credentials to connect.
 *
 * With this mode on, every marketplace card offers Install again and a click
 * plays the REAL install animation and the REAL modal, while the acquire call is
 * never made. Nothing is created, nothing is billed, no receipt is written.
 *
 * Deliberately stored in localStorage rather than as a server-side preference:
 * - it changes what ONE presenter sees for the length of one recording, so it
 *   has no business travelling with the account or reaching another device;
 * - there is no backend write, therefore no way for it to leak into real data;
 * - and it disappears by clearing site data if it is ever forgotten.
 *
 * The ADMIN check is applied on READ, not just when the toggle is written, so a
 * non-admin who sets the key by hand still gets the ordinary marketplace.
 */

const STORAGE_KEY = 'lc.marketplace.demo-install';

/** Same-tab notification. `storage` only fires in the OTHER tabs. */
const CHANGE_EVENT = 'lc:marketplace-demo-install';

function readFlag(): boolean {
  if (typeof window === 'undefined') return false;
  try {
    return window.localStorage.getItem(STORAGE_KEY) === '1';
  } catch {
    // Private mode / site data blocked: behave as off rather than throw in a render.
    return false;
  }
}

/**
 * Turn the mode on or off for this browser. Callers must already have checked
 * that the user is an ADMIN; reads re-check anyway.
 */
export function setMarketplaceDemoInstall(enabled: boolean): void {
  if (typeof window === 'undefined') return;
  try {
    if (enabled) window.localStorage.setItem(STORAGE_KEY, '1');
    else window.localStorage.removeItem(STORAGE_KEY);
  } catch {
    // Storage is blocked (private mode, site data off). Nothing was persisted and
    // `readFlag` re-reads storage, so the switch will not move: notify anyway so
    // subscribers re-read and settle on the truth rather than on a stale render.
  }
  window.dispatchEvent(new Event(CHANGE_EVENT));
}

function subscribe(onChange: () => void): () => void {
  if (typeof window === 'undefined') return () => {};
  const onStorage = (e: StorageEvent) => {
    if (e.key === null || e.key === STORAGE_KEY) onChange();
  };
  window.addEventListener(CHANGE_EVENT, onChange);
  window.addEventListener('storage', onStorage);
  return () => {
    window.removeEventListener(CHANGE_EVENT, onChange);
    window.removeEventListener('storage', onStorage);
  };
}

/** Server render has no localStorage, and the mode is never on by default. */
function getServerSnapshot(): boolean {
  return false;
}

/**
 * The raw toggle, WITHOUT the admin check. Use this only for the settings
 * control itself, which is already inside an admin-gated page; every consumer
 * that changes what the marketplace does must use {@link useMarketplaceDemoInstall}.
 */
export function useMarketplaceDemoInstallFlag(): boolean {
  return useSyncExternalStore(subscribe, readFlag, getServerSnapshot);
}

/**
 * True when the marketplace should pretend every publication is installable and
 * simulate installs. Off for everyone but a platform ADMIN who turned it on.
 *
 * Safe outside an auth provider (the public preview surfaces mount these cards
 * too): `useOptionalAuth` returns undefined there and the mode stays off.
 */
export function useMarketplaceDemoInstall(): boolean {
  const flag = useMarketplaceDemoInstallFlag();
  const auth = useOptionalAuth();
  return flag && (auth?.hasRole('ADMIN') ?? false);
}
