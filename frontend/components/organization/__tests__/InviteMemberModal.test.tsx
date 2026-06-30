// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// next-intl: keys map to `${ns}.${key}`; useLocale returns 'en'.
vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (key: string) => `${ns}.${key}`,
  useLocale: () => 'en',
}));

const { inviteMember } = vi.hoisted(() => ({ inviteMember: vi.fn() }));
vi.mock('@/lib/api/organization-api', () => ({
  organizationApi: { inviteMember },
}));

import InviteMemberModal from '../InviteMemberModal';

describe('InviteMemberModal - CE invite-by-link copy link', () => {
  const writeText = vi.fn().mockResolvedValue(undefined);

  beforeEach(() => {
    Object.assign(navigator, { clipboard: { writeText } });
    // Stable origin for the built link.
    Object.defineProperty(window, 'location', {
      value: { origin: 'https://ce.example.com' },
      writable: true,
    });
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  function fillAndSubmit(email = 'newcomer@example.com') {
    const emailInput = screen.getByPlaceholderText('settings.organization.emailPlaceholder');
    fireEvent.change(emailInput, { target: { value: email } });
    fireEvent.click(screen.getByRole('button', { name: /settings\.organization\.sendInvitation/ }));
  }

  it('shows a copyable invite link when the invite response carries a token', async () => {
    inviteMember.mockResolvedValue({
      id: 'inv-1',
      email: 'newcomer@example.com',
      role: 'MEMBER',
      status: 'PENDING',
      token: 'tok-abc',
    });

    render(<InviteMemberModal open orgId="org-1" onClose={vi.fn()} onInviteSent={vi.fn()} />);
    fillAndSubmit();

    // The link block appears (kept open, not auto-closed) with the accept URL.
    const expectedLink =
      'https://ce.example.com/en/invitations/accept?token=tok-abc';
    await waitFor(() => {
      expect(screen.getByDisplayValue(expectedLink)).toBeInTheDocument();
    });
    expect(screen.getByText('settings.organization.inviteLinkTitle')).toBeInTheDocument();

    // Copying writes the link to the clipboard.
    fireEvent.click(screen.getByRole('button', { name: /settings\.organization\.inviteLinkCopy/ }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(expectedLink));
  });

  it('does NOT show a link and auto-closes when the response has no token (existing-user / cloud invite)', async () => {
    vi.useFakeTimers();
    const onClose = vi.fn();
    inviteMember.mockResolvedValue({
      id: 'inv-2',
      email: 'member@example.com',
      role: 'MEMBER',
      status: 'PENDING',
      // no token
    });

    render(<InviteMemberModal open orgId="org-1" onClose={onClose} onInviteSent={vi.fn()} />);
    fillAndSubmit('member@example.com');

    // Flush the awaited inviteMember promise, then the 1.5s auto-close timer.
    await vi.waitFor(() => expect(inviteMember).toHaveBeenCalled());
    expect(screen.queryByText('settings.organization.inviteLinkTitle')).not.toBeInTheDocument();
    vi.advanceTimersByTime(1600);
    expect(onClose).toHaveBeenCalled();
    vi.useRealTimers();
  });
});
