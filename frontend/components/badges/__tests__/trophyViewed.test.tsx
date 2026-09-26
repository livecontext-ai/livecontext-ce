/**
 * @vitest-environment jsdom
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { Badge } from '@/lib/api/orchestrator/badges.service';

const mocks = vi.hoisted(() => ({ track: vi.fn(), badges: [] as Badge[] }));

vi.mock('@/lib/analytics/analytics', () => ({ track: (...a: unknown[]) => mocks.track(...a) }));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/lib/utils/locale', () => ({ getClientLocale: () => 'en' }));
vi.mock('@/hooks/useBadges', () => ({
  useBadges: () => ({
    badges: mocks.badges,
    unlockedCount: mocks.badges.filter((b) => b.unlocked).length,
    totalCount: mocks.badges.length,
    isLoading: false,
    error: null,
  }),
}));
vi.mock('@tanstack/react-query', () => ({ useQuery: () => ({ data: mocks.badges }) }));
vi.mock('../BadgeDetailDialog', () => ({ BadgeDetailDialog: () => null }));
vi.mock('../BadgeMedal', () => ({ BadgeMedal: () => null }));

import { BadgeCollection } from '../BadgeCollection';
import { ProfileBadgeStrip } from '../ProfileBadgeStrip';

const badge = (overrides: Partial<Badge>): Badge => ({
  code: 'builder_10', family: 'BUILDER', tier: 'SILVER', metric: 'WORKFLOWS_CREATED',
  threshold: 10, value: 0, unlocked: false, unlockedAt: null, ...overrides,
} as Badge);

const EARNED = badge({ code: 'founder_1', family: 'FOUNDER', tier: 'GOLD', unlocked: true, unlockedAt: '2026-09-01T00:00:00Z', value: 1, threshold: 1 });
const CLOSE = badge({ code: 'builder_10', value: 6 });
const UNTOUCHED = badge({ code: 'tenure_365', family: 'TENURE', tier: 'DIAMOND', metric: 'DAYS_ACTIVE' as Badge['metric'], threshold: 365 });

beforeEach(() => {
  mocks.track.mockReset();
  mocks.badges = [EARNED, CLOSE, UNTOUCHED];
});
afterEach(cleanup);

const buttonsFor = (code: string) => screen.getAllByText(`item.${code}.name`).map((el) => el.closest('button')!);

describe('trophy_viewed', () => {
  it('names the strip or the grid a trophy was opened from, with enums lowercased', () => {
    render(<BadgeCollection />);

    // Recent strip first in the page, then the grid.
    fireEvent.click(buttonsFor('founder_1')[0]);
    expect(mocks.track).toHaveBeenLastCalledWith('trophy_viewed', {
      badge_code: 'founder_1', badge_family: 'founder', badge_tier: 'gold', unlocked: true, entry_point: 'recent',
    });

    fireEvent.click(buttonsFor('builder_10')[0]);
    expect(mocks.track).toHaveBeenLastCalledWith('trophy_viewed', expect.objectContaining({
      badge_code: 'builder_10', unlocked: false, entry_point: 'next',
    }));

    fireEvent.click(buttonsFor('tenure_365')[0]);
    expect(mocks.track).toHaveBeenLastCalledWith('trophy_viewed', expect.objectContaining({
      badge_code: 'tenure_365', badge_family: 'tenure', badge_tier: 'diamond', entry_point: 'grid',
    }));
  });

  it('a trophy opened on a profile is reported as such', () => {
    mocks.badges = [EARNED];
    render(<ProfileBadgeStrip userId={7} />);

    fireEvent.click(buttonsFor('founder_1')[0]);

    expect(mocks.track).toHaveBeenCalledWith('trophy_viewed', {
      badge_code: 'founder_1', badge_family: 'founder', badge_tier: 'gold', unlocked: true, entry_point: 'profile',
    });
  });
});
