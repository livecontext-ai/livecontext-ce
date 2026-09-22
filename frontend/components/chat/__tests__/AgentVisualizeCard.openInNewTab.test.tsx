/**
 * @vitest-environment jsdom
 *
 * Regression (2026-09-16): the card's "open" action opened `/app/agent?id=<id>` in a new tab.
 * No code anywhere reads an `id` param, so the tab landed on the plain agent list and the
 * agent the user asked for never appeared. `openAgent` is the param the list actually acts on.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

const getAgentMock = vi.hoisted(() => vi.fn());

vi.mock('@/lib/api/orchestrator/agent.service', () => ({
  agentService: { getAgent: getAgentMock },
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: { deleteAgent: vi.fn() } }));
vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanelSafe: () => ({
    isOpen: false, isForward: false,
    activeTabId: null, openTab: vi.fn(), removeTab: vi.fn(), close: vi.fn(),
  }),
}));
vi.mock('@/components/agents/AvatarPicker', () => ({ AvatarDisplay: () => null }));
vi.mock('@/components/app/AgentPanelContent', () => ({
  AgentPanelContent: () => null,
  AGENT_CONFIGURATION_TAB: 'config',
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <div /> }));
// Drivable stand-in: the real menu hides its items behind a popover, and the action under
// test IS one of those items - a stub that drops them would leave it unreachable.
vi.mock('@/components/chat/PreviewActionMenu', () => ({
  PreviewActionMenu: ({ items }: { items: Array<{ id: string; onClick: () => void }> }) => (
    <div>
      {items.map((item) => (
        <button key={item.id} type="button" onClick={item.onClick}>{`menu:${item.id}`}</button>
      ))}
    </div>
  ),
  ActionIcons: { open: null, delete: null },
}));
vi.mock('@/components/chat/ConfirmDeleteModal', () => ({ ConfirmDeleteModal: () => null }));
vi.mock('@/components/chat/SimpleToast', () => ({ SimpleToast: () => null }));
vi.mock('@/hooks/useDeleteFlow', () => ({
  useDeleteFlow: () => ({
    isDeleted: false, showDeleteModal: false, isDeleting: false, toast: null,
    hideToast: vi.fn(), handleDeleteClick: vi.fn(), handleConfirmDelete: vi.fn(), handleCancelDelete: vi.fn(),
  }),
}));

import { AgentVisualizeCard } from '@/components/chat/AgentVisualizeCard';

describe('AgentVisualizeCard - "open in a new tab"', () => {
  afterEach(() => {
    getAgentMock.mockReset();
    cleanup();
    vi.restoreAllMocks();
  });

  it('opens the agent-panel deep link, not the param no one reads', async () => {
    getAgentMock.mockResolvedValue({ id: 'a-1', name: 'Nova' });
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null);

    render(<AgentVisualizeCard agentId="a-1" />);
    await screen.findByText('Nova', undefined, { timeout: 4000 });

    fireEvent.click(screen.getByText('menu:open'));

    expect(openSpy).toHaveBeenCalledWith('/app/agent?openAgent=a-1', '_blank');
  });
});
