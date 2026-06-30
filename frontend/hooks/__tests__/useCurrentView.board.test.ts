// @vitest-environment jsdom
/**
 * Pins the new `/app/board` → 'board' mapping in {@link useCurrentView}. The sidebar
 * active-highlight (both the collapsed rail and the expanded Board button) keys off
 * `currentView === 'board'`, so this mapping is what makes the Board entry light up.
 * Also guards that the old list routes (which now redirect to the Board) still resolve
 * to their own views before the redirect lands.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook } from '@testing-library/react';

let mockPath = '/app/board';
let mockParams: Record<string, string | undefined> = {};

vi.mock('next/navigation', () => ({
  usePathname: () => mockPath,
  useParams: () => mockParams,
}));

import { useCurrentView } from '../useCurrentView';

beforeEach(() => {
  mockParams = {};
});

describe('useCurrentView - board', () => {
  it('maps /app/board to the board view (list hub, not a detail page)', () => {
    mockPath = '/app/board';
    const { result } = renderHook(() => useCurrentView());
    expect(result.current.view).toBe('board');
    expect(result.current.isDetailPage).toBe(false);
  });

  it('maps the locale-prefixed /en/app/board to board too', () => {
    mockPath = '/en/app/board';
    const { result } = renderHook(() => useCurrentView());
    expect(result.current.view).toBe('board');
  });

  it('does not confuse /app/workflow (redirects to board, but resolves workflow first)', () => {
    mockPath = '/app/workflow';
    const { result } = renderHook(() => useCurrentView());
    expect(result.current.view).toBe('workflow');
  });
});
