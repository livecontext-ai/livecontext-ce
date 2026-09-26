'use client';

import { useTranslations } from 'next-intl';
import { InfoPopover } from '@/components/ui/info-popover';

/**
 * The examples are ids exactly as the run logs show them (StepDataRowMapper.coordinateId for a
 * step row, `<parent id>:<index>` for a list element inside an opened field), so they are code, not text.
 */
const EXAMPLES = [
  { id: '21', key: 'exampleEpoch' },
  { id: '20.1', key: 'exampleSpawn' },
  { id: '20.0.2', key: 'exampleIteration' },
  { id: '20.0.0.3', key: 'exampleItem' },
  { id: '21:3', key: 'exampleNested' },
] as const;

/**
 * The "i" next to the run-logs ID header: how to read an id like `20.0.2`.
 *
 * <p>Through `InfoPopover`, the app's one "i", rather than a hand-rolled button inside a Tooltip.
 * The two properties this used to implement itself are the ones it exists for: a click on the
 * header cell sorts the column, so the trigger has to stop the click, and a touch screen has no
 * hover, so the explanation has to open on a tap. It also gets what the hand-rolled version did
 * not have - Escape to close, a panel portalled above every surface it can be opened from, and the
 * same gesture as every other "i" in the product, which is the whole point of there being one.
 *
 * <p>`accessibleName` keeps the button named "How to read this ID" instead of the default
 * "About {label}": the phrase is already complete, and wrapping it again would read as "About How
 * to read this ID".
 */
export function WorkflowRowIdHint() {
  const t = useTranslations('dataTable.workflowRowId');

  return (
    <InfoPopover
      label={t('label')}
      accessibleName={t('label')}
      side="bottom"
      align="start"
      size="md"
      data-testid="workflow-row-id-hint"
      triggerClassName="flex-shrink-0"
      contentClassName="max-w-xs text-sm"
    >
      <p className="font-medium">{t('label')}</p>
      <p className="mt-1">{t('intro')}</p>
      <ul className="mt-2 space-y-1">
        {EXAMPLES.map(({ id, key }) => (
          <li key={id} className="flex gap-2">
            <code className="font-mono shrink-0">{id}</code>
            <span>{t(key)}</span>
          </li>
        ))}
      </ul>
      <p className="mt-2 text-theme-secondary">{t('sameId')}</p>
    </InfoPopover>
  );
}
