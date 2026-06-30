'use client';

import React from "react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import {
  CheckCircle2,
  XCircle,
  Loader2,
  AlertCircle,
  Clock,
  PauseCircle,
} from "lucide-react";
import { useTranslations } from 'next-intl';

export type StatusType =
  | "completed"
  | "failed"
  | "running"
  | "pending"
  | "skipped"
  | "cancelled"
  | "timeout"
  | "active"
  | "inactive"
  | "draft"
  | "archived"
  | "starting"
  | "partial_success";

interface StatusBadgeProps {
  status: StatusType;
  label?: string;
  showIcon?: boolean;
  className?: string;
  variant?: 'default' | 'noBackground';
}

// Configuration pour les styles et icônes (sans labels - ils viennent des traductions)
const statusStylesNoBg: Record<StatusType, { className: string; icon: React.ReactNode }> = {
  completed: { className: "text-emerald-700 dark:text-emerald-300", icon: <CheckCircle2 className="h-4 w-4" /> },
  failed: { className: "text-red-700 dark:text-red-300", icon: <XCircle className="h-4 w-4" /> },
  running: { className: "text-blue-700 dark:text-blue-300", icon: <Loader2 className="h-4 w-4 animate-spin" /> },
  pending: { className: "text-amber-700 dark:text-amber-200", icon: <Clock className="h-4 w-4" /> },
  skipped: { className: "text-slate-700 dark:text-gray-300", icon: <AlertCircle className="h-4 w-4" /> },
  cancelled: { className: "text-slate-700 dark:text-gray-300", icon: <XCircle className="h-4 w-4" /> },
  timeout: { className: "text-orange-700 dark:text-orange-300", icon: <Clock className="h-4 w-4" /> },
  active: { className: "text-emerald-700 dark:text-emerald-300", icon: <CheckCircle2 className="h-4 w-4" /> },
  inactive: { className: "text-slate-700 dark:text-gray-300", icon: <PauseCircle className="h-4 w-4" /> },
  draft: { className: "text-amber-700 dark:text-amber-200", icon: <AlertCircle className="h-4 w-4" /> },
  archived: { className: "text-slate-700 dark:text-gray-300", icon: <PauseCircle className="h-4 w-4" /> },
  starting: { className: "text-blue-700 dark:text-blue-300", icon: <Loader2 className="h-4 w-4 animate-spin" /> },
  partial_success: { className: "text-amber-700 dark:text-amber-200", icon: <AlertCircle className="h-4 w-4" /> },
};

const statusStylesWithBg: Record<StatusType, { className: string; icon: React.ReactNode }> = {
  completed: { className: "border-emerald-600/60 bg-emerald-50 dark:bg-emerald-500/15 text-emerald-700 dark:text-emerald-300", icon: <CheckCircle2 className="h-3 w-3" /> },
  failed: { className: "border-red-600/60 bg-red-50 dark:bg-red-500/15 text-red-700 dark:text-red-300", icon: <XCircle className="h-3 w-3" /> },
  running: { className: "border-blue-600/60 bg-blue-50 dark:bg-blue-500/15 text-blue-700 dark:text-blue-300", icon: <Loader2 className="h-3 w-3 animate-spin" /> },
  pending: { className: "border-amber-600/60 bg-amber-50 dark:bg-amber-400/15 text-amber-700 dark:text-amber-200", icon: <Clock className="h-3 w-3" /> },
  skipped: { className: "border-slate-500/60 bg-slate-100 dark:bg-gray-500/15 text-slate-700 dark:text-gray-300", icon: <AlertCircle className="h-3 w-3" /> },
  cancelled: { className: "border-slate-500/60 bg-slate-100 dark:bg-gray-500/15 text-slate-700 dark:text-gray-300", icon: <XCircle className="h-3 w-3" /> },
  timeout: { className: "border-orange-600/60 bg-orange-50 dark:bg-orange-500/15 text-orange-700 dark:text-orange-300", icon: <Clock className="h-3 w-3" /> },
  active: { className: "border-emerald-600/60 bg-emerald-50 dark:bg-emerald-500/15 text-emerald-700 dark:text-emerald-300", icon: <CheckCircle2 className="h-3 w-3" /> },
  inactive: { className: "border-slate-500/60 bg-slate-100 dark:bg-gray-500/15 text-slate-700 dark:text-gray-300", icon: <PauseCircle className="h-3 w-3" /> },
  draft: { className: "border-amber-600/60 bg-amber-50 dark:bg-amber-400/15 text-amber-700 dark:text-amber-200", icon: <AlertCircle className="h-3 w-3" /> },
  archived: { className: "border-slate-500/60 bg-slate-100 dark:bg-gray-500/15 text-slate-700 dark:text-gray-300", icon: <PauseCircle className="h-3 w-3" /> },
  starting: { className: "border-blue-600/60 bg-blue-50 dark:bg-blue-500/15 text-blue-700 dark:text-blue-300", icon: <Loader2 className="h-3 w-3 animate-spin" /> },
  partial_success: { className: "border-amber-600/60 bg-amber-50 dark:bg-amber-400/15 text-amber-700 dark:text-amber-200", icon: <AlertCircle className="h-3 w-3" /> },
};

export function StatusBadge({
  status,
  label,
  showIcon = true,
  className,
  variant = 'default',
}: StatusBadgeProps) {
  const t = useTranslations('status');
  const styleNoBg = statusStylesNoBg[status] || statusStylesNoBg.pending;
  const styleWithBg = statusStylesWithBg[status] || statusStylesWithBg.pending;

  // Use provided label or get from translations
  const displayLabel = label || t(status);

  if (variant === 'noBackground') {
    return (
      <span
        className={cn(
          "inline-flex items-center gap-1",
          styleNoBg.className,
          className
        )}
        title={displayLabel}
      >
        {showIcon && styleNoBg.icon}
        <span className="text-sm font-medium">{displayLabel}</span>
      </span>
    );
  }

  // Variant par défaut avec Badge (background et border)
  return (
    <Badge
      variant="outline"
      className={cn(
        "border px-2 py-1 text-sm font-medium inline-flex items-center gap-1",
        styleWithBg.className,
        className
      )}
      title={displayLabel}
    >
      {showIcon && styleWithBg.icon}
    </Badge>
  );
}

// Helper function to map backend status to StatusType
export function mapBackendStatusToStatusType(
  status: string | undefined | null
): StatusType {
  if (!status) return "pending";

  const normalizedStatus = status.toLowerCase().trim();

  // Workflow statuses
  if (normalizedStatus === "active") return "active";
  if (normalizedStatus === "inactive") return "inactive";
  if (normalizedStatus === "draft") return "draft";
  if (normalizedStatus === "archived") return "archived";
  if (normalizedStatus === "starting") return "starting";
  if (normalizedStatus === "running") return "running";
  if (normalizedStatus === "completed") return "completed";
  if (normalizedStatus === "failed") return "failed";

  // Execution statuses
  if (normalizedStatus === "success") return "completed";
  if (normalizedStatus === "pending") return "pending";
  if (normalizedStatus === "skipped") return "skipped";
  if (normalizedStatus === "cancelled") return "cancelled";
  if (normalizedStatus === "timeout") return "timeout";
  if (normalizedStatus === "error") return "failed";
  if (normalizedStatus === "partial_success") return "partial_success";

  // Default fallback
  return "pending";
}

