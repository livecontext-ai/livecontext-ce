import React from 'react';
import { cn } from '@/lib/utils';

/**
 * What the run is doing, from the point of view of the person watching the app.
 *
 * <p>`awaiting` is NOT derivable from the run's own status: a parked run stays
 * `RUNNING` in the database and in every payload (the backend never writes
 * `AWAITING_SIGNAL` to the run row, only to a node), so reading `runStatus`
 * alone showed a busy, sweeping "Running" for the whole time the app was in
 * fact waiting for the user to answer. The caller decides this from the pending
 * signals instead - see `computeRunBlockers`.
 */
export type RunIndicatorState = 'running' | 'awaiting';

interface RunStateIndicatorProps {
  /** null renders nothing, so call-sites can mount this unconditionally. */
  state: RunIndicatorState | null;
  /**
   * The chip's text, which is ALSO the accessible name of the live region and
   * what a screen reader announces on a state change (e.g. "Running",
   * "Waiting for you"). There is no separate aria-label on purpose.
   */
  label: string;
  /** Extra classes for the absolute-fill overlay (e.g. a higher z-index). */
  className?: string;
}

/**
 * State indicator overlaid on a displayed application interface. Unlike a
 * loading skeleton, the app stays visible underneath - this only draws on top
 * of it, and never intercepts a click (`pointer-events: none`).
 *
 * <p>Three parts, and the split is the point. The first version was one pulsing
 * blue ring, reported as unreadable: "the border is blue but you cannot see it
 * is actually loading". A ring that only breathes in opacity is the gesture the
 * product uses for SELECTION, and nothing about it moves. So now:
 * <ul>
 *   <li>a STEADY ring marks the boundary once and never flickers;</li>
 *   <li>a bar SWEEPS the top edge - the ordinary web cue for indeterminate
 *       progress. Only `running` has it: an app waiting on a human must not
 *       look busy, which is the whole distinction the second state exists for;</li>
 *   <li>a chip WRITES the state, so it does not depend on reading a colour.</li>
 * </ul>
 *
 * <p>Colour follows the vocabulary the canvas already settled and that the
 * application surface was the last place not to speak: BLUE while the engine
 * executes, AMBER while it is blocked on a person.
 *
 * <p>Shapes and motion live in `.app-run-state*` (globals.css), which also
 * honours `prefers-reduced-motion` by keeping all three parts and dropping the
 * motion. Rendered once inside the shared `iframeContent` of
 * {@link ApplicationTabContent}, so every surface that shows an application
 * (right side panel, application detail, carousel, visualize card, fullscreen)
 * gets the same treatment.
 */
export function RunStateIndicator({ state, label, className }: RunStateIndicatorProps) {
  if (!state) return null;
  return (
    <div
      // role="status" already implies aria-live="polite". No `aria-label` and no
      // `title`: the label would have replaced the announced CONTENT, and every
      // child was aria-hidden, so the live region had nothing to announce and a
      // running -> waiting change was silent. (`title` was dead anyway: the
      // overlay is `pointer-events: none`, so it can never be hovered.) The
      // chip's own text is the accessible name AND what gets announced.
      role="status"
      data-testid="application-run-state"
      data-run-state={state}
      className={cn('app-run-state z-20', className)}
    >
      {/* The sweep is the "work is advancing" cue, so it belongs to `running`
          alone. Leaving it on while the app waits would say the opposite of
          what the amber ring says. */}
      {state === 'running' && <span className="app-run-state__sweep" aria-hidden="true" />}
      {/* Readable by assistive tech, because it is the only text in the live
          region: hiding it (as the first version did, on the theory that the
          wrapper's aria-label covered it) left the region empty and the state
          change unannounced. Only the decorative dots are hidden. */}
      <span className="app-run-state__chip" data-testid="application-run-state-chip">
        <span className="app-run-state__dots" aria-hidden="true">
          <span />
          <span />
          <span />
        </span>
        {label}
      </span>
    </div>
  );
}
