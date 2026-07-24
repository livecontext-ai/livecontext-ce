// @vitest-environment jsdom
/**
 * The CE budget field converts between the displayed unit (dollars) and the
 * stored unit (credits, 1 credit = $0.001). A unit slip here is a 1000x error,
 * so pin both directions: seed dollars from stored credits, and convert the
 * typed dollars back to credits on save.
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import * as React from 'react';

// Force the CE edition (dollar display) for this file.
vi.mock('@/lib/edition', () => ({ IS_CE: true, IS_CLOUD: false, EDITION: 'ce' }));
// next-intl: echo the key so labels/placeholders are queryable, no catalog needed.
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));

import { EditMetadataModal } from '../EditMetadataModal';

function numberInput(): HTMLInputElement {
  const el = document.querySelector('input[type="number"]');
  if (!el) throw new Error('budget input not found');
  return el as HTMLInputElement;
}

describe('EditMetadataModal - CE budget conversion', () => {
  it('seeds the field in dollars from stored credits (1000 credits -> $1)', () => {
    render(
      <EditMetadataModal
        resourceType="workflow"
        initialName="Wf"
        initialBudgetCredits={1000}
        onClose={() => {}}
        onSave={() => {}}
      />,
    );
    expect(numberInput().value).toBe('1');
  });

  it('seeds a fractional dollar cleanly (350 credits -> "0.35", no float artifact)', () => {
    // Regression for the IEEE-754 artifact: the pre-fix `credits * 0.001` seeded
    // this as "0.35000000000000003". The integer-factor conversion must not.
    render(
      <EditMetadataModal
        resourceType="workflow"
        initialName="Wf"
        initialBudgetCredits={350}
        onClose={() => {}}
        onSave={() => {}}
      />,
    );
    expect(numberInput().value).toBe('0.35');
  });

  it('converts typed dollars back to credits on save ($2 -> 2000 credits)', () => {
    const onSave = vi.fn();
    render(
      <EditMetadataModal
        resourceType="workflow"
        initialName="Wf"
        initialBudgetCredits={1000}
        onClose={() => {}}
        onSave={onSave}
      />,
    );
    fireEvent.change(numberInput(), { target: { value: '2' } });
    fireEvent.click(screen.getByText('save'));
    expect(onSave).toHaveBeenCalledTimes(1);
    const arg = onSave.mock.calls[0][0];
    expect(arg.budgetCredits).toBeCloseTo(2000, 3);
  });

  it('a blank budget clears it (null credits)', () => {
    const onSave = vi.fn();
    render(
      <EditMetadataModal
        resourceType="workflow"
        initialName="Wf"
        initialBudgetCredits={1000}
        onClose={() => {}}
        onSave={onSave}
      />,
    );
    fireEvent.change(numberInput(), { target: { value: '' } });
    fireEvent.click(screen.getByText('save'));
    expect(onSave.mock.calls[0][0].budgetCredits).toBeNull();
  });

  it('does not render the budget field for non-workflow resources', () => {
    render(
      <EditMetadataModal
        resourceType="datasource"
        initialName="Ds"
        onClose={() => {}}
        onSave={() => {}}
      />,
    );
    expect(document.querySelector('input[type="number"]')).toBeNull();
  });
});
