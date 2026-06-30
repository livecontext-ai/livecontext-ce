import { useRef, useMemo } from 'react';
import type { ToolActivity } from '@/components/chat/ActivityFeed';
import type { GroupedToolActivity, ToolActivityOrGroup } from '@/lib/utils/activityGrouping';

/**
 * Hook that provides stable references for grouped tool activities.
 *
 * Problem: When activities change during streaming, groupConsecutiveTools creates
 * new objects every time, causing React to unmount/remount child components.
 *
 * Solution: This hook maintains a cache of groups and only updates what changed,
 * preserving object references for unchanged groups and calls.
 */
export function useStableGroupedActivities(
  activities: ToolActivity[]
): ToolActivityOrGroup[] {
  // Cache for groups by ID
  const groupCacheRef = useRef<Map<string, GroupedToolActivity>>(new Map());
  // Cache for individual activities by ID
  const activityCacheRef = useRef<Map<string, ToolActivity>>(new Map());
  // Previous result for reference stability
  const prevResultRef = useRef<ToolActivityOrGroup[]>([]);

  return useMemo(() => {
    if (activities.length === 0) {
      groupCacheRef.current.clear();
      activityCacheRef.current.clear();
      prevResultRef.current = [];
      return [];
    }

    const result: ToolActivityOrGroup[] = [];
    let currentGroup: ToolActivity[] = [];
    let currentToolName: string | null = null;

    // Helper to get or create stable activity reference
    const getStableActivity = (activity: ToolActivity): ToolActivity => {
      const cached = activityCacheRef.current.get(activity.id);
      if (cached) {
        // Check if anything meaningful changed (including sub-agent fields)
        if (
          cached.status === activity.status &&
          cached.durationMs === activity.durationMs &&
          cached.result === activity.result &&
          cached.error === activity.error &&
          cached.subAgentStatus === activity.subAgentStatus &&
          cached.subAgentContent === activity.subAgentContent &&
          cached.subAgentThinking === activity.subAgentThinking &&
          cached.subActivities?.length === activity.subActivities?.length
        ) {
          return cached; // Return cached reference
        }
        // Update cache with new activity
        activityCacheRef.current.set(activity.id, activity);
        return activity;
      }
      // New activity, add to cache
      activityCacheRef.current.set(activity.id, activity);
      return activity;
    };

    // Helper to flush a group
    const flushGroup = () => {
      if (currentGroup.length === 0) return;

      const groupId = `group-${currentGroup[0].id}`;
      const cachedGroup = groupCacheRef.current.get(groupId);

      // Get stable references for all calls
      const stableCalls = currentGroup.map(getStableActivity);

      // Compute aggregated values
      const overallStatus = computeOverallStatus(stableCalls);
      const totalDurationMs = computeTotalDuration(stableCalls);
      const visualizations = extractVisualizations(stableCalls);

      if (cachedGroup) {
        // Check if group actually changed
        const callsChanged =
          cachedGroup.calls.length !== stableCalls.length ||
          !stableCalls.every((call, i) => cachedGroup.calls[i] === call);

        const statusChanged = cachedGroup.overallStatus !== overallStatus;
        const durationChanged = cachedGroup.totalDurationMs !== totalDurationMs;

        if (!callsChanged && !statusChanged && !durationChanged) {
          // Nothing changed, reuse cached group
          result.push(cachedGroup);
        } else {
          // Something changed, create updated group but try to preserve calls array if unchanged
          const updatedGroup: GroupedToolActivity = {
            ...cachedGroup,
            calls: callsChanged ? stableCalls : cachedGroup.calls,
            overallStatus,
            totalDurationMs,
            visualizations,
          };
          groupCacheRef.current.set(groupId, updatedGroup);
          result.push(updatedGroup);
        }
      } else {
        // New group
        const newGroup: GroupedToolActivity = {
          type: 'group',
          id: groupId,
          toolName: currentToolName!,
          calls: stableCalls,
          overallStatus,
          totalDurationMs,
          timestamp: stableCalls[0].timestamp,
          visualizations,
        };
        groupCacheRef.current.set(groupId, newGroup);
        result.push(newGroup);
      }
    };

    // Process activities
    for (const activity of activities) {
      // System tools, _thinking, and agent (sub-agent) tools are never grouped - they are rendered directly
      // Agent tools need direct rendering to show sub-agent activities, thinking, and content
      const isSystemTool = activity.toolName.startsWith('_system_') || activity.toolName === '_thinking' || activity.toolName === 'agent';

      if (!isSystemTool && activity.toolName === currentToolName) {
        // Same tool name - add to current group
        currentGroup.push(activity);
      } else {
        // Different tool name - flush previous group
        flushGroup();

        if (isSystemTool) {
          // System tools are added directly (not grouped)
          result.push(getStableActivity(activity));
          currentGroup = [];
          currentToolName = null;
        } else {
          // Start new group
          currentGroup = [activity];
          currentToolName = activity.toolName;
        }
      }
    }

    // Flush last group
    flushGroup();

    // Clean up old entries from cache (groups/activities that are no longer present)
    const currentGroupIds = new Set(
      result.filter((item): item is GroupedToolActivity => 'type' in item && item.type === 'group')
        .map(g => g.id)
    );
    const currentActivityIds = new Set(activities.map(a => a.id));

    for (const key of groupCacheRef.current.keys()) {
      if (!currentGroupIds.has(key)) {
        groupCacheRef.current.delete(key);
      }
    }
    for (const key of activityCacheRef.current.keys()) {
      if (!currentActivityIds.has(key)) {
        activityCacheRef.current.delete(key);
      }
    }

    // Check if result array itself changed
    if (
      prevResultRef.current.length === result.length &&
      result.every((item, i) => prevResultRef.current[i] === item)
    ) {
      return prevResultRef.current; // Return same array reference
    }

    prevResultRef.current = result;
    return result;
  }, [activities]);
}

// Helper functions (same as in activityGrouping.ts but inlined to avoid circular deps)
function computeOverallStatus(calls: ToolActivity[]): 'pending' | 'success' | 'error' | 'interrupted' {
  if (calls.some(c => c.status === 'pending')) return 'pending';
  if (calls.some(c => c.status === 'error')) return 'error';
  // 'interrupted' = at least one tool_call never executed (turn stopped before
  // the tool ran). Neutral state - neither success nor real error.
  if (calls.some(c => c.status === 'interrupted')) return 'interrupted';
  return 'success';
}

function computeTotalDuration(calls: ToolActivity[]): number {
  return calls.reduce((sum, c) => sum + (c.durationMs || 0), 0);
}

function extractVisualizations(calls: ToolActivity[]) {
  return calls
    .filter(c => c.visualization)
    .map(c => c.visualization!);
}
