// @vitest-environment jsdom
/**
 * The admin control for marketplace demo mode: the WRITE path (nothing else in
 * the feature turns the mode on) and the standing warning, which is the only
 * place in the product that says a rehearsed install is being served instead of
 * a real one.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

import { MarketplaceDemoInstallCard } from '../MarketplaceDemoInstallCard';

const KEY = 'lc.marketplace.demo-install';

describe('MarketplaceDemoInstallCard', () => {
  beforeEach(() => window.localStorage.clear());
  afterEach(() => {
    cleanup();
    window.localStorage.clear();
  });

  it('starts off and says nothing about a demo being served', () => {
    render(<MarketplaceDemoInstallCard />);
    expect(screen.getByTestId('marketplace-demo-install-toggle')).toHaveAttribute('aria-checked', 'false');
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('turning it on persists the flag and raises the standing warning', () => {
    render(<MarketplaceDemoInstallCard />);
    fireEvent.click(screen.getByTestId('marketplace-demo-install-toggle'));

    expect(window.localStorage.getItem(KEY)).toBe('1');
    expect(screen.getByTestId('marketplace-demo-install-toggle')).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('status')).toHaveTextContent('activeNotice');
  });

  it('turning it off REMOVES the key rather than storing a falsy value', () => {
    window.localStorage.setItem(KEY, '1');
    render(<MarketplaceDemoInstallCard />);
    expect(screen.getByRole('status')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('marketplace-demo-install-toggle'));

    expect(window.localStorage.getItem(KEY)).toBeNull();
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('reflects a flag already stored by a previous session', () => {
    window.localStorage.setItem(KEY, '1');
    render(<MarketplaceDemoInstallCard />);
    expect(screen.getByTestId('marketplace-demo-install-toggle')).toHaveAttribute('aria-checked', 'true');
  });
});
