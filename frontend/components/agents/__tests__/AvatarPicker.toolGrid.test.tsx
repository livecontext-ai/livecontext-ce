// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';
import { AvatarPicker } from '../AvatarPicker';
import { PRESET_GRADIENTS } from '../avatarColors';

afterEach(cleanup);

function renderPicker(value: string, onChange = vi.fn()) {
  render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <AvatarPicker value={value} onChange={onChange} />
    </NextIntlClientProvider>,
  );
  return onChange;
}

// The Customize panel's tool grid is the ONLY UI that writes '?tool=' - these pin
// the exact values it emits (what gets persisted as the agent's avatarUrl).
describe('AvatarPicker - Customize panel tool grid', () => {
  it('clicking a tool emits the bare preset + tool (no color params when colors are default)', () => {
    const onChange = renderPicker('preset:blue');

    fireEvent.click(screen.getByRole('button', { name: 'Wrench' }));

    expect(onChange).toHaveBeenCalledWith('preset:blue?tool=wrench');
  });

  it('the None button clears the tool back to the bare preset', () => {
    const onChange = renderPicker('preset:blue?tool=wrench');

    fireEvent.click(screen.getByRole('button', { name: 'None' }));

    expect(onChange).toHaveBeenCalledWith('preset:blue');
  });

  it('recoloring PRESERVES the selected tool (colors first, tool after)', () => {
    // Regression guard: handleColorChange must thread activeTool through
    // buildPresetValue - dropping the third arg silently loses the badge
    // on the next color tweak.
    const onChange = renderPicker('preset:blue?tool=wrench');
    const [, defaultC2] = PRESET_GRADIENTS['preset:blue'];

    fireEvent.change(screen.getByLabelText('Primary'), { target: { value: '#123456' } });

    expect(onChange).toHaveBeenCalledWith(
      `preset:blue?c1=123456&c2=${defaultC2.replace('#', '').toUpperCase()}&tool=wrench`,
    );
  });

  it('picking a tool on a recolored preset keeps the custom colors', () => {
    const onChange = renderPicker('preset:blue?c1=123456&c2=ABCDEF');

    fireEvent.click(screen.getByRole('button', { name: 'Rocket' }));

    expect(onChange).toHaveBeenCalledWith('preset:blue?c1=123456&c2=ABCDEF&tool=rocket');
  });

  it('Reset clears BOTH the custom colors and the tool', () => {
    const onChange = renderPicker('preset:blue?c1=123456&c2=ABCDEF&tool=wrench');

    fireEvent.click(screen.getByRole('button', { name: 'Reset' }));

    expect(onChange).toHaveBeenCalledWith('preset:blue');
  });

  it('Reset is offered for a tool-only customization too (no custom colors)', () => {
    renderPicker('preset:blue?tool=wrench');
    expect(screen.getByRole('button', { name: 'Reset' })).toBeInTheDocument();
  });

  it('the selected tool is marked pressed; None is pressed when no tool is set', () => {
    renderPicker('preset:blue?tool=wrench');
    expect(screen.getByRole('button', { name: 'Wrench' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'None' })).toHaveAttribute('aria-pressed', 'false');
  });
});
