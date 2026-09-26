/**
 * @vitest-environment jsdom
 *
 * The CE "cloud link needs a paid plan" modal: opens on its event (dispatched by
 * handleCeRelayError for a CLOUD_LINK_PLAN_REQUIRED refusal), renders the REAL en.json strings,
 * and sends the admin to the CLOUD pricing page in a new tab (the CE's own pricing page is the
 * local one, which cannot change the cloud account's plan). Inert in a Cloud build.
 */
import '@testing-library/jest-dom/vitest';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { act, render, screen, fireEvent, cleanup } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import messages from '../../../messages/en.json';

const edition = vi.hoisted(() => ({ isCe: true }));
vi.mock('@/lib/edition', () => ({
  get IS_CE() {
    return edition.isCe;
  },
}));

import CloudLinkPlanRequiredModal, {
  CLOUD_LINK_PLAN_REQUIRED_EVENT,
  showCloudLinkPlanRequiredModal,
} from '../CloudLinkPlanRequiredModal';

const renderModal = () =>
  render(
    <NextIntlClientProvider locale="en" messages={messages as Record<string, unknown>}>
      <CloudLinkPlanRequiredModal />
    </NextIntlClientProvider>,
  );

describe('CloudLinkPlanRequiredModal', () => {
  beforeEach(() => {
    edition.isCe = true;
  });
  afterEach(() => cleanup());

  it('stays closed until its event fires', () => {
    renderModal();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('opens on the event with the paid-plan copy and a new-tab link to the cloud pricing page', () => {
    renderModal();
    act(() => showCloudLinkPlanRequiredModal());

    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByText('Paid plan required')).toBeInTheDocument();
    expect(screen.getByText(/reconnects automatically/i)).toBeInTheDocument();
    const link = screen.getByRole('link', { name: /View cloud plans/i });
    expect(link).toHaveAttribute('href', 'https://livecontext.ai/app/settings/pricing');
    expect(link).toHaveAttribute('target', '_blank');
  });

  it('closes from its Close button', () => {
    renderModal();
    act(() => {
      window.dispatchEvent(new CustomEvent(CLOUD_LINK_PLAN_REQUIRED_EVENT));
    });
    const closeButtons = screen.getAllByRole('button', { name: 'Close' });
    fireEvent.click(closeButtons[closeButtons.length - 1]);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('is a no-op in a Cloud build', () => {
    edition.isCe = false;
    renderModal();
    act(() => showCloudLinkPlanRequiredModal());
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});
