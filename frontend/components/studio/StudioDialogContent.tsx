'use client';

import * as React from 'react';
import { DialogContent } from '@/components/ui/dialog';
import { useStudioLookClass } from '@/hooks/useStudioLook';

/**
 * A dialog, drawn on the ground the surface that opened it is drawn on.
 *
 * <p><b>The third portal, and the one the first two fixes missed.</b>
 * {@code StudioPopoverContent} covers popovers and {@code StudioSelectContent}
 * covers select lists, and that second file calls itself "the one other place
 * it happens". It was not: the credential wizard renders a Radix Dialog in a
 * portal of its own, reached from the studio's model picker by pressing "add my
 * own key". On the darkroom ground that opened a bright, application-themed
 * panel over a dark page, out of a menu that was correctly dark, which is the
 * exact symptom the other two wrappers exist to remove, one layer deeper. The
 * changeset went to some trouble to keep that dialog OPEN and none at all to
 * colour it.
 *
 * <p><b>Why this is safe in a component the studio does not own.</b> The hook
 * reads a context with no provider outside the studio, so the class is the empty
 * string everywhere else and this renders exactly what {@code DialogContent}
 * rendered before. That is what lets the wizard, which the workflow inspector
 * and the settings pages also mount, use it without knowing which surface it is
 * on.
 */
export const StudioDialogContent = React.forwardRef<
  React.ElementRef<typeof DialogContent>,
  React.ComponentPropsWithoutRef<typeof DialogContent>
>(({ className, ...props }, ref) => {
  const look = useStudioLookClass();
  return (
    <DialogContent
      ref={ref}
      // Prepended, and the order carries no meaning: `studio-darkroom` is not a
      // Tailwind utility, so twMerge has no group to match it against and will
      // never drop it. What it does is redefine the colour TOKENS the utilities
      // read, so the call site's own `bg-theme-primary` keeps winning as a class
      // and simply resolves to the studio's value.
      className={look ? `${look} ${className ?? ''}`.trim() : className}
      {...props}
    />
  );
});
StudioDialogContent.displayName = 'StudioDialogContent';
