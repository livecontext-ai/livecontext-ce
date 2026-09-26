'use client';

import PricingPage from '@/components/pricing/PricingPageContent';
import { CeLinkPricingBanner } from '@/components/cloud-link/CeLinkPricingBanner';

/**
 * Settings pricing page component
 * Route: /app/settings/pricing
 * Layout is handled by app/[locale]/app/settings/layout.tsx
 *
 * Unified pricing (#15): CE and Cloud render the SAME grid (same plans, prices and style).
 * PricingPageContent hides the PAYG/billing-cycle controls in CE (isCeMode) and routes every
 * plan action to the linked cloud account, which governs the CE install's entitlements.
 *
 * Cloud only: `?ce_link=1` adds the banner of a self-hosted install waiting for a paid plan
 * before its link can complete (see lib/cloud-link/pendingCeLink.ts).
 */
export default function SettingsPricingPage() {
  return (
    <div className="h-full overflow-y-auto">
      <CeLinkPricingBanner />
      <PricingPage />
    </div>
  );
}
