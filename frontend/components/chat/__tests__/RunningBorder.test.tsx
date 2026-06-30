/**
 * @vitest-environment jsdom
 *
 * The pulsing blue "running" border overlaid on a displayed application
 * interface while its workflow run is executing. The app stays visible; the
 * border only renders while running, so call-sites can mount it unconditionally.
 */
import React from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { RunningBorder } from '../RunningBorder';

afterEach(cleanup);

describe('RunningBorder', () => {
  it('renders nothing when not running', () => {
    const { container } = render(<RunningBorder running={false} label="Running" />);
    expect(container.firstChild).toBeNull();
  });

  it('renders an accessible pulsing-border overlay when running', () => {
    render(<RunningBorder running label="Running" />);
    const status = screen.getByRole('status', { name: 'Running' });
    expect(status).toBeTruthy();
    // The blue pulsing ring is the `.app-running-border` surface (globals.css).
    expect(status.className).toContain('app-running-border');
    // Polite live region so screen readers announce the running state.
    expect(status.getAttribute('aria-live')).toBe('polite');
  });

  it('applies the caller className (e.g. a higher z-index)', () => {
    render(<RunningBorder running label="Running" className="z-[10000]" />);
    expect(screen.getByRole('status').className).toContain('z-[10000]');
  });
});
