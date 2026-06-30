// @vitest-environment jsdom
/**
 * Covers two new-workflow ("How can I help you?") fixes:
 *  1. Toolbox reachability - the composer column re-enables pointer events on
 *     itself while its wrapping Panel is pointer-events:none, so the empty
 *     regions around it (e.g. the top-right "Add node" button) stay clickable.
 *  2. i18n - the trigger dropdown shows a TRANSLATED name (keyed by id), not the
 *     hardcoded English `trigger.name`. Chip labels come from props (resolved
 *     upstream from i18n by BuilderCanvas).
 */
import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, fireEvent } from '@testing-library/react';
import { Plane } from 'lucide-react';

// translations resolve to their key path, so we can assert "it went through i18n"
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/app/shared/components', () => ({
  WelcomeTitle: ({ children }: { children: React.ReactNode }) => <h1>{children}</h1>,
}));

import { EmptyCanvasChat } from '../EmptyCanvasChat';

const baseProps = () => ({
  chatInput: '',
  onChatInputChange: vi.fn(),
  onSendMessage: vi.fn(),
  onStopStream: vi.fn(),
  isStreaming: false,
  displayedSuggestions: [
    { id: 'flight-deals', label: 'CHIP_FLIGHT', prompt: 'p', icon: Plane, triggerType: 'schedule' as const },
  ],
  typingSuggestionId: null,
  onSuggestionClick: vi.fn(),
  onCreateNode: vi.fn(),
  onGetViewportCenter: vi.fn(() => ({ x: 0, y: 0 })),
});

describe('EmptyCanvasChat', () => {
  it('re-enables pointer events on the composer column (wraps the textarea)', () => {
    const { container, getByPlaceholderText } = render(<EmptyCanvasChat {...baseProps()} />);
    const textarea = getByPlaceholderText('messagePlaceholder');
    const autoEl = container.querySelector('[style*="pointer-events: auto"]') as HTMLElement | null;
    expect(autoEl).toBeTruthy();
    // the re-enabled region must actually contain the interactive composer
    expect(autoEl!.contains(textarea)).toBe(true);
  });

  it('renders the chip label passed in (resolved from i18n upstream)', () => {
    const { getByText } = render(<EmptyCanvasChat {...baseProps()} />);
    expect(getByText('CHIP_FLIGHT')).toBeTruthy();
  });

  it('trigger dropdown shows the TRANSLATED name (i18n key), not the hardcoded trigger.name', () => {
    const { getByText, queryByText } = render(<EmptyCanvasChat {...baseProps()} />);
    // open the dropdown (toggle is labelled via t('trigger'))
    fireEvent.click(getByText('trigger'));
    // post-fix: name comes from t(`triggerTypes.${id}`)
    expect(getByText('triggerTypes.schedule-trigger')).toBeTruthy();
    expect(getByText('triggerTypes.webhook-trigger')).toBeTruthy();
    // pre-fix it rendered the hardcoded English names
    expect(queryByText('Scheduler')).toBeNull();
    expect(queryByText('Webhook')).toBeNull();
  });
});
