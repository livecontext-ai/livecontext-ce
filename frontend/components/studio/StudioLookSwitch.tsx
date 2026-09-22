'use client';

import * as React from 'react';
import { Aperture, PanelsTopLeft } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import type { StudioLook } from '@/hooks/useStudioLook';

/**
 * Which ground the studio draws itself on, as one control the reader can press.
 *
 * <p><b>Why a switch rather than a decision made for them.</b> The two looks
 * are not better and worse, they answer different rooms: the app's own theme
 * keeps the studio continuous with every other screen, and the darkroom gives
 * the asset a neutral surround at the cost of that continuity. Which one is
 * right depends on the screen and the light the reader is sitting in, neither
 * of which this code can see.
 *
 * <p>Icon-only on purpose. It sits in the composer row, which already folds its
 * own controls down to icons on a narrow screen, and a view preference must
 * never be the reason a control that SENDS something gets squeezed off the end.
 * The name travels in the tooltip and in the accessible label, which is where
 * an icon-only control is supposed to carry it.
 */
export function StudioLookSwitch({
  look, onChange,
}: {
  look: StudioLook;
  onChange: (next: StudioLook) => void;
}) {
  const t = useTranslations('studio');
  const next: StudioLook = look === 'darkroom' ? 'app' : 'darkroom';
  // The label names what pressing it DOES, not what is currently on: a toggle
  // labelled with its own state reads as a claim about the button.
  const label = t(next === 'darkroom' ? 'look.toDarkroom' : 'look.toApp');

  return (
    <Button
      type="button"
      variant="ghost"
      size="icon"
      // Never disabled, not even mid-generation: it changes how the page is DRAWN and touches
      // nothing that is in flight. A view preference a reader cannot reach while they wait is one
      // they cannot reach at the moment they most want it.
      onClick={() => onChange(next)}
      className="h-9 w-9 shrink-0"
      title={label}
      aria-label={label}
      // No `aria-pressed`. The name already changes with the state ("switch to
      // the darkroom view" / "switch back to the app view"), and a toggle that
      // carries BOTH is announced as "switch back to the app view, pressed",
      // which reads as a claim about the wrong half. One signal, the one that
      // says what pressing it does.
    >
      {look === 'darkroom'
        ? <Aperture className="h-4 w-4" />
        : <PanelsTopLeft className="h-4 w-4" />}
    </Button>
  );
}
