'use client';

import React, { useState } from 'react';
import { X, Search } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { AvatarDisplay } from '@/components/agents';
import { useTranslations } from 'next-intl';

export interface AgentPickerItem {
  id: string;
  name: string;
  description?: string;
  avatarUrl?: string;
  modelProvider?: string;
  modelName?: string;
}

interface AgentPickerPanelProps {
  isOpen: boolean;
  onClose: () => void;
  agents: AgentPickerItem[];
  onSelectAgent: (agent: AgentPickerItem) => void;
}

export function AgentPickerPanel({
  isOpen,
  onClose,
  agents,
  onSelectAgent,
}: AgentPickerPanelProps) {
  const t = useTranslations();
  const [searchQuery, setSearchQuery] = useState('');

  if (!isOpen) return null;

  const filtered = agents.filter(a => {
    const term = searchQuery.trim().toLowerCase();
    if (!term) return true;
    return [a.name, a.description || ''].join(' ').toLowerCase().includes(term);
  });

  return (
    <>
      {/* Close button - desktop only (clips on small screens) */}
      <Button
        onClick={onClose}
        variant="secondary"
        size="sm"
        className="hidden sm:flex absolute top-0 -left-10 h-8 w-8 p-0 rounded-full z-[100] bg-[var(--bg-primary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] shadow-none"
      >
        <X className="h-4 w-4" />
      </Button>

      {/* Main panel - same style as NodeCreatorPanel */}
      <div
        data-agent-picker-panel
        className="w-[min(340px,calc(100vw-48px))] max-h-[800px] rounded-[32px] bg-white/80 dark:bg-gray-800/80 backdrop-blur flex flex-col pointer-events-auto overflow-hidden relative z-[100]"
      >
        {/* Mobile close button - inside panel */}
        <div className="sm:hidden flex justify-end px-3 pt-3 pb-0 flex-shrink-0">
          <Button onClick={onClose} variant="ghost" size="sm" className="h-7 w-7 p-0 rounded-full">
            <X className="h-4 w-4" />
          </Button>
        </div>

        {/* Search - same as NodeCreatorPanel */}
        <div className="px-5 pt-4 sm:pt-4 flex-shrink-0">
          <div className="relative flex items-center">
            <div className="absolute left-3 pointer-events-none z-10">
              <Search className="h-4 w-4 text-gray-400" />
            </div>
            <Input
              type="text"
              placeholder={`${t('common.search')}...`}
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="pl-9 pr-9"
              autoFocus
            />
            {searchQuery && (
              <button
                onClick={() => setSearchQuery('')}
                className="absolute right-3 z-10 text-gray-400 hover:text-gray-600"
              >
                <X className="h-4 w-4" />
              </button>
            )}
          </div>
        </div>

        {/* Agent list */}
        <div className="flex-1 overflow-y-auto px-3 py-3 space-y-1">
          {filtered.length === 0 ? (
            <div className="flex items-center justify-center py-8 text-sm text-theme-secondary">
              {t('sidePanel.noResults')}
            </div>
          ) : (
            filtered.map((agent) => (
              <button
                key={agent.id}
                onClick={() => onSelectAgent(agent)}
                className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl text-left transition-colors hover:bg-slate-100 dark:hover:bg-slate-700/50"
              >
                <AvatarDisplay avatarUrl={agent.avatarUrl} name={agent.name} size="sm" className="!w-8 !h-8 flex-shrink-0" />
                <div className="flex-1 min-w-0">
                  <span className="text-sm font-medium text-theme-primary truncate block">{agent.name}</span>
                  {agent.description && (
                    <span className="text-xs text-theme-secondary truncate block">{agent.description}</span>
                  )}
                  {agent.modelProvider && agent.modelName && (
                    <span className="text-xs text-theme-muted">{agent.modelProvider}/{agent.modelName}</span>
                  )}
                </div>
              </button>
            ))
          )}
        </div>
      </div>
    </>
  );
}
