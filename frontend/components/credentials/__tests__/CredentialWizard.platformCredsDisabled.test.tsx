// @vitest-environment jsdom
import * as React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

// Regression suite for the "platform credentials disabled → fall through to BYOK"
// behaviour. Before this change the wizard hit setStep('error') with a
// "customOAuth.notConfigured" message whenever `hasPlatformCredentials` returned
// false on a catalog API (tmpl.source !== 'custom'). Admins could effectively
// hide an integration by toggling its auth.platform_credentials row off - a
// surprise side effect of an admin operation that should only have meant
// "stop offering OUR shared OAuth client; users may still bring their own".
//
// Now: !available → setStep('oauth-config') + setActiveMode('advanced'), so the
// catalog metadata pre-fills authUrl/tokenUrl/scopes and the user can register
// their own client_id/secret without admin intervention.

const ICON_SLUG = 'slack';

function buildOAuth2Template() {
  const oauth2Config = {
    authorizationUrl: 'https://slack.com/oauth/v2/authorize',
    tokenUrl: 'https://slack.com/api/oauth.v2.access',
    scopes: ['chat:write', 'channels:read'],
  };
  return {
    id: 'tmpl-slack',
    credential_name: 'slack',
    display_name: 'Slack',
    auth_type: 'oauth2',
    icon_slug: ICON_SLUG,
    source: 'catalog',
    properties: [],
    metadata: { type: 'jsonb', value: JSON.stringify({ oauth2Config }) },
  };
}

let __TEMPLATE = buildOAuth2Template();
let __HAS_PLATFORM_CREDS = false;
let __SHOW_UNVERIFIED_WARNING = true;

vi.mock('@/lib/api/orchestrator', async () => ({
  orchestratorApi: {
    getCredentialTemplateByName: vi.fn(() => Promise.resolve(__TEMPLATE)),
    getCredentialTemplates: vi.fn(() => Promise.resolve({ credentials: [__TEMPLATE] })),
    getPlatformCredentialsAvailability: vi.fn(() => Promise.resolve({
      available: __HAS_PLATFORM_CREDS,
      showUnverifiedAppWarning: __SHOW_UNVERIFIED_WARNING,
    })),
    hasPlatformCredentials: vi.fn(() => Promise.resolve(__HAS_PLATFORM_CREDS)),
    getCredentialVariants: vi.fn(() => Promise.resolve([])),
    initiateOAuth2: vi.fn(() => Promise.resolve({ authUrl: 'https://oauth/redirect' })),
    createCredential: vi.fn(() => Promise.resolve({ id: 1 })),
    saveTenantPlatformCredential: vi.fn(() => Promise.resolve({})),
  },
}));

import { CredentialWizard } from '../CredentialWizard';

const messages = {
  credentials: {
    wizard: {
      title: 'Connect',
      saving: 'Saving...',
      close: 'Close',
      done: 'Done',
      nextButton: 'Next',
      connected: 'Connected',
      nextCredential: '{remaining} more',
      multiWizard: { title: 'Connect {name}' },
      oauthConfig: {
        description: 'Enter your OAuth2 credentials.',
        authUrl: 'Authorization URL',
        authUrlPlaceholder: 'https://...',
        tokenUrl: 'Token URL',
        tokenUrlPlaceholder: 'https://...',
        scopes: 'Scopes',
        scopesPlaceholder: 'e.g. read write',
        clientId: 'Client ID',
        clientIdPlaceholder: 'Enter your client ID',
        clientSecret: 'Client Secret',
        clientSecretPlaceholder: 'Enter your client secret',
        saveAndConnect: 'Save & Connect',
      },
      errors: { templateNotFound: 'Template not found', oauthUrlsRequired: 'URLs required' },
    },
    configureDialog: {
      cancel: 'Cancel',
      optional: 'optional',
      credential: 'Credential',
      credentialName: 'Credential Name',
      credentialNamePlaceholder: 'e.g. {name}',
      credentialNameHint: 'A friendly name',
      unverifiedNotice: 'Some providers have not finished verification.',
      modeToggle: {
        label: 'Connection mode',
        standard: 'Standard',
        advanced: 'Custom OAuth',
        ariaSwitchToStandard: 'Switch to standard connection',
        ariaSwitchToAdvanced: 'Switch to custom OAuth connection',
        needCustomOAuth: 'Need a custom OAuth app?',
        backToStandard: 'Use standard connection',
      },
      setupGuide: {
        title: 'Setup guide',
        copy: 'Copy',
        openConsole: 'Open developer portal',
      },
      customOAuth: {
        notConfigured: 'Custom OAuth not configured for this integration.',
      },
      errors: {
        clientIdRequired: 'Client ID is required',
        clientSecretRequired: 'Client Secret is required',
      },
    },
  },
};

function renderWizard() {
  // CredentialWizard now calls useQueryClient (to refresh credential caches after
  // a save), so it must render under a QueryClientProvider. Fresh client per render
  // keeps each case isolated.
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NextIntlClientProvider locale="en" messages={messages as any}>
        <CredentialWizard
          requirements={[{ iconSlug: ICON_SLUG, serviceName: 'Slack' }]}
          open
          onOpenChange={() => {}}
        />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

describe('CredentialWizard - platform creds disabled falls through to BYOK', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    __TEMPLATE = buildOAuth2Template();
    __HAS_PLATFORM_CREDS = false;
    __SHOW_UNVERIFIED_WARNING = true;
  });

  it('catalog API + hasPlatformCredentials=false → wizard lands on oauth-config step instead of showing the customOAuth.notConfigured error. The integration stays usable by registering a tenant OAuth app.', async () => {
    __HAS_PLATFORM_CREDS = false;

    renderWizard();

    // The Save & Connect button is unique to the oauth-config step.
    await screen.findByRole('button', { name: 'Save & Connect' }, { timeout: 3000 });
    // The previous error string must NOT render - that was the regression.
    expect(screen.queryByText('Custom OAuth not configured for this integration.')).toBeNull();
  });

  it('catalog metadata pre-fills the BYOK form so the user does not have to retype provider-canonical URLs/scopes when they fall through to BYOK.', async () => {
    __HAS_PLATFORM_CREDS = false;

    renderWizard();

    // hideOAuthUrls collapses the URL inputs once activeMode='advanced' AND
    // both authUrl + tokenUrl resolved from metadata. Their absence from the
    // DOM proves the pre-fill effect ran (it sets the state values which then
    // feed the hideOAuthUrls computation).
    await screen.findByRole('button', { name: 'Save & Connect' }, { timeout: 3000 });
    expect(screen.queryByPlaceholderText('https://...')).toBeNull();
  });

  it('catalog API + hasPlatformCredentials=true → wizard still lands on the standard configure step. The fallback only fires when no platform creds are available; existing users with a working shared client are unaffected.', async () => {
    __HAS_PLATFORM_CREDS = true;

    renderWizard();

    // Standard configure step exposes the Credential Name input;
    // Save & Connect is unique to oauth-config.
    await screen.findByText('Credential Name', undefined, { timeout: 3000 });
    expect(screen.queryByRole('button', { name: 'Save & Connect' })).toBeNull();
  });

  it('catalog API + showUnverifiedAppWarning=false -> standard connect form suppresses the unverified-app notice.', async () => {
    __HAS_PLATFORM_CREDS = true;
    __SHOW_UNVERIFIED_WARNING = false;

    renderWizard();

    await screen.findByText('Credential Name', undefined, { timeout: 3000 });
    expect(screen.queryByText('Some providers have not finished verification.')).toBeNull();
  });

  it('catalog API + showUnverifiedAppWarning=true -> standard connect form renders the unverified-app notice.', async () => {
    __HAS_PLATFORM_CREDS = true;
    __SHOW_UNVERIFIED_WARNING = true;

    renderWizard();

    await screen.findByText('Some providers have not finished verification.', undefined, { timeout: 3000 });
  });
});
