'use client';

/**
 * DiffView - renders a git-style unified diff inside a chat tool result as
 * red (deletions) / green (additions) lines, one collapsible card per file.
 *
 * Source of truth is the backend-computed unified-diff string carried in tool
 * metadata (`diff.files[].unifiedDiff`) - see mcp/repo-tool.mjs (repo edit/write/
 * diff) and the interface patch tool. Reused for both code edits and interface
 * patches. No diff library: we parse the unified format ourselves and style with
 * Tailwind so the look matches the surrounding chat cards (light + dark).
 */

import { useState } from 'react';
import { useTranslations } from 'next-intl';
import { ChevronDown, ChevronRight, FileText } from 'lucide-react';
import type { ToolDiffData, ToolDiffFile } from '@/contexts/StreamingContext';

type Row =
  | { type: 'hunk'; text: string }
  | { type: 'add'; text: string }
  | { type: 'del'; text: string }
  | { type: 'ctx'; text: string };

// Lines that are file-level metadata in a unified diff - not shown as content.
const META_LINE = /^(diff --git|index |--- |\+\+\+ |===|Index: |new file|deleted file|rename |similarity |old mode|new mode|GIT binary patch|Binary files)/;

/** Parse a unified-diff string into renderable rows (additions/deletions/context/hunks). */
export function parseUnifiedDiff(unified: string): Row[] {
  const rows: Row[] = [];
  for (const raw of (unified || '').split('\n')) {
    if (META_LINE.test(raw)) continue;
    if (raw.startsWith('\\')) continue; // "\ No newline at end of file"
    if (raw.startsWith('@@')) { rows.push({ type: 'hunk', text: raw }); continue; }
    if (raw.startsWith('+')) rows.push({ type: 'add', text: raw.slice(1) });
    else if (raw.startsWith('-')) rows.push({ type: 'del', text: raw.slice(1) });
    else rows.push({ type: 'ctx', text: raw.startsWith(' ') ? raw.slice(1) : raw });
  }
  while (rows.length && rows[rows.length - 1].type === 'ctx' && rows[rows.length - 1].text === '') rows.pop();
  return rows;
}

// Per-file status is conveyed by the +N/−N counts and (for renames) the "old → new"
// path; a single neutral file glyph keeps the header calm and the icon set portable.
const STATUS_DOT = {
  added: 'text-green-500',
  deleted: 'text-red-500',
  renamed: 'text-blue-500',
  modified: 'text-amber-500',
} as const;

function FileDiff({ file }: { file: ToolDiffFile }) {
  const t = useTranslations('chat.diff');
  const rows = parseUnifiedDiff(file.unifiedDiff);
  // Small diffs expanded by default; large ones collapsed so a turn with many
  // edits stays scannable (the header still shows +N/-N).
  const [open, setOpen] = useState(rows.length <= 60);

  return (
    <div className="rounded-lg border border-slate-200 dark:border-slate-700 overflow-hidden">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className="flex w-full items-center gap-2 px-3 py-1.5 text-sm bg-slate-50 dark:bg-slate-800/60 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
      >
        {open ? <ChevronDown className="h-3.5 w-3.5 shrink-0 text-slate-400" /> : <ChevronRight className="h-3.5 w-3.5 shrink-0 text-slate-400" />}
        <FileText className={`h-3.5 w-3.5 shrink-0 ${STATUS_DOT[file.status] || 'text-slate-500'}`} />
        <span className="font-mono text-xs truncate text-slate-700 dark:text-slate-200" title={file.oldPath ? `${file.oldPath} → ${file.path}` : file.path}>
          {file.oldPath && file.oldPath !== file.path ? `${file.oldPath} → ${file.path}` : file.path}
        </span>
        <span className="ml-auto flex items-center gap-2 shrink-0 font-mono text-xs">
          {file.additions > 0 && <span className="text-green-600 dark:text-green-400">+{file.additions}</span>}
          {file.deletions > 0 && <span className="text-red-600 dark:text-red-400">−{file.deletions}</span>}
        </span>
      </button>

      {open && (
        <div className="max-h-96 overflow-auto bg-white dark:bg-slate-900 font-mono text-xs leading-relaxed">
          {rows.map((row, i) => {
            if (row.type === 'hunk') {
              return (
                <div key={i} className="px-3 py-0.5 text-slate-400 dark:text-slate-500 bg-slate-500/5 select-none whitespace-pre-wrap break-all">
                  {row.text}
                </div>
              );
            }
            const cls =
              row.type === 'add'
                ? 'bg-green-500/10 text-green-700 dark:text-green-300'
                : row.type === 'del'
                ? 'bg-red-500/10 text-red-700 dark:text-red-300'
                : 'text-slate-600 dark:text-slate-300';
            const gutter = row.type === 'add' ? '+' : row.type === 'del' ? '-' : ' ';
            return (
              <div key={i} className={`flex ${cls}`}>
                <span className="w-4 shrink-0 select-none text-center opacity-60">{gutter}</span>
                <span className="whitespace-pre-wrap break-all pr-3">{row.text || ' '}</span>
              </div>
            );
          })}
          {file.truncated && (
            <div className="px-3 py-1 text-slate-400 italic select-none">{t('truncated')}</div>
          )}
        </div>
      )}
    </div>
  );
}

export default function DiffView({ diff }: { diff: ToolDiffData }) {
  const t = useTranslations('chat.diff');
  const files = diff?.files || [];
  if (files.length === 0) return null;
  return (
    <div className="space-y-2">
      {files.length > 1 && (
        <div className="text-xs text-slate-400">{t('files', { count: files.length })}</div>
      )}
      {files.map((f, i) => (
        <FileDiff key={`${f.path}-${i}`} file={f} />
      ))}
    </div>
  );
}
