"use client";

import React from "react";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { ExternalLink, Settings } from "lucide-react";
import { CredentialTemplate } from "@/lib/api/orchestrator";
import { useTranslations } from "next-intl";

interface CredentialCardProps {
  template: CredentialTemplate;
  onConfigure: (template: CredentialTemplate) => void;
}

/**
 * Get badge color based on auth type
 */
function getAuthTypeBadgeColor(authType: string): string {
  switch (authType?.toLowerCase()) {
    case "oauth2":
      return "bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400";
    case "apikey":
    case "api_key":
      return "bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400";
    case "basicauth":
    case "basic_auth":
      return "bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400";
    case "webhook":
      return "bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-400";
    default:
      return "bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-400";
  }
}

/**
 * Format auth type for display
 */
function formatAuthType(authType: string): string {
  if (!authType) return "Unknown";

  const formatted = authType
    .replace(/_/g, " ")
    .replace(/([a-z])([A-Z])/g, "$1 $2");

  return formatted.charAt(0).toUpperCase() + formatted.slice(1);
}

export function CredentialCard({ template, onConfigure }: CredentialCardProps) {
  const t = useTranslations('credentials.card');
  return (
    <Card className="group bg-theme-tertiary hover:border-[var(--accent-primary)]/50 transition-colors">
      <CardContent className="p-4 space-y-3">
        {/* Header */}
        <div className="flex items-start gap-3">
          {/* Icon */}
          <div className="flex-shrink-0">
            {template.icon_url ? (
              <img
                src={template.icon_url}
                alt=""
                className="h-10 w-10 rounded-lg object-contain bg-white p-1"
                onError={(e) => {
                  (e.target as HTMLImageElement).style.display = "none";
                  (e.target as HTMLImageElement).parentElement!.innerHTML = `
                    <div class="h-10 w-10 rounded-lg bg-theme-tertiary flex items-center justify-center">
                      <span class="text-lg font-bold text-theme-secondary">${template.display_name?.charAt(0) || "?"}</span>
                    </div>
                  `;
                }}
              />
            ) : (
              <div className="h-10 w-10 rounded-lg bg-theme-tertiary flex items-center justify-center">
                <span className="text-lg font-bold text-theme-secondary">
                  {template.display_name?.charAt(0) || "?"}
                </span>
              </div>
            )}
          </div>

          {/* Title & Badge */}
          <div className="flex-1 min-w-0">
            <h3 className="text-sm font-semibold text-theme-primary truncate">
              {template.display_name || template.credential_name}
            </h3>
            <Badge
              className={`mt-1 text-xs px-1.5 py-0.5 ${getAuthTypeBadgeColor(
                template.auth_type
              )}`}
            >
              {formatAuthType(template.auth_type)}
            </Badge>
          </div>
        </div>

        {/* Description */}
        {template.description && (
          <p className="text-sm text-theme-secondary line-clamp-2">
            {template.description}
          </p>
        )}

        {/* Actions */}
        <div className="flex items-center gap-2 pt-1">
          <Button
            size="sm"
            variant="outline"
            onClick={() => onConfigure(template)}
            className="flex-1 h-8 px-3"
          >
            <Settings className="mr-1 h-4 w-4" />
            {t('configure')}
          </Button>
          {template.documentation_url && (
            <Button
              size="icon"
              variant="ghost"
              asChild
              className="w-8 h-8"
            >
              <a
                href={template.documentation_url}
                target="_blank"
                rel="noopener noreferrer"
              >
                <ExternalLink className="h-4 w-4" />
              </a>
            </Button>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
