// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// Same stand-ins as MessageComposer.orbi.test.tsx: this suite is only about the
// "back to the latest message" arrow the chat asks the composer to draw.
vi.mock('@/i18n/navigation', () => ({
  useRouter: () => ({ push: () => undefined, replace: () => undefined, prefetch: () => undefined }),
  usePathname: () => '/app',
  Link: ({ children }: { children?: React.ReactNode }) => <a>{children}</a>,
}));
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@tanstack/react-query', () => ({ useQuery: () => ({ data: null }) }));
vi.mock('@/hooks/useDefaultSkills', () => ({
  useDefaultSkills: () => ({
    activeSkillIds: new Set<string>(),
    setActiveSkillIds: vi.fn(),
    initializeDefaults: vi.fn(),
    hasExplicitSkillSelection: false,
  }),
}));
vi.mock('@/hooks/useMobileDetection', () => ({ useMobileDetection: () => false }));
vi.mock('@/lib/api/orchestrator', () => ({ orchestratorApi: {} }));
vi.mock('@/components/chat/AttachmentHandler', () => ({ AttachmentHandler: () => null }));
vi.mock('@/components/chat/QueuedMessageBar', () => ({ QueuedMessageBar: () => null }));

import { MessageComposer } from '../MessageComposer';

afterEach(cleanup);

function renderComposer(props: { showScrollToBottom?: boolean; onScrollToBottom?: () => void; minimal?: boolean }) {
  return render(
    <MessageComposer
      {...props}
      inputValue=""
      onInputChange={() => {}}
      onSendMessage={() => {}}
      showAttachmentMenu={false}
      onShowAttachmentMenu={() => {}}
    />,
  );
}

describe('MessageComposer - back to the latest message arrow', () => {
  // Regression: the props were still wired from the chat page, but the button that used
  // them was dropped in a composer rewrite, so scrolling up left no way back down.
  it('draws the arrow when the chat says the reader has scrolled away from the bottom', () => {
    const onScrollToBottom = vi.fn();
    renderComposer({ showScrollToBottom: true, onScrollToBottom });

    const arrow = screen.getByTestId('chat-scroll-to-bottom');
    expect(arrow).toHaveAttribute('aria-label', 'chat.scrollToBottom');
    fireEvent.click(arrow);
    expect(onScrollToBottom).toHaveBeenCalledTimes(1);
  });

  it('draws nothing while the reader is at the bottom', () => {
    renderComposer({ showScrollToBottom: false, onScrollToBottom: vi.fn() });
    expect(screen.queryByTestId('chat-scroll-to-bottom')).not.toBeInTheDocument();
  });

  it('draws nothing without a handler, so a surface that cannot scroll never shows a dead arrow', () => {
    renderComposer({ showScrollToBottom: true });
    expect(screen.queryByTestId('chat-scroll-to-bottom')).not.toBeInTheDocument();
  });

  it('draws nothing in minimal (DM) mode', () => {
    renderComposer({ showScrollToBottom: true, onScrollToBottom: vi.fn(), minimal: true });
    expect(screen.queryByTestId('chat-scroll-to-bottom')).not.toBeInTheDocument();
  });
});
