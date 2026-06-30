// @vitest-environment jsdom
import * as React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

// Mock the React Query hook so we control what the component sees without
// spinning up a QueryClientProvider in every test.
vi.mock('@/hooks/credentials/useMyOAuthApps', () => ({
  useMyOAuthApps: vi.fn(),
}));

// Mock OAuthAppCard since it imports React Query + orchestratorApi; we only
// need to assert the section renders the card list, not exercise the card.
vi.mock('../OAuthAppCard', () => ({
  OAuthAppCard: ({ app }: any) => <li data-testid="oauth-app-card">{app.displayName}</li>,
}));

// Mock the skeleton - it renders fine but adds noise to assertions.
vi.mock('@/components/skeletons', () => ({
  CredentialsListSkeleton: () => <div data-testid="loading-skeleton" />,
}));

import { MyOAuthAppsSection } from '../MyOAuthAppsSection';
import { useMyOAuthApps } from '@/hooks/credentials/useMyOAuthApps';

const messages = {
  myOAuthApps: {
    title: 'Your custom OAuth connections',
    subtitle: 'OAuth apps you registered yourself.',
    addNew: 'Add connection',
    error: { loadFailed: 'Could not load.', retry: 'Retry' },
  },
};

function renderSection(props: Partial<React.ComponentProps<typeof MyOAuthAppsSection>> = {}) {
  return render(
    <NextIntlClientProvider locale="en" messages={messages as any}>
      <MyOAuthAppsSection onAddNew={() => {}} {...props} />
    </NextIntlClientProvider>,
  );
}

describe('MyOAuthAppsSection - discovery contract', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders nothing when the user has zero custom OAuth apps - the section must not occupy real estate above the credentials list for the ~95% of users who never use BYOK', () => {
    (useMyOAuthApps as any).mockReturnValue({ data: [], isLoading: false, error: null, refetch: vi.fn() });

    const { container } = renderSection();

    expect(container.firstChild).toBeNull();
    expect(screen.queryByText('Your custom OAuth connections')).toBeNull();
  });

  it('renders nothing when the apps list is undefined and not loading (defensive: data fetch resolved with no value)', () => {
    (useMyOAuthApps as any).mockReturnValue({ data: undefined, isLoading: false, error: null, refetch: vi.fn() });

    const { container } = renderSection();

    expect(container.firstChild).toBeNull();
  });

  it('renders the loading skeleton while the apps query is in flight - keeps the slot visible during initial fetch so the page does not jump', () => {
    (useMyOAuthApps as any).mockReturnValue({ data: undefined, isLoading: true, error: null, refetch: vi.fn() });

    renderSection();

    expect(screen.getByTestId('loading-skeleton')).toBeTruthy();
  });

  it('renders the error block on fetch failure so users do not silently lose access to existing BYOK apps', () => {
    (useMyOAuthApps as any).mockReturnValue({
      data: undefined,
      isLoading: false,
      error: new Error('boom'),
      refetch: vi.fn(),
    });

    renderSection();

    expect(screen.getByRole('alert')).toBeTruthy();
    expect(screen.getByText('Could not load.')).toBeTruthy();
  });

  it('reappears in full with header + Add CTA + card list once at least one BYOK app exists - the section earns its real estate the moment the user has joined the BYOK subset', () => {
    (useMyOAuthApps as any).mockReturnValue({
      data: [
        { id: 1, displayName: 'My Google Cloud', integrationName: 'gmail', iconSlug: 'gmail', authType: 'oauth2', clientIdMasked: 'abc***xyz', createdAt: '2026-05-01T00:00:00Z' },
      ],
      isLoading: false,
      error: null,
      refetch: vi.fn(),
    });

    renderSection();

    expect(screen.getByText('Your custom OAuth connections')).toBeTruthy();
    expect(screen.getByText('Add connection')).toBeTruthy();
    expect(screen.getByTestId('oauth-app-card')).toBeTruthy();
  });
});
