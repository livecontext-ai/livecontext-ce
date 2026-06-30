import clsx from 'clsx';
import * as React from 'react';
import { ChevronRight } from 'lucide-react';

interface OptionalSectionProps {
  isOpen: boolean;
  onToggle: () => void;
  count: number | string;
  children: React.ReactNode;
  label?: string;
  className?: string;
}

export function OptionalSection({
  isOpen,
  onToggle,
  count,
  children,
  label = 'Optional parameters',
  className,
}: OptionalSectionProps) {
  return (
    <div className={clsx('border-l-2 border-slate-200 dark:border-slate-700 pl-3', className)}>
      <button
        type="button"
        className="flex items-center gap-2 text-xs text-slate-500 hover:text-slate-700 dark:hover:text-slate-300 transition-colors w-full py-2"
        onClick={onToggle}
      >
        <ChevronRight className={clsx('h-4 w-4 transition-transform', isOpen && 'rotate-90')} />
        <span>
          {label}{count ? ` (${count})` : ''}
        </span>
      </button>
      {isOpen ? <div className="space-y-4 pt-2">{children}</div> : null}
    </div>
  );
}
