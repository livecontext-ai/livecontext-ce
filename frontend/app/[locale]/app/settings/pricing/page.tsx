'use client';

import PricingPage from '@/components/pricing/PricingPageContent';

/**
 * Settings pricing page component
 * Route: /app/settings/pricing
 * Layout is handled by app/[locale]/app/settings/layout.tsx
 *
 * Unified pricing (#15): CE and Cloud render the SAME grid (same plans, prices and style).
 * PricingPageContent hides the PAYG/billing-cycle controls in CE (isCeMode) and routes every
 * plan action to the linked cloud account, which governs the CE install's entitlements.
 */
export default function SettingsPricingPage() {
  return (
    <div className="h-full overflow-y-auto">
      <PricingPage />
    </div>
  );
}
