// @vitest-environment jsdom
/**
 * The tool health page keeps its window and its call floor in the address, so a reload reads
 * the same slice of the ledger.
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

const apiGet = vi.hoisted(() => vi.fn());
const t = vi.hoisted(() => (key: string, values?: Record<string, unknown>) =>
  values ? `${key}:${JSON.stringify(values)}` : key);

vi.mock('next-intl', () => ({ useTranslations: () => t }));
vi.mock('@/hooks/useAuthGuard', () => ({ useAuthGuard: () => ({ isLoading: false }) }));
vi.mock('@/lib/providers/smart-providers', () => ({ useAuth: () => ({ user: { sub: 'u1' } }) }));
vi.mock('@/lib/api/api-client', () => ({ apiClient: { get: apiGet } }));

import ToolHealthPage from '../page';

const PAGE = '/en/app/settings/tool-health';

function openAt(query = '') {
  fakeFolderRouter.reset(PAGE);
  if (query) fakeFolderRouter.navigate(`${PAGE}?${query}`, 'replace');
}

const windowSelect = () => screen.getByRole('combobox') as HTMLSelectElement;
const minCallsInput = () => screen.getByRole('spinbutton') as HTMLInputElement;

beforeEach(() => {
  apiGet.mockReset().mockResolvedValue({ tools: [], toolsWithFailures: 0, catalogSuspects: 0, window: { minCalls: 10, sinceDays: 30, limit: 100 } });
});
afterEach(cleanup);

describe('ToolHealthPage - view kept in the address', () => {
  it('asks for the window and the call floor the address carries', async () => {
    openAt('days=90&min=25');
    render(<ToolHealthPage />);

    await waitFor(() => expect(apiGet).toHaveBeenCalledWith('/agents/tool-health', {
      params: { sinceDays: '90', minCalls: '25', limit: '100' },
    }));
    expect(windowSelect().value).toBe('90');
    expect(minCallsInput().value).toBe('25');
  });

  it('falls back to the defaults on a window it does not offer or a floor below one', async () => {
    openAt('days=13&min=0');
    render(<ToolHealthPage />);

    await waitFor(() => expect(apiGet).toHaveBeenCalledWith('/agents/tool-health', {
      params: { sinceDays: '30', minCalls: '10', limit: '100' },
    }));
  });

  it('writes the window and the call floor as they change', async () => {
    openAt();
    render(<ToolHealthPage />);
    await waitFor(() => expect(apiGet).toHaveBeenCalled());

    fireEvent.change(windowSelect(), { target: { value: '0' } });
    expect(fakeFolderRouter.search()).toBe('days=0');

    fireEvent.change(minCallsInput(), { target: { value: '50' } });
    await waitFor(() => expect(fakeFolderRouter.search()).toBe('days=0&min=50'));
    await waitFor(() => expect(apiGet).toHaveBeenLastCalledWith('/agents/tool-health', {
      params: { sinceDays: '0', minCalls: '50', limit: '100' },
    }));
  });
});
