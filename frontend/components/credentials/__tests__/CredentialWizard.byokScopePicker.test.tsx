// @vitest-environment jsdom
import * as React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

// V513 BYOK scope picker. TikTok, Figma and LinkedIn refuse the WHOLE authorization over a
// single scope the own OAuth app was not given, so the BYOK form lists every catalog scope
// (platform scopes + byokOnlyScopes) as a box, all ticked by default, and the save carries
// the user's choice to the backend, which requests only those at connect.

const PLATFORM = ['user.info.basic', 'video.upload'];
const BYOK_ONLY = ['video.publish'];

const TEMPLATE = {
  id: 'tmpl-tiktok',
  credential_name: 'tiktok',
  display_name: 'TikTok',
  auth_type: 'oauth2',
  icon_slug: 'tiktok',
  properties: [],
  metadata: {
    type: 'jsonb',
    value: JSON.stringify({
      oauth2Config: {
        authorizationUrl: 'https://www.tiktok.com/v2/auth/authorize/',
        tokenUrl: 'https://open.tiktokapis.com/v2/oauth/token/',
        scopes: PLATFORM,
        byokOnlyScopes: BYOK_ONLY,
        byok: { surface: 'disclosure' },
      },
    }),
  },
};

let __TEMPLATE: Record<string, unknown> = TEMPLATE;
let __MY_APPS: unknown[] = [];
let __myAppsPromise: (() => Promise<unknown[]>) | null = null;
const saveTenantPlatformCredential = vi.fn((_req: unknown) => Promise.resolve({}));
const track = vi.hoisted(() => vi.fn());
vi.mock('@/lib/analytics/analytics', () => ({ track: (...a: unknown[]) => track(...a) }));

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
    getMyOAuthApps: vi.fn(() => (__myAppsPromise ? __myAppsPromise() : Promise.resolve(__MY_APPS))),
    saveTenantPlatformCredential: (req: unknown) => saveTenantPlatformCredential(req),
  },
}));

import {
  CredentialWizard,
  resolveByokScopeChoices,
  toSelectedScopesPayload,
} from '../CredentialWizard';

const messages = {
  credentials: {
    wizard: {
      title: 'Connect',
      description: 'Connect your account',
      saving: 'Saving...',
      close: 'Close',
      done: 'Done',
      nextButton: 'Next',
      connected: 'Connected',
      nextCredential: '{remaining} more',
      multiWizard: { title: 'Connect {name}' },
      oauthConfig: {
        description: 'Enter your OAuth2 credentials.',
        clientId: 'Client ID',
        clientIdPlaceholder: 'Enter your client ID',
        clientSecret: 'Client Secret',
        clientSecretPlaceholder: 'Enter your client secret',
        authUrl: 'Authorization URL',
        authUrlPlaceholder: 'https://auth',
        tokenUrl: 'Token URL',
        tokenUrlPlaceholder: 'https://token',
        scopes: 'Scopes',
        scopesPlaceholder: 'read write',
        saveAndConnect: 'Save & Connect',
        scopePickerTitle: 'Scopes to request',
        scopePickerHint: 'Untick any scope your OAuth app has not been given.',
      },
      errors: {
        templateNotFound: 'Template not found',
        oauthUrlsRequired: 'URLs required',
        byokScopesRequired: 'Tick at least one scope.',
      },
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
      setupGuide: { title: 'Setup guide', copy: 'Copy', openConsole: 'Open developer portal' },
      errors: {
        clientIdRequired: 'Client ID is required',
        clientSecretRequired: 'Client Secret is required',
      },
    },
  },
};

function renderAdvancedWizard() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NextIntlClientProvider locale="en" messages={messages as any}>
        <CredentialWizard
          requirements={[{ iconSlug: 'tiktok', serviceName: 'TikTok' }]}
          open
          onOpenChange={() => {}}
          initialMode="advanced"
        />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

function box(scope: string): HTMLElement {
  return screen.getByRole('checkbox', { name: scope });
}

async function fillClientAndSave() {
  fireEvent.change(screen.getByPlaceholderText('Enter your client ID'), { target: { value: 'cid' } });
  fireEvent.change(screen.getByPlaceholderText('Enter your client secret'), { target: { value: 'csec' } });
  fireEvent.click(screen.getByText('Save & Connect'));
}

describe('resolveByokScopeChoices / toSelectedScopesPayload', () => {
  it('lists the platform scopes then the byok-only ones, once each', () => {
    expect(resolveByokScopeChoices(TEMPLATE as any)).toEqual([...PLATFORM, ...BYOK_ONLY]);
  });

  it('every box ticked sends an empty selection, so a scope the catalog adds later is requested too', () => {
    const all = [...PLATFORM, ...BYOK_ONLY];
    expect(toSelectedScopesPayload(all, [...all].reverse())).toEqual([]);
  });

  it('a narrower choice is sent in catalog order', () => {
    expect(toSelectedScopesPayload([...PLATFORM, ...BYOK_ONLY], ['video.publish', 'user.info.basic']))
      .toEqual(['user.info.basic', 'video.publish']);
  });
});

describe('CredentialWizard - BYOK scope picker (V513)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    __MY_APPS = [];
    __TEMPLATE = TEMPLATE;
    __myAppsPromise = null;
  });

  it('lists every catalog scope, platform and byok-only, all ticked by default (the pre-V513 request)', async () => {
    renderAdvancedWizard();
    await screen.findByText('Scopes to request', undefined, { timeout: 3000 });

    for (const scope of [...PLATFORM, ...BYOK_ONLY]) {
      expect(box(scope).getAttribute('data-state')).toBe('checked');
    }
  });

  it('an unticked scope is left out of the saved selection: the TikTok app without video.publish can connect', async () => {
    renderAdvancedWizard();
    await screen.findByText('Scopes to request', undefined, { timeout: 3000 });

    fireEvent.click(box('video.publish'));
    await fillClientAndSave();

    await waitFor(() => expect(saveTenantPlatformCredential).toHaveBeenCalled());
    expect(saveTenantPlatformCredential.mock.calls[0][0]).toMatchObject({
      selectedScopes: ['user.info.basic', 'video.upload'],
    });
    // Counts only: the scope names never leave the browser.
    await waitFor(() => expect(track).toHaveBeenCalledWith('oauth_scopes_chosen', {
      integration: 'tiktok', available_count: 3, selected_count: 2, all_selected: false,
    }));
    expect(JSON.stringify(track.mock.calls.filter(([e]) => e === 'oauth_scopes_chosen'))).not.toContain('video');
  });

  it('saving with every box ticked sends an empty selection', async () => {
    renderAdvancedWizard();
    await screen.findByText('Scopes to request', undefined, { timeout: 3000 });

    await fillClientAndSave();

    await waitFor(() => expect(saveTenantPlatformCredential).toHaveBeenCalled());
    expect(saveTenantPlatformCredential.mock.calls[0][0]).toMatchObject({ selectedScopes: [] });
    await waitFor(() => expect(track).toHaveBeenCalledWith('oauth_scopes_chosen', {
      integration: 'tiktok', available_count: 3, selected_count: 3, all_selected: true,
    }));
  });

  it('re-opening shows the boxes as this connection saved them', async () => {
    __MY_APPS = [{
      id: 9, integrationName: 'tiktok', iconSlug: 'tiktok', organizationId: null,
      selectedScopes: ['user.info.basic', 'video.publish'],
    }];
    renderAdvancedWizard();
    await screen.findByText('Scopes to request', undefined, { timeout: 3000 });

    await waitFor(() => expect(box('video.upload').getAttribute('data-state')).toBe('unchecked'));
    expect(box('user.info.basic').getAttribute('data-state')).toBe('checked');
    expect(box('video.publish').getAttribute('data-state')).toBe('checked');
  });

  it('refuses to save with no box ticked: a connection that requests nothing is refused by every provider', async () => {
    renderAdvancedWizard();
    await screen.findByText('Scopes to request', undefined, { timeout: 3000 });

    for (const scope of [...PLATFORM, ...BYOK_ONLY]) fireEvent.click(box(scope));
    await fillClientAndSave();

    await screen.findByText('Tick at least one scope.');
    expect(saveTenantPlatformCredential).not.toHaveBeenCalled();
  });
  it('a saved selection that arrives AFTER the user unticked a box does not overwrite their choice', async () => {
    let resolveApps: (apps: unknown[]) => void = () => {};
    __myAppsPromise = () => new Promise<unknown[]>((resolve) => { resolveApps = resolve; });
    renderAdvancedWizard();
    await screen.findByText('Scopes to request', undefined, { timeout: 3000 });

    fireEvent.click(box('video.upload'));
    resolveApps([{
      id: 9, integrationName: 'tiktok', iconSlug: 'tiktok', organizationId: null,
      selectedScopes: ['video.upload', 'video.publish'],
    }]);
    await new Promise((r) => setTimeout(r, 50));

    expect(box('video.upload').getAttribute('data-state')).toBe('unchecked');
    expect(box('user.info.basic').getAttribute('data-state')).toBe('checked');
  });

  it('a custom API (no catalog OAuth URLs) shows no picker and sends no selection, so the stored one is kept', async () => {
    __TEMPLATE = {
      ...TEMPLATE,
      metadata: { type: 'jsonb', value: JSON.stringify({ oauth2Config: { scopes: PLATFORM } }) },
    };
    renderAdvancedWizard();
    await screen.findByPlaceholderText('https://auth', undefined, { timeout: 3000 });
    expect(screen.queryByTestId('byok-scope-picker')).toBeNull();

    fireEvent.change(screen.getByPlaceholderText('https://auth'), { target: { value: 'https://a' } });
    fireEvent.change(screen.getByPlaceholderText('https://token'), { target: { value: 'https://t' } });
    await fillClientAndSave();

    await waitFor(() => expect(saveTenantPlatformCredential).toHaveBeenCalled());
    expect(saveTenantPlatformCredential.mock.calls[0][0]).toMatchObject({ selectedScopes: undefined });
    // No picker was offered, so there is no choice to report.
    expect(track).not.toHaveBeenCalledWith('oauth_scopes_chosen', expect.anything());
  });
});
