// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, cleanup } from '@testing-library/react';

/**
 * Which plan the dialog is told is "current".
 *
 * The rule differs per edition and getting it wrong is not cosmetic: marking
 * Free as current for a cloud-linked self-hosted install tells a paying reader
 * they are on the free plan.
 */

const subscription = vi.hoisted(() => ({ value: null as unknown }));
const cloudLink = vi.hoisted(() => ({ value: null as unknown }));
const edition = vi.hoisted(() => ({ isCe: false }));
const received = vi.hoisted(() => ({
  planCode: undefined as string | null | undefined,
}));
const opened = vi.hoisted(() => ({ calls: [] as unknown[] }));

vi.mock('@/lib/billing/plan-comparison-open', () => ({
  openPlanComparison: (request?: unknown) => opened.calls.push(request),
}));

vi.mock('@/lib/hooks/smart-hooks-complete', () => ({
  useSubscription: () => ({ subscription: subscription.value }),
}));

vi.mock('@/hooks/useCeCloudLinkStatus', () => ({
  useCeCloudLinkStatus: () => ({ status: cloudLink.value }),
}));

vi.mock('@/lib/edition', () => ({
  get IS_CE() {
    return edition.isCe;
  },
}));

vi.mock('../PlanComparisonDialog', () => ({
  default: ({ currentPlanCode }: { currentPlanCode?: string | null }) => {
    received.planCode = currentPlanCode;
    return null;
  },
}));

import AppPlanComparisonDialog from '../AppPlanComparisonDialog';
import { armWelcomeGift } from '@/lib/onboarding/welcomeGiftHandoff';

beforeEach(() => {
  subscription.value = null;
  cloudLink.value = null;
  edition.isCe = false;
  received.planCode = undefined;
  opened.calls = [];
  sessionStorage.clear();
});

afterEach(() => cleanup());

describe('AppPlanComparisonDialog (cloud)', () => {
  it('prefers the active workspace tier over the personal subscription', () => {
    // Same precedence as the sidebar badge: what governs here is the
    // workspace's plan, not the reader's own subscription.
    subscription.value = { activeOrgPlanCode: 'TEAM', subscription: { planCode: 'STARTER' } };

    render(<AppPlanComparisonDialog />);

    expect(received.planCode).toBe('TEAM');
  });

  it('falls back to the personal subscription when no workspace tier is set', () => {
    subscription.value = { subscription: { planCode: 'PRO' } };

    render(<AppPlanComparisonDialog />);

    expect(received.planCode).toBe('PRO');
  });

  it('marks nothing while the subscription is still unknown', () => {
    render(<AppPlanComparisonDialog />);

    expect(received.planCode).toBeNull();
  });
});

describe('AppPlanComparisonDialog (self-hosted)', () => {
  beforeEach(() => {
    edition.isCe = true;
    // A self-hosted install's LOCAL plan is always FREE; the cloud link is what
    // the backend actually bills against.
    subscription.value = { subscription: { planCode: 'FREE' } };
  });

  it('uses the linked cloud account plan, not the local one', () => {
    cloudLink.value = { cloudPlanCode: 'PRO' };

    render(<AppPlanComparisonDialog />);

    expect(received.planCode).toBe('PRO');
  });

  it('falls back to the local plan when the connected account has no subscription', () => {
    // '__NONE__' is what the cloud sends for a connected account with none.
    cloudLink.value = { cloudPlanCode: '__NONE__' };

    render(<AppPlanComparisonDialog />);

    expect(received.planCode).toBe('FREE');
  });

  it('falls back to the local plan when the install is not linked at all', () => {
    cloudLink.value = null;

    render(<AppPlanComparisonDialog />);

    expect(received.planCode).toBe('FREE');
  });
});

describe('AppPlanComparisonDialog (it is not an opener)', () => {
  it('opens nothing on an ordinary mount', () => {
    // Mounted in the app layout, so this runs on every page: an overlay that
    // opened on its own would cover the app on every navigation.
    render(<AppPlanComparisonDialog />);

    expect(opened.calls).toEqual([]);
  });

  it('opens nothing for a brand-new account either', () => {
    // This table DID open itself once, right after onboarding, to state the new
    // account's two monthly pots. A five-column Free-to-Enterprise matrix is a
    // screen for CHOOSING a plan, and a reader who just signed up is not
    // choosing one - WelcomeGiftModal states the same two figures instead. So
    // the flag onboarding writes must mean nothing at all here, and the
    // comparison is back to one in-app entry point.
    armWelcomeGift();

    render(<AppPlanComparisonDialog />);

    expect(opened.calls).toEqual([]);
  });
});

