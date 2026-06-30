// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${ns}.${key}:${JSON.stringify(vars)}` : `${ns}.${key}`,
}));

const { searchParamsGet, routerPush } = vi.hoisted(() => ({
  searchParamsGet: vi.fn(),
  routerPush: vi.fn(),
}));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: routerPush }),
  useSearchParams: () => ({ get: searchParamsGet }),
  useParams: () => ({ locale: 'en' }),
}));

const { useAuthMock } = vi.hoisted(() => ({ useAuthMock: vi.fn() }));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => useAuthMock(),
}));

const { getInvitationInfo, acceptInvitation } = vi.hoisted(() => ({
  getInvitationInfo: vi.fn(),
  acceptInvitation: vi.fn(),
}));
vi.mock('@/lib/api/organization-api', () => ({
  organizationApi: { getInvitationInfo, acceptInvitation },
}));

const { embeddedRegister } = vi.hoisted(() => ({ embeddedRegister: vi.fn() }));
vi.mock('@/lib/providers/embedded-auth-provider', () => ({
  embeddedRegister,
}));

// IS_CE is build-time in real code; mock it as a live getter so each test can flip
// edition (CE = embedded invite-by-link flow; cloud = original sign-in flow).
const { editionMock } = vi.hoisted(() => ({ editionMock: { IS_CE: true } }));
vi.mock('@/lib/edition', () => ({
  get IS_CE() {
    return editionMock.IS_CE;
  },
}));

// The page now renders inside AuthLayout (login/register chrome), which reads the
// ThemeProvider context. These tests render the page bare, so stub the layout to a
// passthrough - the chrome is not what we exercise here.
vi.mock('@/components/auth/AuthLayout', () => ({
  AuthLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

import AcceptInvitationPage from '../page';

describe('AcceptInvitationPage - CE invite-by-link register branch', () => {
  beforeEach(() => {
    searchParamsGet.mockReturnValue('tok-xyz');
    // Default: not authenticated visitor, CE edition.
    useAuthMock.mockReturnValue({ isAuthenticated: false, isLoading: false });
    editionMock.IS_CE = true;
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('looks up the invitation by token on load', async () => {
    getInvitationInfo.mockResolvedValue({ valid: false });
    render(<AcceptInvitationPage />);
    await waitFor(() => expect(getInvitationInfo).toHaveBeenCalledWith('tok-xyz'));
  });

  it('renders the REGISTER form when the invitation is valid and the email has no account', async () => {
    getInvitationInfo.mockResolvedValue({
      valid: true,
      email: 'newcomer@example.com',
      organizationName: 'Acme',
      role: 'MEMBER',
      hasAccount: false,
    });

    render(<AcceptInvitationPage />);

    await waitFor(() =>
      expect(screen.getByText('invitationAccept.registerTitle')).toBeInTheDocument()
    );
    // Email is prefilled from the invitation, locked, and labelled via the i18n key
    // (the hardcoded "Email" string was replaced with invitationAccept.email).
    const emailInput = screen.getByLabelText('invitationAccept.email') as HTMLInputElement;
    expect(emailInput).toBeDisabled();
    expect(emailInput).toHaveValue('newcomer@example.com');

    // Submitting registers WITH the invitation token (bypass + auto-join).
    embeddedRegister.mockResolvedValue({ success: true });
    fireEvent.change(screen.getByLabelText('invitationAccept.firstName'), { target: { value: 'New' } });
    fireEvent.change(screen.getByLabelText('invitationAccept.lastName'), { target: { value: 'Comer' } });
    fireEvent.change(screen.getByLabelText('invitationAccept.password'), { target: { value: 'password123' } });
    fireEvent.change(screen.getByLabelText('invitationAccept.confirmPassword'), { target: { value: 'password123' } });
    fireEvent.click(screen.getByRole('button', { name: 'invitationAccept.registerCta' }));

    await waitFor(() =>
      expect(embeddedRegister).toHaveBeenCalledWith(
        'newcomer@example.com',
        'password123',
        'New',
        'Comer',
        'tok-xyz'
      )
    );
  });

  it('shows the sign-in CTA (not the register form) when the email already has an account', async () => {
    getInvitationInfo.mockResolvedValue({
      valid: true,
      email: 'member@example.com',
      organizationName: 'Acme',
      role: 'MEMBER',
      hasAccount: true,
    });

    render(<AcceptInvitationPage />);

    await waitFor(() => expect(screen.getByText('invitationAccept.signInTitle')).toBeInTheDocument());
    expect(screen.queryByText('invitationAccept.registerTitle')).not.toBeInTheDocument();
  });

  it('shows the invalid card for an unusable token and never registers', async () => {
    getInvitationInfo.mockResolvedValue({ valid: false });

    render(<AcceptInvitationPage />);

    await waitFor(() => expect(screen.getByText('invitationAccept.invalidTitle')).toBeInTheDocument());
    expect(screen.queryByText('invitationAccept.registerTitle')).not.toBeInTheDocument();
    expect(embeddedRegister).not.toHaveBeenCalled();
  });

  it('an authenticated visitor accepts directly via the token (no register form)', async () => {
    useAuthMock.mockReturnValue({ isAuthenticated: true, isLoading: false });
    getInvitationInfo.mockResolvedValue({ valid: true, email: 'x@example.com', hasAccount: true });
    acceptInvitation.mockResolvedValue({ id: 'org-1', name: 'Acme' });

    render(<AcceptInvitationPage />);

    await waitFor(() => expect(acceptInvitation).toHaveBeenCalledWith('tok-xyz'));
    expect(embeddedRegister).not.toHaveBeenCalled();
  });

  it('CLOUD (not CE): an unauthenticated invitee gets the sign-in flow, never the embedded register form or the info lookup', async () => {
    editionMock.IS_CE = false;

    render(<AcceptInvitationPage />);

    // Cloud keeps the original behavior: sign-in CTA, no embedded register form.
    await waitFor(() => expect(screen.getByText('invitationAccept.signInTitle')).toBeInTheDocument());
    expect(screen.queryByText('invitationAccept.registerTitle')).not.toBeInTheDocument();
    // The embedded invite-by-link flow is fully skipped in cloud: no info lookup, no register.
    expect(getInvitationInfo).not.toHaveBeenCalled();
    expect(embeddedRegister).not.toHaveBeenCalled();
  });
});
