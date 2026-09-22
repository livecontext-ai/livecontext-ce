'use client';

import * as React from 'react';
import { PopoverContent } from '@/components/ui/popover';
import { useStudioLookClass } from '@/hooks/useStudioLook';

/**
 * A studio menu, drawn on the ground the studio is drawn on.
 *
 * <p><b>Why this exists at all.</b> A popover renders in a PORTAL on the
 * document, not inside the element that opened it. The studio's look is a set
 * of colour tokens redefined on the surface's own root, so a menu portalled out
 * of that root reads the APP's tokens instead: on the darkroom ground, every
 * menu opened from the composer came back in the application's own theme, a
 * bright panel over a dark page. Nothing errors, and the two are only ever seen
 * together for as long as the menu is open.
 *
 * <p>So the class travels through a context rather than through the DOM. Use
 * this instead of {@code PopoverContent} for anything opened from the studio;
 * a menu added later then follows the look without its author having to know
 * any of the above.
 *
 * <p>It adds nothing at all on the application's own look, which is what keeps
 * the treatment an offer rather than a redecoration.
 */
export const StudioPopoverContent = React.forwardRef<
  React.ElementRef<typeof PopoverContent>,
  React.ComponentPropsWithoutRef<typeof PopoverContent>
>(({ className, ...props }, ref) => {
  const look = useStudioLookClass();
  return (
    <PopoverContent
      ref={ref}
      // Prepended, and the order carries no meaning: `studio-darkroom` is not a Tailwind utility,
      // so twMerge (inside PopoverContent's own `cn`) has no group to match it against and will
      // never drop it, whatever a call site passes. What it does is redefine the colour TOKENS the
      // utilities read, so a call site's `bg-theme-secondary` keeps winning as a class and simply
      // resolves to the studio's value.
      className={look ? `${look} ${className ?? ''}`.trim() : className}
      {...props}
    />
  );
});
StudioPopoverContent.displayName = 'StudioPopoverContent';
