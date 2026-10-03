// @vitest-environment jsdom
/**
 * Settings > Public access keeps the open tab in the address (`?tab=`, which the bell, the
 * builder and the old /settings/webhooks redirect link to) and the sub-view of the chat tab
 * in `?view=`.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';

const t = vi.hoisted(() => (key: string) => key);
vi.mock('next-intl', () => ({ useTranslations: () => t }));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false }),
}));
vi.mock('@/lib/providers/smart-providers', () => ({ useAuth: () => ({ loginWithRedirect: vi.fn() }) }));
const toast = vi.hoisted(() => ({ toasts: [], addToast: () => {}, removeToast: () => {} }));
vi.mock('@/components/Toast', () => ({ default: () => null, useToast: () => toast }));
vi.mock('@/components/settings', () => ({ PageHeader: () => null }));
vi.mock('@/lib/api/orchestrator', () => ({
  webhookSettingsService: {
    getAll: vi.fn().mockResolvedValue([]),
    getConfig: vi.fn().mockResolvedValue(null),
  },
}));
vi.mock('@/lib/api/orchestrator/chat-endpoint-settings.service', () => ({
  chatEndpointSettingsService: {
    getAll: vi.fn().mockResolvedValue([]),
    getConfig: vi.fn().mockResolvedValue(null),
  },
}));
vi.mock('@/hooks/useHomeStatus', () => ({ useRefreshHomeStatus: () => () => {} }));
vi.mock('@/hooks/useAcquiredAppWorkflowIds', () => ({
  useAcquiredAppWorkflowIds: () => ({ workflowIds: new Set<string>() }),
}));
vi.mock('../../webhooks/components/WebhookCallLogsDialog', () => ({ WebhookCallLogsDialog: () => null }));
vi.mock('@/components/webhook/CurlExamplePopover', () => ({ CurlExamplePopover: () => null }));
vi.mock('@/components/sharing/ShareLinkDialog', () => ({ ShareLinkDialog: () => null }));
vi.mock('../components/DeleteTriggerDialog', () => ({ DeleteTriggerDialog: () => null }));
vi.mock('../components/TriggerEmptyState', () => ({
  TriggerEmptyState: ({ title }: { title: string }) => <div data-testid="empty">{title}</div>,
}));
vi.mock('../components/FormTabContent', () => ({ FormTabContent: () => <div data-testid="form-tab" /> }));
vi.mock('../components/ScheduleTabContent', () => ({ ScheduleTabContent: () => <div data-testid="schedule-tab" /> }));
vi.mock('../components/SharedLinksTabContent', () => ({
  SharedLinksTabContent: () => <div data-testid="shared-links" />,
}));

import TriggersSettingsPage from '../page';

const PAGE = '/en/app/settings/public-access';

function openAt(query = '') {
  fakeFolderRouter.reset(PAGE);
  if (query) fakeFolderRouter.navigate(`${PAGE}?${query}`, 'replace');
}

const tabBar = () => screen.getByTestId('public-access-tab-bar');

afterEach(cleanup);

describe('Settings > Public access - view kept in the address', () => {
  it('opens on the tab the address names', () => {
    openAt('tab=schedule');
    render(<TriggersSettingsPage />);

    expect(screen.getByTestId('schedule-tab')).toBeInTheDocument();
  });

  it('regression - a tab it does not have opens the webhooks instead of an empty page', async () => {
    openAt('tab=nope');
    render(<TriggersSettingsPage />);

    expect(await screen.findByText('noWebhooksEmpty')).toBeInTheDocument();
  });

  it('opens the chat tab on the shared links when the address says so', async () => {
    openAt('tab=chat&view=shared-links');
    render(<TriggersSettingsPage />);

    expect(await screen.findByTestId('shared-links')).toBeInTheDocument();
  });

  it('writes the tab and the sub-view, and a tab change drops the sub-view', async () => {
    openAt();
    render(<TriggersSettingsPage />);

    fireEvent.click(within(tabBar()).getByRole('button', { name: 'chatTab' }));
    expect(fakeFolderRouter.search()).toBe('tab=chat');
    expect(fakeFolderRouter.navigations.at(-1)?.method).toBe('push');

    fireEvent.click(await screen.findByRole('button', { name: 'subViewSharedLinks' }));
    expect(fakeFolderRouter.search()).toBe('tab=chat&view=shared-links');
    expect(await screen.findByTestId('shared-links')).toBeInTheDocument();

    fireEvent.click(within(tabBar()).getByRole('button', { name: 'formTab' }));
    expect(fakeFolderRouter.search()).toBe('tab=form');
    expect(screen.getByTestId('form-tab')).toBeInTheDocument();
  });
});
