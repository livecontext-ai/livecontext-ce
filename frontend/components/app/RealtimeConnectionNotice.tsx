'use client';

import { useEffect, useState } from 'react';
import { useTranslations } from 'next-intl';
import { RefreshCw, WifiOff, X } from 'lucide-react';
import { useWebSocketStatus, wsClient } from '@/lib/websocket';

/** A reconnect shorter than this is routine (a deploy, a network blip) and not worth a word. */
export const REALTIME_NOTICE_DELAY_MS = 8000;

/**
 * Remembers for a day, across tabs, that the reader dismissed the "never connected" variant.
 * That state is usually permanent (a proxy that blocks WebSocket upgrades on a self-hosted
 * install), so re-showing it on every page and every tab would only nag.
 */
const UNAVAILABLE_DISMISSED_KEY = 'lc.realtimeNotice.unavailableDismissedAt';
export const UNAVAILABLE_DISMISS_TTL_MS = 24 * 60 * 60 * 1000;

function readUnavailableDismissed(): boolean {
  try {
    const at = Number(window.localStorage.getItem(UNAVAILABLE_DISMISSED_KEY));
    return Number.isFinite(at) && at > 0 && Date.now() - at < UNAVAILABLE_DISMISS_TTL_MS;
  } catch {
    return false; // storage blocked: the notice simply shows again next page
  }
}

function writeUnavailableDismissed(): void {
  try {
    window.localStorage.setItem(UNAVAILABLE_DISMISSED_KEY, String(Date.now()));
  } catch {
    // storage blocked: dismissal lasts for this page only
  }
}

/**
 * Says so when live updates have been down for a while.
 *
 * Before this, a tab whose WebSocket had dropped looked exactly like a healthy one: a
 * chat reply that never arrived and an app run stuck on "running" were the only signs,
 * and nothing told the user that a reload would show the truth.
 *
 * "Down" is `reconnecting` (only ever set after a failed or refused connection, including
 * a refused FIRST one), or `connecting` again after the page HAD a live connection. Pages
 * that never connect (logged out, public) stay `disconnected` and never show it. It sits
 * under the header, lets clicks through everywhere but its own card (a chat composer or a
 * bottom toolbar must stay usable during an outage), and can be dismissed until the next
 * outage. A page that never got a live connection at all, for a reason other than the server
 * refusing it at its connection cap, gets its own wording (the connection itself may be
 * blocked); its dismissal is remembered for a day. "Ever connected" is read from the socket
 * client, which lives as long as the page, so moving between layouts does not reset it.
 */
export default function RealtimeConnectionNotice() {
  const t = useTranslations('realtime');
  const status = useWebSocketStatus();
  const [wasConnected, setWasConnected] = useState(() => wsClient.hasEverConnected);
  const [visible, setVisible] = useState(false);
  const [dismissed, setDismissed] = useState(false);

  useEffect(() => {
    if (status === 'connected') {
      setWasConnected(true);
      setDismissed(false); // the next outage is a new one
    }
  }, [status]);
  const neverConnected = !wasConnected && !wsClient.lastFailureWasRefusal;
  const down = status === 'reconnecting' || (wasConnected && status === 'connecting');

  useEffect(() => {
    if (!down) {
      setVisible(false);
      return;
    }
    const timer = setTimeout(() => setVisible(true), REALTIME_NOTICE_DELAY_MS);
    return () => clearTimeout(timer);
  }, [down]);

  if (!visible || dismissed || (neverConnected && readUnavailableDismissed())) return null;

  const dismiss = () => {
    if (neverConnected) writeUnavailableDismissed();
    setDismissed(true);
  };

  return (
    <div className="fixed top-14 left-1/2 -translate-x-1/2 z-[60] w-[min(92vw,32rem)] px-2 pointer-events-none">
      <div
        role="status"
        aria-live="polite"
        data-testid="realtime-connection-notice"
        className="pointer-events-auto flex items-start gap-2.5 rounded-xl border border-amber-500/40 bg-amber-50 dark:bg-amber-950/40 text-amber-900 dark:text-amber-100 px-3.5 py-2.5 shadow-lg backdrop-blur-sm"
      >
        <WifiOff className="h-3.5 w-3.5 mt-1 shrink-0" aria-hidden="true" />
        <div className="min-w-0 flex-1">
          <p className="text-sm font-medium">{t(neverConnected ? 'unavailable' : 'reconnecting')}</p>
          <p className="text-sm mt-0.5 opacity-90">{t(neverConnected ? 'unavailableHint' : 'reconnectingHint')}</p>
        </div>
        <button
          type="button"
          onClick={() => window.location.reload()}
          className="text-sm font-medium underline whitespace-nowrap mt-0.5 flex items-center gap-1 cursor-pointer"
        >
          <RefreshCw className="h-3.5 w-3.5" aria-hidden="true" />
          {t('reload')}
        </button>
        <button
          type="button"
          onClick={dismiss}
          aria-label={t('dismiss')}
          className="shrink-0 rounded-md p-0.5 hover:bg-black/10 dark:hover:bg-white/10 cursor-pointer"
        >
          <X className="h-3.5 w-3.5" aria-hidden="true" />
        </button>
      </div>
    </div>
  );
}
