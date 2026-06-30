import React from 'react';
import Link from 'next/link';
import { Home, MessageCircle } from 'lucide-react';

export default function LocaleNotFound() {
  return (
    <div className="min-h-screen flex items-center justify-center bg-theme-primary p-8">
      <div className="max-w-md w-full text-center space-y-6">
        <h1 className="text-7xl font-bold text-theme-primary/10">404</h1>
        <div>
          <h2 className="text-lg font-semibold text-theme-primary mb-2">Page not found</h2>
          <p className="text-sm text-theme-secondary">
            The page you're looking for doesn't exist or has been moved.
          </p>
        </div>
        <div className="flex flex-col sm:flex-row gap-3 justify-center">
          <Link
            href="/app"
            className="inline-flex items-center justify-center gap-2 px-5 py-2.5 bg-theme-secondary rounded-xl text-sm font-medium text-theme-primary hover:bg-theme-tertiary transition-colors"
          >
            <Home className="w-4 h-4" />
            Go to Dashboard
          </Link>
          <Link
            href="/app/chat"
            className="inline-flex items-center justify-center gap-2 px-5 py-2.5 bg-theme-secondary rounded-xl text-sm font-medium text-theme-primary hover:bg-theme-tertiary transition-colors"
          >
            <MessageCircle className="w-4 h-4" />
            Start Chat
          </Link>
        </div>
      </div>
    </div>
  );
}
