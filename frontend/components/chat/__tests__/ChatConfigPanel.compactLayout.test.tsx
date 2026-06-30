/**
 * @vitest-environment jsdom
 *
 * In the narrow composer "Options" popover (compact), the numeric settings
 * (max tokens / max iterations / timeout) must render one-per-row with a wide
 * value box so the typed number stays readable - not squeezed into a 3-column
 * grid. The wide modal (non-compact) keeps the full-width grid input.
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import * as React from 'react';

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
    target: 'conversation',
  }),
}));

// Stub the radix-based wrappers so jsdom doesn't need ResizeObserver / pointer APIs.
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

describe('ChatConfigPanel compact numeric layout', () => {
  it('renders max tokens as an inline row with a wide value box when compact', () => {
    render(<ChatConfigPanel conversationId="c1" compact />);

    const input = screen.getByDisplayValue('4500') as HTMLInputElement;
    // Fixed-width value box (twMerge drops the base w-full in favor of w-28).
    expect(input.className).toContain('w-28');
    expect(input.className).not.toContain('w-full');
    // Label and value share one flex row (label left, value right).
    expect(input.closest('div')?.className).toContain('justify-between');
  });

  it('keeps the full-width grid input when not compact', () => {
    render(<ChatConfigPanel conversationId="c1" />);

    const input = screen.getByDisplayValue('4500') as HTMLInputElement;
    expect(input.className).toContain('w-full');
    expect(input.className).not.toContain('w-28');
  });

  it('offers only All / No tools in the tools-mode select (no dead-end "custom")', () => {
    // The Select mock renders each SelectItem's children, so the option labels appear as text.
    render(<ChatConfigPanel conversationId="c1" compact />);

    expect(screen.getByText('toolsModeAll')).toBeTruthy();
    expect(screen.getByText('toolsModeNone')).toBeTruthy();
    // "custom" has no per-conversation storage or picker - it must not be offered here.
    expect(screen.queryByText('toolsModeCustom')).toBeNull();
  });
});
