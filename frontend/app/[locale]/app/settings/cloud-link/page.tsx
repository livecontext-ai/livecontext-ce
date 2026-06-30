'use client';

/**
 * Legacy route - the cloud-side "self-hosted instances" list was merged into the
 * unified Cloud page at /settings/cloud-account (its Connection tab renders the
 * same connected-installs inventory in the cloud edition). Kept as a redirect so
 * old bookmarks/links land on the canonical page. See the project docs.
 */

import React, { useEffect } from 'react';
import { useParams, useRouter } from 'next/navigation';
import LoadingSpinner from '@/components/LoadingSpinner';

export default function CloudLinkRedirectPage() {
  const router = useRouter();
  const params = useParams();
  const locale = typeof params?.locale === 'string' ? params.locale : 'en';

  useEffect(() => {
    router.replace(`/${locale}/app/settings/cloud-account`);
  }, [router, locale]);

  return (
    <div className="min-h-[300px] flex items-center justify-center">
      <LoadingSpinner />
    </div>
  );
}
