'use client';

import { useState, useEffect } from 'react';
import { User } from 'lucide-react';
import { cn } from '@/lib/utils';

/** Material-design 12-color palette - mirrors InitialsAvatarGenerator.java. */
const PALETTE = [
  '#DB4437', '#E91E63', '#9C27B0', '#673AB7', '#3F51B5', '#4285F4',
  '#039BE5', '#00ACC1', '#0F9D58', '#43A047', '#F4B400', '#FF7043',
];

/** Compute up to 2 initials from a display name (mirrors backend logic). */
function computeInitials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length >= 2) {
    return (parts[0][0] + parts[1][0]).toUpperCase();
  }
  const single = parts[0] || '';
  return single.slice(0, 2).toUpperCase();
}

/**
 * Deterministic color from a string - simple djb2 hash mod palette length.
 * Note: backend uses SHA-256 for its server-generated SVG avatars; colors may
 * differ, but this path only runs when the backend image is unavailable.
 */
function pickColor(seed: string): string {
  let h = 0;
  for (let i = 0; i < seed.length; i++) {
    h = ((h << 5) - h + seed.charCodeAt(i)) | 0;
  }
  return PALETTE[((h % PALETTE.length) + PALETTE.length) % PALETTE.length];
}

interface PublisherAvatarProps {
  /** Internal numeric user id (as stored in publication.publisherId / review.reviewerId). */
  userId: string | number | null | undefined;
  name?: string | null;
  size?: number;
  /**
   * `overlay` (default) → subtle ring + theme-aware fallback, for marketplace card
   * footers and content areas.
   * `neutral` → no ring, muted gray fallback - for info panels, review lists.
   */
  variant?: 'overlay' | 'neutral';
  /**
   * CE-cloud parity: on a cloud-linked CE the publisher is a CLOUD user whose
   * id is absent from the local auth-service, so the local avatar endpoint
   * 404s. When true the avatar is fetched through the CE backend's cloud proxy
   * (`/api/proxy/publications/remote/users/{id}/avatar`). Only marketplace /
   * highlight cards rendering cloud publications pass this; everywhere else
   * (profiles, reviews) renders a LOCAL user and leaves it false.
   */
  remote?: boolean;
}

/**
 * Renders a platform user's avatar by hitting the stable public endpoint
 * `GET /api/proxy/users/{userId}/avatar` (proxied to auth-service, cached 1d).
 * On a cloud-linked CE, {@code remote} routes through the cloud proxy instead.
 *
 * Fallback chain:
 *  1. Backend avatar (uploaded photo or server-generated initials SVG)
 *  2. Client-side initials from `name` prop (for personas / deleted users)
 *  3. Generic User icon
 */
export function PublisherAvatar({ userId, name, size = 18, variant = 'overlay', remote = false }: PublisherAvatarProps) {
  const [failed, setFailed] = useState(false);
  useEffect(() => setFailed(false), [userId]);
  const hasId = userId !== null && userId !== undefined && String(userId).length > 0;
  const hasName = !!name && name.trim().length > 0;

  const wrapperClass = cn(
    'rounded-full overflow-hidden shrink-0',
    variant === 'overlay' && 'ring-1 ring-black/10 dark:ring-white/15',
  );

  const avatarSrc = remote
    ? `/api/proxy/publications/remote/users/${userId}/avatar`
    : `/api/proxy/users/${userId}/avatar`;

  // ---- Backend image (happy path) ----
  if (hasId && !failed) {
    return (
      <div className={wrapperClass} style={{ width: size, height: size }}>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src={avatarSrc}
          alt={name || ''}
          width={size}
          height={size}
          className="h-full w-full object-cover"
          loading="lazy"
          onError={() => setFailed(true)}
        />
      </div>
    );
  }

  // ---- Client-side initials (persona / deleted user / no userId) ----
  if (hasName) {
    const initials = computeInitials(name!);
    const bg = pickColor(name!.toLowerCase());
    const fontSize = Math.max(7, size * 0.45);
    return (
      <div
        className={cn('rounded-full flex items-center justify-center shrink-0',
          variant === 'overlay' && 'ring-1 ring-black/10 dark:ring-white/15',
        )}
        style={{ width: size, height: size, backgroundColor: bg }}
      >
        <span
          className="font-semibold text-white leading-none select-none"
          style={{ fontSize }}
        >
          {initials}
        </span>
      </div>
    );
  }

  // ---- Ultimate fallback - generic User icon ----
  const fallbackClass = cn(
    'rounded-full flex items-center justify-center shrink-0',
    variant === 'overlay'
      ? 'bg-gray-200 dark:bg-white/15 ring-1 ring-black/10 dark:ring-white/15'
      : 'bg-gray-200 dark:bg-gray-700',
  );
  const iconClass = variant === 'overlay'
    ? 'text-gray-500 dark:text-white'
    : 'text-gray-400 dark:text-gray-500';

  return (
    <div className={fallbackClass} style={{ width: size, height: size }}>
      <User className={iconClass} style={{ width: Math.max(10, size * 0.55), height: Math.max(10, size * 0.55) }} />
    </div>
  );
}

export default PublisherAvatar;
