'use client';

import React, { type ComponentType, type SVGProps } from 'react';

interface PageHeaderProps {
  // Any SVG icon component that accepts a className (lucide icons + our own
  // inline icons like McpIcon). Only `className` is used below, so the prop
  // must not be artificially narrowed to LucideIcon.
  icon: ComponentType<SVGProps<SVGSVGElement>>;
  // ReactNode so settings pages can decorate the title with status pills
  // (PR19: workspace-scope badge alongside the page title). Strings still
  // work - React renders them as text nodes.
  title: React.ReactNode;
  subtitle?: string;
  iconClassName?: string;
}

/**
 * Consistent header for settings pages.
 * Renders immediately without auth dependency.
 */
export function PageHeader({
  icon: Icon,
  title,
  subtitle,
  iconClassName = "w-5 h-5 text-theme-primary"
}: PageHeaderProps) {
  return (
    <div className="flex items-center gap-3">
      <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
        <Icon className={iconClassName} />
      </div>
      <div>
        <h1 className="text-lg font-semibold text-theme-primary">{title}</h1>
        {subtitle && (
          <p className="text-sm text-theme-secondary">{subtitle}</p>
        )}
      </div>
    </div>
  );
}

export default PageHeader;
