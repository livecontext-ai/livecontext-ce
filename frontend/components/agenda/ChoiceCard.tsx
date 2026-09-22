'use client';

import type { ComponentType } from 'react';

/**
 * One of two mutually exclusive answers, drawn as a card with its own explanation.
 *
 * <p>Shared by the agenda's dialogs because they ask the same SHAPE of question - move
 * this occurrence or every occurrence, schedule a workflow or an agent - and a choice
 * that looks different from one dialog to the next reads as a different kind of control.
 * It carries no logic: which card is active, and whether either is refusable, stays the
 * caller's business.
 *
 * <p>`aria-pressed` rather than a radio group: these are buttons that set a mode, the
 * dialogs already label them by their own text, and a screen reader is told the state of
 * each rather than having to infer it from a group it cannot see.
 */
export function ChoiceCard({
  active,
  disabled,
  icon: Icon,
  title,
  description,
  onSelect,
}: {
  active: boolean;
  disabled?: boolean;
  icon: ComponentType<{ className?: string }>;
  title: string;
  description: string;
  onSelect: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      disabled={disabled}
      aria-pressed={active}
      // `active && !disabled`, deliberately: `disabled` here means the answer is REFUSABLE,
      // not merely that the form is busy, and drawing an impossible choice with the accent
      // border says the dialog has settled on it. A caller that disables its cards for some
      // other reason gets a selected card drawn plain for that moment, which is the safer
      // of the two wrong readings.
      className={`flex w-full items-start gap-3 rounded-xl border p-3 text-left transition-colors
                  ${active && !disabled
                    ? 'border-[var(--accent-primary)] bg-theme-secondary'
                    : 'border-theme hover:bg-theme-secondary'}
                  ${disabled ? 'cursor-not-allowed opacity-60' : ''}`}
    >
      <Icon className="mt-0.5 h-3.5 w-3.5 shrink-0 text-theme-secondary" />
      <span className="min-w-0">
        <span className="block text-sm text-theme-primary">{title}</span>
        <span className="block text-xs text-theme-muted">{description}</span>
      </span>
    </button>
  );
}
