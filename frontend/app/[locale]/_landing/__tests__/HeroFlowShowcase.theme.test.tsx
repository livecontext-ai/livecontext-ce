// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import LandingThemeProvider, { useLandingTheme } from '@/components/landing/LandingThemeProvider';
import HeroFlowShowcase from '../HeroFlowShowcase';

vi.mock('next-intl', () => ({
  useLocale: () => 'fr',
  useTranslations: () => (key: string) => key === 'common.iframeTitle'
    ? 'Watch an automation build itself and run'
    : `translated:${key}`,
}));

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

  it('passes the app locale and translated navigation labels without a light theme param', () => {
    renderShowcase();
    const iframe = screen.getByTitle('Watch an automation build itself and run');
    const params = new URL(iframe.getAttribute('src')!, window.location.origin).searchParams;
    expect(params.get('locale')).toBe('fr');
    expect(params.get('label-creator')).toBe('translated:personas.creator.name');
    expect(params.get('navigationLabel')).toBe('translated:common.personaNavigation');
    expect(params.has('theme')).toBe(false);
  });

  it('pushes lc-theme dark to the iframe on toggle, and light on toggle back, without changing src', async () => {
    renderShowcase();
    const iframe = screen.getByTitle('Watch an automation build itself and run') as HTMLIFrameElement;
    const initialSrc = iframe.getAttribute('src');
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
    expect(iframe).toHaveAttribute('src', initialSrc);
  });
});
