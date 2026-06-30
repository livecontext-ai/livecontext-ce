// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const getSamlConnectionMock = vi.hoisted(() => vi.fn());
const saveSamlConnectionMock = vi.hoisted(() => vi.fn());
const deleteSamlConnectionMock = vi.hoisted(() => vi.fn());

vi.mock('next-intl', () => ({
  useLocale: () => 'en',
  useTranslations: () => (key: string) => {
    const translations: Record<string, string> = {
      title: 'SAML SSO',
      description: 'Connect this workspace to an enterprise identity provider.',
      lockedDescription: 'SAML SSO is available on Team and Enterprise workspaces.',
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
});
