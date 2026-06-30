'use client';

import { useCallback } from 'react';
import { useSearchParams, useRouter, usePathname } from 'next/navigation';
import { AgentTable } from '@/components/AgentTable';
import { SkillTab } from '@/components/SkillTab';
import { AuthenticatedView } from './AuthenticatedView';
import { AgentFleetCanvas } from '@/components/agent-fleet/AgentFleetCanvas';
import { AgentMetricsDashboard } from '@/components/agent-fleet/AgentMetricsDashboard';
import { AgentPageTabBar, type AgentPageTab } from './AgentPageTabBar';

type LocalTab = AgentPageTab;

const tabFromView = (view: string | null): LocalTab =>
  view === 'fleet' ? 'fleet'
    : view === 'metrics' ? 'metrics'
      : view === 'skills' ? 'skills'
        : 'agents';

/**
 * AgentView - Agent and Skill list view with tabs (inspired by marketplace)
 */
export function AgentView() {
  const searchParams = useSearchParams();
  const router = useRouter();
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
    // (skills/fleet/metrics) is encoded in the URL so it stays the single source
    // of truth and is deep-linkable.
    if (tab === 'agents') {
      router.replace(pathname);
    } else {
      router.replace(`${pathname}?view=${tab}`);
    }
  }, [router, pathname]);

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
        {activeTab === 'metrics' && <AgentMetricsDashboard />}
      </div>
    </AuthenticatedView>
  );
}
