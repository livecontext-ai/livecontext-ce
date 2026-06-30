'use client';

import { AgentView } from '@/components/views/AgentView';

/**
 * Agent page component
 * Uses native Next.js routing - view is determined by URL via useCurrentView() hook
 */
export default function AppAgentPage() {
  return <AgentView />;
}
