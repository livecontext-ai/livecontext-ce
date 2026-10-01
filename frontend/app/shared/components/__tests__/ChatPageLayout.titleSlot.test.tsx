/**
 * @vitest-environment jsdom
 *
 * The chat home's rotating title sits in a FIXED-HEIGHT slot, bottom-anchored.
 *
 * Regression: the Orbi titles (3488d53e5) made most of them wrap to two lines at the title's
 * max-w-md, while the first one fits on one. With the title in normal flow the composer under it
 * moved by a line every time the rotation changed the line count, and the composer-parity e2e
 * measured the chat box 32px below the studio box. jsdom has no layout, so this pins the thing
 * that makes the height constant: the slot class, on the title's own wrapper, with the composer
 * outside it.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/i18n/navigation', () => ({
  Link: ({ children }: { children?: React.ReactNode }) => <a>{children}</a>,
  useRouter: () => ({ push: () => undefined }),
  usePathname: () => '/app',
}));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => ({ isOpen: false }) }));
vi.mock('@/contexts/SidePanelLayoutContext', () => ({ useSidePanelLayoutSafe: () => ({ position: 'right' }) }));
vi.mock('@/contexts/ConversationActivityContext', () => ({
  useConversationActivity: () => ({ isOpen: false, setOpen: vi.fn() }),
}));
vi.mock('@/hooks/useCurrentView', () => ({ useCurrentView: () => ({ view: 'chat', dataSourceId: null }) }));
vi.mock('@/hooks/useMobileDetection', () => ({ useMobileDetection: () => false }));
vi.mock('@/components/chat/ToolSelector', () => ({ ToolSelector: () => <div /> }));
vi.mock('@/components/chat/MessageComposer', () => ({
  MessageComposer: () => <div data-testid="home-composer" />,
}));
vi.mock('@/components/chat/MessageHistory', () => ({ MessageHistory: () => <div /> }));
vi.mock('@/components/chat/ChatCore', () => ({ ChatCore: () => <div /> }));
vi.mock('@/components/chat/HomeModeSwitch', () => ({ HomeModeSwitch: () => <div /> }));
vi.mock('@/components/chat/HomeQuickOpenButton', () => ({ HomeQuickOpenButton: () => <div /> }));
vi.mock('@/lib/stores/current-org-store', () => ({ useCanMutateInCurrentOrg: () => true }));
vi.mock('@/components/chat/DashboardContent', () => ({ DashboardContent: () => <div /> }));
vi.mock('@/components/chat/HighlightedApps', () => ({ HighlightedApps: () => <div /> }));
vi.mock('@/components/chat/HomeDynamicTitle', () => ({
  HomeDynamicTitle: () => <h2 data-testid="home-title">title</h2>,
}));
vi.mock('@/components/chat/HomeSuggestionChips', () => ({ HomeSuggestionChips: () => <div /> }));
vi.mock('@/components/chat/DataSourceMessage', () => ({
  DataSourceMessage: () => <div />,
  isDataSourceMessage: () => false,
}));
vi.mock('@/components/chat/ConversationActivityCard', () => ({ ConversationActivityCard: () => <div /> }));

import { ChatPageLayout } from '../ChatPageLayout';
import { ROTATING_TITLE_SLOT_CLASS } from '../WelcomeTitle';

afterEach(cleanup);

describe('ChatPageLayout - the rotating title cannot move the home composer', () => {
  it('puts the desktop title in the fixed-height, bottom-anchored slot, with the composer outside it', () => {
    render(
      <ChatPageLayout
        toolSelectorProps={{} as never}
        messageHistoryProps={{ messages: [] } as never}
        composerProps={{ inputValue: '', onInputChange: vi.fn() } as never}
        layoutState={{
          showWelcomeMessage: true,
          shouldRenderHistory: false,
          isConversationActive: false,
          isLoadingConversation: false,
          messagesContainerRef: { current: null },
        } as never}
        conversationId={null}
      />,
    );

    const slot = screen.getByTestId('home-title-slot');
    // The desktop title (the mobile layout renders a second one, outside any slot).
    expect(slot).toContainElement(screen.getAllByTestId('home-title')[0]);
    for (const cls of ROTATING_TITLE_SLOT_CLASS.split(' ')) {
      expect(slot).toHaveClass(cls);
    }
    // A fixed height (not a min-height, which a long title would still grow), anchored at the bottom.
    expect(ROTATING_TITLE_SLOT_CLASS).toMatch(/(^| )h-\[/);
    expect(ROTATING_TITLE_SLOT_CLASS).toContain('justify-end');
    for (const composer of screen.getAllByTestId('home-composer')) {
      expect(slot).not.toContainElement(composer);
    }
  });
});
