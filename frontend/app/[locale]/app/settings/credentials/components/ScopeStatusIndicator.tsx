"use client";

import * as React from "react";
import { ShieldCheck } from "lucide-react";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { useTranslations } from "next-intl";
import { normalizeScopes } from "@/lib/credentials/normalizeScopes";

interface ScopeStatusIndicatorProps {
  /** Credential auth type - only renders for exact-case 'OAuth2'. */
  credentialType: string | undefined | null;
  /** Granted scopes captured at OAuth callback time. */
  scopes: string[] | undefined | null;
}

/**
 * V166: lightweight badge + tooltip showing the granted OAuth scopes for an
 * OAuth2 credential. Helps users self-diagnose ("did I grant gmail.send?")
 * without leaving the credentials list.
 *
 * <p>This is intentionally minimal - the deep "missing scopes for tool X"
 * detection lives in {@code MissingScopesBanner} inside the workflow inspector,
 * where the user has direct context about which tool they're configuring.
 */
export function ScopeStatusIndicator({ credentialType, scopes }: ScopeStatusIndicatorProps) {
  const t = useTranslations("credentials.scopeStatus");

  // Exact-case match - CredentialType enum is 'OAuth2'.
  if (credentialType !== "OAuth2") return null;
  // Flatten any single-element comma/whitespace-blob into individual scopes
  // before counting + listing. See {@link normalizeScopes} for the why.
  const granted = normalizeScopes(scopes);
  if (granted.length === 0) return null;

  return (
    <TooltipProvider delayDuration={200}>
      <Tooltip>
        <TooltipTrigger asChild>
          <span
            className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-md text-[11px]
                       bg-emerald-50 dark:bg-emerald-950/40
                       text-emerald-700 dark:text-emerald-300
                       border border-emerald-200 dark:border-emerald-800/60
                       cursor-default"
            aria-label={t("badge", { count: granted.length })}
          >
            <ShieldCheck className="h-3 w-3" />
            {t("badge", { count: granted.length })}
          </span>
        </TooltipTrigger>
        <TooltipContent side="bottom" align="start" className="max-w-md">
          <div className="space-y-1">
            <div className="font-medium text-xs">{t("tooltipHeader")}</div>
            <ul className="space-y-0.5 font-mono text-[11px] break-all">
              {granted.map((scope) => (
                <li key={scope}>{scope}</li>
              ))}
            </ul>
          </div>
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}
