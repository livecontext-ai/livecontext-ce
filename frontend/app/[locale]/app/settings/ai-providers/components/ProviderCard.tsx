"use client";

import React, { useState } from "react";
import {
  Eye,
  EyeOff,
  Save,
  Trash2,
  ExternalLink,
  Check,
  Database,
  Server,
  AlertCircle,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import LoadingSpinner from "@/components/LoadingSpinner";
import { cn } from "@/lib/utils";
import type { LlmProviderStatus, LlmProviderDefinition } from "@/lib/api/orchestrator/types";

interface ProviderCardProps {
  definition: LlmProviderDefinition;
  status: LlmProviderStatus | undefined;
  onSave: (integrationName: string, apiKey: string) => Promise<void>;
  onDelete: (integrationName: string) => Promise<void>;
  t: (key: string, values?: Record<string, string>) => string;
}

export default function ProviderCard({ definition, status, onSave, onDelete, t }: ProviderCardProps) {
  const [apiKey, setApiKey] = useState("");
  const [showKey, setShowKey] = useState(false);
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [saved, setSaved] = useState(false);

  const source = status?.source ?? "none";
  const configured = status?.configured ?? false;
  const hasDbKey = status?.hasDbKey ?? false;

  const handleSave = async () => {
    if (!apiKey.trim()) return;
    setSaving(true);
    try {
      await onSave(definition.integrationName, apiKey.trim());
      setApiKey("");
      setShowKey(false);
      setSaved(true);
      setTimeout(() => setSaved(false), 2000);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    setDeleting(true);
    try {
      await onDelete(definition.integrationName);
    } finally {
      setDeleting(false);
    }
  };

  return (
    <div className="rounded-xl border border-theme bg-theme-secondary/50 p-5">
      <div className="flex items-start justify-between mb-4">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 bg-theme-tertiary rounded-lg flex items-center justify-center">
            <img
              src={`/icons/services/${definition.providerName}.svg`}
              alt={definition.displayName}
              className="w-6 h-6"
              onError={(e) => {
                (e.target as HTMLImageElement).style.display = "none";
              }}
            />
          </div>
          <div>
            <h3 className="text-sm font-semibold text-theme-primary">
              {definition.displayName}
            </h3>
            <a
              href={definition.docsUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="text-xs text-theme-secondary hover:text-theme-primary inline-flex items-center gap-1"
            >
              {t("getDocs")}
              <ExternalLink className="w-3 h-3" />
            </a>
          </div>
        </div>

        {/* Status badge */}
        <div className={cn(
          "inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium",
          source === "database" && "bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400",
          source === "environment" && "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400",
          source === "none" && "bg-theme-tertiary text-theme-secondary"
        )}>
          {source === "database" && <Database className="w-3 h-3" />}
          {source === "environment" && <Server className="w-3 h-3" />}
          {source === "none" && <AlertCircle className="w-3 h-3" />}
          {t(`source.${source}`)}
        </div>
      </div>

      {/* API Key input */}
      <div className="space-y-3">
        <div className="relative">
          <input
            type={showKey ? "text" : "password"}
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
            placeholder={hasDbKey ? t("updateKey") : definition.placeholder}
            className="w-full h-9 px-3 pr-9 text-sm rounded-lg border border-theme bg-theme-primary text-theme-primary placeholder:text-theme-secondary/60 focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)]/40"
            onKeyDown={(e) => e.key === "Enter" && handleSave()}
          />
          <button
            type="button"
            onClick={() => setShowKey(!showKey)}
            className="absolute right-2.5 top-1/2 -translate-y-1/2 text-theme-secondary hover:text-theme-primary"
          >
            {showKey ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
          </button>
        </div>

        <div className="flex items-center justify-end gap-2">
          <Button
            onClick={handleSave}
            disabled={!apiKey.trim() || saving}
            size="sm"
            variant={saved ? "outline" : "default"}
            className="h-8 px-3"
          >
            {saving ? (
              <LoadingSpinner size="sm" className="mr-1.5" />
            ) : saved ? (
              <Check className="w-3.5 h-3.5 mr-1.5" />
            ) : (
              <Save className="w-3.5 h-3.5 mr-1.5" />
            )}
            {saved ? t("saved") : t("save")}
          </Button>

          {hasDbKey && (
            <Button
              onClick={handleDelete}
              disabled={deleting}
              variant="outline"
              size="sm"
              className="h-8 px-3 text-red-600 hover:text-red-700 dark:text-red-400 dark:hover:text-red-300"
            >
              {deleting ? (
                <LoadingSpinner size="sm" className="mr-1.5" />
              ) : (
                <Trash2 className="w-3.5 h-3.5 mr-1.5" />
              )}
              {t("removeKey")}
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}
