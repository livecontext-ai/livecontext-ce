'use client';

import { ReactNode } from 'react';
import { cn } from '@/lib/utils';

export interface WelcomeTitleProps {
  children: ReactNode;
  className?: string;
}

export function WelcomeTitle({ 
  children, 
  className
}: WelcomeTitleProps) {
  return (
    <h2 className={cn(
      'text-2xl font-semibold text-theme-primary mb-2',
      className
    )}>
      {children}
    </h2>
  );
}

