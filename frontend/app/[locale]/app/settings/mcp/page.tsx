'use client';

import { MCPTable } from '@/components/MCPTable';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useAuth } from '@/lib/providers/smart-providers';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { User } from 'lucide-react';

/**
 * MCPs dashboard page
 * Uses MCPTable component with same style as AgentTable
 */
export default function DashboardMCPsPage() {
  const { isAuthenticated, isAuthChecking } = useAuthGuard();
  const { loginWithRedirect } = useAuth();
  const tSettings = useTranslations('settings');

  if (isAuthChecking) {
    return (
      <div className="px-6">
        <div className="space-y-4 animate-pulse">
          <div className="h-8 bg-theme-secondary rounded w-1/3" />
          <div className="h-64 bg-theme-secondary rounded-xl" />
        </div>
      </div>
    );
  }

  if (!isAuthenticated) {
    return (
      <div className="px-6">
        <div className="min-h-[300px] flex items-center justify-center">
          <div className="text-center">
            <h1 className="text-2xl font-bold text-theme-primary mb-4">
              {tSettings('unauthorized')}
            </h1>
            <p className="text-theme-secondary mb-6">
              {tSettings('mustBeLoggedIn')}
            </p>
            <Button onClick={() => loginWithRedirect()} size="sm" className="h-8 px-3">
              <User className="w-4 h-4 mr-1" />
              {tSettings('signIn')}
            </Button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="px-6">
      <MCPTable />
    </div>
  );
}
