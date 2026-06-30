'use client';

import { useCallback } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/lib/providers/smart-providers';

type Variant = 'primary' | 'secondary' | 'link';

interface SignInButtonProps {
  children: React.ReactNode;
  variant?: Variant;
  className?: string;
  returnTo?: string;
  /** Main-site origin. When set (docs subdomain), the button hands off to the
   *  apex app/auth with a full navigation instead of routing locally. */
  baseUrl?: string;
}

export default function SignInButton({
  children,
  variant = 'primary',
  className = '',
  returnTo = '/app/chat',
  baseUrl,
}: SignInButtonProps) {
  const router = useRouter();
  const { isAuthenticated, isLoading, loginWithRedirect } = useAuth();

  const handleClick = useCallback(
    async (e: React.MouseEvent) => {
      e.preventDefault();
      if (baseUrl) {
        // Off the main host (e.g. the docs subdomain): hand off to the apex,
        // which owns the app + auth, with a full navigation.
        window.location.assign(`${baseUrl}${returnTo}`);
        return;
      }
      if (isLoading) return;
      if (isAuthenticated) {
        router.push(returnTo);
        return;
      }
      await loginWithRedirect({ appState: { returnTo } });
    },
    [baseUrl, isAuthenticated, isLoading, loginWithRedirect, returnTo, router]
  );

  const variantStyle: React.CSSProperties =
    variant === 'primary'
      ? { background: 'var(--accent-primary)', color: 'var(--accent-foreground)' }
      : variant === 'secondary'
        ? { border: '1px solid var(--border-color)', color: 'var(--text-primary)' }
        : { color: 'var(--text-secondary)' };

  return (
    <a
      href={baseUrl ? `${baseUrl}${returnTo}` : returnTo}
      onClick={handleClick}
      className={className}
      style={variantStyle}
      aria-busy={isLoading || undefined}
    >
      {children}
    </a>
  );
}
