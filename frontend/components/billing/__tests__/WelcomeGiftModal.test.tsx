// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mockUseCreditBalance = vi.hoisted(() => vi.fn());

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, params?: Record<string, string>) => {
    const translations: Record<string, string> = {
      title: 'Welcome Gift!',
      subtitle: 'Renewed every month, free',
      credits: 'credits / month',
      description: `We've added ${params?.amount ?? ''} free credits per month to your account so you can explore all features right away - they refresh automatically every month.`,
      cta: 'Start exploring',
    };
    return translations[key] ?? key;
  },
}));

vi.mock('@/lib/format-cost', () => ({
  isCeMode: false,
}));

vi.mock('@/lib/hooks/smart-hooks-complete', () => ({
  useCreditBalance: () => mockUseCreditBalance(),
}));

import WelcomeGiftModal from '../WelcomeGiftModal';

describe('WelcomeGiftModal', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    sessionStorage.clear();
    mockUseCreditBalance.mockReset();
    mockUseCreditBalance.mockReturnValue({ balance: 2500, isLoading: false });
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    sessionStorage.clear();
  });

  function advanceWelcomeGiftTimers() {
    act(() => {
      vi.advanceTimersByTime(0);
    });
    act(() => {
      vi.advanceTimersByTime(600);
    });
  }

  it('opens from the post-onboarding gift flag and clears the flag', () => {
    sessionStorage.setItem('lc_show_welcome_gift', '1');

    render(<WelcomeGiftModal />);

    expect(sessionStorage.getItem('lc_show_welcome_gift')).toBeNull();

    advanceWelcomeGiftTimers();

    expect(screen.getByRole('dialog', { name: 'Welcome Gift!' })).toHaveAccessibleDescription(
      /We've added 2[,\s\u00a0\u202f.]500 free credits per month to your account so you can explore all features right away - they refresh automatically every month\./
    );
    expect(screen.getByText('Welcome Gift!')).toBeInTheDocument();
    expect(screen.getAllByText(/2[,\s\u00a0\u202f.]500/).length).toBeGreaterThan(0);
  });

  it('uses theme surfaces instead of the legacy promotional gradient', () => {
    sessionStorage.setItem('lc_show_welcome_gift', '1');

    render(<WelcomeGiftModal />);

    advanceWelcomeGiftTimers();

    const dialog = screen.getByRole('dialog');

    expect(dialog).toHaveClass('bg-theme-primary');
    expect(document.body.querySelector('.bg-gradient-to-br')).not.toBeInTheDocument();
    expect(document.body.querySelector('.bg-gradient-to-r')).not.toBeInTheDocument();
    expect(document.body.querySelectorAll('.border-theme').length).toBeGreaterThan(0);
  });

  it('does not open without a positive balance', () => {
    sessionStorage.setItem('lc_show_welcome_gift', '1');
    mockUseCreditBalance.mockReturnValue({ balance: 0, isLoading: false });

    render(<WelcomeGiftModal />);

    advanceWelcomeGiftTimers();

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('closes when the CTA is clicked', () => {
    sessionStorage.setItem('lc_show_welcome_gift', '1');

    render(<WelcomeGiftModal />);

    advanceWelcomeGiftTimers();
    fireEvent.click(screen.getByRole('button', { name: /Start exploring/i }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

describe('WelcomeGiftModal - suggestions hand-off (lc:welcome-gift-done)', () => {
  let doneEvents: number;
  let handler: () => void;

  beforeEach(() => {
    vi.useFakeTimers();
    sessionStorage.clear();
    mockUseCreditBalance.mockReset();
    doneEvents = 0;
    handler = () => { doneEvents += 1; };
    window.addEventListener('lc:welcome-gift-done', handler);
  });

  afterEach(() => {
    window.removeEventListener('lc:welcome-gift-done', handler);
    cleanup();
    vi.useRealTimers();
    sessionStorage.clear();
  });

  function advance() {
    act(() => { vi.advanceTimersByTime(0); });
    act(() => { vi.advanceTimersByTime(600); });
  }

  it('dispatches done (and sets the latch) when the gift closes via CTA', () => {
    mockUseCreditBalance.mockReturnValue({ balance: 2500, isLoading: false });
    sessionStorage.setItem('lc_show_welcome_gift', '1');

    render(<WelcomeGiftModal />);
    advance();
    fireEvent.click(screen.getByRole('button', { name: /Start exploring/i }));

    expect(doneEvents).toBe(1);
    expect(sessionStorage.getItem('lc_welcome_gift_done')).toBe('1');
  });

  it('dispatches done when balance is zero (gift never shows)', () => {
    mockUseCreditBalance.mockReturnValue({ balance: 0, isLoading: false });
    sessionStorage.setItem('lc_show_welcome_gift', '1');

    render(<WelcomeGiftModal />);
    advance();

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(doneEvents).toBe(1);
  });

  it('regression: dispatches done when the balance fetch errors (no suggestions deadlock)', () => {
    mockUseCreditBalance.mockReturnValue({ balance: null, isLoading: false, error: 'network' });
    sessionStorage.setItem('lc_show_welcome_gift', '1');

    render(<WelcomeGiftModal />);
    advance();

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(doneEvents).toBe(1);
  });

  it('does NOT dispatch done while balance is still settling (null, no error)', () => {
    mockUseCreditBalance.mockReturnValue({ balance: null, isLoading: false });
    sessionStorage.setItem('lc_show_welcome_gift', '1');

    render(<WelcomeGiftModal />);
    advance();

    expect(doneEvents).toBe(0);
  });

  it('does not dispatch done at all when there is no onboarding flag', () => {
    mockUseCreditBalance.mockReturnValue({ balance: 2500, isLoading: false });

    render(<WelcomeGiftModal />);
    advance();

    expect(doneEvents).toBe(0);
  });
});
