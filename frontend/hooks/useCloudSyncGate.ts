"use client";

import { useCallback } from "react";
import { useRouter } from "@/i18n/navigation";
import { IS_CE } from "@/lib/edition";
import { useCeCloudLinkStatus } from "@/hooks/useCeCloudLinkStatus";

/**
 * Catalog-bundle sync (cloud → CE distribution) REQUIRES an active cloud link -
 * the backend gates it (a CE that isn't linked has no signed feed to pull). So
 * before any sync the UI must make sure the install is linked; if it isn't, send
 * the admin to the Cloud account page to Connect first.
 *
 * No-op outside CE (cloud builds bundles locally) and once linked: `ensureCloudLinked`
 * returns true and the caller proceeds.
 */
export function useCloudSyncGate() {
  const router = useRouter();
  const { isCloudLinked, isLoading } = useCeCloudLinkStatus();

  const ensureCloudLinked = useCallback((): boolean => {
    if (IS_CE && !isCloudLinked) {
      // The cloud-account page (connection tab) hosts the Connect-to-Cloud CTA.
      router.push("/app/settings/cloud-account");
      return false;
    }
    return true;
  }, [router, isCloudLinked]);

  // CE + not linked → the sync is blocked until the admin connects.
  const syncBlocked = IS_CE && !isLoading && !isCloudLinked;

  return { ensureCloudLinked, isCloudLinked, syncBlocked };
}
