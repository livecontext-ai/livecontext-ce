'use client';

// Root error page - stays in English intentionally.
// This component renders outside NextIntlClientProvider (no i18n context available).

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { RefreshCw, Home } from 'lucide-react';
import { reloadOnceForChunkLoadError } from '@/lib/utils/chunk-load-recovery';

export default function Error({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  const router = useRouter();

  useEffect(() => {
    console.error('Error:', error);
    reloadOnceForChunkLoadError(error);
  }, [error]);

  return (
    <div className="min-h-screen flex items-center justify-center bg-theme-primary p-8">
      <div className="max-w-md w-full text-center space-y-6">
        <div className="w-12 h-12 bg-red-100 dark:bg-red-900/30 rounded-full flex items-center justify-center mx-auto">
          <span className="text-red-600 dark:text-red-400 text-xl font-bold">!</span>
        </div>
        <div>
          <h1 className="text-lg font-semibold text-theme-primary mb-2">Something went wrong</h1>
          <p className="text-sm text-theme-secondary">
            An error occurred. Please try again or go back to the dashboard.
          </p>
        </div>
        <div className="flex flex-col sm:flex-row gap-3 justify-center">
          <button
            onClick={reset}
            className="inline-flex items-center justify-center gap-2 px-5 py-2.5 bg-theme-secondary rounded-xl text-sm font-medium text-theme-primary hover:bg-theme-tertiary transition-colors cursor-pointer"
          >
            <RefreshCw className="w-4 h-4" />
            Try Again
          </button>
          <button
            onClick={() => router.push('/app')}
            className="inline-flex items-center justify-center gap-2 px-5 py-2.5 bg-theme-secondary rounded-xl text-sm font-medium text-theme-primary hover:bg-theme-tertiary transition-colors cursor-pointer"
          >
            <Home className="w-4 h-4" />
            Go to Dashboard
          </button>
        </div>
      </div>
    </div>
  );
}
