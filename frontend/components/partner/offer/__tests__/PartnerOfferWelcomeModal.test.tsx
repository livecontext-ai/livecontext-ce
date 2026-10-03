// @vitest-environment jsdom
/**
 * Back from paying through a partner's offer: the plan being switched on, each app the offer gives
 * arriving in the workspace, and the partner to write to.
 */
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import enMessages from '@/messages/en.json';
import type { PartnerOfferWelcome } from '@/lib/api/services/partner-program-api.service';

const api = vi.hoisted(() => ({ offerWelcome: vi.fn() }));
const dm = vi.hoisted(() => ({ openThread: vi.fn() }));
const push = vi.fn();
vi.mock('@/lib/api/services/partner-program-api.service', async (importOriginal) => ({
  ...(await importOriginal<object>()),
  partnerProgramApi: api,
}));
vi.mock('@/lib/api/dm-api', () => ({ dmApi: dm }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));
vi.mock('next/link', () => ({
  default: ({ children, href, ...rest }: { children: React.ReactNode; href: string }) => <a href={href} {...rest}>{children}</a>,
}));

import { PartnerOfferWelcomeModal } from '../PartnerOfferWelcomeModal';

const PARTNER = { user_id: '42', name: 'Northwind Studio', handle: 'northwind', avatar_url: '/api/users/42/avatar', tier: 'gold' };

function welcome(overrides: Partial<PartnerOfferWelcome> = {}): PartnerOfferWelcome {
  return {
    partner: PARTNER,
    apps: [
      { id: 'app-a', title: 'Invoice chaser', status: 'INSTALLED' },
      { id: 'app-b', title: 'Lead finder', status: 'PENDING' },
      { id: 'app-c', title: 'Old app', status: 'FAILED' },
    ],
    ...overrides,
  };
}

const onClose = vi.fn();
function renderModal(planState: 'processing' | 'success' | 'error' = 'success', planCode = 'TEAM') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <PartnerOfferWelcomeModal token="Abc23XyZ9k" planState={planState} planCode={planCode} onClose={onClose} />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  api.offerWelcome.mockReset().mockResolvedValue(welcome());
  dm.openThread.mockReset().mockResolvedValue({ id: 'th-9' });
  push.mockReset();
  onClose.mockReset();
});
afterEach(cleanup);

describe('PartnerOfferWelcomeModal', () => {
  it('names the plan once it is active, and shows each app where it stands', async () => {
    renderModal('success');

    expect(screen.getByTestId('offer-welcome-title').textContent).toBe('Welcome to Team');
    expect(await screen.findByTestId('offer-welcome-app-app-a')).toBeTruthy();
    expect(api.offerWelcome).toHaveBeenCalledWith('Abc23XyZ9k');
    expect(screen.getByTestId('offer-welcome-apps').textContent).toContain('Your apps from Northwind Studio');
    // Installed: opens in the workspace.
    expect(screen.getByTestId('offer-welcome-open').getAttribute('href')).toBe('/app/applications/app-a');
    expect(screen.getByTestId('offer-welcome-app-app-b').textContent).toContain('Installing...');
    expect(screen.getByTestId('offer-welcome-app-app-c').textContent).toContain('Could not be installed');
  });

  it('while the payment is being confirmed it says so; a slow confirmation is not called a failure', () => {
    renderModal('processing');
    expect(screen.getByTestId('offer-welcome-title').textContent).toBe('Activating your subscription...');
    cleanup();

    renderModal('error');
    expect(screen.getByText(/You can close this window: your plan and your apps arrive as soon as it is/)).toBeTruthy();
  });

  it('writes to the partner: opens the conversation and goes to it', async () => {
    renderModal();

    fireEvent.click(await screen.findByTestId('offer-welcome-write'));

    await waitFor(() => expect(push).toHaveBeenCalledWith('/app/messages/th-9'));
    expect(dm.openThread).toHaveBeenCalledWith('42');
    expect(onClose).toHaveBeenCalled();
  });

  it('a conversation that cannot be opened is said, and the modal stays', async () => {
    dm.openThread.mockRejectedValue(new Error('down'));
    renderModal();

    fireEvent.click(await screen.findByTestId('offer-welcome-write'));

    expect((await screen.findByRole('alert')).textContent).toBe('The conversation could not be opened. Try again from Messages.');
    expect(push).not.toHaveBeenCalled();
  });

  it('no partner id (not their client): no button to write; a private profile reads as "your partner"', async () => {
    api.offerWelcome.mockResolvedValue(welcome({ partner: { name: 'Northwind Studio', handle: null, avatar_url: null, tier: null } }));
    renderModal();
    await screen.findByTestId('offer-welcome-apps');
    expect(screen.queryByTestId('offer-welcome-write')).toBeNull();
    cleanup();

    api.offerWelcome.mockResolvedValue(welcome({ partner: { user_id: '42', name: null, handle: null, avatar_url: null, tier: null } }));
    renderModal();
    expect((await screen.findByTestId('offer-welcome-write')).textContent).toBe('Write to your partner');
    expect(screen.getByTestId('offer-welcome-apps').textContent).toContain('Your apps from your partner');
  });

  it('an offer without apps shows no app list; an unreadable welcome says the purchase is not affected and the apps arrive in the background', async () => {
    api.offerWelcome.mockResolvedValue(welcome({ apps: [] }));
    renderModal();
    await screen.findByTestId('offer-welcome-partner');
    expect(screen.queryByTestId('offer-welcome-apps')).toBeNull();
    cleanup();

    api.offerWelcome.mockRejectedValue(new Error('down'));
    renderModal();
    expect((await screen.findByTestId('offer-welcome-unreadable')).textContent).toContain('installed in the background after the payment');
  });

  it('asks again while an app is on its way, and stops once every app has landed', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      api.offerWelcome
        .mockResolvedValueOnce(welcome({ apps: [{ id: 'app-a', title: 'Invoice chaser', status: 'WAITING' }] }))
        .mockResolvedValue(welcome({ apps: [{ id: 'app-a', title: 'Invoice chaser', status: 'INSTALLED' }] }));
      renderModal();
      await screen.findByTestId('offer-welcome-app-app-a');
      expect(screen.getByTestId('offer-welcome-app-app-a').dataset.status).toBe('WAITING');

      await vi.advanceTimersByTimeAsync(3_100);
      await waitFor(() => expect(screen.getByTestId('offer-welcome-app-app-a').dataset.status).toBe('INSTALLED'));
      const calls = api.offerWelcome.mock.calls.length;

      await vi.advanceTimersByTimeAsync(10_000);
      expect(api.offerWelcome.mock.calls.length).toBe(calls);
    } finally {
      vi.useRealTimers();
    }
  });

  it('regression: an offer that cannot be read is not asked again every few seconds', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      api.offerWelcome.mockRejectedValue(new Error('404'));
      renderModal();
      await screen.findByTestId('offer-welcome-unreadable');
      const calls = api.offerWelcome.mock.calls.length;

      await vi.advanceTimersByTimeAsync(15_000);
      expect(api.offerWelcome.mock.calls.length).toBe(calls);
    } finally {
      vi.useRealTimers();
    }
  });

  it('apps still on their way after a few minutes: the modal stops asking and says where they will be', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      api.offerWelcome.mockResolvedValue(welcome({ apps: [{ id: 'app-a', title: 'Invoice chaser', status: 'PENDING' }] }));
      renderModal();
      await screen.findByTestId('offer-welcome-app-app-a');
      expect(screen.queryByTestId('offer-welcome-later')).toBeNull();

      await vi.advanceTimersByTimeAsync(3 * 60_000 + 1_000);
      await waitFor(() => expect(screen.getByTestId('offer-welcome-later').textContent).toContain('you will find them in Applications'));
      const calls = api.offerWelcome.mock.calls.length;
      await vi.advanceTimersByTimeAsync(10_000);
      expect(api.offerWelcome.mock.calls.length).toBe(calls);
    } finally {
      vi.useRealTimers();
    }
  });

  it('closing starts the client off', async () => {
    renderModal();
    fireEvent.click(screen.getByTestId('offer-welcome-close'));
    expect(onClose).toHaveBeenCalled();
  });
});
