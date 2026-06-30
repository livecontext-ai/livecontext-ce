/**
 * @vitest-environment jsdom
 *
 * Regression: TimelineItem renders its tool "no content" fallback.
 *
 * The i18n batch (12fb1cc64) swapped the hardcoded no-content string for
 * t('tool.noContent') at the expanded-but-empty branch, but TimelineItem renders
 * independently of ActivityFeed and had no useTranslations of its own - so `t`
 * was undefined in that scope. It threw "t is not defined" the moment the branch
 * rendered, and broke `next build`'s type check (TS2304). This pins the fix: an
 * expanded, content-less tool activity renders the fallback without throwing.
 */
import { describe, it, expect, vi } from 'vitest';
import { render } from '@testing-library/react';
import * as React from 'react';

// Passthrough translator: returns the key, so the rendered fallback is the
// literal 'tool.noContent' we assert on.
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));
// Force the timeline item open so the expanded no-content branch actually runs.
vi.mock('@/hooks/useExpandedState', () => ({
  useExpandedState: () => [true, () => {}],
}));
// Keep the single activity ungrouped so it renders as a TimelineItem (the
// component under test), not a GroupedToolCard.
vi.mock('@/hooks/useStableGroupedActivities', () => ({
  useStableGroupedActivities: (activities: unknown[]) => activities,
}));
vi.mock('@/lib/utils/activityGrouping', () => ({
  isGroupedTool: () => false,
  getToolDescription: () => 'Ran a tool',
  getToolIconType: () => 'code',
}));
vi.mock('@/lib/api', () => ({
  apiClient: { get: async () => ({ content: '' }) },
}));
vi.mock('next/image', () => ({ default: () => null }));
// Heavy presentational children that never render on the no-content path. Stub
// them so the test does not drag in their transitive imports (next-intl
// navigation -> next/navigation, etc.) - the branch under test renders none.
vi.mock('../GroupedToolCard', () => ({ GroupedToolCard: () => null }));
vi.mock('../TasksPreviewBlock', () => ({ TasksPreviewBlock: () => null }));
vi.mock('../DiffView', () => ({ default: () => null }));
vi.mock('../GitStatusView', () => ({ default: () => null }));
vi.mock('@/components/MarkdownRender', () => ({ default: () => null }));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => null }));

import { ActivityFeed, type ToolActivity } from '../ActivityFeed';

describe('ActivityFeed TimelineItem no-content fallback', () => {
  it('renders the tool no-content fallback for an expanded, content-less tool (regression: t was out of scope)', () => {
    // A completed tool with no result / diff / gitStatus / tasks -> the
    // expanded branch falls through to t('tool.noContent').
    const activity = {
      id: 'a1',
      toolName: 'do_thing',
      status: 'success',
      timestamp: 1,
    } as unknown as ToolActivity;

    const { container } = render(
      <ActivityFeed activities={[activity]} isStreaming={true} />,
    );

    // Post-fix: the fallback renders (passthrough key). Pre-fix this render threw
    // "t is not defined" inside TimelineItem.
    expect(container.textContent).toContain('tool.noContent');
  });
});
