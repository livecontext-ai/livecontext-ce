// @vitest-environment jsdom
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const theme = vi.hoisted(() => ({ value: 'light' as 'light' | 'dark', toggle: vi.fn() }));
vi.mock('@/components/ThemeProvider', () => ({
  useTheme: () => ({ theme: theme.value, toggleTheme: theme.toggle }),
}));
vi.mock('next/link', () => ({
  default: ({ children, href, ...rest }: { children: React.ReactNode; href: string }) => <a href={href} {...rest}>{children}</a>,
}));

import { BrandTopbar } from '../BrandTopbar';

beforeEach(() => {
  theme.value = 'light';
  theme.toggle.mockReset();
});
afterEach(cleanup);

describe('BrandTopbar (the Keycloak login bar, on the pages that stand alone)', () => {
  it('the logo mark alone on the left links home; the controls sit before the theme button', () => {
    render(<BrandTopbar><span data-testid="language" /></BrandTopbar>);

    const home = screen.getByRole('link', { name: 'LiveContext home' });
    expect(home.getAttribute('href')).toBe('/');
    // A mark, no wordmark text beside it.
    expect(home.querySelector('svg')).toBeTruthy();
    expect(home.textContent).not.toContain('LiveContext');
    const controls = screen.getByTestId('language').parentElement as HTMLElement;
    expect(controls.lastElementChild?.tagName).toBe('BUTTON');
  });

  it('the theme button is named for the theme it switches to, in the labels it is given', () => {
    const labels = { toLight: 'Passer au thème clair', toDark: 'Passer au thème sombre' };
    const { rerender } = render(<BrandTopbar themeLabels={labels} />);
    expect(screen.getByRole('button', { name: 'Passer au thème sombre' })).toBeTruthy();

    theme.value = 'dark';
    rerender(<BrandTopbar themeLabels={labels} />);
    fireEvent.click(screen.getByRole('button', { name: 'Passer au thème clair' }));
    expect(theme.toggle).toHaveBeenCalledTimes(1);
  });

  it('without labels (the auth pages), it keeps its English name', () => {
    render(<BrandTopbar />);

    expect(screen.getByRole('button', { name: 'Toggle theme' })).toBeTruthy();
  });
});
