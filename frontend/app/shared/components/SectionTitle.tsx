'use client';

import { ReactNode } from 'react';
import { cn } from '@/lib/utils';

export interface SectionTitleProps {
  children: ReactNode;
  className?: string;
  size?: 'sm' | 'md' | 'lg';
}

const sizeClasses = {
  sm: 'text-lg',
  md: 'text-xl',
  lg: 'text-2xl',
};

export function SectionTitle({ 
  children, 
  className,
  size = 'md'
}: SectionTitleProps) {
  return (
    <h2 className={cn(
      'font-bold text-theme-primary transition-colors duration-300',
      sizeClasses[size],
      className
    )}>
      {children}
    </h2>
  );
}

