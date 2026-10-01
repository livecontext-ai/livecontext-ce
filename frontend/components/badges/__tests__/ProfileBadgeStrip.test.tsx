/**
 * @vitest-environment jsdom
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { Badge } from '@/lib/api/orchestrator/badges.service';

const mocks = vi.hoisted(() => ({ badges: [] as Badge[] }));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${JSON.stringify(values)}` : key,
}));
vi.mock('@tanstack/react-query', () => ({ useQuery: () => ({ data: mocks.badges }) }));
vi.mock('../BadgeDetailDialog', () => ({ BadgeDetailDialog: () => null }));
vi.mock('../BadgeMedal', () => ({ BadgeMedal: () => null }));
vi.mock('../badgeAnalytics', () => ({ trackTrophyViewed: vi.fn() }));

import { ProfileBadgeStrip, tilesPerRow } from '../ProfileBadgeStrip';

const makeBadges = (n: number): Badge[] =>
  Array.from({ length: n }, (_, i) => ({
    code: `b${i}`, family: 'BUILDER', tier: 'GOLD', metric: 'WORKFLOWS_CREATED',
    threshold: 1, value: 1, unlocked: true, unlockedAt: '2026-09-01T00:00:00Z',
  }) as Badge);

/** Every element reports this width: jsdom has no layout, so the row is "measured" here. */
function setRowWidth(width: number) {
  Object.defineProperty(HTMLElement.prototype, 'clientWidth', { configurable: true, get: () => width });
}

const medalCount = () => screen.queryAllByText(/^item\.b\d+\.name$/).length;
const moreTile = () => screen.queryByRole('button', { name: /^showMore:/ });

beforeEach(() => {
  mocks.badges = [];
});
afterEach(() => {
  cleanup();
  delete (HTMLElement.prototype as { clientWidth?: number }).clientWidth;
});

describe('tilesPerRow', () => {
  it('counts 68px tiles separated by 16px gaps, never fewer than one', () => {
    expect(tilesPerRow(68)).toBe(1);
    expect(tilesPerRow(151)).toBe(1);
    expect(tilesPerRow(152)).toBe(2); // 68 + 16 + 68
    expect(tilesPerRow(500)).toBe(6);
    expect(tilesPerRow(20)).toBe(1);
  });

  it('falls back to a sane count when the row has no measurable width yet', () => {
    expect(tilesPerRow(0)).toBe(6);
    expect(tilesPerRow(Number.NaN)).toBe(6);
  });
});

describe('ProfileBadgeStrip', () => {
  it('renders nothing when the owner has no trophies', () => {
    const { container } = render(<ProfileBadgeStrip userId={7} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('shows every trophy and no "+" tile when they all fit on one row', () => {
    setRowWidth(500); // 6 per row
    mocks.badges = makeBadges(6);

    render(<ProfileBadgeStrip userId={7} />);

    expect(medalCount()).toBe(6);
    expect(moreTile()).toBeNull();
  });

  it('regression: collapsed shelf is ONE row, the last slot becomes a "+N" tile counting the hidden trophies', () => {
    setRowWidth(320); // floor((320 + 16) / 84) = 4 per row
    mocks.badges = makeBadges(10);

    render(<ProfileBadgeStrip userId={7} />);

    // 3 medals + the "+7" tile fill the 4 slots; pre-fix, 12 medals wrapped onto 3 rows.
    expect(medalCount()).toBe(3);
    const tile = moreTile()!;
    expect(tile).toHaveTextContent('+7');
    expect(tile).toHaveAccessibleName('showMore:{"count":7}');
    const row = screen.getByTestId('profile-badge-row');
    expect(row.className).toContain('flex-nowrap');
    expect(row.className).not.toContain('flex-wrap ');
    // No clipping: it cut the medals' focus rings at the top and bottom of the row.
    expect(row.className).not.toContain('overflow-hidden');
  });

  it('the "+" tile is a filled accent disc, not a faint text link', () => {
    setRowWidth(320);
    mocks.badges = makeBadges(10);

    render(<ProfileBadgeStrip userId={7} />);

    const disc = screen.getByText('+7');
    expect(disc.className).toContain('bg-[var(--accent-primary)]');
    expect(disc.className).toContain('text-[var(--accent-foreground)]');
  });

  it('"+N" expands to every trophy on wrapping rows, and "show less" collapses back to one row', () => {
    setRowWidth(320);
    mocks.badges = makeBadges(10);

    render(<ProfileBadgeStrip userId={7} />);
    fireEvent.click(moreTile()!);

    expect(medalCount()).toBe(10);
    expect(moreTile()).toBeNull();
    expect(screen.getByTestId('profile-badge-row').className).toContain('flex-wrap');

    fireEvent.click(screen.getByRole('button', { name: 'showLess' }));

    expect(medalCount()).toBe(3);
    expect(moreTile()).toHaveTextContent('+7');
  });

  it('on a row too narrow for two tiles, the "+" tile alone still gives access to everything', () => {
    setRowWidth(100); // 1 per row
    mocks.badges = makeBadges(3);

    render(<ProfileBadgeStrip userId={7} />);

    expect(medalCount()).toBe(0);
    expect(moreTile()).toHaveTextContent('+3');
  });
});
