// @vitest-environment jsdom
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import en from '@/messages/en.json';
import PlanCardFrame from '../PlanCardFrame';

function renderCard(props: Partial<React.ComponentProps<typeof PlanCardFrame>> = {}) {
  return render(
    <NextIntlClientProvider locale="en" messages={en}>
      <PlanCardFrame
        planId="pro"
        name="Pro"
        priceLabel="$209"
        period="/month"
        cycle="monthly"
        creditTierIndex={5}
        event={null}
        features={['features.credits', 'features.workspaces']}
        defaultOpen={false}
        frameStyle={{ border: '2px solid gold' }}
        badge={<div data-testid="badge">Recommended</div>}
        footer={(collapsed) => <button type="button" className={collapsed} data-testid="cta">Choose</button>}
        rootProps={{ 'data-testid': 'card', 'data-recommended': 'true' }}
        {...props}
      />
    </NextIntlClientProvider>,
  );
}

afterEach(cleanup);

describe('PlanCardFrame (the plan card the landing and a partner offer share)', () => {
  it('draws the name, the price with its period, every feature, the caller badge and frame', () => {
    renderCard();

    const card = screen.getByTestId('card');
    expect(card.getAttribute('data-recommended')).toBe('true');
    expect(card.style.border).toBe('2px solid gold');
    expect(screen.getByRole('heading', { level: 3 }).textContent).toBe('Pro');
    expect(card.textContent).toContain('$209');
    expect(card.textContent).toContain('/month');
    expect(card.querySelectorAll('li').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByTestId('badge').textContent).toBe('Recommended');
  });

  it('a quote-only plan shows no period after its price', () => {
    renderCard({ period: undefined, priceLabel: 'Custom' });

    expect(screen.getByTestId('card').textContent).not.toContain('/month');
  });

  it('on mobile a closed card hides its footer with its features, and opening it shows both', () => {
    renderCard();
    expect(screen.getByTestId('cta').className).toBe('max-md:hidden');

    fireEvent.click(screen.getByRole('button', { name: /Pro/ }));

    expect(screen.getByTestId('cta').className).toBe('');
  });

  it('a card that starts open (the recommended one) hides nothing', () => {
    renderCard({ defaultOpen: true });

    expect(screen.getByTestId('cta').className).toBe('');
  });
});
