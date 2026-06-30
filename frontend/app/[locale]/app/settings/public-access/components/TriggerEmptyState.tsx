'use client';

import React from 'react';
import type { LucideIcon } from 'lucide-react';

interface TriggerEmptyStateProps {
  icon: LucideIcon;
  title: string;
  description: string;
}

export function TriggerEmptyState({ icon: Icon, title, description }: TriggerEmptyStateProps) {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-center">
      <Icon className="h-10 w-10 text-slate-300 dark:text-slate-600 mb-3" />
      <h3 className="text-sm font-medium text-slate-600 dark:text-slate-300 mb-1">{title}</h3>
      <p className="text-xs text-slate-400 dark:text-slate-500 max-w-sm">{description}</p>
    </div>
  );
}
