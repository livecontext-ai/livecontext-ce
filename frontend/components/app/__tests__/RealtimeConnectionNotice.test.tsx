// @vitest-environment jsdom
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import type { WsConnectionStatus } from '@/lib/websocket/ws-types';

let status: WsConnectionStatus = 'disconnected';
const client = vi.hoisted(() => ({ hasEverConnected: false, lastFailureWasRefusal: false }));

vi.mock('@/lib/websocket', () => ({
  useWebSocketStatus: () => status,
  wsClient: client,
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

import RealtimeConnectionNotice, { REALTIME_NOTICE_DELAY_MS, UNAVAILABLE_DISMISS_TTL_MS } from '../RealtimeConnectionNotice';

/**
 * A tab whose WebSocket had dropped looked exactly like a healthy one (prod, 2026-09-29: a
 * chat reply that never appeared, an app run "running" after it finished). The notice makes
 * that state visible, without crying wolf on a routine reconnect or on pages that never
 * connect at all.
 */
describe('RealtimeConnectionNotice', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    status = 'disconnected';
    client.hasEverConnected = false;
    client.lastFailureWasRefusal = false;
    window.localStorage.clear();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  const notice = () => screen.queryByTestId('realtime-connection-notice');

  it('shows once live updates have been down longer than the delay, and offers a reload', () => {
    status = 'connected';
    const { rerender } = render(<RealtimeConnectionNotice />);
    status = 'reconnecting';
    rerender(<RealtimeConnectionNotice />);

    act(() => { vi.advanceTimersByTime(REALTIME_NOTICE_DELAY_MS - 1); });
    expect(notice()).toBeNull();

    act(() => { vi.advanceTimersByTime(1); });
    expect(notice()).not.toBeNull();
    expect(screen.getByText('reconnecting')).toBeTruthy();
    expect(screen.getByRole('button', { name: /reload/ })).toBeTruthy();
  });

  it('stays up across the connecting/reconnecting flips of a retry, and goes away once connected', () => {
    status = 'connected';
    const { rerender } = render(<RealtimeConnectionNotice />);
    status = 'reconnecting';
    rerender(<RealtimeConnectionNotice />);
    act(() => { vi.advanceTimersByTime(REALTIME_NOTICE_DELAY_MS); });
    status = 'connecting';
    rerender(<RealtimeConnectionNotice />);
    expect(notice()).not.toBeNull();

    status = 'connected';
    rerender(<RealtimeConnectionNotice />);
    expect(notice()).toBeNull();
  });

  it('says nothing while a page is still making its first connection', () => {
    status = 'connecting';
    render(<RealtimeConnectionNotice />);
    act(() => { vi.advanceTimersByTime(REALTIME_NOTICE_DELAY_MS * 3); });
    expect(notice()).toBeNull();
  });

  it("shows when the page's FIRST socket was refused (goaway instead of hello: never connected, now reconnecting)", () => {
    status = 'reconnecting';
    render(<RealtimeConnectionNotice />);
    act(() => { vi.advanceTimersByTime(REALTIME_NOTICE_DELAY_MS); });
    expect(notice()).not.toBeNull();
  });

  it('a page that never connected (not refused at the cap) says the live connection is not reaching it', () => {
    status = 'reconnecting';
    render(<RealtimeConnectionNotice />);
    act(() => { vi.advanceTimersByTime(REALTIME_NOTICE_DELAY_MS); });
    expect(screen.getByText('unavailable')).toBeTruthy();
    expect(screen.getByRole('button', { name: /reload/ })).toBeTruthy();
  });

  it('a first socket REFUSED at the connection cap is an outage, not a blocked connection', () => {
    client.lastFailureWasRefusal = true;
    status = 'reconnecting';
    render(<RealtimeConnectionNotice />);
    act(() => { vi.advanceTimersByTime(REALTIME_NOTICE_DELAY_MS); });
    expect(screen.getByText('reconnecting')).toBeTruthy();
  });

  it('remounting in another layout does not forget the page had connected (wording stays "reconnecting")', () => {
    client.hasEverConnected = true;
    status = 'reconnecting';
    render(<RealtimeConnectionNotice />);
    act(() => { vi.advanceTimersByTime(REALTIME_NOTICE_DELAY_MS); });
    expect(screen.getByText('reconnecting')).toBeTruthy();
  });

  it('dismissing the never-connected wording is remembered for a day, across page loads and tabs', () => {
    status = 'reconnecting';
    const first = render(<RealtimeConnectionNotice />);
    act(() => { vi.advanceTimersByTime(REALTIME_NOTICE_DELAY_MS); });
    act(() => { screen.getByRole('button', { name: 'dismiss' }).click(); });
    first.unmount();

    const second = render(<RealtimeConnectionNotice />); // the next page load
    act(() => { vi.advanceTimersByTime(REALTIME_NOTICE_DELAY_MS * 2); });
    expect(notice()).toBeNull();
    second.unmount();

    vi.setSystemTime(Date.now() + UNAVAILABLE_DISMISS_TTL_MS + 1);
    render(<RealtimeConnectionNotice />); // a day later
    act(() => { vi.advanceTimersByTime(REALTIME_NOTICE_DELAY_MS); });
    expect(notice()).not.toBeNull();
  });

  it('can be dismissed, and comes back for the NEXT outage', () => {
    status = 'connected';
    const { rerender } = render(<RealtimeConnectionNotice />);
    status = 'reconnecting';
    rerender(<RealtimeConnectionNotice />);
    act(() => { vi.advanceTimersByTime(REALTIME_NOTICE_DELAY_MS); });
    act(() => { screen.getByRole('button', { name: 'dismiss' }).click(); });
    expect(notice()).toBeNull();

    status = 'connected';
    rerender(<RealtimeConnectionNotice />);
    status = 'reconnecting';
    rerender(<RealtimeConnectionNotice />);
    act(() => { vi.advanceTimersByTime(REALTIME_NOTICE_DELAY_MS); });
    expect(notice()).not.toBeNull();
  });

  it('lets clicks through outside its card (a composer or bottom toolbar stays usable during an outage)', () => {
    status = 'reconnecting';
    render(<RealtimeConnectionNotice />);
    act(() => { vi.advanceTimersByTime(REALTIME_NOTICE_DELAY_MS); });
    const card = notice()!;
    expect(card.parentElement!.className).toContain('pointer-events-none');
    expect(card.className).toContain('pointer-events-auto');
  });

  it('says nothing after an intentional disconnect (logout)', () => {
    status = 'connected';
    const { rerender } = render(<RealtimeConnectionNotice />);
    status = 'disconnected';
    rerender(<RealtimeConnectionNotice />);
    act(() => { vi.advanceTimersByTime(REALTIME_NOTICE_DELAY_MS * 3); });
    expect(notice()).toBeNull();
  });

  it('a short reconnect never shows it', () => {
    status = 'connected';
    const { rerender } = render(<RealtimeConnectionNotice />);
    status = 'reconnecting';
    rerender(<RealtimeConnectionNotice />);
    act(() => { vi.advanceTimersByTime(2000); });
    status = 'connected';
    rerender(<RealtimeConnectionNotice />);
    act(() => { vi.advanceTimersByTime(REALTIME_NOTICE_DELAY_MS * 2); });
    expect(notice()).toBeNull();
  });
});
