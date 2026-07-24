'use client';

import { LayoutTemplate } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { useCallback, useState } from 'react';

import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { getTemplates } from '@/lib/templates';
import { hydrateAgent, templateCopy } from '@/lib/templates/hydrate';
import {
  instantiateTableTemplate,
  instantiateWorkflowTemplate,
  uniqueName,
} from '@/lib/templates/instantiate';
import type {
  AgentTemplate,
  TableTemplate,
  TemplateKind,
  TemplateMeta,
  WorkflowTemplate,
} from '@/lib/templates/types';

import { TemplateCard } from './TemplateCard';

type PrefilledAgent = ReturnType<typeof hydrateAgent>;

interface TemplateGalleryProps {
  kind: TemplateKind;
  /**
   * False for a VIEWER in an org workspace. The trigger hides entirely rather
   * than offering templates that would fail on click.
   */
  canMutate: boolean;
  /** Names already on screen, used to suffix a duplicate instead of creating a homonym. */
  existingNames?: string[];
  /** kind='workflow': the workflow was created, here is its id. */
  onWorkflowCreated?: (workflowId: string) => void;
  /** kind='agent': open the create modal prefilled with this agent. Nothing is created yet. */
  onAgentTemplateSelected?: (prefilled: PrefilledAgent) => void;
  /** kind='table': the table was created, with the columns that could not be added. */
  onTableCreated?: (dataSourceId: string, skippedColumns: string[]) => void;
  onError?: (message: string) => void;
  className?: string;
}

/**
 * Templates live behind a button next to "Create", not in a permanent banner:
 * they are a starting point, and an experienced user should not have to scroll
 * past them on every visit to their own list.
 */
export function TemplateGallery({
  kind,
  canMutate,
  existingNames = [],
  onWorkflowCreated,
  onAgentTemplateSelected,
  onTableCreated,
  onError,
  className = '',
}: TemplateGalleryProps) {
  const t = useTranslations();
  const [open, setOpen] = useState(false);
  const [busySlug, setBusySlug] = useState<string | null>(null);

  const entries = getTemplates(kind);

  const handleSelect = useCallback(
    async (meta: TemplateMeta) => {
      if (busySlug) return;
      setBusySlug(meta.slug);
      try {
        const entry = entries.find((e) => e.meta.slug === meta.slug);
        if (!entry) return;
        const template = await entry.load();
        const name = uniqueName(templateCopy(meta, t).title, existingNames);

        if (template.kind === 'agent') {
          // Nothing is created here on purpose: the create modal IS the preview
          // for an agent, and a beginner should see the configuration first.
          setOpen(false);
          onAgentTemplateSelected?.(hydrateAgent(template as AgentTemplate, t, name));
          return;
        }

        if (template.kind === 'table') {
          const { dataSourceId, skippedColumns } = await instantiateTableTemplate(
            template as TableTemplate,
            t,
            name,
          );
          setOpen(false);
          onTableCreated?.(dataSourceId, skippedColumns);
          return;
        }

        const workflowId = await instantiateWorkflowTemplate(template as WorkflowTemplate, t, name);
        setOpen(false);
        onWorkflowCreated?.(workflowId);
      } catch (err) {
        const message = err instanceof Error ? err.message : t('templates.gallery.createFailed');
        onError?.(message);
      } finally {
        setBusySlug(null);
      }
    },
    [
      busySlug,
      entries,
      existingNames,
      onAgentTemplateSelected,
      onTableCreated,
      onWorkflowCreated,
      onError,
      t,
    ],
  );

  if (!canMutate || entries.length === 0) return null;

  const title = `templates.gallery.${kind}Title`;
  const subtitle = `templates.gallery.${kind}Subtitle`;

  return (
    <>
      <Button
        variant="outline"
        size="sm"
        onClick={() => setOpen(true)}
        className={className}
      >
        <LayoutTemplate className="mr-1.5 h-4 w-4" />
        {t('templates.gallery.browse')}
      </Button>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-h-[85vh] w-[min(96vw,1100px)] max-w-none overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{t(title)}</DialogTitle>
            <DialogDescription>{t(subtitle)}</DialogDescription>
          </DialogHeader>

          <div className="mt-2 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {entries.map((entry) => (
              <TemplateCard
                key={entry.meta.slug}
                meta={entry.meta}
                busy={busySlug === entry.meta.slug}
                disabled={busySlug !== null && busySlug !== entry.meta.slug}
                onSelect={handleSelect}
              />
            ))}
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}

export default TemplateGallery;
