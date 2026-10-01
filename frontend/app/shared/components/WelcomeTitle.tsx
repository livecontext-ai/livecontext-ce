'use client';

import { ReactNode } from 'react';
import { cn } from '@/lib/utils';

/**
 * The slot a ROTATING welcome title sits in, above a composer anchored under it (the chat home
 * and the empty studio, desktop layout).
 *
 * <p>The rotating titles do not all fit on one line: in English "Hi, I'm Orbi. How can I help?"
 * does and "What should Orbi automate for you today?" does not at the title's max-w-md, and several
 * translations are longer still. With the title in normal flow, every rotation that changed the
 * line count moved the composer under the reader's cursor by a line (32px), every nine seconds,
 * and put the chat and studio composers in different places depending on which title each was
 * showing. The slot has a fixed height of two title lines plus the title's bottom margin, and
 * anchors the title to its BOTTOM: one or two lines never move what is below, and a rare third
 * line grows upward into the free space above instead of pushing the composer down.
 *
 * <p>Both surfaces use this one constant so they cannot drift apart.
 */
export const ROTATING_TITLE_SLOT_CLASS = 'flex h-[4.5rem] flex-col justify-end';

export interface WelcomeTitleProps {
  children: ReactNode;
  className?: string;
}

export function WelcomeTitle({ 
  children, 
  className
}: WelcomeTitleProps) {
  return (
    <h2 className={cn(
      'text-2xl font-semibold text-theme-primary mb-2',
      className
    )}>
      {children}
    </h2>
  );
}

