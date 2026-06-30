// @vitest-environment jsdom
import * as React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

// The wizard mounts with a mode toggle that depends on three runtime gates:
//   1. isOAuth2 (from template.auth_type)
//   2. hasPlatformCredentials (HTTP call)
//   3. byok.surface !== 'hidden' (catalog metadata)
// These tests exercise gate (3) for each of the three valid surfaces. Gates (1)
// and (2) are held constant (oauth2 template, platform creds available) so a
// failure here uniquely points at the byok-surface logic.

const ICON_SLUG = 'gmail';

function buildTemplate(byok: unknown) {
  const oauth2Config: Record<string, unknown> = {
    authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth',
    tokenUrl: 'https://oauth2.googleapis.com/token',
    scopes: ['https://www.googleapis.com/auth/gmail.readonly'],
  };
  if (byok !== undefined) oauth2Config.byok = byok;
  return {
    id: 'tmpl-gmail',
    credential_name: 'gmail',
    display_name: 'Gmail',
    auth_type: 'oauth2',
    icon_slug: ICON_SLUG,
    properties: [],
    metadata: { type: 'jsonb', value: JSON.stringify({ oauth2Config }) },
  };
}

// We mutate this between tests so each one hands the wizard a different byok shape.
let __TEMPLATE: ReturnType<typeof buildTemplate> = buildTemplate(undefined);

vi.mock('@/lib/api/orchestrator', async () => ({
  orchestratorApi: {
    getCredentialTemplateByName: vi.fn(() => Promise.resolve(__TEMPLATE)),
    getCredentialTemplates: vi.fn(() => Promise.resolve({ credentials: [__TEMPLATE] })),
    getPlatformCredentialsAvailability: vi.fn(() => Promise.resolve({
      available: true,
      showUnverifiedAppWarning: true,
    })),
    hasPlatformCredentials: vi.fn(() => Promise.resolve(true)),
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
          requirements={[{ iconSlug: ICON_SLUG, serviceName: 'Gmail' }]}
          open
          onOpenChange={() => {}}
          initialMode={initialMode}
        />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

describe('CredentialWizard - byok.surface gates the toggle', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('regression - surface absent (default for ~99% of OAuth2 APIs): NO inline pill toggle, NO disclosure link. The user only sees the Standard connect form. This is the safe-by-default contract that closes the original Airtable bug - Custom OAuth no longer leaks into providers that have not opted in', async () => {
    __TEMPLATE = buildTemplate(undefined); // surface absent ≡ "hidden"
    renderWizard('standard');

    // Configure step renders Standard. Wait for an element that's deterministic on the configure step.
    await screen.findByText('Saving...').catch(() => {});

    // No inline pills - neither role=radio nor named buttons appear.
    expect(screen.queryByRole('radiogroup')).toBeNull();
    expect(screen.queryByText('Need a custom OAuth app?')).toBeNull();
  });

  it('regression - surface="hidden" explicitly set: same behavior as absent. Confirms surface="hidden" is a real value, not just a default - useful when a future PR wants to deprecate inline mode for a provider without removing the byok block', async () => {
    __TEMPLATE = buildTemplate({ surface: 'hidden' });
    renderWizard('standard');
    await screen.findByText('Saving...').catch(() => {});
    expect(screen.queryByRole('radiogroup')).toBeNull();
    expect(screen.queryByText('Need a custom OAuth app?')).toBeNull();
  });

  it('regression - surface="inline": the 2-pill ModeToggle renders in the configure step (same UX as the original Google V166 ship). The disclosure link must NOT also render - exactly one BYOK affordance per surface', async () => {
    __TEMPLATE = buildTemplate({
      surface: 'inline',
      consoleUrl: 'https://console.cloud.google.com/apis/credentials',
    });
    renderWizard('standard');
    // Wait for the radiogroup (proves we landed on configure with template loaded).
    await screen.findByRole('radiogroup', undefined, { timeout: 3000 });
    // Disclosure link is suppressed when inline mode is active.
    expect(screen.queryByText('Need a custom OAuth app?')).toBeNull();
  });

  it('regression - surface="disclosure": the 2-pill ModeToggle is NOT rendered in the configure step; instead a low-emphasis "Need a custom OAuth app?" link appears. The two affordances are mutually exclusive - surface picks one or the other, never both', async () => {
    __TEMPLATE = buildTemplate({
      surface: 'disclosure',
      consoleUrl: 'https://api.slack.com/apps',
    });
    renderWizard('standard');
    // Wait for the disclosure link (proves configure step rendered).
    await screen.findByText('Need a custom OAuth app?', undefined, { timeout: 3000 });
    // No 2-pill radiogroup in disclosure mode.
    expect(screen.queryByRole('radiogroup')).toBeNull();
  });

  it('regression - disclosure mode AND initial advanced step: the back-link "Use standard connection" appears (replacement for the inline 2-pill in advanced mode). Confirms symmetry: disclosure mode never spawns a 2-pill toggle, neither at the configure step nor at the oauth-config step', async () => {
    __TEMPLATE = buildTemplate({ surface: 'disclosure' });
    renderWizard('advanced');
    await screen.findByText('Use standard connection', undefined, { timeout: 3000 });
    // No 2-pill radiogroup at the oauth-config step in disclosure mode.
    expect(screen.queryByRole('radiogroup')).toBeNull();
  });

  it('regression - open-TRANSITION race: wizard mounted CLOSED in standard, then reopened in advanced (the MissingScopesBanner "Use a custom OAuth connection" path) must land on the BYOK/oauth-config step, NOT the standard configure form. Pre-fix, checkPlatformCredentials read a stale activeMode (the [open] effect had not applied setActiveMode(initialMode) yet) and fell through to the standard form; the fix also checks initialMode', async () => {
    __TEMPLATE = buildTemplate({ surface: 'disclosure' });
    // Stable client across the rerender so the same provider tree is reused.
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { rerender } = render(
      <QueryClientProvider client={client}>
        <NextIntlClientProvider locale="en" messages={messages as any}>
          <CredentialWizard
            requirements={[{ iconSlug: ICON_SLUG, serviceName: 'Gmail' }]}
            open={false}
            onOpenChange={() => {}}
            initialMode="standard"
          />
        </NextIntlClientProvider>
      </QueryClientProvider>,
    );
    // Reopen in advanced - reproduces the prod transition (mounted standard+closed).
    rerender(
      <QueryClientProvider client={client}>
        <NextIntlClientProvider locale="en" messages={messages as any}>
          <CredentialWizard
            requirements={[{ iconSlug: ICON_SLUG, serviceName: 'Gmail' }]}
            open
            onOpenChange={() => {}}
            initialMode="advanced"
          />
        </NextIntlClientProvider>
      </QueryClientProvider>,
    );
    // Landed on the oauth-config (BYOK) step - its back-link proves it.
    await screen.findByText('Use standard connection', undefined, { timeout: 3000 });
    // NOT the standard configure step (whose disclosure link reads differently).
    expect(screen.queryByText('Need a custom OAuth app?')).toBeNull();
  });

  it('regression - schema-driven setup guide: when surface is non-hidden AND `steps[]` is non-empty, the collapsible <details> block renders the steps from the catalog metadata. Pins the contract that the wizard reads byok.steps[] and renders {title, body} pairs - no hardcoded provider-specific copy in the component anymore', async () => {
    __TEMPLATE = buildTemplate({
      surface: 'inline',
      consoleUrl: 'https://console.cloud.google.com/apis/credentials',
      steps: [
        { title: 'My step title', body: 'My step body.' },
        { title: 'Second step', body: 'Second body.' },
      ],
    });
    renderWizard('advanced');
    // Wait for the oauth-config step then find the schema-rendered title.
    await screen.findByText('My step title', undefined, { timeout: 3000 });
    expect(screen.queryByText('My step body.')).not.toBeNull();
    expect(screen.queryByText('Second step')).not.toBeNull();
  });

  it('regression - schema-driven setup guide hidden when `steps[]` is empty: a provider that opts into surface="disclosure" but has no setup guide content should NOT render an empty <details> block. Catches the "ship the surface flag without writing the guide" half-state', async () => {
    __TEMPLATE = buildTemplate({ surface: 'disclosure' }); // no steps
    renderWizard('advanced');
    await screen.findByText('Use standard connection', undefined, { timeout: 3000 });
    expect(screen.queryByText('Setup guide')).toBeNull();
  });

  it('regression - setup guide "Configure scopes" step renders catalog-declared scope chips so a BYOK admin knows which permissions to enable in their provider console. Without these chips, the user reading "Pick the scopes your workflow uses" had no way to know WHICH scopes - they had to guess from a 30+ permission list at the provider portal. Pins the contract: catalog metadata.oauth2Config.scopes flows into chip <code> nodes adjacent to any step whose title contains "scope" (case-insensitive)', async () => {
    __TEMPLATE = buildTemplate({
      surface: 'disclosure',
      consoleUrl: 'https://help.salesforce.com/...',
      steps: [
        { title: 'Open the developer portal', body: 'Sign in.' },
        { title: 'Configure scopes', body: 'Pick the scopes your workflow uses.' },
        { title: 'Copy credentials', body: 'Paste them below.' },
      ],
    });
    renderWizard('advanced');
    // Wait for the setup guide content to render.
    await screen.findByText('Configure scopes', undefined, { timeout: 3000 });
    // The single catalog scope from buildTemplate (gmail.readonly) must appear
    // as a chip under the "Configure scopes" step.
    expect(
      screen.queryByText('https://www.googleapis.com/auth/gmail.readonly'),
    ).not.toBeNull();
    // Non-scope steps must NOT spawn chips - only the title-matched step.
    // Sanity: the "Open the developer portal" body shouldn't render any chip.
    expect(screen.queryByText('Sign in.')).not.toBeNull();
  });

  it('regression - no scope chips render when catalog declares zero scopes (oauth2Config.scopes=[]). Renders the step body alone, no empty chip container. Prevents shipping a phantom UI artifact for the long-tail of providers that genuinely have no scope concept', async () => {
    // Custom template with empty scopes array.
    __TEMPLATE = {
      id: 'tmpl-noscope',
      credential_name: 'noscope',
      display_name: 'NoScope',
      auth_type: 'oauth2',
      icon_slug: 'noscope',
      properties: [],
      metadata: {
        type: 'jsonb',
        value: JSON.stringify({
          oauth2Config: {
            authorizationUrl: 'https://noscope.example/oauth/authorize',
            tokenUrl: 'https://noscope.example/oauth/token',
            scopes: [], // explicitly empty
            byok: {
              surface: 'disclosure',
              consoleUrl: 'https://noscope.example/console',
              steps: [{ title: 'Configure scopes', body: 'No scopes needed.' }],
            },
          },
        }),
      },
    } as any;
    renderWizard('advanced');
    await screen.findByText('Configure scopes', undefined, { timeout: 3000 });
    // Body still renders.
    expect(screen.queryByText('No scopes needed.')).not.toBeNull();
    // No gmail scope leaks (sanity that __TEMPLATE override worked).
    expect(
      screen.queryByText('https://www.googleapis.com/auth/gmail.readonly'),
    ).toBeNull();
  });
});
