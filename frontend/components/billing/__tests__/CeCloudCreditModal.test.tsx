/**
 * @vitest-environment jsdom
 *
 * CE-edition render test for the cloud-credit modal: it opens on its custom event,
 * renders the REAL i18n strings (en.json), and its CTA opens the linked cloud
 * account's billing page in a new tab.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import messages from '../../../messages/en.json';

// CE edition: the modal renders (Cloud build returns null).
vi.mock('@/lib/edition', () => ({ IS_CE: true, CLOUD_WEB_BASE_URL: 'https://livecontext.ai' }));

import CeCloudCreditModal, { showCeCloudCreditModal } from '../CeCloudCreditModal';

const renderModal = () =>
  render(
    <NextIntlClientProvider locale="en" messages={messages as Record<string, unknown>}>
      <CeCloudCreditModal />
    </NextIntlClientProvider>,
  );

describe('CeCloudCreditModal (CE edition)', () => {
  beforeEach(() => { vi.restoreAllMocks(); });
  afterEach(() => cleanup());

  it('is closed until its event fires, then renders the real i18n title + description', () => {
    renderModal();
    expect(screen.queryByText('Cloud credits exhausted')).toBeNull();

    fireEvent(window, new CustomEvent('ceCloudCredit'));

    expect(screen.getByText('Cloud credits exhausted')).toBeTruthy();
    expect(screen.getByText(/linked LiveContext Cloud account is out of credits/i)).toBeTruthy();
  });

  it('opens the linked cloud account billing page in a new tab from the CTA', () => {
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null);
    renderModal();
    fireEvent(window, new CustomEvent('ceCloudCredit'));

    fireEvent.click(screen.getByRole('button', { name: /open cloud billing/i }));

    expect(openSpy).toHaveBeenCalledWith(
      'https://livecontext.ai/en/app/settings/pricing',
      '_blank',
      'noopener,noreferrer',
    );
  });
});
