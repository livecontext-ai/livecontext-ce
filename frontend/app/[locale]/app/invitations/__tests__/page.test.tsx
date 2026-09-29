// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// A STABLE t per namespace, like next-intl in the browser is not guaranteed to be: the
// page memoizes its fetch on t, so a fresh function per render would refetch forever.
const translators = new Map<string, (key: string) => string>();
vi.mock('next-intl', () => ({
  useTranslations: (ns = '') => {
    if (!translators.has(ns)) translators.set(ns, (key: string) => `${ns}.${key}`);
    return translators.get(ns)!;
  },
}));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useParams: () => ({ locale: 'en' }),
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ isAuthenticated: true, isLoading: false }),
}));

const { getMyPendingInvitations } = vi.hoisted(() => ({ getMyPendingInvitations: vi.fn() }));
vi.mock('@/lib/api/organization-api', () => ({
  organizationApi: {
    getMyPendingInvitations,
    acceptInvitationById: vi.fn(),
    declineInvitationById: vi.fn(),
  },
  isInvitationEmailNotVerifiedError: (e: unknown) =>
    typeof e === 'object' && e !== null && (e as { code?: unknown }).code === 'EMAIL_NOT_VERIFIED',
}));

import InvitationsInboxPage from '../page';

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('InvitationsInboxPage - unverified email', () => {
  it('shows "verify your email" instead of an empty inbox when the list is refused with EMAIL_NOT_VERIFIED', async () => {
    getMyPendingInvitations.mockRejectedValue(
      Object.assign(new Error('Verify your email address before accepting or declining an invitation'), {
        status: 403,
        code: 'EMAIL_NOT_VERIFIED',
      })
    );

    render(<InvitationsInboxPage />);

    await waitFor(() => expect(screen.getByText('invitationsInbox.emailNotVerified')).toBeInTheDocument());
    expect(screen.queryByText('invitationsInbox.emptyState')).not.toBeInTheDocument();
    // The raw backend sentence is never shown.
    expect(screen.queryByText(/Verify your email address before/)).not.toBeInTheDocument();
  });

  it('a generic load failure shows the translated load error, not the raw message', async () => {
    getMyPendingInvitations.mockRejectedValue(Object.assign(new Error('HTTP 500: boom'), { status: 500 }));

    render(<InvitationsInboxPage />);

    await waitFor(() => expect(screen.getByText('invitationsInbox.loadError')).toBeInTheDocument());
    expect(screen.queryByText(/boom/)).not.toBeInTheDocument();
  });

  it('a verified account with no invitation sees the empty state', async () => {
    getMyPendingInvitations.mockResolvedValue([]);

    render(<InvitationsInboxPage />);

    await waitFor(() => expect(screen.getByText('invitationsInbox.emptyState')).toBeInTheDocument());
  });
});
