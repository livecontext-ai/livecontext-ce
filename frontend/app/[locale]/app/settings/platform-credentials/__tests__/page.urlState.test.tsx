// @vitest-environment jsdom
/**
 * Settings > Platform credentials keeps its view mode, its category, its search and its auth
 * type in the address, so a reload reopens the list as it was.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';

const api = vi.hoisted(() => ({
  getIntegrations: vi.fn(),
  getPlatformCredentials: vi.fn(),
  getPlatformCredentialCategories: vi.fn(),
  getCredentialTemplates: vi.fn(),
}));
const t = vi.hoisted(() => Object.assign((key: string) => key, { has: () => false }));

vi.mock('next-intl', () => ({ useTranslations: () => t }));
vi.mock('@/lib/edition', () => ({ IS_CE: false, IS_CLOUD: true }));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false, isLoading: false }),
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ loginWithRedirect: vi.fn(), hasRole: (r: string) => r === 'ADMIN' }),
}));
const toast = vi.hoisted(() => ({ toasts: [], addToast: () => {}, removeToast: () => {} }));
vi.mock('@/components/Toast', () => ({ default: () => null, useToast: () => toast }));
vi.mock('@/components/settings/PageHeader', () => ({ PageHeader: () => null }));
vi.mock('@/lib/api/orchestrator/credential.service', () => ({
  credentialService: {
    getPlatformCredentials: api.getPlatformCredentials,
    getPlatformCredentialCategories: api.getPlatformCredentialCategories,
    getCredentialTemplates: api.getCredentialTemplates,
  },
}));
vi.mock('@/lib/api/services/catalog-visibility.service', () => ({
  catalogVisibilityService: { getIntegrations: api.getIntegrations },
}));
vi.mock('../components', () => ({
  CategoryTabs: ({ categories, selectedCategory, onSelectCategory }: {
    categories: Array<{ slug: string }>; selectedCategory: string | null; onSelectCategory: (c: string | null) => void;
  }) => (
    <div data-testid="categories" data-value={selectedCategory ?? ''}>
      {categories.map((c) => (
        <button key={c.slug} type="button" onClick={() => onSelectCategory(c.slug)}>{`cat-${c.slug}`}</button>
      ))}
    </div>
  ),
  IntegrationCard: ({ integration }: { integration: { apiName: string } }) => (
    <div data-testid="integration">{integration.apiName}</div>
  ),
  CredentialFormDialog: () => null,
  PricingModal: () => null,
}));
// A select jsdom can drive: every option is a button that reports its value.
vi.mock('@/components/ui/select', async () => {
  const ReactModule = await import('react');
  const Ctx = ReactModule.createContext<(v: string) => void>(() => {});
  return {
    Select: ({ onValueChange, children }: { onValueChange: (v: string) => void; children: React.ReactNode }) =>
      <Ctx.Provider value={onValueChange}>{children}</Ctx.Provider>,
    SelectTrigger: () => null,
    SelectValue: () => null,
    SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
    SelectItem: ({ value }: { value: string }) => {
      const onValueChange = ReactModule.useContext(Ctx);
      return <button type="button" data-option={value} onClick={() => onValueChange(value)} />;
    },
  };
});

import PlatformCredentialsPage from '../page';

const PAGE = '/en/app/settings/platform-credentials';

function openAt(query = '') {
  fakeFolderRouter.reset(PAGE);
  if (query) fakeFolderRouter.navigate(`${PAGE}?${query}`, 'replace');
}

const names = () => screen.queryAllByTestId('integration').map((el) => el.textContent);
const searchBox = () => screen.getByPlaceholderText('searchPlaceholder') as HTMLInputElement;

const integration = (apiName: string, category: string, authType: string) => ({
  apiId: apiName.toLowerCase(), apiName, iconSlug: apiName.toLowerCase(), authType,
  credentialName: apiName.toLowerCase(), isActive: true, toolCount: 1, activeToolCount: 1, category,
});

beforeEach(() => {
  vi.clearAllMocks();
  api.getIntegrations.mockResolvedValue([
    integration('Slack', 'communication', 'oauth2'),
    integration('Gmail', 'communication', 'oauth2'),
    integration('Stripe', 'payments', 'api_key'),
  ]);
  api.getPlatformCredentials.mockResolvedValue([]);
  api.getPlatformCredentialCategories.mockResolvedValue([
    { slug: 'communication', name: 'Communication', integrationCount: 2 },
    { slug: 'payments', name: 'Payments', integrationCount: 1 },
  ]);
  api.getCredentialTemplates.mockResolvedValue({ credentials: [] });
});
afterEach(cleanup);

describe('PlatformCredentialsPage - view kept in the address', () => {
  it('opens with the category, the search and the auth type the address carries', async () => {
    openAt('category=communication&q=sla&type=oauth2');
    render(<PlatformCredentialsPage />);

    await waitFor(() => expect(names()).toEqual(['Slack']));
    expect(api.getPlatformCredentials).toHaveBeenCalledWith('communication');
    expect(searchBox().value).toBe('sla');
    expect(screen.getByTestId('categories')).toHaveAttribute('data-value', 'communication');
  });

  it('opens on the configured view when the address says so', async () => {
    openAt('view=configured');
    render(<PlatformCredentialsPage />);

    // Nothing is configured in this fixture, so the configured view is empty.
    expect(await screen.findByText('noConfiguredCredentials')).toBeInTheDocument();
  });

  it('drops a category or an auth type the catalogue does not have, instead of an empty list', async () => {
    openAt('category=gone&type=carrier_pigeon');
    render(<PlatformCredentialsPage />);

    await waitFor(() => expect(names()).toHaveLength(3));
    await waitFor(() => expect(fakeFolderRouter.search()).toBe(''));
  });

  it('writes the category, the auth type and the search as they change', async () => {
    openAt();
    const { container } = render(<PlatformCredentialsPage />);
    await waitFor(() => expect(names()).toHaveLength(3));

    fireEvent.click(container.querySelector('[data-option="api_key"]')!);
    expect(fakeFolderRouter.search()).toBe('type=api_key');
    expect(names()).toEqual(['Stripe']);

    fireEvent.click(screen.getByText('cat-payments'));
    expect(fakeFolderRouter.search()).toBe('type=api_key&category=payments');

    fireEvent.change(searchBox(), { target: { value: 'str' } });
    await waitFor(() => expect(fakeFolderRouter.search()).toBe('type=api_key&category=payments&q=str'));
  });
});
