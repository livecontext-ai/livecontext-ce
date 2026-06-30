'use client';

import React from 'react';

interface EmptyStateProps {
  icon: React.ReactNode;
  title: string;
  subtitle: string;
  actions?: React.ReactNode;
  size?: 'md' | 'lg';
}

export function EmptyState({ icon, title, subtitle, actions, size = 'lg' }: EmptyStateProps) {
  const s = size === 'lg' ? 'w-16 h-16 bg-theme-secondary' : 'w-14 h-14 bg-theme-tertiary';

  return (
    <div className="flex flex-col items-center justify-center py-16 text-center">
      <div className={`${s} rounded-full flex items-center justify-center mb-4`}>
        {icon}
      </div>
      <h3 className="text-sm font-medium text-theme-primary mb-1">{title}</h3>
      <p className="text-sm text-theme-secondary max-w-sm">{subtitle}</p>
      {actions && <div className="mt-6 flex justify-center gap-2">{actions}</div>}
    </div>
  );
}
