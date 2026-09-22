// @vitest-environment jsdom
/**
 * The positive half of the plan message: which model costs this account nothing.
 *
 * <p>Its sibling {@code UpgradeRequiredBadge} says which models the balance
 * cannot pay for, and on a Free account that lock lands on most of the menu. A
 * reader meeting only locks concludes that everything is locked, including the
 * model they were primed onto, which the allowance pays for in full. So the
 * covered rows say so.
 *
 * <p>The cases below pin what the chip must not do: render when it does not
 * apply (so a paid plan, CE and a bare test mount no hook and no translator),
 * dictate a paragraph as the accessible name of the control it sits inside, or
 * quote a figure before the live plan row has answered. The sentence it shows is
 * pinned against the real message files in the sibling `FreeTierBadge.tooltip`
 * suite, which opens the tooltip rather than mocking the translator.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// Keys, so an assertion can name the string it expects without depending on any
// wording. Values are appended so the two tooltip sentences stay tellable apart.
vi.mock('next-intl', () => ({
  useTranslations: () => (k: string, vals?: Record<string, unknown>) =>
    vals ? `${k}(${JSON.stringify(vals)})` : k,
  useLocale: () => 'en',
}));

const h = vi.hoisted(() => ({ credits: 100, resolved: true }));
vi.mock('@/lib/hooks/useFreeAiCredits', () => ({
  useFreeAiCreditsAnswer: () => ({ credits: h.credits, resolved: h.resolved }),
}));

import { ComposerFreeTierBadge, FreeTierBadge, quotableCredits } from '../FreeTierBadge';

afterEach(() => {
  h.credits = 100;
  h.resolved = true;
  cleanup();
});

describe('FreeTierBadge - the chip on a covered model', () => {
  it('marks a model the free-tier allowance pays for', () => {
    render(<FreeTierBadge covered />);

    expect(screen.getByTestId('free-tier-badge')).toHaveTextContent('label');
  });

  it('renders nothing at all when the model is not covered', () => {
    // Not merely invisible: the guard is OUTSIDE the body that calls
    // useTranslations, so a paid plan, CE and a bare unit test mount no hook.
    const { container } = render(<FreeTierBadge covered={false} />);

    expect(container).toBeEmptyDOMElement();
  });

  it('hides the SHORT label, not the sentence, because this joins a button name', () => {
    // On two surfaces this chip is rendered inside a <button> (the composer's
    // trigger, and the Radix Select trigger that re-renders the selected option),
    // so anything hidden here joins that button's accessible name - and would be
    // read out again on every covered row of an open menu. The sentence lives in
    // the tooltip; what stays here is the few words the lock beside it carries.
    render(<FreeTierBadge covered credits={100} />);

    // Asserted positively: the sentence's absence from a CLOSED tooltip is not
    // evidence of anything, so what this pins is that the hidden text is the short
    // key. The sentence itself is pinned in the sibling tooltip suite.
    expect(screen.getByTestId('free-tier-badge')).toHaveTextContent('srLabel');
  });

  it('is a label and not a control, because an option cannot hold one', () => {
    // Same constraint as the lock beside it: ARIA gives an option's children no
    // role of their own, a Radix listbox never reaches a tab stop inside it, and
    // the selected option is re-rendered inside the trigger <button>, where a
    // <div> would not even be valid.
    render(<FreeTierBadge covered />);

    const chip = screen.getByTestId('free-tier-badge');
    expect(chip.tagName).toBe('SPAN');
    expect(chip.querySelector('button')).toBeNull();
    expect(chip.getAttribute('role')).toBeNull();
  });
});

describe('quotableCredits - whether the figure is worth quoting', () => {
  it('quotes a live figure', () => {
    expect(quotableCredits({ credits: 250, resolved: true })).toBe(250);
  });

  it('withholds it until the live plan row has answered', () => {
    // Unresolved is either a request in flight or one that has exhausted its
    // retries, and both hand back the seeded stand-in - a figure this account's
    // plan may never grant. Quoting it on a failed fetch would leave a wrong
    // number on screen permanently, since nothing revisits it.
    expect(quotableCredits({ credits: 100, resolved: false })).toBeUndefined();
  });

  it('withholds it when the plan grants nothing', () => {
    // Belt and braces rather than a state to expect: a plan row granting nothing
    // leaves the allowance empty, so the verdict upstream marks no model free and
    // this chip is never mounted. If it ever is, the word Free beside "0 credits a
    // month" would be the worst of both.
    expect(quotableCredits({ credits: 0, resolved: true })).toBeUndefined();
  });
});

describe('ComposerFreeTierBadge - the one instance that quotes the figure', () => {
  it('always renders as covered, because the composer decides when to mount it', () => {
    // The catalogue and the verdict both live in ModelSelectorDropdown, which
    // mounts this node only when the SELECTED model is free right now. Re-asking
    // here would need the model, which this component does not have.
    h.credits = 250;
    render(<ComposerFreeTierBadge />);

    expect(screen.getByTestId('free-tier-badge')).toHaveTextContent('label');
  });

  it('still renders the chip when there is no figure to quote', () => {
    // The two are separate decisions: the model is free either way, and that is
    // the part the reader needs.
    h.resolved = false;
    render(<ComposerFreeTierBadge />);

    expect(screen.getByTestId('free-tier-badge')).toBeInTheDocument();
  });
});
