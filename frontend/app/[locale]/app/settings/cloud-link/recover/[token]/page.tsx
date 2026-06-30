'use client';

/**
 * Legacy squat-recovery route. The recovery email now points at
 * /settings/cloud-account/recover/[token] (SquatRecoveryMailer.recoveryUrl);
 * this redirect keeps any in-flight email sent with the OLD url working -
 * the token (URL path-segment, single-use, 60-min TTL) is preserved so the
 * canonical page consumes it. Stays public (appRouteAuth) so the token is not
 * lost behind a login wall. See the project docs.
 */

import React, { useEffect } from 'react';
import { useParams, useRouter } from 'next/navigation';
import LoadingSpinner from '@/components/LoadingSpinner';

export default function CloudLinkRecoverRedirectPage() {
  const router = useRouter();
  const params = useParams();
  const locale = typeof params?.locale === 'string' ? params.locale : 'en';
  const token =
    typeof params?.token === 'string'
      ? params.token
      : Array.isArray(params?.token)
      ? params.token[0]
      : '';

  useEffect(() => {
    router.replace(
      `/${locale}/app/settings/cloud-account/recover/${encodeURIComponent(token)}`,
    );
  }, [router, locale, token]);

  return (
    <div className="min-h-[400px] flex items-center justify-center">
      <LoadingSpinner />
    </div>
  );
}
