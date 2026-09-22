'use client';

import clsx from 'clsx';
import { Sparkle } from 'lucide-react';
import { NodeIcon } from '@/app/workflows/builder/components/nodes/shared';
import {
  AGENT_ONLY_KIND_ICON,
  isAgentOnlyKind,
  kindNodeIconId,
  type AgendaKind,
} from './agendaLaunchKinds';

interface AgendaKindIconProps {
  kind: AgendaKind;
  /**
   * Extra classes for the OUTER box of either branch, not a size.
   *
   * <p>Sizing is deliberately not a prop, and a size passed here would be INERT:
   * `NodeIcon` draws a 24px tile with a 14px glyph inside it and takes its scale from its
   * own `size`, applying `className` to the container, where `h-6 w-6` is already
   * present and wins on emission order. An earlier version claimed both branches honoured
   * a passed size - they did not, and the two sat at different sizes in one filter row.
   * Both now match NodeIcon's xs footprint exactly, so the row is even whatever the kind
   * is, and the three call sites that used to pass a size no longer pretend to.
   */
  className?: string;
}

/** NodeIcon's `xs` geometry, mirrored so the two branches are the same size. */
const XS_BOX = 'flex h-6 w-6 shrink-0 items-center justify-center';
const XS_GLYPH = 'h-3.5 w-3.5';

/**
 * One glyph for one launch kind, whichever vocabulary the kind came from.
 *
 * <p>The eight trigger kinds are drawn with the SAME `NodeIcon` the builder puts on the
 * trigger node, so a chat trigger looks identical on the canvas, in the notification bell
 * and on this calendar. The three agent-only kinds have no node to borrow from and use a
 * lucide glyph instead.
 *
 * <p>A component rather than a branch repeated in three places: the chip, the filter list
 * and the occurrence menu all draw this, and the first version of the chip silently
 * rendered `NodeIcon` with an undefined id for any kind outside the eight.
 */
export function AgendaKindIcon({ kind, className }: AgendaKindIconProps) {
  const nodeId = isAgentOnlyKind(kind) ? undefined : kindNodeIconId(kind);
  if (nodeId) return <NodeIcon nodeId={nodeId} size="xs" className={className} />;

  // Either an agent-only kind, or a launch kind the backend knows and this build does
  // not. The second case used to render nothing at all, which is the same "blank square
  // that says nothing" this component exists to remove, reached from the other
  // direction: a backend deployed ahead of the frontend.
  const Icon = isAgentOnlyKind(kind) ? AGENT_ONLY_KIND_ICON[kind] : Sparkle;
  return (
    <span className={clsx(XS_BOX, className)} aria-hidden="true">
      <Icon className={XS_GLYPH} />
    </span>
  );
}
