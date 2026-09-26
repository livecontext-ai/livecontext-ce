/**
 * @vitest-environment jsdom
 *
 * ChatPageLayout hands the page's Orbi decision (showOrbi) to BOTH composers it can render:
 * the home composer directly, and the thread composer through ChatCore. A prop dropped on
 * either path leaves Orbi missing on one surface only, which no other test would notice.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';

const seen = vi.hoisted(() => ({ core: [] as Array<Record<string, unknown>>, composer: [] as Array<Record<string, unknown>> }));
let panelOpen = false;
let dock = 'right';
let activityOpen = false;
let mobile = false;

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
vi.mock('@/components/chat/MessageComposer', () => ({
  MessageComposer: (props: Record<string, unknown>) => { seen.composer.push(props); return <div />; },
}));
vi.mock('@/components/chat/MessageHistory', () => ({ MessageHistory: () => <div /> }));
vi.mock('@/components/chat/ChatCore', () => ({
  ChatCore: (props: Record<string, unknown>) => { seen.core.push(props); return <div />; },
}));
vi.mock('@/components/chat/HomeModeSwitch', () => ({ HomeModeSwitch: () => <div /> }));
vi.mock('@/components/chat/HomeQuickOpenButton', () => ({ HomeQuickOpenButton: () => <div /> }));
vi.mock('@/lib/stores/current-org-store', () => ({ useCanMutateInCurrentOrg: () => true }));
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

function renderLayout(welcome: boolean, composerProps: Record<string, unknown>) {
  render(
    <ChatPageLayout
      toolSelectorProps={{} as never}
      messageHistoryProps={{ messages: [] } as never}
      composerProps={{ inputValue: '', onInputChange: vi.fn(), ...composerProps } as never}
      layoutState={{
        showWelcomeMessage: welcome,
        shouldRenderHistory: !welcome,
        isConversationActive: !welcome,
        isLoadingConversation: false,
        messagesContainerRef: { current: null },
        streamLastError: null,
        attemptStreamReconnection: vi.fn(),
      } as never}
      conversationId={welcome ? null : 'conv-1'}
    />,
  );
}

beforeEach(() => {
  seen.core = [];
  seen.composer = [];
  panelOpen = false;
  dock = 'right';
  activityOpen = false;
  mobile = false;
});
afterEach(cleanup);

describe('ChatPageLayout - Orbi plumbing', () => {
  it('passes showOrbi to the thread composer through ChatCore', () => {
    renderLayout(false, { showOrbi: true });
    const core = seen.core[seen.core.length - 1];
    expect(core.showOrbi).toBe(true);
  });

  it('passes them to every home composer (desktop and mobile)', () => {
    renderLayout(true, { showOrbi: true });
    expect(seen.composer.length).toBeGreaterThanOrEqual(2);
    for (const p of seen.composer) {
      expect(p.showOrbi).toBe(true);
    }
  });

  it('keeps Orbi off when the page says it is an agent conversation', () => {
    renderLayout(false, { showOrbi: false });
    expect(seen.core[seen.core.length - 1].showOrbi).toBe(false);
  });
});
