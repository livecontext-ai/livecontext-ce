// @vitest-environment jsdom
/**
 * The MCP list and an API's Tools tab keep their search in the address, so a reload keeps
 * the filtered list.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/hooks/useUserApis', () => ({
  useUserApis: () => ({
    apis: [
      { id: 'a1', apiName: 'Weather API', description: '', updatedAt: '2026-09-01T00:00:00Z' },
      { id: 'a2', apiName: 'Billing API', description: '', updatedAt: '2026-09-01T00:00:00Z' },
    ],
    isLoading: false,
    error: null,
    fetchUserApis: vi.fn(),
  }),
}));
vi.mock('@/lib/stores/current-org-store', () => ({ useCanMutateInCurrentOrg: () => true }));
vi.mock('@/lib/api/orchestrator', () => ({ customApiService: {} }));

import { MCPTable } from '@/components/MCPTable';
import ToolsTab from '../[apiSlug]/components/tabs/ToolsTab';

const PAGE = '/en/app/settings/mcp';

function openAt(query = '') {
  fakeFolderRouter.reset(PAGE);
  if (query) fakeFolderRouter.navigate(`${PAGE}?${query}`, 'replace');
}

const TOOLS = [
  { id: 't1', name: 'get_forecast', description: 'Forecast', endpoint: '/forecast', method: 'GET' },
  { id: 't2', name: 'create_invoice', description: 'Invoice', endpoint: '/invoices', method: 'POST' },
];

afterEach(cleanup);

describe('MCPTable - search kept in the address', () => {
  it('opens filtered by the search the address carries, and writes it as it changes', async () => {
    openAt('q=billing');
    render(<MCPTable />);

    expect(screen.getAllByText('Billing API').length).toBeGreaterThan(0);
    expect(screen.queryByText('Weather API')).toBeNull();

    fireEvent.change(screen.getByDisplayValue('billing'), { target: { value: 'weather' } });
    expect(screen.getAllByText('Weather API').length).toBeGreaterThan(0);
    await waitFor(() => expect(fakeFolderRouter.search()).toBe('q=weather'));
  });
});

describe('ToolsTab - search kept in the address', () => {
  it('opens filtered by the search the address carries, and writes it as it changes', async () => {
    openAt('tab=tools&q=invoice');
    render(<ToolsTab tools={TOOLS as never} />);

    expect(screen.getByText('create_invoice')).toBeInTheDocument();
    expect(screen.queryByText('get_forecast')).toBeNull();

    fireEvent.change(screen.getByPlaceholderText('searchPlaceholder'), { target: { value: 'fore' } });
    expect(screen.getByText('get_forecast')).toBeInTheDocument();
    await waitFor(() => expect(fakeFolderRouter.search()).toBe('tab=tools&q=fore'));
  });
});
