'use client';

import { useQuery } from '@tanstack/react-query';
import { cloudLinkService, type CloudLinkStatus } from '@/lib/api/cloud-link.service';
import { IS_CE } from '@/lib/edition';
import { useAuth } from '@/lib/providers/smart-providers';

export interface CeCloudLinkState {
  /** Raw status payload - null while loading, on error, or in cloud builds. */
  status: CloudLinkStatus | null;
  /**
   * True until we know whether this CE install is cloud-linked (auth resolving
   * or the status request in flight). Always false in cloud builds and for
   * anonymous visitors (who can never be linked).
   */
  isLoading: boolean;
  /**
   * linked AND registered - the install can consume the cloud marketplace the
   * way cloud does (registered gates the install_id handshake; a linked but
   * unregistered install is still mid-onboarding). False on error: degrade to
   * the unlinked experience rather than break the page.
   *
   * PER-USER: only the link OWNER (the admin who connected) reads true here. It
   * drives the MANAGEMENT surface. A non-owner member inheriting the install link
   * reads false here (but true on {@link isInstallCloudLinked}).
   */
  isCloudLinked: boolean;
  /**
   * INSTALL-GLOBAL: true if the install has ANY active registered cloud link (the
   * admin's), regardless of which user asks. Drives VISIBILITY - a non-owner member
   * inherits the admin's cloud marketplace + plan badge even though it cannot manage
   * the link (isCloudLinked stays false for that member).
   */
  isInstallCloudLinked: boolean;
}

/**
 * CE-only cloud-link status. Shares the react-query cache key used by
 * AppSidebar (['cloud-link', 'status']) so the sidebar menu and the
 * marketplace gate never fire duplicate requests. Cloud builds never hit the
 * network and report a resolved, unlinked state.
 */
export function useCeCloudLinkStatus(): CeCloudLinkState {
  const { isLoading: isAuthLoading, isAuthenticated } = useAuth();
  const enabled = IS_CE && !isAuthLoading && isAuthenticated;
  const { data, isPending } = useQuery({
    queryKey: ['cloud-link', 'status'],
    queryFn: () => cloudLinkService.getStatus(),
    enabled,
    staleTime: 5 * 60 * 1000,
    retry: false,
  });

  const isLoading = IS_CE && (isAuthLoading || (enabled && isPending));
  const status = IS_CE && data ? data : null;
  const isCloudLinked = !!status?.linked && status?.registered === true;
  // Install-global: any user on an admin-linked install inherits cloud visibility.
  const isInstallCloudLinked = !!status?.installLinked;
  return { status, isLoading, isCloudLinked, isInstallCloudLinked };
}
