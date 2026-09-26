/**
 * @vitest-environment jsdom
 *
 * The conversation activity card centers only when the side panel actually
 * SHRINKS the conversation area.
 *
 * Centering exists so the card is not docked top-right underneath the panel. A
 * DETACHED panel is a `position: fixed` card that takes no layout space at all,
 * so the area behind it is still full width and centering moves the card away
 * from its corner for nothing. `isOpen` alone cannot tell those apart, which is
 * why the dock is part of the question.
 *
 * The real component is rendered with its heavy children stubbed, so the
 * expression under test is the one that ships rather than a restatement of it.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';

let panelOpen: boolean;
let dock: string;
let activityOpen: boolean;
let mobile: boolean;

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/i18n/navigation', () => ({
  Link: ({ children }: { children?: React.ReactNode }) => <a>{children}</a>,
  // The welcome view now carries the chat/studio switch, which routes.
  useRouter: () => ({ push: () => undefined }),
  usePathname: () => '/app',
}));
vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanelSafe: () => ({ isOpen: panelOpen }),
}));
vi.mock('@/contexts/SidePanelLayoutContext', () => ({
  useSidePanelLayoutSafe: () => ({ position: dock }),
}));
vi.mock('@/contexts/ConversationActivityContext', () => ({
  useConversationActivity: () => ({ isOpen: activityOpen, setOpen: vi.fn() }),
}));
vi.mock('@/hooks/useCurrentView', () => ({
  useCurrentView: () => ({ view: 'chat', dataSourceId: null }),
}));
vi.mock('@/hooks/useMobileDetection', () => ({ useMobileDetection: () => mobile }));

vi.mock('@/components/chat/ToolSelector', () => ({ ToolSelector: () => <div /> }));
vi.mock('@/components/chat/MessageComposer', () => ({ MessageComposer: () => <div /> }));
vi.mock('@/components/chat/MessageHistory', () => ({ MessageHistory: () => <div /> }));
vi.mock('@/components/chat/ChatCore', () => ({
  ChatCore: ({ children }: { children?: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('@/components/chat/DashboardContent', () => ({ DashboardContent: () => <div /> }));
vi.mock('@/components/chat/HighlightedApps', () => ({ HighlightedApps: () => <div /> }));
vi.mock('@/components/chat/HomeDynamicTitle', () => ({ HomeDynamicTitle: () => <div /> }));
vi.mock('@/components/chat/HomeSuggestionChips', () => ({ HomeSuggestionChips: () => <div /> }));
vi.mock('@/components/chat/DataSourceMessage', () => ({
  DataSourceMessage: () => <div />,
  isDataSourceMessage: () => false,
}));
vi.mock('@/components/chat/ConversationActivityCard', () => ({
  ConversationActivityCard: ({ centered }: { centered?: boolean }) => (
    <div data-testid="activity-card" data-centered={String(!!centered)} />
  ),
}));

import { ChatPageLayout } from '../ChatPageLayout';

function renderLayout() {
  render(
    <ChatPageLayout
      toolSelectorProps={{} as never}
      messageHistoryProps={{ messages: [] } as never}
      composerProps={{ inputValue: '' } as never}
      layoutState={{
        showWelcomeMessage: false,
        shouldRenderHistory: true,
        isConversationActive: true,
        isLoadingConversation: false,
        messagesContainerRef: { current: null },
      } as never}
      conversationId="conv-1"
    />,
  );
}

const centered = () => screen.getByTestId('activity-card').getAttribute('data-centered');

beforeEach(() => {
  panelOpen = true;
  dock = 'right';
  activityOpen = true;
  mobile = false;
});
afterEach(cleanup);

describe('ChatPageLayout - activity card centering', () => {
  it('centers when a docked panel takes layout space', () => {
    renderLayout();
    expect(centered()).toBe('true');
  });

  it('does NOT center for a detached window, which takes none', () => {
    dock = 'floating';
    renderLayout();
    expect(centered(), 'the conversation is still full width behind a fixed card').toBe('false');
  });

  it('does not center when the panel is closed', () => {
    panelOpen = false;
    renderLayout();
    expect(centered()).toBe('false');
  });

  it('does NOT center on mobile, where no dock takes layout space either', () => {
    // Below the breakpoint every dock renders as a fixed full-screen overlay, so the
    // conversation behind it is full width exactly as it is behind a detached card.
    mobile = true;
    renderLayout();
    expect(centered()).toBe('false');
  });

  it('still centers for the bottom docks', () => {
    // Only 'floating' is weightless; the bottom docks reshape the area like 'right'.
    for (const d of ['bottom', 'bottom-full']) {
      dock = d;
      renderLayout();
      expect(centered(), d).toBe('true');
      cleanup();
    }
  });
});
