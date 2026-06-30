'use client';

import { Mail, Calendar, FileText, MessageSquare, Bot } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { cn } from '@/lib/utils';
import type { LucideIcon } from 'lucide-react';

// Static set of 5 quick-start automations. Each chip shows a SHORT label
// (`<key>`) but pasting it into the composer fills the input with a richer
// starter prompt (`<key>Prompt`) so the LLM has enough context to draft a
// useful workflow without further user typing.
const SUGGESTIONS: ReadonlyArray<{ icon: LucideIcon; key: string }> = [
  { icon: Mail,          key: 'summarizeInbox' },
  { icon: Calendar,      key: 'dailyDigest' },
  { icon: FileText,      key: 'weeklyReport' },
  { icon: MessageSquare, key: 'slackToNotion' },
  { icon: Bot,           key: 'autoReplyLeads' },
];

export interface HomeSuggestionChipsProps {
  onPick: (prompt: string) => void;
  onInteract?: () => void;
  /** Reserved for parent - kept for prop compatibility; chips no longer rotate. */
  paused?: boolean;
  className?: string;
}

export function HomeSuggestionChips({
  onPick,
  onInteract,
  className,
}: HomeSuggestionChipsProps) {
  const t = useTranslations('chat.home.suggestions');

  return (
    <div
      className={cn(
        // Negative top margin pulls the chips up to sit just under the
        // composer's padding box, so they read as part of the same block.
        'flex flex-wrap items-center justify-center gap-2 px-2 -mt-2',
        className
      )}
    >
      {SUGGESTIONS.map(({ icon: Icon, key }) => {
        const label = t(key);
        const prompt = t(`${key}Prompt`);
        return (
          <button
            key={key}
            type="button"
            onClick={() => {
              onInteract?.();
              onPick(prompt);
            }}
            className={cn(
              'inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-sm transition-colors shrink-0',
              'bg-white/70 hover:bg-white text-gray-700 hover:text-gray-900',
              'dark:bg-gray-800/60 dark:hover:bg-gray-800 dark:text-gray-300 dark:hover:text-gray-100',
              'border border-gray-200/70 dark:border-gray-700/60 backdrop-blur-sm'
            )}
          >
            <Icon className="h-3.5 w-3.5 shrink-0" />
            <span>{label}</span>
          </button>
        );
      })}
    </div>
  );
}
