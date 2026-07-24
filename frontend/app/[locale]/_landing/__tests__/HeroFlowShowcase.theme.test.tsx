// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import LandingThemeProvider, { useLandingTheme } from '@/components/landing/LandingThemeProvider';
import HeroFlowShowcase from '../HeroFlowShowcase';

// The hero iframe follows the landing theme through a postMessage handshake
// ({type:'lc-theme', theme}) pushed on load and on every toggle; the src itself
// is frozen at mount so a toggle never reloads the animation.

function ToggleProbe() {
  const { toggle } = useLandingTheme();
  return (
    <button type="button" onClick={toggle}>
      probe-toggle
    </button>
  );
}

function renderShowcase() {
  return render(
    <LandingThemeProvider>
      <ToggleProbe />
      <HeroFlowShowcase />
    </LandingThemeProvider>,
  );
}

describe('HeroFlowShowcase theme handshake', () => {
  beforeEach(() => {
    localStorage.clear();
  });
  afterEach(cleanup);

  it('mounts the iframe on the plain src under the light default (no theme param)', () => {
    renderShowcase();
    const iframe = screen.getByTitle('Watch an automation build itself and run');
    expect(iframe).toHaveAttribute('src', '/hero-flow.html');
  });

  it('pushes lc-theme dark to the iframe on toggle, and light on toggle back, without changing src', async () => {
    renderShowcase();
    const iframe = screen.getByTitle('Watch an automation build itself and run') as HTMLIFrameElement;
    const postMessage = vi.fn();
    // jsdom iframes have a real contentWindow; spy on its postMessage.
    Object.defineProperty(iframe, 'contentWindow', { value: { postMessage }, configurable: true });

    fireEvent.click(screen.getByRole('button', { name: 'probe-toggle' }));
    await waitFor(() =>
      expect(postMessage).toHaveBeenCalledWith({ type: 'lc-theme', theme: 'dark' }, window.location.origin),
    );

    fireEvent.click(screen.getByRole('button', { name: 'probe-toggle' }));
    await waitFor(() =>
      expect(postMessage).toHaveBeenCalledWith({ type: 'lc-theme', theme: 'light' }, window.location.origin),
    );

    // Frozen src: the toggle must never reload the animation.
    expect(iframe).toHaveAttribute('src', '/hero-flow.html');
  });
});
