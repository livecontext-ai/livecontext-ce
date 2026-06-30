import * as React from 'react';
import clsx from 'clsx';

interface InspectorColumnProps {
  title: string;
  children: React.ReactNode;
  headerRight?: React.ReactNode;
  className?: string;
  showRightBorder?: boolean; // Pour afficher un séparateur sur le bord droit
  popover?: React.ReactNode; // Popover à afficher en position absolue
}

export const InspectorColumn = ({ title, children, headerRight, className, showRightBorder = false }: InspectorColumnProps) => {
  return (
    <div className={clsx("flex-1 flex flex-col min-w-0 min-h-0", className)}>
      <div className="p-3 flex items-center justify-center shrink-0">
        <label className="text-sm font-semibold uppercase tracking-[0.2em] text-slate-500 dark:text-slate-400 block">
          {title}
        </label>
        {headerRight}
      </div>
      <div className={clsx(
        "flex-1 min-h-0 overflow-y-auto px-3 pb-3 custom-scrollbar"
      )} style={{ height: 0 }}>
        {children}
      </div>
    </div>
  );
};

