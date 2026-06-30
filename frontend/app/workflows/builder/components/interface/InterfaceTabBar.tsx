'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { cn } from '@/lib/utils';
import { usePendingInterfacesStore, type PendingInterface } from '@/lib/stores/pending-interfaces-store';

/**
 * Tab bar showing pending interfaces during run mode.
 * Each tab shows the interface label + status dot.
 * Only visible when 2+ pending interfaces exist.
 */
export function InterfaceTabBar() {
  const t = useTranslations('workflowBuilder.inspector.interfaceMappings');
  const interfaces = usePendingInterfacesStore((s) => s.interfaces);
  const activeNodeId = usePendingInterfacesStore((s) => s.activeNodeId);
  const setActive = usePendingInterfacesStore((s) => s.setActive);

  const interfaceList = React.useMemo(
    () => Array.from(interfaces.values()).sort((a, b) => a.addedAt - b.addedAt),
    [interfaces]
  );

  if (interfaceList.length < 2) return null;

  return (
    <div className="flex items-center gap-1 px-2 py-1.5 border-b border-slate-200 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-800/30 overflow-x-auto">
      {interfaceList.map((iface) => (
        <button
          key={iface.nodeId}
          type="button"
          onClick={() => setActive(iface.nodeId)}
          className={cn(
            'flex items-center gap-1.5 px-2.5 py-1 rounded-md text-xs font-medium transition-colors whitespace-nowrap',
            activeNodeId === iface.nodeId
              ? 'bg-white dark:bg-slate-700 text-slate-900 dark:text-slate-100 shadow-sm'
              : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700/50'
          )}
        >
          <StatusDot status={iface.status} />
          <span className="max-w-[120px] truncate">{iface.label}</span>
        </button>
      ))}
    </div>
  );
}

function StatusDot({ status }: { status: PendingInterface['status'] }) {
  return (
    <span
      className={cn(
        'inline-block w-1.5 h-1.5 rounded-full flex-shrink-0',
        status === 'awaiting' && 'bg-amber-400 animate-pulse',
        status === 'rendered' && 'bg-green-400'
      )}
    />
  );
}
