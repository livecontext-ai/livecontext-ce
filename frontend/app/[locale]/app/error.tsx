'use client';

import React, { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { Home, RefreshCw, AlertTriangle, MessageCircle } from 'lucide-react';
import { reloadOnceForChunkLoadError } from '@/lib/utils/chunk-load-recovery';

/**
 * Error boundary for /app routes
 * Displays error page within the app layout with AppSidebar visible
 */
export default function AppError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  const router = useRouter();

  useEffect(() => {
    reloadOnceForChunkLoadError(error);
  }, [error]);

  return (
    <div className="h-full overflow-auto">
      <div className="flex flex-col items-center justify-center min-h-full p-8">
        {/* Error Icon */}
        <div className="mb-6">
          <div className="w-24 h-24 bg-red-100 dark:bg-red-900/30 rounded-full flex items-center justify-center">
            <AlertTriangle className="w-12 h-12 text-red-600 dark:text-red-400" />
          </div>
        </div>

        {/* Title and Description */}
        <div className="text-center mb-8 max-w-md">
          <h1 className="text-2xl font-semibold text-slate-900 dark:text-slate-100 mb-3">
            An error has occurred
          </h1>
          <p className="text-sm text-slate-600 dark:text-slate-400">
            Sorry, something went wrong. Please try again or contact support if the problem persists.
          </p>
          {process.env.NODE_ENV === 'development' && error?.message && (
            <div className="mt-4 p-3 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg">
              <p className="text-xs text-red-700 dark:text-red-300 font-mono break-all">
                {error.message}
              </p>
            </div>
          )}
        </div>

        {/* Quick Actions */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 mb-8 w-full max-w-xl">
          <button
            onClick={reset}
            className="flex flex-col items-center p-4 bg-blue-50 dark:bg-blue-900/30 rounded-xl hover:bg-blue-100 dark:hover:bg-blue-900/50 transition-colors group border border-blue-200 dark:border-blue-800"
          >
            <RefreshCw className="h-6 w-6 text-blue-600 dark:text-blue-400 mb-2 group-hover:text-blue-700 dark:group-hover:text-blue-300 transition-colors" />
            <span className="text-sm font-medium text-blue-700 dark:text-blue-300">Try Again</span>
          </button>

          <button
            onClick={() => router.push('/app')}
            className="flex flex-col items-center p-4 bg-slate-100 dark:bg-slate-800 rounded-xl hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors group"
          >
            <Home className="h-6 w-6 text-slate-500 dark:text-slate-400 mb-2 group-hover:text-slate-700 dark:group-hover:text-slate-200 transition-colors" />
            <span className="text-sm font-medium text-slate-700 dark:text-slate-300">Home</span>
          </button>

          <button
            onClick={() => router.push('/app/chat')}
            className="flex flex-col items-center p-4 bg-slate-100 dark:bg-slate-800 rounded-xl hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors group"
          >
            <MessageCircle className="h-6 w-6 text-slate-500 dark:text-slate-400 mb-2 group-hover:text-slate-700 dark:group-hover:text-slate-200 transition-colors" />
            <span className="text-sm font-medium text-slate-700 dark:text-slate-300">Chat</span>
          </button>
        </div>

        {/* Additional Help */}
        <div className="text-center">
          <p className="text-xs text-slate-500 dark:text-slate-500">
            If the problem persists, please contact support.
          </p>
        </div>
      </div>
    </div>
  );
}
