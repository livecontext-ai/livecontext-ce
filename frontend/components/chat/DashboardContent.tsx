'use client';

import React, { memo, useMemo, useEffect } from 'react';
import { useTranslations } from 'next-intl';
import { usePathname } from 'next/navigation';
import { useAuth } from '@/lib/providers/smart-providers';
import StorageInfo from '@/components/dashboard/StorageInfo';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import DataSourceTable from '@/components/DataSourceTable';
import PricingPage from '@/components/pricing/PricingPageContent';

/**
 * SettingsContent - Settings sections based on pathname
 * Routes:
 * - /app/settings/overview - Plan & Storage info
 * - /app/settings/usage - Data sources
 * - /app/settings/pricing - Pricing page
 */
export const DashboardContent = memo(function DashboardContent() {
  const t = useTranslations('chat');
  const { user, isAuthenticated, isLoading, loginWithRedirect } = useAuth();
  const pathname = usePathname();
  const tenantId = useMemo(() => user?.sub || user?.email || 'demo', [user?.sub, user?.email]);

  // Redirect to login if not authenticated
  useEffect(() => {
    if (!isLoading && !isAuthenticated) {
      loginWithRedirect({
        appState: { returnTo: window.location.pathname }
      });
    }
  }, [isLoading, isAuthenticated, loginWithRedirect]);

  // Wait for auth to be ready - return null to avoid double loading states
  if (isLoading || !isAuthenticated) {
    return null;
  }

  // Usage/Data page
  if (pathname?.includes('/app/settings/usage') || pathname?.includes('/app/settings/data')) {
    return (
      <div className="h-full overflow-y-auto p-6">
        <div className="max-w-7xl mx-auto space-y-8">
          <DataSourceTable />
        </div>
      </div>
    );
  }

  // Pricing page
  if (pathname?.includes('/app/settings/pricing')) {
    return (
      <div className="h-full overflow-y-auto">
        <PricingPage />
      </div>
    );
  }

  // Default: Overview page
  return (
    <div className="h-full overflow-y-auto p-6">
      <div className="max-w-7xl mx-auto space-y-8">
        <Card className="bg-theme-secondary/80">
          <CardHeader>
            <CardTitle className="text-xl text-theme-primary">{t('dashboard.storageOverview')}</CardTitle>
          </CardHeader>
          <CardContent>
            <StorageInfo userId={tenantId} />
          </CardContent>
        </Card>
      </div>
    </div>
  );
});
