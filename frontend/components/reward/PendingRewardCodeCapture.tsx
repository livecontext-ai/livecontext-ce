'use client';

import { useEffect } from 'react';
import { capturePendingRewardCode } from '@/lib/lifecycle/pendingRewardCode';

/**
 * Remembers a partner / creator code from the landing URL (`?lc_ref=CODE` on any page, or
 * `/redeem?code=CODE`) so it survives sign-up. Renders nothing. Mounted once in the root
 * providers (cloud only), next to the acquisition capture, so the landing counts.
 */
export default function PendingRewardCodeCapture() {
  useEffect(() => {
    capturePendingRewardCode(window);
  }, []);
  return null;
}
