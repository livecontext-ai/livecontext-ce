// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const getSamlConnectionMock = vi.hoisted(() => vi.fn());
const saveSamlConnectionMock = vi.hoisted(() => vi.fn());
const deleteSamlConnectionMock = vi.hoisted(() => vi.fn());
// Live, per-test toggle for the edition flag the panel reads (IS_CE). Default Cloud (false).
const edition = vi.hoisted(() => ({ IS_CE: false }));

vi.mock('next-intl', () => ({
  useLocale: () => 'en',
  useTranslations: () => (key: string) => {
    const translations: Record<string, string> = {
      title: 'SAML SSO',
      description: 'Connect this workspace to an enterprise identity provider.',
      lockedDescription: 'SAML SSO is available on Team and Enterprise workspaces.',
      ceUnavailable: "SAML SSO is only available on LiveContext Cloud. Self-hosted installs can't enable SSO.",
      ownerOnly: 'Only workspace owners and admins can manage SAML SSO.',
      displayName: 'Provider name',
      displayNamePlaceholder: 'Company SSO',
      idpEntityId: 'IdP entity ID',
      idpEntityIdPlaceholder: 'https://idp.example.com/metadata',
      ssoUrl: 'Single sign-on URL',
      ssoUrlPlaceholder: 'https://idp.example.com/sso/saml',
      certificate: 'X.509 signing certificate',
      certificatePlaceholder: 'Paste the PEM certificate from your identity provider',
      certificateHelp: 'Leave this blank when editing to keep the current certificate.',
      hideOnLoginPage: 'Hide from the global login page',
      hideOnLoginPageHelp: 'Users sign in through this workspace SSO URL.',
      serviceProviderTitle: 'Service provider details',
      serviceProviderEntityId: 'SP entity ID',
      assertionConsumerServiceUrl: 'ACS URL',
      startUrl: 'Workspace SSO URL',
      save: 'Save SSO',
      saving: 'Saving...',
      loading: 'Loading',
      copy: 'Copy',
      copied: 'Copied',
      errorGeneric: 'Could not load SAML SSO configuration.',
      'statuses.NOT_CONFIGURED': 'Not configured',
      'statuses.ACTIVE': 'Active',
      'statuses.ERROR': 'Error',
    };
    return translations[key] ?? key;
  },
}));

vi.mock('@/lib/api/organization-api', () => ({
  organizationApi: {
    getSamlConnection: getSamlConnectionMock,
    saveSamlConnection: saveSamlConnectionMock,
    deleteSamlConnection: deleteSamlConnectionMock,
  },
}));

// Live binding: the panel reads IS_CE at render time, so the getter reflects the
// current `edition.IS_CE` value per test. Keep the module's other real exports.
vi.mock('@/lib/edition', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/edition')>();
  return {
    ...actual,
    get IS_CE() {
      return edition.IS_CE;
    },
  };
});

import OrganizationSsoPanel from '../OrganizationSsoPanel';

const samlConnection = {
  configured: false,
  organizationId: 'org-1',
  idpAlias: 'org-org1-saml',
  displayName: '',
  idpEntityId: '',
  ssoUrl: '',
  status: 'NOT_CONFIGURED',
  hideOnLoginPage: true,
  ssoStartPath: '/auth/sso?org=org-1&hint=org-org1-saml',
  serviceProviderEntityId: 'https://auth.example.com/realms/livecontext',
  assertionConsumerServiceUrl: 'https://auth.example.com/realms/livecontext/broker/org-org1-saml/endpoint',
  serviceProviderMetadataUrl: '',
  certificateFingerprintSha256: null,
  lastSyncedAt: null,
  lastError: null,
};

function renderPanel(props?: Partial<React.ComponentProps<typeof OrganizationSsoPanel>>) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <OrganizationSsoPanel
        orgId="org-1"
        currentUserRole="OWNER"
        supportsTeam
        {...props}
      />
    </QueryClientProvider>
  );
}

describe('OrganizationSsoPanel', () => {
  beforeEach(() => {
    getSamlConnectionMock.mockResolvedValue(samlConnection);
    saveSamlConnectionMock.mockReset();
    deleteSamlConnectionMock.mockReset();
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    edition.IS_CE = false;
  });

  it('renders a collapsed SAML SSO configuration panel that expands from the audit-style header', async () => {
    renderPanel();

    const trigger = screen.getByRole('button', { name: /SAML SSO/i });
    expect(trigger).toHaveAttribute('aria-expanded', 'false');

    fireEvent.click(trigger);

    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    expect(await screen.findByLabelText('Provider name')).toBeInTheDocument();
    expect(screen.getByText('Service provider details')).toBeInTheDocument();
  });

  it('keeps the SAML SSO acronym uppercase on the locked plan surface', () => {
    renderPanel({ supportsTeam: false });

    expect(screen.getByRole('heading', { name: 'SAML SSO' })).toBeInTheDocument();
    expect(screen.getByText('SAML SSO is available on Team and Enterprise workspaces.')).toBeInTheDocument();
    expect(getSamlConnectionMock).not.toHaveBeenCalled();
  });

  it('locks the panel with a CE message in self-hosted mode, even when the plan supports teams', () => {
    // CE (auth.mode=embedded) has no Keycloak, so the backend rejects SAML. The panel must
    // NOT offer the form (the pre-fix bug: it unlocked whenever the plan supported teams).
    edition.IS_CE = true;
    renderPanel({ supportsTeam: true });

    expect(screen.getByRole('heading', { name: 'SAML SSO' })).toBeInTheDocument();
    expect(
      screen.getByText("SAML SSO is only available on LiveContext Cloud. Self-hosted installs can't enable SSO."),
    ).toBeInTheDocument();
    // No SAML form, and no backend call (the query is disabled in CE).
    expect(screen.queryByLabelText('Provider name')).not.toBeInTheDocument();
    expect(screen.queryByText('SAML SSO is available on Team and Enterprise workspaces.')).not.toBeInTheDocument();
    expect(getSamlConnectionMock).not.toHaveBeenCalled();
  });
});
