'use client';

import { useEffect } from 'react';
import { useOptionalAuth } from '@/lib/providers/smart-providers';
import { isDocsHost } from '@/lib/docs/docsHostRewrite';
import {
  accountDisplayName,
  clearSiteSessionHint,
  initials,
  writeSiteSessionHint,
} from '@/lib/auth/siteSessionHint';

/**
 * Keeps the docs subdomain's "signed in" hint in step with the app's account (see
 * `lib/auth/siteSessionHint`): written with the account's initials while it is signed in,
 * cleared once the app knows nobody is. Renders nothing. Cloud only (CE serves its docs on the
 * app's own origin, which reads the session directly).
 */
export default function SiteSessionHintWriter() {
  const auth = useOptionalAuth();
  const loading = !auth || auth.isLoading;
  const signedIn = !!auth?.isAuthenticated;
  const name = accountDisplayName(auth?.user);

  useEffect(() => {
    // The docs host never holds the session: reading it as "signed out" there would erase the
    // very hint it exists to read.
    if (loading || isDocsHost(window.location.host)) return;
    if (signedIn) writeSiteSessionHint(initials(name));
    else clearSiteSessionHint();
  }, [loading, signedIn, name]);

  return null;
}
