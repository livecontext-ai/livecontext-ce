// @vitest-environment jsdom
import * as React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { CredentialFormDialog, type AvailableIntegration } from '../CredentialFormDialog';
import type { PlatformCredential } from '@/lib/api/orchestrator/types';

vi.mock('@/lib/api/orchestrator', () => ({
  orchestratorApi: {
    getCredentialVariants: vi.fn(() => Promise.resolve([
      { id: 'tmpl-gmail', variant: 'oauth2', auth_type: 'oauth2' },
    ])),
  },
}));

vi.mock('@/components/ui/service-icon', () => ({
  ServiceIcon: () => <span data-testid="service-icon" />,
}));

const messages = {
  platformCredentials: {
    form: {
      addTitle: 'Add Credential',
      editTitle: 'Edit {name}',
      configureTitle: 'Configure {name}',
      integration: 'Integration',
      selectIntegration: 'Select an integration...',
      selectIntegrationError: 'Please select an integration',
      requiredFieldsError: 'Required fields missing: {fields}',
      saveError: 'Failed to save credential',
      deleteConfirm: 'Are you sure?',
      deleteError: 'Failed to delete credential',
      leaveEmpty: 'Leave empty to keep the current value',
      enabled: 'Enabled',
      disabledCredentials: 'Disabled credentials will not be used for authentication',
      unverifiedWarning: 'Show unverified-app warning',
      unverifiedWarningDescription: 'Display the provider verification heads-up during OAuth sign-in.',
      unverifiedWarningToggle: 'Toggle unverified-app warning',
      delete: 'Delete',
      cancel: 'Cancel',
      save: 'Save',
      authType: 'Auth Type',
      noCredentialsNeeded: 'No credentials needed',
      variantPicker: 'Authentication method',
      variantConfigured: 'configured',
      variantDisabled: 'disabled',
      variantFetchError: 'Could not load authentication methods.',
    },
  },
};

const credential: PlatformCredential = {
  id: 42,
  integrationName: 'gmail',
  displayName: 'Gmail',
  authType: 'oauth2',
  clientIdMasked: 'abcd****wxyz',
  hasClientSecret: true,
  hasApiKey: false,
  hasBasicAuth: false,
  hasCustomFields: false,
  authUrl: 'https://accounts.google.com/o/oauth2/v2/auth',
  tokenUrl: 'https://oauth2.googleapis.com/token',
  defaultScopes: 'email profile',
  iconSlug: 'gmail',
  category: 'productivity',
  description: 'Gmail OAuth client',
  showUnverifiedAppWarning: false,
  isEnabled: true,
  endpoints: [],
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  variant: 'oauth2',
  tenantId: null,
};

const availableIntegrations: AvailableIntegration[] = [{
  id: 'api-gmail',
  apiName: 'Gmail',
  apiSlug: 'gmail',
  iconSlug: 'gmail',
  authType: 'oauth2',
  platformCredentialName: 'gmail',
  hasCredential: true,
}];

function renderDialog(onSave = vi.fn(async (_request: unknown) => {})) {
  render(
    <NextIntlClientProvider locale="en" messages={messages as any}>
      <CredentialFormDialog
        open
        onOpenChange={() => {}}
        credential={credential}
        variants={[credential]}
        availableIntegrations={availableIntegrations}
        onSave={onSave}
      />
    </NextIntlClientProvider>,
  );
  return onSave;
}

describe('CredentialFormDialog unverified-app warning switch', () => {
  it('saves the OAuth warning switch value with the platform credential', async () => {
    const onSave = renderDialog();

    const warningSwitch = await screen.findByRole('switch', {
      name: 'Toggle unverified-app warning',
    });
    await screen.findByText('Leave empty to keep the current value');
    expect(warningSwitch.getAttribute('aria-checked')).toBe('false');

    fireEvent.click(warningSwitch);
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
    expect(onSave.mock.calls[0][0]).toMatchObject({
      integrationName: 'gmail',
      authType: 'oauth2',
      variant: 'oauth2',
      showUnverifiedAppWarning: true,
    });
  });
});
