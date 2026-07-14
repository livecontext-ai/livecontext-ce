// @vitest-environment jsdom
import * as React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Dialog, DialogContent, DialogTitle } from '../dialog';

function renderDialog(className?: string) {
  render(
    <Dialog open>
      <DialogContent className={className} data-testid="dialog-content">
        <DialogTitle>Test dialog</DialogTitle>
        <p>body</p>
      </DialogContent>
    </Dialog>
  );
  return screen.getByTestId('dialog-content');
}

describe('DialogContent width defaults', () => {
  // Regression (2026-07-11 modal bounding pass): the base classes carried BOTH
  // max-w-lg and sm:max-w-lg. twMerge treats the sm:-prefixed cap as a separate
  // group, so a caller's plain max-w-* override did not replace it and every
  // wide dialog (pricing, results, comparison views...) clamped to lg (512px)
  // on desktop. The width default must stay unprefixed only.
  it('lets a caller widen the dialog with a plain max-w-* override', () => {
    const el = renderDialog('max-w-3xl');
    expect(el.className).toContain('max-w-3xl');
    expect(el.className).not.toContain('max-w-lg');
  });

  it('does not carry a variant-prefixed width cap that would beat caller overrides', () => {
    const el = renderDialog('max-w-3xl');
    expect(el.className).not.toMatch(/(?:sm|md|lg|xl):max-w-/);
  });

  it('keeps max-w-lg as the default width when the caller passes none', () => {
    const el = renderDialog();
    expect(el.className).toContain('max-w-lg');
  });

  it('keeps the viewport bounding: max-h, scroll, and the small-screen gutter cap', () => {
    const el = renderDialog('max-w-3xl');
    expect(el.className).toContain('max-h-[90vh]');
    expect(el.className).toContain('overflow-y-auto');
    // The gutter cap uses an arbitrary max-[480px]: variant on purpose: it only
    // applies below any Tailwind breakpoint, so it can never fight a caller's
    // desktop width the way sm:max-w-lg did.
    expect(el.className).toContain('max-[480px]:max-w-[calc(100vw-1.5rem)]');
  });
});
