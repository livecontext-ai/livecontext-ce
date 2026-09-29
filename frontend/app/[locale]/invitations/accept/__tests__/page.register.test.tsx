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

const { getInvitationInfo, acceptInvitation, declineInvitation } = vi.hoisted(() => ({
  getInvitationInfo: vi.fn(),
  acceptInvitation: vi.fn(),
  declineInvitation: vi.fn(),
}));
vi.mock('@/lib/api/organization-api', () => ({
  organizationApi: { getInvitationInfo, acceptInvitation, declineInvitation },
  isInvitationEmailNotVerifiedError: (e: unknown) =>
    typeof e === 'object' && e !== null && (e as { code?: unknown }).code === 'EMAIL_NOT_VERIFIED',
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

  it('F4: an authenticated visitor is NOT auto-accepted on load; the consent card shows workspace, role and inviter', async () => {
    useAuthMock.mockReturnValue({ isAuthenticated: true, isLoading: false });
    getInvitationInfo.mockResolvedValue({
      valid: true,
      email: 'x@example.com',
      organizationName: 'Acme',
      role: 'ADMIN',
      hasAccount: true,
      inviterName: 'Ada Lovelace',
    });

    render(<AcceptInvitationPage />);

    await waitFor(() =>
      expect(screen.getByText('invitationAccept.confirmTitle:{"org":"Acme"}')).toBeInTheDocument()
    );
    expect(screen.getByText('Acme')).toBeInTheDocument();
    expect(screen.getByText('invitationsInbox.role.ADMIN')).toBeInTheDocument();
    expect(screen.getByText('Ada Lovelace')).toBeInTheDocument();
    // Explicit consent: opening the link (or a link-preview bot fetching it) joins nothing.
    await new Promise((r) => setTimeout(r, 50));
    expect(acceptInvitation).not.toHaveBeenCalled();
    expect(declineInvitation).not.toHaveBeenCalled();
    expect(embeddedRegister).not.toHaveBeenCalled();
  });

  it('F4: accepts only after the Accept click', async () => {
    useAuthMock.mockReturnValue({ isAuthenticated: true, isLoading: false });
    getInvitationInfo.mockResolvedValue({ valid: true, email: 'x@example.com', organizationName: 'Acme', role: 'MEMBER', hasAccount: true });
    acceptInvitation.mockResolvedValue({ id: 'org-1', name: 'Acme' });

    render(<AcceptInvitationPage />);

    const acceptBtn = await screen.findByRole('button', { name: 'invitationAccept.acceptCta' });
    expect(acceptInvitation).not.toHaveBeenCalled();
    fireEvent.click(acceptBtn);

    await waitFor(() => expect(acceptInvitation).toHaveBeenCalledWith('tok-xyz'));
    await waitFor(() =>
      expect(screen.getByText('invitationAccept.acceptedNamedTitle:{"name":"Acme"}')).toBeInTheDocument()
    );
    expect(declineInvitation).not.toHaveBeenCalled();
  });

  it('F4: the Decline click declines the invitation by token and never accepts it', async () => {
    useAuthMock.mockReturnValue({ isAuthenticated: true, isLoading: false });
    getInvitationInfo.mockResolvedValue({ valid: true, email: 'x@example.com', organizationName: 'Acme', role: 'MEMBER', hasAccount: true });
    declineInvitation.mockResolvedValue({ id: 'inv-1', status: 'CANCELLED' });

    render(<AcceptInvitationPage />);

    fireEvent.click(await screen.findByRole('button', { name: 'invitationAccept.declineCta' }));

    await waitFor(() => expect(declineInvitation).toHaveBeenCalledWith('tok-xyz'));
    await waitFor(() => expect(screen.getByText('invitationAccept.declinedTitle')).toBeInTheDocument());
    expect(acceptInvitation).not.toHaveBeenCalled();
  });

  it('an unverified email (403 EMAIL_NOT_VERIFIED) gets a clear "verify your email first" message', async () => {
    useAuthMock.mockReturnValue({ isAuthenticated: true, isLoading: false });
    getInvitationInfo.mockResolvedValue({ valid: true, email: 'x@example.com', organizationName: 'Acme', role: 'MEMBER', hasAccount: true });
    acceptInvitation.mockRejectedValue(
      Object.assign(new Error('Verify your email address before accepting or declining an invitation'), {
        status: 403,
        code: 'EMAIL_NOT_VERIFIED',
      })
    );

    render(<AcceptInvitationPage />);

    fireEvent.click(await screen.findByRole('button', { name: 'invitationAccept.acceptCta' }));

    await waitFor(() => expect(screen.getByText('invitationAccept.emailNotVerifiedTitle')).toBeInTheDocument());
    expect(screen.getByText('invitationAccept.emailNotVerifiedBody')).toBeInTheDocument();
  });

  it('a 403 email mismatch shows the translated wrong-account message, never the raw backend text', async () => {
    useAuthMock.mockReturnValue({ isAuthenticated: true, isLoading: false });
    getInvitationInfo.mockResolvedValue({ valid: true, email: 'x@example.com', organizationName: 'Acme', role: 'MEMBER', hasAccount: true });
    acceptInvitation.mockRejectedValue(
      Object.assign(new Error('Invitation email does not match user email'), { status: 403, code: 'HTTP_403' })
    );

    render(<AcceptInvitationPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'invitationAccept.acceptCta' }));

    await waitFor(() => expect(screen.getByText('invitationAccept.errorWrongAccount')).toBeInTheDocument());
    expect(screen.getByText('invitationAccept.errorTitle')).toBeInTheDocument();
    expect(screen.queryByText(/does not match/)).not.toBeInTheDocument();
  });

  it('any other accept failure shows the translated generic message, never the raw backend text', async () => {
    useAuthMock.mockReturnValue({ isAuthenticated: true, isLoading: false });
    getInvitationInfo.mockResolvedValue({ valid: true, email: 'x@example.com', organizationName: 'Acme', role: 'MEMBER', hasAccount: true });
    acceptInvitation.mockRejectedValue(
      Object.assign(new Error('Member limit reached (3). Upgrade your plan for more members.'), { status: 400 })
    );

    render(<AcceptInvitationPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'invitationAccept.acceptCta' }));

    await waitFor(() => expect(screen.getByText('invitationAccept.errorGeneric')).toBeInTheDocument());
    expect(screen.queryByText(/Member limit/)).not.toBeInTheDocument();
  });

  it('a failed decline shows the translated decline error title and message', async () => {
    useAuthMock.mockReturnValue({ isAuthenticated: true, isLoading: false });
    getInvitationInfo.mockResolvedValue({ valid: true, email: 'x@example.com', organizationName: 'Acme', role: 'MEMBER', hasAccount: true });
    declineInvitation.mockRejectedValue(Object.assign(new Error('Invitation has expired'), { status: 400 }));

    render(<AcceptInvitationPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'invitationAccept.declineCta' }));

    await waitFor(() => expect(screen.getByText('invitationAccept.declineErrorTitle')).toBeInTheDocument());
    expect(screen.getByText('invitationAccept.declineFallbackError')).toBeInTheDocument();
    expect(screen.queryByText('Invitation has expired')).not.toBeInTheDocument();
  });

  it('CLOUD (not CE): a signed-in invitee also gets the consent card (info looked up, no auto-accept)', async () => {
    editionMock.IS_CE = false;
    useAuthMock.mockReturnValue({ isAuthenticated: true, isLoading: false });
    getInvitationInfo.mockResolvedValue({ valid: true, email: 'x@example.com', organizationName: 'Acme', role: 'VIEWER', hasAccount: true });

    render(<AcceptInvitationPage />);

    await waitFor(() => expect(getInvitationInfo).toHaveBeenCalledWith('tok-xyz'));
    expect(await screen.findByRole('button', { name: 'invitationAccept.acceptCta' })).toBeInTheDocument();
    expect(acceptInvitation).not.toHaveBeenCalled();
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
