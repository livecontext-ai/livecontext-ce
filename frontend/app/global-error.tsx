'use client';

// Global error boundary - stays in English intentionally.
// This component replaces the entire HTML document, so no i18n provider is available.

import { useEffect } from 'react';
import { reloadOnceForChunkLoadError } from '@/lib/utils/chunk-load-recovery';

export default function GlobalError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error('Global Error:', error);
    reloadOnceForChunkLoadError(error);
  }, [error]);

  return (
    <html>
      <body>
        <div className="min-h-screen flex items-center justify-center p-8" style={{ background: '#f9fafb' }}>
          <div className="max-w-md w-full text-center" style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem', alignItems: 'center' }}>
            <div style={{ width: 48, height: 48, borderRadius: '50%', background: '#fee2e2', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              <span style={{ color: '#dc2626', fontSize: 20, fontWeight: 'bold' }}>!</span>
            </div>
            <div>
              <h1 style={{ fontSize: 18, fontWeight: 600, marginBottom: 8 }}>Critical Error</h1>
              <p style={{ fontSize: 14, color: '#6b7280' }}>
                A critical error has occurred. Please refresh the page.
              </p>
            </div>
            <button
              onClick={reset}
              style={{ padding: '10px 20px', borderRadius: 12, background: '#f3f4f6', border: 'none', fontSize: 14, fontWeight: 500, cursor: 'pointer' }}
            >
              Try Again
            </button>
          </div>
        </div>
      </body>
    </html>
  );
}
