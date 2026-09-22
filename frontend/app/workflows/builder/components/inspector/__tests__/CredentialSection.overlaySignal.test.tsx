// @vitest-environment jsdom
/**
 * Telling a container that an overlay of this section is up.
 *
 * <p><b>Why anything needs telling.</b> The credential wizard and the "which of my keys" dropdown
 * both render in a PORTAL on the document, outside whatever contains this section. A container that
 * dismisses itself when the reader interacts elsewhere - a popover, which is how the studio offers
 * this - reads the first click inside the wizard as a click outside ITSELF: it closes, unmounts this
 * section, and takes the form down with it. So the container is told, and holds itself open.
 *
 * <p><b>Why the unmount case is the one that matters most.</b> A container holding itself open for
 * an overlay it is never told about again can never be closed: Escape, the trigger, a click
 * anywhere. That looks like a frozen application, which is worse than the bug the signal exists to
 * fix, so it is pinned here rather than left to the happy path.
 */
import * as React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import type { AbstractIntlMessages } from 'use-intl';
import { beforeEach, describe, expect, it, vi } from 'vitest';

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
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <div /> }));
vi.mock('@/components/credentials/CredentialWizard', async () => {
  const actual = await vi.importActual<typeof import('@/components/credentials/CredentialWizard')>(
    '@/components/credentials/CredentialWizard',
  );
  return { ...actual, CredentialWizard: () => null };
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
      user: 'My credential',
      platform: 'Platform',
      markupNote: 'A small markup is billed on each call.',
      priceUnits: { call: 'call', second: 'second', minute: 'minute', image: 'image', character: 'character' },
      platformExplanation: 'Using platform credentials.',
    },
    toasts: { credentialCreated: 'x', credentialConfigured: 'y' },
  },
};

function renderSection(props: Partial<React.ComponentProps<typeof CredentialSection>> = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <NextIntlClientProvider locale="en" messages={messages}>
        <CredentialSection
          toolCredentials={[{ credentialName: 'seedance', isRequired: true, displayName: 'Seedance' }]}
          integration="seedance"
          apiToolId="11111111-2222-3333-4444-555555555555"
          selectedCredentialId={null}
          onCredentialSelect={vi.fn()}
          credentialSource="user"
          onCredentialSourceChange={vi.fn()}
          {...props}
        />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  // Radix scrolls the highlighted option into view when the list mounts. jsdom has no layout and
  // no such method, and the resulting throw happens in an effect AFTER the assertion has passed -
  // so the suite is green and the run reports unhandled errors. Stubbed rather than ignored.
  Element.prototype.scrollIntoView = vi.fn();
  apiMocks.getAllCredentials.mockResolvedValue([]);
  apiMocks.getCredentialTemplates.mockResolvedValue([]);
  apiMocks.getCredentialTemplateByName.mockResolvedValue(null);
  apiMocks.getPlatformCredentialPublicInfo.mockResolvedValue({
    integrationName: 'seedance', available: false, hasPricing: false,
  });
});

describe('CredentialSection - telling a container an overlay is up', () => {
  it('says "closed" as soon as it mounts, so a container starts from a known state', async () => {
    const onWizardOpenChange = vi.fn();
    renderSection({ onWizardOpenChange });

    await waitFor(() => expect(onWizardOpenChange).toHaveBeenCalledWith(false));
  });

  it('says "closed" when it UNMOUNTS, whatever was open', async () => {
    // The regression this file exists for. Without it a container that held itself open for an
    // overlay is never released, and every dismissal is refused for the rest of its life.
    const onWizardOpenChange = vi.fn();
    const { unmount } = renderSection({ onWizardOpenChange });
    await waitFor(() => expect(onWizardOpenChange).toHaveBeenCalled());
    onWizardOpenChange.mockClear();

    unmount();

    expect(onWizardOpenChange).toHaveBeenLastCalledWith(false);
  });

  it('says "open" when the key form is opened', async () => {
    const onWizardOpenChange = vi.fn();
    renderSection({ onWizardOpenChange });

    const configure = await screen.findByRole('button', { name: /configure credential/i });
    configure.click();

    await waitFor(() => expect(onWizardOpenChange).toHaveBeenLastCalledWith(true));
  });

  it('says "open" when the list of the reader OWN keys is opened', async () => {
    // Not a lesser overlay than the wizard. It is portalled too, and it takes FOCUS, which is one
    // of the three ways a popover dismisses itself: a container told only about the wizard lost
    // the whole pane the moment the reader went to pick which of their keys to use, before a
    // single character of the form had been typed.
    apiMocks.getAllCredentials.mockResolvedValue([
      { id: 5, name: 'My Seedance key', integration: 'seedance' },
    ]);
    const onWizardOpenChange = vi.fn();
    renderSection({ onWizardOpenChange });

    const trigger = await screen.findByRole('combobox');
    onWizardOpenChange.mockClear();
    // The KEYBOARD route, not a click: Radix opens this list from a pointer sequence jsdom does
    // not implement, and a test that fired one would assert nothing while appearing to.
    fireEvent.keyDown(trigger, { key: 'ArrowDown' });

    await waitFor(() => expect(onWizardOpenChange).toHaveBeenLastCalledWith(true));
  });

  it('says "closed" again when that list is dismissed', async () => {
    // The release matters as much as the signal: held open forever, the container refuses every
    // dismissal afterwards, which reads as a frozen application.
    apiMocks.getAllCredentials.mockResolvedValue([
      { id: 5, name: 'My Seedance key', integration: 'seedance' },
    ]);
    const onWizardOpenChange = vi.fn();
    renderSection({ onWizardOpenChange });

    const trigger = await screen.findByRole('combobox');
    fireEvent.keyDown(trigger, { key: 'ArrowDown' });
    await waitFor(() => expect(onWizardOpenChange).toHaveBeenLastCalledWith(true));

    fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' });

    await waitFor(() => expect(onWizardOpenChange).toHaveBeenLastCalledWith(false));
  });

  it('renders for a caller that passes no callback at all, which is the inspector', async () => {
    // The signal is optional on purpose: the inspector lives in a panel that closes on nothing.
    expect(() => renderSection({ onWizardOpenChange: undefined })).not.toThrow();
    await waitFor(() => expect(apiMocks.getAllCredentials).toHaveBeenCalled());
  });
});
