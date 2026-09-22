// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import * as React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Dialog, DialogContent, DialogTitle } from '../dialog';

/**
 * The corner close button's accessible name.
 *
 * <p>It was the string `"Close"`, written into this component, so every dialog
 * in the app announced an English word to a French, German, Spanish, Portuguese
 * or Chinese screen-reader user - on a control whose only other content is an
 * X. `closeLabel` lets a caller hand over its own translation.
 *
 * <p>The default is pinned as hard as the override. Roughly forty dialogs pass
 * nothing, and a default that drifted (to empty, or to `undefined`) would strip
 * the name from the one button on the surface that has no visible label, in a
 * single edit nobody would see in a diff of this file.
 */
function renderDialog(closeLabel?: string) {
  render(
    <Dialog open>
      <DialogContent closeLabel={closeLabel} data-testid="dialog-content">
        <DialogTitle>Test dialog</DialogTitle>
        <p>body</p>
      </DialogContent>
    </Dialog>
  );
}

describe('DialogContent close button naming', () => {
  it('names the close button in the language the caller passes', () => {
    renderDialog('Fermer');

    expect(screen.getByRole('button', { name: 'Fermer' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Close' })).not.toBeInTheDocument();
  });

  it('keeps "Close" for the callers that pass nothing', () => {
    renderDialog();

    expect(screen.getByRole('button', { name: 'Close' })).toBeInTheDocument();
  });

  it('does not leak the label onto the dialog element as an attribute', () => {
    renderDialog('Fermer');

    // It is destructured out of the props that are spread onto Radix's content;
    // spreading it would put a stray `closelabel` attribute on every dialog and
    // log a React warning about an unknown DOM property.
    expect(screen.getByTestId('dialog-content')).not.toHaveAttribute('closelabel');
  });
});
