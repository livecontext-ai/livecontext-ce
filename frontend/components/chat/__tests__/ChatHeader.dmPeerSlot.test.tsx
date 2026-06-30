// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

/**
 * The model selector and the clickable agent-avatar slot moved OUT of the header and
 * INTO the message composer (left of the mic). The header slot now renders ONLY the
 * non-interactive DM peer (avatar + name). These tests pin that contract:
 *   - agent conversation -> the header renders no clickable agent slot (it's in the composer),
 *   - model conversation -> the header renders no model selector (it's in the composer),
 *   - DM peer (agentSlotNonInteractive) -> plain text in the header, not a button.
 */
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  usePathname: () => '/en/app/messages/thread-1',
}));
vi.mock('next/image', () => ({
  // eslint-disable-next-line @next/next/no-img-element
  default: ({ src, alt }: { src: string; alt: string }) => <img src={src} alt={alt} />,
}));
vi.mock('@/hooks/useModels', () => ({
  modelMatches: () => false,
  selectedModelFromAIModel: (m: unknown) => m,
}));
vi.mock('@/components/ai/ModelInfo', () => ({
  ModelOptionDisplay: () => null,
  ModelInfoPopover: () => null,
}));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ user: null, isAuthenticated: true, isLoading: false }),
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isRunMode: false }),
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));
vi.mock('@/components/chat/workflowUtils', () => ({ isWorkflowMessage: () => false }));
vi.mock('@/components/chat/DataSourceMessage', () => ({ isDataSourceMessage: () => false }));
vi.mock('@/app/workflows/builder/components/inspector/StepDataTable', () => ({ StepDataTable: () => null }));
vi.mock('@/components/WorkflowRunResultModalContent', () => ({ WorkflowRunResultModalContent: () => null }));
vi.mock('@/components/workflow/ShareWorkflowModal', () => ({ PublishWorkflowModal: () => null }));
vi.mock('@/components/workflow/WorkflowVersionHistory', () => ({ WorkflowSaveWithVersions: () => null }));
vi.mock('@/components/marketplace/MarketplaceHeaderActions', () => ({ MarketplaceHeaderActions: () => null }));
vi.mock('@/components/chat/NotificationBell', () => ({ NotificationBell: () => null }));
vi.mock('@/components/applications/ApplicationActivationButton', () => ({ ApplicationActivationButton: () => null }));
vi.mock('@/components/agents/AvatarPicker', () => ({
  AvatarDisplay: ({ name }: { name?: string }) => <div data-testid="slot-avatar">{name}</div>,
}));

import { ChatHeader } from '../ChatHeader';

afterEach(cleanup);

const baseProps = {
  selectedModel: { provider: 'openai', id: 'gpt-4' },
  onModelChange: vi.fn(),
  availableModels: [],
  showModelSelector: false,
  onShowModelSelector: vi.fn(),
  sidebarOpen: false,
  onSidebarToggle: vi.fn(),
} as unknown as React.ComponentProps<typeof ChatHeader>;

describe('ChatHeader - model/agent slot moved to the composer', () => {
  it('agent conversation: the header renders NO clickable agent slot (it moved to the composer)', () => {
    const onToggle = vi.fn();
    render(
      <ChatHeader
        {...baseProps}
        agentName="Research Agent"
        agentAvatarUrl="/api/agents/1/avatar"
        onToggleAgentConfigPanel={onToggle}
      />,
    );

    // No clickable agent avatar/name in the header anymore, and it is NOT a static DM peer.
    expect(screen.queryAllByTitle('Research Agent')).toHaveLength(0);
    expect(screen.queryByTestId('header-peer-static')).not.toBeInTheDocument();
    expect(screen.queryByTestId('header-peer-static-mobile')).not.toBeInTheDocument();
  });

  it('model conversation: the header renders NO model selector (it moved to the composer)', () => {
    render(<ChatHeader {...baseProps} />);

    // The model-selector trigger (title = actions.changeModel) and its container are gone.
    expect(screen.queryAllByTitle('actions.changeModel')).toHaveLength(0);
    expect(document.querySelector('[data-model-selector]')).toBeNull();
  });

  it('DM peer (agentSlotNonInteractive): the slot is plain text and clicking it does NOT open the panel', () => {
    const onToggle = vi.fn();
    render(
      <ChatHeader
        {...baseProps}
        agentName="Ada Lovelace"
        agentAvatarUrl="/api/users/7/avatar"
        agentSlotNonInteractive
        onToggleAgentConfigPanel={onToggle}
      />,
    );

    // The static slot renders (desktop + mobile variants) with the peer's name…
    expect(screen.getByTestId('header-peer-static')).toBeInTheDocument();
    expect(screen.getByTestId('header-peer-static-mobile')).toBeInTheDocument();
    // …and none of the name slots is a button.
    screen.getAllByTitle('Ada Lovelace').forEach((slot) => {
      expect(slot.tagName).not.toBe('BUTTON');
      fireEvent.click(slot);
    });
    expect(onToggle).not.toHaveBeenCalled();
  });

  it('DM peer slot still shows the avatar and display name', () => {
    render(
      <ChatHeader
        {...baseProps}
        agentName="Ada Lovelace"
        agentAvatarUrl="/api/users/7/avatar"
        agentSlotNonInteractive
      />,
    );
    const staticSlot = screen.getByTestId('header-peer-static');
    expect(staticSlot).toHaveTextContent('Ada Lovelace');
  });
});
