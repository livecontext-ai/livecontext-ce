/**
 * @vitest-environment jsdom
 *
 * On the account Preferences page (target === 'user-default') the in-panel scope
 * label ("Your workspace defaults") and the hint box ("These defaults seed every
 * new conversation…") are intentionally NOT rendered - the page supplies its own
 * section header, so repeating it here was redundant. Every other scope
 * (agent / conversation / draft) still shows its scope label.
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import * as React from 'react';

// Mutable target so each test can pick the scope the panel renders for.
const h = vi.hoisted(() => ({ target: 'conversation' as string }));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

vi.mock('@/hooks/useChatConfig', () => ({
  useChatConfig: () => ({
    config: { maxTokens: 4500, maxIterations: 50, executionTimeout: 600, temperature: 0.7 },
    updateConfig: vi.fn(),
    isLoading: false,
    isSaving: false,
    error: null,
    target: h.target,
  }),
}));

// Stub radix wrappers so jsdom doesn't need ResizeObserver / pointer APIs.
vi.mock('@/components/ui/slider', () => ({ Slider: () => <div data-testid="slider" /> }));
vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectItem: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectTrigger: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectValue: () => <div />,
}));
vi.mock('@/components/ui/tooltip', () => ({
  Tooltip: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  TooltipContent: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  TooltipProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  TooltipTrigger: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

import { ChatConfigPanel } from '../ChatConfigPanel';

describe('ChatConfigPanel scope label per target', () => {
  it('hides the scope label and any hint box for the user-default (Preferences) target', () => {
    h.target = 'user-default';
    render(<ChatConfigPanel userDefault />);

    // The translation mock echoes the key, so the rendered text === the i18n key.
    // Pre-change this target rendered a "Your workspace defaults" scope label; it's gone.
    expect(screen.queryByText('scopeUserDefault')).toBeNull();
    // No hint box leaks here - draftHint is the only remaining hint and must stay
    // scoped to the draft target (see the draft test below where it IS rendered).
    expect(screen.queryByText('draftHint')).toBeNull();
    // Sanity: the panel still rendered its actual controls.
    expect(screen.getByText('systemPromptLabel')).toBeTruthy();
  });

  it('still renders the scope label for the conversation target', () => {
    h.target = 'conversation';
    render(<ChatConfigPanel conversationId="c1" />);

    expect(screen.getByText('scopeConversation')).toBeTruthy();
  });

  it('still renders the scope label and hint for the draft target', () => {
    h.target = 'draft';
    render(<ChatConfigPanel conversationId="c1" />);

    expect(screen.getByText('scopeDraft')).toBeTruthy();
    expect(screen.getByText('draftHint')).toBeTruthy();
  });
});
