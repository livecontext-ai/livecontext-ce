// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest';
import React from 'react';
import { render, screen, cleanup } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));
vi.mock('@/components/ThemeProvider', () => ({
  useTheme: () => ({ theme: 'light' }),
  useOptionalTheme: () => ({ theme: 'light' }),
}));
vi.mock('@/lib/hooks/smart-hooks-complete', () => ({ usePlans: () => ({ isLoading: false }) }));

import PlanSelector from '../PlanSelector';

const pro = {
  id: 'pro', name: 'Pro', description: '', price: '209', period: '/mo', credits: '250,000', storage: '10 GB',
  features: [], cta: 'Get Pro', popular: false, creditPrice: '',
};
const noop = async () => ({ success: true });

afterEach(cleanup);

describe('PlanSelector: the plan a partner recommended', () => {
  it('the selected card of the recommended plan names the partner, in gold', () => {
    render(<PlanSelector plan={pro} billingCycle="monthly" onPlanSelect={noop} selectedPlanCode="PRO"
      partnerRecommendedPlanCode="PRO" partnerRecommendedLabel="Recommended by your partner" />);

    expect(screen.getByTestId('partner-recommended-badge').textContent).toBe('Recommended by your partner');
    expect(screen.queryByText('selectedPlan')).toBeNull();
  });

  it('once the visitor picks another plan, that one is only "selected": the partner is never credited with it', () => {
    render(<PlanSelector plan={{ ...pro, id: 'team', name: 'Team' }} billingCycle="monthly" onPlanSelect={noop} selectedPlanCode="TEAM"
      partnerRecommendedPlanCode="PRO" partnerRecommendedLabel="Recommended by your partner" />);

    expect(screen.queryByTestId('partner-recommended-badge')).toBeNull();
    expect(screen.getByText('selectedPlan')).toBeTruthy();
  });

  it('the recommended plan, no longer selected, wears no badge at all', () => {
    render(<PlanSelector plan={pro} billingCycle="monthly" onPlanSelect={noop} selectedPlanCode="TEAM"
      partnerRecommendedPlanCode="PRO" partnerRecommendedLabel="Recommended by your partner" />);

    expect(screen.queryByTestId('partner-recommended-badge')).toBeNull();
    expect(screen.queryByText('selectedPlan')).toBeNull();
  });

  it('without a partner link, a selected plan keeps the ordinary "selected" badge', () => {
    render(<PlanSelector plan={pro} billingCycle="monthly" onPlanSelect={noop} selectedPlanCode="PRO" />);

    expect(screen.queryByTestId('partner-recommended-badge')).toBeNull();
    expect(screen.getByText('selectedPlan')).toBeTruthy();
  });
});
