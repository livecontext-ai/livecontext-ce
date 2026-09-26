/**
 * @vitest-environment jsdom
 *
 * The run-logs "i" rendered with the REAL translator and the REAL en.json. The grid suite mocks
 * next-intl to echo keys, so a wrong namespace or a missing key would pass there and show the raw
 * key path to users; here it fails. It also pins that a click (a tap on a touch screen, which has
 * no hover) opens the explanation, and a second click closes it.
 *
 * <p>The explanation is a POPOVER panel (role=dialog), not a hover tooltip: this 'i' goes through
 * InfoPopover, the app's one info icon, which is what the info-icon-click-guard enforces.
 */
import '@testing-library/jest-dom/vitest';
import { describe, expect, it } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import * as React from 'react';
import { NextIntlClientProvider } from 'next-intl';
import messages from '@/messages/en.json';
import { WorkflowRowIdHint } from '../WorkflowRowIdHint';

function renderHint() {
  return render(
    <NextIntlClientProvider locale="en" messages={messages}>
      <WorkflowRowIdHint />
    </NextIntlClientProvider>,
  );
}

describe('WorkflowRowIdHint - real en.json', () => {
  it('names itself with the real label', () => {
    renderHint();

    expect(screen.getByTestId('workflow-row-id-hint')).toHaveAttribute('aria-label', 'How to read this ID');
  });

  it('opens on a click or tap and shows the real explanation, then closes on a second one', async () => {
    renderHint();
    const button = screen.getByTestId('workflow-row-id-hint');

    await act(async () => { fireEvent.click(button); });
    const panel = await screen.findByRole('dialog');
    expect(panel.textContent).toContain('epoch.spawn.iteration.item');
    expect(panel.textContent).toContain('epoch 20, iteration 2');
    expect(panel.textContent).toContain('index 3 inside the output of row 21');
    expect(panel.textContent).toContain('An ID is not unique');

    await act(async () => { fireEvent.click(button); });
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});
