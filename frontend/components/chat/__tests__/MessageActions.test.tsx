// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, fireEvent } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// MessageActions is rendered for every assistant-side bubble in BOTH agent chat and
// DM threads (MessageHistory is shared). The thumbs up/down feedback only makes sense
// for an AI answer, so DM passes showFeedback={false}. These tests pin that gate:
// feedback controls show by default (chat) and are hidden when showFeedback is false (DM),
// while the other actions (copy/share/download) stay in both modes.
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
// vi.hoisted so the spy exists before vi.mock's hoisted factory runs. The component
// calls `.catch(...)` on the return value, so the mock must resolve a Promise.
const { updateMessageFeedback } = vi.hoisted(() => ({
  updateMessageFeedback: vi.fn(() => Promise.resolve()),
}));
vi.mock('@/lib/api/conversationApi', () => ({
  conversationApi: { updateMessageFeedback },
}));
// Render icons as identifiable stand-ins (real SVGs carry no accessible text).
vi.mock('lucide-react', () => ({
  Copy: () => <span data-testid="icon-copy" />,
  Check: () => <span data-testid="icon-check" />,
  Share2: () => <span data-testid="icon-share" />,
  ThumbsUp: () => <span data-testid="icon-thumbsup" />,
  ThumbsDown: () => <span data-testid="icon-thumbsdown" />,
  RotateCcw: () => <span data-testid="icon-retry" />,
  Download: () => <span data-testid="icon-download" />,
}));
// Flatten the tooltip primitives so the trigger buttons render directly.
vi.mock('@/components/ui/tooltip', () => ({
  Tooltip: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  TooltipTrigger: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  TooltipContent: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  TooltipProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

import { MessageActions } from '../MessageActions';

afterEach(() => {
  cleanup();
  updateMessageFeedback.mockClear();
});

describe('MessageActions - feedback gate', () => {
  it('shows thumbs up/down by default (agent chat)', () => {
    render(<MessageActions content="hi" messageId="m1" />);

    expect(screen.getByTestId('icon-thumbsup')).toBeInTheDocument();
    expect(screen.getByTestId('icon-thumbsdown')).toBeInTheDocument();
    // The always-on actions are present too.
    expect(screen.getByTestId('icon-copy')).toBeInTheDocument();
    expect(screen.getByTestId('icon-share')).toBeInTheDocument();
    expect(screen.getByTestId('icon-download')).toBeInTheDocument();
  });

  it('hides thumbs up/down when showFeedback is false (DM threads)', () => {
    render(<MessageActions content="hi" messageId="m1" showFeedback={false} />);

    expect(screen.queryByTestId('icon-thumbsup')).not.toBeInTheDocument();
    expect(screen.queryByTestId('icon-thumbsdown')).not.toBeInTheDocument();
    // Copy / share / download are unaffected by the feedback gate.
    expect(screen.getByTestId('icon-copy')).toBeInTheDocument();
    expect(screen.getByTestId('icon-share')).toBeInTheDocument();
    expect(screen.getByTestId('icon-download')).toBeInTheDocument();
  });

  it('persists feedback through conversationApi when a thumb is clicked (chat)', () => {
    render(<MessageActions content="hi" messageId="m42" />);

    fireEvent.click(screen.getByTestId('icon-thumbsup').closest('button')!);
    expect(updateMessageFeedback).toHaveBeenCalledWith('m42', 1);
  });

  it('does not persist feedback in DM mode because there is no thumb to click', () => {
    render(<MessageActions content="hi" messageId="m42" showFeedback={false} />);

    expect(screen.queryByTestId('icon-thumbsup')).not.toBeInTheDocument();
    expect(updateMessageFeedback).not.toHaveBeenCalled();
  });
});
