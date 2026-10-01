// @vitest-environment jsdom
import { describe, it, expect, afterEach, beforeAll, beforeEach, vi } from 'vitest';
import React, { useState } from 'react';
import { render as rtlRender, screen, cleanup, fireEvent, act } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import en from '@/messages/en.json';
import PlanGrid, { PlanCardToggle } from '../PlanGrid';

const render = (ui: React.ReactElement) =>
  rtlRender(ui, {
    wrapper: ({ children }) => (
      <NextIntlClientProvider locale="en" messages={en}>{children}</NextIntlClientProvider>
    ),
  });

const card = (name: string) => <article key={name}>{name}</article>;
const groups = [
  { key: 'individual', cards: ['Free', 'Starter', 'Pro'].map(card) },
  { key: 'business', tinted: true, cards: ['Team', 'Enterprise'].map(card) },
];

/**
 * jsdom lays nothing out, so the rail's geometry is stubbed: a 1104px rail holding 316px
 * cards, i.e. 3 whole cards and the edge of a 4th, the landing's desktop layout.
 */
function stubLayout({ rail, cardWidth }: { rail: number; cardWidth: number }) {
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(function (this: HTMLElement) {
    return this.dataset.testid === 'plan-grid' ? rail : 0;
  });
  vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockImplementation(function (this: HTMLElement) {
    return this.hasAttribute('data-plan-cell') ? cardWidth : 0;
  });
}

beforeAll(() => {
  class ResizeObserverStub {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  vi.stubGlobal('ResizeObserver', ResizeObserverStub);
  vi.stubGlobal('matchMedia', (query: string) => ({ matches: true, media: query, addEventListener() {}, removeEventListener() {} }));
});

beforeEach(() => {
  HTMLElement.prototype.scrollTo = vi.fn();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('PlanGrid', () => {
  it('renders every plan in order, and only the tinted group sets the card tint', () => {
    render(<PlanGrid groups={groups} />);
    const cells = document.querySelectorAll('[data-plan-cell]');
    expect([...cells].map(c => c.textContent)).toEqual(['Free', 'Starter', 'Pro', 'Team', 'Enterprise']);
    expect(cells[2].className).not.toContain('--plan-card-bg');
    expect(cells[3].className).toContain('--plan-card-bg');
  });

  it('draws no edge and no dots when every card fits', () => {
    stubLayout({ rail: 2000, cardWidth: 316 });
    render(<PlanGrid groups={groups} />);
    expect(screen.queryByRole('button', { name: 'Next plans' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Previous plans' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Show plans from position/ })).toBeNull();
  });

  it('the right edge jumps straight to the last plans, not one card further', () => {
    stubLayout({ rail: 1104, cardWidth: 316 });
    render(<PlanGrid groups={groups} />);
    const rail = screen.getByTestId('plan-grid');

    // At rest only the right edge is offered: nothing hides on the left.
    expect(screen.queryByRole('button', { name: 'Previous plans' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Next plans' }));

    // 5 plans, 3 in view: the last position is card 2, at 2 steps of 316px (no gap in jsdom).
    expect(rail.scrollTo).toHaveBeenCalledWith({ left: 632, behavior: 'smooth' });
  });

  it('once at the end, only the left edge remains and it goes back to the first plan', () => {
    stubLayout({ rail: 1104, cardWidth: 316 });
    render(<PlanGrid groups={groups} />);
    const rail = screen.getByTestId('plan-grid');

    act(() => {
      rail.scrollLeft = 632;
      fireEvent.scroll(rail);
    });

    expect(screen.queryByRole('button', { name: 'Next plans' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Previous plans' }));
    expect(rail.scrollTo).toHaveBeenCalledWith({ left: 0, behavior: 'smooth' });
  });

  it('shows one dot per resting position and marks the current one', () => {
    stubLayout({ rail: 1104, cardWidth: 316 });
    render(<PlanGrid groups={groups} />);
    const dots = screen.getAllByRole('button', { name: /Show plans from position/ });
    expect(dots).toHaveLength(3);
    expect(dots[0].getAttribute('aria-current')).toBe('true');
    expect(dots[1].getAttribute('aria-current')).toBeNull();
  });
});

describe('PlanCardToggle', () => {
  it('reports its state and flips it on tap (the mobile collapse of a plan card)', () => {
    function Harness() {
      const [open, setOpen] = useState(false);
      return <PlanCardToggle open={open} onToggle={() => setOpen(o => !o)} label="Pro" />;
    }
    render(<Harness />);
    const toggle = screen.getByRole('button', { name: 'Pro' });
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    fireEvent.click(toggle);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
  });
});
