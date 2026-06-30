// @vitest-environment jsdom
import * as React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

// Regression suite for the "extra required credential fields not rendered" bug.
// The basic_auth / bearer / api_key wizard branches hardcode only their primary
// fields (username+password, token, api_key). Importer-registered URL-template /
// account-identifier props (Bandwidth account_id, Sinch project + service-plan id,
// {domain}/{instance}/{shop} base-URL vars across ~77 integrations) were never
// rendered nor collected, so the runtime URL/header resolution fell back to the
// wrong value (401/404). Now: getExtraCredentialProperties() surfaces them after
// the standard inputs and handleDirectSave merges them into credential_data.
//
// Also pins the anomaly guard: an api_key/bearer credential whose PRIMARY field is
// mis-named after its header (tailscale "Authorization", neynar "x-api-key") must
// NOT render as a second "extra" field - the leading primary slot is skipped.

let __TEMPLATE: Record<string, unknown>;

vi.mock('@/lib/api/orchestrator', async () => ({
  orchestratorApi: {
    getCredentialTemplateByName: vi.fn(() => Promise.resolve(__TEMPLATE)),
    getCredentialTemplates: vi.fn(() => Promise.resolve({ credentials: [__TEMPLATE] })),
    getPlatformCredentialsAvailability: vi.fn(() => Promise.resolve({ available: true, showUnverifiedAppWarning: false })),
    getCredentialVariants: vi.fn(() => Promise.resolve([])),
    createCredential: vi.fn(() => Promise.resolve({ id: 1 })),
  },
}));

import { CredentialWizard } from '../CredentialWizard';
import { orchestratorApi } from '@/lib/api/orchestrator';

const messages = {
  credentials: {
    wizard: {
      title: 'Connect', saving: 'Saving...', save: 'Save', close: 'Close', done: 'Done',
      username: 'Username', usernamePlaceholder: 'Enter username',
      password: 'Password', passwordPlaceholder: 'Enter password',
      apiKey: 'API Key', apiKeyPlaceholder: 'Enter API key',
      bearerToken: 'Token', bearerPlaceholder: 'Enter token',
      errors: {
        basicRequired: 'Username and password are required',
        apiKeyRequired: 'API key required',
        bearerRequired: 'Token required',
        customFieldRequired: '{field} is required',
        saveFailed: 'Save failed',
      },
    },
    configureDialog: {
      cancel: 'Cancel', optional: 'optional', credential: 'Credential', connect: 'Connect', connecting: 'Connecting',
      credentialName: 'Credential Name', credentialNamePlaceholder: 'e.g. {name}',
    },
  },
};

function renderWizard(iconSlug: string, serviceName: string) {
  // CredentialWizard now calls useQueryClient (to refresh credential caches after
  // a save), so it must render under a QueryClientProvider. Fresh client per render
  // keeps each case isolated.
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NextIntlClientProvider locale="en" messages={messages as any}>
        <CredentialWizard requirements={[{ iconSlug, serviceName }]} open onOpenChange={() => {}} />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

describe('CredentialWizard - extra required credential fields', () => {
  beforeEach(() => vi.clearAllMocks());

  it('basic_auth with an extra required string field (Bandwidth account_id) renders it after username/password', async () => {
    __TEMPLATE = {
      id: 't-bandwidth', credential_name: 'bandwidth', display_name: 'Bandwidth', icon_slug: 'bandwidth',
      auth_type: 'basic_auth', source: 'catalog',
      properties: [
        { name: 'username', displayName: 'API Username', type: 'string', required: true },
        { name: 'password', displayName: 'API Password', type: 'password', required: true },
        { name: 'bandwidth_account_id', displayName: 'Bandwidth Account Id', type: 'string', required: true },
      ],
    };
    renderWizard('bandwidth', 'Bandwidth');
    expect(await screen.findByText('Bandwidth Account Id', undefined, { timeout: 3000 })).toBeTruthy();
    // The standard basic_auth inputs use i18n labels (not the property displayName).
    expect(screen.getByPlaceholderText('Enter username')).toBeTruthy();
    expect(screen.getByPlaceholderText('Enter password')).toBeTruthy();
  });

  it('bearer_token with two extra required fields (Sinch project + service-plan id) renders both, not the access_token', async () => {
    __TEMPLATE = {
      id: 't-sinch', credential_name: 'sinch', display_name: 'Sinch', icon_slug: 'sinch',
      auth_type: 'bearer_token', source: 'catalog',
      properties: [
        { name: 'access_token', displayName: 'API Token', type: 'password', required: true },
        { name: 'sinch_service_plan_id', displayName: 'Sinch Service Plan Id', type: 'string', required: true },
        { name: 'sinch_project_id', displayName: 'Sinch Project Id', type: 'string', required: true },
      ],
    };
    renderWizard('sinch', 'Sinch');
    expect(await screen.findByText('Sinch Service Plan Id', undefined, { timeout: 3000 })).toBeTruthy();
    expect(screen.getByText('Sinch Project Id')).toBeTruthy();
  });

  it('api_key whose single primary field is mis-named after its header (tailscale "Authorization") renders NO extra field', async () => {
    __TEMPLATE = {
      id: 't-tailscale', credential_name: 'tailscale', display_name: 'Tailscale', icon_slug: 'tailscale',
      auth_type: 'api_key', source: 'catalog',
      properties: [
        { name: 'Authorization', displayName: 'Authorization', type: 'password', required: true },
      ],
    };
    renderWizard('tailscale', 'Tailscale');
    // The generic API Key input renders (the primary), but the mis-named field must
    // NOT appear a second time as an "extra".
    expect(await screen.findByText('API Key', undefined, { timeout: 3000 })).toBeTruthy();
    expect(screen.queryByText('Authorization')).toBeNull();
  });

  it('plain basic_auth (only username/password) renders no extra field', async () => {
    __TEMPLATE = {
      id: 't-plainbasic', credential_name: 'plainbasic', display_name: 'PlainBasic', icon_slug: 'plainbasic',
      auth_type: 'basic_auth', source: 'catalog',
      properties: [
        { name: 'username', displayName: 'Username', type: 'string', required: true },
        { name: 'password', displayName: 'Password', type: 'password', required: true },
      ],
    };
    renderWizard('plainbasic', 'PlainBasic');
    // Credential Name input is unique to the configure step - wait for it to confirm render.
    expect(await screen.findByText('Credential Name', undefined, { timeout: 3000 })).toBeTruthy();
    // No extra field id => only the two standard inputs exist.
    expect(document.querySelector('[id^="custom-"]')).toBeNull();
  });

  it('saving a basic_auth credential merges the extra field into credential_data', async () => {
    __TEMPLATE = {
      id: 't-bandwidth2', credential_name: 'bandwidth', display_name: 'Bandwidth', icon_slug: 'bandwidth',
      auth_type: 'basic_auth', source: 'catalog',
      properties: [
        { name: 'username', displayName: 'API Username', type: 'string', required: true },
        { name: 'password', displayName: 'API Password', type: 'password', required: true },
        { name: 'bandwidth_account_id', displayName: 'Bandwidth Account Id', type: 'string', required: true },
      ],
    };
    renderWizard('bandwidth', 'Bandwidth');
    await screen.findByText('Bandwidth Account Id', undefined, { timeout: 3000 });

    fireEvent.change(screen.getByPlaceholderText('Enter username'), { target: { value: 'apiuser' } });
    fireEvent.change(screen.getByPlaceholderText('Enter password'), { target: { value: 'apipass' } });
    fireEvent.change(document.querySelector('#custom-bandwidth_account_id') as HTMLInputElement, { target: { value: '9900000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(orchestratorApi.createCredential).toHaveBeenCalledTimes(1));
    const arg = (orchestratorApi.createCredential as any).mock.calls[0][0];
    expect(arg.credential_data).toEqual({ username: 'apiuser', password: 'apipass', bandwidth_account_id: '9900000' });
  });
});
