// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import enMessages from '@/messages/en.json';

import CookieConsentBanner from '../CookieConsentBanner';

const STORAGE_KEY = 'lc.cookieConsent';

function renderBanner() {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages}>
      <CookieConsentBanner />
    </NextIntlClientProvider>,
  );
}

describe('CookieConsentBanner', () => {
  beforeEach(() => {
    localStorage.clear();
  });
  afterEach(cleanup);

  it('shows the banner with Accept and Reject when no choice was made yet', async () => {
    renderBanner();

    expect(await screen.findByRole('button', { name: 'Accept' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reject' })).toBeInTheDocument();
    // Privacy policy link points outside the locale segment.
    expect(screen.getByRole('link', { name: 'Privacy Policy' })).toHaveAttribute('href', '/legal/privacy');
  });

  it('persists "accepted" (version-stamped) and hides the banner on Accept', async () => {
    renderBanner();

    fireEvent.click(await screen.findByRole('button', { name: 'Accept' }));

    await waitFor(() => expect(screen.queryByRole('region')).not.toBeInTheDocument());
    const stored = JSON.parse(localStorage.getItem(STORAGE_KEY)!);
    expect(stored.status).toBe('accepted');
    expect(stored.version).toBe(1);
  });

  it('persists "rejected" and hides the banner on Reject', async () => {
    renderBanner();

    fireEvent.click(await screen.findByRole('button', { name: 'Reject' }));

    await waitFor(() => expect(screen.queryByRole('region')).not.toBeInTheDocument());
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY)!).status).toBe('rejected');
  });

  it('stays hidden when a current-version choice is already stored', async () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ status: 'accepted', version: 1, ts: 1 }));

    renderBanner();

    // Effect runs synchronously after mount; the banner must never appear.
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Accept' })).not.toBeInTheDocument());
    expect(screen.queryByRole('region')).not.toBeInTheDocument();
  });

  it('re-prompts when the stored consent version is stale', async () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ status: 'accepted', version: 0, ts: 1 }));

    renderBanner();

    expect(await screen.findByRole('button', { name: 'Accept' })).toBeInTheDocument();
  });

  it('shows the banner when the stored value is malformed JSON', async () => {
    localStorage.setItem(STORAGE_KEY, 'not-json{');

    renderBanner();

    expect(await screen.findByRole('button', { name: 'Accept' })).toBeInTheDocument();
  });

  it('still hides (no crash) when localStorage.setItem throws (private mode)', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('QuotaExceededError');
    });
    try {
      renderBanner();
      fireEvent.click(await screen.findByRole('button', { name: 'Accept' }));
      await waitFor(() => expect(screen.queryByRole('region')).not.toBeInTheDocument());
    } finally {
      setItem.mockRestore();
    }
  });

  it('moves focus to the banner region when it appears', async () => {
    renderBanner();

    const region = await screen.findByRole('region');
    await waitFor(() => expect(region).toHaveFocus());
  });
});
