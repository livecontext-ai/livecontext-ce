"use client";

import React from "react";
import { cn } from "@/lib/utils";

/** Per-resource access level shared by every restrictable resource type in the member
 *  access modal: 'full' (no restriction) | 'read' (read-only) | 'deny' (fully blocked). */
export type AccessLevel = "full" | "read" | "deny";

interface ResourceAccessTriStateProps {
  level: AccessLevel;
  onSetLevel: (level: AccessLevel) => void;
  fullLabel: string;
  readLabel: string;
  denyLabel: string;
}

/**
 * Segmented 3-button control (Full access / Read-only / No access) used per resource row.
 * Shared by FileAccessSection and the non-file resource rows in MemberAccessModal so every
 * resource type exposes the SAME tri-state. The READ level maps to a write-blocked
 * (read-only) permission backend-side; DENY is a full block; FULL clears the restriction.
 */
export default function ResourceAccessTriState({
  level,
  onSetLevel,
  fullLabel,
  readLabel,
  denyLabel,
}: ResourceAccessTriStateProps) {
  return (
    <div className="flex items-center gap-0.5 rounded-lg border border-theme p-0.5 shrink-0">
      {(["full", "read", "deny"] as const).map((lvl) => {
        const active = level === lvl;
        const lbl = lvl === "full" ? fullLabel : lvl === "read" ? readLabel : denyLabel;
        return (
          <button
            key={lvl}
            type="button"
            aria-pressed={active}
            onClick={() => onSetLevel(lvl)}
            className={cn(
              "px-2 py-0.5 text-xs rounded-md transition-colors",
              active
                ? "bg-[var(--accent-primary)] text-[var(--accent-foreground)]"
                : "text-theme-secondary hover:text-theme-primary",
            )}
          >
            {lbl}
          </button>
        );
      })}
    </div>
  );
}
