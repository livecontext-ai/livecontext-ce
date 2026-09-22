'use client';

import React, { useCallback, useMemo, useState } from 'react';
import { CornerLeftUp, CornerRightDown, Network, Workflow } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { cn } from '@/lib/utils';
import { canvasChromeCompactButtonClass } from '@/components/ui/canvas-chrome';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { openWorkflowBuilderTab } from '@/lib/sidePanel/openWorkflowBuilderTab';
import type { WorkflowRelationRef, WorkflowRelations } from '@/lib/api/orchestrator/types';

export interface WorkflowRelationsMenuProps {
  /**
   * The resolved relations. Always passed in - this component never fetches, which is what keeps
   * it usable inside a card grid that resolved its whole page in ONE batch call, and free of any
   * data-layer provider. The single-workflow surfaces go through
   * {@link WorkflowRelationsAutoMenu}, which resolves them and renders this.
   *
   * Undefined reads as "not resolved yet" and renders nothing, same as no relations.
   */
  relations: WorkflowRelations | undefined;
  /**
   * How a picked workflow is opened. Defaults to opening its builder in the right side panel,
   * which is what a card grid wants. Inside a workflow view, pass a handler that asks the view to
   * resolve the pinned RUN first (see `requestOpenRelatedWorkflow`).
   */
  onOpen?: (ref: WorkflowRelationRef) => void;
  /** `card` sits in a card footer; `toolbar` sits in the canvas chrome. */
  variant?: 'card' | 'toolbar';
  /** Extra classes for the trigger button. */
  className?: string;
  /** Which side the popover opens on. Card footers open upward, toolbars too by default. */
  side?: 'top' | 'bottom' | 'left' | 'right';
  align?: 'start' | 'center' | 'end';
  'data-testid'?: string;
}

/**
 * "Who calls this workflow, and who does it call?" - the sub-workflow neighbourhood as a menu, with
 * every entry opening that workflow.
 *
 * <p>The trigger is a COUNT, not a label: a workflow with no sub-workflow relation renders nothing
 * at all rather than a button onto an empty list. That is what makes the button itself the
 * indicator the card grids needed - its presence is the mention.
 *
 * <p>Not offered on the marketplace or in a publication preview: those show a snapshot of someone
 * else's plan, whose neighbours are not workflows the viewer has, so every row would be a dead one.
 * The gate lives at the CALL SITE (the component is simply not rendered there) rather than in a
 * prop, because a preview must not even ask.
 */
export function WorkflowRelationsMenu({
  relations,
  onOpen,
  variant = 'card',
  className,
  side = 'top',
  align = 'end',
  'data-testid': testId,
}: WorkflowRelationsMenuProps) {
  const t = useTranslations('workflowRelations');
  const sidePanel = useSidePanelSafe();
  const [open, setOpen] = useState(false);

  const total = (relations?.parents.length ?? 0) + (relations?.children.length ?? 0);

  const handleOpen = useCallback((ref: WorkflowRelationRef) => {
    if (!ref.resolved) return;
    setOpen(false);
    if (onOpen) {
      onOpen(ref);
      return;
    }
    openWorkflowBuilderTab(sidePanel, { workflowId: ref.id, workflowName: ref.name });
  }, [onOpen, sidePanel]);

  // A square icon control in both places, the shape every other button in the app takes - not a
  // chip. In the canvas chrome that means the chrome helper UNMODIFIED, which is what keeps it on
  // the 28px a control nested in a chrome card has to be for the card to land on its 36px; in a
  // card footer, the same flat square at the same size.
  //
  // The count is deliberately NOT painted on the button. It reads as a notification badge, which
  // this is not: there is nothing unread here, and the number changes nothing about what the
  // control does. It stays in the accessible name and the tooltip, so it is still one hover away.
  const triggerClass = useMemo(() => (
    variant === 'toolbar'
      ? canvasChromeCompactButtonClass(open, className)
      : cn(
        'inline-flex items-center justify-center h-7 w-7 rounded-xl',
        // Arbitrary values, not `hover:bg-theme-secondary`: the *-theme-* classes are
        // hand-written CSS, so Tailwind v4 emits the base class and no variant of it -
        // this button had no hover feedback at all. Same ground as the info control it
        // now sits next to.
        'text-theme-secondary transition-colors hover:bg-[var(--bg-secondary)] hover:text-[var(--text-primary)]',
        className,
      )
  ), [variant, className, open]);

  // No neighbour, no affordance. Also covers the not-yet-resolved case: the button appears once
  // the answer is in, which is preferable to a control that pops out from under the cursor.
  if (!relations || total === 0) return null;

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          data-testid={testId ?? 'workflow-relations-trigger'}
          aria-label={t('menuLabel', { count: total })}
          title={t('menuLabel', { count: total })}
          className={triggerClass}
          // Card footers live inside a clickable card: opening the menu must not also open the card.
          onClick={(e) => e.stopPropagation()}
          onMouseDown={(e) => e.stopPropagation()}
        >
          {/* Sized to its neighbours, not to itself: h-4 next to the toolbar's other icons,
              h-3.5 in a card footer where the surrounding text is text-sm. */}
          <Network className={cn('shrink-0', variant === 'toolbar' ? 'h-4 w-4' : 'h-3.5 w-3.5')} />
        </button>
      </PopoverTrigger>
      <PopoverContent
        side={side}
        align={align}
        sideOffset={6}
        onClick={(e) => e.stopPropagation()}
        className="w-[280px] max-h-[320px] overflow-y-auto p-1.5 bg-theme-primary rounded-xl border border-gray-300/70 dark:border-gray-600/70 z-[100000]"
        data-testid="workflow-relations-menu"
      >
        <RelationSection
          title={t('parentsTitle')}
          hint={t('parentsHint')}
          icon={<CornerLeftUp className="h-3 w-3 shrink-0" />}
          refs={relations.parents}
          onOpen={handleOpen}
          testId="workflow-relations-parents"
        />
        {relations.parents.length > 0 && relations.children.length > 0 && (
          <div className="my-1 border-t border-gray-200 dark:border-gray-700" />
        )}
        <RelationSection
          title={t('childrenTitle')}
          hint={t('childrenHint')}
          icon={<CornerRightDown className="h-3 w-3 shrink-0" />}
          refs={relations.children}
          onOpen={handleOpen}
          testId="workflow-relations-children"
        />
      </PopoverContent>
    </Popover>
  );
}

function RelationSection({
  title,
  hint,
  icon,
  refs,
  onOpen,
  testId,
}: {
  title: string;
  hint: string;
  icon: React.ReactNode;
  refs: WorkflowRelationRef[];
  onOpen: (ref: WorkflowRelationRef) => void;
  testId: string;
}) {
  const t = useTranslations('workflowRelations');
  if (refs.length === 0) return null;
  return (
    <div data-testid={testId}>
      <div
        className="flex items-center gap-1.5 px-2.5 pt-1 pb-1 text-xs font-medium text-theme-muted"
        title={hint}
      >
        {icon}
        <span>{title}</span>
        <span className="ml-auto tabular-nums">{refs.length}</span>
      </div>
      {refs.map((ref) => (
        <button
          key={ref.id}
          type="button"
          disabled={!ref.resolved}
          onClick={() => onOpen(ref)}
          title={ref.resolved ? t('openHint') : t('unavailableHint')}
          className={cn(
            'w-full flex items-center gap-2 px-2.5 py-1.5 rounded-lg text-sm text-left transition-colors',
            ref.resolved
              ? 'text-theme-primary hover:bg-gray-100 dark:hover:bg-gray-700'
              : 'text-theme-muted cursor-not-allowed',
          )}
        >
          <Workflow className="h-3.5 w-3.5 shrink-0" />
          <span className="truncate">{ref.resolved ? ref.name : t('unavailable')}</span>
        </button>
      ))}
    </div>
  );
}
