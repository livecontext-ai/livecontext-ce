// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import enMessages from '@/messages/en.json';

/**
 * Component coverage for the cloud-side "connected installs" inventory, which is
 * rendered by the unified cloud-account page (cloud edition). This replaces the
 * CE-CLOUD-LINK-014..027 e2e cases that drove the now-removed standalone
 * /settings/cloud-link page - that inventory is edition-gated to the cloud side,
 * so it can no longer be reached on a CE e2e stack. Same behaviours, faster.
 */

const mine = vi.fn();
const revoke = vi.fn();
// Role is toggled per test - the inventory is admin-gated (see "admin-gating" test).
let isAdmin = true;
vi.mock('@/lib/api/ce-link.service', () => ({
  ceLinkService: {
    mine: (...a: unknown[]) => mine(...a),
    revoke: (...a: unknown[]) => revoke(...a),
  },
}));
vi.mock('@/lib/api/cloud-link.service', () => ({ cloudLinkService: {} }));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ hasRole: (r: string) => (r === 'ADMIN' ? isAdmin : false), isAuthenticated: true, isLoading: false }),
}));
vi.mock('@/lib/edition/edition', () => ({ IS_CE: false }));
vi.mock('next/navigation', () => ({ useSearchParams: () => new URLSearchParams() }));
vi.mock('../components/BundlesSection', () => ({ default: () => null }));

import { LinkedInstallsSection } from '../page';

const DAY = 24 * 60 * 60 * 1000;
const iso = (ageDays: number) => new Date(Date.now() - ageDays * DAY).toISOString();

function renderSection() {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <LinkedInstallsSection />
    </NextIntlClientProvider>,
  );
}

afterEach(() => cleanup());
beforeEach(() => {
  mine.mockReset();
  revoke.mockReset();
  isAdmin = true;
});

describe('LinkedInstallsSection (cloud-account connected-installs inventory)', () => {
  it('renders the empty state when no installs are bound', async () => {
    mine.mockResolvedValue({ content: [] });
    renderSection();
    expect(await screen.findByText('Nothing connected yet')).toBeInTheDocument();
    expect(mine).toHaveBeenCalledWith(0, 50);
  });

  it('classifies installs by heartbeat freshness (active / 7+ / 30+ / never)', async () => {
    mine.mockResolvedValue({
      content: [
        { installId: '11111111-aaaa', label: 'Fresh', createdAt: iso(2), lastSeenAt: iso(1) },
        { installId: '22222222-bbbb', label: 'Amber', createdAt: iso(40), lastSeenAt: iso(10) },
        { installId: '33333333-cccc', label: 'Red', createdAt: iso(90), lastSeenAt: iso(40) },
        { installId: '44444444-dddd', label: null, createdAt: iso(1), lastSeenAt: null },
      ],
    });
    renderSection();

    expect((await screen.findByText('Fresh')).closest('div')).toBeTruthy();
    expect(screen.getByText('Connected')).toBeInTheDocument();
    expect(screen.getByText('Last seen 7+ days ago')).toBeInTheDocument();
    expect(screen.getByText('Last seen 30+ days ago')).toBeInTheDocument();
    expect(screen.getByText('Awaiting first contact')).toBeInTheDocument();
    // null label falls back to the default install label
    expect(screen.getByText('Self-hosted instance')).toBeInTheDocument();
  });

  it('revokes only the selected install after confirmation', async () => {
    mine.mockResolvedValue({
      content: [
        { installId: 'keep-1', label: 'Keep Me', createdAt: iso(2), lastSeenAt: iso(1) },
        { installId: 'drop-1', label: 'Drop Me', createdAt: iso(2), lastSeenAt: iso(1) },
      ],
    });
    revoke.mockResolvedValue(undefined);
    renderSection();

    const dropRow = (await screen.findByText('Drop Me')).closest('.rounded-xl') as HTMLElement;
    fireEvent.click(within(dropRow).getByRole('button', { name: /disconnect/i }));
    // Confirm in the dialog (second "Disconnect").
    const confirmButtons = await screen.findAllByRole('button', { name: /^Disconnect$/i });
    fireEvent.click(confirmButtons[confirmButtons.length - 1]);

    await waitFor(() => expect(revoke).toHaveBeenCalledTimes(1));
    expect(revoke).toHaveBeenCalledWith('drop-1');
    await waitFor(() => expect(screen.queryByText('Drop Me')).not.toBeInTheDocument());
    expect(screen.getByText('Keep Me')).toBeInTheDocument();
  });

  it('surfaces a load error', async () => {
    mine.mockRejectedValue(new Error('boom'));
    renderSection();
    expect(await screen.findByText('boom')).toBeInTheDocument();
  });

  it('is admin-gated: a non-admin sees the admin-only notice instead of the inventory', async () => {
    // Behaviour change from the cloud-link → cloud-account fusion: managing bound
    // installs now requires the platform-admin role (the standalone cloud-link
    // page had no gate). Pin it so the change is intentional, not silent.
    isAdmin = false;
    mine.mockResolvedValue({ content: [{ installId: 'x', label: 'Secret', createdAt: iso(1), lastSeenAt: iso(1) }] });
    renderSection();

    expect(await screen.findByText('Only the platform administrator can manage cloud connections.')).toBeInTheDocument();
    // The install list is gated out of the render even though data is available.
    expect(screen.queryByText('Secret')).not.toBeInTheDocument();
  });
});
