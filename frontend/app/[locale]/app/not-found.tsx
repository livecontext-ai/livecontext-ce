'use client';

import React from 'react';
import { ArrowLeft } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { useSafeNavigate } from '@/contexts/NavigationGuardContext';
import LogoAnimate from '@/components/LogoAnimate';

export default function AppNotFound() {
  const router = useRouter();
  const safeNavigate = useSafeNavigate();

  return (
    <div className="flex items-center justify-center min-h-full p-8">
      <div className="max-w-md w-full text-center space-y-6">
          <LogoAnimate size="lg" className="mx-auto opacity-20" />

          <h1 className="text-6xl font-bold text-theme-primary/15 select-none">404</h1>

          <div>
            <h2 className="text-lg font-semibold text-theme-primary mb-2">Page not found</h2>
            <p className="text-sm text-theme-secondary leading-relaxed">
              This page doesn't exist or has been moved.
            </p>
          </div>

          <div className="flex gap-3 justify-center pt-1">
            <button
              onClick={() => router.back()}
              className="inline-flex items-center gap-1.5 px-4 py-2 bg-theme-secondary rounded-lg text-sm font-medium text-theme-primary hover:bg-theme-tertiary transition-colors cursor-pointer"
            >
              <ArrowLeft className="w-3.5 h-3.5" />
              Go back
            </button>
            <button
              onClick={() => safeNavigate('/app/chat')}
              className="px-4 py-2 bg-theme-secondary rounded-lg text-sm font-medium text-theme-primary hover:bg-theme-tertiary transition-colors cursor-pointer"
            >
              Chat
            </button>
          </div>
      </div>
    </div>
  );
}
