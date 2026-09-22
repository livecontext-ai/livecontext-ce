'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Crosshair } from 'lucide-react';
import { canvasChromeCompactButtonClass } from '@/components/ui/canvas-chrome';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import {
  isRunCameraFollowEnabled,
  setRunCameraFollowEnabled,
  subscribeRunCameraFollow,
} from '@/app/workflows/builder/services/runCameraFollowStore';

/**
 * Toolbar control for whether the camera keeps the running step in frame.
 *
 * It belongs in the View-controls group beside Focus, because it answers the same
 * question: where should the camera be. Focus frames the whole graph once; this keeps
 * framing whatever is running.
 *
 * It renders NOTHING in edit mode, nor on a read-only preview canvas. No step can be
 * running in either, so the control would be permanently inert and would describe a
 * state you cannot reach. That is the same rule the file-strip toggle follows, and for
 * the same reason.
 *
 * The state is a REMEMBERED preference: the moment you want to say "keep the running
 * node in front of me" is usually before the run starts, and once it is going you have
 * no hands free to arm it.
 *
 * Deliberately renders no wrapper of its own: it sits INSIDE the Focus group, so the
 * group's separator and spacing are already around it.
 */
export function CanvasRunFollowToggleButton() {
  const t = useTranslations('workflowBuilder.canvas');
  const { isEditMode, isPreviewOnly } = useWorkflowMode();
  const [enabled, setEnabled] = React.useState(false);

  // Mount-time read, not render-time: the store reaches for localStorage, which does
  // not exist while server rendering.
  React.useEffect(() => {
    setEnabled(isRunCameraFollowEnabled());
    return subscribeRunCameraFollow(setEnabled);
  }, []);

  if (isEditMode || isPreviewOnly) return null;

  const label = enabled ? t('stopFollowingRunningNode') : t('followRunningNode');

  return (
    <button
      type="button"
      data-testid="canvas-toggle-run-follow"
      onClick={() => setRunCameraFollowEnabled(!enabled)}
      // Icon-only control: the accessible name carries the ACTION, aria-pressed the
      // state, exactly as the sibling controls do.
      aria-label={label}
      aria-pressed={enabled}
      className={canvasChromeCompactButtonClass(enabled)}
      title={label}
    >
      <Crosshair className="h-4 w-4" aria-hidden="true" />
    </button>
  );
}
