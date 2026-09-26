'use client';

import { useCallback } from 'react';
import { useSearchParams, usePathname } from 'next/navigation';
import { samePageUrl, showSamePageUrl } from '@/lib/navigation/showSamePageUrl';
import { AgentTable } from '@/components/AgentTable';
import { SkillTab } from '@/components/SkillTab';
import { MemoryTab } from '@/components/MemoryTab';
import { AgentChatDefaults } from '@/components/settings/AgentChatDefaults';
import { AuthenticatedView } from './AuthenticatedView';
import { AgentFleetCanvas } from '@/components/agent-fleet/AgentFleetCanvas';
import { AgentMetricsDashboard } from '@/components/agent-fleet/AgentMetricsDashboard';
import { AgentPageTabBar, type AgentPageTab } from './AgentPageTabBar';

type LocalTab = AgentPageTab;

const tabFromView = (view: string | null): LocalTab =>
  view === 'fleet' ? 'fleet'
    : view === 'metrics' ? 'metrics'
      : view === 'skills' ? 'skills'
        : view === 'memory' ? 'memory'
          : view === 'settings' ? 'settings'
            : 'agents';

/**
 * AgentView - Agent and Skill list view with tabs (inspired by marketplace)
 */
export function AgentView() {
  const searchParams = useSearchParams();
  const pathname = usePathname();

  // The active tab is derived DIRECTLY from the URL (?view=) at render time -
  // the URL is the single source of truth. Mirroring it into local state via a
  // useEffect([searchParams]) was fragile: in Fleet the tab bar is hidden, so the
  // breadcrumb "Agents" crumb is the ONLY way out, and it just clears ?view= with
  // no setState fallback. When the effect failed to re-fire on that navigation the
  // canvas stayed mounted ("nothing happens"). Reading the param at render makes
  // the view follow the URL exactly as the breadcrumb itself does.
  const activeTab: LocalTab = tabFromView(searchParams.get('view'));

  const handleTabChange = useCallback((tab: LocalTab) => {
    // 'agents' is the default - keep its URL clean (no ?view=). Every other tab
    // (skills/memory/fleet/metrics/settings) is encoded in the URL so it stays the single source
    // of truth and is deep-linkable. It is a change of ADDRESS on the page already on
    // screen, and going back to the default removes the last param - which a router push of
    // the bare pathname cannot do when the page was loaded at it. That is the "nothing
    // happens" the comment above describes, and reading the param at render did not cure it
    // because the address itself never changed.
    showSamePageUrl(
      tab === 'agents' ? pathname : `${pathname}?view=${tab}`,
      samePageUrl(pathname, searchParams),
      'replace',
    );
  }, [pathname, searchParams]);

  // Fleet tab → full-screen canvas (no AuthenticatedView wrapper)
  if (activeTab === 'fleet') {
    return <AgentFleetCanvas />;
  }

  return (
    <AuthenticatedView>
      <div className="space-y-4">
        <AgentPageTabBar activeTab={activeTab} onLocalTabChange={handleTabChange} />
      </div>

      <div className="pb-8">
        {activeTab === 'agents' && <AgentTable />}
        {activeTab === 'skills' && <SkillTab />}
        {activeTab === 'memory' && <MemoryTab />}
        {activeTab === 'metrics' && <AgentMetricsDashboard />}
        {activeTab === 'settings' && <AgentChatDefaults />}
      </div>
    </AuthenticatedView>
  );
}
