/**
 * Switch Component - Composant de commutateur pour les contrôles
 */

'use client';

import React from 'react';
import { cn } from '../../lib/utils';

interface SwitchProps {
  checked: boolean;
  onCheckedChange: (checked: boolean) => void;
  disabled?: boolean;
  className?: string;
  /** Accessible label announced by screen readers (e.g. "Activate application"). */
  'aria-label'?: string;
  /**
   * Visual size of the toggle. {@code 'sm'} (default) - h-6 w-11; {@code 'md'} -
   * h-8 w-14 with a larger handle, matches the size of standard h-8 buttons in
   * the chat header so the toggle reads as a peer control next to them.
   */
  size?: 'sm' | 'md';
}

const SIZE_CLASSES: Record<NonNullable<SwitchProps['size']>, {
  container: string;
  handle: string;
  translateOn: string;
  translateOff: string;
}> = {
  sm: {
    container: 'h-6 w-11',
    handle: 'h-4 w-4',
    translateOn: 'translate-x-6',
    translateOff: 'translate-x-1',
  },
  md: {
    container: 'h-8 w-14',
    handle: 'h-6 w-6',
    translateOn: 'translate-x-7',
    translateOff: 'translate-x-1',
  },
};

export const Switch: React.FC<SwitchProps> = ({
  checked,
  onCheckedChange,
  disabled = false,
  className,
  'aria-label': ariaLabel,
  size = 'sm',
}) => {
  const dims = SIZE_CLASSES[size];
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={ariaLabel}
      disabled={disabled}
      onClick={() => onCheckedChange(!checked)}
              className={cn(
                'relative inline-flex items-center rounded-full border border-theme transition-colors duration-200 focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)] focus:ring-offset-2',
                dims.container,
                checked
                  ? 'bg-[var(--accent-primary)]'
                  : 'bg-[var(--border-primary)]',
                disabled && 'cursor-not-allowed opacity-50',
                className
              )}
    >
      <span
                className={cn(
                  'inline-block transform rounded-full border border-theme transition-transform duration-200 shadow-sm',
                  dims.handle,
                  checked
                    ? `${dims.translateOn} bg-[var(--accent-foreground)]`
                    : `${dims.translateOff} bg-[var(--bg-primary)]`
                )}
      />
    </button>
  );
};
