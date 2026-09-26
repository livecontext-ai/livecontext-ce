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
 * Toolbar control for whether the camera follows the work in progress.
 *
 * It belongs in the View-controls group beside Focus, because it answers the same
 * question: where should the camera be. Focus frames the whole graph once; this keeps
 * framing what is happening. In RUN mode that is the step running (or, when nothing
 * runs, the one waiting on the user); in EDIT mode it is the nodes an agent adds while
 * it builds. So it shows in both modes, with a label naming what it follows in each.
 *
 * It renders NOTHING on a read-only preview canvas, where nothing is ever built and
 * whose camera the hook deliberately never moves.
 *
 * The state is a REMEMBERED preference, ON by default: the moment you want to say
 * "keep the work in front of me" is usually before the run or the build starts, and
 * once it is going you have no hands free to arm it.
 *
 * Deliberately renders no wrapper of its own: it sits INSIDE the Focus group, so the
 * group's separator and spacing are already around it.
 */
export function CanvasRunFollowToggleButton() {
  const t = useTranslations('workflowBuilder.canvas');
  const { isEditMode, isPreviewOnly } = useWorkflowMode();
  // Starts from the default (on), so the control does not flash unpressed on mount.
  const [enabled, setEnabled] = React.useState(true);

  // Mount-time read, not render-time: the store reaches for localStorage, which does
  // not exist while server rendering.
  React.useEffect(() => {
    setEnabled(isRunCameraFollowEnabled());
    return subscribeRunCameraFollow(setEnabled);
  }, []);

  if (isPreviewOnly) return null;

  const label = isEditMode
    ? (enabled ? t('stopFollowingAgentBuild') : t('followAgentBuild'))
    : (enabled ? t('stopFollowingRunningNode') : t('followRunningNode'));

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
