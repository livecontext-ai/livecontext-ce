/**
 * @vitest-environment jsdom
 *
 * On the account Preferences page (userDefault) the "Chat defaults" panel is laid
 * out like the General Preferences rows above it: each toggle is a platform Switch
 * on the right of a title+description row, and "Advanced limits" is its own
 * always-visible section (no collapse). The composer/agent layouts are unaffected.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import * as React from 'react';

const h = vi.hoisted(() => ({ updateConfig: vi.fn() }));

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/hooks/useChatConfig', () => ({
  useChatConfig: () => ({
    config: {
      temperature: 0.7,
      webSearch: true,
      imageGeneration: { enabled: false },
      autoAuthorizeTools: false,
    },
    updateConfig: h.updateConfig,
    isLoading: false,
    isSaving: false,
    error: null,
    target: 'user-default',
  }),
}));
// Stub the radix-based wrappers so jsdom doesn't need ResizeObserver / pointer APIs.
// Switch and Textarea are NOT mocked - the platform Switch is exactly what we assert on.
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

describe('ChatConfigPanel - Chat defaults (user-default) row layout', () => {
  afterEach(() => {
    cleanup();
    h.updateConfig.mockClear();
  });

  it('renders the toggles as platform Switches', () => {
    render(<ChatConfigPanel userDefault />);
    // web search + image generation + run-sensitive-actions + context-compaction enable.
    expect(screen.getAllByRole('switch')).toHaveLength(4);
  });

  it('surfaces Advanced limits as an always-visible section (no collapse toggle)', () => {
    render(<ChatConfigPanel userDefault />);
    expect(screen.getByText('advancedSectionTitle')).toBeInTheDocument();
    // The 3 turn-limit fields render directly (not hidden behind a chevron).
    expect(screen.getByText('maxPerResourcePerTurnLabel')).toBeInTheDocument();
    expect(screen.getByText('loopIdenticalStopLabel')).toBeInTheDocument();
    expect(screen.getByText('loopConsecutiveStopLabel')).toBeInTheDocument();
  });

  it('persists a Web-search toggle through updateConfig', () => {
    render(<ChatConfigPanel userDefault />);
    fireEvent.click(screen.getByRole('switch', { name: 'webSearchLabel' }));
    expect(h.updateConfig).toHaveBeenCalledWith({ webSearch: false });
  });

  it('persists the Image-generation enabled flag through updateConfig', () => {
    render(<ChatConfigPanel userDefault />);
    fireEvent.click(screen.getByRole('switch', { name: 'imageGenerationLabel' }));
    expect(h.updateConfig).toHaveBeenCalledWith({ imageGeneration: { enabled: true } });
  });

  it('persists the Run-sensitive-actions toggle through updateConfig', () => {
    render(<ChatConfigPanel userDefault />);
    fireEvent.click(screen.getByRole('switch', { name: 'autoAuthorizeLabel' }));
    expect(h.updateConfig).toHaveBeenCalledWith({ autoAuthorizeTools: true });
  });

  it('shows ⓘ info tooltips on every row like the numeric fields (no inline description paragraphs)', () => {
    render(<ChatConfigPanel userDefault />);
    // 6 in the top section (system prompt, temperature, tools mode, web search,
    // image generation, auto-authorize) + 7 NumericInputs (4 main: max tokens,
    // max iterations, execution timeout, inactivity timeout; + 3 advanced)
    // + the context-compaction enable row (its after-N-turns field is hidden while disabled).
    expect(document.querySelectorAll('svg.lucide-info')).toHaveLength(14);
  });

  it('renders the temperature slider full-width (not boxed in a 220px right column)', () => {
    render(<ChatConfigPanel userDefault />);
    expect(screen.getByTestId('slider')).toBeInTheDocument();
    expect(document.querySelector('[class*="w-[220px]"]')).toBeNull();
  });
});
