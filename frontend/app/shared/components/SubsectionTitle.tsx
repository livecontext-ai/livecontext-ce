'use client';

import { ReactNode } from 'react';
import { cn } from '@/lib/utils';

export interface SubsectionTitleProps {
  children: ReactNode;
  className?: string;
  size?: 'sm' | 'md';
}

const sizeClasses = {
  sm: 'text-base',
  md: 'text-lg',
};

export function SubsectionTitle({ 
  children, 
  className,
  size = 'md'
}: SubsectionTitleProps) {
  return (
    <h3 className={cn(
      'font-semibold text-theme-primary transition-colors duration-300',
      sizeClasses[size],
      className
    )}>
      {children}
    </h3>
  );
}

