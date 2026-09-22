'use client';

import * as React from 'react';
import { TooltipContent } from '@/components/ui/tooltip';
import { useStudioLookClass } from '@/hooks/useStudioLook';

/**
 * A tooltip, drawn on the ground the surface that opened it is drawn on.
 *
 * <p><b>The fourth portal type, and this one has no offender yet.</b> The other
 * three wrappers were each written after a bright panel was seen over a dark
 * page, and each called itself the last: `StudioSelectContent` says "the one
 * other place it happens", `StudioDialogContent` says "the third portal, and
 * the one the first two fixes missed". The pattern was that the list was always
 * one short of reality, so this closes the last Radix content type the design
 * system portals rather than waiting for the symptom.
 *
 * <p>Nothing in the studio tree renders a bare tooltip today, which is why this
 * ships with no call site: it exists so `StudioPortalAdoption.test.ts` has a
 * remedy to point at the moment one appears, instead of a guard that names a
 * fix that does not exist.
 *
 * <p>Inert off a studio surface, like its siblings: the hook reads a context
 * with no provider there, so the class is the empty string and this renders
 * exactly what `TooltipContent` renders.
 */
export const StudioTooltipContent = React.forwardRef<
  React.ElementRef<typeof TooltipContent>,
  React.ComponentPropsWithoutRef<typeof TooltipContent>
>(({ className, ...props }, ref) => {
  const look = useStudioLookClass();
  return (
    <TooltipContent
      ref={ref}
      // Prepended, and the order carries no meaning: `studio-darkroom` is not a
      // Tailwind utility, so twMerge has no group to match it against and will
      // never drop it. What it does is redefine the colour TOKENS the utilities
      // read, so a call site's own classes keep winning as classes.
      className={look ? `${look} ${className ?? ''}`.trim() : className}
      {...props}
    />
  );
});
StudioTooltipContent.displayName = 'StudioTooltipContent';
