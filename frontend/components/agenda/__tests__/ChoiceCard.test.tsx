/**
 * @vitest-environment jsdom
 *
 * The one rule this card carries, which looks like styling and is not.
 *
 * <p>`disabled` on a ChoiceCard means the answer is REFUSABLE, not that the form is busy:
 * the move dialog disables "move every occurrence" when the cron cannot be rewritten, and
 * pre-selects whichever scope is actually available. Drawing a disabled card with the accent
 * border tells the user the dialog has settled on a choice its Confirm button will not take,
 * which is the one thing a two-way question must never say.
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import * as React from 'react';
import { Bot } from 'lucide-react';
import { ChoiceCard } from '../ChoiceCard';

function renderCard(props: { active: boolean; disabled?: boolean; onSelect?: () => void }) {
  render(
    <ChoiceCard
      active={props.active}
      disabled={props.disabled}
      icon={Bot}
      title="Agent"
      description="Runs an instruction on its own"
      onSelect={props.onSelect ?? (() => {})}
    />,
  );
  return screen.getByRole('button', { name: /Agent/ });
}

describe('ChoiceCard', () => {
  it('marks the chosen answer for a screen reader, not only with a border', () => {
    expect(renderCard({ active: true }).getAttribute('aria-pressed')).toBe('true');
  });

  it('does not mark the other one', () => {
    expect(renderCard({ active: false }).getAttribute('aria-pressed')).toBe('false');
  });

  it('draws the accent on the chosen answer', () => {
    expect(renderCard({ active: true }).className).toContain('border-[var(--accent-primary)]');
  });

  it('withholds the accent from a choice that cannot be taken, even when it is current', () => {
    // The move dialog's shape: a non-next fire of a cron nobody can rewrite leaves the
    // dialog sitting on a scope it has disabled. Accented, that reads as a settled answer.
    expect(renderCard({ active: true, disabled: true }).className)
      .not.toContain('border-[var(--accent-primary)]');
  });

  it('cannot be chosen while it is refusable', () => {
    const onSelect = vi.fn();
    fireEvent.click(renderCard({ active: false, disabled: true, onSelect }));
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('answers a click when it can be taken', () => {
    const onSelect = vi.fn();
    fireEvent.click(renderCard({ active: false, onSelect }));
    expect(onSelect).toHaveBeenCalledTimes(1);
  });
});
