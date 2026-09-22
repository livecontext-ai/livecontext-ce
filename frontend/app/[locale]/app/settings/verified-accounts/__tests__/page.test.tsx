// @vitest-environment jsdom
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import enMessages from '@/messages/en.json';

// --- controllable test state -----------------------------------------------
let mockIsManagedCloud = true;
let mockHasRole: (role: string) => boolean = () => true;
const adminSetVerified = vi.fn();

vi.mock('@/lib/edition', () => ({
  get IS_MANAGED_CLOUD() { return mockIsManagedCloud; },
  get IS_CE() { return !mockIsManagedCloud; },
}));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false, isLoading: false }),
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ hasRole: (r: string) => mockHasRole(r), loginWithRedirect: vi.fn() }),
}));
vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: {
    adminSetVerified: (...a: unknown[]) => adminSetVerified(...a),
  },
}));

import VerifiedAccountsPage from '../page';

function renderPage() {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages}>
      <VerifiedAccountsPage />
    </NextIntlClientProvider>,
  );
}

describe('Verified accounts admin page', () => {
  beforeEach(() => {
    mockIsManagedCloud = true;
    mockHasRole = () => true;
    adminSetVerified.mockReset();
    adminSetVerified.mockResolvedValue({
      userId: 7, email: 'alice@example.com',
      verified: true, verifiedByRole: false, effectivelyVerified: true, profileWithdrawn: false,
    });
  });
  afterEach(cleanup);

  it('refuses a self-hosted deployment outright, before any form is shown', () => {
    // The nav entry is already gated; this is the direct-URL guard. Without it a
    // self-hosted admin gets a working-looking form whose every submit answers 503.
    mockIsManagedCloud = false;
    renderPage();

    expect(screen.getByText(/Not available on this deployment/)).toBeTruthy();
    expect(screen.queryByLabelText('Account email')).toBeNull();
  });

  it('refuses a non-admin', () => {
    mockHasRole = () => false;
    renderPage();

    expect(screen.getByText(/Only the platform administrator can verify an account/)).toBeTruthy();
    expect(screen.queryByLabelText('Account email')).toBeNull();
  });

  it('does not submit an address that is not an email', async () => {
    // Driven through "Remove badge" on purpose: it is a plain button, so it reaches our
    // own validation. The grant button is a form submit, where the browser's native
    // type="email" check fires first and this branch would never be exercised - the
    // revoke path is the one that has to catch it.
    renderPage();

    fireEvent.change(screen.getByLabelText('Account email'), { target: { value: 'not-an-email' } });
    fireEvent.click(screen.getByRole('button', { name: /Remove badge/ }));

    await waitFor(() => expect(screen.getByText(/Enter a valid email address/)).toBeTruthy());
    expect(adminSetVerified).not.toHaveBeenCalled();
  });

  it('does not submit a user id that is not a positive whole number', async () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'User ID' }));
    fireEvent.change(screen.getByLabelText('User ID'), { target: { value: '0' } });
    fireEvent.click(screen.getByRole('button', { name: /Remove badge/ }));

    await waitFor(() => expect(screen.getByText(/positive whole number/)).toBeTruthy());
    expect(adminSetVerified).not.toHaveBeenCalled();
  });

  it('grants by email, and by id when the mode is switched', async () => {
    renderPage();

    fireEvent.change(screen.getByLabelText('Account email'), { target: { value: 'alice@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: /Verify account/ }));
    await waitFor(() => expect(adminSetVerified).toHaveBeenCalledWith({
      target_email: 'alice@example.com', verified: true,
    }));

    fireEvent.click(screen.getByRole('button', { name: 'User ID' }));
    fireEvent.change(screen.getByLabelText('User ID'), { target: { value: '42' } });
    fireEvent.click(screen.getByRole('button', { name: /Verify account/ }));
    await waitFor(() => expect(adminSetVerified).toHaveBeenCalledWith({
      target_user_id: 42, verified: true,
    }));
  });

  it('revokes with verified:false, from the same target', async () => {
    adminSetVerified.mockResolvedValue({
      userId: 7, email: 'alice@example.com',
      verified: false, verifiedByRole: false, effectivelyVerified: false,
    });
    renderPage();

    fireEvent.change(screen.getByLabelText('Account email'), { target: { value: 'alice@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: /Remove badge/ }));

    await waitFor(() => expect(adminSetVerified).toHaveBeenCalledWith({
      target_email: 'alice@example.com', verified: false,
    }));
    expect(screen.getByText(/has been removed from this account/)).toBeTruthy();
  });

  it('says so when a revoke changed the flag but not what readers see', async () => {
    // The one outcome that surprises an operator: revoking on a platform admin stores
    // false and the badge keeps showing, because the role grants it.
    adminSetVerified.mockResolvedValue({
      userId: 1, email: 'root@example.com',
      verified: false, verifiedByRole: true, effectivelyVerified: true,
    });
    renderPage();

    fireEvent.change(screen.getByLabelText('Account email'), { target: { value: 'root@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: /Remove badge/ }));

    await waitFor(() => expect(screen.getByText(/keeps the badge from its role/)).toBeTruthy());
    // The line above it reports the WRITE, which did remove the manual flag.
    expect(screen.getByText(/has been removed from this account/)).toBeTruthy();
  });

  it('says a grant on a withdrawn profile is stored but shows nowhere', async () => {
    // The mirror of the admin case above, and the one an operator would otherwise
    // misread as success: the flag is set and no visitor sees a thing.
    adminSetVerified.mockResolvedValue({
      userId: 9, email: 'hidden@example.com',
      verified: true, verifiedByRole: false, effectivelyVerified: false, profileWithdrawn: true,
    });
    renderPage();

    fireEvent.change(screen.getByLabelText('Account email'), { target: { value: 'hidden@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: /Verify account/ }));

    await waitFor(() => expect(screen.getByText(/stored but shown nowhere/)).toBeTruthy());
    // Regression: this line used to read "no longer shows the verified badge" right
    // after a GRANT, contradicting the toast that had just said the opposite.
    expect(screen.getByText(/is now granted to this account/)).toBeTruthy();
    expect(screen.queryByText(/has been removed from this account/)).toBeNull();
  });

  it('surfaces a failure instead of pretending the grant landed', async () => {
    adminSetVerified.mockRejectedValue(new Error('Verified badges are a managed-cloud feature'));
    renderPage();

    fireEvent.change(screen.getByLabelText('Account email'), { target: { value: 'alice@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: /Verify account/ }));

    await waitFor(() => expect(screen.getByText(/managed-cloud feature/)).toBeTruthy());
    // No result panel: nothing was stored.
    expect(screen.queryByText(/is now granted to this account/)).toBeNull();
  });
});
