// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// Pins the `leadingControl` slot: the model selector / agent avatar passed by the
// parent must render in the trailing button group, just LEFT OF the mic.
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

function renderComposer(leadingControl?: React.ReactNode) {
  // Force the SpeechRecognition feature flag on so the mic branch is reachable.
  (window as never as Record<string, unknown>).webkitSpeechRecognition = function MockRec() {
    return { start: vi.fn(), stop: vi.fn() };
  };
  return render(
    <MessageComposer
      inputValue=""
      onInputChange={() => {}}
      onSendMessage={() => {}}
      showAttachmentMenu={false}
      onShowAttachmentMenu={() => {}}
      leadingControl={leadingControl}
    />,
  );
}

describe('MessageComposer - leadingControl slot', () => {
  it('renders the leadingControl node', () => {
    renderComposer(<div data-testid="leading-node">model picker</div>);
    expect(screen.getByTestId('leading-node')).toBeInTheDocument();
  });

  it('places the leadingControl just before the mic button', () => {
    renderComposer(<div data-testid="leading-node">model picker</div>);
    const leading = screen.getByTestId('leading-node');
    const mic = screen.getByTitle('chat.startDictation');
    // The leadingControl must come BEFORE the mic in document order (left of it).
    // DOCUMENT_POSITION_FOLLOWING => `mic` follows `leading`.
    expect(
      leading.compareDocumentPosition(mic) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    // …and they share the same trailing button group container.
    expect(leading.parentElement).toBe(mic.closest('button')?.parentElement);
  });

  it('renders no extra node when leadingControl is omitted', () => {
    renderComposer(undefined);
    expect(screen.queryByTestId('leading-node')).not.toBeInTheDocument();
    expect(screen.getByTitle('chat.startDictation')).toBeInTheDocument();
  });
});
