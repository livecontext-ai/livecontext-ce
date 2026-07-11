"use client";

import React, { useCallback, useEffect, useState } from "react";
import { useAuthGuard } from "@/hooks/useAuthGuard";
import { useAuth } from "@/lib/providers/smart-providers";
import { useTranslations } from "next-intl";
import { Check, Copy, User } from "lucide-react";
import { McpIcon } from "@/components/icons/McpIcon";
import { Button } from "@/components/ui/button";
import { PageHeader } from "@/components/settings";
import Toast, { useToast } from "@/components/Toast";
import {
  mcpServerService,
  type McpConnectionInfo,
} from "@/lib/api/services/mcp-server.service";
import { McpKeysManager } from "./McpKeysManager";

/**
 * Settings > MCP Server: connect external MCP clients (Claude Code, Cursor, ...)
 * to the LiveContext MCP endpoint with a personal lc_live_ API key.
 */
export default function McpServerPage() {
  const { isAuthenticated, isAuthChecking } = useAuthGuard();
  const { loginWithRedirect } = useAuth();
  const t = useTranslations("mcpServer");
  const tSettings = useTranslations("settings");
  const { toasts, addToast, removeToast } = useToast();

  const [connection, setConnection] = useState<McpConnectionInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [copiedKey, setCopiedKey] = useState<string | null>(null);

  const fetchData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setConnection(await mcpServerService.getConnection());
    } catch {
      setError(t("errors.fetchFailed"));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    if (isAuthenticated) {
      void fetchData();
    }
  }, [isAuthenticated, fetchData]);

  const copyValue = async (key: string, value: string) => {
    if (!value) return;
    await navigator.clipboard.writeText(value);
    setCopiedKey(key);
    setTimeout(() => setCopiedKey((c) => (c === key ? null : c)), 1500);
  };

  const renderCopyButton = (key: string, value: string) => (
    <Button
      variant="ghost"
      size="sm"
      className="h-7 px-2 flex-shrink-0"
      onClick={() => void copyValue(key, value)}
      aria-label={t("copy")}
    >
      {copiedKey === key ? (
        <Check className="h-3.5 w-3.5 text-green-600" />
      ) : (
        <Copy className="h-3.5 w-3.5" />
      )}
    </Button>
  );

  const mcpUrl = connection?.url ?? "";
  const keyForSnippet = t("snippet.keyPlaceholder");
  const jsonSnippet = `{
  "mcpServers": {
    "livecontext": {
      "type": "http",
      "url": "${mcpUrl}",
      "headers": {
        "X-API-Key": "${keyForSnippet}"
      }
    }
  }
}`;
  const claudeCodeSnippet =
    `claude mcp add --transport http livecontext ${mcpUrl} --header "X-API-Key: ${keyForSnippet}"`;
  const codexSnippet = `[mcp_servers.livecontext]
url = "${mcpUrl}"

[mcp_servers.livecontext.http_headers]
"X-API-Key" = "${keyForSnippet}"`;

  if (isAuthChecking || (isAuthenticated && loading)) {
    return (
      <div className="space-y-4 animate-pulse">
        <div className="h-10 w-64 rounded-lg bg-theme-secondary" />
        <div className="h-40 rounded-xl bg-theme-secondary" />
        <div className="h-40 rounded-xl bg-theme-secondary" />
      </div>
    );
  }

  if (!isAuthenticated) {
    return (
      <div className="min-h-[300px] flex items-center justify-center">
        <div className="text-center">
          <h1 className="text-2xl font-bold text-theme-primary mb-4">
            {tSettings("unauthorized")}
          </h1>
          <p className="text-theme-secondary mb-6">{tSettings("mustBeLoggedIn")}</p>
          <Button onClick={() => loginWithRedirect()} size="sm" className="h-8 px-3">
            <User className="w-4 h-4 mr-1" />
            {tSettings("signIn")}
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <PageHeader icon={McpIcon} title={t("title")} subtitle={t("subtitle")} />

      {error && (
        <div className="rounded-lg bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 p-4 text-sm text-red-700 dark:text-red-300">
          {error}
        </div>
      )}

      {/* Endpoint */}
      <section className="rounded-xl border border-theme bg-theme-secondary/50 p-5 space-y-3">
        <h2 className="text-sm font-semibold text-theme-primary">{t("endpoint.title")}</h2>
        <p className="text-sm text-theme-secondary">{t("endpoint.description")}</p>
        <div className="flex items-center gap-2">
          <code className="flex-1 min-w-0 truncate rounded-lg bg-theme-tertiary px-3 py-2 text-sm font-mono text-theme-primary">
            {mcpUrl || "-"}
          </code>
          {mcpUrl ? renderCopyButton("url", mcpUrl) : null}
        </div>
        {connection && (
          <p className="text-xs text-theme-muted">
            {t("endpoint.toolCount", { count: connection.toolCount })}
          </p>
        )}
      </section>

      {/* API keys - the single key system (each key can be full-access or scoped to a
          set of tools). No separate legacy single-key section: one coherent list. */}
      <McpKeysManager
        availableScopes={connection?.availableScopes ?? []}
        onToast={(type, title, message) => addToast({ type, title, message })}
      />

      {/* Client configuration */}
      <section className="rounded-xl border border-theme bg-theme-secondary/50 p-5 space-y-4">
        <h2 className="text-sm font-semibold text-theme-primary">{t("snippet.title")}</h2>
        <p className="text-sm text-theme-secondary">{t("snippet.description")}</p>

        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <span className="text-xs font-medium text-theme-muted">{t("snippet.claudeCode")}</span>
            {renderCopyButton("claude", claudeCodeSnippet)}
          </div>
          <pre className="overflow-x-auto rounded-lg bg-theme-tertiary p-3 text-xs font-mono text-theme-primary whitespace-pre-wrap break-all">
            {claudeCodeSnippet}
          </pre>
        </div>

        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <span className="text-xs font-medium text-theme-muted">{t("snippet.jsonConfig")}</span>
            {renderCopyButton("json", jsonSnippet)}
          </div>
          <pre className="overflow-x-auto rounded-lg bg-theme-tertiary p-3 text-xs font-mono text-theme-primary">
            {jsonSnippet}
          </pre>
        </div>

        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <span className="text-xs font-medium text-theme-muted">{t("snippet.codex")}</span>
            {renderCopyButton("codex", codexSnippet)}
          </div>
          <pre className="overflow-x-auto rounded-lg bg-theme-tertiary p-3 text-xs font-mono text-theme-primary">
            {codexSnippet}
          </pre>
        </div>

        <p className="text-xs text-theme-muted">{t("snippet.placeholderHint")}</p>
      </section>

      {toasts.map((toast) => (
        <Toast key={toast.id} {...toast} onClose={removeToast} />
      ))}
    </div>
  );
}
