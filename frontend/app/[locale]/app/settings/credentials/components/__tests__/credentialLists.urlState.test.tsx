// @vitest-environment jsdom
/**
 * The three lists of Settings > Credentials keep their view in the address: the search, the
 * page and the filters survive a reload, and each change is written back.
 */
import * as React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';

vi.mock('next-intl', () => ({
  useTranslations: () => Object.assign((key: string) => key, { has: () => false }),
}));

vi.mock('@/components/ui/service-icon', () => ({ ServiceIcon: () => null }));

// A select jsdom can drive: every option is a button that reports its value.
vi.mock('@/components/ui/select', async () => {
  const ReactModule = await import('react');
  const Ctx = ReactModule.createContext<{ value?: string; onValueChange?: (v: string) => void }>({});
  return {
    Select: ({ value, onValueChange, children }: {
      value?: string; onValueChange?: (v: string) => void; children: React.ReactNode;
    }) => <Ctx.Provider value={{ value, onValueChange }}><div data-select-value={value}>{children}</div></Ctx.Provider>,
    SelectTrigger: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
    SelectValue: () => null,
    SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
    SelectItem: ({ value, children }: { value: string; children: React.ReactNode }) => {
      const ctx = ReactModule.useContext(Ctx);
      return <button type="button" data-option={value} onClick={() => ctx.onValueChange?.(value)}>{children}</button>;
    },
  };
});

vi.mock('@/components/ui/dialog', () => ({
  Dialog: () => null,
  DialogContent: () => null,
  DialogHeader: () => null,
  DialogFooter: () => null,
  DialogTitle: () => null,
  DialogDescription: () => null,
}));

vi.mock('@/app/workflows/builder/components/palette/useLazyLoadObserver', () => ({
  useLazyLoadObserver: () => React.createRef<HTMLDivElement>(),
}));

const api = vi.hoisted(() => ({
  getCredentials: vi.fn(),
  getCredentialTemplates: vi.fn(),
  listVariables: vi.fn(),
  getQuota: vi.fn(),
}));
vi.mock('@/lib/api/orchestrator', () => ({
  orchestratorApi: {
    getCredentials: api.getCredentials,
    getCredentialTemplates: api.getCredentialTemplates,
  },
}));
vi.mock('@/lib/api/services/variables-api.service', () => ({
  variablesApi: { list: api.listVariables, getQuota: api.getQuota },
}));

import { MyCredentialsList } from '../MyCredentialsList';
import { AvailableCredentialsList } from '../AvailableCredentialsList';
import { VariablesSection } from '../VariablesSection';

const PAGE = '/en/app/settings/credentials';

function openAt(query = '') {
  fakeFolderRouter.reset(PAGE);
  if (query) fakeFolderRouter.navigate(`${PAGE}?${query}`, 'replace');
}

function withQueryClient(node: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>{node}</QueryClientProvider>);
}

const searchBox = () => screen.getByPlaceholderText('searchPlaceholder') as HTMLInputElement;

beforeEach(() => {
  vi.clearAllMocks();
  api.getCredentials.mockResolvedValue({
    credentials: [{
      id: 1, tenant_id: 't', name: 'gmail Credential', integration: 'gmail', type: 'OAuth2',
      environment: 'Production', status: 'active', credential_data: {}, scopes: [], tags: [],
      is_default: true, last_used: null, created_at: '2026-05-04T10:00:00Z', updated_at: '2026-05-04T10:00:00Z',
    }],
    page: 2, pageSize: 10, totalItems: 30, totalPages: 3, hasNext: true, hasPrevious: true,
  });
  api.getCredentialTemplates.mockResolvedValue({
    credentials: [
      { id: 'a', credential_name: 'slack', display_name: 'Slack', auth_type: 'oauth2', icon_slug: 'slack' },
      { id: 'b', credential_name: 'stripe', display_name: 'Stripe', auth_type: 'api_key', icon_slug: 'stripe' },
    ],
  });
  api.listVariables.mockResolvedValue([]);
  api.getQuota.mockResolvedValue({ used: 0, limit: 3, planCode: 'FREE' });
});
afterEach(() => cleanup());

describe('MyCredentialsList - view kept in the address', () => {
  it('opens on the page, the search and the filter the address carries', async () => {
    openAt('q=gmail&page=2&filter=default');
    const { container } = withQueryClient(<MyCredentialsList addToast={() => {}} />);

    await waitFor(() => expect(api.getCredentials).toHaveBeenCalledWith({ page: 2, pageSize: 10 }));
    expect(api.getCredentials).not.toHaveBeenCalledWith({ page: 1, pageSize: 10 });
    expect(searchBox().value).toBe('gmail');
    expect(container.querySelector('[data-select-value="default"]')).not.toBeNull();
  });

  it('writes the search, the filter and the page as they change, keeping the other params', async () => {
    openAt('tab=credentials&page=2');
    const { container } = withQueryClient(<MyCredentialsList addToast={() => {}} />);
    await screen.findByText('gmail Credential');

    fireEvent.change(searchBox(), { target: { value: 'gm' } });
    await waitFor(() => expect(fakeFolderRouter.search()).toBe('tab=credentials&page=2&q=gm'));

    fireEvent.click(container.querySelector('[data-option="non-default"]')!);
    expect(fakeFolderRouter.search()).toBe('tab=credentials&page=2&q=gm&filter=non-default');

    fireEvent.click(screen.getByRole('button', { name: 'next' }));
    expect(fakeFolderRouter.search()).toContain('page=3');
    await waitFor(() => expect(api.getCredentials).toHaveBeenCalledWith({ page: 3, pageSize: 10 }));
  });

  it('falls back to the first page and no filter on values it does not recognise', async () => {
    openAt('page=0&filter=nope');
    const { container } = withQueryClient(<MyCredentialsList addToast={() => {}} />);

    await waitFor(() => expect(api.getCredentials).toHaveBeenCalledWith({ page: 1, pageSize: 10 }));
    expect(container.querySelector('[data-select-value="all"]')).not.toBeNull();
  });

  it('a page restored from the address that is past the end steps back to the last one', async () => {
    // Credentials were deleted since the address was saved: the list now has 3 pages, and the
    // server answers an empty page 40 while still saying how many pages there are.
    const credential = (id: number, name: string) => ({
      id, tenant_id: 't', name, integration: 'gmail', type: 'OAuth2',
      environment: 'Production', status: 'active', credential_data: {}, scopes: [], tags: [],
      is_default: false, last_used: null, created_at: '2026-05-04T10:00:00Z', updated_at: '2026-05-04T10:00:00Z',
    });
    api.getCredentials.mockImplementation(async ({ page }: { page: number }) => ({
      credentials: page === 3 ? [credential(21, 'last page credential')] : [],
      page, pageSize: 10, totalItems: 21, totalPages: 3, hasNext: false, hasPrevious: page > 1,
    }));
    openAt('tab=credentials&page=40');
    withQueryClient(<MyCredentialsList addToast={() => {}} />);

    // The user ends on rows, not on the empty state of a page that no longer exists.
    expect(await screen.findByText('last page credential')).toBeTruthy();
    expect(screen.queryByText('noCredentialsYet')).toBeNull();
    // The address says the page that is shown, so the next reload does not start at 40 again.
    expect(fakeFolderRouter.search()).toBe('tab=credentials&page=3');
    expect(screen.getByText('3 / 3')).toBeTruthy();
    expect(api.getCredentials.mock.calls.map(([arg]) => arg.page)).toEqual([40, 3]);
  });
});

describe('AvailableCredentialsList - view kept in the address', () => {
  it('opens filtered by the search and the auth type the address carries', async () => {
    openAt('type=api_key');
    render(<AvailableCredentialsList onConfigure={() => {}} onConfigureMultiple={() => {}} />);

    expect(await screen.findByText('Stripe')).toBeTruthy();
    expect(screen.queryByText('Slack')).toBeNull();
  });

  it('writes the search and the auth type as they change', async () => {
    openAt();
    const { container } = render(<AvailableCredentialsList onConfigure={() => {}} onConfigureMultiple={() => {}} />);
    await screen.findByText('Stripe');

    fireEvent.change(searchBox(), { target: { value: 'sl' } });
    await waitFor(() => expect(fakeFolderRouter.search()).toBe('q=sl'));
    expect(screen.queryByText('Stripe')).toBeNull();

    fireEvent.click(container.querySelector('[data-option="oauth2"]')!);
    expect(fakeFolderRouter.search()).toBe('q=sl&type=oauth2');
  });

  it('drops an auth type the catalogue does not have, instead of showing an empty list', async () => {
    openAt('type=gone');
    render(<AvailableCredentialsList onConfigure={() => {}} onConfigureMultiple={() => {}} />);

    expect(await screen.findByText('Stripe')).toBeTruthy();
    expect(screen.getByText('Slack')).toBeTruthy();
    expect(fakeFolderRouter.search()).toBe('');
  });
});

describe('VariablesSection - search kept in the address', () => {
  it('opens on the search the address carries, and writes it as it changes', async () => {
    openAt('tab=variables&q=api');
    withQueryClient(<VariablesSection refreshSignal={0} addToast={() => {}} />);
    await waitFor(() => expect(api.listVariables).toHaveBeenCalled());

    expect(searchBox().value).toBe('api');
    fireEvent.change(searchBox(), { target: { value: 'retry' } });
    await waitFor(() => expect(fakeFolderRouter.search()).toBe('tab=variables&q=retry'));
  });
});
