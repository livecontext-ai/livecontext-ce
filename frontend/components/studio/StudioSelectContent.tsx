'use client';

import * as React from 'react';
import { SelectContent } from '@/components/ui/select';
import { useStudioLookClass } from '@/hooks/useStudioLook';

/**
 * A dropdown list, drawn on the ground the surface that opened it is drawn on.
 *
 * <p><b>The same portal problem {@code StudioPopoverContent} solves, in the one
 * other place it happens.</b> A Radix select list renders in a PORTAL on the
 * document, not inside the element that opened it. The studio's look is a set
 * of colour tokens redefined on the surface's own root, so a list portalled out
 * of that root reads the APPLICATION's tokens: on the darkroom ground, opening
 * "which of my keys" inside the studio's model picker produced a bright panel
 * over a dark page, inside a menu that was correctly dark. Nothing errors, and
 * the two are only ever seen together while the list is open.
 *
 * <p><b>Why this is safe in components the studio does not own.</b> The hook
 * reads a context with no provider outside the studio, so the class is the
 * empty string everywhere else and this renders exactly what
 * {@code SelectContent} rendered before. That is what lets a shared component
 * like the credential section use it without knowing whether it is on a studio
 * surface or in the workflow inspector, which is the only way it could be
 * correct in both.
 */
export const StudioSelectContent = React.forwardRef<
  React.ElementRef<typeof SelectContent>,
  React.ComponentPropsWithoutRef<typeof SelectContent>
>(({ className, ...props }, ref) => {
  const look = useStudioLookClass();
  return (
    <SelectContent
      ref={ref}
      // Prepended, and the order carries no meaning: `studio-darkroom` is not a
      // Tailwind utility, so twMerge has no group to match it against and will
      // never drop it. What it does is redefine the colour TOKENS the utilities
      // read, so a call site's own classes keep winning as classes and simply
      // resolve to the studio's values.
      className={look ? `${look} ${className ?? ''}`.trim() : className}
      {...props}
    />
  );
});
StudioSelectContent.displayName = 'StudioSelectContent';
