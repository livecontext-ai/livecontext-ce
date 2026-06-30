// @vitest-environment jsdom
import * as React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

import { ModeToggle } from '../CredentialWizard';

const t = (key: string) => {
  const map: Record<string, string> = {
    'modeToggle.label': 'Connection mode',
    'modeToggle.standard': 'Standard',
    'modeToggle.advanced': 'Custom OAuth',
    'modeToggle.ariaSwitchToStandard': 'Switch to standard connection',
    'modeToggle.ariaSwitchToAdvanced': 'Switch to custom OAuth connection',
  };
  return map[key] ?? key;
};

describe('ModeToggle - radiogroup ARIA contract', () => {
  it('uses role="radiogroup" with aria-label - the right WAI-ARIA pattern for "pick one of N states" with no associated panel (a tablist would require an aria-controls tabpanel which the wizard does not have, since each step replaces the entire form)', () => {
    render(<ModeToggle active="standard" onSwitch={() => {}} disabled={false} t={t} />);

    const group = screen.getByRole('radiogroup');
    expect(group.getAttribute('aria-label')).toBe('Connection mode');
  });

  it('marks the active segment with aria-checked="true" and the inactive with "false" - assistive tech announces "Standard, selected"', () => {
    render(<ModeToggle active="standard" onSwitch={() => {}} disabled={false} t={t} />);

    const standard = screen.getByRole('radio', { name: 'Standard' });
    const advanced = screen.getByRole('radio', { name: 'Switch to custom OAuth connection' });

    expect(standard.getAttribute('aria-checked')).toBe('true');
    expect(advanced.getAttribute('aria-checked')).toBe('false');
  });

  it('keeps both segments focusable via the roving-tabindex pattern - the active radio has tabIndex=0 (in tab order), the inactive radio has tabIndex=-1 (programmatically focusable, reachable via arrow keys). Drops HTML disabled on the active so screen-reader users keep the "you are here" affordance', () => {
    render(<ModeToggle active="standard" onSwitch={() => {}} disabled={false} t={t} />);

    const standard = screen.getByRole('radio', { name: 'Standard' });
    const advanced = screen.getByRole('radio', { name: 'Switch to custom OAuth connection' });

    expect(standard.getAttribute('tabindex')).toBe('0');
    expect(advanced.getAttribute('tabindex')).toBe('-1');
    expect((standard as HTMLButtonElement).disabled).toBe(false);
    expect((advanced as HTMLButtonElement).disabled).toBe(false);
  });

  it('clicking the active segment is a no-op (onClick is undefined); only the inactive segment fires onSwitch', () => {
    const onSwitch = vi.fn();
    render(<ModeToggle active="standard" onSwitch={onSwitch} disabled={false} t={t} />);

    const standard = screen.getByRole('radio', { name: 'Standard' });
    const advanced = screen.getByRole('radio', { name: 'Switch to custom OAuth connection' });

    fireEvent.click(standard);
    expect(onSwitch).not.toHaveBeenCalled();

    fireEvent.click(advanced);
    expect(onSwitch).toHaveBeenCalledTimes(1);
  });

  it('arrow keys (Left/Right/Up/Down) on the radiogroup fire onSwitch - selection follows focus per the radio-group pattern, so the user does not need an extra Enter keystroke', () => {
    const onSwitch = vi.fn();
    render(<ModeToggle active="standard" onSwitch={onSwitch} disabled={false} t={t} />);

    const group = screen.getByRole('radiogroup');

    fireEvent.keyDown(group, { key: 'ArrowRight' });
    fireEvent.keyDown(group, { key: 'ArrowLeft' });
    fireEvent.keyDown(group, { key: 'ArrowUp' });
    fireEvent.keyDown(group, { key: 'ArrowDown' });

    expect(onSwitch).toHaveBeenCalledTimes(4);
  });

  it('non-arrow keys do NOT fire onSwitch - Tab/Enter/Space follow native button behavior, no rogue switch', () => {
    const onSwitch = vi.fn();
    render(<ModeToggle active="standard" onSwitch={onSwitch} disabled={false} t={t} />);

    const group = screen.getByRole('radiogroup');

    fireEvent.keyDown(group, { key: 'Enter' });
    fireEvent.keyDown(group, { key: ' ' });
    fireEvent.keyDown(group, { key: 'Tab' });
    fireEvent.keyDown(group, { key: 'a' });

    expect(onSwitch).not.toHaveBeenCalled();
  });

  it('respects the disabled prop: clicks AND arrow keys both inert during in-flight save', () => {
    const onSwitch = vi.fn();
    render(<ModeToggle active="standard" onSwitch={onSwitch} disabled t={t} />);

    const advanced = screen.getByRole('radio', { name: 'Switch to custom OAuth connection' });
    expect((advanced as HTMLButtonElement).disabled).toBe(true);

    fireEvent.click(advanced);
    fireEvent.keyDown(screen.getByRole('radiogroup'), { key: 'ArrowRight' });

    expect(onSwitch).not.toHaveBeenCalled();
  });

  it('regression - copy contract: the active segment renders ONLY the bare noun, no marketing subtitle. Pins the original UX fix: the prior banner had "Standard connection" + "1-click connect via LiveContext\'s verified OAuth client. Limited to standard scopes." - the descriptive subtitle is what made users read it as a feature description rather than a state indicator. If a future PR reintroduces hint copy inside the toggle, this test fails.', () => {
    render(<ModeToggle active="standard" onSwitch={() => {}} disabled={false} t={t} />);

    const standard = screen.getByRole('radio', { name: 'Standard' });
    // Bare noun, nothing else. No "1-click connect …" subtitle, no parenthetical.
    expect(standard.textContent?.trim()).toBe('Standard');

    const advanced = screen.getByRole('radio', { name: 'Switch to custom OAuth connection' });
    expect(advanced.textContent?.trim()).toBe('Custom OAuth');
  });

  it('regression - visual selection class: active segment carries a distinct background class from the inactive one. Without this, a CSS regression flattening both pills to the same style would silently ship and the user would lose the "you are here" visual affordance', () => {
    render(<ModeToggle active="standard" onSwitch={() => {}} disabled={false} t={t} />);

    const standard = screen.getByRole('radio', { name: 'Standard' });
    const advanced = screen.getByRole('radio', { name: 'Switch to custom OAuth connection' });

    // Active pill must include the filled-bg class; inactive must NOT.
    expect(standard.className).toContain('bg-theme-primary');
    expect(advanced.className).not.toContain('bg-theme-primary');
  });
});
