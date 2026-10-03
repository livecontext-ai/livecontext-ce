// @vitest-environment jsdom
/**
 * Agent Debug keeps its tab, the open prompt and the tools search and category in the
 * address, so a reload reopens the page where the admin was.
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

const service = vi.hoisted(() => ({
  getPrompts: vi.fn(),
  getPrompt: vi.fn(),
  getCategories: vi.fn(),
  getTools: vi.fn(),
}));

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ user: { sub: 'u1' }, isLoading: false, isAuthenticated: true, isAuthChecking: false }),
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ loginWithRedirect: vi.fn(), hasRole: (r: string) => r === 'ADMIN' }),
}));
vi.mock('@/lib/api/orchestrator', () => ({ agentToolsService: service }));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => null }));
// Every panel is rendered only when its tab is the open one, and a trigger is a plain button.
vi.mock('@/components/ui/tabs', async () => {
  const ReactModule = await import('react');
  const Ctx = ReactModule.createContext<{ value: string; onValueChange: (v: string) => void }>({
    value: '', onValueChange: () => {},
  });
  return {
    Tabs: ({ value, onValueChange, children }: {
      value: string; onValueChange: (v: string) => void; children: React.ReactNode;
    }) => <Ctx.Provider value={{ value, onValueChange }}>{children}</Ctx.Provider>,
    TabsList: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
    TabsTrigger: ({ value, children }: { value: string; children: React.ReactNode }) => {
      const ctx = ReactModule.useContext(Ctx);
      return <button type="button" data-tab={value} onClick={() => ctx.onValueChange(value)}>{children}</button>;
    },
    TabsContent: ({ value, children }: { value: string; children: React.ReactNode }) => {
      const ctx = ReactModule.useContext(Ctx);
      return ctx.value === value ? <div data-panel={value}>{children}</div> : null;
    },
  };
});

import AgentDebugPage from '../page';

const PAGE = '/en/app/settings/agent-debug';

function openAt(query = '') {
  fakeFolderRouter.reset(PAGE);
  if (query) fakeFolderRouter.navigate(`${PAGE}?${query}`, 'replace');
}

const searchBox = () => screen.getByPlaceholderText('Search tools...') as HTMLInputElement;

beforeEach(() => {
  vi.clearAllMocks();
  service.getPrompts.mockResolvedValue({
    prompts: [
      { name: 'main', description: 'Main prompt', tokenEstimate: 10, lineCount: 2, isDefault: true },
      { name: 'builder', description: 'Builder prompt', tokenEstimate: 20, lineCount: 4, isDefault: false },
    ],
  });
  service.getPrompt.mockImplementation(async (name: string) => ({
    name, description: `${name} prompt`, content: `content of ${name}`, tokenEstimate: 10, lineCount: 2,
  }));
  service.getCategories.mockResolvedValue({
    categories: [
      { slug: 'workflow', name: 'Workflow', toolCount: 1 },
      { slug: 'table', name: 'Table', toolCount: 1 },
    ],
  });
  service.getTools.mockResolvedValue({
    tools: [
      { name: 'workflow_run', description: 'Run a workflow', category: 'workflow', parameters: {} },
      { name: 'table_rows', description: 'Read rows', category: 'table', parameters: {} },
    ],
  });
});
afterEach(cleanup);

describe('AgentDebugPage - view kept in the address', () => {
  it('reopens the prompt the address names', async () => {
    openAt('prompt=builder');
    render(<AgentDebugPage />);

    await waitFor(() => expect(service.getPrompt).toHaveBeenCalledWith('builder'));
    expect(await screen.findByText('content of builder')).toBeInTheDocument();
  });

  it('does not follow a prompt name that could walk to another request path', async () => {
    // The name ends up in a request path, and the address is anyone's to write.
    openAt(`prompt=${encodeURIComponent('../llm-providers')}`);
    render(<AgentDebugPage />);

    await waitFor(() => expect(service.getPrompts).toHaveBeenCalled());
    expect(service.getPrompt).not.toHaveBeenCalled();
  });

  it('opens the tools tab with the search and the category the address carries', async () => {
    openAt('tab=tools&category=table&q=rows');
    render(<AgentDebugPage />);

    expect(await screen.findByText('table_rows')).toBeInTheDocument();
    expect(screen.queryByText('workflow_run')).toBeNull();
    expect(searchBox().value).toBe('rows');
  });

  it('falls back to the prompts tab on a tab it does not have', async () => {
    openAt('tab=nope');
    render(<AgentDebugPage />);

    expect(await screen.findByText('Main prompt')).toBeInTheDocument();
  });

  it('writes the prompt, the tab, the category and the search, and a tab change drops what the tab owned', async () => {
    openAt();
    const { container } = render(<AgentDebugPage />);

    fireEvent.click(await screen.findByText('main'));
    expect(fakeFolderRouter.search()).toBe('prompt=main');

    fireEvent.click(container.querySelector('[data-tab="tools"]')!);
    expect(fakeFolderRouter.search()).toBe('tab=tools');
    expect(fakeFolderRouter.navigations.at(-1)?.method).toBe('push');

    fireEvent.click(await screen.findByRole('button', { name: 'Table (1)' }));
    expect(fakeFolderRouter.search()).toBe('tab=tools&category=table');

    fireEvent.change(searchBox(), { target: { value: 'ro' } });
    await waitFor(() => expect(fakeFolderRouter.search()).toBe('tab=tools&category=table&q=ro'));
  });
});
