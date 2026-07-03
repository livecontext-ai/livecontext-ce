// @vitest-environment jsdom
/**
 * Tests the global plan-limit toast listener: it reacts to the
 * "plan-limit-exceeded" window event fired by apiClient on HTTP 409
 * PLAN_RESOURCE_LIMIT_EXCEEDED, resolves the resource label from its inline
 * en/fr string tables (it mounts OUTSIDE NextIntlClientProvider, so language
 * comes from document.documentElement.lang), and raises a warning toast.
 * Focus: the new WORKFLOW_VARIABLE resource label in both languages.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, cleanup, act } from '@testing-library/react';

const mocks = vi.hoisted(() => ({
  addToast: vi.fn(),
  removeToast: vi.fn(),
}));

vi.mock('@/components/Toast', () => ({
  useToast: () => ({ toasts: [], addToast: mocks.addToast, removeToast: mocks.removeToast }),
}));

vi.mock('@/components/ToastContainer', () => ({
  default: () => <div data-testid="toast-container" />,
}));

import PlanLimitToastListener from '../PlanLimitToastListener';

interface PlanLimitDetail {
  error: string;
  resourceType: string;
  planCode: string;
  currentCount: number;
  limit: number;
  upgradeHint?: string;
}

function dispatchPlanLimit(detail: PlanLimitDetail) {
  act(() => {
    window.dispatchEvent(new CustomEvent('plan-limit-exceeded', { detail }));
  });
}

const BASE_DETAIL: PlanLimitDetail = {
  error: 'PLAN_RESOURCE_LIMIT_EXCEEDED',
  resourceType: 'WORKFLOW_VARIABLE',
  planCode: 'FREE',
  currentCount: 3,
  limit: 3,
};

describe('PlanLimitToastListener', () => {
  beforeEach(() => {
    document.documentElement.lang = 'en';
    render(<PlanLimitToastListener />);
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    document.documentElement.lang = '';
  });

  it('shows the English "workflow variable" label for WORKFLOW_VARIABLE', () => {
    dispatchPlanLimit(BASE_DETAIL);

    expect(mocks.addToast).toHaveBeenCalledTimes(1);
    expect(mocks.addToast).toHaveBeenCalledWith({
      type: 'warning',
      title: 'workflow variable limit reached',
      message: 'Your FREE plan allows 3 workflow variable(s). Upgrade your plan to create more.',
      duration: 8000,
    });
  });

  it('shows the French "variable de workflow" label when document lang is fr', () => {
    document.documentElement.lang = 'fr';

    dispatchPlanLimit(BASE_DETAIL);

    expect(mocks.addToast).toHaveBeenCalledTimes(1);
    expect(mocks.addToast).toHaveBeenCalledWith({
      type: 'warning',
      title: 'Limite de variable de workflow atteinte',
      message:
        'Votre plan FREE autorise 3 variable de workflow(s). Passez à un plan supérieur pour en créer davantage.',
      duration: 8000,
    });
  });

  it('resolves a regional French lang (fr-CA) to the French table', () => {
    document.documentElement.lang = 'fr-CA';

    dispatchPlanLimit(BASE_DETAIL);

    expect(mocks.addToast).toHaveBeenCalledWith(
      expect.objectContaining({ title: 'Limite de variable de workflow atteinte' })
    );
  });

  it('shows the "publication" label for PUBLICATION', () => {
    dispatchPlanLimit({ ...BASE_DETAIL, resourceType: 'PUBLICATION', limit: 1 });

    expect(mocks.addToast).toHaveBeenCalledWith(
      expect.objectContaining({
        title: 'publication limit reached',
        message: 'Your FREE plan allows 1 publication(s). Upgrade your plan to create more.',
      })
    );
  });

  it('prefers the backend upgradeHint over the default hint', () => {
    dispatchPlanLimit({ ...BASE_DETAIL, upgradeHint: 'Upgrade to Pro for 20 variables.' });

    expect(mocks.addToast).toHaveBeenCalledWith(
      expect.objectContaining({
        message: 'Your FREE plan allows 3 workflow variable(s). Upgrade to Pro for 20 variables.',
      })
    );
  });

  it('falls back to the raw resourceType for an unknown resource', () => {
    dispatchPlanLimit({ ...BASE_DETAIL, resourceType: 'SOMETHING_NEW' });

    expect(mocks.addToast).toHaveBeenCalledWith(
      expect.objectContaining({ title: 'SOMETHING_NEW limit reached' })
    );
  });

  it('ignores events whose error is not PLAN_RESOURCE_LIMIT_EXCEEDED', () => {
    dispatchPlanLimit({ ...BASE_DETAIL, error: 'SOME_OTHER_ERROR' });

    expect(mocks.addToast).not.toHaveBeenCalled();
  });

  it('ignores events with no detail', () => {
    act(() => {
      window.dispatchEvent(new CustomEvent('plan-limit-exceeded'));
    });

    expect(mocks.addToast).not.toHaveBeenCalled();
  });

  it('stops listening after unmount', () => {
    cleanup();

    dispatchPlanLimit(BASE_DETAIL);

    expect(mocks.addToast).not.toHaveBeenCalled();
  });
});
