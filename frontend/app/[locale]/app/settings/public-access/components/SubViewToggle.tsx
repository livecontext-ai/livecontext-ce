'use client';

import React from 'react';
import { Server, Link } from 'lucide-react';
import { useTranslations } from 'next-intl';

export type SubView = 'endpoints' | 'shared-links';

interface SubViewToggleProps {
  activeView: SubView;
  onViewChange: (view: SubView) => void;
}

const views: { id: SubView; icon: React.ElementType; labelKey: string }[] = [
  { id: 'endpoints', icon: Server, labelKey: 'subViewEndpoints' },
  { id: 'shared-links', icon: Link, labelKey: 'subViewSharedLinks' },
];

export function SubViewToggle({ activeView, onViewChange }: SubViewToggleProps) {
  const t = useTranslations('triggerSettings');

  return (
    <div className="flex items-center justify-center">
      <div className="inline-flex items-center gap-1 p-1.5 bg-theme-tertiary rounded-2xl w-max">
        {views.map(({ id, icon: Icon, labelKey }) => {
          const isActive = activeView === id;
          return (
            <button
              key={id}
              onClick={() => onViewChange(id)}
              className={`flex h-9 flex-shrink-0 items-center gap-1.5 px-3 sm:px-4 rounded-xl text-sm font-medium transition-all duration-200 outline-none focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]/60 ${
                isActive
                  ? 'bg-[var(--bg-primary)] text-[var(--text-primary)] shadow-sm'
                  : 'text-theme-secondary hover:text-theme-primary hover:bg-[var(--bg-primary)]/50'
              }`}
            >
              <Icon className="h-3.5 w-3.5 flex-shrink-0" />
              {t(labelKey)}
            </button>
          );
        })}
      </div>
    </div>
  );
}
