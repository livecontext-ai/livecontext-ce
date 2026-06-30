"use client";

import React, { useState } from "react";
import { createPortal } from "react-dom";
import { Plus, Check } from "lucide-react";
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import type { ModelConfigOverrideInput } from "@/lib/api/model-config.service";
import { getEffectiveDefaultProvider, useModels } from "@/hooks/useModels";
import { getProviderDisplayName } from "@/lib/ai-providers/providerIcons";

interface AddModelDialogProps {
  onSave: (input: ModelConfigOverrideInput) => Promise<void>;
  onClose: () => void;
  t: (key: string) => string;
}

export default function AddModelDialog({ onSave, onClose, t }: AddModelDialogProps) {
  const { providers: catalogProviders } = useModels();
  const [provider, setProvider] = useState(() => getEffectiveDefaultProvider() ?? '');
  const [modelId, setModelId] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [tier, setTier] = useState("mid");
  const [priceInput, setPriceInput] = useState("");
  const [priceOutput, setPriceOutput] = useState("");
  const [rateLimitTpm, setRateLimitTpm] = useState("");
  const [rateLimitRpm, setRateLimitRpm] = useState("");
  // Per-tenant rate limits: dormant under the GLOBAL rate-limit strategy (stored but
  // not enforced) and hidden from the dialog. The value stays "" so the create payload
  // omits them and the backend applies its own per-tenant defaults; re-enabling is just
  // re-adding a setter + the two inputs. Backend contract untouched.
  const [rateLimitTpmPerTenant] = useState("");
  const [rateLimitRpmPerTenant] = useState("");
  const [saving, setSaving] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!modelId.trim()) return;

    setSaving(true);
    try {
      await onSave({
        provider,
        modelId: modelId.trim(),
        displayName: displayName.trim() || undefined,
        tier,
        enabled: true,
        recommended: false,
        isCustom: true,
        priceInput: priceInput ? parseFloat(priceInput) : undefined,
        priceOutput: priceOutput ? parseFloat(priceOutput) : undefined,
        rateLimitTpm: rateLimitTpm ? parseInt(rateLimitTpm, 10) : undefined,
        rateLimitRpm: rateLimitRpm ? parseInt(rateLimitRpm, 10) : undefined,
        rateLimitTpmPerTenant: rateLimitTpmPerTenant ? parseInt(rateLimitTpmPerTenant, 10) : undefined,
        rateLimitRpmPerTenant: rateLimitRpmPerTenant ? parseInt(rateLimitRpmPerTenant, 10) : undefined,
      });
    } finally {
      setSaving(false);
    }
  };

  const canSave = modelId.trim().length > 0 && !saving;

  const modalContent = (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={onClose}
    >
      <div
        className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="px-8 pt-8 pb-4">
          <div className="text-center mb-2">
            <div className="flex justify-center mb-4">
              <div className="w-14 h-14 bg-theme-secondary rounded-full flex items-center justify-center">
                <Plus className="w-7 h-7 text-theme-primary" />
              </div>
            </div>
            <h3 className="text-xl font-semibold text-theme-primary">
              {t("modelConfig.addModel")}
            </h3>
          </div>
        </div>

        {/* Content */}
        <form onSubmit={handleSubmit} className="flex-1 overflow-y-auto px-8 pb-4">
          <div className="space-y-5">
            {/* Provider */}
            <div>
              <label className="block text-sm font-medium text-theme-primary mb-2">
                {t("modelConfig.addDialog.provider")} *
              </label>
              <Select value={provider} onValueChange={setProvider}>
                <SelectTrigger className="w-full rounded-xl">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {catalogProviders.map((p) => (
                    <SelectItem key={p.name} value={p.name}>{getProviderDisplayName(p.name)}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            {/* Model ID */}
            <div>
              <label className="block text-sm font-medium text-theme-primary mb-2">
                {t("modelConfig.addDialog.modelId")} *
              </label>
              <input
                type="text"
                value={modelId}
                onChange={(e) => setModelId(e.target.value)}
                placeholder="gpt-5.4-turbo"
                className="w-full rounded-xl border border-theme bg-[var(--bg-primary)] px-4 py-3 text-sm text-[var(--text-primary)] placeholder:text-[var(--text-secondary)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-0"
                required
              />
            </div>

            {/* Display Name */}
            <div>
              <label className="block text-sm font-medium text-theme-primary mb-2">
                {t("modelConfig.addDialog.displayName")}
              </label>
              <input
                type="text"
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                placeholder={t("modelConfig.addDialog.displayNamePlaceholder")}
                className="w-full rounded-xl border border-theme bg-[var(--bg-primary)] px-4 py-3 text-sm text-[var(--text-primary)] placeholder:text-[var(--text-secondary)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-0"
              />
            </div>

            {/* Tier */}
            <div>
              <label className="block text-sm font-medium text-theme-primary mb-2">
                {t("modelConfig.columns.tier")}
              </label>
              <Select value={tier} onValueChange={setTier}>
                <SelectTrigger className="w-full rounded-xl">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="top">Top</SelectItem>
                  <SelectItem value="high">High</SelectItem>
                  <SelectItem value="mid">Mid</SelectItem>
                  <SelectItem value="budget">Budget</SelectItem>
                </SelectContent>
              </Select>
            </div>

            {/* Pricing */}
            <div>
              <label className="block text-sm font-medium text-theme-primary mb-2">
                {t("modelConfig.columns.pricing")}
              </label>
              <div className="grid grid-cols-2 gap-3">
                <input
                  type="number"
                  value={priceInput}
                  onChange={(e) => setPriceInput(e.target.value)}
                  placeholder="Input ($/1M)"
                  step="0.01"
                  min="0"
                  className="w-full rounded-xl border border-theme bg-[var(--bg-primary)] px-4 py-3 text-sm text-[var(--text-primary)] placeholder:text-[var(--text-secondary)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-0"
                />
                <input
                  type="number"
                  value={priceOutput}
                  onChange={(e) => setPriceOutput(e.target.value)}
                  placeholder="Output ($/1M)"
                  step="0.01"
                  min="0"
                  className="w-full rounded-xl border border-theme bg-[var(--bg-primary)] px-4 py-3 text-sm text-[var(--text-primary)] placeholder:text-[var(--text-secondary)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-0"
                />
              </div>
            </div>

            {/* Rate Limits */}
            <div>
              <label className="block text-sm font-medium text-theme-primary mb-2">
                {t("modelConfig.addDialog.rateLimits")}
              </label>
              <p className="text-sm text-theme-secondary mb-2">
                {t("modelConfig.addDialog.rateLimitsHint")}
              </p>
              <div className="grid grid-cols-2 gap-3">
                <input
                  type="number"
                  value={rateLimitTpm}
                  onChange={(e) => setRateLimitTpm(e.target.value)}
                  placeholder={t("modelConfig.rateLimits.tpmGlobal")}
                  step="1000"
                  min="0"
                  className="w-full rounded-xl border border-theme bg-[var(--bg-primary)] px-4 py-3 text-sm text-[var(--text-primary)] placeholder:text-[var(--text-secondary)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-0"
                />
                <input
                  type="number"
                  value={rateLimitRpm}
                  onChange={(e) => setRateLimitRpm(e.target.value)}
                  placeholder={t("modelConfig.rateLimits.rpmGlobal")}
                  step="10"
                  min="0"
                  className="w-full rounded-xl border border-theme bg-[var(--bg-primary)] px-4 py-3 text-sm text-[var(--text-primary)] placeholder:text-[var(--text-secondary)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-0"
                />
                {/* Per-tenant rate-limit inputs are intentionally hidden while the
                    platform runs the GLOBAL rate-limit strategy (per-tenant caps are
                    dormant data, not enforced - see rate-limit.strategy). The state
                    and the create payload below stay wired so re-enabling is just
                    re-adding these two inputs; the backend contract is untouched. */}
              </div>
            </div>
          </div>
        </form>

        {/* Footer */}
        <div className="px-8 py-4 border-t border-theme flex justify-end gap-2">
          <Button variant="outline" onClick={onClose} disabled={saving}>
            {t("modelConfig.addDialog.cancel")}
          </Button>
          <Button onClick={handleSubmit} disabled={!canSave}>
            {saving ? (
              <>
                <LoadingSpinner size="xs" className="mr-2" />
                {t("modelConfig.addDialog.add")}
              </>
            ) : (
              <>
                <Check className="h-4 w-4 mr-2" />
                {t("modelConfig.addDialog.add")}
              </>
            )}
          </Button>
        </div>
      </div>
    </div>
  );

  return createPortal(modalContent, document.body);
}
