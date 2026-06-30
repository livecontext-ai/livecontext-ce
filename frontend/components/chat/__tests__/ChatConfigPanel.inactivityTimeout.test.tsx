/**
 * @vitest-environment jsdom
 *
 * The "Inactivity timeout" control sits next to "Timeout" (executionTimeout) in the
 * advanced-limits grid. It must (a) render the configured value, (b) push edits through
 * updateConfig under the `inactivityTimeout` key (independent of executionTimeout), and
 * (c) show 0 verbatim for the "disabled" sentinel rather than coercing it to the 300s
 * default (`config.inactivityTimeout ?? 300` keeps 0, since 0 is not nullish).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import * as React from 'react';

const h = vi.hoisted(() => ({
  updateConfig: vi.fn(),
  config: { maxTokens: 4500, maxIterations: 50, executionTimeout: 600, inactivityTimeout: 90, temperature: 0.7 } as Record<string, unknown>,
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

vi.mock('@/hooks/useChatConfig', () => ({
  useChatConfig: () => ({
    config: h.config,
    updateConfig: h.updateConfig,
    isLoading: false,
    isSaving: false,
    error: null,
    target: 'conversation',
  }),
}));

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

describe('ChatConfigPanel inactivity-timeout control', () => {
  beforeEach(() => {
    h.updateConfig.mockClear();
    h.config = { maxTokens: 4500, maxIterations: 50, executionTimeout: 600, inactivityTimeout: 90, temperature: 0.7 };
  });

  it('renders the configured inactivity value and its label', () => {
    render(<ChatConfigPanel conversationId="c1" />);
    expect(screen.getByText('inactivityTimeoutLabel')).toBeTruthy();
    expect(screen.getByDisplayValue('90')).toBeTruthy();
  });

  it('pushes an edit through updateConfig under the inactivityTimeout key', () => {
    render(<ChatConfigPanel conversationId="c1" />);
    fireEvent.change(screen.getByDisplayValue('90'), { target: { value: '45' } });
    expect(h.updateConfig).toHaveBeenCalledWith({ inactivityTimeout: 45 });
    // It must not touch executionTimeout (the sibling field stays independent).
    expect(h.updateConfig).not.toHaveBeenCalledWith(expect.objectContaining({ executionTimeout: expect.anything() }));
  });

  it('lets the user disable the watchdog by entering 0 (sent verbatim, not coerced to default)', () => {
    render(<ChatConfigPanel conversationId="c1" />);
    fireEvent.change(screen.getByDisplayValue('90'), { target: { value: '0' } });
    expect(h.updateConfig).toHaveBeenCalledWith({ inactivityTimeout: 0 });
  });

  it('shows 0 verbatim when the stored value is the disabled sentinel', () => {
    h.config = { maxTokens: 4500, maxIterations: 50, executionTimeout: 600, inactivityTimeout: 0, temperature: 0.7 };
    render(<ChatConfigPanel conversationId="c1" />);
    // executionTimeout=600 and inactivityTimeout=0 both render; 0 must not become 300.
    const zeroInputs = screen.getAllByDisplayValue('0');
    expect(zeroInputs.length).toBeGreaterThan(0);
  });
});
