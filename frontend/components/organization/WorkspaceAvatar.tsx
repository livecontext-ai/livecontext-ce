"use client";

import React from "react";
import { cn } from "@/lib/utils";

/**
 * Workspace (organization) avatar - presentational only.
 *
 * Renders the workspace's configured avatar when one is set (OWNER/ADMIN upload
 * it from the settings/organization header → served via the org avatar
 * endpoint, passed in as `avatarUrl`), otherwise a deterministic initials chip
 * (e.g. "AL" for "ada lovelace's Workspace") on a colour derived from the name.
 * The upload/remove UI lives in the org settings page, not here.
 */

const SIZE_CLASSES = {
  xs: "w-6 h-6 text-[10px]",
  sm: "w-8 h-8 text-xs",
  md: "w-10 h-10 text-sm",
  lg: "w-12 h-12 text-base",
} as const;

// Deterministic palette for the initials fallback. Each entry reads well in
// both light and dark themes (solid hue + white text).
const PALETTE = [
  "bg-rose-500 text-white",
  "bg-orange-500 text-white",
  "bg-amber-500 text-white",
  "bg-emerald-500 text-white",
  "bg-teal-500 text-white",
  "bg-sky-500 text-white",
  "bg-blue-500 text-white",
  "bg-indigo-500 text-white",
  "bg-violet-500 text-white",
  "bg-fuchsia-500 text-white",
] as const;

/**
 * Derive up to two uppercase initials from a workspace name. Strips the
 * possessive "'s" and the generic "Workspace" word so "ada lovelace's
 * Workspace" → "AL" and a single-token name like "livecontextai" → "LI".
 */
export function getWorkspaceInitials(name: string): string {
  const cleaned = (name ?? "")
    .replace(/['’]s\b/gi, "")
    .replace(/\bworkspace\b/gi, "")
    .trim();
  const words = cleaned.split(/\s+/).filter(Boolean);
  if (words.length === 0) {
    const compact = (name ?? "").replace(/\s+/g, "");
    return (compact.slice(0, 2) || "?").toUpperCase();
  }
  if (words.length === 1) {
    return words[0].slice(0, 2).toUpperCase();
  }
  return (words[0][0] + words[words.length - 1][0]).toUpperCase();
}

/**
 * Backend avatar URLs come back as `/api/organizations/{id}/avatar?v=…`; the
 * browser must reach them through the Next.js proxy. Object URLs (blob:/data:)
 * and absolute URLs are passed through untouched.
 */
function resolveSrc(avatarUrl: string): string {
  return avatarUrl.startsWith("/api/") ? `/api/proxy${avatarUrl.slice(4)}` : avatarUrl;
}

function colorClassFor(name: string): string {
  let hash = 0;
  for (let i = 0; i < (name ?? "").length; i++) {
    hash = (hash * 31 + name.charCodeAt(i)) | 0;
  }
  return PALETTE[Math.abs(hash) % PALETTE.length];
}

interface WorkspaceAvatarProps {
  name: string;
  /** Configured workspace avatar URL. Falsy → initials fallback. */
  avatarUrl?: string | null;
  size?: keyof typeof SIZE_CLASSES;
  className?: string;
}

export function WorkspaceAvatar({ name, avatarUrl, size = "md", className }: WorkspaceAvatarProps) {
  // Anti-blink: the image fades in OVER the persistent initials chip once loaded, so a
  // fresh mount (e.g. the user menu opening) shows stable initials instead of an empty
  // circle that pops to the photo. A failed load just keeps the initials.
  const [imgLoaded, setImgLoaded] = React.useState(false);
  const base = cn(
    SIZE_CLASSES[size],
    "rounded-full flex-shrink-0 flex items-center justify-center overflow-hidden",
    className
  );

  return (
    <div
      className={cn(base, colorClassFor(name), "font-semibold leading-none select-none relative")}
      title={name}
      aria-hidden="true"
    >
      {getWorkspaceInitials(name)}
      {avatarUrl && (
        <img
          src={resolveSrc(avatarUrl)}
          alt={name}
          onLoad={() => setImgLoaded(true)}
          className={cn(
            "absolute inset-0 w-full h-full object-cover transition-opacity duration-150",
            imgLoaded ? "opacity-100" : "opacity-0"
          )}
        />
      )}
    </div>
  );
}
