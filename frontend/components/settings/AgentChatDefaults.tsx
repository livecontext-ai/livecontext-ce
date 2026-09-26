'use client';

import { MessageSquare } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { PageHeader } from '@/components/settings/PageHeader';
import { ChatConfigPanel } from '@/components/chat/ChatConfigPanel';

/**
 * Agent & general-chat defaults - the per-(user, workspace) chat defaults (V312) that
 * seed the message composer and every NEW conversation (general chat included) in this
 * workspace: system prompt, temperature, token/iteration/timeout budgets, tools mode,
 * web search, image generation, turn limits and compaction.
 *
 * Rendered ONLY by the Agents page "Settings" tab (/app/agent?view=settings), through
 * <ChatConfigPanel userDefault />, whose store is GET/PUT /v3/chat/defaults. Settings >
 * Agents & Chat used to mount a second copy and now redirects here; Settings > Overview >
 * Preferences keeps just a "Chat defaults" row linking to this tab.
 *
 * The heading is an h2: the sibling tabs render an h2 at most, and an h1 that appears on
 * one tab only would move the page's heading level around as the user switches tabs.
 *
 * Width is capped at max-w-4xl: the Agents page column is max-w-6xl and the sliders +
 * 2-column number grids stretch badly past ~900px.
 */
export function AgentChatDefaults() {
  const t = useTranslations('settings.agentDefaults');

  return (
    <div className="space-y-6 max-w-4xl">
      <PageHeader
        icon={MessageSquare}
        title={t('title')}
        subtitle={t('subtitle')}
        headingLevel="h2"
      />
      <ChatConfigPanel userDefault />
    </div>
  );
}

export default AgentChatDefaults;
