// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// Same stand-ins as MessageComposer.minimalMode.test.tsx: this suite is only about whether Orbi
// perches on the composer, which depends on the caller's showOrbi and on minimal mode.
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

function renderComposer(props: { showOrbi?: boolean | 'compact'; minimal?: boolean; isStreaming?: boolean }) {
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

describe('MessageComposer - Orbi perch', () => {
  it('perches Orbi when the chat page says this is the general chat', () => {
    renderComposer({ showOrbi: true });
    const perch = screen.getByTestId('orbi-perch');
    expect(perch).toHaveClass('pointer-events-none');
    expect(perch.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
  });

  it('shows nothing by default: agent conversations, the studio and the builder never pass showOrbi', () => {
    renderComposer({});
    expect(screen.queryByTestId('orbi-perch')).not.toBeInTheDocument();
  });

  it('never shows in minimal (DM) mode, even if asked', () => {
    renderComposer({ showOrbi: true, minimal: true });
    expect(screen.queryByTestId('orbi-perch')).not.toBeInTheDocument();
  });

  it('thinks while the composer is streaming', () => {
    renderComposer({ showOrbi: true, isStreaming: true });
    expect(screen.getByTestId('orbi-perch').querySelector('svg')).toHaveAttribute('data-mood', 'thinking');
  });

  it('draws the compact size for the side panel', () => {
    renderComposer({ showOrbi: 'compact' });
    expect(screen.getByTestId('orbi-perch')).toHaveClass('h-8', 'w-8');
  });

  it('is shown on phones too: compact below sm, full size from sm up', () => {
    renderComposer({ showOrbi: true });
    const perch = screen.getByTestId('orbi-perch');
    expect(perch).not.toHaveClass('hidden');
    expect(perch).toHaveClass('h-8', 'w-8', 'sm:h-10', 'sm:w-10');
  });
});

describe('MessageComposer - placeholder', () => {
  it('invites the user to message Orbi when Orbi is shown', () => {
    renderComposer({ showOrbi: true });
    expect(screen.getByRole('textbox')).toHaveAttribute('placeholder', 'chat.placeholderOrbi');
  });

  it('keeps the generic placeholder everywhere else (agent, studio, builder)', () => {
    renderComposer({});
    expect(screen.getByRole('textbox')).toHaveAttribute('placeholder', 'chat.placeholder');
  });

  it('keeps the generic placeholder in minimal (DM) mode', () => {
    renderComposer({ showOrbi: true, minimal: true });
    expect(screen.getByRole('textbox')).toHaveAttribute('placeholder', 'chat.placeholder');
  });
});
