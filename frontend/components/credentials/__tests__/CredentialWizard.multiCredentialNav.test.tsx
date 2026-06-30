// @vitest-environment jsdom
//
// Multi-credential CredentialWizard navigation. The user must be able to move
// cleanly from one service to the next in a multi-service setup - including
// past an OAuth/BYOK step that cannot be completed inline. This used to be a
// dedicated "Skip" button; that button was removed (redundant) and navigation
// now happens by clicking a later service's chip in the progress stepper.
//
// Drives the REAL CredentialWizard and exercises:
//   (a) chip navigation A -> B -> C and back to A
//   (b) moving past an OAuth/BYOK (oauth-config) step to the next API via a chip
//   (c) a SINGLE credential renders no stepper (nothing to navigate to)
//   (d) wizard mounted CLOSED then opened still inits its requirements so the
//       chips exist (regression: empty credentialStates → no chips → stuck)
//
import * as React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

vi.mock('@/lib/api/orchestrator', async () => {
  // Slugs starting with "oauth" resolve to an oauth2 template; everything else
  // is api_key. Lets one suite cover both auth shapes.
  const mk = (slug: string) => ({
    id: `tmpl-${slug}`,
    credential_name: slug,
    display_name: slug.toUpperCase(),
    auth_type: slug.startsWith('oauth') ? 'oauth2' : 'api_key',
    icon_slug: slug,
    properties: [],
    metadata: null,
  });
  return {
    orchestratorApi: {
      // Return a template keyed by the requested slug so each requirement
      // resolves to its OWN template.
      getCredentialTemplateByName: vi.fn(async (slug: string) => mk(slug)),
      getCredentialTemplates: vi.fn(async ({ search }: { search?: string }) => ({
        credentials: [mk(search || 'unknown')],
      })),
      // No platform OAuth client for this instance → OAuth2 creds auto-route to
      // the BYOK (oauth-config) step, exactly as on the user's instance.
      getPlatformCredentialsAvailability: vi.fn().mockResolvedValue({ available: false }),
      getCredentialVariants: vi.fn().mockResolvedValue([]),
      createCredential: vi.fn().mockResolvedValue({ id: 1 }),
      initiateOAuth2: vi.fn(),
      saveTenantPlatformCredential: vi.fn(),
    },
  };
});

import { CredentialWizard } from '../CredentialWizard';

// CredentialWizard now calls useQueryClient (to refresh credential caches after a
// save), so it must render under a QueryClientProvider. The wizard runs no query
// of its own here, so one shared empty client is safe and keeps the rerender
// (open=false -> true) regression below mounting the SAME provider instance.
const testQueryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

const messages = {
  credentials: {
    wizard: {
      title: 'Connect {name}',
      description: 'Connect your account',
      successTitle: 'Connected',
      successDescription: '{name} connected',
      connected: '{name} connected',
      nextCredential: '{remaining} more',
      nextButton: 'Next',
      done: 'Done',
      close: 'Close',
      saving: 'Saving...',
      save: 'Save',
      apiKey: 'API Key',
      apiKeyPlaceholder: 'Enter API key',
      variantPickerLabel: 'Auth method',
      variantFallbackLabel: 'Default',
      oauthConfig: {
        description: 'Enter your OAuth2 application credentials.',
        clientId: 'Client ID',
        clientIdPlaceholder: 'Enter your client ID',
        clientSecret: 'Client Secret',
        clientSecretPlaceholder: 'Enter your client secret',
        authUrl: 'Authorization URL',
        authUrlPlaceholder: 'https://provider/authorize',
        tokenUrl: 'Token URL',
        tokenUrlPlaceholder: 'https://provider/token',
        scopes: 'Scopes',
        scopesPlaceholder: 'read write',
        saveAndConnect: 'Save & Connect',
      },
      errors: { templateNotFound: 'Template not found', fetchFailed: 'Fetch failed', apiKeyRequired: 'API key required', saveFailed: 'Save failed', oauthUrlsRequired: 'URLs required', oauthConfigFailed: 'OAuth config failed' },
    },
    configureDialog: {
      authType: 'Auth Type',
      cancel: 'Cancel',
      close: 'Close',
      connect: 'Connect',
      connecting: 'Connecting...',
      documentation: 'Docs',
      optional: 'optional',
      credential: 'Credential',
      credentialName: 'Credential Name',
      credentialNamePlaceholder: 'e.g. {name}',
      modeToggle: { label: 'Mode', standard: 'Standard', advanced: 'Custom OAuth', backToStandard: 'Back to standard', needCustomOAuth: 'Need custom OAuth?', byokOnlyTitle: 'BYOK only', byokOnlyDescription: 'Bring your own OAuth.' },
      setupGuide: { title: 'Setup guide', copy: 'Copy', openConsole: 'Open console' },
      errors: { credentialsNotConfigured: 'Not configured', contactAdmin: 'Contact admin', clientIdRequired: 'Client ID required', clientSecretRequired: 'Client Secret required' },
    },
  },
};

// The dialog title is "Connect <DISPLAY_NAME>"; display_name = slug.toUpperCase().
const titleFor = (slug: string) => `Connect ${slug.toUpperCase()}`;

describe('CredentialWizard - multi-credential navigation (no Skip button)', () => {
  beforeEach(() => vi.clearAllMocks());

  it('renders NO Skip button (it was removed) - navigation is via the stepper chips', async () => {
    render(
      <QueryClientProvider client={testQueryClient}>
      <NextIntlClientProvider locale="en" messages={messages as any}>
        <CredentialWizard
          requirements={[
            { iconSlug: 'apia', serviceName: 'API A' },
            { iconSlug: 'apib', serviceName: 'API B' },
          ]}
          open
          onOpenChange={() => {}}
        />
      </NextIntlClientProvider>
      </QueryClientProvider>,
    );
    expect(await screen.findByText(titleFor('apia'), undefined, { timeout: 4000 })).toBeTruthy();
    // The Skip affordance is gone everywhere in the wizard.
    expect(screen.queryByRole('button', { name: 'Skip' })).toBeNull();
    // The stepper chips are present instead (one per service).
    expect(screen.getByTestId('cred-step-apia')).toBeTruthy();
    expect(screen.getByTestId('cred-step-apib')).toBeTruthy();
  });

  it('chip navigation advances A -> B -> C and back to A', async () => {
    render(
      <QueryClientProvider client={testQueryClient}>
      <NextIntlClientProvider locale="en" messages={messages as any}>
        <CredentialWizard
          requirements={[
            { iconSlug: 'apia', serviceName: 'API A' },
            { iconSlug: 'apib', serviceName: 'API B' },
            { iconSlug: 'apic', serviceName: 'API C' },
          ]}
          open
          onOpenChange={() => {}}
        />
      </NextIntlClientProvider>
      </QueryClientProvider>,
    );

    // Land on API A's configure form.
    expect(await screen.findByText(titleFor('apia'), undefined, { timeout: 4000 })).toBeTruthy();

    // (a) click the B chip -> expect B.
    fireEvent.click(screen.getByTestId('cred-step-apib'));
    expect(await screen.findByText(titleFor('apib'), undefined, { timeout: 4000 })).toBeTruthy();

    // (b) click the C chip -> expect C.
    fireEvent.click(screen.getByTestId('cred-step-apic'));
    expect(await screen.findByText(titleFor('apic'), undefined, { timeout: 4000 })).toBeTruthy();

    // (c) click the A chip (now pending again) -> expect A.
    fireEvent.click(screen.getByTestId('cred-step-apia'));
    expect(await screen.findByText(titleFor('apia'), undefined, { timeout: 4000 })).toBeTruthy();
  });

  it('moves past an OAuth/BYOK (oauth-config) step to the next API via the stepper chip', async () => {
    // The load-bearing case: an OAuth2 credential with no platform client
    // auto-routes to the BYOK form, which cannot be completed inline. The user
    // must still be able to reach the next API - by clicking its chip.
    render(
      <QueryClientProvider client={testQueryClient}>
      <NextIntlClientProvider locale="en" messages={messages as any}>
        <CredentialWizard
          requirements={[
            { iconSlug: 'oauthx', serviceName: 'OAuth X' },
            { iconSlug: 'apib', serviceName: 'API B' },
          ]}
          open
          onOpenChange={() => {}}
        />
      </NextIntlClientProvider>
      </QueryClientProvider>,
    );

    // We land on the BYOK (oauth-config) form - its Client ID field is the
    // readiness signal (it only renders on the oauth-config step).
    await screen.findByLabelText(/Client ID/i, undefined, { timeout: 4000 });
    // No Skip button on this step anymore.
    expect(screen.queryByRole('button', { name: 'Skip' })).toBeNull();

    // Click API B's chip -> advances to API B (an api_key configure form).
    fireEvent.click(screen.getByTestId('cred-step-apib'));
    expect(await screen.findByText(titleFor('apib'), undefined, { timeout: 4000 })).toBeTruthy();
  });

  it('a SINGLE credential renders no stepper (nothing to navigate to) and no Skip', async () => {
    render(
      <QueryClientProvider client={testQueryClient}>
      <NextIntlClientProvider locale="en" messages={messages as any}>
        <CredentialWizard
          requirements={[{ iconSlug: 'oauthx', serviceName: 'OAuth X' }]}
          open
          onOpenChange={() => {}}
        />
      </NextIntlClientProvider>
      </QueryClientProvider>,
    );

    await screen.findByLabelText(/Client ID/i, undefined, { timeout: 4000 });
    // totalCount === 1 → renderProgressStepper returns null (no chips).
    expect(screen.queryByTestId('cred-step-oauthx')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Skip' })).toBeNull();
  });

  it('regression: wizard mounted CLOSED then opened still inits requirements so the chips exist', async () => {
    // The panel/gate mount the wizard ahead of time with open={false} and flip it
    // to true on click. The on-close reset wipes credentialStates; if the init
    // effect does not re-run on open, credentialStates stays [] → no stepper chips
    // → no way to move between services. Navigation via the B chip proves the
    // requirements were re-initialised on open.
    const reqs = [
      { iconSlug: 'oauthx', serviceName: 'OAuth X' },
      { iconSlug: 'apib', serviceName: 'API B' },
    ];
    const ui = (open: boolean) => (
      <QueryClientProvider client={testQueryClient}>
      <NextIntlClientProvider locale="en" messages={messages as any}>
        <CredentialWizard requirements={reqs} open={open} onOpenChange={() => {}} />
      </NextIntlClientProvider>
      </QueryClientProvider>
    );
    const { rerender } = render(ui(false));
    rerender(ui(true)); // panel flips open on the user's click

    // Land on the BYOK (oauth-config) step for OAuth X.
    await screen.findByLabelText(/Client ID/i, undefined, { timeout: 4000 });

    // The B chip exists (requirements were initialised) and navigates to API B.
    fireEvent.click(screen.getByTestId('cred-step-apib'));
    expect(await screen.findByText(titleFor('apib'), undefined, { timeout: 4000 })).toBeTruthy();
  });
});
