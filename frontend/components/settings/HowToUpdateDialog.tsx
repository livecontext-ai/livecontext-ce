'use client';

import { useState } from 'react';
import { Check, Copy, ExternalLink } from 'lucide-react';
import { useTranslations } from 'next-intl';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from '@/components/ui/dialog';

const UPDATE_COMMANDS = 'docker compose pull\ndocker compose up -d';

/**
 * Self-hosted "How to update" instructions: copy-paste docker compose commands +
 * a link to the release notes. We intentionally do NOT auto-update the container
 * from the app (it cannot safely restart itself + run migrations); we show the
 * exact steps instead, matching n8n/Grafana.
 */
export default function HowToUpdateDialog({
  open,
  onOpenChange,
  releaseUrl,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  releaseUrl: string | null;
}) {
  const t = useTranslations('version');
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    try {
      await navigator.clipboard?.writeText(UPDATE_COMMANDS);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard unavailable (insecure context / denied): the commands are
      // still visible for manual copy, so this is a no-op.
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t('howToUpdate')}</DialogTitle>
          <DialogDescription>{t('updateIntro')}</DialogDescription>
        </DialogHeader>

        <div className="space-y-2">
          <p className="text-sm text-theme-secondary">{t('updateRunFrom')}</p>
          <div className="relative">
            <pre className="rounded-lg bg-theme-secondary p-3 pr-12 text-xs font-mono text-theme-primary overflow-x-auto whitespace-pre">
{UPDATE_COMMANDS}
            </pre>
            <button
              type="button"
              onClick={copy}
              className="absolute right-2 top-2 flex items-center gap-1 rounded-md px-2 py-1 text-xs text-theme-secondary hover:bg-theme-tertiary hover:text-theme-primary transition-colors cursor-pointer"
              title={t('copy')}
            >
              {copied ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
              {copied ? t('copied') : t('copy')}
            </button>
          </div>
        </div>

        {releaseUrl && (
          <a
            href={releaseUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1.5 text-sm text-theme-primary hover:underline"
          >
            <ExternalLink className="h-3.5 w-3.5" />
            {t('releaseNotes')}
          </a>
        )}
      </DialogContent>
    </Dialog>
  );
}
