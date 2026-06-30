import React from 'react';
import { useTranslations } from 'next-intl';
import { Workflow, Table, Monitor, Cpu, FolderOpen } from 'lucide-react';
import { Label } from '@/components/ui/label';
import { GRANT_FAMILIES, getGrant, getAccessMode, type GrantFamily, type ResourceGrant } from '@/lib/agents/toolsConfigAccess';

// ─── Per-family access grant + read/write display ───
// Surfaces the TWO independent access axes the backend stores on toolsConfig but the
// inspector previously hid: (1) the per-family GRANT (none|all|custom) and (2) the
// per-family READ/RW mode. Distinct from the catalogue-level "Tools Access Mode" (the
// MCP `mode`). Read strictly via getGrant/getAccessMode - absent grant ⇒ 'none' (the
// list is NEVER consulted, so a grant='all' family with an empty id list still shows
// "All", not "none"), absent mode ⇒ 'write' - matching the backend-authoritative defaults.

const GRANT_FAMILY_ICON: Record<GrantFamily, React.ComponentType<{ className?: string }>> = {
  workflows: Workflow,
  tables: Table,
  interfaces: Monitor,
  agents: Cpu,
  applications: FolderOpen,
};

export function GrantBadge({ grant, t }: { grant: ResourceGrant; t: ReturnType<typeof useTranslations> }) {
  const cls =
    grant === 'all'
      ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400'
      : grant === 'custom'
      ? 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400'
      : 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400';
  return <span className={`text-[10px] px-1.5 py-0.5 rounded font-medium ${cls}`}>{t(`grant_${grant}`)}</span>;
}

export function AgentFamilyAccessSection({
  agent,
  t,
}: {
  agent: { toolsConfig?: unknown };
  t: ReturnType<typeof useTranslations>;
}) {
  // One row per family. A family at grant='none' carries no read/write meaning, so
  // its R/W badge is suppressed (R/W only applies once access is granted).
  const rows = GRANT_FAMILIES.map(family => ({
    family,
    grant: getGrant(agent.toolsConfig, family),
    mode: getAccessMode(agent.toolsConfig, family),
  }));

  return (
    <div className="space-y-2">
      <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
        {t('resourceAccessTitle')}
      </Label>
      <div className="space-y-1">
        {rows.map(({ family, grant, mode }) => {
          const Icon = GRANT_FAMILY_ICON[family];
          return (
            <div key={family} className="flex items-center justify-between gap-2 rounded-lg bg-slate-50 dark:bg-slate-800/60 px-3 py-1.5">
              <span className="flex items-center gap-2 min-w-0 text-sm text-slate-700 dark:text-slate-300">
                <Icon className="h-3.5 w-3.5 flex-shrink-0 text-slate-400" />
                <span className="truncate">{t(`family_${family}`)}</span>
              </span>
              <span className="flex items-center gap-1.5 flex-shrink-0">
                <GrantBadge grant={grant} t={t} />
                {grant !== 'none' && (
                  <span
                    className={`text-[10px] px-1.5 py-0.5 rounded font-medium ${
                      mode === 'read'
                        ? 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400'
                        : 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400'
                    }`}
                  >
                    {mode === 'read' ? t('accessModeRead') : t('accessModeReadWrite')}
                  </span>
                )}
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
