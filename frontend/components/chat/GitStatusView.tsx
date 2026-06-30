'use client';

/**
 * GitStatusView - renders a `repo(git_status)` result as a clean branch header
 * plus a list of changed files with colored porcelain-code badges (M/A/D/R/??/…),
 * instead of raw porcelain text. Data comes from tool metadata (`gitStatus`) -
 * see mcp/repo-tool.mjs parseGitStatus.
 */

import { useTranslations } from 'next-intl';
import { GitBranch, ArrowUp, ArrowDown } from 'lucide-react';
import type { GitStatusData } from '@/contexts/StreamingContext';

/** Classify a 1-2 char porcelain XY code into a label + Tailwind color. */
function classify(code: string): { label: string; cls: string } {
  const c = (code || '').trim();
  if (c === '??' || c.includes('?')) return { label: 'U', cls: 'text-slate-500 bg-slate-500/10' };       // untracked
  if (c.includes('U') || c === 'DD' || c === 'AA') return { label: '!', cls: 'text-red-600 dark:text-red-400 bg-red-500/10' }; // conflict
  if (c.includes('A')) return { label: 'A', cls: 'text-green-600 dark:text-green-400 bg-green-500/10' };  // added
  if (c.includes('D')) return { label: 'D', cls: 'text-red-600 dark:text-red-400 bg-red-500/10' };        // deleted
  if (c.includes('R')) return { label: 'R', cls: 'text-blue-600 dark:text-blue-400 bg-blue-500/10' };      // renamed
  if (c.includes('C')) return { label: 'C', cls: 'text-blue-600 dark:text-blue-400 bg-blue-500/10' };      // copied
  if (c.includes('M')) return { label: 'M', cls: 'text-amber-600 dark:text-amber-400 bg-amber-500/10' };   // modified
  return { label: c || '·', cls: 'text-slate-500 bg-slate-500/10' };
}

export default function GitStatusView({ status }: { status: GitStatusData }) {
  const t = useTranslations('chat.gitStatus');
  const files = status?.files || [];

  return (
    <div className="rounded-lg border border-slate-200 dark:border-slate-700 overflow-hidden text-sm">
      <div className="flex items-center gap-2 px-3 py-1.5 bg-slate-50 dark:bg-slate-800/60 flex-wrap">
        <GitBranch className="h-3.5 w-3.5 shrink-0 text-slate-500" />
        <span className="font-mono text-xs text-slate-700 dark:text-slate-200">{status?.branch || '-'}</span>
        {!!status?.ahead && (
          <span className="inline-flex items-center gap-0.5 text-xs text-green-600 dark:text-green-400" title={t('ahead', { count: status.ahead })}>
            <ArrowUp className="h-3 w-3" />{status.ahead}
          </span>
        )}
        {!!status?.behind && (
          <span className="inline-flex items-center gap-0.5 text-xs text-amber-600 dark:text-amber-400" title={t('behind', { count: status.behind })}>
            <ArrowDown className="h-3 w-3" />{status.behind}
          </span>
        )}
        <span className="ml-auto text-xs text-slate-400">{t('changes', { count: files.length })}</span>
      </div>

      {files.length === 0 ? (
        <div className="px-3 py-2 text-xs text-slate-400 italic">{t('clean')}</div>
      ) : (
        <div className="max-h-72 overflow-auto divide-y divide-slate-100 dark:divide-slate-800">
          {files.map((f, i) => {
            const { label, cls } = classify(f.status);
            return (
              <div key={`${f.path}-${i}`} className="flex items-center gap-2 px-3 py-1">
                <span className={`shrink-0 w-5 text-center rounded text-[10px] font-bold font-mono py-0.5 ${cls}`} title={f.status}>
                  {label}
                </span>
                <span className="font-mono text-xs truncate text-slate-700 dark:text-slate-300" title={f.path}>{f.path}</span>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
