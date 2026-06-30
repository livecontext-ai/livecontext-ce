'use client';

import { ReactNode } from 'react';
import { LucideIcon } from 'lucide-react';
import { cn } from '@/lib/utils';

export interface PageTitleProps {
  children: ReactNode;
  icon?: LucideIcon;
  className?: string;
  size?: 'sm' | 'md' | 'lg' | 'xl';
}

const sizeClasses = {
  sm: 'text-2xl sm:text-3xl',
  md: 'text-3xl sm:text-4xl',
  lg: 'text-4xl sm:text-5xl',
  xl: 'text-4xl xl:text-5xl',
};

export function PageTitle({ 
  children, 
  icon: Icon, 
  className,
  size = 'md'
}: PageTitleProps) {
  return (
    <div className={cn('flex items-center gap-3', className)}>
      {Icon && (
        <Icon className="w-6 h-6 text-theme-primary flex-shrink-0" />
      )}
      <h1 className={cn(
        'font-semibold text-theme-primary transition-colors duration-300',
        sizeClasses[size]
      )}>
        {children}
      </h1>
    </div>
  );
}

