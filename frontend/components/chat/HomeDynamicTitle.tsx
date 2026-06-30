'use client';

import { useEffect, useState } from 'react';
import { useTranslations } from 'next-intl';
import { cn } from '@/lib/utils';
import { WelcomeTitle } from '@/app/shared/components';

const TITLE_KEYS = ['title1', 'title2', 'title3', 'title4', 'title5'] as const;
const ROTATE_MS = 9000;
const FADE_MS = 280;

export interface HomeDynamicTitleProps {
  /** When true, rotation stops on the currently displayed title. */
  paused?: boolean;
  className?: string;
}

export function HomeDynamicTitle({ paused = false, className }: HomeDynamicTitleProps) {
  const t = useTranslations('chat.home');
  const [idx, setIdx] = useState(0);
  const [visible, setVisible] = useState(true);

  useEffect(() => {
    if (paused) return;
    const swap = window.setInterval(() => {
      setVisible(false);
      window.setTimeout(() => {
        setIdx((i) => (i + 1) % TITLE_KEYS.length);
        setVisible(true);
      }, FADE_MS);
    }, ROTATE_MS);
    return () => window.clearInterval(swap);
  }, [paused]);

  return (
    <WelcomeTitle
      className={cn(
        'transition-opacity duration-300',
        visible ? 'opacity-100' : 'opacity-0',
        className
      )}
    >
      {t(TITLE_KEYS[idx])}
    </WelcomeTitle>
  );
}
