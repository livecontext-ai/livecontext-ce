// @vitest-environment jsdom
import * as React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import type { AbstractIntlMessages } from 'use-intl';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { CredentialTemplate } from '@/lib/api/orchestrator';

const GMAIL_LABELS = 'https://www.googleapis.com/auth/gmail.labels';
const GMAIL_SEND = 'https://www.googleapis.com/auth/gmail.send';
const GMAIL_READONLY = 'https://www.googleapis.com/auth/gmail.readonly';

const apiMocks = vi.hoisted(() => ({
  getAllCredentials: vi.fn(),
  getCredentialTemplates: vi.fn(),
  getCredentialTemplateByName: vi.fn(),
  getPlatformCredentialPublicInfo: vi.fn(),
}));

vi.mock('@/lib/api/orchestrator', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api/orchestrator')>(
    '@/lib/api/orchestrator',
  );
  return {
    ...actual,
    orchestratorApi: {
      ...actual.orchestratorApi,
      getAllCredentials: apiMocks.getAllCredentials,
      getCredentialTemplates: apiMocks.getCredentialTemplates,
      getCredentialTemplateByName: apiMocks.getCredentialTemplateByName,
      getPlatformCredentialPublicInfo: apiMocks.getPlatformCredentialPublicInfo,
    },
  };
});

vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isPreviewOnly: false }),
}));

vi.mock('@/components/LoadingSpinner', () => ({
  default: () => <div data-testid="loading-spinner" />,
}));

vi.mock('@/components/credentials/CredentialWizard', async () => {
  const actual = await vi.importActual<typeof import('@/components/credentials/CredentialWizard')>(
    '@/components/credentials/CredentialWizard',
  );
  return {
    ...actual,
    CredentialWizard: ({ open, initialMode }: { open: boolean; initialMode: 'standard' | 'advanced' }) =>
      open ? <div data-testid="credential-wizard-mode">{initialMode}</div> : null,
  };
});

import { CredentialSection } from '../CredentialSection';

const messages: AbstractIntlMessages = {
  credentials: {
    configure: 'Configure credential',
    configured: 'Configured',
    selectCredential: 'Select credential',
    addNewCredential: 'Add new credential',
    manageAll: 'Manage all credentials',
    source: {
      label: 'Source',
      user: 'User',
      platform: 'Platform',
      markupNote: 'Platform credential usage may be billed.',
      markupNoteWithRate: 'Platform credential usage costs {rate} credits.',
      platformExplanation: 'Using platform credentials.',
    },
    toasts: {
      credentialCreated: 'Credential created',
      credentialConfigured: 'Credential configured',
    },
  },
};

const gmailTemplate = {
  id: 'gmail-oauth2',
  credential_name: 'gmail',
  display_name: 'Gmail',
  auth_type: 'oauth2',
  properties: [],
  metadata: {
    type: 'jsonb',
    value: JSON.stringify({
      oauth2Config: {
        scopes: [GMAIL_LABELS, GMAIL_SEND],
        byokOnlyScopes: [GMAIL_READONLY],
        byok: { surface: 'disclosure' },
      },
    }),
  },
} as CredentialTemplate;

function renderCredentialSection() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <NextIntlClientProvider locale="en" messages={messages}>
        <CredentialSection
          toolCredentials={[
            {
              credentialName: 'gmail',
              isRequired: true,
              displayName: 'Gmail',
              authType: 'OAuth2',
              credentialType: 'oauth2',
            },
          ]}
          integration="gmail"
          requiredScopes={[GMAIL_READONLY]}
          selectedCredentialId={null}
          onCredentialSelect={vi.fn()}
        />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

describe('CredentialSection configure click', () => {
  beforeEach(() => {
    apiMocks.getAllCredentials.mockResolvedValue([]);
    apiMocks.getCredentialTemplates.mockResolvedValue({ credentials: [gmailTemplate] });
    apiMocks.getCredentialTemplateByName.mockResolvedValue(null);
    apiMocks.getPlatformCredentialPublicInfo.mockResolvedValue({ available: false });
  });

  it('opens BYOK directly when an unconfigured tool requires a scope the platform app cannot grant', async () => {
    renderCredentialSection();

    fireEvent.click(await screen.findByRole('button', { name: /configure credential/i }));

    await waitFor(() => {
      expect(screen.getByTestId('credential-wizard-mode').textContent).toBe('advanced');
    });
  });
});
