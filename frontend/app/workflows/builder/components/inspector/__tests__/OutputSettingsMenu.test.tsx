// @vitest-environment jsdom
/**
 * Tests for the Output column header settings menu: gear next to the OUTPUT
 * title, offering "Use as mock output" on the run output currently loaded by
 * the column (published by RunDataPreview). Hidden entirely on nodes that
 * cannot carry a mock; the entry is disabled unless the loaded output is a
 * plain object.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';

import { OutputSettingsMenu } from '../OutputSettingsMenu';

const messages = {
  workflowBuilder: {
    canvas: { settings: 'Settings' },
    mock: { useAsMock: 'Use as mock output' },
  },
};

function renderMenu(props: React.ComponentProps<typeof OutputSettingsMenu>) {
  return render(
    <NextIntlClientProvider locale="en" messages={messages as any}>
      <OutputSettingsMenu {...props} />
    </NextIntlClientProvider>,
  );
}

function openMenu() {
  fireEvent.click(screen.getByTestId('output-settings-trigger'));
}

afterEach(cleanup);

describe('OutputSettingsMenu', () => {
  it('renders nothing on non-mock-capable nodes (no handler)', () => {
    renderMenu({ onUseAsMock: undefined, loadedOutput: { ok: true } });
    expect(screen.queryByTestId('output-settings-trigger')).toBeNull();
  });

  it('calls onUseAsMock with the loaded output object and closes', () => {
    const onUseAsMock = vi.fn();
    const output = { result: { score: 87 } };
    renderMenu({ onUseAsMock, loadedOutput: output });
    openMenu();

    const item = screen.getByTestId('output-use-as-mock') as HTMLButtonElement;
    expect(item.disabled).toBe(false);
    fireEvent.click(item);

    expect(onUseAsMock).toHaveBeenCalledTimes(1);
    expect(onUseAsMock).toHaveBeenCalledWith(output);
    expect(screen.queryByTestId('output-use-as-mock')).toBeNull(); // menu closed
  });

  it.each([
    ['null (nothing loaded)', null],
    ['an array', [1, 2, 3]],
  ])('disables the entry when the loaded output is %s', (_label, loadedOutput) => {
    const onUseAsMock = vi.fn();
    renderMenu({ onUseAsMock, loadedOutput });
    openMenu();

    const item = screen.getByTestId('output-use-as-mock') as HTMLButtonElement;
    expect(item.disabled).toBe(true);
    fireEvent.click(item);
    expect(onUseAsMock).not.toHaveBeenCalled();
  });

  it('exposes proper menu semantics (menu owns the menuitem, trigger announces it)', () => {
    renderMenu({ onUseAsMock: vi.fn(), loadedOutput: { ok: true } });
    const trigger = screen.getByTestId('output-settings-trigger');
    expect(trigger.getAttribute('aria-haspopup')).toBe('menu');
    openMenu();
    const menu = screen.getByRole('menu');
    expect(menu.contains(screen.getByRole('menuitem'))).toBe(true);
  });

  it('stacks the menu ABOVE the inspector overlay (z-[10000] beats the panel z-[9999])', () => {
    // Regression: the popover portals to <body> with a default z-50 while the
    // inspector panel hosting the trigger sits at z-[9999]/z-[150] - the menu
    // opened invisibly behind the panel.
    renderMenu({ onUseAsMock: vi.fn(), loadedOutput: { ok: true } });
    openMenu();
    const content = screen.getByRole('menu').parentElement as HTMLElement;
    expect(content.className).toContain('z-[10000]');
    expect(content.className).not.toMatch(/(?:^|\s)z-50(?:\s|$)/);
  });
});
