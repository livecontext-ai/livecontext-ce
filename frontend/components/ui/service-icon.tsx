"use client";

import React, { useState } from "react";
import Image from "next/image";
import { Key } from "lucide-react";
import {
  extractIconSlugFromUrl,
  monoDarkInvertClass,
} from "@/lib/credentials/monoIconSlugs";
import { normalizeIconSlug } from "@/lib/credentials/iconSlug";

interface ServiceIconProps {
  /** Icon slug (e.g. "gmail") - resolved to /icons/services/{iconSlug}.svg */
  iconSlug?: string;
  /** Full icon URL (e.g. "/icons/services/gmail.svg") - used directly */
  iconUrl?: string;
  size?: "sm" | "md" | "lg";
  className?: string;
  fallbackIcon?: React.ReactNode;
}

const sizeMap = {
  sm: { width: 16, height: 16, className: "w-4 h-4" },
  md: { width: 24, height: 24, className: "w-6 h-6" },
  lg: { width: 32, height: 32, className: "w-8 h-8" },
};

export function ServiceIcon({
  iconSlug,
  iconUrl,
  size = "md",
  className = "",
  fallbackIcon,
}: ServiceIconProps) {
  const [hasError, setHasError] = useState(false);
  const { width, height, className: sizeClass } = sizeMap[size];

  // Resolve src: prefer iconUrl, then build from normalized iconSlug ([a-z0-9]+)
  const canonicalSlug = iconSlug ? normalizeIconSlug(iconSlug) : null;
  const src = iconUrl || (canonicalSlug ? `/icons/services/${canonicalSlug}.svg` : null);

  if (!src || hasError) {
    return (
      fallbackIcon || (
        <div
          className={`${sizeClass} ${className} bg-theme-tertiary rounded flex items-center justify-center`}
        >
          <Key className="w-1/2 h-1/2 text-theme-secondary" />
        </div>
      )
    );
  }

  const monoDark = monoDarkInvertClass(iconSlug ?? extractIconSlugFromUrl(iconUrl));

  return (
    <Image
      src={src}
      alt=""
      width={width}
      height={height}
      className={`${sizeClass} ${className} ${monoDark} rounded`}
      onError={() => setHasError(true)}
    />
  );
}
