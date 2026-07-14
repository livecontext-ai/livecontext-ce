// @vitest-environment jsdom
import * as React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

// Regression suite for the "defaulted custom field wrongly flagged required" bug.
// The isCustom wizard branch (SMTP/IMAP and any non oauth2/api_key/bearer/basic
// template) validated + collected each property via customFields[prop.name], WITHOUT
// the `?? prop.default` fallback the render path uses. A required field displaying its
// DEFAULT value (e.g. Port "587") was therefore treated as empty: Save raised
// "Port is required" unless the user manually retyped it, and even a passing value was
// dropped from credential_data. The extra-fields loop already used the fallback; the
// isCustom loops now mirror it.

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
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NextIntlClientProvider locale="en" messages={messages as any}>
        <CredentialWizard requirements={[{ iconSlug, serviceName }]} open onOpenChange={() => {}} />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

// A custom (SMTP) template: Host has no default, Port defaults to "587". Both required.
const SMTP_TEMPLATE = {
  id: 't-smtp', credential_name: 'smtp', display_name: 'SMTP', icon_slug: 'smtp',
  auth_type: 'custom', source: 'catalog',
  properties: [
    { name: 'host', displayName: 'Host', type: 'string', required: true },
    { name: 'port', displayName: 'Port', type: 'string', required: true, default: '587' },
    { name: 'username', displayName: 'Username', type: 'string', required: true },
    { name: 'password', displayName: 'Password', typeOptions: { password: true }, type: 'string', required: true },
  ],
};

describe('CredentialWizard - defaulted custom field (SMTP Port)', () => {
  beforeEach(() => vi.clearAllMocks());

  it('does NOT flag a defaulted required Port as empty and persists its default value', async () => {
    __TEMPLATE = SMTP_TEMPLATE;
    renderWizard('smtp', 'SMTP');

    // Custom form renders every property. The Port input shows its default "587".
    await screen.findByText('Host', undefined, { timeout: 3000 });
    expect((document.querySelector('#custom-port') as HTMLInputElement).value).toBe('587');

    // Fill the non-defaulted required fields, but leave Port untouched at its default.
    fireEvent.change(document.querySelector('#custom-host') as HTMLInputElement, { target: { value: 'smtp.example.com' } });
    fireEvent.change(document.querySelector('#custom-username') as HTMLInputElement, { target: { value: 'me@example.com' } });
    fireEvent.change(document.querySelector('#custom-password') as HTMLInputElement, { target: { value: 'secret' } });

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    // (a) No "Port is required" error surfaces, (b) save goes through.
    await waitFor(() => expect(orchestratorApi.createCredential).toHaveBeenCalledTimes(1));
    expect(screen.queryByText('Port is required')).toBeNull();

    // (b) The default Port value is included in the submitted credential_data.
    const arg = (orchestratorApi.createCredential as any).mock.calls[0][0];
    expect(arg.credential_data).toEqual({
      host: 'smtp.example.com',
      port: '587',
      username: 'me@example.com',
      password: 'secret',
    });
  });

  it('still blocks save when a required field with NO default is left empty', async () => {
    __TEMPLATE = SMTP_TEMPLATE;
    renderWizard('smtp', 'SMTP');
    await screen.findByText('Host', undefined, { timeout: 3000 });

    // Leave Host (no default) empty; fill the rest.
    fireEvent.change(document.querySelector('#custom-username') as HTMLInputElement, { target: { value: 'me@example.com' } });
    fireEvent.change(document.querySelector('#custom-password') as HTMLInputElement, { target: { value: 'secret' } });

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('Host is required', undefined, { timeout: 3000 })).toBeTruthy();
    expect(orchestratorApi.createCredential).not.toHaveBeenCalled();
  });
});
