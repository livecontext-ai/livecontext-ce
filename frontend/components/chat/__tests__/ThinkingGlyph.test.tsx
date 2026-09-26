/**
 * @vitest-environment jsdom
 *
 * The braille spinner in front of "Thinking…".
 *
 * <p>What is pinned: the frames advance at the declared pace and loop; a reader
 * with reduced motion gets one still frame and no timer; the timer dies with the
 * component; and in the feed header the glyph sits INSIDE the `shimmer-text`
 * element with no color of its own, which is the only way the glyph and the word
 * share one gradient (a sibling would get the default text color instead).
 */
import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import * as React from 'react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));
vi.mock('@/hooks/useExpandedState', () => ({ useExpandedState: () => [true, () => {}] }));
vi.mock('@/hooks/useStableGroupedActivities', () => ({
  useStableGroupedActivities: (activities: unknown[]) => activities,
}));
vi.mock('@/lib/utils/activityGrouping', () => ({
  isGroupedTool: () => false,
  getToolDescription: (name: string) => name,
  getToolIconType: () => 'code',
}));
vi.mock('@/lib/api', () => ({ apiClient: { get: async () => ({ content: '' }) } }));
vi.mock('@/lib/hooks/useResourceQuery', () => ({
  useResourceQuery: () => ({ data: undefined, isLoading: false, error: null }),
}));
vi.mock('next/image', () => ({ default: () => null }));
vi.mock('../GroupedToolCard', () => ({ GroupedToolCard: () => null }));
vi.mock('../TasksPreviewBlock', () => ({ TasksPreviewBlock: () => null }));
vi.mock('../DiffView', () => ({ default: () => null }));
vi.mock('../GitStatusView', () => ({ default: () => null }));
vi.mock('@/components/MarkdownRender', () => ({ default: () => null }));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => null }));

import { ThinkingGlyph, THINKING_GLYPH_FRAMES, THINKING_GLYPH_FRAME_MS } from '../ThinkingGlyph';
import { ActivityFeed } from '../ActivityFeed';

function stubReducedMotion(matches: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches,
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  }));
}

const glyph = () => screen.getByTestId('thinking-glyph');

beforeEach(() => {
  vi.useFakeTimers();
  stubReducedMotion(false);
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('ThinkingGlyph', () => {
  it('advances one braille frame per tick and loops back to the first', () => {
    render(<ThinkingGlyph />);
    expect(glyph().textContent).toBe(THINKING_GLYPH_FRAMES[0]);

    act(() => { vi.advanceTimersByTime(THINKING_GLYPH_FRAME_MS); });
    expect(glyph().textContent).toBe(THINKING_GLYPH_FRAMES[1]);

    act(() => { vi.advanceTimersByTime(THINKING_GLYPH_FRAME_MS * (THINKING_GLYPH_FRAMES.length - 1)); });
    expect(glyph().textContent).toBe(THINKING_GLYPH_FRAMES[0]);
  });

  it('is hidden from screen readers, so the label is announced once and not per frame', () => {
    render(<ThinkingGlyph />);
    expect(glyph().getAttribute('aria-hidden')).toBe('true');
  });

  it('holds one still frame and starts no timer when the reader asks for reduced motion', () => {
    stubReducedMotion(true);
    render(<ThinkingGlyph />);

    expect(vi.getTimerCount()).toBe(0);
    act(() => { vi.advanceTimersByTime(THINKING_GLYPH_FRAME_MS * 5); });
    expect(glyph().textContent).toBe(THINKING_GLYPH_FRAMES[0]);
  });

  it('clears its timer on unmount', () => {
    const { unmount } = render(<ThinkingGlyph />);
    expect(vi.getTimerCount()).toBe(1);
    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });
});

describe('ActivityFeed thinking header', () => {
  it('renders the glyph inside the shimmer label with no color of its own, so one gradient paints both', () => {
    render(<ActivityFeed activities={[]} thinkingMessage="Thinking..." isStreaming />);

    const label = screen.getByTestId('activity-feed-thinking');
    expect(label.classList.contains('shimmer-text')).toBe(true);
    expect(label.contains(glyph())).toBe(true);
    expect(glyph().className).not.toMatch(/\b(text|bg)-(?!center)/);
    expect(glyph().getAttribute('style')).toBeNull();
    // The glyph comes first, the word after it.
    expect(label.textContent).toBe(`${THINKING_GLYPH_FRAMES[0]}thinking`);
  });

  it('shows no glyph once the turn is over and the header reads the duration', () => {
    const done = { id: 't', toolId: 't', toolName: 't', status: 'success' as const, timestamp: 1, displayToolName: 't' };
    render(<ActivityFeed activities={[done]} isStreaming={false} />);
    expect(screen.getByRole('button', { name: /^duration/ })).toBeTruthy();
    expect(screen.queryByTestId('thinking-glyph')).toBeNull();
  });
});
