/**
 * @vitest-environment jsdom
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

const openTabMock = vi.hoisted(() => vi.fn());
const removeTabMock = vi.hoisted(() => vi.fn());
const closeMock = vi.hoisted(() => vi.fn());

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanelSafe: () => ({
    isOpen: false,
    activeTabId: null,
    tabs: [],
    openTab: openTabMock,
    removeTab: removeTabMock,
    close: closeMock,
  }),
}));

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getWorkflow: vi.fn().mockResolvedValue({
      id: 'wf-1',
      name: 'Customer Sync',
      description: 'Sync customers',
      status: 'draft',
      plan: {
        triggers: [{ id: 'trigger:manual' }],
        mcps: [],
        tables: [],
        cores: [],
      },
      nodeIcons: [],
    }),
    deleteWorkflow: vi.fn(),
  },
}));

vi.mock('@/components/app/WorkflowBuilderPanelContent', () => ({
  WorkflowBuilderPanelContent: () => null,
}));

vi.mock('@/components/WorkflowNodeIcons', () => ({
  WorkflowNodeIcons: () => <div data-testid="node-icons" />,
}));

import { WorkflowPreviewBlock } from '@/components/chat/WorkflowPreviewBlock';

describe('WorkflowPreviewBlock side-panel tabs', () => {
  afterEach(() => {
    openTabMock.mockClear();
    removeTabMock.mockClear();
    closeMock.mockClear();
    cleanup();
  });

  it('opens workflow preview tabs editable by default', async () => {
    render(<WorkflowPreviewBlock workflowId="wf-1" />);

    fireEvent.click(await screen.findByText('Customer Sync'));

    await waitFor(() => expect(openTabMock).toHaveBeenCalledTimes(1));
    const content = openTabMock.mock.calls[0][0].content as React.ReactElement<{ readOnly: boolean }>;
    expect(content.props.readOnly).toBe(false);
  });

  it('preserves read-only mode for shared or preview workflow cards', async () => {
    render(<WorkflowPreviewBlock workflowId="wf-1" readOnly />);

    fireEvent.click(await screen.findByText('Customer Sync'));

    await waitFor(() => expect(openTabMock).toHaveBeenCalledTimes(1));
    const content = openTabMock.mock.calls[0][0].content as React.ReactElement<{ readOnly: boolean }>;
    expect(content.props.readOnly).toBe(true);
  });
});
