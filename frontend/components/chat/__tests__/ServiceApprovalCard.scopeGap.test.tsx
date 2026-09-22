/**
 * @vitest-environment jsdom
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ServiceApprovalCard } from '../ServiceApprovalCard';

const mocks = vi.hoisted(() => ({
  hasCredential: vi.fn(),
  refetchCredentials: vi.fn(),
  wizardProps: vi.fn(),
  byokCapability: vi.fn(),
}));

vi.mock('next-intl', () => ({
  useTranslations: (namespace?: string) => (key: string) => `${namespace ?? ''}.${key}`,
}));

vi.mock('next/navigation', () => ({
  useSearchParams: () => ({ get: () => null }),
  useRouter: () => ({ replace: vi.fn() }),
  usePathname: () => '/app/chat',
}));

vi.mock('next/image', () => ({
  default: (props: React.ImgHTMLAttributes<HTMLImageElement>) => <img {...props} />,
}));

vi.mock('@/hooks/useCredentialCheck', () => ({
  useCredentialCheck: () => ({
    hasCredential: mocks.hasCredential,
    refetch: mocks.refetchCredentials,
    isLoading: false,
    credentials: [],
  }),
}));

vi.mock('@/components/Toast', () => ({
  useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }),
}));

vi.mock('@/components/ToastContainer', () => ({ default: () => null }));

vi.mock('@/components/credentials', () => ({
  CredentialWizard: (props: any) => {
    mocks.wizardProps(props);
    return props.open ? <div data-testid="credential-wizard" /> : null;
  },
}));

vi.mock('@/lib/credentials/useByokCapability', () => ({
  useByokCapability: (integration: string) => mocks.byokCapability(integration),
}));

const READONLY = 'https://www.googleapis.com/auth/gmail.readonly';
const SEND = 'https://www.googleapis.com/auth/gmail.send';
const LABELS = 'https://www.googleapis.com/auth/gmail.labels';

const RECONNECT = 'workflow.node.missingScopes.cta';
const BYOK = 'workflow.node.missingScopes.switchToAdvanced';

/** The account production held: it can label and send, and it cannot read. */
const gmailShortOfReadonly = {
  serviceType: 'gmail',
  serviceName: 'Gmail',
  iconSlug: 'gmail',
  toolName: 'List Messages',
  requiredScopes: [READONLY],
  grantedScopes: [LABELS, SEND],
  missingScopes: [READONLY],
  credentialType: 'OAuth2',
};

/**
 * Shaped like what the backend really emits for a scope gap: the service entry carries the
 * scopes, and needsAttention is set at the top level, which is what stops the card from
 * auto-approving itself out of existence.
 */
const renderCard = (service: Record<string, unknown>, needsAttention = true) =>
  render(
    <ServiceApprovalCard
      conversationId="conv-1"
      pendingApproval={{
        services: [service as never],
        reason: 'read the inbox',
        needsAttention,
        timestamp: 1,
      }}
    />,
  );

describe('ServiceApprovalCard - a connected account that was not granted enough', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // The credential exists: this is the state that made the ordinary card useless.
    mocks.hasCredential.mockReturnValue(true);
    // Default: the catalog offers BYOK for this integration but declares no restricted scope.
    mocks.byokCapability.mockReturnValue({
      byokOnlyScopes: [],
      platformScopes: [],
      byokOffered: true,
    });
  });

  it('names the scopes the account is missing', () => {
    renderCard(gmailShortOfReadonly);

    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByText(READONLY)).toBeInTheDocument();
    expect(screen.queryByText(SEND)).toBeNull();
  });

  it('offers the user their own OAuth client, and opens the wizard on that form', () => {
    renderCard(gmailShortOfReadonly);

    fireEvent.click(screen.getByRole('button', { name: BYOK }));

    expect(mocks.wizardProps).toHaveBeenLastCalledWith(
      expect.objectContaining({ open: true, initialMode: 'advanced' }),
    );
  });

  it('an ordinary reconnect opens the wizard on the standard form instead', () => {
    renderCard(gmailShortOfReadonly);

    fireEvent.click(screen.getByRole('button', { name: RECONNECT }));

    expect(mocks.wizardProps).toHaveBeenLastCalledWith(
      expect.objectContaining({ open: true, initialMode: 'standard' }),
    );
  });

  /**
   * The account already HAS a credential, so it is absent from the "needs credentials" list the
   * requirements used to be built from. Without the scope-gap services added back in, both CTAs
   * would open a wizard with nothing in it.
   */
  it('the wizard is given the account to reconnect, not an empty list', () => {
    renderCard(gmailShortOfReadonly);

    fireEvent.click(screen.getByRole('button', { name: RECONNECT }));

    expect(mocks.wizardProps).toHaveBeenLastCalledWith(
      expect.objectContaining({
        requirements: [expect.objectContaining({ iconSlug: 'gmail', serviceName: 'Gmail' })],
      }),
    );
  });

  /**
   * When the platform's shared client cannot ask for the scope, a standard reconnect is a
   * detour to the same refusal, so the banner drops it. Gmail read access is exactly that case:
   * Google requires verification the shared consent screen does not have.
   */
  it('hides the standard reconnect when only the user own client can grant the scope', () => {
    mocks.byokCapability.mockReturnValue({
      byokOnlyScopes: [READONLY],
      platformScopes: [LABELS, SEND],
      byokOffered: true,
    });

    renderCard(gmailShortOfReadonly);

    expect(screen.queryByRole('button', { name: RECONNECT })).toBeNull();
    expect(screen.getByRole('button', { name: BYOK })).toBeInTheDocument();
  });

  /**
   * Roughly every OAuth2 API in the catalog hides BYOK. Offering the form anyway sends someone
   * off to register an OAuth application the product never meant them to register.
   */
  it('does not offer BYOK for an integration whose catalog entry hides it', () => {
    mocks.byokCapability.mockReturnValue({
      byokOnlyScopes: [],
      platformScopes: [],
      byokOffered: false,
    });

    renderCard(gmailShortOfReadonly);

    expect(screen.queryByRole('button', { name: BYOK })).toBeNull();
    expect(screen.getByRole('button', { name: RECONNECT })).toBeInTheDocument();
  });

  /**
   * An API key has no scope concept. A scope banner over one would tell its owner to obtain a
   * permission that does not exist for that kind of credential.
   */
  it('says nothing for a credential type that has no scopes', () => {
    renderCard({ ...gmailShortOfReadonly, credentialType: 'API Key' });

    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('says nothing when the account already holds everything the call asked for', () => {
    renderCard({
      ...gmailShortOfReadonly,
      grantedScopes: [LABELS, SEND, READONLY],
      missingScopes: [],
    });

    expect(screen.queryByRole('alert')).toBeNull();
  });

  /**
   * Asserted on a service the user has NOT connected, so the card takes its ordinary
   * "connect this" branch and actually renders the services list. Asserting it on a connected
   * service would pass for the wrong reason: that card auto-approves and renders a green box,
   * so there would be no alert either way and the scope logic would never be reached.
   */
  it('says nothing about scopes for an ordinary connect request', () => {
    mocks.hasCredential.mockReturnValue(false);

    renderCard(
      { serviceType: 'slack', serviceName: 'Slack', iconSlug: 'slack', toolName: 'Post Message' },
      false,
    );

    expect(screen.getByText('Slack')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
