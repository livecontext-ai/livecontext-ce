/**
 * @vitest-environment jsdom
 *
 * The overlay a displayed application carries while its run is executing or is
 * parked on a human. The app stays visible; the overlay only renders in those
 * two states, so call-sites can mount it unconditionally.
 *
 * The parts are asserted SEPARATELY on purpose. The first version was a single
 * pulsing ring, reported as unreadable as progress; the moving sweep and the
 * written chip are what fixed that. And the sweep belongs to ONE of the two
 * states: an app waiting on a person must not look busy, which is the entire
 * reason the second state exists.
 */
import React from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { RunStateIndicator } from '../RunStateIndicator';

afterEach(cleanup);

describe('RunStateIndicator', () => {
  it('renders nothing when the run is in neither state', () => {
    const { container } = render(<RunStateIndicator state={null} label="Running" />);
    expect(container.firstChild).toBeNull();
  });

  it('renders an accessible overlay while running', () => {
    render(<RunStateIndicator state="running" label="Running" />);
    // Queried WITHOUT a name: `status` is not a name-from-content role, so the
    // region has none - and needs none. A live region announces what its
    // CONTENT becomes, which is the assertion below.
    const status = screen.getByRole('status');
    expect(status.className).toContain('app-run-state');
    expect(status.dataset.runState).toBe('running');
    expect(status.textContent).toContain('Running');
  });

  it('leaves text INSIDE the live region, or it announces nothing', () => {
    // The first version put `aria-label` on the region and `aria-hidden` on
    // every child. A live region announces the text of what changed, so that
    // arrangement was silent: attribute present, behaviour absent. `role=status`
    // already implies aria-live=polite, so the text is the whole contract.
    render(<RunStateIndicator state="awaiting" label="Waiting for you" />);
    const status = screen.getByRole('status');
    expect(status.getAttribute('aria-hidden')).toBeNull();
    expect(status.textContent).toContain('Waiting for you');

    const chip = screen.getByTestId('application-run-state-chip');
    expect(chip.getAttribute('aria-hidden'), 'hiding the chip empties the region').toBeNull();
    // Only the decorative dots are hidden.
    expect(status.querySelector('.app-run-state__dots')?.getAttribute('aria-hidden')).toBe('true');
  });

  it('moves ONLY while running - the sweep is the "work is advancing" cue', () => {
    render(<RunStateIndicator state="running" label="Running" />);
    const sweep = screen.getByRole('status').querySelector('.app-run-state__sweep');
    expect(sweep, 'a steady ring alone failed to convey progress').toBeTruthy();
    // Decorative: the chip's text is what the region announces.
    expect(sweep?.getAttribute('aria-hidden')).toBe('true');
  });

  it('drops the sweep while waiting, so a parked app does not look busy', () => {
    render(<RunStateIndicator state="awaiting" label="Waiting for you" />);
    const status = screen.getByRole('status');
    expect(status.dataset.runState).toBe('awaiting');
    expect(
      status.querySelector('.app-run-state__sweep'),
      'motion here would say the opposite of what the amber ring says',
    ).toBeNull();
  });

  it('writes the state in a chip, so it does not depend on reading a colour', () => {
    render(<RunStateIndicator state="awaiting" label="Waiting for you" />);
    const chip = screen.getByTestId('application-run-state-chip');
    expect(chip.textContent).toContain('Waiting for you');
    expect(chip.querySelectorAll('.app-run-state__dots > span').length).toBe(3);
  });

  it('carries no title, which a pointer-events:none overlay can never show', () => {
    render(<RunStateIndicator state="running" label="Running" />);
    expect(screen.getByRole('status').getAttribute('title')).toBeNull();
  });

  it('applies the caller className (e.g. a higher z-index)', () => {
    render(<RunStateIndicator state="running" label="Running" className="z-[10000]" />);
    expect(screen.getByRole('status').className).toContain('z-[10000]');
  });
});
