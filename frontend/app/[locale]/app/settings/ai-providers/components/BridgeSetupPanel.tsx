"use client";

import React, { useState } from "react";
import { Check, Copy, Terminal, CheckCircle2, XCircle, AlertTriangle } from "lucide-react";
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from "@/components/ui/button";
import { credentialService } from "@/lib/api/orchestrator/credential.service";
import type { BridgeStatusResponse } from "@/lib/api/orchestrator/types";
import { cn } from "@/lib/utils";

interface BridgeSetupPanelProps {
  cli: "claudeCode" | "codex" | "geminiCli" | "mistralVibe";
  t: (key: string) => string;
}

const CLI_ICON_MAP: Record<string, { src: string; alt: string }> = {
  claudeCode: { src: '/icons/services/claude-code.svg', alt: 'Claude Code' },
  codex: { src: '/icons/services/codex.svg', alt: 'Codex' },
  geminiCli: { src: '/icons/services/gemini-cli.svg', alt: 'Gemini CLI' },
  mistralVibe: { src: '/icons/services/mistral-vibe.svg', alt: 'Mistral Vibe' },
};

function CopyableCommand({ command, t }: { command: string; t: (key: string) => string }) {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    await navigator.clipboard.writeText(command);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="flex items-center gap-2 rounded-lg bg-theme-primary border border-theme px-3 py-2 font-mono text-sm">
      <code className="flex-1 text-theme-primary truncate">{command}</code>
      <button
        type="button"
        onClick={handleCopy}
        className="flex-shrink-0 text-theme-secondary hover:text-theme-primary transition-colors"
        title={copied ? t("bridge.copied") : t("bridge.copy")}
      >
        {copied ? (
          <Check className="w-3.5 h-3.5 text-emerald-500" />
        ) : (
          <Copy className="w-3.5 h-3.5" />
        )}
      </button>
    </div>
  );
}

export default function BridgeSetupPanel({ cli, t }: BridgeSetupPanelProps) {
  const [checking, setChecking] = useState(false);
  const [status, setStatus] = useState<BridgeStatusResponse | null>(null);

  // Reset status when switching between CLIs
  React.useEffect(() => {
    setStatus(null);
  }, [cli]);

  const handleVerify = async () => {
    setChecking(true);
    setStatus(null);
    try {
      const result = await credentialService.getBridgeStatus({ cli, force: true });
      setStatus(result);
    } catch {
      setStatus({ connected: false, bridgeReachable: false, error: "Failed to reach bridge" });
    } finally {
      setChecking(false);
    }
  };

  const prefix = `bridge.${cli}`;

  const steps = [
    { title: t(`${prefix}.step1Title`), command: t(`${prefix}.step1Command`) },
    { title: t(`${prefix}.step2Title`), command: t(`${prefix}.step2Command`) },
    { title: t(`${prefix}.step3Title`), command: t(`${prefix}.step3Command`) },
  ];

  return (
    <div className="rounded-xl border border-theme bg-theme-secondary/50 p-6 space-y-6">
      {/* Header */}
      <div className="flex items-center gap-3">
        <div className="w-10 h-10 bg-theme-tertiary rounded-lg flex items-center justify-center">
          <img
            src={CLI_ICON_MAP[cli]?.src}
            alt={CLI_ICON_MAP[cli]?.alt}
            className="w-5 h-5"
            onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }}
          />
        </div>
        <div>
          <h3 className="text-sm font-semibold text-theme-primary">{t(`${prefix}.title`)}</h3>
          <p className="text-sm text-theme-secondary">{t(`${prefix}.description`)}</p>
        </div>
      </div>

      {/* Setup steps */}
      <div className="space-y-4">
        {steps.map((step, index) => (
          <div key={index} className="space-y-2">
            <div className="flex items-center gap-2">
              <span className="flex-shrink-0 w-6 h-6 rounded-full bg-theme-tertiary flex items-center justify-center text-xs font-medium text-theme-primary">
                {index + 1}
              </span>
              <span className="text-sm font-medium text-theme-primary">{step.title}</span>
            </div>
            <div className="ml-8">
              <CopyableCommand command={step.command} t={t} />
            </div>
          </div>
        ))}
      </div>

      {/* Verify connection */}
      <div className="flex items-center justify-end gap-2">
        {status && (() => {
          const reachable = status.bridgeReachable !== false;
          const installed = !!status.cli?.installed;
          // `!== false`: only an EXPLICIT authenticated=false means "login required".
          // A bridge too old to report it (undefined) keeps the prior behavior so we
          // never regress an existing install into a permanent false "login required".
          const authed = status.cli?.authenticated !== false;
          // "Connected" requires auth too: an installed-but-not-logged-in CLI would
          // still fail at run time with "please log in", so showing it green lied.
          const ok = reachable && installed && authed;
          const ver = status.cli?.version ? ` · v${status.cli.version}` : "";
          let label: string;
          let tone: "ok" | "warn" | "bad";
          if (!reachable) {
            label = t("bridge.notConnected");
            tone = "bad";
          } else if (!installed) {
            label = status.cli?.error
              ? `${t("bridge.notConnected")} · ${status.cli.error}`
              : t("bridge.notConnected");
            tone = "bad";
          } else if (!authed) {
            label = `${t("bridge.loginRequired")}${ver}`;
            tone = "warn";
          } else {
            label = `${t("bridge.connected")}${ver}`;
            tone = "ok";
          }
          return (
            <div
              className={cn(
                "inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium max-w-[24rem] truncate",
                tone === "ok"
                  ? "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400"
                  : tone === "warn"
                  ? "bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400"
                  : "bg-theme-tertiary text-theme-secondary"
              )}
              title={label}
            >
              {tone === "ok" ? (
                <CheckCircle2 className="w-3 h-3 flex-shrink-0" />
              ) : tone === "warn" ? (
                <AlertTriangle className="w-3 h-3 flex-shrink-0" />
              ) : (
                <XCircle className="w-3 h-3 flex-shrink-0" />
              )}
              <span className="truncate">{label}</span>
            </div>
          );
        })()}
        <Button
          onClick={handleVerify}
          disabled={checking}
          size="sm"
          className="h-8 px-3"
        >
          {checking ? (
            <LoadingSpinner size="xs" className="mr-1.5" />
          ) : (
            <Terminal className="w-3.5 h-3.5 mr-1.5" />
          )}
          {checking ? t("bridge.checking") : t("bridge.verifyConnection")}
        </Button>
      </div>
    </div>
  );
}
