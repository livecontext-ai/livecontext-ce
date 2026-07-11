"use client";

import React from "react";
import { cn } from "../../lib/utils";
import { Badge } from "./badge";

interface ToggleOption {
  value: string;
  label: React.ReactNode;
  badge?: string;
  icon?: React.ReactNode;
  className?: string;
}

export interface ToggleGroupProps {
  value: string;
  onValueChange: (value: string) => void;
  options: ToggleOption[];
  hasBorder?: boolean;
  disabled?: boolean;
  className?: string;
  variant?: "grid" | "pill";
  activeClassName?: string;
  inactiveClassName?: string;
}

export const ToggleGroup: React.FC<ToggleGroupProps> = ({
  value,
  onValueChange,
  options,
  hasBorder = true,
  disabled = false,
  className,
  variant = "grid",
  activeClassName,
  inactiveClassName,
}) => {
  const isPill = variant === "pill";

  const containerClasses = cn(
      hasBorder ? "border border-theme transition-colors duration-150" : "transition-colors duration-150",
    isPill
      ? "bg-theme-tertiary rounded-2xl p-1 flex items-center gap-1"
      : "bg-theme-tertiary rounded-xl p-1.5",
    className,
  );

  const wrapperClasses = isPill
    ? "flex items-center gap-1"
    : "grid grid-cols-2 gap-2";

  const baseButtonClasses = isPill
    ? "px-3 h-9 rounded-xl text-sm font-medium transition-colors duration-150 cursor-pointer flex items-center justify-center gap-1 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-theme-primary/60"
    : "py-2 px-6 rounded-lg font-medium text-sm transition-colors duration-150 cursor-pointer flex items-center justify-center gap-1 ring-offset-background focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)] focus:ring-offset-1";

  const defaultActiveClass = isPill
    ? "bg-[var(--bg-primary)] text-[var(--text-primary)] shadow-sm"
    : "bg-[var(--bg-primary)] text-[var(--text-primary)] shadow-sm";

  const defaultInactiveClass = isPill
    ? "text-theme-secondary hover:text-theme-primary hover:bg-theme-primary/10"
    : "text-[var(--text-secondary)] hover:text-[var(--text-primary)]";

  const resolvedActiveClass = activeClassName ?? defaultActiveClass;
  const resolvedInactiveClass = inactiveClassName ?? defaultInactiveClass;

  return (
    <div className={containerClasses}>
      <div className={wrapperClasses}>
        {options.map((option) => (
          <button
            key={option.value}
            type="button"
            disabled={disabled}
            onClick={() => !disabled && onValueChange(option.value)}
            className={cn(
              baseButtonClasses,
              value === option.value
                ? resolvedActiveClass
                : resolvedInactiveClass,
              disabled && "cursor-not-allowed opacity-50",
              option.className,
            )}
          >
            <div
              className={cn(
                "flex items-center justify-center gap-1",
                !option.icon && "gap-0",
              )}
            >
              {option.icon && (
                <span className="inline-flex items-center justify-center">
                  {option.icon}
                </span>
              )}
              {option.label && (
                <span className={cn(option.icon && "flex items-center gap-1")}>
                  {option.label}
                </span>
              )}
              {option.badge && (
                <Badge
                  variant="outline"
                  className="bg-green-100 dark:bg-green-800 border-green-200 dark:border-green-700 text-green-800 dark:text-green-100"
                >
                  {option.badge}
                </Badge>
              )}
            </div>
          </button>
        ))}
      </div>
    </div>
  );
};
