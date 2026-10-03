// @vitest-environment jsdom
/**
 * Settings > Credentials keeps both tab levels in the address (`?tab=` and `?view=`), drops
 * the params of the list being left, and still strips the one-shot OAuth callback params.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// The page cleans the callback params through the real history API and reads the real
// location, so the address here is jsdom's own; `h.searchParams` is what Next would hand out.
const h = vi.hoisted(() => ({ searchParams: new URLSearchParams(), addToast: vi.fn() }));
const PATH = '/en/app/settings/credentials';

vi.mock('next/navigation', () => ({
  useSearchParams: () => h.searchParams,
  usePathname: () => '/en/app/settings/credentials',
}));
const t = vi.hoisted(() => (key: string) => key);
vi.mock('next-intl', () => ({ useTranslations: () => t }));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false }),
}));
vi.mock('@/lib/providers/smart-providers', () => ({ useAuth: () => ({ loginWithRedirect: vi.fn() }) }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrg: () => ({ currentOrgId: 'org-1' }),
  useCanMutateInCurrentOrg: () => true,
}));
vi.mock('@/components/Toast', () => ({
  default: () => null,
  useToast: () => ({ toasts: [], addToast: h.addToast, removeToast: () => {} }),
}));
vi.mock('@/components/settings', () => ({ PageHeader: () => null }));
vi.mock('@/components/skeletons', () => ({ CredentialsListSkeleton: () => null }));
vi.mock('@/components/credentials', () => ({ CredentialWizard: () => null }));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn(), slugOrNull: () => null }));
vi.mock('../components', () => ({
  CredentialsPrimaryTabs: ({ onTabChange }: { onTabChange: (tab: string) => void }) => (
    <>
      <button type="button" onClick={() => onTabChange('credentials')}>primary-credentials</button>
      <button type="button" onClick={() => onTabChange('variables')}>primary-variables</button>
    </>
  ),
  CredentialTabs: ({ onTabChange }: { onTabChange: (tab: string) => void }) => (
    <>
      <button type="button" onClick={() => onTabChange('my')}>view-my</button>
      <button type="button" onClick={() => onTabChange('available')}>view-available</button>
    </>
  ),
  MyCredentialsList: ({ focusCredentialId }: { focusCredentialId?: string | null }) => (
    <div data-testid="my-list" data-focus={focusCredentialId ?? ''} />
  ),
  AvailableCredentialsList: () => <div data-testid="available-list" />,
  MyOAuthAppsSection: () => null,
  VariablesSection: () => <div data-testid="variables" />,
}));

import CredentialsPage from '../page';

function openAt(query = '') {
  h.searchParams = new URLSearchParams(query);
  window.history.replaceState({}, '', query ? `${PATH}?${query}` : PATH);
}

beforeEach(() => {
  vi.clearAllMocks();
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('CredentialsPage - tabs kept in the address', () => {
  it('opens on the list the address names', () => {
    openAt('view=available');
    render(<CredentialsPage />);
    expect(screen.getByTestId('available-list')).toBeInTheDocument();
  });

  it('opens on the variables when the address says so', () => {
    openAt('tab=variables');
    render(<CredentialsPage />);
    expect(screen.getByTestId('variables')).toBeInTheDocument();
  });

  it('falls back to My credentials on values it does not have', () => {
    openAt('tab=nope&view=nope');
    render(<CredentialsPage />);
    expect(screen.getByTestId('my-list')).toBeInTheDocument();
  });

  it('picking a list pushes it and drops the search, page and filters of the one being left', () => {
    openAt('q=gmail&page=2&filter=default&credentialId=51');
    const pushState = vi.spyOn(window.history, 'pushState');
    render(<CredentialsPage />);

    fireEvent.click(screen.getByText('view-available'));

    expect(screen.getByTestId('available-list')).toBeInTheDocument();
    expect(pushState).toHaveBeenCalledWith(null, '', `${PATH}?credentialId=51&view=available`);
  });

  it('the variables tab is pushed too, with the list params dropped', () => {
    openAt('q=gmail&type=oauth2');
    const pushState = vi.spyOn(window.history, 'pushState');
    render(<CredentialsPage />);

    fireEvent.click(screen.getByText('primary-variables'));

    expect(screen.getByTestId('variables')).toBeInTheDocument();
    expect(pushState).toHaveBeenCalledWith(null, '', `${PATH}?tab=variables`);
  });
});

describe('CredentialsPage - the OAuth callback params stay one-shot', () => {
  it('toasts the success once and strips success and credentialId from the address', async () => {
    openAt('success=true&credentialId=7');
    render(<CredentialsPage />);

    await waitFor(() => expect(h.addToast).toHaveBeenCalledTimes(1));
    expect(h.addToast).toHaveBeenCalledWith(expect.objectContaining({ type: 'success' }));
    expect(window.location.search).toBe('');
    expect(screen.getByTestId('my-list')).toBeInTheDocument();
  });

  it('regression - a success landing on another list returns to My credentials and leaves no callback param behind', async () => {
    openAt('success=true&credentialId=7&view=available&tab=variables');
    render(<CredentialsPage />);

    await waitFor(() => expect(h.addToast).toHaveBeenCalledTimes(1));
    expect(screen.getByTestId('my-list')).toBeInTheDocument();
    expect(window.location.search).toBe('');
  });

  it('toasts the error and strips it, keeping the list the user was on', async () => {
    openAt('error=Access%20denied&view=available');
    render(<CredentialsPage />);

    await waitFor(() => expect(h.addToast).toHaveBeenCalledWith(expect.objectContaining({
      type: 'error', message: 'Access denied',
    })));
    expect(window.location.search).toBe('?view=available');
    expect(screen.getByTestId('available-list')).toBeInTheDocument();
  });
});
