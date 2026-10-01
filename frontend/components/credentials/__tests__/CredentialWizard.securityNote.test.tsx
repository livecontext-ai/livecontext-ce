// @vitest-environment jsdom
import * as React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

// The connect dialog reassures the user that what they paste is protected.
// The wording is "in transit and at rest", never "end-to-end": the backend has
// to decrypt the secret to call the provider on the user's behalf.

let __TEMPLATE: Record<string, unknown> | null;

vi.mock('@/lib/api/orchestrator', async () => ({
  orchestratorApi: {
    getCredentialTemplateByName: vi.fn(() =>
      __TEMPLATE ? Promise.resolve(__TEMPLATE) : Promise.reject(new Error('not found')),
    ),
    getCredentialTemplates: vi.fn(() => Promise.resolve({ credentials: __TEMPLATE ? [__TEMPLATE] : [] })),
    getPlatformCredentialsAvailability: vi.fn(() => Promise.resolve({ available: false, showUnverifiedAppWarning: false })),
    getCredentialVariants: vi.fn(() => Promise.resolve([])),
    createCredential: vi.fn(() => Promise.resolve({ id: 1 })),
  },
}));

import { CredentialWizard } from '../CredentialWizard';

const messages = {
  credentials: {
    wizard: {
      securityNote: { title: 'Encrypted in transit and at rest', body: 'Stored encrypted with AES-256.' },
      title: 'Connect', saving: 'Saving...', save: 'Save', close: 'Close', done: 'Done',
      apiKey: 'API Key', apiKeyPlaceholder: 'Enter API key',
      errors: { apiKeyRequired: 'API key required', saveFailed: 'Save failed', customFieldRequired: '{field} is required' },
    },
    configureDialog: {
      cancel: 'Cancel', optional: 'optional', credential: 'Credential', connect: 'Connect', connecting: 'Connecting',
      credentialName: 'Credential Name', credentialNamePlaceholder: 'e.g. {name}', close: 'Close',
      errors: { credentialsNotConfigured: 'Not configured', contactAdmin: 'Contact admin' },
    },
  },
};

function renderWizard() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NextIntlClientProvider locale="en" messages={messages as any} onError={() => {}}>
        <CredentialWizard requirements={[{ iconSlug: 'acme', serviceName: 'Acme' }]} open onOpenChange={() => {}} />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

describe('CredentialWizard - security note', () => {
  beforeEach(() => vi.clearAllMocks());

  it('tells the user their credentials are encrypted on the step where they enter them', async () => {
    __TEMPLATE = {
      id: 't-acme', credential_name: 'acme', display_name: 'Acme', icon_slug: 'acme',
      auth_type: 'api_key', source: 'catalog',
      properties: [{ name: 'api_key', displayName: 'Api Key', type: 'password', required: true }],
    };
    renderWizard();

    expect(await screen.findByText('API Key', undefined, { timeout: 3000 })).toBeTruthy();
    expect(screen.getByTestId('credential-security-note')).toBeTruthy();
    expect(screen.getByText('Encrypted in transit and at rest')).toBeTruthy();
  });

  it('is not shown while the dialog is still loading', () => {
    __TEMPLATE = {
      id: 't-acme', credential_name: 'acme', display_name: 'Acme', icon_slug: 'acme',
      auth_type: 'api_key', source: 'catalog', properties: [],
    };
    renderWizard();

    expect(screen.queryByTestId('credential-security-note')).toBeNull();
  });
});
