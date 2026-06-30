'use client';

import { InvitePanel } from '@/components/reward/InvitePanel';
import { RewardRedeemCard } from '@/components/reward/RewardRedeemCard';

/**
 * Settings -> Refer & earn: the owner's "invite friends" panel plus a card to
 * redeem a code received from someone else. Available in both editions (CE reads
 * its bound cloud account over cloud-connect). The page header is intentionally
 * omitted; each card carries its own title.
 */
export default function RewardsSettingsPage() {
  return (
    <div className="max-w-3xl space-y-6">
      <InvitePanel />
      <RewardRedeemCard />
    </div>
  );
}
