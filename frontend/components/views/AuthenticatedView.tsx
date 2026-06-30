'use client';

import { useEffect } from 'react';
import { useAuth } from '@/lib/providers/smart-providers';

interface AuthenticatedViewProps {
  children: React.ReactNode;
  maxWidth?: string;
  overflow?: boolean;
}

export function AuthenticatedView({ children, maxWidth = 'max-w-6xl', overflow }: AuthenticatedViewProps) {
  const { isLoading, isAuthenticated, loginWithRedirect } = useAuth();

  useEffect(() => {
    if (!isLoading && !isAuthenticated) {
      loginWithRedirect({
        appState: { returnTo: window.location.pathname }
      });
    }
  }, [isLoading, isAuthenticated, loginWithRedirect]);

  if (isLoading || !isAuthenticated) {
    return null;
  }

  if (overflow) {
    return (
      <div className="flex-1 min-h-0 overflow-hidden flex flex-col px-6 pt-6 pb-2">
        <div className={`${maxWidth} mx-auto w-full flex-1 min-h-0 flex flex-col`}>
          {children}
        </div>
      </div>
    );
  }

  return (
    <div className="flex-1 min-h-0 overflow-y-auto">
      <div className="min-h-full w-full p-6 pb-12">
        <div className={`${maxWidth} mx-auto space-y-6 w-full`}>
          {children}
        </div>
      </div>
    </div>
  );
}
