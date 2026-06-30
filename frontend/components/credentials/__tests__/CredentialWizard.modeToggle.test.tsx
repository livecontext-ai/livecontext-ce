// @vitest-environment jsdom
import * as React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

// Mock orchestratorApi BEFORE importing the wizard so the wizard's static
// imports resolve to the mock. The wizard's useEffect chain needs:
//   1. getCredentialTemplateByName  → returns the template by slug
//   2. hasPlatformCredentials       → returns whether LiveContext has a global OAuth client
//   3. getCredentialVariants        → returns the list of auth variants for this name
vi.mock('@/lib/api/orchestrator', async () => {
  const template = {
    id: 'tmpl-gmail',
    credential_name: 'gmail',
    display_name: 'Gmail',
    auth_type: 'oauth2',
    icon_slug: 'gmail',
    properties: [],
    metadata: {
      type: 'jsonb',
      value: JSON.stringify({
        oauth2Config: {
          authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth',
          tokenUrl: 'https://oauth2.googleapis.com/token',
          scopes: ['https://www.googleapis.com/auth/gmail.readonly'],
          // The wizard's ModeToggle now requires byok.surface !== 'hidden' to
          // render. These tests pin the inline-mode (V166 Google) behavior, so
          // we declare surface='inline' to match Gmail's catalog config.
          byok: { surface: 'inline' },
        },
      }),
    },
  };
  return {
    orchestratorApi: {
      getCredentialTemplateByName: vi.fn().mockResolvedValue(template),
      getCredentialTemplates: vi.fn().mockResolvedValue({ credentials: [template] }),
      getPlatformCredentialsAvailability: vi.fn().mockResolvedValue({
        available: true,
        showUnverifiedAppWarning: true,
      }),
      hasPlatformCredentials: vi.fn().mockResolvedValue(true),
      getCredentialVariants: vi.fn().mockResolvedValue([]),
      initiateOAuth2: vi.fn().mockResolvedValue({ authUrl: 'https://oauth/redirect' }),
      createCredential: vi.fn().mockResolvedValue({ id: 1 }),
      saveTenantPlatformCredential: vi.fn().mockResolvedValue({}),
    },
  };
});

import { CredentialWizard } from '../CredentialWizard';

const messages = {
  credentials: {
    wizard: {
      title: 'Connect',
      description: 'Connect your account to continue.',
      saving: 'Saving...',
      close: 'Close',
      done: 'Done',
      nextButton: 'Next',
      connected: 'Connected',
      nextCredential: '{remaining} more',
      multiWizard: { title: 'Connect {name}' },
      oauthConfig: {
        description: 'Enter your OAuth2 application credentials.',
        authUrl: 'Authorization URL',
        authUrlPlaceholder: 'https://provider.com/oauth/authorize',
        tokenUrl: 'Token URL',
        tokenUrlPlaceholder: 'https://provider.com/oauth/token',
        scopes: 'Scopes',
        scopesPlaceholder: 'e.g. read write',
        clientId: 'Client ID',
        clientIdPlaceholder: 'Enter your client ID',
        clientSecret: 'Client Secret',
        clientSecretPlaceholder: 'Enter your client secret',
        saveAndConnect: 'Save & Connect',
      },
      errors: {
        templateNotFound: 'Template not found',
        oauthUrlsRequired: 'URLs required',
      },
    },
    configureDialog: {
      cancel: 'Cancel',
      optional: 'optional',
      credential: 'Credential',
      authType: 'Connection Type',
      connect: 'Connect',
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
      },
      errors: {
        clientIdRequired: 'Client ID is required',
        clientSecretRequired: 'Client Secret is required',
      },
    },
  },
};

function renderWizard(initialMode: 'standard' | 'advanced' = 'standard') {
  // CredentialWizard now calls useQueryClient (to refresh credential caches after
  // a save), so it must render under a QueryClientProvider. Fresh client per render
  // keeps each case isolated.
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NextIntlClientProvider locale="en" messages={messages as any}>
        <CredentialWizard
          requirements={[{ iconSlug: 'gmail', serviceName: 'Gmail' }]}
          open
          onOpenChange={() => {}}
          initialMode={initialMode}
        />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

describe('CredentialWizard - mode toggle wires step AND activeMode together (the v2 regression guard)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('regression: opening in advanced mode lands on oauth-config with URL fields HIDDEN - proves activeMode + hideOAuthUrls + auto-fill all reach the right state through the production wire envelope', async () => {
    renderWizard('advanced');

    // Wait for the radiogroup to render (template fetch + platform-creds check resolve in microtasks).
    const advancedPill = await screen.findByRole('radio', { name: 'Custom OAuth' }, { timeout: 3000 });
    expect(advancedPill.getAttribute('aria-checked')).toBe('true');

    // The Authorization URL field MUST NOT render - hideOAuthUrls evaluated true
    // because activeMode='advanced' AND resolveOAuth2Defaults returned non-null
    // URLs through the wire envelope. If the metadata branch's envelope unwrap
    // regressed, this test would catch it (URL fields would render as empty).
    expect(screen.queryByLabelText(/Authorization URL/i)).toBeNull();
    expect(screen.queryByLabelText(/^Token URL/i)).toBeNull();
  });

  it('regression: clicking "Custom OAuth" mid-flow flips BOTH step AND activeMode - the v2 audit blocker. Without setActiveMode in the onSwitch handler, the wizard would land on oauth-config with activeMode stuck on standard, hideOAuthUrls would evaluate false, and URL fields would render visible+empty. The canonical guard is the absence of the URL fields, NOT the aria-checked state of the new toggle (the oauth-config-step ModeToggle is rendered with hardcoded active="advanced", so its aria-checked tracks the step transition, not the activeMode state).', async () => {
    renderWizard('standard');

    // Wait for configure step to render with the mode toggle.
    const advancedPill = await screen.findByRole('radio', { name: 'Switch to custom OAuth connection' }, { timeout: 3000 });
    expect(advancedPill.getAttribute('aria-checked')).toBe('false');

    // Click to switch - fires both setActiveMode('advanced') AND setStep('oauth-config').
    fireEvent.click(advancedPill);

    // Wait for the OAuth config step to render. Use the BYOK form's Client ID
    // field as the readiness signal - it only renders on oauth-config.
    await screen.findByLabelText(/Client ID/i, undefined, { timeout: 3000 });

    // Canonical regression guard: hideOAuthUrls === true REQUIRES
    // activeMode==='advanced' (it AND-gates with the resolved defaults).
    // If a future PR drops setActiveMode('advanced') from the onSwitch
    // handler, hideOAuthUrls would evaluate false and the Authorization URL
    // / Token URL fields would render. This assertion fails in that exact
    // case - the regression guard the v2 audit demanded.
    expect(screen.queryByLabelText(/Authorization URL/i)).toBeNull();
    expect(screen.queryByLabelText(/^Token URL/i)).toBeNull();
  });
});
