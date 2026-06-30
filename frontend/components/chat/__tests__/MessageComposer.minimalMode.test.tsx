// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// Pins the minimal (DM) composer surface: attachments + dictation are REAL DM features
// (paperclip + mic visible), while the AI-only Tools & Skills button stays chat-only.
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@tanstack/react-query', () => ({
  useQuery: () => ({ data: null }),
}));
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

function renderComposer(minimal: boolean) {
  // Force the SpeechRecognition feature flag on so the mic branch is reachable.
  (window as never as Record<string, unknown>).webkitSpeechRecognition = function MockRec() {
    return { start: vi.fn(), stop: vi.fn() };
  };
  return render(
    <MessageComposer
      minimal={minimal}
      inputValue=""
      onInputChange={() => {}}
      onSendMessage={() => {}}
      showAttachmentMenu={false}
      onShowAttachmentMenu={() => {}}
    />,
  );
}

describe('MessageComposer - minimal (DM) mode surface', () => {
  it('regression: minimal mode shows the paperclip AND the mic, but NOT Tools & Skills', () => {
    renderComposer(true);

    expect(screen.getByTitle('chat.attachFiles')).toBeInTheDocument();
    expect(screen.getByTitle('chat.startDictation')).toBeInTheDocument();
    expect(screen.queryByTitle('credentials.toolsAndSkills')).not.toBeInTheDocument();
  });

  it('normal chat keeps all three controls', () => {
    renderComposer(false);

    expect(screen.getByTitle('chat.attachFiles')).toBeInTheDocument();
    expect(screen.getByTitle('chat.startDictation')).toBeInTheDocument();
    expect(screen.getByTitle('credentials.toolsAndSkills')).toBeInTheDocument();
  });
});
